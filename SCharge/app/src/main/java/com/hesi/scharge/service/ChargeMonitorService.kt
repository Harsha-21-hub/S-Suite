package com.hesi.scharge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hesi.scharge.R
import com.hesi.scharge.core.CalibrationManager
import com.hesi.scharge.core.CycleEstimator
import com.hesi.scharge.data.Prefs
import com.hesi.scharge.telemetry.TelemetryData
import com.hesi.scharge.telemetry.TelemetryReader
import com.hesi.scharge.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * NO-ROOT foreground service. It does NOT control charging — it only:
 *   - advances a running calibration (so it finishes even with the screen off,
 *     on rooted AND unrooted devices), and
 *   - shows a live "full in ~Hh Mm" notification while charging.
 *
 * It is started by the UI while charging or calibrating, and stops itself once
 * neither is true. The root charge-control service (SmartChargeService) owns the
 * notification when Smart Charge is active, so the UI does not start this one in
 * that case (avoids a duplicate notification).
 */
class ChargeMonitorService : Service() {

    companion object {
        const val ACTION_START = "com.hesi.scharge.monitor.START"
        const val ACTION_STOP = "com.hesi.scharge.monitor.STOP"

        private const val CHANNEL_ID = "charge_monitor"
        private const val NOTIF_ID = 2

        fun start(context: Context) {
            val i = Intent(context, ChargeMonitorService::class.java).setAction(ACTION_START)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, ChargeMonitorService::class.java).setAction(ACTION_STOP)
            context.startForegroundService(i)
        }

        /** Charge that still has to go in makes it worth running. */
        fun shouldRun(ctx: Context, t: TelemetryData): Boolean {
            if (Prefs.calibRunning(ctx)) return true
            return t.plugged && t.level < 100 &&
                (t.status.equals("Charging", true) || t.current > 0.02)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastNotifText = ""

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch { tick() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Monitoring charge"), type)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // Keep the estimate fresh even when broadcasts are sparse.
        scope.launch {
            while (isActive) {
                tick()
                delay(30_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                if (!Prefs.calibRunning(applicationContext)) stopSelfSafely()
            }
        } else {
            scope.launch { tick() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(receiver)
        } catch (_: Throwable) {
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun tick() {
        val ctx = applicationContext
        val t = TelemetryReader.read(ctx)
        if (Prefs.calibRunning(ctx)) CalibrationManager.onTelemetry(ctx, t)
        CycleEstimator.onTelemetry(ctx, t)
        if (!shouldRun(ctx, t)) {
            stopSelfSafely()
            return
        }
        updateNotification(ctx, t)
    }

    private fun effectiveFull(ctx: Context, t: TelemetryData): Double {
        val calib = Prefs.calibratedFullMah(ctx)
        if (calib > 0) return calib.toDouble()
        if (t.fullMah > 0) return t.fullMah
        val manual = Prefs.manualDesignMah(ctx)
        if (manual > 0) return manual.toDouble()
        return t.designMah
    }

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "Charge monitor", NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            description = "Charge time estimate and calibration progress"
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_charge)
            .setContentTitle("S Charge")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(ctx: Context, t: TelemetryData) {
        val text = when {
            Prefs.calibRunning(ctx) ->
                "${t.level}% · Calibrating — keep charging"
            t.level >= 100 || t.status.equals("Full", true) ->
                "${t.level}% · Fully charged"
            else -> {
                val mins = TelemetryReader.frameworkMinutesToFull(ctx, t)
                    ?: TelemetryReader.timeToFullMinutes(t, effectiveFull(ctx, t))
                if (mins != null && mins > 0) {
                    "${t.level}% · Full in ${formatMinutes(mins)}"
                } else {
                    "${t.level}% · Charging"
                }
            }
        }
        if (text == lastNotifText) return
        lastNotifText = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}

/** "1h 23m" / "45m". Shared by the notification and the UI. */
fun formatMinutes(total: Int): String {
    val h = total / 60
    val m = total % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
