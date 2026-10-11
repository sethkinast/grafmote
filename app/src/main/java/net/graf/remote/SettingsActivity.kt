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
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/** Manage TVs (nickname + IP/hostname, SSDP scan) and app-launch buttons. */
class SettingsActivity : Activity() {

    private lateinit var store: TvStore
    private lateinit var tvList: LinearLayout
    private lateinit var appList: LinearLayout

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
     * (GET http://<tv>:8001/api/v2/applications/list), show real names in a
     * multi-select dialog, and add the chosen ones with their IDs. Manual
     * entry stays available as a fallback in the same dialog.
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

        thread {
            val found = fetchInstalledApps(target.host)
            runOnUiThread {
                try {
                    loading.dismiss()
                } catch (_: Exception) {
                }
                if (found == null) {
                    AlertDialog.Builder(this)
                        .setTitle("Add apps")
                        .setMessage(
                            "Couldn't reach ${target.nick} — " +
                                "the TV must be on and connected to the same Wi-Fi."
                        )
                        .setPositiveButton("Retry") { _, _ -> addAppFlow() }
                        .setNeutralButton("Enter manually") { _, _ -> appDialog(null) }
                        .setNegativeButton("Cancel", null)
                        .show()
                    return@runOnUiThread
                }
                if (found.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("Add apps")
                        .setMessage("No apps reported by ${target.nick}.")
                        .setPositiveButton("Retry") { _, _ -> addAppFlow() }
                        .setNeutralButton("Enter manually") { _, _ -> appDialog(null) }
                        .setNegativeButton("Cancel", null)
                        .show()
                    return@runOnUiThread
                }
                val names = found.map { it.first }.toTypedArray()
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
                        found.forEachIndexed { i, (name, appId) ->
                            if (checked[i] && !have.contains(appId) &&
                                all.none { it.appId == appId }
                            ) {
                                all.add(AppButton(UUID.randomUUID().toString(), name, appId))
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
    }

    /**
     * GET the TV's installed-app list. Returns null when the TV is
     * unreachable (must be on + connected); otherwise the parsed
     * (name, appId) pairs, possibly empty.
     */
    private fun fetchInstalledApps(host: String): List<Pair<String, String>>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("http://$host:8001/api/v2/applications/list").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            parseAppList(body).sortedBy { it.first.lowercase() }
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * The endpoint returns a JSON array of app objects, but key names vary by
     * firmware — accept the common variants for both the envelope and the
     * per-app name/id fields.
     */
    private fun parseAppList(body: String): List<Pair<String, String>> {
        val arr: JSONArray = try {
            JSONArray(body)
        } catch (_: Exception) {
            try {
                val root = JSONObject(body)
                arrayFrom(root, "data")
                    ?: arrayFrom(root, "applications")
                    ?: arrayFrom(root, "apps")
                    ?: arrayFrom(root, "list")
                    ?: arrayFrom(root, "result")
                    ?: arrayFrom(root, "items")
                    ?: return emptyList()
            } catch (_: Exception) {
                return emptyList()
            }
        }
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = firstNonEmpty(
                o.optString("name"), o.optString("appName"),
                o.optString("title"), o.optString("label")
            )
            val appId = firstNonEmpty(
                o.optString("appId"), o.optString("id"),
                o.optString("app_id"), o.optString("applicationId")
            )
            if (name.isNotEmpty() && appId.isNotEmpty()) out.add(name to appId)
        }
        return out
    }

    private fun arrayFrom(root: JSONObject, key: String): JSONArray? {
        val v = root.opt(key) ?: return null
        return when (v) {
            is JSONArray -> v
            is JSONObject -> {
                // Some firmware nests once more, e.g. {"data":{"apps":[...]}}.
                arrayFrom(v, "apps") ?: arrayFrom(v, "applications")
                    ?: arrayFrom(v, "list") ?: arrayFrom(v, "items")
            }
            else -> null
        }
    }

    private fun firstNonEmpty(vararg vals: String): String =
        vals.firstOrNull { it.isNotBlank() }?.trim() ?: ""

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
