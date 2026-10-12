package com.sdrive.shared

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Tiny, dependency-free HTTP/1.1 file server (a few KB of code, ~0 RAM when idle).
 * Serves the PC/phone web UI and the REST API used by other SDrive apps.
 * Every /api call except /api/ping needs the device PIN (header X-Pin or ?pin=).
 */
class WristServer {
    @Volatile private var ss: ServerSocket? = null
    @Volatile var port = 0
        private set
    val running: Boolean get() = ss?.isClosed == false

    private val pool = ThreadPoolExecutor(2, 16, 30, TimeUnit.SECONDS, SynchronousQueue()).apply {
        allowCoreThreadTimeOut(true)
    }
    private var badPins = 0
    private var lockedUntil = 0L
    /** PCs/phones that entered the right PIN recently (by IP): the S Drive PC helper on that PC needs no PIN. */
    private val trusted = HashMap<String, Long>()
    /** Last request from the S Drive PC helper (S-Drive-PC.bat), and when the PC page pressed Stop. */
    @Volatile private var lastPcAgent = 0L
    @Volatile private var pcStopAt = 0L
    private var indexHtml: ByteArray? = null

    @Synchronized
    fun start(): Int {
        ss?.let { if (!it.isClosed) return port }
        var bound: ServerSocket? = null
        for (p in 8080..8095) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.receiveBufferSize = 1 shl 20
                s.bind(InetSocketAddress(p), 32)
                bound = s; port = p; break
            } catch (_: IOException) {}
        }
        val s = bound ?: throw IOException("No free port")
        ss = s
        pcStopAt = 0L
        thread(name = "wd-accept", isDaemon = true) {
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    try { pool.execute { handle(c) } } catch (_: RejectedExecutionException) { c.close() }
                } catch (_: IOException) {
                    if (s.isClosed) break
                }
            }
        }
        return port
    }

    @Synchronized
    fun stop() {
        try { ss?.close() } catch (_: IOException) {}
        ss = null
    }

    private class Req(
        val method: String, val path: String, val q: Map<String, String>,
        val h: Map<String, String>, val ins: InputStream, val out: OutputStream, val ip: String,
    )

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                s.tcpNoDelay = true
                s.soTimeout = 60_000
                s.sendBufferSize = 1 shl 20
                val ins = BufferedInputStream(s.getInputStream(), 64 * 1024)
                val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
                val line = readLine(ins) ?: return
                val parts = line.split(' ')
                if (parts.size < 2) return
                val headers = HashMap<String, String>()
                while (true) {
                    val l = readLine(ins) ?: return
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val target = parts[1]
                val qi = target.indexOf('?')
                val path = dec(if (qi >= 0) target.substring(0, qi) else target)
                val q = if (qi >= 0) parseQuery(target.substring(qi + 1)) else emptyMap()
                // The PC helper polls quietly: it must not keep an idle server alive by itself.
                if (headers["x-sd-agent"] == "pc") lastPcAgent = SystemClock.elapsedRealtime() else Transfers.touch()
                val ip = s.inetAddress?.hostAddress ?: ""
                route(Req(parts[0].uppercase(), path, q, headers, ins, out, ip))
                out.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun route(r: Req) {
        when {
            r.path == "/" || r.path == "/index.html" -> index(r)
            r.path == "/api/ping" -> json(r, 200, JSONObject().put("app", "sdrive").put("name", Prefs.deviceName).put("kind", kind()))
            !r.path.startsWith("/api/") -> text(r, 404, "Not found")
            !authorized(r) -> json(r, 401, JSONObject().put("error", "pin"))
            r.path == "/api/info" -> info(r)
            r.path == "/api/transfer" -> transferSwitch(r)
            r.path == "/api/pc/stop" -> pcStop(r)
            r.path == "/api/outbox" -> outbox(r) // also while OFF, so the PC helper still hears "stop"
            !TransferService.enabled.value -> json(r, 403, err("Transfer is OFF on ${Prefs.deviceName}").put("off", true))
            else -> when (r.path) {
                "/api/list" -> list(r)
                "/api/file" -> file(r)
                "/api/upload" -> upload(r)
                "/api/mkdir" -> mkdir(r)
                "/api/delete" -> delete(r)
                "/api/rename" -> rename(r)
                "/api/outbox/file" -> outboxFile(r)
                "/api/outbox/add" -> outboxAdd(r)
                "/api/categories" -> categories(r)
                "/api/catfolders" -> catFolders(r)
                "/api/catfiles" -> catFiles(r)
                else -> text(r, 404, "Not found")
            }
        }
    }

    private fun kind() = if (App.isWatch) "watch" else "phone"

    @Synchronized
    private fun authorized(r: Req): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now < lockedUntil) return false
        val pin = r.h["x-pin"] ?: r.q["pin"]
        if (pin == Prefs.pin) { badPins = 0; if (r.ip.isNotEmpty()) trusted[r.ip] = now; return true }
        if (pin.isNullOrEmpty()) {
            val t = trusted[r.ip]
            if (t != null && now - t < TRUST_MS) return true
        } else {
            badPins++
            if (badPins >= 8) { lockedUntil = now + 60_000; badPins = 0 }
        }
        return false
    }

    // ---------- endpoints ----------

    private fun index(r: Req) {
        val b = indexHtml ?: App.ctx.assets.open("web/index.html").use { it.readBytes() }.also { indexHtml = it }
        send(r, 200, "text/html; charset=utf-8", b)
    }

    private fun info(r: Req) {
        val root = Storage.root()
        json(r, 200, JSONObject()
            .put("name", Prefs.deviceName).put("kind", kind())
            .put("free", root.usableSpace).put("total", root.totalSpace)
            .put("allFiles", Storage.hasAllFiles())
            .put("receive", Storage.rel(Storage.receiveDir()))
            .put("on", TransferService.enabled.value)
            .put("pc", pcActive()))
    }

    private fun pcActive() = SystemClock.elapsedRealtime() - lastPcAgent < 8_000

    /** GET = state; POST ?on=1/0 = switch Transfer ON/OFF from the PC page. */
    private fun transferSwitch(r: Req) {
        if (r.method == "POST") TransferService.setEnabledRemote(r.q["on"] == "1")
        json(r, 200, JSONObject().put("on", TransferService.enabled.value))
    }

    /** PC page "Stop": tells the PC helper to exit, then closes this port (re-run S-Drive-PC.bat to start again). */
    private fun pcStop(r: Req) {
        if (r.method != "POST") return json(r, 405, err("Use POST"))
        pcStopAt = SystemClock.elapsedRealtime()
        json(r, 200, JSONObject().put("ok", true))
        val helper = pcActive()
        thread(name = "wd-pcstop", isDaemon = true) {
            // Give the helper one poll (every 2 s) to see the stop, then close the port.
            try { Thread.sleep(if (helper) 4_500 else 800) } catch (_: InterruptedException) {}
            TransferService.closePort()
        }
    }

    private fun list(r: Req) {
        val dir = Storage.resolve(r.q["path"])
        if (dir == null || !dir.isDirectory) return json(r, 404, err("Folder not found"))
        val hidden = r.q["hidden"] == "1"
        val arr = JSONArray()
        dir.listFiles().orEmpty()
            .filter { hidden || !it.name.startsWith(".") }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .forEach { f ->
                arr.put(JSONObject().put("name", f.name).put("dir", f.isDirectory)
                    .put("size", if (f.isFile) f.length() else 0L).put("mtime", f.lastModified()))
            }
        json(r, 200, JSONObject().put("path", Storage.rel(dir)).put("entries", arr))
    }

    private fun file(r: Req) {
        val f = Storage.resolve(r.q["path"])
        if (f == null || !f.isFile) return json(r, 404, err("File not found"))
        sendFile(r, f, attachment = r.q["dl"] == "1", track = r.q["dl"] == "1")
    }

    private fun upload(r: Req) {
        if (r.method != "PUT" && r.method != "POST") return json(r, 405, err("Use PUT"))
        val name = Storage.safeName(r.q["name"] ?: return json(r, 400, err("name missing")))
        val len = r.h["content-length"]?.toLongOrNull() ?: return json(r, 411, err("Content-Length required"))
        val dir: File = if (r.q["dir"] != null) {
            Storage.resolve(r.q["dir"]) ?: return json(r, 400, err("Bad folder"))
        } else Storage.receiveSub(r.q["sub"])
        dir.mkdirs()
        if (dir.usableSpace < len + 2_000_000) return json(r, 507, err("Not enough space on ${Prefs.deviceName}"))
        if (r.h["expect"].equals("100-continue", ignoreCase = true)) {
            r.out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); r.out.flush()
        }
        val dest = Storage.unique(dir, name)
        val part = File(dir, ".${dest.name}.wdpart")
        val via = r.h["x-via"]?.let { dec(it) } ?: "Wi-Fi"
        val h = Transfers.begin(dest.name, true, via, len)
        try {
            FileOutputStream(part).use { fo ->
                val buf = ByteArray(Storage.BUF)
                var left = len
                var got = 0L
                while (left > 0) {
                    val n = r.ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw EOFException("Sender disconnected")
                    fo.write(buf, 0, n)
                    left -= n; got += n
                    h.progress(got)
                }
            }
            if (!part.renameTo(dest)) throw IOException("Rename failed")
            r.h["x-mtime"]?.toLongOrNull()?.let { dest.setLastModified(it) }
            h.finish(dest.path)
            Storage.scan(dest)
            json(r, 200, JSONObject().put("saved", Storage.rel(dest)))
        } catch (e: Exception) {
            part.delete()
            h.fail(e)
            try { json(r, 500, err(e.message ?: "error")) } catch (_: Exception) {}
        }
    }

    private fun mkdir(r: Req) {
        val d = Storage.resolve(r.q["path"]) ?: return json(r, 400, err("Bad path"))
        if (d.exists() || d.mkdirs()) json(r, 200, JSONObject().put("ok", true)) else json(r, 500, err("Can't create folder"))
    }

    private fun delete(r: Req) {
        val f = Storage.resolve(r.q["path"]) ?: return json(r, 400, err("Bad path"))
        if (f.path == Storage.root().path) return json(r, 400, err("Refusing to delete root"))
        if (f.deleteRecursively()) json(r, 200, JSONObject().put("ok", true)) else json(r, 500, err("Delete failed"))
    }

    private fun rename(r: Req) {
        val f = Storage.resolve(r.q["path"]) ?: return json(r, 400, err("Bad path"))
        val to = Storage.safeName(r.q["to"] ?: return json(r, 400, err("to missing")))
        val dest = File(f.parentFile, to)
        if (dest.exists()) return json(r, 409, err("Name already exists"))
        if (f.renameTo(dest)) json(r, 200, JSONObject().put("ok", true)) else json(r, 500, err("Rename failed"))
    }

    // ---------- categories (same index as the app's home screen) ----------

    private fun relOf(abs: String, root: String): String =
        if (abs == root) "/" else if (abs.startsWith("$root/")) abs.substring(root.length) else abs

    private fun categories(r: Req) {
        MediaIndex.refreshIfStale()
        val snap = MediaIndex.snap.value
        val arr = JSONArray()
        Cat.entries.forEach { c ->
            val st = snap?.stats?.get(c)
            arr.put(JSONObject().put("id", c.name).put("label", c.label).put("unit", c.unit)
                .put("count", st?.count ?: 0).put("bytes", st?.bytes ?: 0L))
        }
        json(r, 200, JSONObject().put("ready", snap != null).put("cats", arr))
    }

    private fun catOf(r: Req): Cat? = Cat.entries.firstOrNull { it.name == r.q["cat"] }

    private fun catFolders(r: Req) {
        val cat = catOf(r) ?: return json(r, 400, err("cat"))
        val snap = MediaIndex.snap.value ?: return json(r, 200, JSONObject().put("ready", false).put("folders", JSONArray()))
        val arr = JSONArray()
        snap.folders(cat).forEach { g ->
            arr.put(JSONObject().put("dir", relOf(g.dir, snap.root)).put("name", g.name)
                .put("count", g.count).put("bytes", g.bytes).put("newest", g.newest))
        }
        json(r, 200, JSONObject().put("ready", true).put("folders", arr))
    }

    private fun catFiles(r: Req) {
        val cat = catOf(r) ?: return json(r, 400, err("cat"))
        val snap = MediaIndex.snap.value ?: return json(r, 200, JSONObject().put("ready", false).put("files", JSONArray()))
        val d = r.q["dir"]
        val absDir = if (d.isNullOrEmpty()) null else if (d == "/") snap.root else snap.root + "/" + d.trimStart('/')
        val arr = JSONArray()
        snap.files(cat, absDir).take(2000).forEach { f ->
            arr.put(JSONObject().put("path", relOf(f.path, snap.root)).put("name", f.name)
                .put("size", f.size).put("mtime", f.mtime))
        }
        json(r, 200, JSONObject().put("ready", true).put("files", arr))
    }

    private fun outbox(r: Req) {
        val arr = JSONArray()
        if (TransferService.enabled.value) Outbox.items.value.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.file.name).put("size", it.file.length())) }
        json(r, 200, JSONObject().put("items", arr).put("pc", pcActive()).put("stop", pcStopAt != 0L)
            .put("on", TransferService.enabled.value))
    }

    /** PC page with the S Drive PC helper running: "download" = queue it for the helper (saves to Downloads\\S-Drive). */
    private fun outboxAdd(r: Req) {
        if (r.method != "POST") return json(r, 405, err("Use POST"))
        val f = Storage.resolve(r.q["path"])
        if (f == null || !f.exists()) return json(r, 404, err("File not found"))
        Outbox.add(listOf(f))
        json(r, 200, JSONObject().put("ok", true))
    }

    private fun outboxFile(r: Req) {
        val id = r.q["id"]?.toLongOrNull() ?: return json(r, 400, err("id"))
        val item = Outbox.get(id) ?: return json(r, 404, err("Gone"))
        if (!item.file.isFile) { Outbox.remove(id); return json(r, 404, err("Gone")) }
        if (sendFile(r, item.file, attachment = true, track = true)) Outbox.remove(id)
    }

    // ---------- helpers ----------

    /** Streams a file, honouring Range requests (so the browser can seek in videos). */
    private fun sendFile(r: Req, f: File, attachment: Boolean, track: Boolean): Boolean {
        val len = f.length()
        var start = 0L
        var end = len - 1
        var partial = false
        val range = r.h["range"]
        if (range != null && range.startsWith("bytes=") && len > 0) {
            val spec = range.substring(6).split(',')[0].trim()
            val dash = spec.indexOf('-')
            if (dash >= 0) {
                val a = spec.substring(0, dash).trim()
                val b = spec.substring(dash + 1).trim()
                if (a.isEmpty()) {
                    start = (len - (b.toLongOrNull() ?: 0L)).coerceAtLeast(0)
                } else {
                    start = a.toLongOrNull() ?: 0L
                    if (b.isNotEmpty()) end = (b.toLongOrNull() ?: end).coerceAtMost(len - 1)
                }
                if (start >= len || start > end) {
                    writeHead(r.out, 416, mapOf("Content-Range" to "bytes */$len", "Content-Length" to "0"))
                    return false
                }
                partial = true
            }
        }
        val count = if (len == 0L) 0L else end - start + 1
        val headers = linkedMapOf(
            "Content-Type" to Storage.mime(f.name),
            "Content-Length" to count.toString(),
            "Accept-Ranges" to "bytes",
        )
        if (partial) headers["Content-Range"] = "bytes $start-$end/$len"
        if (attachment) headers["Content-Disposition"] = "attachment; filename*=UTF-8''" + enc(f.name)
        writeHead(r.out, if (partial) 206 else 200, headers)
        if (r.method == "HEAD") return false
        val h = if (track && !partial) Transfers.begin(f.name, false, "Wi-Fi → browser/peer", len) else null
        try {
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(start)
                var left = count
                var sent = 0L
                val buf = ByteArray(Storage.BUF)
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    r.out.write(buf, 0, n)
                    left -= n; sent += n
                    h?.progress(sent)
                }
            }
            r.out.flush()
            h?.finish()
            return true
        } catch (e: Exception) {
            h?.fail(e)
            return false
        }
    }

    private fun writeHead(out: OutputStream, code: Int, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Connection: close\r\nCache-Control: no-store\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun send(r: Req, code: Int, type: String, body: ByteArray) {
        writeHead(r.out, code, mapOf("Content-Type" to type, "Content-Length" to body.size.toString()))
        if (r.method != "HEAD") r.out.write(body)
    }

    private fun json(r: Req, code: Int, o: JSONObject) =
        send(r, code, "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))

    private fun text(r: Req, code: Int, t: String) = send(r, code, "text/plain; charset=utf-8", t.toByteArray())

    private fun err(m: String) = JSONObject().put("error", m)

    private fun reason(c: Int) = when (c) {
        200 -> "OK"; 204 -> "No Content"; 206 -> "Partial Content"; 400 -> "Bad Request"
        401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"
        411 -> "Length Required"; 416 -> "Range Not Satisfiable"; 507 -> "Insufficient Storage"
        else -> "Error"
    }

    private fun readLine(ins: InputStream): String? {
        val sb = ByteArrayOutputStream(128)
        while (true) {
            val c = ins.read()
            if (c < 0) return if (sb.size() == 0) null else sb.toString("UTF-8")
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.write(c)
            if (sb.size() > 16_384) throw IOException("Header too long")
        }
        return sb.toString("UTF-8")
    }

    private fun parseQuery(s: String): Map<String, String> =
        s.split('&').filter { it.isNotEmpty() }.associate {
            val i = it.indexOf('=')
            if (i < 0) dec(it) to "" else dec(it.substring(0, i)) to dec(it.substring(i + 1))
        }

    companion object {
        private const val TRUST_MS = 3 * 60 * 60 * 1000L
        fun dec(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
