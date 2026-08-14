package com.hesi.snotes

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Requirement 8 - why exports used to look "zoomed out" and blurry.
 *
 * The old exporter always rendered the WHOLE page rectangle. That rectangle
 * grows by 900 world units every time you write near an edge, so after a bit of
 * writing the page was several times larger than the ink on it. Everything was
 * then squeezed into a 2048px bitmap, so the actual handwriting ended up
 * occupying a few hundred pixels - tiny on screen and a large file for nothing.
 *
 * Now the export region is the INK bounding box plus a small margin. The same
 * pixel budget is spent entirely on the writing, so the result is sharper AND
 * smaller. You can still split a long note into several images.
 */
object Exporter {

    private const val PAGE_ASPECT = 1.41421356f // A4
    private const val MARGIN = 90f              // world units around the ink

    /**
     * Long edge of every exported image. There is only one quality now - the
     * low-detail mode has been removed, so exports are always sharp. Combined
     * with cropping to the ink this is ~220 DPI on an A4 page.
     */
    const val QUALITY = 2600

    // Requirement 13: every single-image export is normalised to a FIXED pixel
    // size (A4 at ~300 DPI), so JPG/PNG shares always have consistent dimensions.
    // Orientation follows the drawing (portrait vs landscape); the ink is fitted
    // and centred inside with a small margin.
    private const val EXPORT_FIX_LONG = 3508   // A4 long edge @ 300 DPI
    private const val EXPORT_FIX_SHORT = 2480   // A4 short edge @ 300 DPI
    private const val EXPORT_MARGIN = 130f

