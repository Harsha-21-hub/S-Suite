package com.hesi.snotes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The note canvas.
 *
 * Requirement 6 - the redraw path was reworked for battery/CPU:
 *   - strokes are vector data, and each one caches a Path that is now appended
 *     to incrementally instead of being rebuilt on every MOVE event
 *   - onDraw culls against the actual dirty clip, so a moving pen only
 *     rasterises the few hundred pixels around the nib
 *   - drawing invalidates a rectangle, not the whole view
 *   - the laser animates only its own bounds and stops posting frames the
 *     moment it has faded out
 */
class DrawingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Tool { PEN, ERASER, STROKE_ERASER, LASER, SELECT, SHAPE, TEXT }

    interface Listener {
        /** Called while the user pans/zooms/scrolls (used to auto-hide toolbars). */
        fun onGesture()
        /** Any touch that lands on the canvas - used to close open dropdowns. */
        fun onCanvasTouch()
        fun onSelection(active: Boolean)
        fun onHistory(canUndo: Boolean, canRedo: Boolean)
        /** Fired once, the first time a stylus is seen on this device. */
        fun onStylusDetected()
        /** Text/image object selected from the canvas. */
        fun onEditText(obj: TextObject) {}
        fun onEditImage(obj: ImageObject) {}
        /** User chose the text tool and tapped a page position. */
        fun onCreateTextAt(wx: Float, wy: Float) {}
    }

    var listener: Listener? = null

    // ---------- Document ----------
    val strokes = ArrayList<Stroke>()
    val page = RectF(0f, 0f, 2200f, 3000f)
    var dark = false
        private set

    // ---------- Tool state ----------
    var tool = Tool.PEN
        set(v) {
            field = v
            if (v != Tool.SELECT) clearSelection()
            invalidate()
        }
    var penColor = Color.BLACK
    var penWidth = 5f
    var eraserWidth = 36f

    /** Width of the laser trail, in world units. */
    var laserWidth = 6f

    /** Requirement B3: 0 = infinite length, 1 = limited pages, 2 = infinite pages. */
    var pageMode = NoteStore.PAGE_INFINITE

    /** The shape drawn by Tool.SHAPE. Automatic recognition has been removed. */
    var shapeKind = Shapes.Kind.RECT

    /** Requirement 1: shape stroke width, independent of the pen size. */
    var shapeWidth = 8f

    /**
     * Palm rejection: once a stylus is detected (OnePlus Pad / Samsung Tab S-Pen, ...)
     * fingers no longer draw - a single finger pans, two fingers pan/zoom, and extra
     * touches while the stylus is down (a resting hand) are ignored completely.
     */
    var stylusOnly = false

    private var stylusSeen = false

    // ---------- Viewport ----------
    private var panX = 0f
    private var panY = 0f
    private var zoom = 1f
    private var sizedOnce = false

    // ---------- History ----------
    private val undoStack = ArrayList<Action>()
    private val redoStack = ArrayList<Action>()

    // ---------- Gesture state ----------
    private var mode = MODE_NONE
    private var current: Stroke? = null
    private var drawingWithStylus = false
    private var activePointerId = -1
    private var lastWX = 0f
    private var lastWY = 0f
    private var lastSX = 0f
    private var lastSY = 0f
    private var downSX = 0f
    private var downSY = 0f
    private var pfx = 0f
    private var pfy = 0f
    private var pdist = 0f
    private val pendingRemoved = ArrayList<Pair<Int, Stroke>>()

    // Drawing is deliberately raw: sampled points are stored at a constant width.
    // No pressure-to-width conversion, speed weighting or smoothing is applied.

    // ---------- Manual shape drag ----------
    private var shapeX0 = 0f
    private var shapeY0 = 0f
    private val shapePts = ArrayList<Float>(96)
    private var shapePreview: Stroke? = null
    private val prevShapeBounds = RectF()

    // ---------- Objects (requirement C4) ----------
    // Rendered UNDER the ink so handwriting always goes on top. Interaction only
    // happens with the SELECT tool, so drawing tools never disturb them.
    val textObjects = ArrayList<TextObject>()
    val imageObjects = ArrayList<ImageObject>()
    private val objTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val objBmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    // Requirement 2: the on-canvas transform editor. One object can be "active";
    // it then shows a dashed frame with corner resize handles, a rotate handle
    // and a small toolbar (edit / delete) - like a photo editor.
    private var activeText: TextObject? = null
    private var activeImage: ImageObject? = null
    private var objMode = OBJ_NONE
    private var objDownWX = 0f
    private var objDownWY = 0f
    private var objCX = 0f
    private var objCY = 0f
    private var objStartDist = 1f
    private var objStartAngleDeg = 0f
    private var objStartRot = 0f
    private var objStartSize = 0f
    private var objStartW = 0f
    private var objStartH = 0f
    private var resizeHandle = 0
    private var objStartX = 0f
    private var objStartY = 0f
    private var objStartTextW = 0f
    private var objStartTextH = 0f
    private var objDidChange = false
    private var objGestureBefore: DocumentState? = null
    private val objFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF3A3A3A.toInt()
    }
    private val objHandleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val objHandleLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF3A3A3A.toInt() }
    private val objToolFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xF2242424.toInt() }
    private val objToolIcon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }

    /** Notifies the host when objects change so it can autosave them. */
    var onObjectsChanged: (() -> Unit)? = null

    // ---------- Laser ----------
    // Requirement A1 - GoodNotes-style laser. The COMPLETE current trail stays
    // fully visible while you keep writing. Every new laser point resets the
    // disappearance timer; only after LASER_HOLD_MS with no new input does the
    // whole trail fade out (over LASER_FADE_MS). Resuming before it expires keeps
    // and continues the same trail, so an old timer can never erase new content.
    private val laserSegments = ArrayList<LaserSegment>(8)
    private var activeLaser: LaserSegment? = null
    private val laserPath = Path()
    // Requirement 1: a GLOBAL "last input" clock. While ANY laser writing is
    // happening, every segment stays fully visible; only after LASER_HOLD_MS of
    // no input at all do they fade - staggered oldest-first, so they vanish
    // stroke by stroke. Resuming before a segment is fully gone brings it back.
    private var laserLastInput = 0L

    private class LaserSegment {
        val x = ArrayList<Float>(128)
        val y = ArrayList<Float>(128)
        var lastInput = 0L
    }

    // ---------- Selection ----------
    private val selRect = RectF()
    private var selectingRect = false
    private val selected = ArrayList<Stroke>()
    private val selBounds = RectF()
    private var accDX = 0f
    private var accDY = 0f
    private var accScale = 1f
    private var pivotX = 0f
    private var pivotY = 0f
    private var handleStartDist = 0f

    // ---------- Scrollbars ----------
    private var barGrab = 0f

    private val density = resources.displayMetrics.density
    private val barHit = 16f * density
    private val barThick = 5f * density
    private val minThumb = 42f * density

    // ---------- Paints (allocated once) ----------
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val laserHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFF0D0D.toInt()
    }
    private val laserMid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFF1A1A.toInt()
    }
    private val laserCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val laserDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var haloBlurFor = -1f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val eraserClearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = RED
    }
    private var dashZoom = -1f

    private val clipI = Rect()
    private val visible = RectF()
    private val tmpRect = RectF()
    private val tmpBounds = RectF()

    companion object {
        const val RED = 0xFFD71921.toInt()
        private const val MODE_NONE = 0
        private const val MODE_DRAW = 1
        private const val MODE_PANZOOM = 2
        private const val MODE_SELECT_RECT = 3
        private const val MODE_SEL_MOVE = 4
        private const val MODE_SEL_SCALE = 5
        private const val MODE_BAR_V = 6
        private const val MODE_BAR_H = 7
        private const val MODE_STROKE_ERASE = 8
        private const val MODE_PAN1 = 9
        private const val MODE_LASER = 10
        private const val MODE_SHAPE = 11
        private const val MODE_OBJ_DRAG = 12
        private const val MODE_OBJ_XFORM = 13

        // object sub-gestures
        private const val OBJ_NONE = 0
        private const val OBJ_MOVE = 1
        private const val OBJ_RESIZE = 2
        private const val OBJ_ROTATE = 3
        // Requirement 5: trigger the page expansion well before the pen reaches
        // an edge, and add a large chunk so it always stays comfortably ahead.
        private const val GROW_MARGIN = 650f
        private const val GROW_BY = 2600f
        private const val PAGE_BREAK_H = 3111f   // A4 height for the 2200-wide column
        private const val MIN_ZOOM = 0.15f
        private const val MAX_ZOOM = 8f

        /** Each laser stroke fades independently, like a presentation laser. */
        private const val LASER_HOLD_MS = 600f
        private const val LASER_FADE_MS = 260f
        private const val LASER_STAGGER_MS = 150f  // oldest strokes fade first
        private const val LASER_TAIL_FADE_FRACTION = 0.15f
    }

    // =====================================================================
    //  Public API
    // =====================================================================

    fun setDarkTheme(d: Boolean) {
        if (dark == d) return
        dark = d
        val seen = HashSet<Stroke>()
        fun swap(s: Stroke) {
            if (!s.eraser && seen.add(s)) {
                if (s.color == Color.BLACK) s.color = Color.WHITE
                else if (s.color == Color.WHITE) s.color = Color.BLACK
            }
        }
        for (s in strokes) swap(s)
        for (t in textObjects) {
            if (t.color == Color.BLACK) t.color = Color.WHITE
            else if (t.color == Color.WHITE) t.color = Color.BLACK
        }
        for (stack in arrayOf(undoStack, redoStack)) {
            for (a in stack) when (a) {
                is Action.Add -> a.strokes.forEach(::swap)
                is Action.Remove -> a.items.forEach { swap(it.second) }
                is Action.Move -> a.strokes.forEach(::swap)
                is Action.Scale -> a.strokes.forEach(::swap)
                is Action.Clear -> a.old.forEach(::swap)
                is Action.Document -> {
                    a.before.strokes.forEach { st -> swap(Stroke(ArrayList(st.points), st.color, st.width, st.eraser, st.straight, st.pressures?.let { ArrayList(it) })) }
                    a.after.strokes.forEach { st -> swap(Stroke(ArrayList(st.points), st.color, st.width, st.eraser, st.straight, st.pressures?.let { ArrayList(it) })) }
                }
            }
        }
        if (penColor == Color.BLACK) penColor = Color.WHITE
        else if (penColor == Color.WHITE) penColor = Color.BLACK
        invalidate()
    }

    fun loadNote(newPage: RectF, newStrokes: List<Stroke>) {
        strokes.clear()
        strokes.addAll(newStrokes)
        page.set(newPage)
        undoStack.clear()
        redoStack.clear()
        clearSelection()
        sizedOnce = false
        if (width > 0) {
            fitPage()
            sizedOnce = true
        }
        notifyHistory()
        invalidate()
    }

    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()

    private fun cloneStroke(s: Stroke): Stroke {
        val p = ArrayList(s.points)
        val pr = s.pressures?.let { ArrayList(it) }
        return Stroke(p, s.color, s.width, s.eraser, s.straight, pr).also { it.seal() }
    }

    fun captureState(): DocumentState = DocumentState(
        // Strokes are intentionally NOT copied: object undo/redo (restoreState)
        // only restores objects, so snapshotting every stroke's points here would
        // allocate megabytes on each text/image edit for nothing.
        strokes = emptyList(),
        texts = textObjects.map { TextState(it.text, it.x, it.y, it.w, it.h, it.size, it.color, it.rotation) },
        images = imageObjects.map { ImageState(it.name, it.x, it.y, it.w, it.h, it.rotation, it.bitmap) }
    )

    fun commitDocumentState(before: DocumentState?) {
        if (before == null) return
        val after = captureState()
        push(Action.Document(before, after))
    }

    private fun restoreState(state: DocumentState) {
        // Document actions only ever change text/image objects (never strokes),
        // so we restore OBJECTS ONLY and leave the stroke list untouched. This
        // keeps every existing Stroke instance identical, so the incremental
        // stroke history (Add/Remove/Move/Scale) stays valid even when an object
        // edit happens between two drawing/erasing operations.
        textObjects.clear()
        state.texts.forEach { t ->
            // Keep black/white text visible for the CURRENT theme even if the
            // snapshot was taken under the other theme (requirement 7).
            var c = t.color
            if (dark && c == Color.BLACK) c = Color.WHITE
            else if (!dark && c == Color.WHITE) c = Color.BLACK
            textObjects.add(TextObject(t.text, t.x, t.y, t.w, t.h, t.size, c, t.rotation))
        }
        imageObjects.clear()
        state.images.forEach { im ->
            imageObjects.add(ImageObject(im.name, im.x, im.y, im.w, im.h, im.rotation).also { it.bitmap = im.bitmap })
        }
        activeText = null
        activeImage = null
        selected.clear()
        selectingRect = false
        listener?.onSelection(false)
    }

    /** Reset zoom level while keeping the same canvas position/page in view. */
    fun viewportState(): Triple<Float, Float, Float> = Triple(panX, panY, zoom)

    fun restoreViewport(savedPanX: Float, savedPanY: Float, savedZoom: Float) {
        if (savedPanX.isNaN() || savedPanY.isNaN() || savedZoom.isNaN()) return
        post {
            zoom = savedZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
            panX = savedPanX
            panY = savedPanY
            clampPan()
            invalidate()
        }
    }

    fun resetZoom() {
        if (width == 0 || height == 0) return

        // Preserve the world point currently at the center of the viewport.
        // Resetting zoom must not send the user back to the first page/origin.
        val centerWorldX = (width / 2f - panX) / zoom
        val centerWorldY = (height / 2f - panY) / zoom

        // Keep the existing/default reset zoom level.
        val refW = 2200f + 120f
        val newZoom = (width / refW).coerceIn(MIN_ZOOM, MAX_ZOOM)

        zoom = newZoom
        panX = width / 2f - centerWorldX * zoom
        panY = height / 2f - centerWorldY * zoom

        // Clamp only when the preserved position is outside the legal bounds.
        clampPan()
        invalidate()
    }

    /** Requirement 8: the rectangle that actually contains ink (null = empty note). */
    fun contentBounds(): RectF? {
        if (strokes.isEmpty()) return null
        val r = RectF()
        var first = true
        for (s in strokes) {
            if (s.points.isEmpty()) continue
            if (first) { r.set(s.bounds); first = false } else r.union(s.bounds)
        }
        return if (first) null else r
    }

    /**
     * Requirement 11 - undo/redo felt laggy because every press repainted the
     * WHOLE canvas (every sealed stroke, every frame). Now only the rectangle
     * the action actually touched is invalidated, so undoing a single stroke is
     * effectively instant even on a busy page. A Clear still repaints in full.
     */
    fun undo() {
        val a = undoStack.removeLastOrNull() ?: return
        val before = actionBounds(a)
        when (a) {
            is Action.Add -> strokes.removeAll(a.strokes.toSet())
            is Action.Remove -> reinsert(a.items)
            is Action.Move -> for (s in a.strokes) s.translate(-a.dx, -a.dy)
            is Action.Scale -> if (a.f != 0f) for (s in a.strokes) s.scaleAround(1f / a.f, a.px, a.py)
            is Action.Clear -> strokes.addAll(a.old)
            is Action.Document -> restoreState(a.before)
        }
        redoStack.add(a)
        clearSelectionQuiet()
        notifyHistory()
        repaintForAction(a, before)
    }

    fun redo() {
        val a = redoStack.removeLastOrNull() ?: return
        val before = actionBounds(a)
        when (a) {
            is Action.Add -> strokes.addAll(a.strokes)
            is Action.Remove -> strokes.removeAll(a.items.map { it.second }.toSet())
            is Action.Move -> for (s in a.strokes) s.translate(a.dx, a.dy)
            is Action.Scale -> for (s in a.strokes) s.scaleAround(a.f, a.px, a.py)
            is Action.Clear -> strokes.clear()
            is Action.Document -> restoreState(a.after)
        }
        undoStack.add(a)
        clearSelectionQuiet()
        notifyHistory()
        repaintForAction(a, before)
    }

    private fun actionStrokes(a: Action): List<Stroke> = when (a) {
        is Action.Add -> a.strokes
        is Action.Remove -> a.items.map { it.second }
        is Action.Move -> a.strokes
        is Action.Scale -> a.strokes
        is Action.Clear -> a.old
        is Action.Document -> emptyList()
    }

    /** Union of the involved strokes' bounds (before the op is applied). */
    private fun actionBounds(a: Action): RectF? {
        val list = actionStrokes(a)
        var out: RectF? = null
        for (s in list) {
            if (s.points.isEmpty()) continue
            if (out == null) out = RectF(s.bounds) else out.union(s.bounds)
        }
        return out
    }

    private fun repaintForAction(a: Action, before: RectF?) {
        // A Clear can touch the whole page, so fall back to a full repaint.
        if (a is Action.Clear) { invalidate(); return }
        val after = actionBounds(a)
        val dirty = when {
            before != null && after != null -> RectF(before).apply { union(after) }
            before != null -> before
            after != null -> after
            else -> null
        }
        if (dirty == null) invalidate()
        else invalidateWorld(dirty.left, dirty.top, dirty.right, dirty.bottom, 6f / zoom)
    }

    fun clearAll() {
        if (strokes.isEmpty()) return
        push(Action.Clear(ArrayList(strokes)))
        strokes.clear()
        clearSelection()
        invalidate()
    }

    fun hasSelection() = selected.isNotEmpty()

    fun deleteSelection() {
        if (selected.isEmpty() && !hasActiveObject()) return
        if (selected.isEmpty() && hasActiveObject()) { deleteActiveObject(); listener?.onSelection(false); return }
        val items = ArrayList<Pair<Int, Stroke>>(selected.size)
        for (s in selected) {
            val idx = strokes.indexOf(s)
            if (idx >= 0) items.add(idx to s)
        }
        items.sortBy { it.first }
        strokes.removeAll(selected.toSet())
        push(Action.Remove(items))
        clearSelection()
        invalidate()
    }

    fun clearSelection() {
        if (selected.isNotEmpty() || selectingRect) {
            selected.clear()
            selectingRect = false
            listener?.onSelection(false)
            invalidate()
        }
    }

    /** Same as clearSelection but leaves repainting to the caller (requirement 11). */
    private fun clearSelectionQuiet() {
        if (selected.isNotEmpty() || selectingRect) {
            selected.clear()
            selectingRect = false
            listener?.onSelection(false)
        }
    }

    // =====================================================================
    //  Layout / viewport
    // =====================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!sizedOnce) {
            fitPage()
            sizedOnce = true
        } else if (oldw > 0 && oldh > 0) {
            // Requirement 6: keep the same world point centred after a rotation
            // or size change, so switching portrait <-> landscape doesn't jump.
            val cxWorld = (oldw / 2f - panX) / zoom
            val cyWorld = (oldh / 2f - panY) / zoom
            panX = w / 2f - cxWorld * zoom
            panY = h / 2f - cyWorld * zoom
            clampPan()
        } else {
            clampPan()
        }
    }

    private fun fitPage() {
        if (width == 0 || height == 0) return
        zoom = (width / (page.width() + 120f)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        panX = (width - page.width() * zoom) / 2f - page.left * zoom
        panY = 24f * density - page.top * zoom
        clampPan()
    }

    private fun toWorldX(sx: Float) = (sx - panX) / zoom
    private fun toWorldY(sy: Float) = (sy - panY) / zoom

    private fun clampPan() {
        val m = 80f * density
        var lo = m - page.right * zoom
        var hi = width - m - page.left * zoom
        panX = if (lo > hi) (lo + hi) / 2f else panX.coerceIn(lo, hi)
        lo = m - page.bottom * zoom
        hi = height - m - page.top * zoom
        panY = if (lo > hi) (lo + hi) / 2f else panY.coerceIn(lo, hi)
    }

    private fun growPageFor(x: Float, y: Float) {
        when (pageMode) {
            NoteStore.PAGE_LIMITED -> return   // fixed number of pages, never grows
            NoteStore.PAGE_MULTI_INFINITE -> {
                // Infinite stack of fixed-width A4-height pages. Extend by WHOLE
                // page units so the page breaks never drift or create a partial
                // page when the user writes near an edge.
                var grew = false
                if (y > page.bottom - GROW_MARGIN) {
                    page.bottom += PAGE_BREAK_H *
                        max(1, ceil((y + GROW_MARGIN - page.bottom) / PAGE_BREAK_H).toInt())
                    grew = true
                }
                if (y < page.top + GROW_MARGIN) {
                    page.top -= PAGE_BREAK_H *
                        max(1, ceil((page.top - (y - GROW_MARGIN)) / PAGE_BREAK_H).toInt())
                    grew = true
                }
                if (grew) invalidate()
            }
            else -> {
                // PAGE_INFINITE: free canvas, grows in every direction
                var grew = false
                if (x > page.right - GROW_MARGIN) { page.right = x + GROW_BY; grew = true }
                if (x < page.left + GROW_MARGIN) { page.left = x - GROW_BY; grew = true }
                if (y > page.bottom - GROW_MARGIN) { page.bottom = y + GROW_BY; grew = true }
                if (y < page.top + GROW_MARGIN) { page.top = y - GROW_BY; grew = true }
                if (grew) invalidate()
            }
        }
    }

    /** Repaints only the given world rectangle - the core of requirement 6. */
    private fun invalidateWorld(l: Float, t: Float, r: Float, b: Float, padWorld: Float) {
        val x0 = (l - padWorld) * zoom + panX
        val y0 = (t - padWorld) * zoom + panY
        val x1 = (r + padWorld) * zoom + panX
        val y1 = (b + padWorld) * zoom + panY
        invalidate(
            floor(x0).toInt() - 1, floor(y0).toInt() - 1,
            ceil(x1).toInt() + 1, ceil(y1).toInt() + 1
        )
    }

    private fun invalidateStroke(s: Stroke) =
        invalidateWorld(s.bounds.left, s.bounds.top, s.bounds.right, s.bounds.bottom, 2f / zoom)

    // =====================================================================
    //  Touch
    // =====================================================================

    private fun isStylus(ev: MotionEvent, index: Int): Boolean {
        val t = ev.getToolType(index)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun noteStylus() {
        if (!stylusSeen) {
            stylusSeen = true
            if (!stylusOnly) {
                stylusOnly = true
                listener?.onStylusDetected()
            }
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(ev)
            MotionEvent.ACTION_POINTER_DOWN -> onSecondFinger(ev)
            MotionEvent.ACTION_MOVE -> onMove(ev)
            MotionEvent.ACTION_POINTER_UP -> {
                val liftedId = ev.getPointerId(ev.actionIndex)
                if ((mode == MODE_DRAW || mode == MODE_LASER ||
                            mode == MODE_STROKE_ERASE || mode == MODE_SHAPE) &&
                    liftedId == activePointerId
                ) {
                    onUp()
                    mode = MODE_NONE
                } else if (mode == MODE_PANZOOM && ev.pointerCount - 1 < 2) {
                    mode = MODE_NONE
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onUp()
                mode = MODE_NONE
            }
        }
        return true
    }

    private fun onDown(ev: MotionEvent) {
        val sx = ev.x
        val sy = ev.y
        downSX = sx; downSY = sy
        lastSX = sx; lastSY = sy
        val wx = toWorldX(sx)
        val wy = toWorldY(sy)
        lastWX = wx; lastWY = wy

        val stylus = isStylus(ev, 0)
        if (stylus) noteStylus()
        activePointerId = ev.getPointerId(0)
        // Touching the page closes any open tool dropdown.
        listener?.onCanvasTouch()

        if (hitVBar(sx, sy)) { mode = MODE_BAR_V; barGrab = sy; listener?.onGesture(); return }
        if (hitHBar(sx, sy)) { mode = MODE_BAR_H; barGrab = sx; listener?.onGesture(); return }

        val drawingTool = tool != Tool.SELECT
        if (drawingTool) clearActiveObject()   // hide the object frame while drawing
        if (stylusOnly && !stylus && drawingTool) {
            mode = MODE_PAN1
            return
        }

        val effectiveTool =
            if (ev.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) Tool.ERASER else tool

        when (effectiveTool) {
            Tool.PEN -> startStroke(wx, wy, penColor, penWidth, stylus, ev.pressure)
            Tool.ERASER -> {
                mode = MODE_DRAW
                drawingWithStylus = stylus
                current = Stroke(ArrayList(64), 0, eraserWidth, eraser = true).also {
                    it.addPoint(wx, wy)
                    it.extend()
                }
            }
            Tool.STROKE_ERASER -> {
                mode = MODE_STROKE_ERASE
                drawingWithStylus = stylus
                pendingRemoved.clear()
                eraseStrokesAt(wx, wy)
            }
            Tool.LASER -> {
                mode = MODE_LASER
                drawingWithStylus = stylus
                // Every ACTION_DOWN starts a NEW laser stroke. Never reuse the
                // previous stroke's endpoint, so two separate strokes can never
                // be joined by an invisible segment.
                activeLaser = LaserSegment().also { laserSegments.add(it) }
                addLaserPoint(wx, wy)
            }
            Tool.TEXT -> {
                // Requirement 5: don't create the object yet - the editor creates
                // it on Done, so a text is a single undo step (and blank cancels
                // leave nothing behind).
                listener?.onCreateTextAt(wx, wy)
                mode = MODE_NONE
            }
            Tool.SHAPE -> {
                mode = MODE_SHAPE
                drawingWithStylus = stylus
                shapeX0 = wx; shapeY0 = wy
                shapePts.clear()
                prevShapeBounds.setEmpty()
                shapePreview =
                    Stroke(shapePts, penColor, shapeWidth, eraser = false, straight = true)
            }
            Tool.SELECT -> {
                if (selected.isNotEmpty() && hitScaleHandle(sx, sy)) {
                    mode = MODE_SEL_SCALE
                    pivotX = selBounds.centerX()
                    pivotY = selBounds.centerY()
                    handleStartDist = max(1f, hypot(wx - pivotX, wy - pivotY))
                    accScale = 1f
                } else if (selected.isNotEmpty() && selBounds.contains(wx, wy)) {
                    mode = MODE_SEL_MOVE
                    accDX = 0f; accDY = 0f
                } else if (beginObjectGesture(wx, wy)) {
                    // consumed by the active object's frame (move/resize/rotate/toolbar)
                } else if (hitObjectAt(wx, wy)) {
                    clearSelection(); listener?.onSelection(true)
                    beginObjectGesture(wx, wy)
                } else if (selectSingleStrokeAt(wx, wy)) {
                    clearActiveObject()
                } else {
                    clearActiveObject()
                    clearSelection()
                    mode = MODE_SELECT_RECT
                    selectingRect = true
                    selRect.set(wx, wy, wx, wy)
                }
            }
        }
        // Requirement 3: the old blanket invalidate() here repainted every stroke
        // on the page at the instant the pen touched down, which is exactly the
        // "pen freezes for a moment at the start of each stroke" the user saw.
        // A fresh stroke/shape/laser only needs the few pixels around the nib.
        when (mode) {
            MODE_DRAW, MODE_LASER, MODE_SHAPE, MODE_STROKE_ERASE ->
                invalidateWorld(wx, wy, wx, wy, max(penWidth, eraserWidth) * 1.5f + 8f / zoom)
            else -> invalidate()
        }
    }

    private fun selectSingleStrokeAt(wx: Float, wy: Float): Boolean {
        var best: Stroke? = null
        var bestD = Float.MAX_VALUE
        for (s in strokes) {
            if (s.eraser) continue
            val tol = s.width / 2f + 16f / zoom      // easy to tap, but…
            val d = s.distanceTo(wx, wy)
            if (d <= tol && d < bestD) { bestD = d; best = s }   // …nearest wins
        }
        val hit = best ?: return false
        selected.clear(); selected.add(hit); recomputeSelBounds(); selectingRect = false
        listener?.onSelection(true); invalidate(); return true
    }

    private fun startStroke(
        wx: Float, wy: Float, color: Int, width: Float, stylus: Boolean, pressure: Float
    ) {
        mode = MODE_DRAW
        drawingWithStylus = stylus
        current = Stroke(ArrayList(64), color, width, eraser = false).also {
            it.addPoint(wx, wy)
            it.extend()
        }
        // Requirement 4: the page extends only while a stroke is DRAWN near an
        // edge (see addDrawPoint), not on a bare tap/touch.
    }

    private fun onSecondFinger(ev: MotionEvent) {
        if (drawingWithStylus &&
            (mode == MODE_DRAW || mode == MODE_LASER ||
                    mode == MODE_STROKE_ERASE || mode == MODE_SHAPE)
        ) return
        if (ev.pointerCount != 2) return
        current?.let { c ->
            if (c.points.size <= 12) current = null else finishStroke()
        }
        activeLaser = null
        shapePreview = null
        if (mode == MODE_SELECT_RECT) selectingRect = false
        if (mode == MODE_SEL_MOVE) finishSelMove()
        if (mode == MODE_SEL_SCALE) finishSelScale()
        if (mode == MODE_STROKE_ERASE) finishStrokeErase()
        mode = MODE_PANZOOM
        pfx = (ev.getX(0) + ev.getX(1)) / 2f
        pfy = (ev.getY(0) + ev.getY(1)) / 2f
        pdist = max(1f, hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1)))
        invalidate()
    }

    private fun onMove(ev: MotionEvent) {
        when (mode) {
            MODE_PANZOOM -> {
                if (ev.pointerCount < 2) return
                val fx = (ev.getX(0) + ev.getX(1)) / 2f
                val fy = (ev.getY(0) + ev.getY(1)) / 2f
                val d = max(1f, hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1)))
                val newZoom = (zoom * d / pdist).coerceIn(MIN_ZOOM, MAX_ZOOM)
                val wx = (pfx - panX) / zoom
                val wy = (pfy - panY) / zoom
                zoom = newZoom
                panX = fx - wx * zoom
                panY = fy - wy * zoom
                pfx = fx; pfy = fy; pdist = d
                clampPan()
                listener?.onGesture()
                invalidate()
            }
            MODE_PAN1 -> {
                val idx = ev.findPointerIndex(activePointerId)
                if (idx < 0) return
                panX += ev.getX(idx) - lastSX
                panY += ev.getY(idx) - lastSY
                lastSX = ev.getX(idx)
                lastSY = ev.getY(idx)
                clampPan()
                listener?.onGesture()
                invalidate()
            }
            MODE_DRAW -> {
                val c = current ?: return
                val idx = ev.findPointerIndex(activePointerId)
                if (idx < 0) return
                var dl = c.points[c.points.size - 2]
                var dt = c.points[c.points.size - 1]
                var dr = dl
                var db = dt
                var h = 0
                while (h < ev.historySize) {
                    if (addDrawPoint(
                            c,
                            toWorldX(ev.getHistoricalX(idx, h)),
                            toWorldY(ev.getHistoricalY(idx, h)),
                            ev.getHistoricalPressure(idx, h),
                            ev.getHistoricalEventTime(h)
                        )
                    ) {
                        val x = c.points[c.points.size - 2]
                        val y = c.points[c.points.size - 1]
                        if (x < dl) dl = x; if (x > dr) dr = x
                        if (y < dt) dt = y; if (y > db) db = y
                    }
                    h++
                }
                if (addDrawPoint(
                        c, toWorldX(ev.getX(idx)), toWorldY(ev.getY(idx)),
                        ev.getPressure(idx), ev.eventTime
                    )
                ) {
                    val x = c.points[c.points.size - 2]
                    val y = c.points[c.points.size - 1]
                    if (x < dl) dl = x; if (x > dr) dr = x
                    if (y < dt) dt = y; if (y > db) db = y
                }
                c.extend()
                invalidateWorld(dl, dt, dr, db, c.width * 1.8f + 2f / zoom)
            }
            MODE_LASER -> {
                val idx = ev.findPointerIndex(activePointerId)
                if (idx < 0) return
                var h = 0
                while (h < ev.historySize) {
                    addLaserPoint(toWorldX(ev.getHistoricalX(idx, h)),
                        toWorldY(ev.getHistoricalY(idx, h)))
                    h++
                }
                addLaserPoint(toWorldX(ev.getX(idx)), toWorldY(ev.getY(idx)))
                invalidateLaser()
            }
            MODE_SHAPE -> {
                val idx = ev.findPointerIndex(activePointerId)
                if (idx < 0) return
                val s = shapePreview ?: return
                val wx = toWorldX(ev.getX(idx))
                val wy = toWorldY(ev.getY(idx))
                // No per-frame allocation and a single repaint covering the old
                // and new outline together - this is what removed the drag lag.
                prevShapeBounds.set(s.bounds)
                Shapes.buildInto(shapeKind, shapeX0, shapeY0, wx, wy, shapePts)
                s.rebuild()
                growPageFor(wx, wy)
                if (prevShapeBounds.isEmpty) prevShapeBounds.set(s.bounds)
                else prevShapeBounds.union(s.bounds)
                invalidateWorld(
                    prevShapeBounds.left, prevShapeBounds.top,
                    prevShapeBounds.right, prevShapeBounds.bottom, 2f / zoom
                )
            }
            MODE_STROKE_ERASE -> {
                val idx = ev.findPointerIndex(activePointerId)
                if (idx < 0) return
                val wx = toWorldX(ev.getX(idx))
                val wy = toWorldY(ev.getY(idx))
                val steps = max(1, (hypot(wx - lastWX, wy - lastWY) / (10f / zoom)).toInt())
                for (i in 1..steps) {
                    val t = i.toFloat() / steps
                    eraseStrokesAt(lastWX + (wx - lastWX) * t, lastWY + (wy - lastWY) * t)
                }
                lastWX = wx; lastWY = wy
            }
            MODE_SELECT_RECT -> {
                val wx = toWorldX(ev.x)
                val wy = toWorldY(ev.y)
                selRect.set(
                    min(toWorldX(downSX), wx), min(toWorldY(downSY), wy),
                    max(toWorldX(downSX), wx), max(toWorldY(downSY), wy)
                )
                invalidate()
            }
            MODE_SEL_MOVE -> {
                val wx = toWorldX(ev.x)
                val wy = toWorldY(ev.y)
                val dx = wx - lastWX
                val dy = wy - lastWY
                for (s in selected) s.translate(dx, dy)
                selBounds.offset(dx, dy)
                accDX += dx; accDY += dy
                lastWX = wx; lastWY = wy
                invalidate()
            }
            MODE_SEL_SCALE -> {
                val wx = toWorldX(ev.x)
                val wy = toWorldY(ev.y)
                val d = max(1f, hypot(wx - pivotX, wy - pivotY))
                val f = d / handleStartDist
                if (f > 0.01f) {
                    val step = f / accScale
                    for (s in selected) s.scaleAround(step, pivotX, pivotY)
                    accScale = f
                    recomputeSelBounds()
                    invalidate()
                }
            }
            MODE_OBJ_XFORM -> {
                val wx = toWorldX(ev.x)
                val wy = toWorldY(ev.y)
                when (objMode) {
                    OBJ_MOVE -> moveActiveObjectTo(wx, wy)
                    OBJ_RESIZE -> resizeActiveObject(wx, wy)
                    OBJ_ROTATE -> rotateActiveObject(wx, wy)
                }
            }
            MODE_BAR_V -> {
                val dy = ev.y - barGrab
                barGrab = ev.y
                panY -= dy / height * page.height() * zoom
                clampPan()
                listener?.onGesture()
                invalidate()
            }
            MODE_BAR_H -> {
                val dx = ev.x - barGrab
                barGrab = ev.x
                panX -= dx / width * page.width() * zoom
                clampPan()
                listener?.onGesture()
                invalidate()
            }
        }
    }

    /** Returns true when the point was actually appended. */
    private fun addDrawPoint(
        c: Stroke, wx: Float, wy: Float, pressure: Float, timeMs: Long
    ): Boolean {
        val n = c.points.size
        val px = c.points[n - 2]
        val py = c.points[n - 1]
        val dist = hypot(wx - px, wy - py)
        if (dist < 1.0f / zoom) return false
        c.addPoint(wx, wy)
        growPageFor(wx, wy)
        return true
    }

    private fun addLaserPoint(wx: Float, wy: Float) {
        val seg = activeLaser ?: return
        val n = seg.x.size
        if (n > 0) {
            val dx = wx - seg.x[n - 1]
            val dy = wy - seg.y[n - 1]
            val minDist = 1.0f / zoom
            if (dx * dx + dy * dy < minDist * minDist) return
        }
        seg.x.add(wx)
        seg.y.add(wy)
        seg.lastInput = SystemClock.uptimeMillis()
        laserLastInput = seg.lastInput
    }

    /** Repaint the current laser segments. */
    private fun invalidateLaser() {
        var first = true
        var minx = 0f; var maxx = 0f; var miny = 0f; var maxy = 0f
        for (seg in laserSegments) {
            for (i in seg.x.indices) {
                val x = seg.x[i]; val y = seg.y[i]
                if (first) { minx = x; maxx = x; miny = y; maxy = y; first = false }
                else { minx = min(minx, x); maxx = max(maxx, x); miny = min(miny, y); maxy = max(maxy, y) }
            }
        }
        if (!first) invalidateWorld(minx, miny, maxx, maxy, laserWidth * 4f)
    }

    private fun onUp() {
        when (mode) {
            MODE_DRAW -> finishStroke()
            MODE_LASER -> finishLaser()
            MODE_SHAPE -> finishShape()
            MODE_STROKE_ERASE -> finishStrokeErase()
            MODE_SELECT_RECT -> {
                selectingRect = false
                selected.clear()
                for (s in strokes) if (!s.eraser && RectF.intersects(s.bounds, selRect)) selected.add(s)
                var objectSelected = false
                for (t in textObjects) {
                    val r = RectF(t.x, t.y, t.x + t.w, t.y + t.h)
                    if (RectF.intersects(r, selRect)) { activateText(t); objectSelected = true }
                }
                for (im in imageObjects) {
                    val r = RectF(im.x, im.y, im.x + im.w, im.y + im.h)
                    if (RectF.intersects(r, selRect)) { activateImage(im); objectSelected = true }
                }
                if (selected.isNotEmpty()) recomputeSelBounds()
                listener?.onSelection(selected.isNotEmpty() || objectSelected)
                invalidate()
            }
            MODE_SEL_MOVE -> finishSelMove()
            MODE_SEL_SCALE -> finishSelScale()
            MODE_OBJ_XFORM -> finishObjectGesture()
        }
        drawingWithStylus = false
        activePointerId = -1
        if (mode != MODE_NONE) mode = MODE_NONE
    }

    private fun finishStroke() {
        val c = current ?: return
        current = null
        // The stroke is committed exactly as it was drawn - no recognition and
        // no smoothing pass. Only the automatic pressure taper shapes it.
        c.seal()
        strokes.add(c)
        push(Action.Add(listOf(c)))
        invalidateStroke(c)
    }

    // ---------- Objects (requirement 2 / C4) ----------

    private fun textW(o: TextObject): Float = o.w

    private fun textH(o: TextObject): Float = o.h

    /** Unrotated world box of the active object into [out]. */
    private fun objBox(out: RectF) {
        activeText?.let { out.set(it.x, it.y, it.x + it.w, it.y + it.h); return }
        activeImage?.let { out.set(it.x, it.y, it.x + it.w, it.y + it.h) }
    }

    private fun objRotation(): Float = activeText?.rotation ?: activeImage?.rotation ?: 0f

    private fun hasActiveObject() = activeText != null || activeImage != null

    /** Rotate world point (px,py) around (cx,cy) by -deg (into object-local frame). */
    private fun toLocal(px: Float, py: Float, cx: Float, cy: Float, deg: Float, out: FloatArray) {
        val r = Math.toRadians(-deg.toDouble())
        val cos = cos(r).toFloat(); val sin = sin(r).toFloat()
        val dx = px - cx; val dy = py - cy
        out[0] = cx + dx * cos - dy * sin
        out[1] = cy + dx * sin + dy * cos
    }

    /** Rotate local point around (cx,cy) by +deg (into world). */
    private fun toWorldPt(px: Float, py: Float, cx: Float, cy: Float, deg: Float, out: FloatArray) {
        val r = Math.toRadians(deg.toDouble())
        val cos = cos(r).toFloat(); val sin = sin(r).toFloat()
        val dx = px - cx; val dy = py - cy
        out[0] = cx + dx * cos - dy * sin
        out[1] = cy + dx * sin + dy * cos
    }

    private val objPt = FloatArray(2)

    private fun activateText(o: TextObject) { activeText = o; activeImage = null; invalidate() }
    private fun activateImage(o: ImageObject) { activeImage = o; activeText = null; invalidate() }
    fun clearActiveObject() {
        if (hasActiveObject()) { activeText = null; activeImage = null; objMode = OBJ_NONE; invalidate() }
    }

    /** Screen-constant sizes converted to world units. */
    private fun handleR() = 8f * density / zoom
    private fun rotOffset() = 34f * density / zoom
    private fun toolHalf() = 16f * density / zoom
    private fun toolGap() = 6f * density / zoom
    private fun toolYOffset() = 30f * density / zoom

    /**
     * SELECT-tool down while an object is active: figure out which part of the
     * editor was grabbed (rotate handle, a corner, the toolbar, or the body).
     * Returns true if the gesture was consumed.
     */
    private fun beginObjectGesture(wx: Float, wy: Float): Boolean {
        if (!hasActiveObject()) return false
        objBox(tmpBounds)
        val cx = tmpBounds.centerX(); val cy = tmpBounds.centerY()
        val deg = objRotation()
        objCX = cx; objCY = cy
        objDidChange = false

        val hr = handleR() * 1.6f
        // rotate handle (above top-centre)
        toWorldPt(cx, tmpBounds.top - rotOffset(), cx, cy, deg, objPt)
        if (hypot(wx - objPt[0], wy - objPt[1]) <= hr) {
            objMode = OBJ_ROTATE
            objGestureBefore = captureState()
            objStartAngleDeg = Math.toDegrees(atan2((wy - cy).toDouble(), (wx - cx).toDouble())).toFloat()
            objStartRot = deg
            mode = MODE_OBJ_XFORM
            return true
        }
        // toolbar buttons (below bottom-centre): [edit][delete]
        val ty = tmpBounds.bottom + toolYOffset()
        toWorldPt(cx - (toolHalf() + toolGap() / 2f), ty, cx, cy, deg, objPt)
        if (hypot(wx - objPt[0], wy - objPt[1]) <= toolHalf()) {
            activeText?.let { listener?.onEditText(it) }
            activeImage?.let { listener?.onEditImage(it) }
            return true
        }
        toWorldPt(cx + (toolHalf() + toolGap() / 2f), ty, cx, cy, deg, objPt)
        if (hypot(wx - objPt[0], wy - objPt[1]) <= toolHalf()) {
            deleteActiveObject()
            return true
        }
        // All 8 resize handles work: corners keep aspect ratio, side handles
        // stretch only the touched axis.
        val handles = arrayOf(
            0 to floatArrayOf(tmpBounds.left, tmpBounds.top),
            1 to floatArrayOf(tmpBounds.centerX(), tmpBounds.top),
            2 to floatArrayOf(tmpBounds.right, tmpBounds.top),
            3 to floatArrayOf(tmpBounds.right, tmpBounds.centerY()),
            4 to floatArrayOf(tmpBounds.right, tmpBounds.bottom),
            5 to floatArrayOf(tmpBounds.centerX(), tmpBounds.bottom),
            6 to floatArrayOf(tmpBounds.left, tmpBounds.bottom),
            7 to floatArrayOf(tmpBounds.left, tmpBounds.centerY())
        )
        for ((kind, c) in handles) {
            toWorldPt(c[0], c[1], cx, cy, deg, objPt)
            if (hypot(wx - objPt[0], wy - objPt[1]) <= hr) {
                objMode = OBJ_RESIZE
                resizeHandle = kind
                objStartDist = max(1f, hypot(wx - cx, wy - cy))
                objStartSize = activeText?.size ?: 0f
                objStartW = activeImage?.w ?: 0f
                objStartH = activeImage?.h ?: 0f
                objStartX = activeText?.x ?: activeImage?.x ?: 0f
                objStartY = activeText?.y ?: activeImage?.y ?: 0f
                objStartTextW = activeText?.w ?: 0f
                objStartTextH = activeText?.h ?: 0f
                objGestureBefore = captureState()
                mode = MODE_OBJ_XFORM
                return true
            }
        }
        // inside the (rotated) body -> move
        toLocal(wx, wy, cx, cy, deg, objPt)
        if (tmpBounds.contains(objPt[0], objPt[1])) {
            objMode = OBJ_MOVE
            objGestureBefore = captureState()
            objDownWX = wx; objDownWY = wy
            mode = MODE_OBJ_XFORM
            return true
        }
        return false
    }

    /** Picks the top-most object under the point and activates it. */
    private fun hitObjectAt(wx: Float, wy: Float): Boolean {
        for (i in textObjects.indices.reversed()) {
            val o = textObjects[i]
            val cx = o.x + o.w / 2f; val cy = o.y + o.h / 2f
            toLocal(wx, wy, cx, cy, o.rotation, objPt)
            tmpBounds.set(o.x, o.y, o.x + o.w, o.y + o.h)
            if (tmpBounds.contains(objPt[0], objPt[1])) { activateText(o); return true }
        }
        for (i in imageObjects.indices.reversed()) {
            val o = imageObjects[i]
            val cx = o.x + o.w / 2f; val cy = o.y + o.h / 2f
            toLocal(wx, wy, cx, cy, o.rotation, objPt)
            if (objPt[0] >= o.x && objPt[0] <= o.x + o.w && objPt[1] >= o.y && objPt[1] <= o.y + o.h) {
                activateImage(o); return true
            }
        }
        return false
    }

    private fun moveActiveObjectTo(wx: Float, wy: Float) {
        val dx = wx - objDownWX; val dy = wy - objDownWY
        activeText?.let { it.x += dx; it.y += dy }
        activeImage?.let { it.x += dx; it.y += dy }
        objDownWX = wx; objDownWY = wy
        objDidChange = true
        invalidate()
    }

    private fun resizeActiveObject(wx: Float, wy: Float) {
        val cx = objCX; val cy = objCY
        val local = objPt
        toLocal(wx, wy, cx, cy, objRotation(), local)
        val left = if (activeText != null) objStartX else objStartX
        val top = objStartY
        val right = left + if (activeText != null) objStartTextW else objStartW
        val bottom = top + if (activeText != null) objStartTextH else objStartH
        val minW = 40f; val minH = 30f
        var nLeft = left; var nTop = top; var nRight = right; var nBottom = bottom
        when (resizeHandle) {
            0 -> { nLeft = min(local[0], right - minW); nTop = min(local[1], bottom - minH) }
            1 -> { nTop = min(local[1], bottom - minH) }
            2 -> { nRight = max(local[0], left + minW); nTop = min(local[1], bottom - minH) }
            3 -> { nRight = max(local[0], left + minW) }
            4 -> { nRight = max(local[0], left + minW); nBottom = max(local[1], top + minH) }
            5 -> { nBottom = max(local[1], top + minH) }
            6 -> { nLeft = min(local[0], right - minW); nBottom = max(local[1], top + minH) }
            7 -> { nLeft = min(local[0], right - minW) }
        }
        activeText?.let { t ->
            t.x = nLeft; t.y = nTop; t.w = (nRight - nLeft).coerceAtLeast(minW); t.h = (nBottom - nTop).coerceAtLeast(minH)
        }
        activeImage?.let { im ->
            im.x = nLeft; im.y = nTop; im.w = (nRight - nLeft).coerceAtLeast(minW); im.h = (nBottom - nTop).coerceAtLeast(minH)
        }
        objDidChange = true
        invalidate()
    }

    private fun rotateActiveObject(wx: Float, wy: Float) {
        val ang = Math.toDegrees(atan2((wy - objCY).toDouble(), (wx - objCX).toDouble())).toFloat()
        val rot = objStartRot + (ang - objStartAngleDeg)
        activeText?.rotation = rot
        activeImage?.rotation = rot
        objDidChange = true
        invalidate()
    }

    private fun finishObjectGesture() {
        objMode = OBJ_NONE
        mode = MODE_NONE
        if (objDidChange) {
            commitDocumentState(objGestureBefore)
            objGestureBefore = null
            onObjectsChanged?.invoke()
        }
        invalidate()
    }

    fun deleteActiveObject() {
        if (!hasActiveObject()) return
        val before = captureState()
        activeText?.let { textObjects.remove(it) }
        activeImage?.let { imageObjects.remove(it) }
        activeText = null; activeImage = null
        objMode = OBJ_NONE
        onObjectsChanged?.invoke()
        invalidate()
    }

    fun addTextObject(text: String, wx: Float = toWorldX(width * 0.3f), wy: Float = toWorldY(height * 0.32f)): TextObject {
        val before = captureState()
        val size = 64f
        objTextPaint.textScaleX = 1f
        objTextPaint.textSize = size
        val w = objTextPaint.measureText(text).coerceAtLeast(60f)
        val o = TextObject(
            text = text, x = wx, y = wy, w = w, h = size * 1.28f, size = size,
            color = penColor
        )
        textObjects.add(o)
        activateText(o)
        commitDocumentState(before)
        onObjectsChanged?.invoke()
        invalidate()
        return o
    }

    fun addImageObject(name: String, bmp: Bitmap): ImageObject {
        val before = captureState()
        val maxW = page.width() * 0.4f
        val scale = (maxW / bmp.width).coerceAtMost(1.2f)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val wx = toWorldX(width * 0.3f)
        val wy = toWorldY(height * 0.3f)
        val o = ImageObject(name, wx, wy, w, h).also { it.bitmap = bmp }
        imageObjects.add(o)
        activateImage(o)
        commitDocumentState(before)
        onObjectsChanged?.invoke()
        invalidate()
        return o
    }

    fun deleteObject(t: TextObject?) {
        if (t != null && textObjects.contains(t)) {
            val before = captureState()
            textObjects.remove(t)

            if (activeText === t) activeText = null
            commitDocumentState(before)
            onObjectsChanged?.invoke(); invalidate()
        }
    }

    fun deleteObject(im: ImageObject?) {
        if (im != null && imageObjects.contains(im)) {
            val before = captureState()
            imageObjects.remove(im)

            if (activeImage === im) activeImage = null
            commitDocumentState(before)
            onObjectsChanged?.invoke(); invalidate()
        }
    }

    fun notifyObjectEdited() { onObjectsChanged?.invoke(); invalidate() }

    fun setObjects(texts: List<TextObject>, images: List<ImageObject>) {
        textObjects.clear(); textObjects.addAll(texts)
        imageObjects.clear(); imageObjects.addAll(images)
        activeText = null; activeImage = null
        invalidate()
    }

    private fun drawWrappedText(canvas: Canvas, o: TextObject) {
        if (o.text.isEmpty()) return
        objTextPaint.color = o.color
        objTextPaint.style = Paint.Style.FILL
        // Requirement 3: the text stretches/compresses to fill its box. Vertical
        // size comes from the box height; horizontal scale from the box width, so
        // every handle (corners AND the 4 side midpoints) resizes the glyphs.
        val vSize = (o.h / 1.28f).coerceAtLeast(6f)
        objTextPaint.textSize = vSize
        objTextPaint.textScaleX = 1f
        val natural = objTextPaint.measureText(o.text).coerceAtLeast(1f)
        objTextPaint.textScaleX = (o.w / natural).coerceIn(0.1f, 12f)
        canvas.drawText(o.text, o.x, o.y - objTextPaint.ascent(), objTextPaint)
        objTextPaint.textScaleX = 1f
    }

    /** After editing text, keep the box height but refit width so glyphs aren't stretched. */
    fun refitTextWidth(o: TextObject) {
        objTextPaint.textScaleX = 1f
        objTextPaint.textSize = (o.h / 1.28f).coerceAtLeast(12f)
        o.w = objTextPaint.measureText(o.text).coerceAtLeast(60f)
        invalidate()
    }

    /** Fits a text object's box snugly around its text at the given font size. */
    fun fitTextBox(o: TextObject, size: Float = o.size) {
        objTextPaint.textScaleX = 1f
        objTextPaint.textSize = size.coerceAtLeast(12f)
        o.size = size
        o.w = objTextPaint.measureText(o.text).coerceAtLeast(60f)
        o.h = size * 1.28f
        invalidate()
    }

    private fun drawObjects(canvas: Canvas) {
        for (o in imageObjects) {
            val bmp = o.bitmap ?: continue
            canvas.save(); canvas.rotate(o.rotation, o.x + o.w / 2f, o.y + o.h / 2f)
            tmpBounds.set(o.x, o.y, o.x + o.w, o.y + o.h)
            canvas.drawBitmap(bmp, null, tmpBounds, objBmpPaint); canvas.restore()
        }
        for (o in textObjects) {
            canvas.save(); canvas.rotate(o.rotation, o.x + o.w / 2f, o.y + o.h / 2f)
            drawWrappedText(canvas, o); canvas.restore()
        }
    }

    /** Draws the transform frame for the active object, on top of the ink. */
    private fun drawObjectFrame(canvas: Canvas) {
        if (!hasActiveObject() || tool != Tool.SELECT) return
        objBox(tmpBounds)
        val cx = tmpBounds.centerX(); val cy = tmpBounds.centerY()
        val deg = objRotation()
        canvas.save()
        canvas.rotate(deg, cx, cy)

        objFramePaint.strokeWidth = 1.4f / zoom
        objFramePaint.pathEffect = DashPathEffect(floatArrayOf(9f / zoom, 7f / zoom), 0f)
        canvas.drawRect(tmpBounds, objFramePaint)
        objFramePaint.pathEffect = null

        val hr = handleR()
        objHandleLine.strokeWidth = 1.4f / zoom
        for (c in arrayOf(
            floatArrayOf(tmpBounds.left, tmpBounds.top),
            floatArrayOf(tmpBounds.right, tmpBounds.top),
            floatArrayOf(tmpBounds.right, tmpBounds.bottom),
            floatArrayOf(tmpBounds.left, tmpBounds.bottom),
            floatArrayOf(tmpBounds.centerX(), tmpBounds.top),
            floatArrayOf(tmpBounds.centerX(), tmpBounds.bottom),
            floatArrayOf(tmpBounds.left, tmpBounds.centerY()),
            floatArrayOf(tmpBounds.right, tmpBounds.centerY())
        )) {
            canvas.drawRect(c[0] - hr, c[1] - hr, c[0] + hr, c[1] + hr, objHandleFill)
            canvas.drawRect(c[0] - hr, c[1] - hr, c[0] + hr, c[1] + hr, objHandleLine)
        }

        // rotate handle
        val ry = tmpBounds.top - rotOffset()
        canvas.drawLine(cx, tmpBounds.top, cx, ry, objHandleLine)
        canvas.drawCircle(cx, ry, hr * 1.2f, objHandleFill)
        canvas.drawCircle(cx, ry, hr * 1.2f, objHandleLine)

        // toolbar: [edit][delete]
        val ty = tmpBounds.bottom + toolYOffset()
        val th = toolHalf()
        val ex = cx - (th + toolGap() / 2f)
        val dx2 = cx + (th + toolGap() / 2f)
        val rad = 6f / zoom
        objToolIcon.strokeWidth = 2f / zoom
        // edit (pencil) button
        canvas.drawRoundRect(ex - th, ty - th, ex + th, ty + th, rad, rad, objToolFill)
        canvas.drawLine(ex - th * 0.4f, ty + th * 0.4f, ex + th * 0.35f, ty - th * 0.45f, objToolIcon)
        canvas.drawLine(ex + th * 0.35f, ty - th * 0.45f, ex + th * 0.55f, ty - th * 0.25f, objToolIcon)
        // delete (trash) button
        canvas.drawRoundRect(dx2 - th, ty - th, dx2 + th, ty + th, rad, rad, objToolFill)
        canvas.drawLine(dx2 - th * 0.4f, ty - th * 0.25f, dx2 + th * 0.4f, ty - th * 0.25f, objToolIcon)
        canvas.drawLine(dx2 - th * 0.28f, ty - th * 0.25f, dx2 - th * 0.2f, ty + th * 0.45f, objToolIcon)
        canvas.drawLine(dx2 + th * 0.28f, ty - th * 0.25f, dx2 + th * 0.2f, ty + th * 0.45f, objToolIcon)
        canvas.drawLine(dx2 - th * 0.2f, ty + th * 0.45f, dx2 + th * 0.2f, ty + th * 0.45f, objToolIcon)

        canvas.restore()
    }

    private fun finishShape() {
        val s = shapePreview
        shapePreview = null
        if (s == null || shapePts.size < 4) { invalidate(); return }
        if (hypot(s.bounds.width(), s.bounds.height()) < 12f / zoom) { invalidate(); return }
        // Copy out of the reusable buffer before storing it.
        val committed = Stroke(
            ArrayList(shapePts), s.color, s.width, eraser = false, straight = true
        )
        committed.seal()
        strokes.add(committed)
        push(Action.Add(listOf(committed)))
        invalidateStroke(committed)
    }

    private fun finishLaser() {
        activeLaser = null
        invalidateLaser()
    }

    private fun finishStrokeErase() {
        if (pendingRemoved.isNotEmpty()) {
            val items = ArrayList(pendingRemoved)
            items.sortBy { it.first }
            push(Action.Remove(items))
            pendingRemoved.clear()
        }
    }

    private fun finishSelMove() {
        if (accDX != 0f || accDY != 0f) {
            push(Action.Move(ArrayList(selected), accDX, accDY))
            accDX = 0f; accDY = 0f
        }
    }

    private fun finishSelScale() {
        if (accScale != 1f && accScale > 0.01f) {
            push(Action.Scale(ArrayList(selected), accScale, pivotX, pivotY))
        }
        accScale = 1f
    }

    private fun eraseStrokesAt(wx: Float, wy: Float) {
        val r = eraserWidth / 2f
        var i = strokes.size - 1
        var hit = false
        tmpBounds.setEmpty()
        while (i >= 0) {
            val s = strokes[i]
            if (!s.eraser && s.hits(wx, wy, r)) {
                strokes.removeAt(i)
                pendingRemoved.add(i to s)
                if (!hit) { tmpBounds.set(s.bounds); hit = true } else tmpBounds.union(s.bounds)
            }
            i--
        }
        if (hit) {
            invalidateWorld(
                tmpBounds.left, tmpBounds.top, tmpBounds.right, tmpBounds.bottom, 2f / zoom
            )
        }
    }

    private fun reinsert(items: List<Pair<Int, Stroke>>) {
        for ((idx, s) in items) strokes.add(min(idx, strokes.size), s)
    }

    private fun push(a: Action) {
        undoStack.add(a)
        if (undoStack.size > 100) undoStack.removeAt(0)
        redoStack.clear()
        notifyHistory()
    }

    private fun notifyHistory() {
        listener?.onHistory(undoStack.isNotEmpty(), redoStack.isNotEmpty())
    }

    private fun recomputeSelBounds() {
        if (selected.isEmpty()) { selBounds.setEmpty(); return }
        selBounds.set(selected[0].bounds)
        for (i in 1 until selected.size) selBounds.union(selected[i].bounds)
    }

    // =====================================================================
    //  Hit tests for scrollbars / selection handle
    // =====================================================================

    private fun vThumb(out: RectF): Boolean {
        val ph = page.height()
        if (ph <= 0f) return false
        val visTop = ((0f - panY) / zoom - page.top) / ph
        val visBot = ((height - panY) / zoom - page.top) / ph
        var t = visTop.coerceIn(0f, 1f) * height
        var b = visBot.coerceIn(0f, 1f) * height
        if (b - t < minThumb) {
            val c = (t + b) / 2f
            t = (c - minThumb / 2f).coerceAtLeast(0f)
            b = t + minThumb
        }
        out.set(width - barThick - 3f * density, t, width - 3f * density, b)
        return true
    }

    private fun hThumb(out: RectF): Boolean {
        val pw = page.width()
        if (pw <= 0f) return false
        val visL = ((0f - panX) / zoom - page.left) / pw
        val visR = ((width - panX) / zoom - page.left) / pw
        var l = visL.coerceIn(0f, 1f) * width
        var r = visR.coerceIn(0f, 1f) * width
        if (r - l < minThumb) {
            val c = (l + r) / 2f
            l = (c - minThumb / 2f).coerceAtLeast(0f)
            r = l + minThumb
        }
        out.set(l, height - 3f * density - barThick, r, height - 3f * density)
        return true
    }

    private fun hitVBar(sx: Float, sy: Float): Boolean {
        if (sx < width - barHit) return false
        if (!vThumb(tmpRect)) return false
        return sy >= tmpRect.top - barHit && sy <= tmpRect.bottom + barHit
    }

    private fun hitHBar(sx: Float, sy: Float): Boolean {
        if (sy < height - barHit) return false
        if (!hThumb(tmpRect)) return false
        return sx >= tmpRect.left - barHit && sx <= tmpRect.right + barHit
    }

    private fun hitScaleHandle(sx: Float, sy: Float): Boolean {
        val hx = selBounds.right * zoom + panX
        val hy = selBounds.bottom * zoom + panY
        val r = 26f * density
        return abs(sx - hx) < r && abs(sy - hy) < r
    }

    // =====================================================================
    //  Drawing
    // =====================================================================

    override fun onDraw(canvas: Canvas) {
        val bg = if (dark) Color.BLACK else Color.WHITE
        val outside = if (dark) 0xFF141414.toInt() else 0xFFEBEBEB.toInt()
        val border = if (dark) 0xFF2C2C2C.toInt() else 0xFFD4D4D4.toInt()

        canvas.drawColor(outside)

        canvas.save()
        canvas.translate(panX, panY)
        canvas.scale(zoom, zoom)

        fillPaint.color = bg
        canvas.drawRect(page, fillPaint)
        linePaint.color = border
        linePaint.strokeWidth = 1.5f / zoom
        canvas.drawRect(page, linePaint)

        // Requirement B3: page-break lines for the paged notebook modes.
        if (pageMode == NoteStore.PAGE_MULTI_INFINITE) {
            linePaint.strokeWidth = 1f / zoom
            var y = page.top + PAGE_BREAK_H
            while (y < page.bottom - 1f) {
                canvas.drawLine(page.left, y, page.right, y, linePaint)
                y += PAGE_BREAK_H
            }
        }

        canvas.clipRect(page)

        // Requirement C4: object layer sits under the ink so writing goes on top.
        drawObjects(canvas)

        // Ink is rendered on its own layer. Normal eraser strokes use CLEAR so
        // they remove only ink and reveal an image underneath instead of painting
        // the page background over the image.
        if (canvas.getClipBounds(clipI)) visible.set(clipI) else visible.set(page)

        // The offscreen "reveal" layer (CLEAR eraser over objects) is expensive,
        // so only pay for it when there is BOTH an object to reveal AND an eraser
        // stroke. Otherwise draw strokes directly - the eraser just paints the
        // page background, which looks identical when nothing is underneath.
        val hasObjects = textObjects.isNotEmpty() || imageObjects.isNotEmpty()
        var hasEraser = false
        if (hasObjects) { for (s in strokes) if (s.eraser) { hasEraser = true; break } }

        if (hasObjects && hasEraser) {
            val inkLayer = canvas.saveLayer(page, null)
            for (s in strokes) {
                if (s.eraser || !RectF.intersects(s.bounds, visible)) continue
                strokePaint.color = s.color
                strokePaint.style = Paint.Style.STROKE
                s.draw(canvas, strokePaint)
            }
            eraserClearPaint.strokeWidth = 1f
            for (s in strokes) {
                if (!s.eraser || !RectF.intersects(s.bounds, visible)) continue
                eraserClearPaint.strokeWidth = s.width
                s.draw(canvas, eraserClearPaint)
            }
            canvas.restoreToCount(inkLayer)
        } else {
            for (s in strokes) {
                if (!RectF.intersects(s.bounds, visible)) continue
                strokePaint.color = if (s.eraser) bg else s.color
                strokePaint.style = Paint.Style.STROKE
                s.draw(canvas, strokePaint)
            }
        }
        current?.let { c ->
            strokePaint.color = if (c.eraser) bg else c.color
            c.draw(canvas, strokePaint)
        }
        shapePreview?.let { s ->
            strokePaint.color = s.color
            strokePaint.alpha = 170
            s.draw(canvas, strokePaint)
            strokePaint.alpha = 255
        }

        drawLasers(canvas)

        if (selectingRect || selected.isNotEmpty()) {
            if (dashZoom != zoom) {
                dashPaint.pathEffect = DashPathEffect(floatArrayOf(12f / zoom, 9f / zoom), 0f)
                dashZoom = zoom
            }
            dashPaint.strokeWidth = 2f / zoom
            if (selectingRect) canvas.drawRect(selRect, dashPaint)
            if (selected.isNotEmpty()) {
                canvas.drawRect(selBounds, dashPaint)
                fillPaint.color = RED
                val hr = 9f / zoom * density
                canvas.drawRect(
                    selBounds.right - hr, selBounds.bottom - hr,
                    selBounds.right + hr, selBounds.bottom + hr, fillPaint
                )
            }
        }

        drawObjectFrame(canvas)

        canvas.restore()

        fillPaint.color = 0xAAD71921.toInt()
        if (vThumb(tmpRect)) canvas.drawRoundRect(tmpRect, barThick, barThick, fillPaint)
        if (hThumb(tmpRect)) canvas.drawRoundRect(tmpRect, barThick, barThick, fillPaint)
    }

    /**
     * GoodNotes-style laser rendering. A laser stroke does NOT fade as one solid
     * alpha value anymore. During the fade window the oldest part of each stroke
     * disappears first and the newest part remains visible longer, with a soft
     * transition between them. This prevents the whole stroke from blinking out
     * at once and gives the pointer a much smoother, presentation-like exit.
     */
    private fun drawLasers(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (laserSegments.isEmpty()) return

        val over = (now - laserLastInput) - LASER_HOLD_MS

        var dirty: RectF? = null
        var index = 0
        val iterator = laserSegments.iterator()
        while (iterator.hasNext()) {
            val seg = iterator.next()
            if (seg.x.isEmpty()) {
                iterator.remove()
                continue
            }

            val fadeProgress = if (over <= 0f) 0f
            else ((over - index * LASER_STAGGER_MS) / LASER_FADE_MS).coerceIn(0f, 1f)
            index++

            if (fadeProgress >= 1f) {
                iterator.remove()
                continue
            }

            // While the stroke is in the hold phase, render it in one path for
            // maximum efficiency. Once fading starts, use short line sections so
            // the fade travels smoothly from the old tail toward the new head.
            val w = laserWidth
            if (haloBlurFor != w) {
                laserHalo.maskFilter = BlurMaskFilter(w * 2.6f, BlurMaskFilter.Blur.NORMAL)
                haloBlurFor = w
            }

            if (fadeProgress <= 0f || seg.x.size < 2) {
                laserPath.rewind()
                laserPath.moveTo(seg.x[0], seg.y[0])
                var minx = seg.x[0]; var maxx = minx
                var miny = seg.y[0]; var maxy = miny
                for (i in 1 until seg.x.size) {
                    val x = seg.x[i]; val y = seg.y[i]
                    laserPath.lineTo(x, y)
                    minx = min(minx, x); maxx = max(maxx, x)
                    miny = min(miny, y); maxy = max(maxy, y)
                }
                laserHalo.strokeWidth = w * 3.4f
                laserHalo.alpha = 100
                canvas.drawPath(laserPath, laserHalo)
                laserMid.strokeWidth = w * 1.9f
                laserMid.alpha = 235
                canvas.drawPath(laserPath, laserMid)
                laserCore.strokeWidth = w * 0.62f
                laserCore.alpha = 255
                canvas.drawPath(laserPath, laserCore)

                val r = RectF(minx, miny, maxx, maxy)
                r.inset(-w * 4f, -w * 4f)
                if (dirty == null) dirty = r else dirty!!.union(r)
            } else {
                val fadeStart = (fadeProgress - LASER_TAIL_FADE_FRACTION).coerceAtLeast(0f)
                val fadeEnd = (fadeProgress + 0.035f).coerceAtMost(1f)
                var minx = Float.POSITIVE_INFINITY
                var miny = Float.POSITIVE_INFINITY
                var maxx = Float.NEGATIVE_INFINITY
                var maxy = Float.NEGATIVE_INFINITY

                for (i in 1 until seg.x.size) {
                    val u = i.toFloat() / (seg.x.size - 1).toFloat()
                    var a = when {
                        u <= fadeStart -> 0f
                        u >= fadeEnd -> 1f
                        else -> {
                            val t = ((u - fadeStart) / (fadeEnd - fadeStart)).coerceIn(0f, 1f)
                            t * t * (3f - 2f * t)
                        }
                    }

                    if (a <= 0f) continue

                    // Bias the beginning of the visible section slightly softer for
                    // a sleek tail, while keeping the head crisp.
                    a = 0.82f + 0.18f * a
                    val x0 = seg.x[i - 1]; val y0 = seg.y[i - 1]
                    val x1 = seg.x[i]; val y1 = seg.y[i]
                    laserHalo.strokeWidth = w * 3.4f
                    laserHalo.alpha = (100f * a).toInt()
                    canvas.drawLine(x0, y0, x1, y1, laserHalo)
                    laserMid.strokeWidth = w * 1.9f
                    laserMid.alpha = (235f * a).toInt()
                    canvas.drawLine(x0, y0, x1, y1, laserMid)
                    laserCore.strokeWidth = w * 0.62f
                    laserCore.alpha = (255f * a).toInt()
                    canvas.drawLine(x0, y0, x1, y1, laserCore)

                    minx = min(minx, min(x0, x1)); maxx = max(maxx, max(x0, x1))
                    miny = min(miny, min(y0, y1)); maxy = max(maxy, max(y0, y1))
                }

                if (minx.isFinite()) {
                    val r = RectF(minx, miny, maxx, maxy)
                    r.inset(-w * 4f, -w * 4f)
                    if (dirty == null) dirty = r else dirty!!.union(r)
                }
            }

            if (seg === activeLaser) {
                val tx = seg.x.last(); val ty = seg.y.last()
                laserDot.color = Color.WHITE
                laserDot.alpha = 255
                canvas.drawCircle(tx, ty, w * 0.55f, laserDot)
                laserDot.color = 0x77FF0D0D
                laserDot.alpha = 0x77
                canvas.drawCircle(tx, ty, w * 1.9f, laserDot)
                laserDot.alpha = 255
            }
        }

        val d = dirty
        if (d != null) {
            if (over <= 0f) {
                // Trail is fully visible and unchanging during the hold window;
                // don't burn frames - just wake once when the fade should begin.
                postInvalidateDelayed((-over).toLong() + 24L)
            } else {
                // Fading: animate frame by frame until it clears.
                postInvalidateWorld(d.left, d.top, d.right, d.bottom, 0f)
            }
        }
    }

    private fun postInvalidateWorld(l: Float, t: Float, r: Float, b: Float, padWorld: Float) {
        val x0 = (l - padWorld) * zoom + panX
        val y0 = (t - padWorld) * zoom + panY
        val x1 = (r + padWorld) * zoom + panX
        val y1 = (b + padWorld) * zoom + panY
        postInvalidateOnAnimation(
            floor(x0).toInt() - 1, floor(y0).toInt() - 1,
            ceil(x1).toInt() + 1, ceil(y1).toInt() + 1
        )
    }
}
