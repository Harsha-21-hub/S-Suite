package com.sdrive.shared.ui

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdrive.shared.App
import com.sdrive.shared.Cat
import com.sdrive.shared.Discovery
import com.sdrive.shared.MediaIndex
import com.sdrive.shared.Platform
import com.sdrive.shared.Prefs
import com.sdrive.shared.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private class QuickCmd(val label: String, val cmd: String)

/** Runs a command in this device's own shell (as the app user). */
private suspend fun localShell(cmd: String, onStart: (Process) -> Unit, onOut: (String) -> Unit) = withContext(Dispatchers.IO) {
    val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
    onStart(p)
    try {
        val r = p.inputStream.bufferedReader()
        val buf = CharArray(4096)
        while (true) {
            val n = try { r.read(buf) } catch (_: Exception) { -1 }
            if (n < 0) break
            onOut(String(buf, 0, n))
        }
        p.waitFor()
    } finally {
        p.destroy()
    }
}

private fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

/**
 * Phone app terminal: the watch's shell over ADB (Wireless debugging), or this phone's own shell.
 * Quick actions grant the watch app its permissions, stop it sleeping, pre-compile it (smooth UI)
 * and install APKs on the watch.
 */
@Composable
fun TerminalScreen() {
    val ui = LocalUi.current
    val watch = LocalIsWatch.current
    val scope = rememberCoroutineScope()
    val adb = Platform.adb
    val pkg = App.ctx.packageName
    val noAdb = remember { MutableStateFlow<String?>(null) }
    val adbStatus by (adb?.status ?: noAdb).collectAsState()
    var target by remember { mutableStateOf(if (adb != null) "adb" else "local") }
    var out by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var proc by remember { mutableStateOf<Process?>(null) }
    var cwdLocal by remember { mutableStateOf("/sdcard") }
    var cwdAdb by remember { mutableStateOf("/sdcard") }
    val history = remember { mutableStateListOf<String>() }
    var histPos by remember { mutableIntStateOf(-1) }
    var pickApk by remember { mutableStateOf(false) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val scroll = rememberScrollState()
    val isAdb = target == "adb" && adb != null
    val busy = job?.isActive == true

    fun append(s: String) { main.post { out = (out + s).takeLast(120_000) } }

    LaunchedEffect(out) { withFrameNanos { }; scroll.scrollTo(scroll.maxValue) }
    LaunchedEffect(Unit) {
        if (out.isEmpty()) append(
            if (adb != null) "${App.NAME} terminal.\nWatch: Settings > Developer options > Wireless debugging ON.\n" +
                "First time: tap Pair. After that: Connect.\n"
            else "${App.NAME} terminal (this device's shell).\n"
        )
    }

    fun run(raw: String) {
        val cmd = raw.trim()
        if (cmd.isEmpty() || job?.isActive == true) return
        history.remove(cmd); history.add(cmd); histPos = -1
        val viaAdb = isAdb
        val cwd = if (viaAdb) cwdAdb else cwdLocal
        append("\n${if (viaAdb) "watch" else "phone"}:$cwd $ $cmd\n")
        val cdArg: String? = when {
            cmd == "cd" -> "/sdcard"
            cmd.startsWith("cd ") && cmd.none { it == ';' || it == '&' || it == '|' } -> cmd.removePrefix("cd ").trim()
            else -> null
        }
        val full = if (cdArg != null) "cd ${shQuote(cwd)} && cd $cdArg && pwd" else "cd ${shQuote(cwd)} 2>/dev/null; $cmd"
        job = scope.launch {
            val captured = StringBuilder()
            val sink: (String) -> Unit = { s -> if (cdArg != null) synchronized(captured) { captured.append(s) } else append(s) }
            try {
                if (viaAdb) {
                    val a = adb ?: return@launch
                    if (a.status.value == null) { append("Not connected to the watch. Tap Connect (or Pair first).\n"); return@launch }
                    a.shell(full, sink)
                } else {
                    localShell(full, { proc = it }, sink)
                }
                if (cdArg != null) {
                    val text = synchronized(captured) { captured.toString() }
                    val p = text.trim().lines().lastOrNull()?.trim().orEmpty()
                    if (p.startsWith("/")) { if (viaAdb) cwdAdb = p else cwdLocal = p } else append(text)
                }
            } catch (e: CancellationException) {
                append("^C\n"); throw e
            } catch (e: Exception) {
                append("Error: ${e.message ?: e.javaClass.simpleName}\n")
            } finally {
                proc = null
            }
        }
    }

    fun stop() { proc?.destroy(); job?.cancel() }

    fun guessHost(): String =
        Discovery.peers.value.firstOrNull { it.kind == "watch" }?.host
            ?: Prefs.lastAdb.substringBefore(':').ifEmpty { "192.168." }

    fun pair() {
        val a = adb ?: return
        ui.prompt = Prompt("Watch > Wireless debugging > Pair new device. Enter the IP:port shown there", guessHost() + ":") { hp ->
            val host = hp.substringBefore(':').trim()
            val port = hp.substringAfter(':', "").trim().toIntOrNull()
            if (host.isEmpty() || port == null) {
                append("Use the form IP:port, e.g. 192.168.1.23:37891\n")
            } else {
                ui.prompt = Prompt("6-digit pairing code shown on the watch", numeric = true) { code ->
                    append("Pairing with $host:$port…\n")
                    job = scope.launch {
                        try {
                            a.pair(host, port, code.trim())
                            append("Paired. Now tap Connect and enter the IP:port shown at the top of the Wireless debugging screen (not the pairing port).\n")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            append("Pairing failed: ${e.message ?: e.javaClass.simpleName}\n")
                        }
                    }
                }
            }
        }
    }

    fun connect() {
        val a = adb ?: return
        val pre = Prefs.lastAdb.ifEmpty { guessHost() + ":" }
        ui.prompt = Prompt("Watch > Wireless debugging: IP address & port", pre) { hp ->
            val host = hp.substringBefore(':').trim()
            val port = hp.substringAfter(':', "").trim().toIntOrNull()
            if (host.isEmpty() || port == null) {
                append("Use the form IP:port, e.g. 192.168.1.23:41235\n")
            } else {
                append("Connecting to $host:$port…\n")
                job = scope.launch {
                    try {
                        a.connect(host, port)
                        append("Connected to the watch.\n")
                        // Automatic setup: all-files access, notifications, never sleep, speed-compile.
                        append("Setting up ${App.NAME} on the watch…\n")
                        a.shell(
                            "appops set --uid $pkg MANAGE_EXTERNAL_STORAGE allow && echo 'OK all files access'; " +
                                "pm grant $pkg android.permission.POST_NOTIFICATIONS 2>/dev/null && echo 'OK notifications'; " +
                                "dumpsys deviceidle whitelist +$pkg >/dev/null 2>&1 && echo 'OK never sleep'; " +
                                "cmd package compile -m speed $pkg >/dev/null 2>&1 && echo 'OK compiled (smooth UI)'",
                            ::append,
                        )
                        append("Setup done.\n")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        append("Connect failed: ${e.message ?: e.javaClass.simpleName}\n")
                    }
                }
            }
        }
    }

    val adbCmds = listOf(
        QuickCmd("Grant all-files access", "appops set --uid $pkg MANAGE_EXTERNAL_STORAGE allow && echo OK: all files access granted"),
        QuickCmd("Never sleep", "dumpsys deviceidle whitelist +$pkg"),
        QuickCmd("Fix UI lag (compile)", "cmd package compile -m speed -f $pkg"),
        QuickCmd("Allow notifications", "pm grant $pkg android.permission.POST_NOTIFICATIONS && echo OK"),
        QuickCmd("Watch IP", "ip -f inet addr show wlan0"),
        QuickCmd("Storage", "df -h /sdcard"),
        QuickCmd("Battery", "dumpsys battery | head -n 12"),
        QuickCmd("Device info", "getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk"),
        QuickCmd("User apps", "pm list packages -3"),
    )
    val localCmds = listOf(
        QuickCmd("Who am I", "id"),
        QuickCmd("Storage", "df -h /sdcard"),
        QuickCmd("Wi-Fi IP", "ip -f inet addr show wlan0"),
        QuickCmd("Device info", "getprop ro.product.model; getprop ro.build.version.release"),
        QuickCmd("List storage", "ls -la /sdcard"),
    )

    Column(Modifier.fillMaxSize().background(Pal.bg)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = if (watch) 24.dp else 12.dp)) {
            Txt("TERMINAL", size = if (watch) 16.sp else 22.sp, font = DotFont)
            Txt(if (isAdb) (adbStatus?.let { "Watch · $it" } ?: "Watch · not connected") else "This device · app shell",
                size = 11.sp, color = Pal.dim)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (adb != null) Tag("Watch (ADB)", target == "adb", { target = "adb" })
                Tag("This device", target == "local", { target = "local" })
                Tag("Clear", false, { out = "" })
            }
            if (isAdb) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (adbStatus != null) Box(Modifier.size(8.dp).clip(CircleShape).background(Pal.red))
                    Tag("Pair", false, { pair() })
                    Tag(if (adbStatus == null) "Connect" else "Reconnect", adbStatus == null, { connect() })
                    if (adbStatus != null) Tag("Disconnect", false, { scope.launch { adb?.disconnect(); append("Disconnected.\n") } })
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(end = 8.dp)) {
                items(if (isAdb) adbCmds else localCmds, key = { it.label }) { q -> Tag(q.label, false, { run(q.cmd) }) }
                if (isAdb) item(key = "apk") { Tag("Install APK…", false, { pickApk = true }) }
                if (isAdb) item(key = "reboot") {
                    Tag("Reboot watch", false, {
                        ui.confirm = Confirm("Reboot the watch?", "The ADB connection will drop.", "Reboot", danger = true) { run("reboot") }
                    })
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ---- output ----
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(14.dp)).background(Pal.surface)
        ) {
            SelectionContainer {
                BasicText(
                    out,
                    Modifier.fillMaxSize().verticalScroll(scroll).padding(10.dp),
                    style = TextStyle(color = Pal.text, fontSize = if (watch) 9.sp else 12.sp, fontFamily = FontFamily.Monospace),
                )
            }
        }

        // ---- input ----
        Row(
            Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(50)).background(Pal.surface)
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt("$", color = Pal.red, size = 14.sp, font = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = input, onValueChange = { input = it }, singleLine = true,
                textStyle = TextStyle(color = Pal.text, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(Pal.red),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { run(input); input = "" }),
                modifier = Modifier.weight(1f),
            )
            RoundBtn(Ic.up, {
                if (history.isNotEmpty()) {
                    histPos = if (histPos <= 0) history.lastIndex else histPos - 1
                    input = history[histPos]
                }
            }, size = 36.dp, bg = Color.Transparent)
            if (busy) RoundBtn(Ic.close, { stop() }, size = 36.dp, bg = Pal.red, tint = Pal.text)
            else RoundBtn(Ic.send, { run(input); input = "" }, size = 36.dp, bg = Pal.accent, tint = Pal.bg)
        }
    }

    // ---- APK picker ----
    if (pickApk) {
        val apks = remember { MediaIndex.snap.value?.of(Cat.APPS)?.sortedByDescending { it.mtime }.orEmpty() }
        BackHandler { pickApk = false }
        Box(Modifier.fillMaxSize().background(Pal.bg)) {
            WList {
                item { Txt("Install on watch", size = 18.sp) }
                if (apks.isEmpty()) item {
                    Txt("No .apk files found on this phone. Put them in Download, then reopen S Drive.",
                        Modifier.padding(12.dp), size = 12.sp, color = Pal.dim, lines = 3, align = TextAlign.Center)
                }
                items(apks, key = { it.path }) { f ->
                    Chip(f.name, {
                        pickApk = false
                        val a = adb
                        if (a == null || adbStatus == null) {
                            append("Connect to the watch first.\n")
                        } else {
                            append("\nInstalling ${f.name} (${Storage.fmt(f.size)}) on the watch…\n")
                            job = scope.launch {
                                try { a.install(File(f.path), ::append) }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { append("Install failed: ${e.message}\n") }
                            }
                        }
                    }, icon = Ic.apps, sub = "${Storage.fmt(f.size)} · ${File(f.dir).name}")
                }
                item { Chip("Cancel", { pickApk = false }, icon = Ic.close) }
            }
        }
    }
}
