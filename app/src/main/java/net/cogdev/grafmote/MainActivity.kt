package net.cogdev.grafmote

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Single-activity remote. Cold-start path is deliberately trivial: inflate the
 * cached layout, wire click listeners (taps queue until the socket opens —
 * buttons are live instantly and NEVER gated on connection state), then
 * connect + poll in the background.
 * No custom Application class, no ContentProviders, no DI, no Compose.
 *
 * Header shows each TV once (in the selector) with a two-state LED dot:
 * green = socket open (connected), red = anything else. TV-off and
 * unreachable are indistinguishable from the phone, so there is no third
 * state. Transient states (connecting / approval prompts) surface as brief
 * toasts on state transitions only — there is no persistent status text.
 * The LED is truthful but never gates input: every button always attempts
 * to send, kicking an immediate reconnect when the socket is down.
 */
class MainActivity : Activity(), TvConnection.Listener {

    private lateinit var store: TvStore
    private lateinit var conn: TvConnection
    private val ui = Handler(Looper.getMainLooper())

    private var tvs: MutableList<Tv> = mutableListOf()
    private var apps: MutableList<AppButton> = mutableListOf()
    private var current: Tv? = null
    private var power: String = "unknown"
    private var connState: TvConnection.State = TvConnection.State.IDLE
    private var connDetail: String = ""
    private var spinnerGuard = true
    private var lastSpinnerPos = -1

    private lateinit var tvSpinner: Spinner
    private lateinit var statusDot: View
    private lateinit var appsRow: LinearLayout
    private lateinit var appsScroll: HorizontalScrollView
    private lateinit var appsGrid: LinearLayout
    private lateinit var appsRowTop: LinearLayout
    private lateinit var appsRowBottom: LinearLayout

