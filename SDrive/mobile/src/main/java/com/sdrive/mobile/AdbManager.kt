package com.sdrive.mobile

import android.content.Context
import com.sdrive.shared.AdbBackend
import com.sdrive.shared.App
import com.sdrive.shared.Prefs
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.math.BigInteger
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * ADB client that runs on the phone and talks to the watch over Wireless debugging
 * (pairing + TLS), using LibADB Android. The key pair is generated once and stored
 * privately in the app, so the watch remembers this phone after the first pairing.
 */
object SDriveAdb : AdbBackend {
    private val _status = MutableStateFlow<String?>(null)
    override val status: StateFlow<String?> = _status

    private val lock = Any()
    private var mgr: Manager? = null

    private fun manager(): Manager = synchronized(lock) { mgr ?: Manager(App.ctx).also { mgr = it } }

    private fun friendly(e: Throwable): IOException {
        val n = e.javaClass.simpleName
        val msg = when {
            n.contains("PairingRequired") -> "This phone isn't paired with the watch yet. Tap PAIR first."
            n.contains("AuthenticationFailed") -> "The watch rejected this phone. Pair again."
            e is ConnectException -> "Can't reach the watch. Check IP:port and that Wireless debugging is ON."
            e is SocketTimeoutException -> "The watch didn't answer (timeout). Is it awake and on the same Wi-Fi?"
            else -> e.message ?: n
        }
        return IOException(msg, e)
    }

    override suspend fun pair(host: String, port: Int, code: String) = withContext(Dispatchers.IO) {
        try {
            manager().pair(host, port, code)
        } catch (e: Exception) {
            throw friendly(e)
        }
        Unit
    }

    override suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        val m = manager()
        try { m.disconnect() } catch (_: Exception) {}
        _status.value = null
        val ok = try {
            m.connect(host, port)
        } catch (e: Exception) {
            throw friendly(e)
        }
        if (!ok || !m.isConnected) {
            throw IOException("The watch didn't accept the connection. Use the IP:port from the top of the Wireless debugging screen, and tap Allow on the watch if asked.")
        }
        _status.value = "$host:$port"
        Prefs.lastAdb = "$host:$port"
        Unit
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) { try { mgr?.disconnect() } catch (_: Exception) {} }
        _status.value = null
    }

    private fun open(destination: String): AdbStream {
        val m = manager()
        if (!m.isConnected) {
            _status.value = null
            throw IOException("Not connected to the watch. Tap CONNECT.")
        }
        return try { m.openStream(destination) } catch (e: Exception) { throw friendly(e) }
    }

    /** Reads a stream to the end, passing text chunks to [onOutput]. */
    private fun drain(s: AdbStream, onOutput: (String) -> Unit) {
        val r = InputStreamReader(s.openInputStream(), Charsets.UTF_8)
        val buf = CharArray(4096)
        while (true) {
            val n = try { r.read(buf) } catch (e: IOException) {
                if (s.isClosed) -1 else throw e
            }
            if (n < 0) break
            if (n > 0) onOutput(String(buf, 0, n))
        }
    }

    override suspend fun shell(cmd: String, onOutput: (String) -> Unit) = runInterruptible(Dispatchers.IO) {
        val s = open("shell:$cmd")
        try { drain(s, onOutput) } finally { try { s.close() } catch (_: Exception) {} }
    }

    override suspend fun install(apk: File, onOutput: (String) -> Unit) = runInterruptible(Dispatchers.IO) {
        val size = apk.length()
        val s = open("exec:cmd package install -r -S $size")
        try {
            val out = s.openOutputStream()
            FileInputStream(apk).use { it.copyTo(out, 64 * 1024) }
            out.flush()
            drain(s, onOutput)
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    /** LibADB connection manager with a persistent RSA key + self-signed certificate. */
    private class Manager(ctx: Context) : AbsAdbConnectionManager() {
        private val key: PrivateKey
        private val cert: Certificate

        init {
            setApi(30) // the watch runs Android 11+ (Wear OS 3+)
            setTimeout(20, TimeUnit.SECONDS)
            val (k, c) = loadOrCreate(File(ctx.filesDir, "adb"))
            key = k
            cert = c
        }

        override fun getPrivateKey(): PrivateKey = key
        override fun getCertificate(): Certificate = cert
        override fun getDeviceName(): String = "S Drive"

        private fun loadOrCreate(dir: File): Pair<PrivateKey, Certificate> {
            dir.mkdirs()
            val kf = File(dir, "key.pk8")
            val cf = File(dir, "cert.der")
            if (kf.isFile && cf.isFile) {
                try {
                    val k = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
                    val c = cf.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
                    return Pair(k, c)
                } catch (_: Exception) {
                    // fall through and regenerate
                }
            }
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()
            val name = X500Name("CN=S Drive")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), Date(now - 86_400_000L), Date(now + 3650L * 86_400_000L), name, kp.public,
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
            val x509 = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            kf.writeBytes(kp.private.encoded)
            cf.writeBytes(x509.encoded)
            return Pair(kp.private, x509)
        }
    }
}