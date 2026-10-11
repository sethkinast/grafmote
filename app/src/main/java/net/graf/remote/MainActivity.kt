package net.graf.remote

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

    private lateinit var tvSpinner: Spinner
    private lateinit var statusDot: View
    private lateinit var appsRow: LinearLayout

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
        appsRow = findViewById(R.id.appsRow)

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
        key(R.id.btnPlayPause, "PLAYPAUSE")
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
        tvs = store.loadTvs()
        apps = store.loadApps()
        buildSpinner()
        buildAppButtons()
        // Foreground kick (round 19): same-TV foregrounding keeps the
        // last-known LED/state and silently re-checks via foregroundCheck —
        // no stale "disconnected" flash while the check is in flight, and no
        // user-visible error. Full switch only when the target actually
        // changed (first start, TV added/removed/reselected in settings).
        val last = store.lastTvId()?.let { id -> tvs.firstOrNull { it.id == id } }
        val target = last ?: tvs.firstOrNull()
        if (target == null) {
            switchTo(null, force = true)
        } else if (target.id != current?.id) {
            switchTo(target, force = true)
        } else {
            conn.foregroundCheck(target)
            conn.refreshInfo(target) { p -> power = p }
        }
        ui.post(poller)
    }

    override fun onPause() {
        super.onPause()
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
        spinnerGuard = false
    }

    private fun buildAppButtons() {
        appsRow.removeAllViews()
        if (apps.isEmpty()) {
            val t = TextView(this).apply { text = "No app buttons — add some in settings" }
            appsRow.addView(t)
            return
        }
        for (app in apps) {
            val s = dp(97)
            val lp = LinearLayout.LayoutParams(s, s).apply { setMargins(dp(4), 0, dp(4), 0) }
            appsRow.addView(appTile(app), lp)
        }
    }

    /**
     * App tile (round 21): buttons without a stored logo are today's
     * text-only tile, byte-for-byte the same look and behavior. Buttons with
     * a logo show the image above the label inside the same tile frame —
     * same size, same btn_face_selector pressed states, same tap behavior
     * (tries every fallback ID in order). The ImageView starts GONE and only
     * appears once IconLoader has the bitmap cached or downloaded, so offline
     * tiles are exactly the text-only tile.
     */
    private fun appTile(app: AppButton): View {
        val launch = { v: View, tile: View ->
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
            return Button(this).apply {
                text = app.label
                textSize = 14f
                maxLines = 2
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.btn_face_selector)
                elevation = dp(1).toFloat()
                setOnClickListener { v -> launch(v, this) }
            }
        }
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.btn_face_selector)
            elevation = dp(1).toFloat()
            isClickable = true
            isFocusable = true
        }
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = app.label
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(50), dp(50)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        val label = TextView(this).apply {
            text = app.label
            textSize = 12f
            maxLines = 2
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(2), dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        tile.addView(iv)
        tile.addView(label)
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
                    toast(connDetail.ifBlank { "Approve \"GrafRemote\" on the TV, then tap again" })
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
