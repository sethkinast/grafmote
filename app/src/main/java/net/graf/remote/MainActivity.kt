package net.graf.remote

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * Single-activity remote. Cold-start path is deliberately trivial: inflate the
 * cached layout, wire click listeners (taps queue until the socket opens —
 * buttons are live instantly), then connect + poll in the background.
 * No custom Application class, no ContentProviders, no DI, no Compose.
 *
 * Header shows each TV once (in the selector) with a three-state LED dot:
 * green = socket open and TV on, gray = socket open but TV off/standby,
 * red = no socket. The status line below appears only for transient messages
 * (connecting / reconnecting / approval prompts).
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
    private lateinit var statusText: TextView
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
        statusText = findViewById(R.id.statusText)
        appsRow = findViewById(R.id.appsRow)

        // D-pad
        key(R.id.btnUp, "UP")
        key(R.id.btnDown, "DOWN")
        key(R.id.btnLeft, "LEFT")
        key(R.id.btnRight, "RIGHT")
        key(R.id.btnOk, "ENTER")
        // Rows (power/mute are ImageButtons now — key() works on any View)
        key(R.id.btnBack, "RETURN")
        key(R.id.btnHome, "HOME")
        key(R.id.btnMute, "MUTE")
        key(R.id.btnVolUp, "VOLUP")
        key(R.id.btnVolDown, "VOLDOWN")
        findViewById<View>(R.id.btnPower).setOnClickListener {
            current?.let { conn.togglePower(it, effectivePower()) }
                ?: toast("Add a TV first (gear icon)")
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        tvSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (spinnerGuard) return
                if (pos in tvs.indices) switchTo(tvs[pos])
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    override fun onResume() {
        super.onResume()
        tvs = store.loadTvs()
        apps = store.loadApps()
        buildSpinner()
        buildAppButtons()
        // Silent reconnect: switchTo(force=true) drops any stale socket and
        // reconnects without any user-visible error. Any late failure callback
        // from the old socket is sanitized to "Reconnecting…" (see renderStatus).
        val last = store.lastTvId()?.let { id -> tvs.firstOrNull { it.id == id } }
        switchTo(last ?: tvs.firstOrNull(), force = true)
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

    private fun key(viewId: Int, samsungKey: String) {
        findViewById<View>(viewId).setOnClickListener {
            current?.let { conn.sendKey(it, samsungKey) }
                ?: toast("Add a TV first (gear icon)")
        }
    }

    private fun switchTo(tv: Tv?, force: Boolean = false) {
        if (!force && tv?.id == current?.id) return
        current = tv
        power = "unknown"
        connState = TvConnection.State.IDLE
        connDetail = ""
        if (tv == null) {
            conn.selectTv(null)
            connDetail = "No TV configured — tap Setup to add one"
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
            val b = Button(this).apply {
                text = app.label
                minWidth = dp(120)
                setOnClickListener {
                    val tv = current
                    if (tv == null) {
                        toast("Add a TV first (gear icon)")
                        return@setOnClickListener
                    }
                    isEnabled = false
                    conn.launchApp(tv, app.appId) { ok ->
                        isEnabled = true
                        if (!ok) toast("Launch failed — is ${tv.nick} on?")
                    }
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(4), 0, dp(4), 0) }
            appsRow.addView(b, lp)
        }
    }

    // ---- TvConnection.Listener (already on main thread) ----

    override fun onConnState(tv: Tv?, state: TvConnection.State, detail: String) {
        if (tv != null && tv.id != current?.id) return
        val was = connState
        connState = state
        connDetail = detail
        if (state == TvConnection.State.OPEN && was != TvConnection.State.OPEN) {
            // Socket success proves the TV is on; re-run the REST poll now so
            // the power reading genuinely refreshes on (re)connect.
            current?.let { conn.refreshInfo(it) { p -> power = p } }
        }
        renderStatus()
    }

    override fun onPower(tv: Tv?, power: String) {
        if (tv != null && tv.id != current?.id) return
        this.power = power
        renderStatus()
    }

    /**
     * Effective power for the toggle + LED. A successful remote-control
     * WebSocket connection proves the TV is on, so OPEN overrides a stale
     * or failed ("unknown") REST poll — but an explicit REST "off" while
     * connected still shows gray (standby edge case).
     */
    private fun effectivePower() =
        if (power == "on" || connState == TvConnection.State.OPEN) "on" else power

    private fun renderStatus() {
        val dot = when {
            connState == TvConnection.State.OPEN && power == "off" -> DOT_GRAY
            connState == TvConnection.State.OPEN -> DOT_GREEN
            else -> DOT_RED
        }
        statusDot.backgroundTintList = ColorStateList.valueOf(dot)

        // Defensive second layer: raw WebSocket close codes/reasons must never
        // reach the UI, no matter what string the connection layer passes up.
        var detail = connDetail
        if (detail.contains("code ", ignoreCase = true) ||
            detail.contains("reserved", ignoreCase = true) ||
            detail.contains("disconnected (", ignoreCase = true)
        ) {
            detail = "Reconnecting…"
            connDetail = detail
        }
        val nick = current?.nick ?: ""
        val msg: String? = when (connState) {
            TvConnection.State.OPEN -> null
            TvConnection.State.CONNECTING ->
                detail.ifBlank { "Connecting to $nick…" }
            TvConnection.State.WAITING_APPROVAL ->
                detail.ifBlank { "Approve \"GrafRemote\" on the TV, then tap again" }
            TvConnection.State.UNREACHABLE ->
                detail.ifBlank { "TV may be off — Power still sends WoL" }
            TvConnection.State.IDLE -> detail.ifBlank { null }
        }
        if (msg == null) {
            statusText.visibility = View.GONE
        } else {
            statusText.visibility = View.VISIBLE
            statusText.text = msg
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val DOT_GREEN = 0xFF34C759.toInt()
        private const val DOT_RED = 0xFFFF453A.toInt()
        private const val DOT_GRAY = 0xFF8E8E93.toInt()
    }
}
