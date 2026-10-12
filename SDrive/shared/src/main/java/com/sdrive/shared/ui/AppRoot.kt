package com.sdrive.shared.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.sdrive.shared.App
import android.view.WindowManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import com.sdrive.shared.AppState
import com.sdrive.shared.Cat
import com.sdrive.shared.MediaIndex
import com.sdrive.shared.Prefs
import com.sdrive.shared.TState
import com.sdrive.shared.Transfers
import com.sdrive.shared.Discovery
import com.sdrive.shared.Storage
import java.io.File

sealed interface Screen {
    data object Files : Screen
    /** files == null -> return to whatever is currently playing */
    data class Play(val files: List<File>?, val index: Int = 0) : Screen
    data object Transfer : Screen
    data class Remote(val peer: Discovery.Peer) : Screen
    data class Send(val files: List<File>, val uris: List<Uri> = emptyList()) : Screen
    data class Image(val file: File) : Screen
    /** Google-Files-style category (Videos, Audio, Documents…) grouped by folder. */
    data class Category(val cat: Cat) : Screen
    /** Files of one category inside one folder ([dir] = null -> all folders). */
    data class CatFiles(val cat: Cat, val dir: String?, val title: String) : Screen
    data object Terminal : Screen
    data object Help : Screen
}

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Files)
    val top: Screen get() = stack.last()
    fun go(s: Screen) { stack.add(s) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun replace(s: Screen) { if (stack.size > 1) stack[stack.lastIndex] = s else stack.add(s) }
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
}

data class Prompt(val title: String, val initial: String = "", val numeric: Boolean = false, val onOk: (String) -> Unit)
data class Confirm(val title: String, val message: String, val ok: String, val danger: Boolean = false, val onOk: () -> Unit)

class Ui {
    var prompt by mutableStateOf<Prompt?>(null)
    var confirm by mutableStateOf<Confirm?>(null)
}

val LocalNav = staticCompositionLocalOf<Nav> { error("no nav") }
val LocalUi = staticCompositionLocalOf<Ui> { error("no ui") }
val LocalBrowser = staticCompositionLocalOf<BrowserModel> { error("no browser") }

/**
 * Entry point used by BOTH the watch and the phone app -> identical design everywhere.
 * [incoming] = files shared into the app from other apps (phone "Share → S Drive").
 */
