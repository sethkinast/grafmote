package net.graf.remote

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.UUID
import kotlin.concurrent.thread

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
        findViewById<Button>(R.id.btnAddApp).setOnClickListener { appDialog(null) }
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
            tvList.addView(row(
                "${tv.nick}\n${tv.host}",
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
            val label = TextView(context).apply {
                this.text = text
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val edit = Button(context).apply {
                this.text = "Edit"
                setOnClickListener { onEdit() }
            }
            val del = Button(context).apply {
                this.text = "Del"
                setOnClickListener { onDelete() }
            }
            addView(label)
            addView(edit)
            addView(del)
            setPadding(0, dp(4), 0, dp(4))
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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(nick)
            addView(host)
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
                if (existing == null) {
                    all.add(Tv(UUID.randomUUID().toString(), n, h))
                } else {
                    all.firstOrNull { it.id == existing.id }?.let {
                        it.nick = n
                        it.host = h
                    }
                }
                store.saveTvs(all)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        toast("Scanning LAN for Samsung TVs…")
        thread {
            val found = Ssdp.discover(this@SettingsActivity)
            runOnUiThread {
                if (found.isEmpty()) {
                    toast("No TVs found — add by IP manually")
                    return@runOnUiThread
                }
                val all = store.loadTvs()
                var added = 0
                for (f in found) {
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
