package com.hesi.snotes

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PDF notebooks.
 *
 * Every page of an imported PDF becomes its own canvas sheet made of two parts:
 *
 *   PDF block   - the PDF page (always upright, never rotated) + a margin
 *   notes panel - free writing space, by default the same size as the PDF block
 *
 * The ORIENTATION button only moves the notes panel:
 *
 *   LANDSCAPE sheets  [ PDF | notes ]      (panel on the right)
 *   PORTRAIT sheets   [ PDF ]
 *                     [ notes ]            (panel underneath)
 *
 * Ink is moved with the part of the sheet it was written on: anything on or
 * around the PDF stays exactly on the PDF, anything in the notes panel moves
 * with the panel, keeping its exact shape. Nothing is ever rotated, scaled or
 * clipped, so switching back and forth is lossless.
 *
 * Storage (alongside the normal note files):
 *   files/notes/pdf/<id>/source.pdf   the original PDF, untouched
 *   files/notes/pdf/<id>/layout.json  sheets, PDF rects and panel sizes (v2)
 */
object PdfNotebook {

    /**
     * One canvas sheet. [pdf] is the PDF page, [sheet] the whole drawable page,
     * [side] = notes panel on the right (landscape) instead of below (portrait).
     * [freeW] x [freeH] is the notes panel size.
     */
    class Slot(
        val index: Int, val sheet: RectF, val pdf: RectF,
        val srcW: Float, val srcH: Float,
        val freeW: Float, val freeH: Float, val side: Boolean
    ) {
        /** PDF page + its margin. */
        val blockW get() = pdf.width() + 2 * MARGIN
        val blockH get() = pdf.height() + 2 * MARGIN

        /** Notes panel origin, relative to the sheet's top-left. */
        val freeX get() = if (side) blockW else 0f
        val freeY get() = if (side) 0f else blockH

        /** Is a point (relative to the sheet) in the notes panel? */
        fun inPanel(lx: Float, ly: Float) = if (side) lx >= blockW else ly >= blockH
    }

    /** Page size + notes panel size: everything needed to lay a sheet out. */
    class PageSpec(val srcW: Float, val srcH: Float, val freeW: Float, val freeH: Float)

    private const val PDF_LONG = 2400f     // long edge of a PDF page, world units
    private const val MARGIN = 120f        // space around the PDF page on its sheet
    const val GAP = 220f                   // gap between sheets (shows the page number)
    private const val LAYOUT_VERSION = 2

    private fun notesDir(ctx: Context) = File(ctx.filesDir, "notes")
    fun dir(ctx: Context, id: String) = File(File(notesDir(ctx), "pdf"), id)
    fun pdfFile(ctx: Context, id: String) = File(dir(ctx, id), "source.pdf")
    fun layoutFile(ctx: Context, id: String) = File(dir(ctx, id), "layout.json")
    fun has(ctx: Context, id: String) = pdfFile(ctx, id).isFile && layoutFile(ctx, id).isFile

    fun dirSize(ctx: Context, id: String): Long =
        dir(ctx, id).listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

    fun delete(ctx: Context, id: String) {
        dir(ctx, id).deleteRecursively()
    }

    // =====================================================================
    //  Layout
    // =====================================================================

    /** On-sheet PDF size: long edge = PDF_LONG, aspect kept, never rotated. */
    private fun pdfSize(srcW: Float, srcH: Float): Pair<Float, Float> {
        val w = max(1f, srcW); val h = max(1f, srcH)
        return if (h >= w) (PDF_LONG * w / h) to PDF_LONG else PDF_LONG to (PDF_LONG * h / w)
    }

    /** New pages get a notes panel exactly the size of the PDF block. */
    fun defaultSpecs(sizes: List<Pair<Float, Float>>): List<PageSpec> = sizes.map { (w, h) ->
        val (pw, ph) = pdfSize(w, h)
        PageSpec(w, h, pw + 2 * MARGIN, ph + 2 * MARGIN)
    }

    /**
     * Starting orientation after import: a portrait PDF gets landscape sheets
     * (PDF left, notes right); a landscape PDF gets portrait sheets (PDF on
     * top, notes below). Both give a ~1.4:1 sheet that fills the screen.
     */
    fun defaultSide(sizes: List<Pair<Float, Float>>): Boolean =
        sizes.firstOrNull()?.let { it.second >= it.first } ?: true