    private fun sharedPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun safeName(name: String) =
        name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "note" }

    /** The region worth exporting: the ink, padded. Falls back to the page. */
    fun exportRegion(strokes: List<Stroke>, page: RectF): RectF {
        var first = true
        val r = RectF()
        for (s in strokes) {
            if (s.points.isEmpty()) continue
            if (first) { r.set(s.bounds); first = false } else r.union(s.bounds)
        }
        if (first) {
            // empty note - keep a sane A4 sheet instead of the grown page
            val w = min(page.width(), 2200f)
            return RectF(page.left, page.top, page.left + w, page.top + w * PAGE_ASPECT)
        }
        r.inset(-MARGIN, -MARGIN)
        // never wider/taller than the page itself
        r.left = max(r.left, page.left)
        r.top = max(r.top, page.top)
        r.right = min(r.right, page.right)
        r.bottom = min(r.bottom, page.bottom)
        if (r.width() < 50f) r.right = r.left + 50f
        if (r.height() < 50f) r.bottom = r.top + 50f
        return r
    }

    /** How many A4-ish pages the region splits into. */
    fun pageCount(region: RectF): Int {
        val pageH = region.width() * PAGE_ASPECT
        if (pageH <= 0f) return 1
        return max(1, ceil(region.height() / pageH).toDouble().toInt())
    }

    private fun drawRegion(
        canvas: Canvas, strokes: List<Stroke>, region: RectF,
        scale: Float, outDark: Boolean, inkDark: Boolean, paint: Paint,
        texts: List<TextObject> = emptyList(), images: List<ImageObject> = emptyList()
    ) {
        val bg = if (outDark) Color.BLACK else Color.WHITE
        val flip = outDark != inkDark
        canvas.drawColor(bg)
        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(-region.left, -region.top)
        canvas.clipRect(region)
        drawObjectsWorld(canvas, texts, images, flip)
        val inkLayer = canvas.saveLayer(region, null)
        paint.xfermode = null
        for (s in strokes) {
            if (s.eraser || !RectF.intersects(s.bounds, region)) continue
            var c = s.color
            if (flip) {
                if (c == Color.BLACK) c = Color.WHITE else if (c == Color.WHITE) c = Color.BLACK
            }
            paint.color = c
            paint.style = Paint.Style.STROKE
            s.draw(canvas, paint)
        }
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        for (s in strokes) {
            if (!s.eraser || !RectF.intersects(s.bounds, region)) continue
            paint.strokeWidth = s.width
            s.draw(canvas, paint)
        }
        paint.xfermode = null
        canvas.restoreToCount(inkLayer)
        canvas.restore()
    }

    /** Requirement C4 - object layer under the ink in exports (world coords). */
    private fun drawObjectsWorld(
        canvas: Canvas, texts: List<TextObject>, images: List<ImageObject>, flip: Boolean
    ) {
        if (images.isNotEmpty()) {
            val bp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            val dst = RectF()
            for (im in images) {
                val b = im.bitmap ?: continue
                canvas.save()
                canvas.rotate(im.rotation, im.x + im.w / 2f, im.y + im.h / 2f)
                dst.set(im.x, im.y, im.x + im.w, im.y + im.h)
                canvas.drawBitmap(b, null, dst, bp)
                canvas.restore()
            }
        }
        if (texts.isNotEmpty()) {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            for (t in texts) {
                if (t.text.isEmpty()) continue
                var c = t.color
                if (flip) {
                    if (c == Color.BLACK) c = Color.WHITE else if (c == Color.WHITE) c = Color.BLACK
                }
                tp.color = c
                val vSize = (t.h / 1.28f).coerceAtLeast(6f)
                tp.textSize = vSize
                tp.textScaleX = 1f
                val natural = tp.measureText(t.text).coerceAtLeast(1f)
                tp.textScaleX = (t.w / natural).coerceIn(0.1f, 12f)
                canvas.save()
                canvas.rotate(t.rotation, t.x + t.w / 2f, t.y + t.h / 2f)
                canvas.drawText(t.text, t.x, t.y - tp.ascent(), tp)
                canvas.restore()
                tp.textScaleX = 1f
            }
        }
    }

    /** Expands a region to also contain the objects. */
    private fun unionObjects(region: RectF, texts: List<TextObject>, images: List<ImageObject>) {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        for (t in texts) {
            tp.textSize = t.size
            region.union(RectF(t.x, t.y, t.x + t.w, t.y + t.h))
        }
        for (im in images) region.union(RectF(im.x, im.y, im.x + im.w, im.y + im.h))
    }

    /**
     * Requirement 13 - draw the ink fitted and centred inside a fixed-size
     * canvas (canvasW x canvasH) with a uniform margin, so every exported image
     * has the same pixel dimensions.
     */
    private fun drawRegionFitted(
        canvas: Canvas, strokes: List<Stroke>, region: RectF,
        outDark: Boolean, inkDark: Boolean, paint: Paint,
        canvasW: Int, canvasH: Int,
        texts: List<TextObject> = emptyList(), images: List<ImageObject> = emptyList()
    ) {
        val bg = if (outDark) Color.BLACK else Color.WHITE
        val flip = outDark != inkDark
        canvas.drawColor(bg)

        val availW = canvasW - 2f * EXPORT_MARGIN
        val availH = canvasH - 2f * EXPORT_MARGIN
        val scale = min(availW / max(1f, region.width()), availH / max(1f, region.height()))
            .coerceIn(0.01f, 12f)
        val offX = (canvasW - region.width() * scale) / 2f
        val offY = (canvasH - region.height() * scale) / 2f

        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scale, scale)
        canvas.translate(-region.left, -region.top)
        canvas.clipRect(region)
        drawObjectsWorld(canvas, texts, images, flip)
        val inkLayer = canvas.saveLayer(region, null)
        paint.xfermode = null
        for (s in strokes) {
            if (s.eraser || !RectF.intersects(s.bounds, region)) continue
            var c = s.color
            if (flip) {
                if (c == Color.BLACK) c = Color.WHITE else if (c == Color.WHITE) c = Color.BLACK
            }
            paint.color = c
            paint.style = Paint.Style.STROKE
            s.draw(canvas, paint)
        }
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        for (s in strokes) {
            if (!s.eraser || !RectF.intersects(s.bounds, region)) continue
            paint.strokeWidth = s.width
            s.draw(canvas, paint)
        }
        paint.xfermode = null
        canvas.restoreToCount(inkLayer)
        canvas.restore()
    }

    /**
     * @param split   true  -> one A4-shaped image per page (requirement 8)
     *                false -> a single image containing everything
     * @param outDark the theme the user picked in the export dialog (requirement 4)
     * @param inkDark the theme the in-memory strokes are currently authored in
     */
    fun renderPages(
        strokes: List<Stroke>,
        page: RectF,
        outDark: Boolean,
        inkDark: Boolean = false,
        split: Boolean = true,
        maxDim: Int = QUALITY,
        texts: List<TextObject> = emptyList(),
        images: List<ImageObject> = emptyList()
    ): List<Bitmap> {
        val region = exportRegion(strokes, page)
        unionObjects(region, texts, images)
        val out = ArrayList<Bitmap>()
        val paint = sharedPaint()

        if (!split) {
            // Requirement 13: fixed output size. Pick orientation from the ink,
            // then fit-and-centre it on a fixed A4 pixel canvas.
            val landscape = region.width() >= region.height()
            val cw = if (landscape) EXPORT_FIX_LONG else EXPORT_FIX_SHORT
            val ch = if (landscape) EXPORT_FIX_SHORT else EXPORT_FIX_LONG
            val bmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
            drawRegionFitted(Canvas(bmp), strokes, region, outDark, inkDark, paint, cw, ch, texts, images)
            out.add(bmp)
            return out
        }

        val pageW = max(1f, region.width())
        val pageH = pageW * PAGE_ASPECT
        val count = pageCount(region)
        val scale = min(maxDim / pageW, maxDim / pageH).coerceIn(0.05f, 6f)
        val bw = max(1, (pageW * scale).toInt())
        val bh = max(1, (pageH * scale).toInt())
        for (i in 0 until count) {
            val r = RectF(
                region.left, region.top + i * pageH,
                region.right, region.top + (i + 1) * pageH
            )
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            drawRegion(Canvas(bmp), strokes, r, scale, outDark, inkDark, paint, texts, images)
            out.add(bmp)
        }
        return out
    }

    /** Shares all pages as PNG or JPG files (one image per page). */
    fun shareImages(ctx: Context, pages: List<Bitmap>, asPng: Boolean, name: String) {
        try {
            val dirF = File(ctx.cacheDir, "shared").apply { mkdirs() }
            // stale exports would otherwise pile up in the cache forever
            dirF.listFiles()?.forEach { it.delete() }
            val safe = safeName(name)
            val ext = if (asPng) "png" else "jpg"
            val uris = ArrayList<Uri>()
            for ((i, bmp) in pages.withIndex()) {
                val suffix = if (pages.size > 1) "_p${i + 1}" else ""
                val file = File(dirF, "$safe$suffix.$ext")
                FileOutputStream(file).use { out ->
                    // Requirement 1/7: every export is high quality now.
                    if (asPng) bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    else bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                uris.add(FileProvider.getUriForFile(ctx, "com.hesi.snotes.fileprovider", file))
            }
            val type = if (asPng) "image/png" else "image/jpeg"
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    this.type = type
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    this.type = type
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(intent, "Share note"))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Share failed", Toast.LENGTH_SHORT).show()
        } finally {
            for (bmp in pages) bmp.recycle()
        }
    }

    /** Writes the note as an A4 multi-page PDF and shares it. */
    fun sharePdf(
        ctx: Context, strokes: List<Stroke>, page: RectF,
        outDark: Boolean, inkDark: Boolean, name: String,
        texts: List<TextObject> = emptyList(), images: List<ImageObject> = emptyList()
    ) {
        try {
            val region = exportRegion(strokes, page)
            unionObjects(region, texts, images)
            val pdf = PdfDocument()
            val pageW = max(1f, region.width())
            val pageH = pageW * PAGE_ASPECT
            val count = pageCount(region)
            val pdfW = 595 // A4 @ 72dpi
            val pdfH = 842
            val scale = pdfW / pageW
            val paint = sharedPaint()
            for (i in 0 until count) {
                val info = PdfDocument.PageInfo.Builder(pdfW, pdfH, i + 1).create()
                val p = pdf.startPage(info)
                val r = RectF(
                    region.left, region.top + i * pageH,
                    region.right, region.top + (i + 1) * pageH
                )
                drawRegion(p.canvas, strokes, r, scale, outDark, inkDark, paint, texts, images)
                pdf.finishPage(p)
            }
            val dirF = File(ctx.cacheDir, "shared").apply { mkdirs() }
            val file = File(dirF, "${safeName(name)}.pdf")
            FileOutputStream(file).use { pdf.writeTo(it) }
            pdf.close()
            val uri = FileProvider.getUriForFile(ctx, "com.hesi.snotes.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(intent, "Share note"))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Share failed", Toast.LENGTH_SHORT).show()
        }
    }

    /** Saves every page as a PNG in Pictures/S Notes. */
    fun saveToGallery(ctx: Context, pages: List<Bitmap>, name: String) {
        var ok = 0
        try {
            val safe = safeName(name)
            for ((i, bmp) in pages.withIndex()) {
                val suffix = if (pages.size > 1) "_p${i + 1}" else ""
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "$safe$suffix.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/S Notes")
                }
                val uri = ctx.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: continue
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                ok++
            }
            val msg = when {
                ok == 1 -> "Saved to Pictures/S Notes"
                ok > 1 -> "$ok images saved to Pictures/S Notes"
                else -> "Save failed"
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Save failed", Toast.LENGTH_SHORT).show()
        } finally {
            for (bmp in pages) bmp.recycle()
        }
    }
}
