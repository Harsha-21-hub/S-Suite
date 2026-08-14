package com.hesi.snotes

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable S Notes notebook format (.hesi). */
object HesiNotebook {
    const val MIME = "application/x-hesi"
    private const val FORMAT = 1
    private const val MAX_UNCOMPRESSED = 256L * 1024L * 1024L

    data class Imported(
        val name: String,
        val pageMode: Int,
        val page: android.graphics.RectF,
        val strokes: ArrayList<Stroke>,
        val texts: List<TextObject>,
        val images: List<ImageObject>
    )

    fun exportToCache(
        ctx: Context,
        name: String,
        pageMode: Int,
        page: android.graphics.RectF,
        strokes: List<Stroke>,
        texts: List<TextObject>,
        images: List<ImageObject>
    ): File {
        val root = File(ctx.cacheDir, "shared").apply { mkdirs() }
        val safeName = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "Notebook" }
        val out = File(root, "$safeName.hesi")
        val tmp = File(root, "$safeName.hesi.tmp")

        ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zip ->
            val manifest = JSONObject().apply {
                put("format", "hesi")
                put("version", FORMAT)
                put("name", name)
                put("pageMode", pageMode)
            }
            writeText(zip, "manifest.json", manifest.toString())

            val pageJson = JSONArray().apply {
                put(page.left.toDouble()); put(page.top.toDouble())
                put(page.right.toDouble()); put(page.bottom.toDouble())
            }
            val rootJson = JSONObject().apply {
                put("page", pageJson)
                put("strokes", encodeStrokes(strokes))
            }
            writeText(zip, "note.json", rootJson.toString())
            writeText(zip, "objects.json", encodeObjects(texts, images).toString())

