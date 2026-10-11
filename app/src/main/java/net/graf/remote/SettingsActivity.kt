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

    /** Round-29 drag state: id + row height captured at drag start, the
     * hidden original row, plus the visible placeholder marking the
     * would-be insertion point. */
    private var draggedAppId: String? = null
    private var draggedRowHeight: Int = 0
    private var draggedRow: View? = null
    private var dragPlaceholder: View? = null

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
            tvList.addView(tvRow(tv.nick, sub, onEdit = { tvDialog(tv) }))
        }
        appList.removeAllViews()
        val apps = store.loadApps()
        // Round 30: universal icon matching — backfill any stored button
        // (preseeded defaults, TV-API adds, manual entries) against the
        // vendored catalog so matched rows get the catalog logo.
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
        if (apps.isEmpty()) appList.addView(hint("No app buttons."))
        for (app in apps) {
            appList.addView(appRow(app))
        }
    }

    /**
     * TV row (round 30): two-line typography — name at 16sp, IP/host line
     * 2sp smaller in light gray — plus Edit glyph ONLY. TV rows stay
     * non-draggable.
     */
    private fun tvRow(name: String, sub: String, onEdit: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = name
                    textSize = 16f
                    setTextColor(0xFFF2F2F2.toInt())
                    gravity = android.view.Gravity.CENTER_VERTICAL
                })
                addView(TextView(context).apply {
                    text = sub
                    textSize = 14f
                    setTextColor(0xFFAAAAAA.toInt())
                    gravity = android.view.Gravity.CENTER_VERTICAL
                })
            }
            addView(texts)
            addView(editGlyph(onEdit))
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
    }

    /** Round 29 row() kept as a thin alias for the TV row. */
    private fun row(text: String, onEdit: () -> Unit): LinearLayout {
        val parts = text.split("\n", limit = 2)
        return tvRow(parts.getOrElse(0) { text }, parts.getOrElse(1) { "" }, onEdit)
    }

    /**
     * Round 29: flat Material "edit" pencil (ic_edit) shared by TV and app
     * rows — plain, matching the simplicity of the other glyphs.
     */
    private fun editGlyph(onEdit: () -> Unit): ImageView {
        return ImageView(this).apply {
            setImageResource(R.drawable.ic_edit)
            contentDescription = "Edit"
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onEdit() }
        }
    }

    /**
     * App-button row (round 30): the WHOLE row is the drag handle — no
     * handle glyph (rows aren't tappable, so no gesture conflict). Label is
     * two-line typography (name 16sp, app ID 14sp light gray) + Edit glyph
     * ONLY (Delete lives in the edit dialog). The row starts a
     * drag-and-drop reorder with a placeholder gap: the original row is GONE
     * (no layout gap) while its shadow is dragged, and a single centralized
     * hover path on the container snaps the placeholder above/below the
     * hovered row by pointer half — symmetric in both directions, first
     * position reachable. TV rows stay non-draggable.
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
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = app.label
                    textSize = 16f
                    setTextColor(0xFFF2F2F2.toInt())
                    gravity = android.view.Gravity.CENTER_VERTICAL
                })
                addView(TextView(context).apply {
                    text = sub
                    textSize = 14f
                    setTextColor(0xFFAAAAAA.toInt())
                    gravity = android.view.Gravity.CENTER_VERTICAL
                })
            }
            val edit = editGlyph { appDialog(app) }
            addView(texts)
            addView(edit)
            setPadding(dp(4), dp(6), dp(4), dp(6))
            tag = app.id
            setOnTouchListener { v, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN) {
                    draggedAppId = app.id
                    draggedRowHeight = row.height
                    draggedRow = row
                    val tx = ev.x.toInt()
                    val ty = ev.y.toInt()
                    val shadowView = buildAppRowShadow(app, sub, row)
                    val data = ClipData.newPlainText("app-button", app.id)
                    v.startDragAndDrop(data, AppRowShadow(shadowView, tx, ty), app.id, 0)
                    // Round 30: the original row is GONE (no layout slot)
                    // while its shadow is dragged — the placeholder alone
                    // models the future slot, so the visible list never
                    // keeps a gap where the row was.
                    row.visibility = View.GONE
                    true
                } else {
                    false
                }
            }
        }
        return row
    }

    /**
     * Drag shadow (round 30): a detached clone showing the label ONLY (no
     * handle, no Edit/Delete buttons) over a solid opaque background, drawn
     * at the touch point passed in from the row.
     */
    private fun buildAppRowShadow(app: AppButton, sub: String, row: LinearLayout): View {
        val shadow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF2E2E33.toInt())
            setPadding(dp(4), dp(6), dp(4), dp(6))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = app.label
                    textSize = 16f
                    setTextColor(0xFFF2F2F2.toInt())
                })
                addView(TextView(context).apply {
                    text = sub
                    textSize = 14f
                    setTextColor(0xFFAAAAAA.toInt())
                })
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
            // Round 29: the placeholder must NEVER act as a hover target —
            // it consumes drag events in place (DROP still commits) so
            // hovering it is a no-op and can't relocate it and thrash.
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
     * Round 30: centralized gapless hover hit-test (coordinates relative to
     * appList, the container receiving the event). App rows partition the
     * container's full vertical span into continuous intervals split at the
     * midpoints between consecutive rows — no pixel may belong to no row,
     * so the ~1px dead zone between rows can never throw the placeholder
     * to the bottom. The GONE original row is excluded (it has no slot);
     * touch over the placeholder itself (margins included) is a no-op.
     */
    private fun handleHover(y: Float) {
        if (draggedAppId == null) return
        val ph = dragPlaceholder
        if (ph != null && ph.parent === appList) {
            val lp = ph.layoutParams as? LinearLayout.LayoutParams
            val top = ph.top - (lp?.topMargin ?: 0)
            val bottom = ph.bottom + (lp?.bottomMargin ?: 0)
            if (y >= top && y <= bottom) return
        }
        val rows = mutableListOf<View>()
        for (i in 0 until appList.childCount) {
            val c = appList.getChildAt(i)
            if (c === ph) continue
            if (c.tag !is String) continue
            if (c.tag == draggedAppId) continue
            rows.add(c)
        }
        if (rows.isEmpty()) return
        rows.sortBy { it.top }
        var target: View? = null
        var above = true
        for (i in rows.indices) {
            val c = rows[i]
            val topEdge = if (i == 0) Float.NEGATIVE_INFINITY
                else (rows[i - 1].bottom + c.top) / 2f
            val bottomEdge = if (i == rows.size - 1) Float.POSITIVE_INFINITY
                else (c.bottom + rows[i + 1].top) / 2f
            if (y >= topEdge && y < bottomEdge) {
                target = c
                above = y < c.top + c.height / 2f
                break
            }
        }
        if (target == null) {
            target = rows.last()
            above = false
        }
        movePlaceholder(target, above)
    }

    /**
     * Round 29: placeholder snapping — above the hovered row when the
     * touch is on its top 50%, below it on the bottom 50%. The target
     * index is computed in placeholder-excluded space, so re-hovering the
     * slot the placeholder already models is a no-op instead of a
     * remove+re-add layout thump.
     */
    private fun movePlaceholder(target: View, above: Boolean) {
        val ph = ensurePlaceholder()
        val current = appList.indexOfChild(ph)
        var idx = appList.indexOfChild(target)
        if (idx < 0) return
        if (current >= 0 && idx > current) idx--
        if (!above) idx++
        if (idx == current) return // already there: no-op
        (ph.parent as? ViewGroup)?.removeView(ph)
        appList.addView(ph, idx.coerceIn(0, appList.childCount))
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
        draggedAppId = null
        draggedRowHeight = 0
        // Round 30: un-hide the GONE original row (commitDrop() re-renders
        // right after, so this only matters for cancelled drags).
        draggedRow?.visibility = View.VISIBLE
        draggedRow = null
    }

    private fun commitDrop(): Boolean {
        val draggedId = draggedAppId ?: return false
        val ph = dragPlaceholder
        if (ph == null || ph.parent !== appList) {
            // Dropped without ever hovering a slot: order unchanged.
            clearDragState()
            render()
            return true
        }
        val at = placeholderInsertIndex()
        val all = store.loadApps()
        val dragged = all.firstOrNull { it.id == draggedId }
        if (dragged == null) {
            clearDragState()
            return false
        }
        all.removeAll { it.id == draggedId }
        all.add(at.coerceIn(0, all.size), dragged)
        store.saveApps(all)
        clearDragState()
        render()
        return true
    }

    /**
     * Round 30: the container is the ONLY hover target — rows carry no
     * drag listeners of their own. Every ENTERED/LOCATION hit-tests by
     * gapless coordinates (handleHover), so no pixel belongs to no row;
     * the past-the-last-row area resolves to the last row instead of
     * thrashing the placeholder to the bottom.
     */
    private fun appListDropListener(): View.OnDragListener {
        return View.OnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED ->
                    event.clipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
                DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
                    if (draggedAppId != null) handleHover(event.y)
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

    /**
     * Add/edit TV dialog (round 30): fields in the content view, actions as
     * AlertDialog positive (Save) / negative (Cancel) / neutral (Delete in
     * edit mode only) buttons — no hand-built action rows. The window wraps
     * to its contents, never full-height.
     */
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
        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add TV" else "Edit TV")
            .setView(layout)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
        if (existing != null) {
            builder.setNeutralButton("Delete", null)
        }
        val dialog = builder.create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val n = nick.text.toString().trim()
            val h = host.text.toString().trim()
            if (n.isEmpty() || h.isEmpty()) {
                toast("Nickname and IP are both required")
                return@setOnClickListener
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
            dialog.dismiss()
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
            dialog.dismiss()
        }
        if (existing != null) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.let { delete ->
                delete.setTextColor(0xFFFF453A.toInt())
                delete.setOnClickListener {
                    store.saveTvs(store.loadTvs().filter { it.id != existing.id })
                    render()
                    dialog.dismiss()
                }
            }
        }
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
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
            // Round 30: the TV results dialog also offers the catalog
            // browser (neutral button) — a working TV query no longer hides
            // the catalog. Manual entry stays one tap away inside the
            // catalog browser.
            AlertDialog.Builder(this)
                .setTitle("Add apps from ${target.nick}")
                .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton("Add selected") { _, _ ->
                    val catalog = try {
                        AppCatalog.load(this)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val all = store.loadApps()
                    val have = all.flatMap { it.launchIds() }.toSet()
                    var added = 0
                    found.forEachIndexed { i, info ->
                        if (checked[i] && !have.contains(info.appId) &&
                            all.none { it.launchIds().contains(info.appId) }
                        ) {
                            // Round 30: TV-API adds match the catalog by
                            // name or app ID so they get the logo tile.
                            val match = AppCatalog.findMatch(catalog, info.name, info.appId)
                            val ids = if (match != null) {
                                (listOf(info.appId) + match.ids).distinct()
                            } else {
                                listOf(info.appId)
                            }
                            val icon = match?.icon?.ifBlank { null }
                            all.add(AppButton(UUID.randomUUID().toString(), info.name, info.appId, ids, icon))
                            added++
                        }
                    }
                    store.saveApps(all)
                    render()
                    toast(if (added > 0) "Added $added app(s)" else "Nothing new selected")
                }
                .setNeutralButton("Browse catalog") { _, _ -> showCatalogBrowser(null) }
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
        // Round 30: wrap to content height — never full-height.
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * Add/edit app-button dialog (round 30): fields in the content view,
     * actions as AlertDialog positive (Save) / negative (Cancel) / neutral
     * (Delete in edit mode only) buttons — no hand-built action rows.
     * Manual entries are matched against the vendored catalog by name or
     * app ID so they get the catalog logo + fallback IDs. The window wraps
     * to its contents, never full-height.
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
        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add app button" else "Edit app button")
            .setView(layout)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
        if (existing != null) {
            builder.setNeutralButton("Delete", null)
        }
        val dialog = builder.create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val l = label.text.toString().trim()
            val a = appId.text.toString().trim()
            val typedIcon = iconUrl.text.toString().trim().ifEmpty { null }
            if (l.isEmpty() || a.isEmpty()) {
                toast("Label and app ID are both required")
                return@setOnClickListener
            }
            val catalog = try {
                AppCatalog.load(this)
            } catch (_: Exception) {
                emptyList()
            }
            val match = AppCatalog.findMatch(catalog, l, a)
            val resolvedIcon = typedIcon ?: match?.icon?.ifBlank { null }
            val resolvedIds = if (match != null) {
                (listOf(a) + match.ids).distinct()
            } else {
                listOf(a)
            }
            val all = store.loadApps()
            if (existing == null) {
                all.add(AppButton(UUID.randomUUID().toString(), l, a, resolvedIds, resolvedIcon))
            } else {
                all.firstOrNull { it.id == existing.id }?.let {
                    it.label = l
                    if (a != it.appId) {
                        // Changed primary ID: reset the fallback chain
                        // around the typed ID (+ catalog fallbacks).
                        it.appId = a
                        it.appIds = resolvedIds
                        it.iconUrl = resolvedIcon
                    } else if (match != null) {
                        // Unchanged ID with a catalog match: an explicit
                        // typed icon wins, otherwise keep the stored one
                        // and fill blanks from the catalog.
                        if (typedIcon != null) it.iconUrl = typedIcon
                        else if (it.iconUrl.isNullOrBlank()) it.iconUrl = resolvedIcon
                        val merged = (it.launchIds() + match.ids).distinct()
                        if (merged.size > it.launchIds().size) it.appIds = merged
                    } else {
                        it.iconUrl = typedIcon
                    }
                }
            }
            store.saveApps(all)
            render()
            dialog.dismiss()
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
            dialog.dismiss()
        }
        if (existing != null) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.let { delete ->
                delete.setTextColor(0xFFFF453A.toInt())
                delete.setOnClickListener {
                    store.saveApps(store.loadApps().filter { it.id != existing.id })
                    render()
                    dialog.dismiss()
                }
            }
        }
        // Round 29: fit/wrap to contents — never stretch full height.
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
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
                btn.text = "Scan"
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
