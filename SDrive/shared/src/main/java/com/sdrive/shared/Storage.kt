package com.sdrive.shared

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

enum class Kind { DIR, VIDEO, AUDIO, IMAGE, SUBTITLE, APK, ARCHIVE, DOC, OTHER }

object Storage {
    const val BUF = 256 * 1024

    private val VIDEO = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts", "flv", "mpg", "mpeg", "3g2", "mts", "m2ts", "vob", "divx")
    private val AUDIO = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "amr", "mka", "weba", "m4b", "ac3", "eac3", "ec3", "3ga")
    private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    private val SUBS = setOf("srt", "vtt", "ass", "ssa")
    private val ARCH = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
    private val DOCS = setOf(
        "txt", "pdf", "doc", "docx", "md", "json", "xml", "csv", "log", "html", "htm", "rtf", "odt", "ods", "odp",
        "xls", "xlsx", "ppt", "pptx", "epub",
    )

    fun hasAllFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(App.ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /** Browsable root: whole internal storage when allowed, otherwise the app's own folder. */
    @Suppress("DEPRECATION")
    fun root(): File {
        val r = if (hasAllFiles()) Environment.getExternalStorageDirectory()
        else (App.ctx.getExternalFilesDir(null) ?: App.ctx.filesDir)
        r.mkdirs()
        return r.canonicalFile
    }

    @Suppress("DEPRECATION")
    fun downloadsDir(): File =
        if (hasAllFiles()) Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        else root()

    /** Received files go to Download/S-Drive (or the app folder without all-files access). */
    fun receiveDir(): File {
        val d = if (hasAllFiles()) File(downloadsDir(), App.RECEIVE_FOLDER) else File(root(), "Received")
        d.mkdirs()
        return d.canonicalFile
    }

    /** Sub-folder inside the receive dir, sanitised so a sender can't escape it. */
    fun receiveSub(sub: String?): File {
        val base = receiveDir()
        val clean = sub.orEmpty().split('/', '\\')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/") { safeName(it) }
        val d = if (clean.isEmpty()) base else File(base, clean)
        d.mkdirs()
        return d
    }

    fun inside(parent: File, f: File): Boolean =
        f.path == parent.path || f.path.startsWith(parent.path + File.separator)

    /** Resolve a path relative to [root]; returns null if it would escape the root. */
    fun resolve(rel: String?): File? {
        val r = root()
        if (rel.isNullOrBlank() || rel == "/") return r
        val f = File(r, rel.trimStart('/')).canonicalFile
        return if (inside(r, f)) f else null
    }

    fun rel(f: File): String {
        val r = root().path
        val p = f.canonicalPath
        return if (p.startsWith(r)) p.removePrefix(r).ifEmpty { "/" } else p
    }

    fun safeName(n: String): String =
        n.replace('/', '_').replace('\\', '_').replace("\u0000", "").trim().trimStart('.')
            .ifEmpty { "file" }.take(180)

    fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun kindOf(name: String): Kind = when (ext(name)) {
        in VIDEO -> Kind.VIDEO
        in AUDIO -> Kind.AUDIO
        in IMAGE -> Kind.IMAGE
        in SUBS -> Kind.SUBTITLE
        "apk" -> Kind.APK
        in ARCH -> Kind.ARCHIVE
        in DOCS -> Kind.DOC
        else -> Kind.OTHER
    }

    fun kind(f: File): Kind = if (f.isDirectory) Kind.DIR else kindOf(f.name)

    fun mime(name: String): String {
        val e = ext(name)
        return when (e) {
            "mkv" -> "video/x-matroska"
            "srt" -> "application/x-subrip"
            "vtt" -> "text/vtt"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(e) ?: "application/octet-stream"
        }
    }

    fun fmt(b: Long): String {
        if (b < 0) return "?"
        if (b < 1024) return "$b B"
        val u = arrayOf("KB", "MB", "GB", "TB")
        var v = b / 1024.0
        var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
        return if (v >= 100) String.format(Locale.US, "%.0f %s", v, u[i]) else String.format(Locale.US, "%.1f %s", v, u[i])
    }

    fun fmtTime(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
    }

    /** Copy/move [src] into [dir]. Never overwrites; never copies a folder into itself. */
    fun copyInto(src: File, dir: File, move: Boolean) {
        if (src.isDirectory && inside(src.canonicalFile, dir.canonicalFile)) throw IOException("Can't put a folder inside itself")
        if (src.parentFile?.canonicalPath == dir.canonicalPath && move) return
        val dest = unique(dir, src.name)
        if (move && src.renameTo(dest)) return
        if (src.isDirectory) src.copyRecursively(dest, overwrite = false)
        else src.copyTo(dest, overwrite = false, bufferSize = BUF)
        if (move) src.deleteRecursively()
    }

    fun scan(f: File) {
        try { MediaScannerConnection.scanFile(App.ctx, arrayOf(f.path), null, null) } catch (_: Exception) {}
        MediaIndex.markDirty()
    }
}

/** Copies with a large buffer and reports progress (throws if the transfer was cancelled). */
fun copyStream(ins: InputStream, out: OutputStream, h: Transfers.Handle?, start: Long = 0L): Long {
    val buf = ByteArray(Storage.BUF)
    var total = start
    while (true) {
        val n = ins.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
        h?.progress(total)
    }
    out.flush()
    return total
}

/** A thing to send: a file on disk or a content:// Uri shared from another app. */
class Src(
    val name: String,
    val size: Long,
    val sub: String,
    val mtime: Long?,
    val file: File?,
    val uri: Uri?,
    val open: () -> InputStream,
) {
    companion object {
        fun ofFile(f: File, sub: String = "") =
            Src(f.name, f.length(), sub, f.lastModified(), f, null) { FileInputStream(f) }

        /** Expands folders recursively, keeping the folder structure in [sub]. */
        fun fromFiles(files: List<File>): List<Src> {
            val out = ArrayList<Src>()
            for (f in files) {
                if (f.isDirectory) {
                    val base = f.parentFile ?: f
                    f.walkTopDown().filter { it.isFile }.forEach { c ->
                        out += ofFile(c, c.parentFile!!.relativeTo(base).path)
                    }
                } else if (f.isFile) out += ofFile(f)
            }
            return out
        }

        fun fromUris(ctx: Context, uris: List<Uri>): List<Src> {
            val cr = ctx.applicationContext.contentResolver
            return uris.map { u ->
                var name = u.lastPathSegment?.substringAfterLast('/') ?: "file"
                var size = -1L
                try {
                    cr.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            c.getString(0)?.let { name = it }
                            if (!c.isNull(1)) size = c.getLong(1)
                        }
                    }
                } catch (_: Exception) {}
                if (u.scheme == "file") u.path?.let { p -> File(p).takeIf { it.isFile }?.let { size = it.length() } }
                Src(Storage.safeName(name), size, "", null, null, u) {
                    cr.openInputStream(u) ?: throw IOException("Cannot open $name")
                }
            }
        }
    }
}