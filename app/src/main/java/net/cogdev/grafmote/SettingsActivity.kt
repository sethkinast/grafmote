package net.cogdev.grafmote

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
import android.view.ViewTreeObserver
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
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
    /**
     * Round 32: pending long-jump placeholder index. A move of more than
     * one slot is applied only when two consecutive hover events agree on
     * the same destination — a single-frame glitch (e.g. a spurious hit
     * on the first row mid-drag) is held, never applied.
     */
    private var pendingJumpIndex: Int? = null
    /**
     * Round 35: settle gate. True from drag start (row GONE + placeholder
     * inserted at the original index) until the post-insert layout pass
     * completes. While true, hover events hold the placeholder at its
     * start slot instead of hit-testing against stale mid-layout geometry
     * (top-half-of-B reading as "above A" -> index 0).
     * Round 37: the same gate is re-armed after EVERY placeholder move
     * (see armSettleGate), so hover events arriving between a move and its
     * layout pass hold instead of hit-testing stale row geometry.
     */
    private var dragSettling = false
    private var dragSettleListener: ViewTreeObserver.OnGlobalLayoutListener? = null

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
     * at 12sp in #808080 gray — plus Edit glyph ONLY. TV rows stay
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
                    textSize = 12f
                    setTextColor(0xFF808080.toInt())
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
     * two-line typography (name 16sp, app ID 12sp #808080 gray) + Edit glyph
     * ONLY (Delete lives in the edit dialog). The row starts a
     * drag-and-drop reorder with a placeholder gap: the original row is GONE
     * (no layout gap) while its shadow is dragged, and a single centralized
     * hover path on the container snaps the placeholder above/below the
     * hovered row by pointer half — symmetric in both directions, first
     * position reachable. TV rows stay non-draggable.
     *
     * Round 40: a small app logo leads every row — the stored iconUrl via
     * IconLoader (round-30 catalog enrich already backfilled it), with the
     * letter tile underneath as the fallback when no logo matches (or while
     * offline, since the ImageView starts GONE).
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
                    textSize = 12f
                    setTextColor(0xFF808080.toInt())
                    gravity = android.view.Gravity.CENTER_VERTICAL
                })
            }
            val edit = editGlyph { appDialog(app) }
            addView(appRowLogo(app))
            addView(texts)
            addView(edit)
            setPadding(dp(4), dp(6), dp(4), dp(6))
            tag = app.id
            setOnTouchListener { v, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN) {
                    draggedAppId = app.id
                    draggedRowHeight = row.height
                    draggedRow = row
                    val startIndex = appList.indexOfChild(row)
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
                    // Round 35: insert the placeholder at the dragged row's
                    // ORIGINAL index — no hit-testing, no 50/50 computation
                    // at start, so the placeholder always begins exactly
                    // where the row was regardless of where in the row the
                    // touch landed. Then gate hover moves until layout
                    // settles (one-shot global-layout listener, not a
                    // posted Runnable) so stale mid-layout geometry can't
                    // jump the placeholder on the first hover frames.
                    // Round 37: arming lives in armSettleGate(), re-armed
                    // after every placeholder move for the same hazard.
                    pendingJumpIndex = null
                    val ph = ensurePlaceholder()
                    (ph.parent as? ViewGroup)?.removeView(ph)
                    appList.addView(ph, startIndex.coerceIn(0, appList.childCount))
                    armSettleGate()
                    true
                } else {
                    false
                }
            }
        }
        return row
    }

    /**
     * Round 40: small leading logo for an app row — a 40dp square with the
     * letter tile underneath and the catalog logo on top via IconLoader.
     * When the button has no iconUrl (no catalog match) the ImageView stays
     * GONE and the letter shows; when it does, the logo covers the letter
     * once loaded (offline it stays GONE, so the letter is the fallback).
     */
    private fun appRowLogo(app: AppButton): FrameLayout {
        val size = dp(40)
        val holder = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(8)
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        val letter = (app.label.trim().firstOrNull()?.uppercase() ?: "?")
        holder.addView(TextView(this).apply {
            text = letter
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(0xFFF2F2F2.toInt())
            includeFontPadding = false
            setBackgroundResource(R.drawable.btn_face_selector)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        })
        val logo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = app.label
            setPadding(0, 0, 0, 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        holder.addView(logo)
        IconLoader.loadInto(logo, app.iconUrl?.trim().orEmpty())
        return holder
    }

    /**
     * Drag shadow (round 30): a detached clone showing the label ONLY (no
     * handle, no Edit/Delete buttons) over a solid opaque background, drawn
     * at the touch point passed in from the row.
     * Round 40: leads with the same logo/letter tile as the row.
     */
    private fun buildAppRowShadow(app: AppButton, sub: String, row: LinearLayout): View {
        val shadow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF2E2E33.toInt())
            setPadding(dp(4), dp(6), dp(4), dp(6))
            addView(appRowLogo(app))
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
                    textSize = 12f
                    setTextColor(0xFF808080.toInt())
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

    /**
     * Round 36: the placeholder's TOTAL vertical footprint (view height +
     * top/bottom margins) is EXACTLY the dragged row's measured height, so
     * inserting or moving it causes zero layout shift in subsequent rows.
     * The 2dp top/bottom margins are subtracted from the view height to
     * compensate — height + margins == row height, padding included.
     */
    private fun ensurePlaceholder(): View {
        dragPlaceholder?.let { return it }
        val total = if (draggedRowHeight > 0) draggedRowHeight else dp(52)
        val marginTop = dp(2)
        val marginBottom = dp(2)
        val h = (total - marginTop - marginBottom).coerceAtLeast(0)
        val v = View(this).apply {
            setBackgroundColor(0x33FFFFFF)
            minimumHeight = h
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h
            ).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
                topMargin = marginTop
                bottomMargin = marginBottom
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
     *
     * Round 32: HOLD-ON-MISS debounce (the actual thrash fix — gapless
     * hit-testing above stays as defense in depth). The placeholder moves
     * ONLY on a confident new hit; every no-confident-hit case holds the
     * last valid placeholder position and returns without moving it:
     * touch over the placeholder itself, touch over no row (above the
     * first row / below the last row), an empty row set, an unreliable
     * mid-layout frame (zero-height rows), a non-finite coordinate, or a
     * gapless miss (target == null — held, never defaulted to any slot).
     * A distant jump (>1 slot in one event, e.g. mid-list to position 0
     * for a single frame) additionally requires a second consecutive
     * event agreeing on the same destination before it is applied;
     * adjacent moves stay instant. First position stays reachable by
     * hovering the top half of the first row; above/below-50% snapping,
     * the GONE original row, and whole-row drag are unchanged.
     */
    private fun handleHover(y: Float) {
        if (draggedAppId == null) return
        // Round 35: settle gate — hold the start slot until the
        // post-insert layout pass completes; never hit-test mid-layout.
        if (dragSettling) return
        // No confident coordinate, no move.
        if (!y.isFinite()) {
            pendingJumpIndex = null
            return
        }
        val ph = dragPlaceholder
        if (ph != null && ph.parent === appList) {
            val lp = ph.layoutParams as? LinearLayout.LayoutParams
            val top = ph.top - (lp?.topMargin ?: 0)
            val bottom = ph.bottom + (lp?.bottomMargin ?: 0)
            if (y >= top && y <= bottom) {
                pendingJumpIndex = null
                return
            }
        }
        val rows = mutableListOf<View>()
        for (i in 0 until appList.childCount) {
            val c = appList.getChildAt(i)
            if (c === ph) continue
            if (c.tag !is String) continue
            if (c.tag == draggedAppId) continue
            rows.add(c)
        }
        if (rows.isEmpty()) {
            pendingJumpIndex = null
            return
        }
        rows.sortBy { it.top }
        // Mid-layout frame with unreliable geometry: hold, don't guess.
        for (c in rows) {
            if (c.height <= 0 || c.bottom <= c.top) {
                pendingJumpIndex = null
                return
            }
        }
        // Touch above the first row or below the last row is over no row:
        // hold the last valid position instead of snapping to an end.
        // (Both ends stay reachable by hovering the end rows themselves.)
        val contentTop = rows.first().top
        val contentBottom = rows.last().bottom
        if (y < contentTop || y > contentBottom) {
            pendingJumpIndex = null
            return
        }
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
            // Gapless hit-test missed: HOLD — never fall through to a
            // default slot (that default is what snapped the placeholder
            // to position 0 for single frames mid-drag).
            pendingJumpIndex = null
            return
        }
        movePlaceholderDebounced(target, above)
    }

    /**
     * Round 32: debounced placeholder move. Adjacent (<=1 slot) moves apply
     * instantly so normal dragging feels identical; a distant jump applies
     * only when two consecutive hover events agree on the same destination
     * insertion index, which kills single-frame thrash (e.g. ph@3 -> ph@0
     * -> ph@1 while the finger drags steadily downward) regardless of
     * which dead pixel or spurious coordinate caused the bad frame.
     */
    private fun movePlaceholderDebounced(target: View, above: Boolean) {
        val ph = dragPlaceholder
        val current = if (ph != null && ph.parent === appList) appList.indexOfChild(ph) else -1
        var idx = appList.indexOfChild(target)
        if (idx < 0) {
            pendingJumpIndex = null
            return
        }
        if (current >= 0 && idx > current) idx--
        if (!above) idx++
        idx = idx.coerceIn(0, appList.childCount)
        if (current >= 0 && idx == current) {
            pendingJumpIndex = null
            return // already there: no-op
        }
        if (current < 0 || kotlin.math.abs(idx - current) <= 1) {
            pendingJumpIndex = null
            movePlaceholder(target, above)
            return
        }
        if (pendingJumpIndex == idx) {
            pendingJumpIndex = null
            movePlaceholder(target, above)
        } else {
            pendingJumpIndex = idx
        }
    }

    /**
     * Round 29: placeholder snapping — above the hovered row when the
     * touch is on its top 50%, below it on the bottom 50%. The target
     * index is computed in placeholder-excluded space, so re-hovering the
     * slot the placeholder already models is a no-op instead of a
     * remove+re-add layout thump.
     * Round 37: after an actual move, re-arm the settle gate — hover
     * events arriving before the move's layout pass hold instead of
     * hit-testing stale row geometry.
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
        armSettleGate()
    }

    /**
     * Round 37: one-shot settle-gate arming, extracted from the drag-start
     * path and reused after every placeholder move. Sets [dragSettling]
     * until the next layout pass completes; hover events hold while set
     * (see handleHover). Replaces any previously armed listener so at
     * most one is ever registered. At most one frame of hold per move —
     * drag feel is otherwise identical.
     */
    private fun armSettleGate() {
        dragSettling = true
        dragSettleListener?.let { prev ->
            try {
                appList.viewTreeObserver.removeOnGlobalLayoutListener(prev)
            } catch (_: Exception) {
            }
        }
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                try {
                    appList.viewTreeObserver.removeOnGlobalLayoutListener(this)
                } catch (_: Exception) {
                }
                if (dragSettleListener === this) dragSettleListener = null
                dragSettling = false
                pendingJumpIndex = null
            }
        }
        dragSettleListener = listener
        try {
            appList.viewTreeObserver.addOnGlobalLayoutListener(listener)
        } catch (_: Exception) {
            dragSettleListener = null
            dragSettling = false
        }
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
        dragSettleListener?.let { listener ->
            try {
                appList.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            } catch (_: Exception) {
            }
        }
        dragSettleListener = null
        dragSettling = false
        dragPlaceholder?.let { (it.parent as? ViewGroup)?.removeView(it) }
        dragPlaceholder = null
        draggedAppId = null
        draggedRowHeight = 0
        pendingJumpIndex = null
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
     * drag listeners of their own. Every LOCATION hit-tests by
     * gapless coordinates (handleHover), so no pixel belongs to no row.
     *
     * Round 32: touches outside the row span (including past-the-last-row)
     * HOLD the last valid placeholder position instead of resolving to an
     * end slot — the ends stay reachable by hovering the end rows, and a
     * drop past the last row commits the held (append) position.
     *
     * Round 39: ACTION_DRAG_ENTERED never drives the hit-test — it
     * carries y=0.0 on child→parent re-entry (proven by logcat tag
     * GrafDND: drag exiting the placeholder, which swallows events with
     * its own listener, then re-entering the container delivers ENTERED
     * with y=0.0, which hit-tests to the first row above=true and
     * applies an instant adjacent 1→0 move for one frame before the next
     * real LOCATION recovers). Only ACTION_DRAG_LOCATION carries real
     * coordinates. This loses nothing: round 35 already places the
     * placeholder at the dragged row's original index at drag start, and
     * every subsequent real position arrives via LOCATION.
     */
    private fun appListDropListener(): View.OnDragListener {
        return View.OnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED ->
                    event.clipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
                DragEvent.ACTION_DRAG_ENTERED -> true
                DragEvent.ACTION_DRAG_LOCATION -> {
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
