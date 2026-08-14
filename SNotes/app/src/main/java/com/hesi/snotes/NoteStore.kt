package com.hesi.snotes

import android.content.Context
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
            out.add(Meta(id, name, mod, created, data.length() + f.length()))
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

    fun saveObjects(
        ctx: Context, id: String, texts: List<TextObject>, images: List<ImageObject>
    ) {
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
        io.execute { runCatching { writeAtomic(objFile(ctx, id), root.toString()) } }
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
    }

    fun emptyTrash(ctx: Context) {
        trashDir(ctx).listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    // =====================================================================
    //  Persistence
    // =====================================================================

    private fun encode(page: RectF, strokes: List<Stroke>): String {
        val root = JSONObject()
        val pageArr = JSONArray()
        pageArr.put(page.left.toDouble())
        pageArr.put(page.top.toDouble())
        pageArr.put(page.right.toDouble())
        pageArr.put(page.bottom.toDouble())
        root.put("page", pageArr)

        val arr = JSONArray()
        for (s in strokes) {
            val o = JSONObject()
            o.put("c", s.color)
            o.put("w", s.width.toDouble())
            o.put("e", if (s.eraser) 1 else 0)
            o.put("s", if (s.straight) 1 else 0)
            val pts = JSONArray()
            for (p in s.points) pts.put(p.toDouble())
            o.put("p", pts)
            arr.put(o)
        }
        root.put("strokes", arr)
        return root.toString()
    }

    fun save(ctx: Context, id: String, page: RectF, strokes: List<Stroke>) {
        writeAtomic(dataFile(ctx, id), encode(page, strokes))
    }

    /**
     * Requirement 12 - the pen was freezing mid-stroke because JSON encoding of
     * every stroke ran on the main thread whenever a debounced autosave fired.
     * Now the caller thread only takes a cheap snapshot (a shallow copy of the
     * stroke-list references plus the page rect); the heavy encode AND the write
     * both run on the low-priority writer thread, so drawing is never blocked.
     *
     * The snapshot protects against the list being structurally changed while we
     * iterate it. Sealed strokes never have points added/removed, so reading
     * their point buffers off-thread is safe.
     */
    fun saveAsync(ctx: Context, id: String, page: RectF, strokes: List<Stroke>) {
        val pageCopy = RectF(page)
        val snapshot = ArrayList(strokes)
        val target = dataFile(ctx, id)
        io.execute {
            runCatching { writeAtomic(target, encode(pageCopy, snapshot)) }
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

    private fun loadFrom(f: File): Pair<RectF, ArrayList<Stroke>>? {
        if (!f.exists()) return null
        return runCatching {
            val root = JSONObject(f.readText())
            val pa = root.getJSONArray("page")
            val page = RectF(
                pa.getDouble(0).toFloat(), pa.getDouble(1).toFloat(),
                pa.getDouble(2).toFloat(), pa.getDouble(3).toFloat()
            )
            val strokes = ArrayList<Stroke>()
            val arr = root.getJSONArray("strokes")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pts = o.getJSONArray("p")
                val list = ArrayList<Float>(pts.length())
                for (j in 0 until pts.length()) list.add(pts.getDouble(j).toFloat())
                val s = Stroke(
                    list,
                    o.getInt("c"),
                    o.getDouble("w").toFloat(),
                    o.optInt("e", 0) == 1,
                    o.optInt("s", 0) == 1,
                    null
                )
                // Build only the exact centre-line path on the background thread.
                // No pressure outline / beautification is reconstructed.
                s.rebuild()
                strokes.add(s)
            }
            page to strokes
        }.getOrNull()
    }

    /** "12.4 KB" / "1.8 MB" - shown on the home screen. */
    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
    }
}
