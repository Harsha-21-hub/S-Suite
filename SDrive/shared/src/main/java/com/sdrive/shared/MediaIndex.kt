package com.sdrive.shared

import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.EnumSet
import java.util.concurrent.Executors

/**
 * Background threads with LOW priority: indexing and thumbnail decoding then only use CPU the UI
 * doesn't need (the watch has 2 small cores; normal-priority workers made scrolling stutter).
 */
internal fun lowPriorityDispatcher(threads: Int, name: String): CoroutineDispatcher =
    Executors.newFixedThreadPool(threads) { r ->
        Thread({
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) } catch (_: Throwable) {}
            r.run()
        }, name).apply { isDaemon = true }
    }.asCoroutineDispatcher()

/** Google-Files-style categories. */
enum class Cat(val label: String, val unit: String) {
    DOWNLOADS("Downloads", "files"),
    IMAGES("Images", "images"),
    VIDEOS("Videos", "videos"),
    AUDIO("Audio", "tracks"),
    DOCUMENTS("Documents", "docs"),
    APPS("Apps", "APKs"),
    ARCHIVES("Archives", "archives");

    fun matches(f: IFile): Boolean = when (this) {
        DOWNLOADS -> f.inDownloads
        IMAGES -> f.kind == Kind.IMAGE && f.media
        VIDEOS -> f.kind == Kind.VIDEO && f.media
        AUDIO -> f.kind == Kind.AUDIO && f.media
        DOCUMENTS -> f.kind == Kind.DOC
        APPS -> f.kind == Kind.APK
        ARCHIVES -> f.kind == Kind.ARCHIVE
    }
}

/** One indexed file. Kept tiny: a few thousand of these cost well under 1 MB. */
class IFile(
    val path: String,
    val name: String,
    val size: Long,
    val mtime: Long,
    val kind: Kind,
    /** false inside folders that contain a .nomedia file (app caches etc.) */
    val media: Boolean,
    val inDownloads: Boolean,
) {
    val dir: String get() = path.substring(0, (path.length - name.length - 1).coerceAtLeast(0))
    val file: File get() = File(path)
}

data class CatStat(val count: Int, val bytes: Long)

/** A folder holding files of one category; [cover] = its newest file (used as thumbnail). */
class FolderGroup(val dir: String, val name: String, val count: Int, val bytes: Long, val newest: Long, val cover: IFile)

class IndexSnapshot(
    val files: List<IFile>,
    val stats: Map<Cat, CatStat>,
    val recents: List<IFile>,
    val totalBytes: Long,
    val root: String,
    val builtAt: Long,
) {
    fun of(cat: Cat): List<IFile> = files.filter { cat.matches(it) }

    /** Files of a category, newest first; [dir] = null means every folder. */
    fun files(cat: Cat, dir: String?): List<IFile> =
        files.filter { cat.matches(it) && (dir == null || it.dir == dir) }.sortedByDescending { it.mtime }

    /** The folders that contain files of this category ("classify by folder"), most recently changed first. */
    fun folders(cat: Cat): List<FolderGroup> {
        val groups = LinkedHashMap<String, MutableList<IFile>>()
        files.forEach { f -> if (cat.matches(f)) groups.getOrPut(f.dir) { ArrayList() }.add(f) }
        return groups.map { (dir, list) ->
            val name = if (dir == root) "Internal storage" else dir.substringAfterLast('/')
            val cover = list.maxBy { it.mtime }
            FolderGroup(dir, name, list.size, list.sumOf { it.size }, cover.mtime, cover)
        }.sortedByDescending { it.newest }
    }

    fun search(q: String, limit: Int = 300): List<IFile> {
        val t = q.trim()
        if (t.isEmpty()) return emptyList()
        return files.asSequence().filter { it.name.contains(t, ignoreCase = true) }
            .sortedByDescending { it.mtime }.take(limit).toList()
    }

    fun large(minBytes: Long, limit: Int = 60): List<IFile> =
        files.asSequence().filter { it.size >= minBytes }.sortedByDescending { it.size }.take(limit).toList()

    fun byPaths(paths: Set<String>): List<IFile> = files.filter { it.path in paths }
}

/**
 * Background index of everything on the device's storage (like Google Files' categories).
 * Walks the storage once (a few hundred ms on the watch) and caches the result in memory;
 * refreshed after transfers, file operations, or when the app comes back to the foreground.
 */
