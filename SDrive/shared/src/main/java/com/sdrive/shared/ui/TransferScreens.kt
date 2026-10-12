package com.sdrive.shared.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.Node
import com.sdrive.shared.App
import com.sdrive.shared.AppState
import com.sdrive.shared.Discovery
import com.sdrive.shared.Kind
import com.sdrive.shared.Net
import com.sdrive.shared.NetworkKeeper
import com.sdrive.shared.Outbox
import com.sdrive.shared.PeerClient
import com.sdrive.shared.Platform
import com.sdrive.shared.Prefs
import com.sdrive.shared.Sender
import com.sdrive.shared.Src
import com.sdrive.shared.Storage
import com.sdrive.shared.TState
import com.sdrive.shared.Transfer
import com.sdrive.shared.TransferService
import com.sdrive.shared.Transfers
import com.sdrive.shared.WearBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

// ======================= TRANSFER HUB =======================

@Composable
fun TransferScreen() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val ui = LocalUi.current
    val browser = LocalBrowser.current
    val watch = LocalIsWatch.current
    val on by TransferService.serverOn.collectAsState()
    val enabledOn by TransferService.enabled.collectAsState()
    val srvErr by TransferService.serverError.collectAsState()
    val wifi by NetworkKeeper.wifiUp.collectAsState()
    val peers by Discovery.peers.collectAsState()
    val transfers by Transfers.list.collectAsState()
    val outbox by Outbox.items.collectAsState()
    var nodes by remember { mutableStateOf<List<Node>>(emptyList()) }
    var pin by remember { mutableStateOf(Prefs.pin) }
    var name by remember { mutableStateOf(Prefs.deviceName) }
    var keepAwake by remember { mutableStateOf(Prefs.keepAwake) }
    var mode by remember { mutableIntStateOf(0) } // 0 = Receive, 1 = Send
    val tick = AppState.resumeTick
    val unrestricted = remember(tick) {
        try { ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true } catch (_: Exception) { false }
    }

    fun askBattery() {
        val pkg = ctx.packageName
        val tries = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
        for (i in tries) {
            try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        }
        ui.confirm = Confirm(
            "Set it from phone or PC",
            "This device has no settings page for it. Phone app → Terminal → Never sleep, or on PC:\nadb shell \"dumpsys deviceidle whitelist +$pkg\"",
            "OK",
        ) {}
    }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        TransferService.startServer(ctx)
        nodes = WearBridge.nodes(ctx)
    }
    DisposableEffect(Unit) { Discovery.start(); onDispose { Discovery.stop() } }
    val ip by produceState(Net.localIp(), wifi, on) { while (true) { value = Net.localIp(); delay(4000) } }

    WList {
        item { Txt("TRANSFER", size = if (watch) 16.sp else 22.sp, font = DotFont) }
        // ---- master switch (same switch on the PC page) ----
        item {
            Chip(if (enabledOn) "Transfer: ON" else "Transfer: OFF", { TransferService.setEnabled(ctx, !enabledOn) },
                icon = Ic.wifi, iconTint = if (enabledOn) Pal.accent else Pal.red, selected = enabledOn,
                sub = if (enabledOn) "Tap to turn off" else "Tap to turn on · nothing is sent or received")
        }
        if (enabledOn) item {
            Row(Modifier.curved(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag("Receive", mode == 0, { mode = 0 })
                Tag("Send", mode == 1, { mode = 1 })
            }
        }

        // ---- RECEIVE: this device's receive status (PC browser link + PIN) ----
        if (enabledOn && mode == 0) item {
            Column(
                Modifier.curved().fillMaxWidth().clip(RoundedCornerShape(if (watch) 26.dp else 16.dp))
                    .background(if (on) Pal.accentDim else Pal.surface).padding(if (watch) 10.dp else 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ico(Ic.wifi, if (wifi || ip != null) Pal.accent else Pal.warn, 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Txt(if (on) "Receiving · Wi-Fi ${if (wifi || ip != null) "on" else "connecting…"}" else "Receiving is off",
                        size = 12.sp, color = if (on) Pal.accent else Pal.dim, weight = FontWeight.Bold)
                }
                if (on && ip != null) {
                    Spacer(Modifier.height(4.dp))
                    Txt("http://$ip:${TransferService.server.port}", size = if (watch) 13.sp else 18.sp, weight = FontWeight.Bold, align = TextAlign.Center)
                    Txt("PIN $pin", size = if (watch) 18.sp else 24.sp, color = Pal.accent, font = DotFont)
                    Txt("Open the link in any PC/phone browser on the same Wi-Fi", size = 10.sp, color = Pal.dim, lines = 2, align = TextAlign.Center)
                }
                srvErr?.let { Txt(it, size = 11.sp, color = Pal.danger, lines = 2) }
                Spacer(Modifier.height(6.dp))
                Tag(if (on) "Stop receiving" else "Start receiving", on, {
                    if (on) TransferService.stopServer(ctx) else TransferService.startServer(ctx)
                })
            }
        }
        if (enabledOn && !wifi && ip == null) item {
            Chip("Wi-Fi settings", {
                try { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }, icon = Ic.wifi, iconTint = Pal.warn, sub = "Connect to the same Wi-Fi as your phone/PC")
        }

        if (!enabledOn) {
            // switched OFF: only the switch, help and settings below
        } else if (mode == 0) {
            // ---- RECEIVE: get files from other devices ----
            item { SectionTitle("Devices on Wi-Fi") }
            if (peers.isEmpty()) item {
                Txt("Searching… open S Drive → Transfer on your other device", Modifier.curved(), size = 11.sp, color = Pal.dim, lines = 3, align = TextAlign.Center)
            }
            peers.forEach { p ->
                item(key = "peer-${p.host}-${p.port}") {
                    Chip(p.name, { nav.go(Screen.Remote(p)) }, icon = peerIcon(p.kind), sub = "${p.host} · browse & download")
                }
            }
            item {
                Chip("Add device by IP", {
                    ui.prompt = Prompt("IP address (e.g. 192.168.1.20 or 192.168.1.20:8080)") { s ->
                        val host = s.substringBefore(':').trim()
                        val port = s.substringAfter(':', "8080").trim().toIntOrNull() ?: 8080
                        if (host.isNotEmpty()) Discovery.addManual(host, port)
                    }
                }, icon = Ic.add)
            }
            if (nodes.isNotEmpty()) {
                item { SectionTitle("Bluetooth link") }
                nodes.forEach { n ->
                    item(key = "node-${n.id}") {
                        Chip(n.displayName, { App.toast("On ${n.displayName}: Transfer → Send") },
                            icon = Ic.bluetooth, sub = if (n.isNearby) "Connected · works without Wi-Fi" else "Connected via cloud")
                    }
                }
            }
        } else {
            // ---- SEND: pick files, then choose phone / watch / PC / Bluetooth ----
            item {
                Chip("Choose files to send", { browser.pickHint = true; nav.home() }, icon = Ic.send,
                    sub = "Long-press files, then tap Send")
            }
            item {
                Txt("Then pick where to: phone or watch on Wi-Fi, Bluetooth, or PC browser.",
                    Modifier.curved(), size = 11.sp, color = Pal.dim, lines = 3, align = TextAlign.Center)
            }
            if (outbox.isNotEmpty()) {
                item { SectionTitle("Waiting for PC browser") }
                item { Chip("${outbox.size} file(s) ready", { Outbox.clear() }, icon = Ic.computer, sub = "They download automatically when the page is open. Tap to clear.") }
            }
        }

        // ---- transfers ----
        if (transfers.isNotEmpty()) {
            item { SectionTitle("Transfers") }
            transfers.forEach { t ->
                item(key = "t-${t.id}") {
                    TransferRow(t) {
                        t.savedPath?.let { p -> File(p).parentFile?.let { d -> browser.dir = d; nav.home() } }
                    }
                }
            }
            item {
                Row(Modifier.curved()) {
                    if (transfers.any { it.state == TState.RUNNING || it.state == TState.QUEUED })
                        Tag("Cancel all", false, { Transfers.cancelAll() })
                    Spacer(Modifier.width(6.dp))
                    Tag("Clear finished", false, { Transfers.clearFinished() })
                }
            }
        }

        item { Chip("How to transfer", { nav.go(Screen.Help) }, icon = Ic.help, sub = "PC / phone ↔ watch · speeds") }

        // ---- stay awake during transfers ----
        item { SectionTitle("Stay awake") }
        item {
            Chip("Keep screen on while transferring", { keepAwake = !keepAwake; Prefs.keepAwake = keepAwake },
                icon = Ic.lock, selected = keepAwake, sub = if (keepAwake) "On · no sleep during transfers" else "Off · saves battery")
        }
        item {
            Chip(if (unrestricted) "Battery: unrestricted" else "Allow background running", { askBattery() },
                icon = Ic.battery, iconTint = if (unrestricted) Pal.accent else Pal.warn,
                sub = if (unrestricted) "Android won't pause transfers" else "Stops Android pausing transfers")
        }

        // ---- settings ----
        item { SectionTitle("This device") }
        item {
            Chip(name, {
                ui.prompt = Prompt("Device name", name) { n -> Prefs.deviceName = n; name = Prefs.deviceName; App.toast("Restart receiving to apply") }
            }, icon = if (watch) Ic.watch else Ic.phone, sub = "Name others see")
        }
        item {
            Chip("New PIN", {
                ui.confirm = Confirm("Change PIN?", "Other devices will have to enter the new PIN.", "Change") { pin = Prefs.regenPin() }
            }, icon = Ic.lock, sub = "Current: $pin")
        }
    }
}

private fun peerIcon(kind: String) = when (kind) { "watch" -> Ic.watch; "phone" -> Ic.phone; else -> Ic.computer }

@Composable
private fun TransferRow(t: Transfer, onOpen: () -> Unit) {
    val watch = LocalIsWatch.current
    val running = t.state == TState.RUNNING
    Column(
        Modifier.curved().fillMaxWidth().clip(RoundedCornerShape(if (watch) 20.dp else 14.dp)).background(Pal.surface)
            .clickable(enabled = t.state == TState.DONE && t.savedPath != null, onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ico(if (t.incoming) Ic.down else Ic.up, if (t.incoming) Pal.accent else Pal.video, 16.dp)
            Spacer(Modifier.width(6.dp))
            Txt(t.name, Modifier.weight(1f), size = 12.sp, weight = FontWeight.Medium)
            if (running || t.state == TState.QUEUED) RoundBtn(Ic.close, { Transfers.cancel(t.id) }, size = 24.dp)
        }
        val status = when (t.state) {
            TState.QUEUED -> t.note ?: "Waiting…"
            TState.RUNNING -> t.note ?: "${Storage.fmt(t.done)} / ${Storage.fmt(t.total)} · ${Storage.fmt(t.bps)}/s"
            TState.DONE -> "Done · ${Storage.fmt(t.total)}" + if (t.incoming && t.savedPath != null) " · tap to open folder" else ""
            TState.FAILED -> "Failed: ${t.error}"
            TState.CANCELLED -> "Cancelled"
        }
        Txt(status, size = 10.sp, color = if (t.state == TState.FAILED) Pal.danger else Pal.dim, lines = 2)
        Txt(t.via, size = 10.sp, color = Pal.dim)
        if (running && t.total > 0) { Spacer(Modifier.height(4.dp)); Bar(t.done.toFloat() / t.total) }
    }
}

// ======================= SEND =======================

@Composable
fun SendScreen(files: List<File>, uris: List<Uri>) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val ui = LocalUi.current
    val watch = LocalIsWatch.current
    val peers by Discovery.peers.collectAsState()
    var nodes by remember { mutableStateOf<List<Node>>(emptyList()) }
    var shrink by remember { mutableStateOf(false) }
    val srcs by produceState<List<Src>?>(null, files, uris) {
        value = withContext(Dispatchers.IO) { Src.fromFiles(files) + Src.fromUris(ctx, uris) }
    }
    LaunchedEffect(Unit) { nodes = WearBridge.nodes(ctx); TransferService.keepAlive(ctx) }
    DisposableEffect(Unit) { Discovery.start(); onDispose { Discovery.stop() } }

    val list = srcs
    val total = list?.sumOf { it.size.coerceAtLeast(0) } ?: 0L
    val hasVideo = list?.any { Storage.kindOf(it.name) == Kind.VIDEO } == true

    fun done() { App.toast("Sending ${list?.size ?: 0} file(s)…"); nav.replace(Screen.Transfer) }

    fun sendTo(p: Discovery.Peer) {
        if (TransferService.offToast()) return
        val l = list ?: return
        val saved = Prefs.peerPin(p.host)
        fun go(pin: String) {
            Prefs.setPeerPin(p.host, pin)
            Sender.toPeer(ctx, p, pin, l, shrink) {
                ui.prompt = Prompt("Wrong PIN. Enter the PIN shown on ${p.name}", numeric = true) { go(it) }
            }
            done()
        }
        if (saved != null) go(saved)
        else ui.prompt = Prompt("Enter the PIN shown on ${p.name} (Transfer screen)", numeric = true) { go(it) }
    }

    val enabledOn by TransferService.enabled.collectAsState()

    WList {
        item { Txt("SEND", size = if (watch) 16.sp else 22.sp, font = DotFont) }
        if (!enabledOn) item {
            Chip("Transfer is OFF", { TransferService.setEnabled(ctx, true) }, icon = Ic.wifi, iconTint = Pal.red,
                sub = "Tap to turn it on")
        }
        item {
            Txt(if (list == null) "Preparing…" else "${list.size} file(s) · ${Storage.fmt(total)}",
                size = 12.sp, color = Pal.dim, align = TextAlign.Center)
        }
        if (Platform.optimizer != null && hasVideo) item {
            Chip(if (shrink) "Shrink videos for watch: ON" else "Shrink videos for watch: OFF", { shrink = !shrink },
                icon = Ic.movie, iconTint = Pal.video, selected = shrink,
                sub = "Re-encode to 480p H.264 → smooth on the watch & 5-10× smaller")
        }

        item { SectionTitle("Wi-Fi · fastest") }
        if (peers.isEmpty()) item {
            Txt("No devices yet. On the other device open S Drive → Transfer (same Wi-Fi).", Modifier.curved(),
                size = 11.sp, color = Pal.dim, lines = 3, align = TextAlign.Center)
        }
        peers.forEach { p ->
            item(key = "sp-${p.host}-${p.port}") {
                Chip(p.name, { sendTo(p) }, icon = peerIcon(p.kind), sub = p.host)
            }
        }
        item {
            Chip("Add device by IP", {
                ui.prompt = Prompt("IP address of the other device") { s ->
                    val host = s.substringBefore(':').trim()
                    val port = s.substringAfter(':', "8080").trim().toIntOrNull() ?: 8080
                    if (host.isNotEmpty()) Discovery.addManual(host, port)
                }
            }, icon = Ic.add)
        }

        if (nodes.isNotEmpty()) {
            item { SectionTitle("Bluetooth · no Wi-Fi needed") }
            nodes.forEach { n ->
                item(key = "sn-${n.id}") {
                    Chip(n.displayName, {
                        if (!TransferService.offToast()) list?.let { Sender.toNode(ctx, n, it, shrink); done() }
                    }, icon = Ic.bluetooth, sub = "Slower (≈100-300 KB/s) but always available")
                }
            }
        }

        if (files.isNotEmpty()) {
            item { SectionTitle("PC (browser)") }
            item {
                Chip("Offer to PC browser", {
                    if (!TransferService.offToast()) { Outbox.add(files); TransferService.startServer(ctx); nav.replace(Screen.Transfer) }
                }, icon = Ic.computer, sub = "Open the link shown on Transfer screen on your PC")
            }
        }

        item { SectionTitle("Other apps") }
        item {
            Chip("Quick Share / Nearby / other apps", { shareSystem(ctx, files, uris) }, icon = Ic.share,
                sub = "Android share sheet")
        }
    }
}

// ======================= REMOTE BROWSER (pull files from another device) =======================

private data class RemoteEntry(val name: String, val dir: Boolean, val size: Long)

@Composable
fun RemoteScreen(peer: Discovery.Peer) {
    val ctx = LocalContext.current
    val ui = LocalUi.current
    val watch = LocalIsWatch.current
    var path by remember { mutableStateOf("/") }
    var pin by remember { mutableStateOf(Prefs.peerPin(peer.host)) }
    var entries by remember { mutableStateOf<List<RemoteEntry>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var reload by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    fun askPin(msg: String) {
        ui.prompt = Prompt(msg, numeric = true) { p -> Prefs.setPeerPin(peer.host, p); pin = p }
    }

    LaunchedEffect(path, pin, reload) {
        entries = null; err = null
        try {
            val o = withContext(Dispatchers.IO) { PeerClient.get(peer, "/api/list", mapOf("path" to path), pin) }
            val arr = o.getJSONArray("entries")
            entries = (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                RemoteEntry(e.getString("name"), e.getBoolean("dir"), e.optLong("size"))
            }
        } catch (e: PeerClient.PinRequired) {
            err = "PIN needed"
            askPin(if (pin == null) "Enter the PIN shown on ${peer.name}" else "Wrong PIN for ${peer.name}")
        } catch (e: Exception) {
            err = "Can't reach ${peer.name}. Open its Transfer screen and make sure both are on the same Wi-Fi."
        }
    }
    BackHandler(enabled = path != "/") { path = path.substringBeforeLast('/').ifEmpty { "/" } }

    fun child(n: String) = if (path == "/") "/$n" else "$path/$n"

    WList {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(peer.name, size = if (watch) 14.sp else 20.sp, weight = FontWeight.Bold)
                Txt(path, size = 11.sp, color = Pal.dim)
            }
        }
        err?.let { e ->
            item { Txt(e, Modifier.curved(), size = 12.sp, color = Pal.danger, lines = 4, align = TextAlign.Center) }
            item { Chip("Retry", { reload++ }, icon = Ic.repeat) }
            if (e == "PIN needed") item { Chip("Enter PIN", { askPin("Enter the PIN shown on ${peer.name}") }, icon = Ic.lock) }
        }
        if (entries == null && err == null) item { Txt("Loading…", color = Pal.dim) }
        entries?.let { list ->
            if (list.isEmpty()) item { Txt("Empty folder", color = Pal.dim) }
            list.forEach { e ->
                item(key = "r-$path/${e.name}") {
                    val k = if (e.dir) Kind.DIR else Storage.kindOf(e.name)
                    Chip(e.name, {
                        if (e.dir) path = child(e.name)
                        else if (!TransferService.offToast()) {
                            Sender.pull(ctx, peer, pin, child(e.name), e.name, e.size)
                            App.toast("Downloading ${e.name}")
                        }
                    }, icon = kindIcon(k), iconTint = kindColor(k), sub = if (e.dir) "Folder" else "${Storage.fmt(e.size)} · tap to download")
                }
            }
        }
    }
}

// ======================= IMAGE VIEWER =======================

@Composable
fun ImageScreen(file: File) {
    val maxPx = LocalConfiguration.current.screenWidthDp * 3
    val bmp by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            try {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, b)
                var s = 1
                while (b.outWidth / s > maxPx * 2 || b.outHeight / s > maxPx * 2) s *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
            } catch (_: Throwable) { null }
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTransformGestures { _, p, z, _ ->
                scale = (scale * z).coerceIn(1f, 6f); off = if (scale == 1f) Offset.Zero else off + p
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b == null) Txt("Loading…", color = Pal.dim)
        else Image(b, file.name, Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale; scaleY = scale; translationX = off.x; translationY = off.y
        }, contentScale = ContentScale.Fit)
        Txt(file.name, Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp, start = 30.dp, end = 30.dp), size = 11.sp, color = Pal.dim, align = TextAlign.Center)
    }
}
