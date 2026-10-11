package net.graf.remote

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.UUID
import kotlin.concurrent.thread

/** Manage TVs (nickname + IP/hostname, SSDP scan) and app-launch buttons. */
class SettingsActivity : Activity() {

    private lateinit var store: TvStore
    private lateinit var tvList: LinearLayout
    private lateinit var appList: LinearLayout
    private var appQueryConn: TvConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        store = TvStore(this)
        tvList = findViewById(R.id.tvList)
        appList = findViewById(R.id.appList)

        findViewById<Button>(R.id.btnAddTv).setOnClickListener { tvDialog(null) }
        findViewById<Button>(R.id.btnScan).setOnClickListener { scan() }
        findViewById<Button>(R.id.btnAddApp).setOnClickListener { addAppFlow() }
        findViewById<Button>(R.id.btnDone).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        appQueryConn?.close()
        appQueryConn = null
        super.onDestroy()
    }

    private fun render() {
        tvList.removeAllViews()
        val tvs = store.loadTvs()
        if (tvs.isEmpty()) tvList.addView(hint("No TVs yet — add one by IP or scan."))
        for (tv in tvs) {
            val sub = if (tv.haptic) tv.host else "${tv.host} · haptics off"
            tvList.addView(row(
                "${tv.nick}\n$sub",
                onEdit = { tvDialog(tv) },
                onDelete = {
                    val all = store.loadTvs().filter { it.id != tv.id }
                    store.saveTvs(all)
                    render()
                }
            ))
        }
        appList.removeAllViews()
        val apps = store.loadApps()
        if (apps.isEmpty()) appList.addView(hint("No app buttons."))
        for (app in apps) {
            appList.addView(row(
                "${app.label}\n${app.appId}",
                onEdit = { appDialog(app) },
                onDelete = {
                    val all = store.loadApps().filter { it.id != app.id }
                    store.saveApps(all)
                    render()
                }
            ))
        }
    }

    private fun row(text: String, onEdit: () -> Unit, onDelete: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val label = TextView(context).apply {
                this.text = text
                textSize = 16f
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val edit = Button(context).apply {
                this.text = "Edit"
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                isAllCaps = false
                includeFontPadding = false
                minWidth = 0
                minimumWidth = 0
                setBackgroundResource(R.drawable.btn_face_selector)
                setTextColor(0xFFF2F2F2.toInt())
                elevation = dp(1).toFloat()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(40)
                )
                setOnClickListener { onEdit() }
            }
            val del = Button(context).apply {
                this.text = "Del"
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                isAllCaps = false
                includeFontPadding = false
                minWidth = 0
                minimumWidth = 0
                setBackgroundResource(R.drawable.btn_face_selector)
                setTextColor(0xFFF2F2F2.toInt())
                elevation = dp(1).toFloat()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(40)
                ).apply { marginStart = dp(8) }
                setOnClickListener { onDelete() }
            }
            addView(label)
            addView(edit)
            addView(del)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
    }

    private fun hint(text: String) = TextView(this).apply { this.text = text }

    private fun tvDialog(existing: Tv?) {
        val nick = EditText(this).apply {
            hint = "Nickname (e.g. Living Room)"
            setText(existing?.nick ?: "")
        }
        val host = EditText(this).apply {
            hint = "IP or hostname (e.g. 192.168.1.50)"
            setText(existing?.host ?: "")
        }
        val haptic = Switch(this).apply {
            text = "Haptic feedback"
            isChecked = existing?.haptic ?: true
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(nick)
            addView(host)
            addView(haptic)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add TV" else "Edit TV")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val n = nick.text.toString().trim()
                val h = host.text.toString().trim()
                if (n.isEmpty() || h.isEmpty()) {
                    toast("Nickname and IP are both required")
                    return@setPositiveButton
                }
                val all = store.loadTvs()
                val hapticOn = haptic.isChecked
                if (existing == null) {
                    all.add(Tv(UUID.randomUUID().toString(), n, h, haptic = hapticOn))
                } else {
                    all.firstOrNull { it.id == existing.id }?.let {
                        it.nick = n
                        it.host = h
                        it.haptic = hapticOn
                    }
                }
                store.saveTvs(all)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * "Add app" flow: query the currently selected TV's installed-app list
     * over the remote-control WebSocket (ed.installedApp.get emit; see
     * TvConnection.queryInstalledApps), show real names in a multi-select
     * dialog, and add the chosen ones with their IDs. Manual entry stays
     * available as a fallback in the same dialog. A TV that never answers
     * (older firmware) is "unavailable", not an error.
     */
    private fun addAppFlow() {
        val tvs = store.loadTvs()
        if (tvs.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Add apps")
                .setMessage("Add a TV first, then pick apps from it.")
                .setPositiveButton("OK", null)
                .setNeutralButton("Enter manually") { _, _ -> appDialog(null) }
                .show()
            return
        }
        val target = store.lastTvId()?.let { id -> tvs.firstOrNull { it.id == id } }
            ?: tvs.first()

        val loading = AlertDialog.Builder(this)
            .setTitle("Add apps")
            .setMessage("Loading apps from ${target.nick}…")
            .setCancelable(false)
            .create()
        loading.show()

        val conn = TvConnection(store)
        appQueryConn?.close()
        appQueryConn = conn
        conn.queryInstalledApps(target) { found ->
            if (isFinishing || isDestroyed) {
                conn.close()
                return@queryInstalledApps
            }
            try {
                loading.dismiss()
            } catch (_: Exception) {
            }
            conn.close()
            if (appQueryConn === conn) appQueryConn = null
            if (found == null) {
                AlertDialog.Builder(this)
                    .setTitle("Add apps")
                    .setMessage(
                        "Couldn't get apps from ${target.nick} — " +
                            "the TV didn't answer (older firmware doesn't respond)."
                    )
                    .setPositiveButton("Retry") { _, _ -> addAppFlow() }
                    .setNeutralButton("Enter manually") { _, _ -> appDialog(null) }
                    .setNegativeButton("Cancel", null)
                    .show()
                return@queryInstalledApps
            }
            if (found.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Add apps")
                    .setMessage("No apps reported by ${target.nick}.")
                    .setPositiveButton("Retry") { _, _ -> addAppFlow() }
                    .setNeutralButton("Enter manually") { _, _ -> appDialog(null) }
                    .setNegativeButton("Cancel", null)
                    .show()
                return@queryInstalledApps
            }
            val names = found.map { it.name }.toTypedArray()
            val checked = BooleanArray(found.size)
            AlertDialog.Builder(this)
                .setTitle("Add apps from ${target.nick}")
                .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton("Add selected") { _, _ ->
                    val all = store.loadApps()
                    val have = all.map { it.appId }.toSet()
                    var added = 0
                    found.forEachIndexed { i, info ->
                        if (checked[i] && !have.contains(info.appId) &&
                            all.none { it.appId == info.appId }
                        ) {
                            all.add(AppButton(UUID.randomUUID().toString(), info.name, info.appId))
                            added++
                        }
                    }
                    store.saveApps(all)
                    render()
                    toast(if (added > 0) "Added $added app(s)" else "Nothing new selected")
                }
                .setNeutralButton("Enter manually") { _, _ -> appDialog(null) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun appDialog(existing: AppButton?) {
        val label = EditText(this).apply {
            hint = "Label (e.g. Jellyfin)"
            setText(existing?.label ?: "")
        }
        val appId = EditText(this).apply {
            hint = "Samsung app ID (e.g. AprZAARz4r.Jellyfin)"
            setText(existing?.appId ?: "")
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(label)
            addView(appId)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add app button" else "Edit app button")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val l = label.text.toString().trim()
                val a = appId.text.toString().trim()
                if (l.isEmpty() || a.isEmpty()) {
                    toast("Label and app ID are both required")
                    return@setPositiveButton
                }
                val all = store.loadApps()
                if (existing == null) {
                    all.add(AppButton(UUID.randomUUID().toString(), l, a))
                } else {
                    all.firstOrNull { it.id == existing.id }?.let {
                        it.label = l
                        it.appId = a
                    }
                }
                store.saveApps(all)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun scan() {
        val btn = findViewById<Button>(R.id.btnScan)
        btn.isEnabled = false
        btn.text = "Scanning…"
        toast("Scanning LAN for Samsung TVs…")
        thread {
            val result = Ssdp.discover(this@SettingsActivity) { done, total ->
                runOnUiThread { btn.text = "Scanning $done/$total" }
            }
            runOnUiThread {
                btn.isEnabled = true
                btn.text = "Scan LAN"
                if (result.noWifi) {
                    toast("Connect to Wi-Fi first — the scan needs the local network")
                    return@runOnUiThread
                }
                if (result.found.isEmpty()) {
                    toast("No TVs found — add by IP manually")
                    return@runOnUiThread
                }
                val all = store.loadTvs()
                var added = 0
                for (f in result.found) {
                    if (all.none { it.host == f.ip }) {
                        all.add(Tv(UUID.randomUUID().toString(), f.name, f.ip))
                        added++
                    }
                }
                store.saveTvs(all)
                render()
                toast(if (added > 0) "Added $added TV(s)" else "Found TVs already listed")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