    private val poller = object : Runnable {
        override fun run() {
            current?.let { conn.refreshInfo(it) { p -> power = p } }
            ui.postDelayed(this, 15_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = TvStore(this)
        conn = TvConnection(store)
        conn.listener = this

        tvSpinner = findViewById(R.id.tvSpinner)
        statusDot = findViewById(R.id.statusDot)
        // Round 40: the whole pill is one touch target — taps on the LED,
        // the label, or the chevron all open the selector. The pill's
        // selector background gives the single consistent pressed state;
        // the spinner keeps its own touch handling for its own area.
        findViewById<View>(R.id.tvPill).setOnClickListener { tvSpinner.performClick() }
        appsRow = findViewById(R.id.appsRow)
        appsScroll = findViewById(R.id.appsScroll)
        appsGrid = findViewById(R.id.appsGrid)
        appsRowTop = findViewById(R.id.appsRowTop)
        appsRowBottom = findViewById(R.id.appsRowBottom)

        // D-pad ring
        key(R.id.btnUp, "UP")
        key(R.id.btnDown, "DOWN")
        key(R.id.btnLeft, "LEFT")
        key(R.id.btnRight, "RIGHT")
        key(R.id.btnOk, "ENTER")
        // Back/Home live inside the ring's dead space now.
        key(R.id.btnBack, "RETURN")
        key(R.id.btnHome, "HOME")
        // Bottom row: volume rocker, mute + play/pause, channel rocker.
        key(R.id.btnMute, "MUTE")
        key(R.id.btnVolUp, "VOLUP")
        key(R.id.btnVolDown, "VOLDOWN")
        key(R.id.btnChUp, "CHUP")
        key(R.id.btnChDown, "CHDOWN")
        // Round 42: PLAY and PAUSE are separate keys, NOT toggles — the
        // Samsung key list has KEY_PLAY and KEY_PAUSE and no KEY_PLAYPAUSE,
        // so the remote has one button per key. (Round 40's single button
        // sent KEY_PAUSE, which paused but never resumed.)
        key(R.id.btnPlay, "PLAY")
        key(R.id.btnPause, "PAUSE")
        findViewById<View>(R.id.btnPower).setOnClickListener { v ->
            Haptics.tick(v, hapticEnabled())
            current?.let { conn.togglePower(it, effectivePower()) }
                ?: toast("Add a TV first (gear icon)")
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener { v ->
            Haptics.tick(v, hapticEnabled())
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        tvSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (spinnerGuard) return
                // Foreground silence: buildSpinner()'s programmatic
                // setSelection/setAdapter fires onItemSelected asynchronously,
                // AFTER spinnerGuard is cleared. A re-selection of the already
                // handled position must be completely silent — no tick, no
                // switchTo. Genuine user taps land on a new position and tick
                // as before.
                if (pos == lastSpinnerPos) return
                lastSpinnerPos = pos
                if (pos in tvs.indices) {
                    Haptics.tick(v ?: tvSpinner, tvs[pos].haptic)
                    switchTo(tvs[pos])
                }
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Warm the vendored app catalog off the UI thread (round 21): the
        // ~7KB res/raw parse costs ~1ms but stays off the cold-start path
        // entirely; tiles only need the stored iconUrl, never the catalog.
        thread { AppCatalog.load(this) }
    }

    override fun onResume() {
        super.onResume()
        // Round 42: app is foregrounded — release TvConnection's
        // background reconnect hold so the foreground check below (and any
        // pending retry) may connect.
        conn.setForeground(true)
        tvs = store.loadTvs()
        apps = store.loadApps()
        // Round 30: universal icon matching — preseeded defaults (and any
        // icon-less stored button) pick up the catalog logo on first sight
        // so fresh installs get logo tiles without visiting settings.
        try {
            val catalog = AppCatalog.load(this)
            if (catalog.isNotEmpty()) {
                var changed = false
                for (app in apps) {
                    if (AppCatalog.enrichButton(app, catalog)) changed = true
                }
                if (changed) store.saveApps(apps)
            }
        } catch (_: Exception) {
        }
        buildSpinner()
        buildAppButtons()
        // Foreground kick (round 19): same-TV foregrounding keeps the
        // last-known LED/state and silently re-checks via foregroundCheck —
        // no stale "disconnected" flash while the check is in flight, and no
        // user-visible error. Full switch only when the target actually
        // changed (first start, TV added/removed/reselected in settings).
        val last = store.lastTvId()?.let { id -> tvs.firstOrNull { it.id == id } }
        val target = last ?: tvs.firstOrNull()
        if (target != null) {
            // Cold-start sync (round 41): buildSpinner() ran while current
            // was still null, so the spinner sits at position 0. Pin it to
            // the resolved target now; lastSpinnerPos silences the async
            // setSelection callback via the existing check.
            val pos = tvs.indexOfFirst { it.id == target.id }.takeIf { it >= 0 }
            if (pos != null) {
                spinnerGuard = true
                tvSpinner.setSelection(pos)
                lastSpinnerPos = tvSpinner.selectedItemPosition
                spinnerGuard = false
            }
        }
        if (target == null) {
            switchTo(null, force = true)
        } else if (target.id != current?.id) {
            switchTo(target, force = true)
        } else {
            // Same-TV foregrounding: adopt the fresh object so Settings
            // edits (new host) take effect; a host change forces a
            // reconnect so the socket moves to the new IP immediately.
            val oldHost = current?.host
            current = target
            if (oldHost != target.host) {
                conn.selectTv(target)
            } else {
                conn.foregroundCheck(target)
            }
            conn.refreshInfo(target) { p -> power = p }
        }
        ui.post(poller)
    }

    override fun onPause() {
        super.onPause()
        // Round 42: app is backgrounded — TvConnection must not
        // auto-reconnect while backgrounded; the next onResume reconnects.
        conn.setForeground(false)
        ui.removeCallbacks(poller)
    }

    override fun onDestroy() {
        conn.close()
        super.onDestroy()
    }

    /**
     * Remote keys are NEVER gated on connection state (round 19): every tap
     * attempts to send — TvConnection queues the frame and kicks an
     * immediate reconnect when the socket is down.
     */
    private fun key(viewId: Int, samsungKey: String) {
        findViewById<View>(viewId).setOnClickListener { v ->
            Haptics.tick(v, hapticEnabled())
            current?.let { conn.sendKey(it, samsungKey) }
                ?: toast("Add a TV first (gear icon)")
        }
    }

    /** Per-TV haptic toggle; default ON (including when no TV exists yet). */
    private fun hapticEnabled(): Boolean =
        current?.let { store.isHapticEnabled(it.id) } ?: true

    private fun switchTo(tv: Tv?, force: Boolean = false) {
        if (!force && tv?.id == current?.id) return
        current = tv
        power = "unknown"
        connState = TvConnection.State.IDLE
        connDetail = ""
        if (tv == null) {
            conn.selectTv(null)
            renderStatus()
            return
        }
        store.setLastTvId(tv.id)
        renderStatus()
        conn.selectTv(tv)
        conn.refreshInfo(tv) { p -> power = p }
    }

    private fun buildSpinner() {
        spinnerGuard = true
        val names = if (tvs.isEmpty()) listOf("No TVs") else tvs.map { it.nick }
        tvSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        current?.let { c -> tvs.indexOfFirst { it.id == c.id }.takeIf { it >= 0 }?.let { tvSpinner.setSelection(it) } }
        lastSpinnerPos = tvSpinner.selectedItemPosition
        spinnerGuard = false
    }

    /**
     * App tiles (round 26): 1-8 tiles wrap to up to two centered rows of 4
     * (1-4 = one row; 5-8 = 4 on row 1 + remainder centered on row 2);
     * 9+ fall back to today's horizontally scrolling single row. The
     * visible container wraps its height to content.
     */
    private fun buildAppButtons() {
        appsRow.removeAllViews()
        appsRowTop.removeAllViews()
        appsRowBottom.removeAllViews()
        if (apps.isEmpty()) {
            appsScroll.visibility = View.GONE
            appsGrid.visibility = View.VISIBLE
            appsRowTop.visibility = View.VISIBLE
            appsRowBottom.visibility = View.GONE
            val t = TextView(this).apply { text = "No app buttons — add some in settings" }
            appsRowTop.addView(t)
            return
        }
        if (apps.size <= 8) {
            appsScroll.visibility = View.GONE
            appsGrid.visibility = View.VISIBLE
            val top = apps.take(4)
            val bottom = apps.drop(4)
            appsRowTop.visibility = View.VISIBLE
            for (app in top) {
                appsRowTop.addView(appTile(app), tileLp())
            }
            if (bottom.isEmpty()) {
                appsRowBottom.visibility = View.GONE
            } else {
                appsRowBottom.visibility = View.VISIBLE
                for (app in bottom) {
                    appsRowBottom.addView(appTile(app), tileLp())
                }
            }
            return
        }
        appsGrid.visibility = View.GONE
        appsScroll.visibility = View.VISIBLE
        for (app in apps) {
            appsRow.addView(appTile(app), tileLp())
        }
    }

    private fun tileLp(): LinearLayout.LayoutParams {
        // Round 28: equal margin on all sides — 9dp side margins minus 2dp
        // = 7dp, with vertical set to match.
        val s = dp(87)
        return LinearLayout.LayoutParams(s, s).apply { setMargins(dp(7), dp(7), dp(7), dp(7)) }
    }

    /**
     * App tile (round 26): buttons without a stored logo are today's
     * text-only tile, byte-for-byte the same look and behavior. Buttons with
     * a logo are FULL-BLEED: the image fills the tile edge-to-edge with no
     * text label and no padding (CENTER_CROP, clipped to the same 15dp
     * rounded face). Same size, same btn_face_selector background (so the
     * pressed state selector is unchanged), same tap behavior (tries every
     * fallback ID in order, per-TV winner first). A translucent pressed
     * overlay darkens the logo while pressed so feedback stays visible over
     * the image. The ImageView starts GONE and only appears once IconLoader
     * has the bitmap, so an offline logo tile is the empty face (still the
     * same size and tappable).
     */
    private fun appTile(app: AppButton): View {
        val launch = launch@{ v: View, tile: View ->
            Haptics.tick(v, hapticEnabled())
            val tv = current
            if (tv == null) {
                toast("Add a TV first (gear icon)")
                return@launch
            }
            tile.isEnabled = false
            conn.launchApp(tv, app.id, app.launchIds()) { ok ->
                tile.isEnabled = true
                if (!ok) toast("Launch failed — is ${tv.nick} on?")
            }
        }
        val icon = app.iconUrl?.trim().orEmpty()
        if (icon.isEmpty()) {
            // Square-ish rounded tiles with the same top-right-light gradient.
            // Round 40: single line with end-ellipsis, centered — multi-word
            // names (e.g. "CBS News") never wrap, so they stay vertically
            // centered exactly like single-word ones.
            return Button(this).apply {
                text = app.label
                textSize = 14f
                maxLines = 1
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                includeFontPadding = false
                setBackgroundResource(R.drawable.btn_face_selector)
                elevation = dp(1).toFloat()
                setOnClickListener { v -> launch(v, this) }
            }
        }
        val tile = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.btn_face_selector)
            elevation = dp(1).toFloat()
            isClickable = true
            isFocusable = true
            clipToOutline = true
            foreground = resources.getDrawable(R.drawable.tile_logo_pressed_overlay, theme)
        }
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = app.label
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(0, 0, 0, 0)
        }
        tile.addView(iv)
        tile.setOnClickListener { v -> launch(v, tile) }
        IconLoader.loadInto(iv, icon)
        return tile
    }

    // ---- TvConnection.Listener (already on main thread) ----

    override fun onConnState(tv: Tv?, state: TvConnection.State, detail: String) {
        if (tv != null && tv.id != current?.id) return
        val was = connState
        connState = state
        connDetail = sanitize(detail)
        if (state == TvConnection.State.OPEN && was != TvConnection.State.OPEN) {
            // Socket success proves the TV is on; re-run the REST poll now so
            // the power reading genuinely refreshes on (re)connect.
            current?.let { conn.refreshInfo(it) { p -> power = p } }
        }
        renderStatus()
        // Transient states surface as brief toasts on transitions only.
        if (state != was) {
            when (state) {
                TvConnection.State.WAITING_APPROVAL ->
                    toast(connDetail.ifBlank { "Approve \"Grafmote\" on the TV, then tap again" })
                TvConnection.State.UNREACHABLE ->
                    toast(connDetail.ifBlank { "TV may be off — Power still sends WoL" })
                else -> {}
            }
        }
    }

    override fun onPower(tv: Tv?, power: String) {
        if (tv != null && tv.id != current?.id) return
        this.power = power
        renderStatus()
    }

    /**
     * Power reading for the toggle only (the LED is socket state, not power).
     * A successful remote-control WebSocket connection proves the TV is on, so
     * OPEN overrides a stale or failed ("unknown") REST poll.
     */
    private fun effectivePower() =
        if (power == "on" || connState == TvConnection.State.OPEN) "on" else power

    /** Two-state LED: green = socket open, red = anything else. */
    private fun renderStatus() {
        val dot = if (connState == TvConnection.State.OPEN) DOT_GREEN else DOT_RED
        statusDot.backgroundTintList = ColorStateList.valueOf(dot)
    }

    /**
     * Defensive layer: raw WebSocket close codes/reasons must never reach the
     * UI, no matter what string the connection layer passes up.
     */
    private fun sanitize(detail: String): String {
        if (detail.contains("code ", ignoreCase = true) ||
            detail.contains("reserved", ignoreCase = true) ||
            detail.contains("disconnected (", ignoreCase = true)
        ) {
            return "Reconnecting…"
        }
        return detail
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val DOT_GREEN = 0xFF34C759.toInt()
        private const val DOT_RED = 0xFFFF453A.toInt()
    }
}
