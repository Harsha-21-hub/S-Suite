package com.sdrive.shared

import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

/**
 * Small thumbnails for images, videos and album art.
 * Decoded at display size on 2 low-priority background threads and kept in a size-limited memory cache.
 */
object Thumbs {
    private val maxBytes: Int = run {
        val heap = Runtime.getRuntime().maxMemory()
        (heap / 10).coerceIn(4L * 1024 * 1024, 32L * 1024 * 1024).toInt()
    }

    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val failed: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val gate = Semaphore(2)
    private val bg = lowPriorityDispatcher(2, "sd-thumbs")

    private fun key(path: String, px: Int) = "$px|$path"

    fun cached(path: String, px: Int): Bitmap? = cache.get(key(path, px))

    fun canThumb(kind: Kind) = kind == Kind.IMAGE || kind == Kind.VIDEO || kind == Kind.AUDIO

    suspend fun load(path: String, kind: Kind, px: Int): Bitmap? {
        if (!canThumb(kind)) return null
        val k = key(path, px)
        cache.get(k)?.let { return it }
        if (k in failed) return null
        return withContext(bg) {
            gate.withPermit {
                cache.get(k) ?: try {
                    val f = File(path)
                    val sz = Size(px, px)
                    val b = when (kind) {
                        Kind.VIDEO -> ThumbnailUtils.createVideoThumbnail(f, sz, null)
                        Kind.AUDIO -> ThumbnailUtils.createAudioThumbnail(f, sz, null)
                        else -> ThumbnailUtils.createImageThumbnail(f, sz, null)
                    }
                    cache.put(k, b)
                    b
                } catch (_: Throwable) {
                    failed += k
                    null
                }
            }
        }
    }

    fun clear() {
        cache.evictAll()
        failed.clear()
    }
}