object MediaIndex {
    val snap = MutableStateFlow<IndexSnapshot?>(null)
    val scanning = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + lowPriorityDispatcher(1, "sd-index"))
    private val lock = Any()
    private var running = false
    private var again = false
    @Volatile private var dirty = true

    /** Something changed on disk; the next [refreshIfStale] will rescan. */
    fun markDirty() { dirty = true }

    /** Rescan now (e.g. after delete / move / rename). */
    fun invalidate() { dirty = true; refresh() }

    fun refreshIfStale(maxAgeMs: Long = 90_000) {
        val s = snap.value
        val stale = s == null || dirty || SystemClock.elapsedRealtime() - s.builtAt > maxAgeMs ||
            s.root != Storage.root().path
        if (stale) refresh()
    }

    fun refresh() {
        synchronized(lock) {
            if (running) { again = true; return }
            running = true
        }
        scanning.value = true
        scope.launch {
            while (true) {
                dirty = false
                try { snap.value = build() } catch (_: Throwable) {}
                synchronized(lock) {
                    if (!again) {
                        running = false
                        scanning.value = false
                        return@launch
                    }
                    again = false
                }
            }
        }
    }

    private fun build(): IndexSnapshot {
        val root = Storage.root()
        val rootPath = root.path
        val dlPrefix = Storage.downloadsDir().canonicalPath + "/"
        val skip = setOf("$rootPath/Android/data", "$rootPath/Android/obb")
        val out = ArrayList<IFile>(4096)
        val mediaStack = ArrayDeque<Boolean>()

        Files.walkFileTree(root.toPath(), EnumSet.noneOf(FileVisitOption::class.java), 40,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val p = dir.toString()
                    if (p != rootPath) {
                        val n = dir.fileName?.toString().orEmpty()
                        if (n.startsWith(".") || p in skip) return FileVisitResult.SKIP_SUBTREE
                    }
                    val parentMedia = mediaStack.lastOrNull() ?: true
                    mediaStack.addLast(parentMedia && !File(p, ".nomedia").exists())
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    mediaStack.removeLastOrNull()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                    val name = file.fileName?.toString() ?: return FileVisitResult.CONTINUE
                    if (name.startsWith(".")) return FileVisitResult.CONTINUE
                    val path = file.toString()
                    out += IFile(
                        path = path,
                        name = name,
                        size = attrs.size(),
                        mtime = attrs.lastModifiedTime().toMillis(),
                        kind = Storage.kindOf(name),
                        media = mediaStack.lastOrNull() ?: true,
                        inDownloads = path.startsWith(dlPrefix),
                    )
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })

        val counts = IntArray(Cat.entries.size)
        val bytes = LongArray(Cat.entries.size)
        var total = 0L
        for (f in out) {
            total += f.size
            for (c in Cat.entries) if (c.matches(f)) { counts[c.ordinal]++; bytes[c.ordinal] += f.size }
        }
        val stats = Cat.entries.associateWith { CatStat(counts[it.ordinal], bytes[it.ordinal]) }
        val recents = out.asSequence()
            .filter { (it.media && (it.kind == Kind.IMAGE || it.kind == Kind.VIDEO || it.kind == Kind.AUDIO)) ||
                it.kind == Kind.DOC || it.kind == Kind.APK }
            .sortedByDescending { it.mtime }.take(24).toList()
        return IndexSnapshot(out, stats, recents, total, rootPath, SystemClock.elapsedRealtime())
    }

    // ---------------- duplicates ----------------

    class DupGroup(val files: List<IFile>) {
        val wasted: Long get() = files.drop(1).sumOf { it.size }
    }

    /**
     * Finds identical files: same size, then same fingerprint (first + last 64 KB, MD5).
     * Fast even for big videos because only 128 KB per candidate is read.
     */
    suspend fun duplicates(s: IndexSnapshot): List<DupGroup> = withContext(Dispatchers.IO) {
        val bySize = s.files.filter { it.size >= 4096 }.groupBy { it.size }.values.filter { it.size > 1 }
        val groups = ArrayList<DupGroup>()
        var budget = 3000
        for (same in bySize) {
            if (budget <= 0) break
            budget -= same.size
            val byHash = same.groupBy { fingerprint(it) }
            byHash.forEach { (h, list) ->
                if (h != null && list.size > 1) groups += DupGroup(list.sortedByDescending { it.mtime })
            }
        }
        groups.sortedByDescending { it.wasted }
    }

    private fun fingerprint(f: IFile): String? = try {
        val md = MessageDigest.getInstance("MD5")
        RandomAccessFile(f.path, "r").use { raf ->
            val buf = ByteArray(64 * 1024)
            val n1 = raf.read(buf)
            if (n1 > 0) md.update(buf, 0, n1)
            val len = raf.length()
            if (len > buf.size * 2L) {
                raf.seek(len - buf.size)
                val n2 = raf.read(buf)
                if (n2 > 0) md.update(buf, 0, n2)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { null }
}