@Composable
fun SDriveApp(isWatch: Boolean, incoming: List<Uri> = emptyList(), onIncomingHandled: () -> Unit = {}) {
    val nav = remember { Nav() }
    val ui = remember { Ui() }
    val browser = remember { BrowserModel() }

    LaunchedEffect(incoming) {
        if (incoming.isNotEmpty()) {
            nav.home(); nav.go(Screen.Send(emptyList(), incoming)); onIncomingHandled()
        }
    }

    // First launch: ask for the permissions the app can ask for itself (notifications, never sleep).
    // All-files access + speed-compile are applied automatically by the PC tool / phone terminal over ADB.
    val ctx0 = LocalContext.current
    LaunchedEffect(Unit) {
        val act = ctx0.findActivity()
        if (act != null && !Prefs.autoAsked) {
            Prefs.autoAsked = true
            FirstRun.ask(act)
        }
    }

    LaunchedEffect(AppState.openTransferTick) {
        if (AppState.openTransferTick > 0 && nav.top != Screen.Transfer) { nav.home(); nav.go(Screen.Transfer) }
    }

    // Categories index: (re)built in the background on start, on resume and after files arrive.
    val transfers by Transfers.list.collectAsState()
    val doneCount = transfers.count { it.state == TState.DONE }
    LaunchedEffect(AppState.resumeTick, doneCount) { MediaIndex.refreshIfStale() }

    // Keep the screen (and with it Wi-Fi + CPU) fully awake while something is transferring.
    val busy = transfers.any { it.state == TState.RUNNING || it.state == TState.QUEUED }
    val context = LocalContext.current
    DisposableEffect(busy) {
        val w = context.findActivity()?.window
        if (busy && Prefs.keepAwake) w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    CompositionLocalProvider(
        LocalIsWatch provides isWatch, LocalNav provides nav, LocalUi provides ui, LocalBrowser provides browser,
    ) {
        BackHandler(enabled = nav.stack.size > 1) { nav.back() }
        val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        Box(Modifier.fillMaxSize().background(Pal.bg).then(if (isWatch) Modifier.edgeSwipeBack { backDispatcher?.onBackPressed() } else Modifier)) {
            when (val s = nav.top) {
                Screen.Files -> BrowserScreen(browser)
                is Screen.Play -> PlayerScreen(s.files, s.index)
                Screen.Transfer -> TransferScreen()
                is Screen.Remote -> RemoteScreen(s.peer)
                is Screen.Send -> SendScreen(s.files, s.uris)
                is Screen.Image -> ImageScreen(s.file)
                is Screen.Category -> CategoryScreen(s.cat)
                is Screen.CatFiles -> CatFilesScreen(s.cat, s.dir, s.title)
                Screen.Terminal -> TerminalScreen()
                Screen.Help -> HelpScreen()
            }
            ui.confirm?.let { c -> ConfirmOverlay(c) { ui.confirm = null; AppState.focusTick++ } }
            ui.prompt?.let { p -> PromptOverlay(p) { ui.prompt = null; AppState.focusTick++ } }
        }
    }
}

@Composable
private fun PromptOverlay(p: Prompt, dismiss: () -> Unit) {
    val watch = LocalIsWatch.current
    // Watch keyboards drop digits in a text field, so numbers (PINs, codes) use our own keypad there.
    if (watch && p.numeric) { NumPadOverlay(p, dismiss); return }
    val sel = p.initial.lastIndexOf('.').takeIf { it > 0 } ?: p.initial.length
    var tf by remember(p) { mutableStateOf(TextFieldValue(p.initial, TextRange(0, sel))) }
    val fr = remember { FocusRequester() }
    fun ok() { val t = tf.text.trim(); if (t.isNotEmpty()) { dismiss(); p.onOk(t) } }
    BackHandler { dismiss() }
    Box(Modifier.fillMaxSize().background(Color(0xF0000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(if (watch) 0.8f else 0.9f), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(p.title, size = if (watch) 14.sp else 18.sp, weight = FontWeight.Bold, lines = 2, align = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            BasicTextField(
                value = tf, onValueChange = { tf = it }, singleLine = true,
                textStyle = TextStyle(color = Pal.text, fontSize = if (watch) 15.sp else 18.sp, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(Pal.accent),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (p.numeric) KeyboardType.NumberPassword else KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { ok() }),
                modifier = Modifier.fillMaxWidth().background(Pal.surface2, RoundedCornerShape(14.dp))
                    .padding(12.dp).focusRequester(fr),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RoundBtn(Ic.close, dismiss, bg = Pal.surface2)
                RoundBtn(Ic.check, { ok() }, bg = Pal.accent, tint = Pal.bg)
            }
        }
    }
    LaunchedEffect(Unit) { try { fr.requestFocus() } catch (_: Exception) {} }
}

/** Watch PIN / code entry: big round keys, no system keyboard involved. */
@Composable
private fun NumPadOverlay(p: Prompt, dismiss: () -> Unit) {
    var v by remember(p) { mutableStateOf(p.initial.filter { it.isDigit() }) }
    fun ok() { if (v.isNotEmpty()) { dismiss(); p.onOk(v) } }
    BackHandler { dismiss() }
    Box(Modifier.fillMaxSize().background(Color(0xF0000000)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(p.title, Modifier.width(118.dp), size = 10.sp, color = Pal.dim, lines = 2, align = TextAlign.Center)
            Spacer(Modifier.height(2.dp))
            Txt(if (v.isEmpty()) "- - - - - -" else v, size = 18.sp, color = if (v.isEmpty()) Pal.dim else Pal.text, font = DotFont)
            Spacer(Modifier.height(4.dp))
            listOf("123", "456", "789", "<0>").forEach { row ->
                Row(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { k ->
                        val bg = if (k == '>') Pal.accent else Pal.surface2
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(bg).clickable {
                                when (k) {
                                    '<' -> v = v.dropLast(1)
                                    '>' -> ok()
                                    else -> if (v.length < 12) v += k
                                }
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            when (k) {
                                '<' -> Ico(Ic.back, Pal.text, 16.dp)
                                '>' -> Ico(Ic.check, Pal.bg, 18.dp)
                                else -> Txt(k.toString(), size = 15.sp, weight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmOverlay(c: Confirm, dismiss: () -> Unit) {
    val watch = LocalIsWatch.current
    BackHandler { dismiss() }
    Box(Modifier.fillMaxSize().background(Color(0xF0000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(if (watch) 0.78f else 0.9f), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(c.title, size = if (watch) 15.sp else 19.sp, weight = FontWeight.Bold, lines = 2, align = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Txt(c.message, size = if (watch) 12.sp else 14.sp, color = Pal.dim, lines = 4, align = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundBtn(Ic.close, dismiss)
                RoundBtn(Ic.check, { dismiss(); c.onOk() }, bg = if (c.danger) Pal.danger else Pal.accent, tint = Pal.bg)
            }
        }
    }
}

// ---------------- helpers shared by screens ----------------

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

fun uriFor(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

fun openExternal(ctx: Context, f: File) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(ctx, f), Storage.mime(f.name))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try { ctx.startActivity(i) } catch (_: ActivityNotFoundException) { App.toast("No app on this device can open ${f.name}") }
    catch (_: Exception) { App.toast("Can't open ${f.name}") }
}

/** Android share sheet: Quick Share / Nearby Share, Bluetooth, messaging apps... */
fun shareSystem(ctx: Context, files: List<File>, uris: List<Uri> = emptyList()) {
    val all = files.filter { it.isFile }.map { uriFor(ctx, it) } + uris
    if (all.isEmpty()) { App.toast("Pick files (not folders) to share"); return }
    val i = if (all.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, all[0])
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(all))
    i.type = if (files.size == 1) Storage.mime(files[0].name) else "*/*"
    i.clipData = ClipData.newRawUri("", all[0]).also { cd -> all.drop(1).forEach { cd.addItem(ClipData.Item(it)) } }
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(Intent.createChooser(i, "Share via").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) { App.toast("No sharing apps available on this device") }
}

/**
 * Watch: swipe right from the left edge = Back (like the system gesture), on every screen.
 * Only gestures that start within 24 dp of the left edge are looked at; everything else
 * (lists, video seek…) works exactly as before.
 */
@Composable
private fun Modifier.edgeSwipeBack(onBack: () -> Unit): Modifier {
    val density = LocalDensity.current
    val edgePx = with(density) { 24.dp.toPx() }
    val triggerPx = with(density) { 48.dp.toPx() }
    return this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.x > edgePx) return@awaitEachGesture
            var dx = 0f
            var dy = 0f
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) break
                dx += ch.position.x - ch.previousPosition.x
                dy += ch.position.y - ch.previousPosition.y
                if (dx > triggerPx && dx > abs(dy) * 1.5f) {
                    ch.consume()
                    onBack()
                    // swallow the rest of this swipe so nothing underneath reacts to it
                    while (true) {
                        val rest = awaitPointerEvent(PointerEventPass.Initial)
                        rest.changes.forEach { it.consume() }
                        if (rest.changes.none { it.pressed }) break
                    }
                    break
                }
            }
        }
    }
}

/** Permission prompts shown once, on the first launch. */
object FirstRun {
    fun ask(act: Activity) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(act, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            try { ActivityCompat.requestPermissions(act, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11) } catch (_: Exception) {}
        }
        val pm = act.getSystemService(PowerManager::class.java)
        val exempt = try { pm?.isIgnoringBatteryOptimizations(act.packageName) == true } catch (_: Exception) { true }
        if (!exempt) {
            try {
                act.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + act.packageName)))
            } catch (_: Exception) {}
        }
    }
}
