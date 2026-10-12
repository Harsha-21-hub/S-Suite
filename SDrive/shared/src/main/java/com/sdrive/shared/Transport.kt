package com.sdrive.shared

import android.content.Context
import android.os.PowerManager
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** HTTP client used to talk to another SDrive device's server (Wi-Fi / LAN). */
object PeerClient {
    class PinRequired : IOException("Wrong or missing PIN")

    private fun url(p: Discovery.Peer, path: String, q: Map<String, String>): URL {
        val qs = if (q.isEmpty()) "" else "?" + q.entries.joinToString("&") { it.key + "=" + WristServer.enc(it.value) }
        return URL("http://${p.host}:${p.port}$path$qs")
    }

    private fun open(p: Discovery.Peer, path: String, q: Map<String, String>, pin: String?): HttpURLConnection {
        val c = url(p, path, q).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 60_000
        c.useCaches = false
        c.setRequestProperty("X-Pin", pin ?: "")
        return c
    }

    fun get(p: Discovery.Peer, path: String, q: Map<String, String>, pin: String?): JSONObject {
        val c = open(p, path, q, pin)
        try {
            val code = c.responseCode
            if (code == 401) throw PinRequired()
            if (code !in 200..299) throw IOException("HTTP $code")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    fun upload(p: Discovery.Peer, pin: String?, s: Src, h: Transfers.Handle) {
        val c = open(p, "/api/upload", mapOf("name" to s.name, "sub" to s.sub), pin)
        c.requestMethod = "PUT"
        c.doOutput = true
        c.setFixedLengthStreamingMode(s.size)
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setRequestProperty("X-Via", WristServer.enc("Wi-Fi ← ${Prefs.deviceName}"))
        s.mtime?.let { c.setRequestProperty("X-Mtime", it.toString()) }
        try {
            c.outputStream.use { o -> s.open().use { i -> copyStream(i, o, h) } }
            val code = c.responseCode
            if (code == 401) throw PinRequired()
            if (code !in 200..299) {
                val msg = try { JSONObject(c.errorStream.bufferedReader().readText()).optString("error") } catch (_: Exception) { "" }
                throw IOException(msg.ifEmpty { "HTTP $code" })
            }
        } finally { c.disconnect() }
    }

    fun download(p: Discovery.Peer, pin: String?, remotePath: String, name: String, destDir: File, h: Transfers.Handle): File {
        val c = open(p, "/api/file", mapOf("path" to remotePath, "dl" to "1"), pin)
        try {
            val code = c.responseCode
            if (code == 401) throw PinRequired()
            if (code !in 200..299) throw IOException("HTTP $code")
            destDir.mkdirs()
            val dest = Storage.unique(destDir, Storage.safeName(name))
            val part = File(destDir, ".${dest.name}.wdpart")
            try {
                BufferedInputStream(c.inputStream, 64 * 1024).use { i -> FileOutputStream(part).use { o -> copyStream(i, o, h) } }
                if (!part.renameTo(dest)) throw IOException("Rename failed")
            } catch (e: Exception) { part.delete(); throw e }
            return dest
        } finally { c.disconnect() }
    }
}

/**
 * Bluetooth link between the watch and its paired phone, using the Wear OS Data Layer
 * (ChannelClient). Works even when Wi-Fi is off; Google Play services automatically uses
 * Wi-Fi for the link when both devices can reach each other on it.
 */
object WearBridge {
    const val PREFIX = "/sdrive/file/"

    suspend fun nodes(ctx: Context): List<Node> = try {
        Wearable.getNodeClient(ctx.applicationContext).connectedNodes.await()
    } catch (_: Exception) { emptyList() }

    suspend fun send(ctx: Context, nodeId: String, s: Src, h: Transfers.Handle) {
        val cc = Wearable.getChannelClient(ctx.applicationContext)
        val path = PREFIX + s.size + "/" + WristServer.enc(s.sub) + "/" + WristServer.enc(s.name)
        val ch = cc.openChannel(nodeId, path).await()
        try {
            val out = cc.getOutputStream(ch).await()
            withContext(Dispatchers.IO) {
                BufferedOutputStream(out, 64 * 1024).use { o -> s.open().use { i -> copyStream(i, o, h) } }
            }
            // Wait for the receiver's 1-byte "saved OK" acknowledgement.
            val ack = withTimeoutOrNull(120_000) {
                withContext(Dispatchers.IO) { cc.getInputStream(ch).await().use { it.read() } }
            }
            if (ack != 1) throw IOException("Receiver didn't confirm (is ${App.NAME} installed on the other device?)")
        } finally {
            try { cc.close(ch).await() } catch (_: Exception) {}
        }
    }
}

/** Receives files sent over the Bluetooth / Data Layer link. Started by Play services on demand. */
class DataLayerReceiver : WearableListenerService() {
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (!channel.path.startsWith(WearBridge.PREFIX)) return
        val parts = channel.path.removePrefix(WearBridge.PREFIX).split("/")
        if (parts.size < 3) return
        val size = parts[0].toLongOrNull() ?: -1L
        val sub = WristServer.dec(parts[1])
        val name = Storage.safeName(WristServer.dec(parts[2]))
        val cc = Wearable.getChannelClient(this)
        if (!TransferService.enabled.value) { // Transfer switched OFF on this device
            try { Tasks.await(cc.close(channel)) } catch (_: Exception) {}
            return
        }
        // Foreground service while receiving, so the system doesn't pause us mid-transfer.
        TransferService.keepAlive(applicationContext)
        val wl = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SDrive:bt").apply { acquire(3 * 60 * 60 * 1000L) }
        val dir = Storage.receiveSub(sub)
        val dest = Storage.unique(dir, name)
        val part = File(dir, ".${dest.name}.wdpart")
        val h = Transfers.begin(dest.name, true, "Bluetooth", size)
        try {
            val ins = Tasks.await(cc.getInputStream(channel))
            BufferedInputStream(ins, 64 * 1024).use { i -> FileOutputStream(part).use { o -> copyStream(i, o, h) } }
            if (size >= 0 && part.length() != size) throw IOException("Incomplete (${part.length()} of $size bytes)")
            if (!part.renameTo(dest)) throw IOException("Rename failed")
            h.finish(dest.path)
            Storage.scan(dest)
            try { Tasks.await(cc.getOutputStream(channel)).use { it.write(1); it.flush() } } catch (_: Exception) {}
        } catch (e: Exception) {
            part.delete()
            h.fail(e)
            try { Tasks.await(cc.close(channel)) } catch (_: Exception) {}
        } finally {
            if (wl.isHeld) wl.release()
        }
    }
}

/** Queues and runs outgoing transfers (2 in parallel for max throughput without starving the CPU). */
object Sender {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    private val shrinkLock = Mutex()

