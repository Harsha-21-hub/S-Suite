package com.sdrive.shared.player

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.media.AudioManager
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.sdrive.shared.Kind
import com.sdrive.shared.Prefs
import com.sdrive.shared.Storage
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * Software gain stage that lets volume go up to 300% (like VLC).
 * Runs on 16-bit PCM inside ExoPlayer's audio pipeline, with a soft limiter so boosted
 * audio saturates smoothly instead of crackling. Costs ~nothing at gain 1.0 (straight copy).
 */
@OptIn(UnstableApi::class)
class GainAudioProcessor : BaseAudioProcessor() {
    @Volatile var gain = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        val g = gain
        if (g == 1f) {
            out.put(inputBuffer)
        } else {
            while (inputBuffer.remaining() >= 2) {
                val x = inputBuffer.short * g / 32768f
                out.putShort((softClip(x) * 32767f).toInt().toShort())
            }
            if (inputBuffer.hasRemaining()) out.put(inputBuffer)
        }
        out.flip()
    }

    private fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val y = KNEE + (1f - KNEE) * tanh(((a - KNEE) / (1f - KNEE)).toDouble()).toFloat()
        return if (x < 0) -y else y
    }

    private companion object { const val KNEE = 0.80f }
}

/**
 * One shared ExoPlayer for the whole app (audio keeps playing in background via [PlaybackService]).
 * Tuned for wearables: hardware decoders, SurfaceView output, small buffers (low RAM).
 */
@OptIn(UnstableApi::class)
class PlayerEngine private constructor(private val app: Context) {
    val gainProcessor = GainAudioProcessor()
    private val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 0.0 .. 3.0  (1.0 = 100 %; above that is software boost) */
    var volume by mutableFloatStateOf(1f)
        private set
    var droppedFrames by mutableIntStateOf(0)
        private set
    var decoderName by mutableStateOf<String?>(null)
        private set
    var queue: List<File> = emptyList()
        private set

    val player: ExoPlayer

    init {
        val renderers = object : DefaultRenderersFactory(app) {
            override fun buildAudioSink(
                context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf<AudioProcessor>(gainProcessor))
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }.setEnableDecoderFallback(true)
            // Hardware/system decoders first; FFmpeg only for audio they can't decode (AC-3, DTS… in MKV etc.).
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        // Local files only: small buffers keep RAM low and start-up instant.
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(4_000, 15_000, 500, 1_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        player = ExoPlayer.Builder(app, renderers)
            .setLoadControl(load)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            .build()
        // Only switch display refresh rate when it can be done without a visible glitch.
        player.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, count: Int, elapsedMs: Long) {
                droppedFrames += count
            }
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long,
            ) {
                this@PlayerEngine.decoderName = decoderName
                droppedFrames = 0
            }
        })
        syncVolumeFromSystem()
    }

    fun open(files: List<File>, index: Int) {
        queue = files
        val siblings = files.firstOrNull()?.parentFile?.listFiles()?.toList().orEmpty()
        val subsByBase = siblings.filter { Storage.kindOf(it.name) == Kind.SUBTITLE }
        val items = files.map { item(it, subsByBase) }
        val start = files[index.coerceIn(0, files.lastIndex)]
        player.setMediaItems(items, index.coerceIn(0, files.lastIndex), Prefs.resume(start.path))
        player.prepare()
        player.play()
    }

    private fun item(f: File, subs: List<File>): MediaItem {
        val base = f.nameWithoutExtension
        val subConfigs = subs.filter { it.name.startsWith(base) }.mapIndexed { i, s ->
            MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(s))
                .setMimeType(
                    when (Storage.ext(s.name)) {
                        "vtt" -> MimeTypes.TEXT_VTT
                        "ass", "ssa" -> MimeTypes.TEXT_SSA
                        else -> MimeTypes.APPLICATION_SUBRIP
                    }
                )
                .setLabel(s.name)
                .setSelectionFlags(if (i == 0) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        return MediaItem.Builder()
            .setUri(Uri.fromFile(f))
            .setMediaId(f.path)
            .setSubtitleConfigurations(subConfigs)
            .setMediaMetadata(MediaMetadata.Builder().setDisplayTitle(base).build())
            .build()
    }

    /** VLC-style: 0–100 % drives the system media volume, 100–300 % adds software boost. */
    fun setVol(v: Float) {
        val c = (v.coerceIn(0f, 3f) * 100).roundToInt() / 100f
        volume = c
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        try {
            if (c <= 1f) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (c * max).roundToInt(), 0)
                gainProcessor.gain = 1f
            } else {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
                gainProcessor.gain = c
            }
        } catch (_: SecurityException) {
            gainProcessor.gain = c.coerceAtLeast(1f)
        }
    }

    fun syncVolumeFromSystem() {
        if (gainProcessor.gain > 1f) return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volume = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    fun saveResume() {
        val item = player.currentMediaItem ?: return
        val pos = player.currentPosition
        val dur = player.duration
        val keep = pos > 5_000 && (dur <= 0 || pos < dur - 5_000)
        Prefs.setResume(item.mediaId, if (keep) pos else 0L)
    }

    val currentFile: File? get() = player.currentMediaItem?.mediaId?.let { File(it) }

    companion object {
        @Volatile private var inst: PlayerEngine? = null
        fun get(ctx: Context): PlayerEngine =
            inst ?: synchronized(this) { inst ?: PlayerEngine(ctx.applicationContext).also { inst = it } }
        fun peek(): PlayerEngine? = inst
        fun release() {
            inst?.player?.release()
            inst = null
        }
    }
}

/** Gives background playback + lock-screen/watch-face media controls. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val engine = PlayerEngine.get(this)
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val b = MediaSession.Builder(this, engine.player)
        if (launch != null) {
            b.setSessionActivity(PendingIntent.getActivity(this, 1, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        session = b.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        PlayerEngine.peek()?.saveResume()
        session?.release()
        session = null
        PlayerEngine.release()
        super.onDestroy()
    }
}
