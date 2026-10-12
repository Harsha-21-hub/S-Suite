package com.sdrive.shared

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.NetworkInterface

object Net {
    /** This device's LAN IPv4 address (Wi-Fi preferred). */
    fun localIp(): String? = try {
        val nis = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val c = nis.filter { it.isUp && !it.isLoopback }.flatMap { ni ->
            ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it }
        }
        (c.firstOrNull { it.first.startsWith("wlan") }
            ?: c.firstOrNull { it.second.isSiteLocalAddress }
            ?: c.firstOrNull())?.second?.hostAddress
    } catch (_: Exception) { null }
}

/**
 * Keeps Wi-Fi ON and at full speed while transferring.
 *
 * Galaxy Watches (Wear OS) switch Wi-Fi off whenever the phone is connected over Bluetooth.
 * Requesting a Wi-Fi network through ConnectivityManager is the official way to make Wear OS
 * bring Wi-Fi up and keep it up until we release the request. We also hold a Wi-Fi lock and a
 * partial wake lock so the radio and CPU don't sleep mid-transfer.
 */
object NetworkKeeper {
    val wifiUp = MutableStateFlow(false)
    private var cb: ConnectivityManager.NetworkCallback? = null
    private val locks = ArrayList<WifiManager.WifiLock>()
    private var wake: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire() {
        if (cb != null) return
        val ctx = App.ctx
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SDrive:transfer").apply {
            setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L)
        }
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            @Suppress("DEPRECATION")
            locks += wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SDrive:hp").apply { setReferenceCounted(false); acquire() }
            locks += wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "SDrive:ll").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {}

        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val c = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                try { cm.bindProcessToNetwork(network) } catch (_: Exception) {}
                wifiUp.value = true
            }
            override fun onLost(network: Network) {
                wifiUp.value = false
                try { cm.bindProcessToNetwork(null) } catch (_: Exception) {}
            }
        }
        cb = c
        try {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // LAN without internet is fine
                .build()
            cm.requestNetwork(req, c)
        } catch (_: Exception) {}
    }

    @Synchronized
    fun release() {
        val cm = App.ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cb?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        cb = null
        try { cm.bindProcessToNetwork(null) } catch (_: Exception) {}
        locks.forEach { try { if (it.isHeld) it.release() } catch (_: Exception) {} }
        locks.clear()
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        wifiUp.value = false
    }
}

/** Finds other SDrive devices on the same Wi-Fi via mDNS / NSD. */
object Discovery {
    private const val TYPE = "_sdrive._tcp"

    data class Peer(val name: String, val host: String, val port: Int, val kind: String)

    val peers = MutableStateFlow<List<Peer>>(emptyList())
    private val nsd by lazy { App.ctx.getSystemService(Context.NSD_SERVICE) as NsdManager }
    private val main = Handler(Looper.getMainLooper())
    private var reg: NsdManager.RegistrationListener? = null
    private var disc: NsdManager.DiscoveryListener? = null
    @Volatile private var myName: String? = null
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var users = 0

    fun register(port: Int) = main.post {
        if (reg != null) return@post
        val info = NsdServiceInfo().apply {
            serviceName = Prefs.deviceName
            serviceType = TYPE
            this.port = port
            setAttribute("k", if (App.isWatch) "watch" else "phone")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) { myName = si.serviceName }
            override fun onRegistrationFailed(si: NsdServiceInfo, err: Int) { main.post { reg = null } }
            override fun onServiceUnregistered(si: NsdServiceInfo) {}
            override fun onUnregistrationFailed(si: NsdServiceInfo, err: Int) {}
        }
        reg = l
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) } catch (_: Exception) { reg = null }
    }

    fun unregister() = main.post {
        reg?.let { try { nsd.unregisterService(it) } catch (_: Exception) {} }
        reg = null
    }

    /** Ref-counted so several screens can ask for discovery at once. */
    fun start() = main.post {
        users++
        if (disc != null) return@post
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { main.post { disc = null } }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(si: NsdServiceInfo) {
                if (si.serviceName == myName) return
                main.post { queue.addLast(si); next() }
            }
            override fun onServiceLost(si: NsdServiceInfo) {
                peers.update { l -> l.filterNot { it.name == si.serviceName } }
            }
        }
        disc = l
        try { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, l) } catch (_: Exception) { disc = null }
    }

    fun stop() = main.post {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return@post
        disc?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        disc = null
    }

    fun addManual(host: String, port: Int) {
        val p = Peer(host, host.trim(), port, "manual")
        peers.update { l -> l.filterNot { it.host == p.host && it.port == p.port } + p }
    }

    private fun next() {
        if (resolving) return
        val si = queue.removeFirstOrNull() ?: return
        resolving = true
        try {
            @Suppress("DEPRECATION")
            nsd.resolveService(si, object : NsdManager.ResolveListener {
                override fun onResolveFailed(s: NsdServiceInfo, e: Int) { main.post { resolving = false; next() } }
                override fun onServiceResolved(s: NsdServiceInfo) {
                    val host = hostOf(s)
                    if (host != null && host != Net.localIp()) {
                        val kind = s.attributes["k"]?.let { String(it) } ?: "device"
                        val p = Peer(s.serviceName, host, s.port, kind)
                        peers.update { l -> l.filterNot { it.name == p.name || (it.host == p.host && it.port == p.port) } + p }
                    }
                    main.post { resolving = false; next() }
                }
            })
        } catch (_: Exception) { resolving = false; main.post { next() } }
    }

    @Suppress("DEPRECATION")
    private fun hostOf(s: NsdServiceInfo): String? {
        if (Build.VERSION.SDK_INT >= 34) {
            s.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress?.let { return it }
        }
        return s.host?.hostAddress
    }
}