    fun toPeer(ctx: Context, peer: Discovery.Peer, pin: String?, items: List<Src>, shrink: Boolean, onBadPin: () -> Unit) {
        if (TransferService.offToast()) return
        val app = ctx.applicationContext
        TransferService.keepAlive(app)
        scope.launch {
            try {
                PeerClient.get(peer, "/api/info", emptyMap(), pin)
            } catch (e: PeerClient.PinRequired) {
                Prefs.setPeerPin(peer.host, null)
                withContext(Dispatchers.Main) { onBadPin() }
                return@launch
            } catch (e: Exception) {
                App.toast("Can't reach ${peer.name}. Is its Transfer screen open?")
                return@launch
            }
            items.forEach { s -> enqueue(app, s, "Wi-Fi → ${peer.name}", shrink) { src, h -> PeerClient.upload(peer, pin, src, h) } }
        }
    }

    fun toNode(ctx: Context, node: Node, items: List<Src>, shrink: Boolean) {
        if (TransferService.offToast()) return
        val app = ctx.applicationContext
        TransferService.keepAlive(app)
        items.forEach { s -> enqueue(app, s, "Bluetooth → ${node.displayName}", shrink) { src, h -> WearBridge.send(app, node.id, src, h) } }
    }

    fun pull(ctx: Context, peer: Discovery.Peer, pin: String?, remotePath: String, name: String, size: Long) {
        if (TransferService.offToast()) return
        TransferService.keepAlive(ctx.applicationContext)
        val h = Transfers.begin(name, true, "Wi-Fi ← ${peer.name}", size, queued = true)
        scope.launch {
            try {
                gate.withPermit {
                    h.start()
                    val f = PeerClient.download(peer, pin, remotePath, name, Storage.receiveDir(), h)
                    h.finish(f.path)
                    Storage.scan(f)
                }
            } catch (e: Throwable) { h.fail(e) }
        }
    }

    private fun enqueue(ctx: Context, s: Src, via: String, shrink: Boolean, block: suspend (Src, Transfers.Handle) -> Unit) {
        val h = Transfers.begin(s.name, false, via, s.size, queued = true)
        scope.launch {
            val temps = ArrayList<File>()
            try {
                var src = s
                val opt = Platform.optimizer
                if (shrink && opt != null && Storage.kindOf(s.name) == Kind.VIDEO) {
                    val f = shrinkLock.withLock { opt.optimize(ctx, s, h) }
                    temps += f
                    src = Src.ofFile(f, s.sub)
                    h.retarget(f.name, f.length())
                }
                if (src.size < 0) { // unknown size (some content providers) -> stage to cache first
                    val tmp = File(ctx.cacheDir, "stage_${h.id}_${src.name}")
                    src.open().use { i -> FileOutputStream(tmp).use { o -> copyStream(i, o, null) } }
                    temps += tmp
                    src = Src(src.name, tmp.length(), src.sub, src.mtime, tmp, null) { tmp.inputStream() }
                    h.retarget(src.name, src.size)
                }
                gate.withPermit {
                    h.start()
                    block(src, h)
                }
                h.finish()
            } catch (e: Throwable) {
                h.fail(e)
            } finally {
                temps.forEach { it.delete() }
            }
        }
    }
}