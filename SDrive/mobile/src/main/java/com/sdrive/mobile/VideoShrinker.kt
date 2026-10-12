package com.sdrive.mobile

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.sdrive.shared.Src
import com.sdrive.shared.Transfers
import com.sdrive.shared.VideoOptimizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Phone-side hardware re-encode to 480p H.264 / AAC before sending to the watch.
 * The watch's decoder then has ~4× fewer pixels per frame -> rock-solid 60 fps and much less battery.
 */
@OptIn(UnstableApi::class)
object VideoShrinker : VideoOptimizer {
    override suspend fun optimize(ctx: Context, src: Src, h: Transfers.Handle): File = withContext(Dispatchers.Main) {
        val input: Uri = src.uri ?: Uri.fromFile(src.file!!)
        val dir = File(ctx.cacheDir, "shrink").apply { mkdirs() }
        val out = File(dir, src.name.substringBeforeLast('.') + "_watch.mp4")
        out.delete()
        h.note("Shrinking for watch…")
        suspendCancellableCoroutine { cont ->
            val main = Handler(Looper.getMainLooper())
            lateinit var poll: Runnable
            val transformer = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        main.removeCallbacks(poll)
                        if (cont.isActive) cont.resume(out)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        main.removeCallbacks(poll)
                        out.delete()
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            val edited = EditedMediaItem.Builder(MediaItem.fromUri(input))
                .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(480))))
                .build()
            transformer.start(edited, out.absolutePath)
            val holder = ProgressHolder()
            poll = Runnable {
                if (h.cancelled) {
                    transformer.cancel(); out.delete()
                    if (cont.isActive) cont.resumeWithException(Transfers.CancelledIo())
                    return@Runnable
                }
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    h.note("Shrinking for watch ${holder.progress}%")
                }
                main.postDelayed(poll, 500)
            }
            main.postDelayed(poll, 500)
            cont.invokeOnCancellation { main.post { main.removeCallbacks(poll); transformer.cancel(); out.delete() } }
        }
    }
}