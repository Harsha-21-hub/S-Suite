package com.sdrive.shared.ui

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.provider.Settings
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.sdrive.shared.AppState
import com.sdrive.shared.Prefs
import com.sdrive.shared.Storage
import com.sdrive.shared.player.PlaybackService
import com.sdrive.shared.player.PlayerEngine
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private enum class Fit(val label: String) { FIT("Fit"), FILL("Fill"), STRETCH("Stretch") }
private enum class Panel { MAIN }

private val SPEEDS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)

private fun speedLabel(s: Float): String =
    if (s == s.roundToInt().toFloat()) "${s.roundToInt()}×" else String.format(Locale.US, "%s×", s.toString().trimEnd('0'))

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(files: List<java.io.File>?, startIndex: Int) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val watch = LocalIsWatch.current
    val view = LocalView.current
    val density = LocalDensity.current
    val activity = remember { ctx.findActivity() }
    val engine = remember { PlayerEngine.get(ctx) }
    val player: ExoPlayer = engine.player

    // ---------- open + session binding ----------
    LaunchedEffect(Unit) {
        if (files != null && files.isNotEmpty()) engine.open(files, startIndex)
        else if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }
    DisposableEffect(Unit) {
        val fut = MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))).buildAsync()
        onDispose {
            engine.saveResume()
            if (player.videoSize.width > 0) player.pause() // videos stop when you leave; music keeps playing
            MediaController.releaseFuture(fut)
            AppState.videoPlaying = false
            activity?.window?.let { w ->
                w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
            if (!watch) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // ---------- player state mirrored into Compose ----------
    var playing by remember { mutableStateOf(player.isPlaying) }
    var vsize by remember { mutableStateOf(player.videoSize) }
    var tracks by remember { mutableStateOf(player.currentTracks) }
    var meta by remember { mutableStateOf(player.mediaMetadata) }
    var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    var repeat by remember { mutableIntStateOf(player.repeatMode) }
    var shuffle by remember { mutableStateOf(player.shuffleModeEnabled) }
    var buffering by remember { mutableStateOf(false) }
    var duration by remember { mutableLongStateOf(player.duration.coerceAtLeast(0)) }
    var position by remember { mutableLongStateOf(player.currentPosition) }
    var cue by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var abA by remember { mutableLongStateOf(-1L) }
    var abB by remember { mutableLongStateOf(-1L) }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                playing = p.isPlaying
                vsize = p.videoSize
                tracks = p.currentTracks
                meta = p.mediaMetadata
                speed = p.playbackParameters.speed
                repeat = p.repeatMode
                shuffle = p.shuffleModeEnabled
                buffering = p.playbackState == Player.STATE_BUFFERING
                duration = p.duration.coerceAtLeast(0)
                position = p.currentPosition
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) { abA = -1; abB = -1; error = null; cue = "" }
            }
            override fun onCues(cueGroup: CueGroup) {
                cue = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n")
            }
            override fun onPlayerError(e: PlaybackException) {
                error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                        "This device can't decode this file's format (e.g. HEVC 10-bit / 4K video). " +
                            "Tip: send videos from the phone with \"Shrink videos for watch\" ON."
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                        "Can't read this file (unsupported or damaged container)."
                    else -> "Can't play this file (${e.errorCodeName.removePrefix("ERROR_CODE_")})"
                }
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    val hasVideo = vsize.width > 0 || tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
    AppState.videoPlaying = hasVideo && playing

    // ---------- UI state ----------
    var controls by remember { mutableStateOf(true) }
    var poke by remember { mutableIntStateOf(0) }
    var locked by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var fit by remember { mutableStateOf(Fit.FIT) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var osd by remember { mutableStateOf<String?>(null) }
    var osdLevel by remember { mutableFloatStateOf(-1f) }
    var osdTick by remember { mutableIntStateOf(0) }
    var seekPreview by remember { mutableLongStateOf(-1L) }
    var boostedFrom by remember { mutableFloatStateOf(0f) }
    var sleepAt by remember { mutableLongStateOf(0L) }
    var subScale by remember { mutableFloatStateOf(Prefs.subtitleScale) }
    val fr = remember { FocusRequester() }

    fun show(text: String, level: Float = -1f) { osd = text; osdLevel = level; osdTick++ }
    fun interact() { controls = true; poke++ }
    fun seekBy(ms: Long) {
        val t = (player.currentPosition + ms).coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
        player.seekTo(t); position = t
        show(if (ms > 0) "+${ms / 1000}s" else "${ms / 1000}s")
    }
    fun togglePlay() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        if (player.isPlaying) player.pause() else player.play()
    }
    fun setVol(v: Float) {
        engine.setVol(v)
        show("Volume ${(engine.volume * 100).roundToInt()}%", engine.volume / 3f)
    }
    fun brightnessNow(): Float = activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f }
        ?: (Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
    fun setBrightness(b: Float) {
        val w = activity?.window ?: return
        val c = b.coerceIn(0.02f, 1f)
        w.attributes = w.attributes.apply { screenBrightness = c }
        show("Brightness ${(c * 100).roundToInt()}%", c)
    }

    // ---------- effects ----------
    DisposableEffect(playing, hasVideo) {
        view.keepScreenOn = playing && hasVideo
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(controls, poke, playing, panel, locked, hasVideo) {
        if (controls && playing && panel == null && hasVideo) { delay(3500); controls = false }
    }
    LaunchedEffect(osdTick) { if (osd != null) { delay(900); osd = null } }
    LaunchedEffect(showUnlock) { if (showUnlock) { delay(2500); showUnlock = false } }
    LaunchedEffect(playing, controls, abB, panel) {
        while (playing && (controls || abB >= 0 || panel != null || !hasVideo)) {
            position = player.currentPosition
            if (abB > abA && abA >= 0 && position >= abB) player.seekTo(abA)
            delay(if (abB >= 0) 120 else 250)
        }
        position = player.currentPosition
    }
    LaunchedEffect(sleepAt) {
        if (sleepAt > 0) {
            delay((sleepAt - System.currentTimeMillis()).coerceAtLeast(0))
            player.pause(); sleepAt = 0
        }
    }
    LaunchedEffect(panel) { if (panel == null) try { fr.requestFocus() } catch (_: Exception) {} }
    LaunchedEffect(Unit) { engine.syncVolumeFromSystem() }

    BackHandler(enabled = panel != null || locked) {
        if (panel != null) panel = null else { showUnlock = true }
    }

    val title = meta.title?.toString() ?: meta.displayTitle?.toString() ?: engine.currentFile?.nameWithoutExtension ?: ""
    val shownPos = if (seekPreview >= 0) seekPreview else position
    val big = if (watch) 46.dp else 64.dp
    val mid = if (watch) 32.dp else 48.dp
    val small = if (watch) 28.dp else 40.dp

    if (AppState.pip) { // phone picture-in-picture: video only
        Box(Modifier.fillMaxSize().background(Color.Black)) { VideoSurface(player, vsize, fit, 1f, Offset.Zero) }
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onRotaryScrollEvent { e ->
                if (panel == null && !locked) {
                    setVol(engine.volume + if (e.verticalScrollPixels > 0) 0.05f else -0.05f)
                    true
                } else false
            }
            .focusRequester(fr)
            .focusable()
    ) {
        // ---------- picture ----------
        if (hasVideo) VideoSurface(player, vsize, fit, zoom, pan)
        else AudioArt(meta.artworkData, watch)

        // ---------- gestures ----------
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(locked) { // pinch to zoom
                    if (locked) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.count { it.pressed } >= 2) {
                                zoom = (zoom * ev.calculateZoom()).coerceIn(1f, 5f)
                                pan += ev.calculatePan()
                                if (zoom == 1f) pan = Offset.Zero
                                show("Zoom ${(zoom * 100).roundToInt()}%")
                                ev.changes.forEach { it.consume() }
                            }
                            if (ev.changes.none { it.pressed }) break
                        }
                    }
                }
                .pointerInput(locked, hasVideo) {
                    detectTapGestures(
                        onTap = {
                            if (locked) showUnlock = true
                            else if (hasVideo) { controls = !controls; poke++ } else interact()
                        },
                        onDoubleTap = { o ->
                            if (!locked) when {
                                o.x < size.width * 0.35f -> seekBy(-10_000)
                                o.x > size.width * 0.65f -> seekBy(10_000)
                                else -> togglePlay()
                            }
                        },
                        onLongPress = {
                            if (!locked && player.isPlaying) {
                                boostedFrom = player.playbackParameters.speed
                                player.setPlaybackSpeed(2f); show("2× while holding")
                            }
                        },
                        onPress = {
                            tryAwaitRelease()
                            if (boostedFrom > 0f) { player.setPlaybackSpeed(boostedFrom); boostedFrom = 0f }
                        },
                    )
                }
                .pointerInput(locked, hasVideo) {
                    if (locked) return@pointerInput
                    var mode = 0
                    var acc = Offset.Zero
                    var left = false
                    var startVol = 1f
                    var startBright = 0.5f
                    var startPos = 0L
                    var lastSeek = 0L
                    fun end() {
                        if (mode == 1 && seekPreview >= 0) {
                            player.setSeekParameters(SeekParameters.EXACT)
                            player.seekTo(seekPreview); position = seekPreview
                        }
                        seekPreview = -1; mode = 0
                    }
                    detectDragGestures(
                        onDragStart = { o ->
                            mode = 0; acc = Offset.Zero; left = o.x < size.width / 2f
                            startVol = engine.volume; startBright = brightnessNow(); startPos = player.currentPosition
                        },
                        onDragEnd = { end() },
                        onDragCancel = { end() },
                        onDrag = { ch, d ->
                            ch.consume()
                            acc += d
                            if (mode == 0) mode = when {
                                zoom > 1f -> 4
                                abs(acc.x) > abs(acc.y) -> 1
                                left && hasVideo -> 3
                                else -> 2
                            }
                            when (mode) {
                                1 -> if (duration > 0) {
                                    val span = min(duration, 100_000L).toFloat()
                                    val t = (startPos + acc.x / size.width * span).roundToLong().coerceIn(0, duration)
                                    seekPreview = t
                                    show("${Storage.fmtTime(t)} / ${Storage.fmtTime(duration)}", t.toFloat() / duration)
                                    val now = System.currentTimeMillis()
                                    if (now - lastSeek > 150) {
                                        lastSeek = now
                                        player.setSeekParameters(SeekParameters.CLOSEST_SYNC) // fast preview seeks
                                        player.seekTo(t)
                                    }
                                }
                                2 -> setVol(startVol - acc.y / size.height * 2f)
                                3 -> setBrightness(startBright - acc.y / size.height * 1.2f)
                                4 -> pan += d
                            }
                        },
                    )
                }
        )

        // ---------- subtitles ----------
        if (cue.isNotEmpty() && hasVideo) {
            Txt(
                cue,
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = if (controls) (if (watch) 64.dp else 96.dp) else (if (watch) 30.dp else 24.dp), start = 24.dp, end = 24.dp)
                    .background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                size = (if (watch) 13f else 17f).times(subScale).sp, lines = 4, align = TextAlign.Center,
            )
        }

        // ---------- center OSD ----------
        osd?.let { t ->
            Column(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Color(0xCC000000))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Txt(t, size = if (watch) 14.sp else 18.sp, weight = FontWeight.Bold)
                if (osdLevel >= 0f) {
                    Spacer(Modifier.height(6.dp))
                    Bar(osdLevel, Modifier.width(if (watch) 90.dp else 140.dp),
                        color = if (osd?.startsWith("Volume") == true && engine.volume > 1f) Pal.warn else Pal.accent)
                }
            }
        }
        if (buffering) Txt("…", Modifier.align(Alignment.Center), size = 28.sp, color = Pal.dim)

        // ---------- controls ----------
        AnimatedVisibility(visible = (controls || !hasVideo) && !locked && panel == null, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(
                    if (hasVideo) Brush.verticalGradient(listOf(Color(0x99000000), Color(0x33000000), Color(0xAA000000)))
                    else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                )
            ) {
                Column(
                    Modifier.align(Alignment.TopCenter).padding(top = if (watch) 20.dp else 16.dp)
                        .fillMaxWidth(if (watch) 0.66f else 0.9f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!watch) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RoundBtn(Ic.back, { nav.back() }, size = small, bg = Color(0x66000000))
                        Spacer(Modifier.weight(1f))
                    }
                    Txt(title, size = if (watch) 12.sp else 16.sp, weight = FontWeight.Bold, align = TextAlign.Center,
                        modifier = if (hasVideo) Modifier else Modifier.padding(top = if (watch) 0.dp else 0.dp))
                    val artist = meta.artist?.toString()
                    if (!hasVideo && artist != null) Txt(artist, size = 11.sp, color = Pal.dim, align = TextAlign.Center)
                    if (player.mediaItemCount > 1) Txt("${player.currentMediaItemIndex + 1} / ${player.mediaItemCount}", size = 10.sp, color = Pal.dim)
                }

                Row(
                    Modifier.align(Alignment.Center).offset(y = if (watch) (if (hasVideo) (-6).dp else 10.dp) else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (watch) 4.dp else 18.dp),
                ) {
                    RoundBtn(Ic.prev, { player.seekToPreviousMediaItem(); interact() }, size = small, bg = Color(0x55000000), enabled = player.hasPreviousMediaItem())
                    RoundBtn(Ic.rewind, { seekBy(-10_000); interact() }, size = mid, bg = Color(0x55000000))
                    RoundBtn(if (playing) Ic.pause else Ic.play, { togglePlay(); interact() }, size = big, bg = Pal.accent, tint = Pal.bg)
                    RoundBtn(Ic.forward, { seekBy(10_000); interact() }, size = mid, bg = Color(0x55000000))
                    RoundBtn(Ic.next, { player.seekToNextMediaItem(); interact() }, size = small, bg = Color(0x55000000), enabled = player.hasNextMediaItem())
                }

                Column(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = if (watch) 24.dp else 20.dp)
                        .fillMaxWidth(if (watch) 0.72f else 0.94f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        Txt(Storage.fmtTime(shownPos), size = 10.sp, color = Pal.text)
                        Spacer(Modifier.weight(1f))
                        if (abA >= 0) Txt(if (abB >= 0) "A-B" else "A…", size = 10.sp, color = Pal.warn)
                        Spacer(Modifier.weight(1f))
                        Txt("-" + Storage.fmtTime(duration - shownPos), size = 10.sp, color = Pal.text)
                    }
                    SeekBar(
                        fraction = if (duration > 0) shownPos.toFloat() / duration else 0f,
                        onScrub = { f ->
                            val t = (f * duration).toLong(); seekPreview = t; poke++
                            player.setSeekParameters(SeekParameters.CLOSEST_SYNC); player.seekTo(t)
                        },
                        onScrubEnd = { f ->
                            val t = (f * duration).toLong()
                            player.setSeekParameters(SeekParameters.EXACT); player.seekTo(t)
                            position = t; seekPreview = -1; poke++
                        },
                    )
                    Spacer(Modifier.height(if (watch) 2.dp else 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(if (watch) 6.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (hasVideo) RoundBtn(Ic.lock, { locked = true; controls = false; show("Locked · tap to unlock") }, size = small, bg = Color(0x55000000))
                        else RoundBtn(Ic.repeat, {
                            val r = (repeat + 1) % 3; player.repeatMode = r
                            show(arrayOf("Repeat off", "Repeat one", "Repeat all")[r])
                        }, size = small, bg = if (repeat != Player.REPEAT_MODE_OFF) Pal.accentDim else Color(0x55000000))
                        Box(
                            Modifier.height(small).clip(RoundedCornerShape(50)).background(Color(0x55000000))
                                .pointerInput(Unit) {
                                    detectTapGestures {
                                        val i = SPEEDS.indexOfFirst { it >= player.playbackParameters.speed + 0.01f }
                                        val s = if (i < 0) 1f else SPEEDS[i]
                                        player.setPlaybackSpeed(s); show("Speed ${speedLabel(s)}"); poke++
                                    }
                                }.padding(horizontal = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) { Txt(speedLabel(speed), size = if (watch) 11.sp else 14.sp, weight = FontWeight.Bold) }
                        if (hasVideo) RoundBtn(Ic.aspect, {
                            fit = Fit.entries[(fit.ordinal + 1) % Fit.entries.size]; zoom = 1f; pan = Offset.Zero
                            show(fit.label); poke++
                        }, size = small, bg = Color(0x55000000))
                        else RoundBtn(Ic.volume, { panel = Panel.MAIN }, size = small, bg = Color(0x55000000))
                        RoundBtn(Ic.more, { panel = Panel.MAIN }, size = small, bg = Color(0x55000000))
                    }
                }
            }
        }

        if (locked && showUnlock) {
            RoundBtn(Ic.unlock, { locked = false; showUnlock = false; interact() }, Modifier.align(Alignment.Center), size = big, bg = Pal.accent, tint = Pal.bg)
        }

        error?.let { e ->
            Column(Modifier.align(Alignment.Center).fillMaxWidth(0.8f), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(e, size = 13.sp, color = Pal.danger, lines = 5, align = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoundBtn(Ic.close, { nav.back() })
                    if (player.hasNextMediaItem()) RoundBtn(Ic.next, { error = null; player.seekToNextMediaItem(); player.prepare(); player.play() })
                }
            }
        }

        if (panel != null) {
            MorePanel(
                engine = engine, player = player, tracks = tracks, hasVideo = hasVideo, speed = speed,
                fit = fit, onFit = { fit = it; zoom = 1f; pan = Offset.Zero }, zoom = zoom, onZoom = { zoom = it; if (it == 1f) pan = Offset.Zero },
                repeat = repeat, shuffle = shuffle, abA = abA, abB = abB,
                onAB = { a, b -> abA = a; abB = b }, position = { player.currentPosition },
                sleepAt = sleepAt, onSleep = { sleepAt = it }, subScale = subScale,
                onSubScale = { subScale = it; Prefs.subtitleScale = it },
                onRotate = if (watch) null else {
                    {
                        val a = activity
                        if (a != null) a.requestedOrientation =
                            if (a.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                },
                onClose = { panel = null },
            )
        }
    }
}

