package net.graf.remote

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
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
            val sub = if (app.launchIds().size > 1) {
                "${app.appId} (+${app.launchIds().size - 1} fallback)"
            } else {
                app.appId
            }
            appList.addView(row(
                "${app.label}\n$sub",
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
     * dialog, and add the chosen ones with their IDs. Actually-installed
     * apps win whenever the TV answers.
     *
     * Round 21: when the TV doesn't answer (older firmware — "unavailable",
     * not an error) or reports nothing, fall through to the searchable
     * vendored catalog browser (name + logo rows, multi-select) instead of
     * leaving only manual entry. Manual app-ID entry stays as the final
     * fallback everywhere.
     */
    private fun addAppFlow() {
        val tvs = store.loadTvs()
        if (tvs.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Add apps")
                .setMessage("Add a TV first to pick its installed apps, or browse the catalog.")
                .setPositiveButton("Browse catalog") { _, _ -> showCatalogBrowser(null) }
                .setNeutralButton("Enter manually") { _, _ -> appDialog(null) }
                .setNegativeButton("Cancel", null)
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
                // TV didn't answer (older firmware): catalog browser carries
                // the retry + manual-entry fallbacks inside.
                showCatalogBrowser(
                    "Couldn't get apps from ${target.nick} — " +
                        "the TV didn't answer (older firmware doesn't respond)."
                )
                return@queryInstalledApps
            }
            if (found.isEmpty()) {
                showCatalogBrowser("No apps reported by ${target.nick}.")
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

    /**
     * Searchable catalog browser (round 21): name + logo rows with
     * multi-select over the vendored res/raw catalog. [notice] explains why
     * the catalog is showing instead of the TV's own list (null when the
     * user chose the catalog directly). Picks store the primary ID, all
     * fallback IDs, and the icon URL. Manual entry stays one tap away, and
     * the TV query can be retried from here.
     */
    private fun showCatalogBrowser(notice: String?) {
        val catalog = AppCatalog.load(this)
        if (catalog.isEmpty()) {
            // Vendored parse failed (shouldn't happen): manual entry only.
            appDialog(null)
            return
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        if (notice != null) {
            layout.addView(TextView(this).apply {
                text = "$notice Showing the app catalog instead."
                textSize = 14f
                setPadding(0, 0, 0, dp(4))
            })
        }
        var dialog: AlertDialog? = null
        if (notice != null) {
            val retry = Button(this).apply {
                text = "Retry TV query"
                textSize = 14f
                isAllCaps = false
                setBackgroundResource(R.drawable.btn_face_selector)
                setTextColor(0xFFF2F2F2.toInt())
            }
            layout.addView(retry)
            retry.setOnClickListener {
                dialog?.dismiss()
                addAppFlow()
            }
        }
        val search = EditText(this).apply { hint = "Search apps…" }
        layout.addView(search)
        val list = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
        }
        layout.addView(
            list,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(320)
            ).apply { topMargin = dp(8) }
        )

        val visible = catalog.toMutableList()
        val selected = mutableSetOf<String>()

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = visible.size
            override fun getItem(pos: Int): Any = visible[pos]
            override fun getItemId(pos: Int): Long = pos.toLong()
            override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
                val app = visible[pos]
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    addView(CheckBox(context).apply {
                        isFocusable = false
                        isClickable = false
                        tag = "check"
                    })
                    addView(ImageView(context).apply {
                        tag = "logo"
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                            marginStart = dp(8)
                            marginEnd = dp(8)
                        }
                    })
                    addView(TextView(context).apply {
                        tag = "name"
                        textSize = 16f
                        layoutParams = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                    })
                }
                val check = row.findViewWithTag<CheckBox>("check")
                val logo = row.findViewWithTag<ImageView>("logo")
                val name = row.findViewWithTag<TextView>("name")
                check.isChecked = selected.contains(app.primaryId)
                name.text = app.name
                IconLoader.loadInto(logo, app.icon)
                return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val app = visible[pos]
            if (!selected.remove(app.primaryId)) selected.add(app.primaryId)
            adapter.notifyDataSetChanged()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                visible.clear()
                if (q.isEmpty()) {
                    visible.addAll(catalog)
                } else {
                    visible.addAll(catalog.filter { it.name.lowercase().contains(q) })
                }
                adapter.notifyDataSetChanged()
            }
        })

        dialog = AlertDialog.Builder(this)
            .setTitle(
                if (notice != null) "Add apps — catalog" else "Add apps from catalog"
            )
            .setView(layout)
            .setPositiveButton("Add selected") { _, _ ->
                val all = store.loadApps()
                val have = all.flatMap { it.launchIds() }.toSet()
                var added = 0
                for (app in catalog) {
                    if (!selected.contains(app.primaryId)) continue
                    if (app.ids.any { have.contains(it) }) continue
                    if (all.any { b -> b.launchIds().any { app.ids.contains(it) } }) continue
                    all.add(
                        AppButton(
                            UUID.randomUUID().toString(),
                            app.name,
                            app.primaryId,
                            app.ids,
                            app.icon.ifBlank { null }
                        )
                    )
                    added++
                }
                store.saveApps(all)
                render()
                toast(if (added > 0) "Added $added app(s)" else "Nothing new selected")
            }
            .setNeutralButton("Enter manually") { _, _ -> appDialog(null) }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
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
        val iconUrl = EditText(this).apply {
            hint = "Icon URL (optional)"
            setText(existing?.iconUrl ?: "")
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(label)
            addView(appId)
            addView(iconUrl)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add app button" else "Edit app button")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val l = label.text.toString().trim()
                val a = appId.text.toString().trim()
                val icon = iconUrl.text.toString().trim().ifEmpty { null }
                if (l.isEmpty() || a.isEmpty()) {
                    toast("Label and app ID are both required")
                    return@setPositiveButton
                }
                val all = store.loadApps()
                if (existing == null) {
                    all.add(AppButton(UUID.randomUUID().toString(), l, a, listOf(a), icon))
                } else {
                    all.firstOrNull { it.id == existing.id }?.let {
                        it.label = l
                        // A changed primary ID resets the fallback chain to the
                        // single typed ID; an unchanged ID keeps catalog picks.
                        if (a != it.appId) {
                            it.appId = a
                            it.appIds = listOf(a)
                        }
                        it.iconUrl = icon
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
