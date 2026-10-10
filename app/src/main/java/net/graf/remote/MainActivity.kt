package net.graf.remote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * Single-activity remote. Cold-start path is deliberately trivial: inflate the
 * cached layout, wire click listeners (taps queue until the socket opens —
 * buttons are live instantly), then connect + poll in the background.
 * No custom Application class, no ContentProviders, no DI, no Compose.
 */
class MainActivity : Activity(), TvConnection.Listener {

    private lateinit var store: TvStore
    private lateinit var conn: TvConnection
    private val ui = Handler(Looper.getMainLooper())

    private var tvs: MutableList<Tv> = mutableListOf()
    private var apps: MutableList<AppButton> = mutableListOf()
    private var current: Tv? = null
    private var power: String = "unknown"
    private var spinnerGuard = true

    private lateinit var tvSpinner: Spinner
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
        statusText = findViewById(R.id.statusText)
        appsRow = findViewById(R.id.appsRow)

        // D-pad
        key(R.id.btnUp, "UP")
        key(R.id.btnDown, "DOWN")
        key(R.id.btnLeft, "LEFT")
        key(R.id.btnRight, "RIGHT")
        key(R.id.btnOk, "ENTER")
        // Rows
        key(R.id.btnBack, "RETURN")
        key(R.id.btnHome, "HOME")
        key(R.id.btnMute, "MUTE")
        key(R.id.btnVolUp, "VOLUP")
        key(R.id.btnVolDown, "VOLDOWN")
        findViewById<Button>(R.id.btnPower).setOnClickListener {
            current?.let { conn.togglePower(it, power) }
                ?: toast("Add a TV first (gear icon)")
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
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
        findViewById<Button>(viewId).setOnClickListener {
            current?.let { conn.sendKey(it, samsungKey) }
                ?: toast("Add a TV first (gear icon)")
        }
    }

    private fun switchTo(tv: Tv?, force: Boolean = false) {
        if (!force && tv?.id == current?.id) return
        current = tv
        power = "unknown"
        if (tv == null) {
            conn.selectTv(null)
            statusText.text = "No TV configured — tap the gear to add one"
            return
        }
        store.setLastTvId(tv.id)
        statusText.text = "Connecting to ${tv.nick}…"
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
        statusText.text = when (state) {
            TvConnection.State.OPEN -> "${tv?.nick ?: ""} · connected · TV ${powerText()}"
            TvConnection.State.UNREACHABLE -> detail
            else -> detail
        }
    }

    override fun onPower(tv: Tv?, power: String) {
        if (tv != null && tv.id != current?.id) return
        this.power = power
        current?.let { onConnState(it, conn.currentState(), "") }
        if (conn.currentState() == TvConnection.State.IDLE) {
            statusText.text = "${tv?.nick ?: ""} · TV ${powerText()}"
        }
    }

    private fun powerText() = if (power == "unknown") "may be off" else power

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