/** Video goes straight from the hardware decoder to a SurfaceView: the cheapest, smoothest path on Android. */
@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(player: ExoPlayer, vs: androidx.media3.common.VideoSize, fit: Fit, zoom: Float, pan: Offset) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bw = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val bh = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val vw = if (vs.width > 0) vs.width * vs.pixelWidthHeightRatio else 16f
        val vh = if (vs.height > 0) vs.height.toFloat() else 9f
        val (w, h) = when (fit) {
            Fit.STRETCH -> bw to bh
            Fit.FIT -> { val s = min(bw / vw, bh / vh); vw * s to vh * s }
            Fit.FILL -> { val s = max(bw / vw, bh / vh); vw * s to vh * s }
        }
        val wd = with(density) { (w * zoom).toDp() }
        val hd = with(density) { (h * zoom).toDp() }
        AndroidView(
            factory = { c -> SurfaceView(c).also { player.setVideoSurfaceView(it) } },
            onRelease = { player.clearVideoSurfaceView(it) },
            modifier = Modifier.requiredSize(wd, hd).offset { IntOffset(pan.x.roundToInt(), pan.y.roundToInt()) },
        )
    }
}

@Composable
private fun AudioArt(art: ByteArray?, watch: Boolean) {
    val bmp = remember(art) {
        art?.let {
            try {
                val o = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeByteArray(it, 0, it.size, o)?.asImageBitmap()
            } catch (_: Exception) { null }
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.35f)
        } else {
            Box(Modifier.size(if (watch) 140.dp else 220.dp).clip(CircleShape).background(Pal.audio.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                Ico(Ic.music, Pal.audio.copy(alpha = 0.35f), if (watch) 60.dp else 96.dp)
            }
        }
    }
}