    fun layout(specs: List<PageSpec>, side: Boolean): List<Slot> {
        class Box(val sw: Float, val sh: Float, val pw: Float, val ph: Float)
        val boxes = specs.map { sp ->
            val (pw, ph) = pdfSize(sp.srcW, sp.srcH)
            val bw = pw + 2 * MARGIN; val bh = ph + 2 * MARGIN
            if (side) Box(bw + sp.freeW, max(bh, sp.freeH), pw, ph)
            else Box(max(bw, sp.freeW), bh + sp.freeH, pw, ph)
        }
        val maxW = boxes.maxOfOrNull { it.sw } ?: 0f
        val out = ArrayList<Slot>(boxes.size)
        var y = 0f
        for ((i, b) in boxes.withIndex()) {
            val left = (maxW - b.sw) / 2f
            val sheet = RectF(left, y, left + b.sw, y + b.sh)
            val pdf = RectF(left + MARGIN, y + MARGIN, left + MARGIN + b.pw, y + MARGIN + b.ph)
            out.add(Slot(i, sheet, pdf, specs[i].srcW, specs[i].srcH, specs[i].freeW, specs[i].freeH, side))
            y += b.sh + GAP
        }
        return out
    }

    fun bounds(slots: List<Slot>): RectF {
        if (slots.isEmpty()) return RectF(0f, 0f, 2200f, 3000f)
        val r = RectF(slots[0].sheet)
        for (s in slots) r.union(s.sheet)
        return r
    }

    fun isLandscape(slots: List<Slot>) = slots.firstOrNull()?.side ?: true

    /** The same pages with the notes panel moved (the ORIENTATION button). */
    fun toggledLayout(slots: List<Slot>): List<Slot> =
        layout(slots.map { PageSpec(it.srcW, it.srcH, it.freeW, it.freeH) }, !isLandscape(slots))

    fun saveSlots(ctx: Context, id: String, slots: List<Slot>) = saveLayout(ctx, id, slots)

    /**
     * Maps a world point on sheet [from] to the same spot on sheet [to]. Points
     * on the PDF block keep their position relative to the PDF; points in the
     * notes panel keep their position relative to the panel. [panel] forces the
     * part (used for the two halves of a stroke cut at the panel edge).
     */
    fun mapPoint(from: Slot, to: Slot, x: Float, y: Float, out: FloatArray, panel: Boolean? = null) {
        var lx = x - from.sheet.left
        var ly = y - from.sheet.top
        if (panel ?: from.inPanel(lx, ly)) {
            lx = lx - from.freeX + to.freeX
            ly = ly - from.freeY + to.freeY
        }
        out[0] = to.sheet.left + lx
        out[1] = to.sheet.top + ly
    }

