package com.hesi.snotes

import android.content.Context
import android.graphics.RectF
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.concurrent.Executors

/**
 * Notes live in internal storage:
 *   files/notes/<id>.json         -> page rect + strokes
 *   files/notes/<id>.meta         -> "name\ncreatedMillis"
 *   files/notes/trash/<id>.*      -> requirement 3: the trash bin
 *
 * Nothing in the trash is ever removed automatically - it stays until the user
 * restores it or deletes it permanently.
 */
object NoteStore {

    /**
     * @param modified last-saved time
     * @param created  first-created time
     * @param bytes    storage this note occupies (requirement 5)
     */
    data class Meta(
        val id: String,
        val name: String,
        val modified: Long,
        val created: Long,
        val bytes: Long
    )

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "note-writer").apply { priority = Thread.MIN_PRIORITY }
    }

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, "notes").apply { if (!exists()) mkdirs() }

    private fun trashDir(ctx: Context): File =
        File(dir(ctx), "trash").apply { if (!exists()) mkdirs() }

    private fun dataFile(ctx: Context, id: String) = File(dir(ctx), "$id.json")
    private fun metaFile(ctx: Context, id: String) = File(dir(ctx), "$id.meta")
    private fun trashData(ctx: Context, id: String) = File(trashDir(ctx), "$id.json")
    private fun trashMeta(ctx: Context, id: String) = File(trashDir(ctx), "$id.meta")

    // =====================================================================
    //  Listing
    // =====================================================================

    private fun listIn(folder: File): List<Meta> {
        val out = ArrayList<Meta>()
        val files = folder.listFiles { f -> f.isFile && f.name.endsWith(".meta") } ?: return out
        for (f in files) {
            val id = f.name.removeSuffix(".meta")
            val raw = runCatching { f.readText() }.getOrDefault("NOTE")
            val lines = raw.split("\n")
            val name = lines.getOrNull(0)?.ifBlank { "NOTE" } ?: "NOTE"
            val data = File(folder, "$id.json")
            val created = lines.getOrNull(1)?.trim()?.toLongOrNull()
                ?: f.lastModified().let { if (it > 0) it else System.currentTimeMillis() }
            val mod = data.lastModified().let { if (it > 0) it else f.lastModified() }
            // PDF notebooks also own their source PDF (files/notes/pdf/<id>/)
            val notesRoot = if (folder.name == "trash") folder.parentFile else folder
            val pdfBytes = File(File(notesRoot, "pdf"), id).listFiles()
                ?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
            out.add(Meta(id, name, mod, created, data.length() + f.length() + pdfBytes))
        }
        out.sortByDescending { it.modified }
        return out
    }

    fun list(ctx: Context): List<Meta> = listIn(dir(ctx))

    fun listTrash(ctx: Context): List<Meta> = listIn(trashDir(ctx))

    fun trashCount(ctx: Context): Int =
        trashDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".meta") }?.size ?: 0

    /** Total bytes used by all notes (trash included). */
    fun totalBytes(ctx: Context): Long {
        var t = 0L
        dir(ctx).walkTopDown().forEach { if (it.isFile) t += it.length() }
        return t
    }

    // =====================================================================
    //  CRUD
    // =====================================================================

    // Requirement B3: page modes. 0 = infinite length (one endless page),
    // 1 = legacy limited pages (kept only so old notebooks still open), 2 = infinite multiple pages.
    const val PAGE_INFINITE = 0
    const val PAGE_LIMITED = 1
    const val PAGE_MULTI_INFINITE = 2
    /** Imported PDF: one fixed canvas sheet per PDF page (see PdfNotebook). */
    const val PAGE_PDF = 3
    private const val A4_H = 3111f          // A4 height for a 2200-wide column
    private const val LIMITED_PAGES = 4

    fun create(ctx: Context, name: String, pageMode: Int = PAGE_INFINITE): String {
        var id = System.currentTimeMillis().toString()
        while (metaFile(ctx, id).exists() || trashMeta(ctx, id).exists()) id += "1"
        writeMeta(ctx, id, name, System.currentTimeMillis(), pageMode)
        val h = 3000f
        save(ctx, id, RectF(0f, 0f, 2200f, h), emptyList())
        return id
    }

    fun readPageMode(ctx: Context, id: String): Int =
        runCatching { metaFile(ctx, id).readText().split("\n")[2].trim().toInt() }
            .getOrDefault(PAGE_INFINITE)

    // ---------- Objects (requirement C4) ----------
    // Kept in a SEPARATE file so the stroke format is untouched and old notes
    // load unchanged.
    private fun objFile(ctx: Context, id: String) = File(dir(ctx), "$id.obj.json")
    private fun objImgDir(ctx: Context, id: String) =
        File(File(dir(ctx), "objimg"), id).apply { if (!exists()) mkdirs() }

    fun objImageFile(ctx: Context, id: String, name: String) = File(objImgDir(ctx, id), name)

    /** Same as [saveObjects] but writes before returning (used by upgrades). */
    fun saveObjectsNow(
        ctx: Context, id: String, texts: List<TextObject>, images: List<ImageObject>
    ) {
        writeAtomic(objFile(ctx, id), encodeObjects(texts, images))
    }

    fun saveObjects(
        ctx: Context, id: String, texts: List<TextObject>, images: List<ImageObject>
    ) {
        val json = encodeObjects(texts, images)
        io.execute { runCatching { writeAtomic(objFile(ctx, id), json) } }
    }

    private fun encodeObjects(texts: List<TextObject>, images: List<ImageObject>): String {
        val root = JSONObject()
        val ta = JSONArray()
        for (t in texts) {
            ta.put(JSONObject().apply {
                put("t", t.text); put("x", t.x.toDouble()); put("y", t.y.toDouble())
                put("w", t.w.toDouble()); put("h", t.h.toDouble())
                put("s", t.size.toDouble()); put("c", t.color); put("r", t.rotation.toDouble())
            })
        }
        val ia = JSONArray()
        for (im in images) {
            ia.put(JSONObject().apply {
                put("n", im.name); put("x", im.x.toDouble()); put("y", im.y.toDouble())
                put("w", im.w.toDouble()); put("h", im.h.toDouble()); put("r", im.rotation.toDouble())
            })
        }
        root.put("texts", ta); root.put("images", ia)
        return root.toString()
    }

    /** Loads objects (bitmaps decoded from the note's image dir). Empty if none. */
    fun loadObjects(ctx: Context, id: String): Pair<List<TextObject>, List<ImageObject>> {
        val texts = ArrayList<TextObject>()
        val images = ArrayList<ImageObject>()
        runCatching {
            val f = objFile(ctx, id)
            if (!f.exists()) return texts to images
            val root = JSONObject(f.readText())
            val ta = root.optJSONArray("texts")
            if (ta != null) for (i in 0 until ta.length()) {
                val o = ta.getJSONObject(i)
                texts.add(
                    TextObject(
                        o.getString("t"), o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                        o.optDouble("w", 620.0).toFloat(), o.optDouble("h", 180.0).toFloat(),
                        o.optDouble("s", 64.0).toFloat(), o.optInt("c", 0xFF000000.toInt()),
                        o.optDouble("r", 0.0).toFloat()
                    )
                )
            }
            val ia = root.optJSONArray("images")
            if (ia != null) for (i in 0 until ia.length()) {
                val o = ia.getJSONObject(i)
                val name = o.getString("n")
                val bmp = runCatching {
                    android.graphics.BitmapFactory.decodeFile(objImageFile(ctx, id, name).absolutePath)
                }.getOrNull()
                val im = ImageObject(
                    name, o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                    o.getDouble("w").toFloat(), o.getDouble("h").toFloat(),
                    o.optDouble("r", 0.0).toFloat()
                )
                im.bitmap = bmp
                if (bmp != null) images.add(im)
            }
        }
        return texts to images
    }

    private fun writeMeta(ctx: Context, id: String, name: String, created: Long, pageMode: Int = PAGE_INFINITE) {
        metaFile(ctx, id).writeText("$name\n$created\n$pageMode")
    }

    fun readName(ctx: Context, id: String): String =
        runCatching { metaFile(ctx, id).readText().split("\n")[0] }
            .getOrDefault("NOTE").ifBlank { "NOTE" }

    fun readCreated(ctx: Context, id: String): Long =
        runCatching { metaFile(ctx, id).readText().split("\n")[1].trim().toLong() }
            .getOrNull() ?: metaFile(ctx, id).lastModified()

    fun readModified(ctx: Context, id: String): Long = dataFile(ctx, id).lastModified()

    fun sizeBytes(ctx: Context, id: String): Long =
        dataFile(ctx, id).length() + metaFile(ctx, id).length()

    fun rename(ctx: Context, id: String, newName: String) {
        writeMeta(ctx, id, newName, readCreated(ctx, id), readPageMode(ctx, id))
    }

    // ---- Requirement 3: trash instead of instant destruction ----

    fun moveToTrash(ctx: Context, id: String): Boolean {
        trashDir(ctx)
        val okMeta = metaFile(ctx, id).renameTo(trashMeta(ctx, id))
        val okData = dataFile(ctx, id).renameTo(trashData(ctx, id))
        return okMeta || okData
    }

    fun restoreFromTrash(ctx: Context, id: String): Boolean {
        val okMeta = trashMeta(ctx, id).renameTo(metaFile(ctx, id))
        val okData = trashData(ctx, id).renameTo(dataFile(ctx, id))
        return okMeta || okData
    }

    /** Permanent - used both from the note list and from inside the trash. */
    fun deleteForever(ctx: Context, id: String) {
        dataFile(ctx, id).delete()
        metaFile(ctx, id).delete()
        trashData(ctx, id).delete()
        trashMeta(ctx, id).delete()
        objFile(ctx, id).delete()
        objImgDir(ctx, id).deleteRecursively()
        PdfNotebook.delete(ctx, id)
    }

    fun emptyTrash(ctx: Context) {
        // Remove each trashed note completely (objects + imported PDF too).
        trashDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".meta") }
            ?.forEach { deleteForever(ctx, it.name.removeSuffix(".meta")) }
        trashDir(ctx).listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    // =====================================================================
    //  Persistence
    // =====================================================================

    /**
     * Streams the note straight to disk in the same JSON format as before
     * ({"page":[..],"strokes":[{"c","w","e","s","p","pr"}]}), so old and new
     * builds read each other's files.
     *
     * Performance: the old encoder built an org.json tree - one boxed Double per
     * coordinate, a JSONObject per stroke, then one giant String - every time
     * autosave fired (1.5 s after you lift the pen). On a full page that was
     * hundreds of milliseconds of CPU and tens of MB of garbage after every
     * pause, which is the periodic spike / creeping RAM seen while writing.
     * This writer formats numbers into a reusable char buffer with no
     * per-point allocation at all.
     *
     * [colors] (optional) overrides each stroke's colour, used to store notes
     * theme-independently without allocating copies of every stroke.
     */
    private fun writeNote(f: File, page: RectF, strokes: List<Stroke>, colors: IntArray?) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        FastJsonOut(tmp).use { o ->
            o.raw("{\"page\":[")
            o.fixed(page.left, 2); o.raw(","); o.fixed(page.top, 2); o.raw(",")
            o.fixed(page.right, 2); o.raw(","); o.fixed(page.bottom, 2)
            o.raw("],\"strokes\":[")
            for ((k, s) in strokes.withIndex()) {
                if (k > 0) o.raw(",")
                o.raw("{\"c\":"); o.int(colors?.get(k) ?: s.color)
                o.raw(",\"w\":"); o.fixed(s.width, 3)
                o.raw(",\"e\":"); o.int(if (s.eraser) 1 else 0)
                o.raw(",\"s\":"); o.int(if (s.straight) 1 else 0)
                o.raw(",\"p\":[")
                val p = s.points
                val n = p.size
                for (i in 0 until n) {
                    if (i > 0) o.raw(",")
                    o.fixed(p[i], 2)
                }
                o.raw("]")
                // stylus pressure: per-point width multiplier (2 decimals is plenty)
                if (s.hasPressure()) {
                    o.raw(",\"pr\":[")
                    val pr = s.pressures!!
                    for (i in 0 until pr.size) {
                        if (i > 0) o.raw(",")
                        o.fixed(pr[i], 2)
                    }
                    o.raw("]")
                }
                o.raw("}")
            }
            o.raw("]}")
        }
        if (!tmp.renameTo(f)) {
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
    }

    fun save(ctx: Context, id: String, page: RectF, strokes: List<Stroke>) {
        writeNote(dataFile(ctx, id), page, strokes, null)
    }

    /**
     * The caller thread only takes a cheap snapshot (stroke references, their
     * colours and the page rect); the encode AND the write both run on the
     * low-priority writer thread, so drawing is never blocked. Sealed strokes
     * never change length, so reading their point buffers off-thread is safe.
     *
     * [flipBW] stores black/white ink swapped (notes are saved in light form).
     */
    fun saveAsync(ctx: Context, id: String, page: RectF, strokes: List<Stroke>, flipBW: Boolean = false) {
        val pageCopy = RectF(page)
        val snapshot = ArrayList(strokes)
        val colors = IntArray(snapshot.size) { i ->
            val s = snapshot[i]
            val c = s.color
            if (flipBW && !s.eraser) when (c) {
                android.graphics.Color.WHITE -> android.graphics.Color.BLACK
                android.graphics.Color.BLACK -> android.graphics.Color.WHITE
                else -> c
            } else c
        }
        val target = dataFile(ctx, id)
        io.execute {
            runCatching { writeNote(target, pageCopy, snapshot, colors) }
        }
    }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    /** Returns (page, strokes) or null if the note can't be read. */
    fun load(ctx: Context, id: String): Pair<RectF, ArrayList<Stroke>>? =
        loadFrom(dataFile(ctx, id))

    fun loadFromTrash(ctx: Context, id: String): Pair<RectF, ArrayList<Stroke>>? =
        loadFrom(trashData(ctx, id))

    /**
     * Streaming reader (android.util.JsonReader): coordinates go straight into
     * primitive buffers instead of first building a whole org.json tree of
     * boxed Doubles, so opening a big note needs a fraction of the memory.
     */
    private fun loadFrom(f: File): Pair<RectF, ArrayList<Stroke>>? {
        if (!f.exists()) return null
        return runCatching {
            val page = RectF(0f, 0f, 2200f, 3000f)
            val strokes = ArrayList<Stroke>()
            JsonReader(BufferedReader(InputStreamReader(FileInputStream(f), Charsets.UTF_8), 1 shl 16)).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "page" -> {
                            r.beginArray()
                            val v = FloatArray(4)
                            var i = 0
                            while (r.hasNext()) {
                                val d = r.nextDouble().toFloat()
                                if (i < 4) v[i] = d
                                i++
                            }
                            r.endArray()
                            if (i >= 4) page.set(v[0], v[1], v[2], v[3])
                        }
                        "strokes" -> {
                            r.beginArray()
                            while (r.hasNext()) readStroke(r)?.let { strokes.add(it) }
                            r.endArray()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
            }
            page to strokes
        }.getOrNull()
    }

    private fun readStroke(r: JsonReader): Stroke? {
        var color = 0xFF000000.toInt()
        var width = 5f
        var eraser = false
        var straight = false
        var pts: FloatList? = null
        var prs: FloatList? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "c" -> color = r.nextLong().toInt()
                "w" -> width = r.nextDouble().toFloat()
                "e" -> eraser = readFlag(r)
                "s" -> straight = readFlag(r)
                "p" -> pts = readFloats(r)
                "pr" -> prs = readFloats(r)
                else -> r.skipValue()
            }
        }
        r.endObject()
        val p = pts ?: return null
        val pressures = prs?.takeIf { it.size * 2 == p.size && it.size >= 2 }
        // Pressure strokes are baked into their filled outline here, on the
        // background thread; plain strokes just need the centre line.
        return Stroke(p, color, width, eraser, straight, pressures).also { it.seal() }
    }

    private fun readFlag(r: JsonReader): Boolean = when (r.peek()) {
        JsonToken.BOOLEAN -> r.nextBoolean()
        JsonToken.NUMBER -> r.nextInt() != 0
        else -> { r.skipValue(); false }
    }

    private fun readFloats(r: JsonReader): FloatList {
        val out = FloatList(64)
        r.beginArray()
        while (r.hasNext()) out.add(r.nextDouble().toFloat())
        r.endArray()
        return out
    }

    /** Pressure list for a stroke, or null if absent / not matching the points. */
    fun decodePressures(arr: JSONArray?, pointCount: Int): FloatList? {
        if (arr == null || arr.length() != pointCount || pointCount < 2) return null
        val out = FloatList(pointCount)
        for (i in 0 until arr.length()) out.add(arr.getDouble(i).toFloat())
        return out
    }

    /** "12.4 KB" / "1.8 MB" - shown on the home screen. */
    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
    }
}
