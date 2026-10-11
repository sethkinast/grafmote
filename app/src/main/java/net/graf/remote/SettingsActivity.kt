package net.graf.remote

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipDescription
import android.graphics.Canvas
import android.graphics.Point
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
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

    /** Round-27 drag state: id + row height captured at drag start, plus the
     * visible placeholder marking the would-be insertion point. */
    private var draggedAppId: String? = null
    private var draggedRowHeight: Int = 0
    private var dragPlaceholder: View? = null
    private var dragInsertIndex: Int = -1

    /** ViewHolder for catalog browser rows (row.tag). See showCatalogBrowser. */
    private data class CatalogRow(
        val check: CheckBox,
        val logo: ImageView,
        val name: TextView
    )

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
        // Container-level drop target: catches drops on gaps and past the
        // last row (appends at the end via the placeholder position).
        appList.setOnDragListener(appListDropListener())
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
            appList.addView(appRow(app))
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
            val edit = TextView(context).apply {
                this.text = "✎"
                textSize = 22f
                gravity = android.view.Gravity.CENTER
                contentDescription = "Edit"
                setTextColor(0xFFF2F2F2.toInt())
                setBackgroundColor(0x00000000)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
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

    /**
     * App-button row (round 27): handle + label + Edit ONLY (Delete moved
     * into the edit dialog). The handle starts a drag-and-drop reorder with
     * a placeholder gap: while dragging, a visible placeholder marks the
     * would-be insertion point (before/after each row by pointer half), and
     * release drops exactly there — symmetric in both directions. TV rows
     * stay non-draggable.
     */
    private fun appRow(app: AppButton): LinearLayout {
        val sub = if (app.launchIds().size > 1) {
            "${app.appId} (+${app.launchIds().size - 1} fallback)"
        } else {
            app.appId
        }
        lateinit var row: LinearLayout
        row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val handle = TextView(context).apply {
                text = "☰"
                textSize = 22f
                gravity = android.view.Gravity.CENTER
                contentDescription = "Drag to reorder"
                setPadding(dp(4), dp(4), dp(12), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val label = TextView(context).apply {
                this.text = "${app.label}\n$sub"
                textSize = 16f
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val edit = TextView(context).apply {
                this.text = "✎"
                textSize = 22f
                gravity = android.view.Gravity.CENTER
                contentDescription = "Edit"
                setTextColor(0xFFF2F2F2.toInt())
                setBackgroundColor(0x00000000)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener { appDialog(app) }
            }
            addView(handle)
            addView(label)
            addView(edit)
            setPadding(dp(4), dp(6), dp(4), dp(6))
            tag = app.id
            setOnDragListener(appDropListener(app.id))
            handle.setOnTouchListener { v, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN) {
                    draggedAppId = app.id
                    draggedRowHeight = row.height
                    // Touch point relative to the ROW (handle sits at its
                    // left), so the shadow originates at the handle.
                    val tx = (handle.left + ev.x).toInt()
                    val ty = (handle.top + ev.y).toInt()
                    val shadowView = buildAppRowShadow(app, sub, row)
                    val data = ClipData.newPlainText("app-button", app.id)
                    v.startDragAndDrop(data, AppRowShadow(shadowView, tx, ty), app.id, 0)
                    true
                } else {
                    false
                }
            }
        }
        return row
    }

    /**
     * Drag shadow (round 27): a detached clone showing handle + label ONLY
     * (no Edit/Delete buttons) over a solid opaque background, drawn at the
     * touch point passed in from the handle.
     */
    private fun buildAppRowShadow(app: AppButton, sub: String, row: LinearLayout): View {
        val shadow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF2E2E33.toInt())
            setPadding(dp(4), dp(6), dp(4), dp(6))
            addView(TextView(context).apply {
                text = "☰"
                textSize = 22f
                gravity = android.view.Gravity.CENTER
                setTextColor(0xFFF2F2F2.toInt())
                setPadding(dp(4), dp(4), dp(12), dp(4))
            })
            addView(TextView(context).apply {
                text = "${app.label}\n$sub"
                textSize = 16f
                gravity = android.view.Gravity.CENTER_VERTICAL
                setTextColor(0xFFF2F2F2.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        val w = if (row.width > 0) row.width else dp(320)
        val h = if (row.height > 0) row.height else dp(52)
        shadow.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        shadow.layout(0, 0, shadow.measuredWidth, shadow.measuredHeight)
        return shadow
    }

    private class AppRowShadow(
        shadowView: View,
        private val touchX: Int,
        private val touchY: Int
    ) : View.DragShadowBuilder(shadowView) {
        override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
            val v = view
            val w = v?.measuredWidth?.takeIf { it > 0 } ?: v?.width ?: 0
            val h = v?.measuredHeight?.takeIf { it > 0 } ?: v?.height ?: 0
            outShadowSize.set(w, h)
            outShadowTouchPoint.set(touchX.coerceIn(0, w), touchY.coerceIn(0, h))
        }

        override fun onDrawShadow(canvas: Canvas) {
            view?.draw(canvas)
        }
    }

    private fun ensurePlaceholder(): View {
        dragPlaceholder?.let { return it }
        val h = if (draggedRowHeight > 0) draggedRowHeight else dp(52)
        val v = View(this).apply {
            setBackgroundColor(0x33FFFFFF)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h
            ).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
                topMargin = dp(2)
                bottomMargin = dp(2)
            }
            // Round 28: the placeholder must NEVER act as a hover target —
            // it consumes drag events in place (DROP still commits) so
            // hovering it can't relocate it and thrash every frame.
            setOnDragListener(View.OnDragListener { _, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED ->
                        event.clipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
                    DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION,
                    DragEvent.ACTION_DRAG_EXITED -> true
                    DragEvent.ACTION_DROP -> {
                        commitDrop()
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        clearDragState()
                        true
                    }
                    else -> true
                }
            })
        }
        dragPlaceholder = v
        return v
    }

    /**
     * Round 28: placeholder hit-test exclusion — coordinates are relative
     * to appList (the container receiving the event); the placeholder is a
     * direct child so its left/top/right/bottom are in the same space.
     */
    private fun isOverPlaceholder(x: Float, y: Float): Boolean {
        val ph = dragPlaceholder ?: return false
        if (ph.parent !== appList) return false
        return x >= ph.left && x <= ph.right && y >= ph.top && y <= ph.bottom
    }

    private fun placePlaceholderBefore(target: View) {
        val ph = ensurePlaceholder()
        (ph.parent as? ViewGroup)?.removeView(ph)
        val idx = appList.indexOfChild(target)
        if (idx >= 0) appList.addView(ph, idx) else appList.addView(ph)
        dragInsertIndex = appList.indexOfChild(ph)
    }

    private fun placePlaceholderAfter(target: View) {
        val ph = ensurePlaceholder()
        (ph.parent as? ViewGroup)?.removeView(ph)
        val idx = appList.indexOfChild(target)
        if (idx >= 0) appList.addView(ph, idx + 1) else appList.addView(ph)
        dragInsertIndex = appList.indexOfChild(ph)
    }

    private fun placePlaceholderAtEnd() {
        val ph = ensurePlaceholder()
        (ph.parent as? ViewGroup)?.removeView(ph)
        appList.addView(ph)
        dragInsertIndex = appList.indexOfChild(ph)
    }

    /** Insertion index = non-dragged rows strictly before the placeholder. */
    private fun placeholderInsertIndex(): Int {
        val ph = dragPlaceholder ?: return -1
        var count = 0
        for (i in 0 until appList.childCount) {
            val child = appList.getChildAt(i)
            if (child === ph) return count
            if (child.tag is String && child.tag != draggedAppId) count++
        }
        return count
    }

    private fun clearDragState() {
        dragPlaceholder?.let { (it.parent as? ViewGroup)?.removeView(it) }
        dragPlaceholder = null
        dragInsertIndex = -1
        draggedAppId = null
        draggedRowHeight = 0
    }

    private fun commitDrop(): Boolean {
        val draggedId = draggedAppId ?: return false
        val at = placeholderInsertIndex().takeIf { it >= 0 } ?: -1
        val all = store.loadApps()
        val dragged = all.firstOrNull { it.id == draggedId }
        if (dragged == null) {
            clearDragState()
            return false
        }
        all.removeAll { it.id == draggedId }
        if (at < 0) all.add(dragged) else all.add(at.coerceIn(0, all.size), dragged)
        store.saveApps(all)
        clearDragState()
        render()
        return true
    }

    private fun appDropListener(targetId: String): View.OnDragListener {
        return View.OnDragListener { v, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED ->
                    event.clipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
                DragEvent.ACTION_DRAG_ENTERED -> {
                    v.setBackgroundColor(0x33FFFFFF)
                    if (draggedAppId != null && targetId != draggedAppId) {
                        if (event.y < v.height / 2f) placePlaceholderBefore(v)
                        else placePlaceholderAfter(v)
                    }
                    true
                }
                DragEvent.ACTION_DRAG_LOCATION -> {
                    if (draggedAppId != null && targetId != draggedAppId) {
                        if (event.y < v.height / 2f) placePlaceholderBefore(v)
                        else placePlaceholderAfter(v)
                    }
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    v.setBackgroundColor(0x00000000)
                    true
                }
                DragEvent.ACTION_DROP -> {
                    v.setBackgroundColor(0x00000000)
                    commitDrop()
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    v.setBackgroundColor(0x00000000)
                    clearDragState()
                    true
                }
                else -> true
            }
        }
    }

    private fun appListDropListener(): View.OnDragListener {
        return View.OnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED ->
                    event.clipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
                DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
                    // Round 28: the placeholder is never a hover target —
                    // hovering it leaves it exactly where it is.
                    if (draggedAppId != null && !isOverPlaceholder(event.x, event.y)) {
                        placePlaceholderAtEnd()
                    }
                    true
                }
                DragEvent.ACTION_DROP -> {
                    commitDrop()
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    clearDragState()
                    true
                }
                else -> true
            }
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

        // Round 28: never scan when the TV is offline — a quick REST probe
        // decides; offline goes straight to the catalog with an offline
        // notice, no WebSocket query is ever kicked off.
        val checking = AlertDialog.Builder(this)
            .setTitle("Add apps")
            .setMessage("Checking ${target.nick}…")
            .setCancelable(false)
            .create()
        checking.show()
        thread {
            val reachable = isTvReachable(target.host)
            runOnUiThread {
                try {
                    checking.dismiss()
                } catch (_: Exception) {
                }
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!reachable) {
                    showCatalogBrowser("${target.nick} is offline. ")
                    return@runOnUiThread
                }
                queryTvApps(target)
            }
        }
    }

    /** Round 28: lightweight offline probe — plain REST GET, short timeouts. */
    private fun isTvReachable(host: String): Boolean {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = java.net.URL("http://$host:8001/api/v2/").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.connect()
            conn.responseCode in 200..499
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    /** Round 28: the online half of addAppFlow — WebSocket installed-app query. */
    private fun queryTvApps(target: Tv) {
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
            // ViewHolder cached in row.tag: IconLoader tracks its URL via a
            // keyed tag and never touches the plain tag, so recycled rows
            // always resolve their views (previously findViewWithTag("logo")
            // returned null after loadInto overwrote the "logo" tag).
            override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
                val app = visible[pos]
                val row: LinearLayout
                val holder: CatalogRow
                if (convertView is LinearLayout && convertView.tag is CatalogRow) {
                    row = convertView
                    holder = row.tag as CatalogRow
                } else {
                    row = LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(4), dp(6), dp(4), dp(6))
                        addView(CheckBox(context).apply {
                            isFocusable = false
                            isClickable = false
                        })
                        addView(ImageView(context).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                                marginStart = dp(8)
                                marginEnd = dp(8)
                            }
                        })
                        addView(TextView(context).apply {
                            textSize = 16f
                            layoutParams = LinearLayout.LayoutParams(
                                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                            )
                        })
                    }
                    holder = CatalogRow(
                        row.getChildAt(0) as CheckBox,
                        row.getChildAt(1) as ImageView,
                        row.getChildAt(2) as TextView
                    )
                    row.tag = holder
                }
                holder.check.isChecked = selected.contains(app.primaryId)
                holder.name.text = app.name
                IconLoader.loadInto(holder.logo, app.icon)
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

    /**
     * Add/edit app-button dialog (round 27): same fields + Save/Cancel
     * behavior as before; rows show Edit only, so Delete lives here — a red
     * text button on the left of the dialog's actions row (edit mode only).
     */
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
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add app button" else "Edit app button")
            .setView(layout)
            .create()
        if (existing == null) {
            actions.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        } else {
            actions.addView(Button(this).apply {
                text = "Delete"
                textSize = 14f
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                isAllCaps = false
                includeFontPadding = false
                minWidth = 0
                minimumWidth = 0
                setBackgroundColor(0x00000000)
                setTextColor(0xFFFF453A.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    store.saveApps(store.loadApps().filter { it.id != existing.id })
                    render()
                    dialog.dismiss()
                }
            })
            actions.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        actions.addView(Button(this).apply {
            text = "Cancel"
            textSize = 14f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setBackgroundColor(0x00000000)
            setTextColor(0xFFF2F2F2.toInt())
            setOnClickListener { dialog.dismiss() }
        })
        actions.addView(Button(this).apply {
            text = "Save"
            textSize = 14f
            isAllCaps = false
            includeFontPadding = false
            minWidth = 0
            minimumWidth = 0
            setBackgroundColor(0x00000000)
            setTextColor(0xFFF2F2F2.toInt())
            setOnClickListener {
                val l = label.text.toString().trim()
                val a = appId.text.toString().trim()
                val icon = iconUrl.text.toString().trim().ifEmpty { null }
                if (l.isEmpty() || a.isEmpty()) {
                    toast("Label and app ID are both required")
                    return@setOnClickListener
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
                dialog.dismiss()
            }
        })
        layout.addView(actions)
        dialog.show()
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