    /** Index of the sheet nearest to a world point. */
    fun nearest(slots: List<Slot>, x: Float, y: Float): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        for (s in slots) {
            val dx = max(0f, max(s.sheet.left - x, x - s.sheet.right))
            val dy = max(0f, max(s.sheet.top - y, y - s.sheet.bottom))
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = s.index }
        }
        return best
    }

    /**
     * Cuts a stroke where it crosses the edge between the PDF block and the
     * notes panel. Returns null when the stroke lies entirely in one part, else
     * the pieces in drawing order, each tagged with whether it is in the panel.
     * Both pieces share the exact crossing point, so when the layout is
     * switched back they meet again seamlessly.
     */
    private fun cutAtPanelEdge(s: Stroke, slot: Slot): List<Pair<Stroke, Boolean>>? {
        val n = s.points.size / 2
        if (n < 2) return null
        val ox = slot.sheet.left; val oy = slot.sheet.top
        fun inP(i: Int) = slot.inPanel(s.points[2 * i] - ox, s.points[2 * i + 1] - oy)
        val first = inP(0)
        if ((1 until n).none { inP(it) != first }) return null

        val pr = if (s.hasPressure()) s.pressures else null
        val out = ArrayList<Pair<Stroke, Boolean>>()
        var pts = ArrayList<Float>()
        var prs: ArrayList<Float>? = if (pr != null) ArrayList() else null
        var part = first
        fun add(x: Float, y: Float, p: Float) { pts.add(x); pts.add(y); prs?.add(p) }
        fun flush() {
            if (pts.size < 4) return
            val keepPr = prs?.takeIf { it.size * 2 == pts.size }
            out.add(Stroke(pts, s.color, s.width, s.eraser, s.straight, keepPr).also { it.seal() } to part)
        }
        add(s.points[0], s.points[1], pr?.get(0) ?: 1f)
        for (i in 1 until n) {
            val x1 = s.points[2 * i]; val y1 = s.points[2 * i + 1]
            val p1 = pr?.get(i) ?: 1f
            val now = inP(i)
            if (now != part) {
                val x0 = s.points[2 * (i - 1)]; val y0 = s.points[2 * (i - 1) + 1]
                val p0 = pr?.get(i - 1) ?: 1f
                val t = if (slot.side) {
                    val bx = ox + slot.blockW
                    if (x1 != x0) ((bx - x0) / (x1 - x0)).coerceIn(0f, 1f) else 0.5f
                } else {
                    val by = oy + slot.blockH
                    if (y1 != y0) ((by - y0) / (y1 - y0)).coerceIn(0f, 1f) else 0.5f
                }
                val xb = x0 + (x1 - x0) * t; val yb = y0 + (y1 - y0) * t; val pb = p0 + (p1 - p0) * t
                add(xb, yb, pb)
                flush()
                pts = ArrayList(); prs = if (pr != null) ArrayList() else null
                part = now
                add(xb, yb, pb)
            }
            add(x1, y1, p1)
        }
        flush()
        return out.takeIf { it.isNotEmpty() }
    }

    /**
     * Moves strokes, texts and images from layout [from] to layout [to].
     *
     * With the default mapping, each stroke belongs to the sheet under its
     * centre; a stroke lying across the PDF / notes edge is cut there (see
     * [cutAtPanelEdge]) and each piece moves with its own part - nothing jumps,
     * stretches or disappears. Objects move with the part under their centre.
     * A custom [map] (only the one-time upgrade uses one) moves whole strokes.
     */
    fun remap(
        from: List<Slot>, to: List<Slot>,
        strokes: MutableList<Stroke>, texts: List<TextObject>, images: List<ImageObject>,
        map: ((Slot, Slot, Float, Float, FloatArray) -> Unit)? = null,
        turnObj: (Slot, Slot) -> Float = { _, _ -> 0f }
    ) {
        if (from.isEmpty() || from.size != to.size) return
        val out = FloatArray(2)
        var k = 0
        while (k < strokes.size) {
            val s = strokes[k]
            if (s.points.isEmpty()) { k++; continue }
            val i = nearest(from, s.bounds.centerX(), s.bounds.centerY())
            val a = from[i]; val b = to[i]
            if (map != null) {
                s.mapPoints { x, y, o -> map(a, b, x, y, o) }
                k++
                continue
            }
            val pieces = cutAtPanelEdge(s, a)
            if (pieces == null) {
                val ox = a.sheet.left; val oy = a.sheet.top
                val panel = a.inPanel(s.points[0] - ox, s.points[1] - oy)
                s.mapPoints { x, y, o -> mapPoint(a, b, x, y, o, panel) }
                k++
            } else {
                strokes.removeAt(k)
                for ((piece, panel) in pieces) {
                    piece.mapPoints { x, y, o -> mapPoint(a, b, x, y, o, panel) }
                    strokes.add(k, piece)
                    k++
                }
            }
        }
        fun mapObj(cx: Float, cy: Float): Int {
            val i = nearest(from, cx, cy)
            if (map != null) map(from[i], to[i], cx, cy, out) else mapPoint(from[i], to[i], cx, cy, out)
            return i
        }
        for (t in texts) {
            val i = mapObj(t.x + t.w / 2f, t.y + t.h / 2f)
            t.x = out[0] - t.w / 2f; t.y = out[1] - t.h / 2f
            t.rotation = (t.rotation + turnObj(from[i], to[i])) % 360f
        }
        for (im in images) {
            val i = mapObj(im.x + im.w / 2f, im.y + im.h / 2f)
            im.x = out[0] - im.w / 2f; im.y = out[1] - im.h / 2f
            im.rotation = (im.rotation + turnObj(from[i], to[i])) % 360f
        }
    }

    private fun saveLayout(ctx: Context, id: String, slots: List<Slot>) {
        val arr = JSONArray()
        for (s in slots) arr.put(JSONObject().apply {
            put("w", s.srcW.toDouble()); put("h", s.srcH.toDouble())
            put("fw", s.freeW.toDouble()); put("fh", s.freeH.toDouble())
            put("s", rectJson(s.sheet)); put("p", rectJson(s.pdf))
        })
        val root = JSONObject().apply {
            put("version", LAYOUT_VERSION)
            put("side", isLandscape(slots))
            put("pages", arr)
        }
        val f = layoutFile(ctx, id)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(f)) { f.writeText(root.toString()); tmp.delete() }
    }

    private fun rectJson(r: RectF) = JSONArray().apply {
        put(r.left.toDouble()); put(r.top.toDouble()); put(r.right.toDouble()); put(r.bottom.toDouble())
    }

    private fun jsonRect(a: JSONArray) = RectF(
        a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat()
    )

    fun loadSlots(ctx: Context, id: String): List<Slot>? = loadSlots(layoutFile(ctx, id))

    /** Reads a v2 layout (older files are converted by [upgradeIfNeeded] first). */
    fun loadSlots(f: File): List<Slot>? = runCatching {
        if (!f.isFile) return null
        val root = JSONObject(f.readText())
        if (root.optInt("version", 1) < LAYOUT_VERSION) {
            // still an old file (upgrade failed): show it with default panels
            val sizes = legacyPages(root).map { it.srcW to it.srcH }
            return layout(defaultSpecs(sizes), defaultSide(sizes))
        }
        val side = root.optBoolean("side", true)
        val arr = root.getJSONArray("pages")
        val out = ArrayList<Slot>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Slot(
                i, jsonRect(o.getJSONArray("s")), jsonRect(o.getJSONArray("p")),
                o.optDouble("w", 595.0).toFloat(), o.optDouble("h", 842.0).toFloat(),
                o.getDouble("fw").toFloat(), o.getDouble("fh").toFloat(), side
            ))
        }
        out.takeIf { it.isNotEmpty() }
    }.getOrNull()

    // =====================================================================
    //  Upgrade of notebooks made by the previous version
    // =====================================================================

    /** A page as written by layout v1 (with the short-lived "rotate" field). */
    private class LegacyPage(val srcW: Float, val srcH: Float, val sheet: RectF, val pdf: RectF, val rot: Int)

    private fun legacyPages(root: JSONObject): List<LegacyPage> {
        val arr = root.getJSONArray("pages")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            LegacyPage(
                o.optDouble("w", 595.0).toFloat(), o.optDouble("h", 842.0).toFloat(),
                jsonRect(o.getJSONArray("s")), jsonRect(o.getJSONArray("p")),
                o.optInt("r", 0).let { if (it == 90 || it == 270) it else 0 }
            )
        }
    }

    /** The v1 layout rules, kept only to read old notebooks back correctly. */
    private fun legacyLayout(sizes: List<Pair<Float, Float>>): List<Pair<RectF, RectF>> {
        val boxes = sizes.map { (w0, h0) ->
            val w = max(1f, w0); val h = max(1f, h0)
            if (h >= w) {
                val ph = PDF_LONG; val pw = PDF_LONG * w / h; val sh = ph + 2 * MARGIN
                floatArrayOf(max(sh * 1.41421356f, pw + 2 * MARGIN + 800f), sh, pw, ph)
            } else {
                val pw = PDF_LONG; val ph = PDF_LONG * h / w; val sw = pw + 2 * MARGIN
                floatArrayOf(sw, max(sw * 1.41421356f, ph + 2 * MARGIN + 800f), pw, ph)
            }
        }
        val maxW = boxes.maxOfOrNull { it[0] } ?: 0f
        var y = 0f
        return boxes.map { b ->
            val left = (maxW - b[0]) / 2f
            val r = RectF(left, y, left + b[0], y + b[1]) to
                RectF(left + MARGIN, y + MARGIN, left + MARGIN + b[2], y + MARGIN + b[3])
            y += b[1] + GAP
            r
        }
    }

    /**
     * Converts a notebook saved by the previous version (layout v1, possibly
     * "rotated" sideways) to the current layout, moving its ink so everything
     * lands back on the upright PDF / in the notes panel. Runs once, on a
     * background thread, before the notebook is opened or exported.
     */
    fun upgradeIfNeeded(ctx: Context, id: String) {
        runCatching {
            val f = layoutFile(ctx, id)
            if (!f.isFile) return
            val root = JSONObject(f.readText())
            if (root.optInt("version", 1) >= LAYOUT_VERSION) return
            val legacy = legacyPages(root)
            if (legacy.isEmpty()) return
            val sizes = legacy.map { it.srcW to it.srcH }

            val data = NoteStore.load(ctx, id) ?: return
            val objs = NoteStore.loadObjects(ctx, id)
            val strokes = data.second
            val texts = objs.first
            val images = objs.second

            // 1) undo any sideways turn: map back onto the upright v1 sheets
            val upright = legacyLayout(sizes)
            fun asSlot(i: Int, sheet: RectF, pdf: RectF): Slot {
                val sp = legacy[i]
                val side = sp.srcH >= sp.srcW
                val bw = pdf.width() + 2 * MARGIN; val bh = pdf.height() + 2 * MARGIN
                return Slot(
                    i, sheet, pdf, sp.srcW, sp.srcH,
                    if (side) sheet.width() - bw else sheet.width(),
                    if (side) sheet.height() else sheet.height() - bh, side
                )
            }
            val v1 = upright.mapIndexed { i, (sheet, pdf) -> asSlot(i, sheet, pdf) }
            if (legacy.any { it.rot != 0 }) {
                val turned = legacy.mapIndexed { i, lp -> asSlot(i, lp.sheet, lp.pdf) }
                remap(turned, v1, strokes, texts, images,
                    map = { a, b, x, y, o ->
                        val lx = x - a.sheet.left; val ly = y - a.sheet.top
                        val nx: Float; val ny: Float
                        when (legacy[a.index].rot) {
                            90 -> { nx = ly; ny = a.sheet.width() - lx }   // turn back counter-clockwise
                            270 -> { nx = a.sheet.height() - ly; ny = lx } // turn back clockwise
                            else -> { nx = lx; ny = ly }
                        }
                        o[0] = b.sheet.left + nx; o[1] = b.sheet.top + ny
                    },
                    turnObj = { a, _ -> if (legacy[a.index].rot == 90) -90f else if (legacy[a.index].rot == 270) 90f else 0f }
                )
            }

            // 2) v1 sheets -> v2 sheets, keeping each page's own panel size
            val specs = v1.map { PageSpec(it.srcW, it.srcH, it.freeW, it.freeH) }
            val v2 = layout(specs, defaultSide(sizes))
            remap(v1, v2, strokes, texts, images)

            NoteStore.save(ctx, id, bounds(v2), strokes)
            NoteStore.saveObjectsNow(ctx, id, texts, images)
            saveLayout(ctx, id, v2)
        }
    }

    // =====================================================================
    //  Import
    // =====================================================================

    class PasswordProtected : Exception("This PDF is password-protected")

    /** Reads every page size (in PDF points). Throws for broken or locked files. */
    private fun readPageSizes(file: File): List<Pair<Float, Float>> {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val renderer = try {
                PdfRenderer(pfd)
            } catch (e: SecurityException) {
                throw PasswordProtected()
            }
            renderer.use { r ->
                val out = ArrayList<Pair<Float, Float>>(r.pageCount)
                for (i in 0 until r.pageCount) {
                    r.openPage(i).use { p -> out.add(p.width.toFloat() to p.height.toFloat()) }
                }
                return out
            }
        } finally {
            runCatching { pfd.close() }
        }
    }

    /**
     * Copies the PDF into private storage, builds one canvas sheet per page and
     * creates the note. Runs on a background thread. Returns the new note id.
     */
    fun importFromUri(ctx: Context, uri: Uri): String {
        val tmp = File(ctx.cacheDir, "pdf_import_${System.currentTimeMillis()}.pdf")
        try {
            ctx.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot read PDF" }
                FileOutputStream(tmp).use { input.copyTo(it) }
            }
            val sizes = readPageSizes(tmp)
            require(sizes.isNotEmpty()) { "PDF has no pages" }
            val slots = layout(defaultSpecs(sizes), defaultSide(sizes))

            val name = HesiNotebook.uniqueName(ctx, HesiNotebook.displayName(ctx, uri, "PDF"))
            val id = NoteStore.create(ctx, name, NoteStore.PAGE_PDF)
            try {
                dir(ctx, id).mkdirs()
                val dst = pdfFile(ctx, id)
                if (!tmp.renameTo(dst)) tmp.copyTo(dst, overwrite = true)
                saveLayout(ctx, id, slots)
                NoteStore.save(ctx, id, bounds(slots), emptyList())
            } catch (e: Exception) {
                NoteStore.deleteForever(ctx, id)
                throw e
            }
            return id
        } finally {
            tmp.delete()
        }
    }

    /** Used by .snotes import: install an already-extracted PDF + layout for [id]. */
    fun install(ctx: Context, id: String, pdf: File, layout: File) {
        dir(ctx, id).mkdirs()
        pdf.copyTo(pdfFile(ctx, id), overwrite = true)
        layout.copyTo(layoutFile(ctx, id), overwrite = true)
    }

    // =====================================================================
    //  Rendering helpers
    // =====================================================================

    /** Renders one PDF page to an opaque white bitmap of exactly w x h. */
    fun renderPage(renderer: PdfRenderer, index: Int, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        renderer.openPage(index).use { p ->
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
        return bmp
    }

    /** Draws a rendered PDF page bitmap into the slot's PDF rect (always upright). */
    fun drawPdfBitmap(canvas: Canvas, bmp: Bitmap, slot: Slot, paint: Paint) {
        canvas.drawBitmap(bmp, null, slot.pdf, paint)
    }

    /** Pixel size to render the PDF page at, for [slot] drawn at [scale] px per world unit. */
    fun uprightSize(slot: Slot, scale: Float): Pair<Int, Int> =
        (slot.pdf.width() * scale).roundToInt() to (slot.pdf.height() * scale).roundToInt()

    fun swapBW(c: Int): Int = when (c) {
        Color.BLACK -> Color.WHITE
        Color.WHITE -> Color.BLACK
        else -> c
    }

    // =====================================================================
    //  Export - one output page / image per canvas sheet, PDF + ink + objects
    // =====================================================================

    /**
     * Draws one sheet (world coords) onto [canvas] at [scale].
     *
     * The PDF page always keeps its real (white) paper colours, so ink lying ON
     * the PDF is drawn in its light-paper colour (black stays black), while ink
     * on the free canvas follows the chosen export theme. A stroke that crosses
     * the edge simply changes colour at the paper border.
     */
    fun drawSheet(
        canvas: Canvas, slot: Slot, pdfBmp: Bitmap?, strokes: List<Stroke>,
        texts: List<TextObject>, images: List<ImageObject>,
        outDark: Boolean, inkDark: Boolean, scale: Float
    ) {
        val sheet = slot.sheet
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(-sheet.left, -sheet.top)
        canvas.clipRect(sheet)

        fill.color = if (outDark) Color.BLACK else Color.WHITE
        canvas.drawRect(sheet, fill)
        fill.color = Color.WHITE
        canvas.drawRect(slot.pdf, fill)
        if (pdfBmp != null) {
            drawPdfBitmap(canvas, pdfBmp, slot, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        if (!outDark) {
            // white PDF on white paper: a hairline keeps the page edge visible
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = 0xFFCFCFCF.toInt(); strokeWidth = 3f
            }
            canvas.drawRect(slot.pdf, edge)
        }

        val flip = outDark != inkDark
        Exporter.drawObjectsWorld(
            canvas,
            texts.filter { RectF.intersects(RectF(it.x, it.y, it.x + it.w, it.y + it.h), sheet) },
            images.filter { RectF.intersects(RectF(it.x, it.y, it.x + it.w, it.y + it.h), sheet) },
            flip
        )

        val layer = canvas.saveLayer(sheet, null)
        val themed: (Int) -> Int = { c -> if (flip) swapBW(c) else c }
        if (!outDark) {
            // light output: paper colours == canvas colours, one pass
            drawInk(canvas, strokes, sheet, paint, themed)
        } else {
            canvas.save()
            canvas.clipOutRect(slot.pdf)
            drawInk(canvas, strokes, sheet, paint, themed)
            canvas.restore()
            canvas.save()
            canvas.clipRect(slot.pdf)
            drawInk(canvas, strokes, slot.pdf, paint) { c -> if (inkDark) swapBW(c) else c }
            canvas.restore()
        }
        canvas.restoreToCount(layer)
        canvas.restore()
    }

    /** Strokes in order; eraser strokes clear only ink (never the PDF underneath). */
    private fun drawInk(canvas: Canvas, strokes: List<Stroke>, region: RectF, paint: Paint, color: (Int) -> Int) {
        val clear = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        for (s in strokes) {
            if (!RectF.intersects(s.bounds, region)) continue
            if (s.eraser) {
                paint.xfermode = clear
                paint.color = Color.BLACK
            } else {
                paint.xfermode = null
                paint.color = color(s.color)
            }
            paint.style = Paint.Style.STROKE
            s.draw(canvas, paint)
        }
        paint.xfermode = null
    }

    private inline fun <T> withRenderer(ctx: Context, id: String, block: (PdfRenderer?) -> T): T {
        val f = pdfFile(ctx, id)
        if (!f.isFile) return block(null)
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val r = runCatching { PdfRenderer(pfd) }.getOrNull()
            try {
                return block(r)
            } finally {
                runCatching { r?.close() }
            }
        } finally {
            runCatching { pfd.close() }
        }
    }

    private fun safeName(name: String) =
        name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "note" }

    /**
     * Renders sheet [slot] into a bitmap whose long edge is [longEdge] px. The
     * PDF page is rasterised straight at the size it occupies in the output,
     * so it is as sharp as the output allows.
     */
    private fun renderSheetBitmap(
        renderer: PdfRenderer?, slot: Slot, strokes: List<Stroke>,
        texts: List<TextObject>, images: List<ImageObject>,
        outDark: Boolean, inkDark: Boolean, longEdge: Int
    ): Bitmap {
        val scale = longEdge / max(slot.sheet.width(), slot.sheet.height())
        val bw = max(1, (slot.sheet.width() * scale).roundToInt())
        val bh = max(1, (slot.sheet.height() * scale).roundToInt())
        val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val pdfBmp = renderer?.let {
            runCatching {
                val (pw, ph) = uprightSize(slot, scale)
                renderPage(it, slot.index, pw, ph)
            }.getOrNull()
        }
        drawSheet(Canvas(out), slot, pdfBmp, strokes, texts, images, outDark, inkDark, scale)
        pdfBmp?.recycle()
        return out
    }

    /** One image file per sheet (JPG or PNG), streamed so memory stays flat. */
    fun writeImages(
        ctx: Context, id: String, name: String, slots: List<Slot>, strokes: List<Stroke>,
        texts: List<TextObject>, images: List<ImageObject>,
        outDark: Boolean, inkDark: Boolean, asPng: Boolean, dir: File,
        progress: ((Int, Int) -> Unit)? = null
    ): List<File> = withRenderer(ctx, id) { renderer ->
        dir.mkdirs()
        val safe = safeName(name)
        val ext = if (asPng) "png" else "jpg"
        val files = ArrayList<File>()
        for (slot in slots) {
            progress?.invoke(slot.index + 1, slots.size)
            val bmp = renderSheetBitmap(renderer, slot, strokes, texts, images, outDark, inkDark, IMAGE_LONG)
            val suffix = if (slots.size > 1) "_p${slot.index + 1}" else ""
            val f = File(dir, "$safe$suffix.$ext")
            FileOutputStream(f).use {
                if (asPng) bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                else bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            bmp.recycle()
            files.add(f)
        }
        files
    }

    /** Saves one PNG per sheet into Pictures/S Notes. Returns how many were saved. */
    fun saveToGallery(
        ctx: Context, id: String, name: String, slots: List<Slot>, strokes: List<Stroke>,
        texts: List<TextObject>, images: List<ImageObject>,
        outDark: Boolean, inkDark: Boolean, progress: ((Int, Int) -> Unit)? = null
    ): Int = withRenderer(ctx, id) { renderer ->
        val safe = safeName(name)
        var ok = 0
        for (slot in slots) {
            progress?.invoke(slot.index + 1, slots.size)
            val bmp = renderSheetBitmap(renderer, slot, strokes, texts, images, outDark, inkDark, IMAGE_LONG)
            val suffix = if (slots.size > 1) "_p${slot.index + 1}" else ""
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$safe$suffix.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/S Notes")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                ctx.contentResolver.openOutputStream(uri)?.use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it); ok++
                }
            }
            bmp.recycle()
        }
        ok
    }

    /**
     * A PDF with one page per sheet. Page size follows each sheet (landscape for
     * portrait PDF pages, portrait for landscape ones), long edge = A4 (842 pt).
     * Ink stays vector; the PDF page is embedded at ~200 DPI.
     */
    fun writePdf(
        ctx: Context, id: String, name: String, slots: List<Slot>, strokes: List<Stroke>,
        texts: List<TextObject>, images: List<ImageObject>,
        outDark: Boolean, inkDark: Boolean, dir: File,
        progress: ((Int, Int) -> Unit)? = null
    ): File = withRenderer(ctx, id) { renderer ->
        dir.mkdirs()
        val doc = PdfDocument()
        try {
            for (slot in slots) {
                progress?.invoke(slot.index + 1, slots.size)
                val scale = 842f / max(slot.sheet.width(), slot.sheet.height())
                val pw = max(1, (slot.sheet.width() * scale).roundToInt())
                val ph = max(1, (slot.sheet.height() * scale).roundToInt())
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, slot.index + 1).create())
                val pdfBmp = renderer?.let {
                    val pxScale = PDF_EMBED_LONG / max(slot.pdf.width(), slot.pdf.height())
                    runCatching {
                        val (pw, ph) = uprightSize(slot, pxScale)
                        renderPage(it, slot.index, pw, ph)
                    }.getOrNull()
                }
                drawSheet(page.canvas, slot, pdfBmp, strokes, texts, images, outDark, inkDark, scale)
                doc.finishPage(page)
                pdfBmp?.recycle()
            }
            val f = File(dir, "${safeName(name)}.pdf")
            FileOutputStream(f).use { doc.writeTo(it) }
            f
        } finally {
            doc.close()
        }
    }

    private const val IMAGE_LONG = 3508      // A4 long edge @ 300 DPI, same as other exports
    private const val PDF_EMBED_LONG = 2300f // ~200 DPI for an A4 page inside the exported PDF
}