            for ((index, image) in images.withIndex()) {
                val bmp = image.bitmap ?: continue
                val entryName = "images/${sanitizeEntryName(image.name.ifBlank { "image_$index.png" })}"
                zip.putNextEntry(ZipEntry(entryName))
                bmp.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
                writeText(zip, "image_map/$index.txt", entryName)
            }
        }
        if (!tmp.renameTo(out)) {
            out.delete()
            if (!tmp.renameTo(out)) error("Unable to create notebook file")
        }
        return out
    }

    fun share(
        ctx: Context,
        name: String,
        pageMode: Int,
        page: android.graphics.RectF,
        strokes: List<Stroke>,
        texts: List<TextObject>,
        images: List<ImageObject>
    ) {
        val file = exportToCache(ctx, name, pageMode, page, strokes, texts, images)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.fileprovider", file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(android.content.Intent.createChooser(intent, "Share notebook"))
    }

    fun importFromUri(ctx: Context, uri: Uri): String {
        val work = File(ctx.cacheDir, "hesi_import_${System.currentTimeMillis()}")
        if (!work.mkdirs()) error("Unable to prepare notebook import")
        try {
            extractSafely(ctx, uri, work)
            val manifest = JSONObject(File(work, "manifest.json").readText())
            require(manifest.optString("format") == "hesi") { "Not an S Notes notebook" }
            require(manifest.optInt("version", -1) == FORMAT) { "Unsupported .hesi version" }

            val note = JSONObject(File(work, "note.json").readText())
            val pa = note.getJSONArray("page")
            val page = android.graphics.RectF(
                pa.getDouble(0).toFloat(), pa.getDouble(1).toFloat(),
                pa.getDouble(2).toFloat(), pa.getDouble(3).toFloat()
            )
            val strokes = decodeStrokes(note.getJSONArray("strokes"))
            val objects = decodeObjects(File(work, "objects.json").readText(), work)

            val name = manifest.optString("name", "Imported Notebook").ifBlank { "Imported Notebook" }
            val pageMode = manifest.optInt("pageMode", NoteStore.PAGE_INFINITE)
            val id = NoteStore.create(ctx, uniqueName(ctx, name), pageMode)
            NoteStore.save(ctx, id, page, strokes)

            for (image in objects.second) {
                val src = File(work, "images/${sanitizeEntryName(image.name)}")
                if (src.exists()) {
                    src.inputStream().use { input ->
                        NoteStore.objImageFile(ctx, id, image.name).outputStream().use { output -> input.copyTo(output) }
                    }
                    image.bitmap = android.graphics.BitmapFactory.decodeFile(
                        NoteStore.objImageFile(ctx, id, image.name).absolutePath
                    )
                }
            }
            NoteStore.saveObjects(ctx, id, objects.first, objects.second)
            return id
        } finally {
            work.deleteRecursively()
        }
    }

    fun displayName(ctx: Context, uri: Uri): String {
        var name: String? = null
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0)
            }
        }
        return name?.substringBeforeLast('.')?.ifBlank { "Imported Notebook" } ?: "Imported Notebook"
    }

    private fun uniqueName(ctx: Context, requested: String): String {
        val base = requested.trim().ifBlank { "Imported Notebook" }
        val existing = NoteStore.list(ctx).map { it.name.lowercase() }.toHashSet()
        if (base.lowercase() !in existing) return base
        var n = 2
        while ("$base ($n)".lowercase() in existing) n++
        return "$base ($n)"
    }

    private fun encodeStrokes(strokes: List<Stroke>): JSONArray = JSONArray().apply {
        for (s in strokes) put(JSONObject().apply {
            put("color", s.color)
            put("width", s.width.toDouble())
            put("eraser", s.eraser)
            put("straight", s.straight)
            put("points", JSONArray().apply { s.points.forEach { put(it.toDouble()) } })
        })
    }

    private fun decodeStrokes(arr: JSONArray): ArrayList<Stroke> = ArrayList<Stroke>().apply {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pts = o.getJSONArray("points")
            val list = ArrayList<Float>(pts.length())
            for (j in 0 until pts.length()) list.add(pts.getDouble(j).toFloat())
            add(Stroke(
                list,
                o.getInt("color"),
                o.getDouble("width").toFloat(),
                o.optBoolean("eraser", false),
                o.optBoolean("straight", false),
                null
            ).also { it.rebuild() })
        }
    }

    private fun encodeObjects(texts: List<TextObject>, images: List<ImageObject>) = JSONObject().apply {
        put("texts", JSONArray().apply {
            texts.forEach { t -> put(JSONObject().apply {
                put("text", t.text); put("x", t.x); put("y", t.y); put("w", t.w); put("h", t.h)
                put("size", t.size); put("color", t.color); put("rotation", t.rotation)
            }) }
        })
        put("images", JSONArray().apply {
            images.forEach { im -> put(JSONObject().apply {
                put("name", im.name); put("x", im.x); put("y", im.y); put("w", im.w); put("h", im.h)
                put("rotation", im.rotation)
            }) }
        })
    }

    private fun decodeObjects(text: String, work: File): Pair<List<TextObject>, List<ImageObject>> {
        val root = JSONObject(text)
        val texts = ArrayList<TextObject>()
        val images = ArrayList<ImageObject>()
        val ta = root.optJSONArray("texts")
        if (ta != null) for (i in 0 until ta.length()) {
            val o = ta.getJSONObject(i)
            texts.add(TextObject(
                o.getString("text"), o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                o.getDouble("w").toFloat(), o.getDouble("h").toFloat(), o.getDouble("size").toFloat(),
                o.getInt("color"), o.optDouble("rotation", 0.0).toFloat()
            ))
        }
        val ia = root.optJSONArray("images")
        if (ia != null) for (i in 0 until ia.length()) {
            val o = ia.getJSONObject(i)
            val name = sanitizeEntryName(o.getString("name"))
            images.add(ImageObject(
                name, o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                o.getDouble("w").toFloat(), o.getDouble("h").toFloat(),
                o.optDouble("rotation", 0.0).toFloat()
            ))
        }
        return texts to images
    }

    private fun writeText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun sanitizeEntryName(name: String): String {
        val last = name.replace('\\', '/').substringAfterLast('/')
        require(last.isNotBlank() && last != "." && last != "..") { "Invalid notebook entry" }
        return last.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun extractSafely(ctx: Context, uri: Uri, destination: File) {
        var total = 0L
        ctx.contentResolver.openInputStream(uri).use { raw ->
            requireNotNull(raw) { "Cannot read notebook" }
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                val buffer = ByteArray(8192)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory) { "Invalid notebook" }
                    val name = entry.name.replace('\\', '/')
                    require(!name.startsWith("/") && !name.split('/').contains("..")) { "Invalid notebook path" }
                    require(name.length <= 240) { "Invalid notebook entry name" }
                    val out = File(destination, name)
                    require(out.canonicalPath.startsWith(destination.canonicalPath + File.separator)) { "Invalid notebook path" }
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos ->
                        while (true) {
                            val n = zip.read(buffer)
                            if (n <= 0) break
                            total += n
                            require(total <= MAX_UNCOMPRESSED) { "Notebook is too large" }
                            fos.write(buffer, 0, n)
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        require(File(destination, "manifest.json").isFile) { "Invalid .hesi notebook" }
        require(File(destination, "note.json").isFile) { "Invalid .hesi notebook" }
        require(File(destination, "objects.json").isFile) { "Invalid .hesi notebook" }
    }
}
