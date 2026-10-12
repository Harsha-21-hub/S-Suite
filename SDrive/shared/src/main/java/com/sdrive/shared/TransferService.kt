package com.sdrive.shared

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.concurrent.thread

/**
 * Foreground service alive only while something is being transferred or the user has
 * "Receive" switched on. Holds the Wi-Fi keep-alive and runs the HTTP server.
 * Auto-stops when idle to save battery.
 */
class TransferService : Service() {

    companion object {
        private const val A_SERVER_ON = "on"
        private const val A_SERVER_OFF = "off"
        private const val A_KEEP = "keep"
        private const val SERVER_IDLE_MS = 15 * 60 * 1000L
        private const val KEEP_IDLE_MS = 45 * 1000L

        val server = WristServer()
        val serverOn = MutableStateFlow(false)
        val serverError = MutableStateFlow<String?>(null)

        private fun cmd(ctx: Context, action: String) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, TransferService::class.java).setAction(action))
            } catch (e: Exception) {
                // keepAlive() from the background can be refused on Android 12+; transfers still run.
                if (action == A_SERVER_ON) serverError.value = e.message
            }
        }
        fun startServer(ctx: Context) = cmd(ctx, A_SERVER_ON)
        fun stopServer(ctx: Context) = cmd(ctx, A_SERVER_OFF)
        fun keepAlive(ctx: Context) = cmd(ctx, A_KEEP)

        /** Transfer master switch. OFF = nothing can be sent or received until it is turned ON again. */
        val enabled = MutableStateFlow(Prefs.transferOn)

        /** Switch from this device: ON starts receiving, OFF cancels transfers and closes the port. */
        fun setEnabled(ctx: Context, on: Boolean) {
            Prefs.transferOn = on
            enabled.value = on
            if (on) startServer(ctx) else { Transfers.cancelAll(); stopServer(ctx) }
        }

        /** Switch from the PC page: the port stays open so the page can switch it back ON. */
        fun setEnabledRemote(on: Boolean) {
            Prefs.transferOn = on
            enabled.value = on
            if (!on) Transfers.cancelAll()
        }

        /** PC page "Stop": closes the port now (the service then stops itself when idle). */
        fun closePort() {
            server.stop()
            Discovery.unregister()
            serverOn.value = false
        }

        /** Shown when something is attempted while the switch is OFF. Returns true if OFF. */
        fun offToast(): Boolean {
            if (enabled.value) return false
            App.toast("Transfer is OFF. Turn it ON in Transfer.")
            return true
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { checkIdle(); handler.postDelayed(this, 15_000) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NetworkKeeper.acquire()
        handler.postDelayed(tick, 15_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        when (intent?.action) {
            A_SERVER_ON -> if (enabled.value) startServerNow() else checkIdle()
            A_SERVER_OFF -> { stopServerNow(); checkIdle() }
            else -> Transfers.touch()
        }
        return START_NOT_STICKY
    }

    private fun startServerNow() {
        if (server.running) { serverOn.value = true; return }
        thread(name = "wd-start") {
            try {
                val p = server.start()
                Discovery.register(p)
                serverError.value = null
                serverOn.value = true
                Transfers.touch()
                handler.post { startFg() }
            } catch (e: Exception) {
                serverError.value = e.message ?: "Server failed"
            }
        }
    }

    private fun stopServerNow() {
        server.stop()
        Discovery.unregister()
        serverOn.value = false
    }

    private fun checkIdle() {
        if (Transfers.active) return
        val idle = SystemClock.elapsedRealtime() - Transfers.lastActivity
        if (serverOn.value) {
            if (idle < SERVER_IDLE_MS) return
            stopServerNow()
        } else if (idle < KEEP_IDLE_MS) return
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        stopServerNow()
        NetworkKeeper.release()
        super.onDestroy()
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("transfer", "Transfers", NotificationManager.IMPORTANCE_LOW))
        val text = if (serverOn.value) "Receiving on ${Net.localIp() ?: "Wi-Fi"}:${server.port} · PIN ${Prefs.pin}"
        else "Transferring files…"
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(this, "transfer")
            .setSmallIcon(R.drawable.wd_ic_stat)
            .setContentTitle(App.NAME)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        ServiceCompat.startForeground(
            this, 7, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
    }
}