/**
 * Background, zoom-aware rasteriser for the on-screen PDF pages.
 *
 * Pages are rendered lazily on one worker thread (PdfRenderer is single-page
 * at a time) into three quality tiers. The view asks for the tier that matches
 * the current zoom; the newest request is served first so fast scrolling never
 * queues up a backlog, and pages that scrolled out of view are skipped.
 * Bitmaps live in an LRU bounded by memory, never recycled manually (the UI
 * thread may still be drawing an evicted one) - the GC reclaims them.
 */
class PdfPageCache(private val file: File, private val onReady: () -> Unit) {

    companion object {
        val TIERS = intArrayOf(1024, 2048, 3072)
    }

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pdf-render").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    @Volatile private var closed = false
    @Volatile private var failed = false
    @Volatile var wantFirst = 0
    @Volatile var wantLast = -1

    private val lock = Any()
    private val pending = LinkedHashMap<Int, Int>()   // page -> tier index, insertion = recency
    private var inFlight = -1L
    private val failedKeys = HashSet<Long>()          // e.g. OOM on the top tier: don't retry forever

    private val cache = object : LruCache<Long, Bitmap>(
        min(Runtime.getRuntime().maxMemory() / 4, 192L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }

    private fun key(page: Int, tier: Int) = page.toLong() * 8 + tier

    /** Tier index whose long edge covers [px] on-screen pixels. */
    fun tierFor(px: Float): Int {
        for (i in TIERS.indices) if (TIERS[i] >= px * 0.85f) return i
        return TIERS.size - 1
    }

    /** Exact tier if cached, otherwise the best other cached tier (or null). */
    fun best(page: Int, tier: Int): Bitmap? {
        cache.get(key(page, tier))?.let { return it }
        for (t in TIERS.indices.reversed()) if (t != tier) cache.get(key(page, t))?.let { return it }
        return null
    }

    fun has(page: Int, tier: Int) = cache.get(key(page, tier)) != null

    fun request(page: Int, tier: Int) {
        if (closed || failed) return
        if (cache.get(key(page, tier)) != null) return
        synchronized(lock) {
            if (inFlight == key(page, tier) || key(page, tier) in failedKeys) return
            if (pending[page] == tier) {
                // already queued - just bump its priority
                pending.remove(page); pending[page] = tier
                return
            }
            pending.remove(page)
            pending[page] = tier
        }
        exec.execute { drainOne() }
    }

    private fun drainOne() {
        if (closed) return
        val page: Int
        val tier: Int
        synchronized(lock) {
            if (pending.isEmpty()) return
            var last: Map.Entry<Int, Int>? = null
            for (e in pending.entries) last = e
            page = last!!.key; tier = last.value
            pending.remove(page)
            // skip pages that scrolled away while queued
            if (page < wantFirst - 1 || page > wantLast + 1) return
            if (cache.get(key(page, tier)) != null) return
            inFlight = key(page, tier)
        }
        try {
            val r = openRenderer() ?: return
            if (page !in 0 until r.pageCount) return
            val bmp = r.openPage(page).use { p ->
                val long = TIERS[tier].toFloat()
                val pw = max(1, p.width); val ph = max(1, p.height)
                val s = long / max(pw, ph)
                val b = Bitmap.createBitmap(max(1, (pw * s).roundToInt()), max(1, (ph * s).roundToInt()), Bitmap.Config.ARGB_8888)
                b.eraseColor(Color.WHITE)
                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                b
            }
            if (closed) return
            cache.put(key(page, tier), bmp)
            onReady()
        } catch (_: Throwable) {
            // OOM on a huge tier or a broken page: keep whatever is already cached
            synchronized(lock) { failedKeys.add(key(page, tier)) }
        } finally {
            synchronized(lock) { inFlight = -1L }
        }
    }

    private fun openRenderer(): PdfRenderer? {
        renderer?.let { return it }
        if (failed) return null
        return runCatching {
            val d = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd = d
            PdfRenderer(d).also { renderer = it }
        }.getOrElse { failed = true; null }
    }

    fun close() {
        closed = true
        synchronized(lock) { pending.clear() }
        exec.execute {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
            renderer = null; pfd = null
        }
        exec.shutdown()
        cache.evictAll()
    }
}