@Composable
private fun SeekBar(fraction: Float, onScrub: (Float) -> Unit, onScrubEnd: (Float) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val scrub by rememberUpdatedState(onScrub)
    val end by rememberUpdatedState(onScrubEnd)
    val f = (drag ?: fraction).coerceIn(0f, 1f)
    Box(
        Modifier.fillMaxWidth().height(22.dp)
            .pointerInput(Unit) { detectTapGestures { o -> end((o.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f); scrub(drag!!) },
                    onDragEnd = { drag?.let(end); drag = null },
                    onDragCancel = { drag = null },
                ) { ch, dx ->
                    ch.consume()
                    drag = ((drag ?: 0f) + dx / size.width).coerceIn(0f, 1f)
                    scrub(drag!!)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Bar(f, height = if (drag != null) 6.dp else 4.dp)
        Box(Modifier.fillMaxWidth(f.coerceAtLeast(0.001f))) {
            Box(Modifier.align(Alignment.CenterEnd).offset(x = 6.dp).size(12.dp).clip(CircleShape).background(Pal.accent))
        }
    }
}

// ---------------- the "⋮" panel: every extra control ----------------

@OptIn(UnstableApi::class)
@Composable
private fun MorePanel(
    engine: PlayerEngine, player: ExoPlayer, tracks: Tracks, hasVideo: Boolean, speed: Float,
    fit: Fit, onFit: (Fit) -> Unit, zoom: Float, onZoom: (Float) -> Unit,
    repeat: Int, shuffle: Boolean, abA: Long, abB: Long, onAB: (Long, Long) -> Unit, position: () -> Long,
    sleepAt: Long, onSleep: (Long) -> Unit, subScale: Float, onSubScale: (Float) -> Unit,
    onRotate: (() -> Unit)?, onClose: () -> Unit,
) {
    val watch = LocalIsWatch.current
    Box(Modifier.fillMaxSize().background(Color(0xF2000000))) {
        WList {
            item { Txt("Player", size = if (watch) 15.sp else 20.sp, weight = FontWeight.Bold) }

            // Volume 0–300 %
            item { SectionTitle("Volume  ${(engine.volume * 100).roundToInt()}%") }
            item {
                Row(Modifier.curved(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoundBtn(Ic.down, { engine.setVol(engine.volume - 0.1f) }, size = 34.dp)
                    Bar(engine.volume / 3f, Modifier.width(if (watch) 70.dp else 160.dp), color = if (engine.volume > 1f) Pal.warn else Pal.accent, height = 8.dp)
                    RoundBtn(Ic.up, { engine.setVol(engine.volume + 0.1f) }, size = 34.dp)
                }
            }
            item {
                TagRow {
                    listOf(0.5f, 1f, 2f, 3f).forEach { v ->
                        Tag("${(v * 100).roundToInt()}%", abs(engine.volume - v) < 0.01f, { engine.setVol(v) })
                    }
                }
            }

            item { SectionTitle("Speed") }
            SPEEDS.toList().chunked(4).forEach { row ->
                item { TagRow { row.forEach { s -> Tag(speedLabel(s), abs(speed - s) < 0.01f, { player.setPlaybackSpeed(s) }) } } }
            }

            if (hasVideo) {
                item { SectionTitle("Picture") }
                item { TagRow { Fit.entries.forEach { f -> Tag(f.label, f == fit, { onFit(f) }) } } }
                item {
                    TagRow {
                        Tag("−", false, { onZoom((zoom - 0.25f).coerceAtLeast(1f)) })
                        Tag("Zoom ${(zoom * 100).roundToInt()}%", zoom == 1f, { onZoom(1f) })
                        Tag("+", false, { onZoom((zoom + 0.25f).coerceAtMost(5f)) })
                    }
                }
                if (onRotate != null) item { Chip("Rotate screen", onRotate, icon = Ic.aspect) }
            }

            val audio = trackOptions(tracks, C.TRACK_TYPE_AUDIO)
            if (audio.size > 1) {
                item { SectionTitle("Audio track") }
                audio.forEach { (g, i, label) ->
                    item { Chip(label, { selectTrack(player, g, i) }, selected = g.isTrackSelected(i)) }
                }
            }

            val text = trackOptions(tracks, C.TRACK_TYPE_TEXT)
            if (hasVideo) {
                item { SectionTitle("Subtitles") }
                val off = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || text.none { it.first.isTrackSelected(it.second) }
                item {
                    Chip("Off", {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                    }, selected = off)
                }
                text.forEach { (g, i, label) -> item { Chip(label, { selectTrack(player, g, i) }, selected = !off && g.isTrackSelected(i)) } }
                if (text.isEmpty()) item { Txt("Tip: put a .srt with the same name next to the video", size = 11.sp, color = Pal.dim, lines = 3, align = TextAlign.Center) }
                item {
                    TagRow {
                        Tag("A−", false, { onSubScale((subScale - 0.15f).coerceAtLeast(0.6f)) })
                        Tag("Size ${(subScale * 100).roundToInt()}%", false, { onSubScale(1f) })
                        Tag("A+", false, { onSubScale((subScale + 0.15f).coerceAtMost(2.5f)) })
                    }
                }
            }

            item { SectionTitle("Repeat") }
            item {
                TagRow {
                    Tag("Off", repeat == Player.REPEAT_MODE_OFF, { player.repeatMode = Player.REPEAT_MODE_OFF })
                    Tag("One", repeat == Player.REPEAT_MODE_ONE, { player.repeatMode = Player.REPEAT_MODE_ONE })
                    Tag("All", repeat == Player.REPEAT_MODE_ALL, { player.repeatMode = Player.REPEAT_MODE_ALL })
                }
            }
            item { TagRow { Tag(if (shuffle) "Shuffle on" else "Shuffle off", shuffle, { player.shuffleModeEnabled = !shuffle }) } }

            item { SectionTitle("A-B repeat") }
            item {
                TagRow {
                    Tag(if (abA >= 0) "A ${Storage.fmtTime(abA)}" else "Set A", abA >= 0, { onAB(position(), -1) })
                    Tag(if (abB >= 0) "B ${Storage.fmtTime(abB)}" else "Set B", abB >= 0, {
                        val p = position(); if (abA in 0 until p) onAB(abA, p)
                    })
                    Tag("Clear", false, { onAB(-1, -1) })
                }
            }

            item { SectionTitle(if (sleepAt > 0) "Sleep timer · ${Storage.fmtTime(sleepAt - System.currentTimeMillis())} left" else "Sleep timer") }
            item {
                TagRow {
                    Tag("Off", sleepAt == 0L, { onSleep(0) })
                    listOf(15, 30, 60).forEach { m -> Tag("${m}m", false, { onSleep(System.currentTimeMillis() + m * 60_000L) }) }
                }
            }

            if (hasVideo) {
                item { SectionTitle("Playback info") }
                item {
                    val f: Format? = player.videoFormat
                    val fps = f?.frameRate?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.0f fps", it) } ?: "? fps"
                    val dec = engine.decoderName ?: "?"
                    val hw = if (dec.startsWith("OMX.google") || dec.startsWith("c2.android")) "software" else "hardware"
                    Txt(
                        "${f?.width ?: 0}×${f?.height ?: 0} · $fps · ${f?.sampleMimeType?.substringAfter('/') ?: ""}\n$hw decoder: $dec\ndropped frames: ${engine.droppedFrames}",
                        Modifier.curved(), size = 11.sp, color = Pal.dim, lines = 5, align = TextAlign.Center,
                    )
                }
            }
            item { Chip("Close", onClose, icon = Ic.close, iconTint = Pal.text) }
        }
    }
}

private fun trackOptions(tracks: Tracks, type: Int): List<Triple<Tracks.Group, Int, String>> =
    tracks.groups.filter { it.type == type }.flatMap { g ->
        (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
            val f = g.getTrackFormat(i)
            val lang = f.language?.takeIf { it != "und" }?.let { Locale(it).displayLanguage }
            val label = listOfNotNull(f.label, lang, f.channelCount.takeIf { it > 0 }?.let { "${it}ch" })
                .distinct().joinToString(" · ").ifEmpty { "Track ${i + 1}" }
            Triple(g, i, label)
        }
    }

private fun selectTrack(player: ExoPlayer, g: Tracks.Group, i: Int) {
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(g.type, false)
        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
        .build()
}
