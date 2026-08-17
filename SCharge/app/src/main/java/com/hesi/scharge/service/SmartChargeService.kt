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
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hesi.scharge.R
import com.hesi.scharge.core.CalibrationManager
import com.hesi.scharge.core.ChargeControl
import com.hesi.scharge.core.RootShell
import com.hesi.scharge.data.Prefs
import com.hesi.scharge.telemetry.TelemetryReader
import com.hesi.scharge.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Event-driven foreground service (ROOT). Reacts to the system's own
 * ACTION_BATTERY_CHANGED / POWER_CONNECTED / POWER_DISCONNECTED broadcasts.
 *
 *  - Smart charge: stop at [stopAt]%, resume at [resumeAt]% (plain hysteresis,
 *    mirrored in Prefs — no per-tick kernel writes, so it cannot oscillate).
 *  - Manual overrides (FORCE / STOP) reset on a genuine unplug/replug.
 *  - PHANTOM GUARD: toggling the charge switch makes some kernels fire their own
 *    CONNECTED/DISCONNECTED events. Any plug event within [PHANTOM_MS] of one of
 *    our own writes is ignored, which stops the "pause → charge → pause" loop.
 *  - Never leaves charging blocked behind.
 *  - Advances a running calibration.
 */
class SmartChargeService : Service() {

    companion object {
        const val ACTION_START = "com.hesi.scharge.action.START"
        const val ACTION_FORCE_CHARGE = "com.hesi.scharge.action.FORCE"
        const val ACTION_STOP_CHARGE = "com.hesi.scharge.action.STOP_CHARGE"
        const val ACTION_CLEAR_OVERRIDE = "com.hesi.scharge.action.CLEAR"
        const val ACTION_REEVALUATE = "com.hesi.scharge.action.EVAL"
        const val ACTION_SHUTDOWN = "com.hesi.scharge.action.SHUTDOWN"

        private const val CHANNEL_ID = "smart_charge"
        private const val NOTIF_ID = 1
        private const val PHANTOM_MS = 6_000L

        fun start(context: Context, action: String = ACTION_START) {
            val i = Intent(context, SmartChargeService::class.java).setAction(action)
            context.startForegroundService(i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastPlugged: Boolean? = null

    @Volatile
    private var lastEvalAt = 0L

    @Volatile
    private var lastNotifText = ""

    /** True if a plug event is really the echo of our own charge-switch write. */
    private fun isPhantom(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return now - ChargeControl.lastBlockAt < PHANTOM_MS ||
            now - ChargeControl.lastUnblockAt < PHANTOM_MS
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> scope.launch { onConnected() }
                Intent.ACTION_POWER_DISCONNECTED -> scope.launch { onDisconnected() }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val plugged =
                        intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    val prev = lastPlugged
                    lastPlugged = plugged
                    if (prev != null && prev != plugged) {
                        scope.launch { if (plugged) onConnected() else onDisconnected() }
                    } else if (SystemClock.elapsedRealtime() - lastEvalAt > 3000) {
                        scope.launch { evaluate() }
                    }
                }
            }
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
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("S Charge active"), type)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ctx = applicationContext
        when (intent?.action) {
            ACTION_FORCE_CHARGE -> scope.launch {
                Prefs.setOverrideMode(ctx, Prefs.OVERRIDE_FORCE)
                ChargeControl.setChargingEnabled(ctx, true)
                updateNotification()
            }

            ACTION_STOP_CHARGE -> scope.launch {
                Prefs.setOverrideMode(ctx, Prefs.OVERRIDE_STOP)
                ChargeControl.setChargingEnabled(ctx, false)
                updateNotification()
            }

            ACTION_CLEAR_OVERRIDE -> scope.launch {
                Prefs.setOverrideMode(ctx, Prefs.OVERRIDE_NONE)
                if (Prefs.chargingBlocked(ctx)) ChargeControl.setChargingEnabled(ctx, true)
                evaluate()
                maybeExit()
            }

            ACTION_SHUTDOWN -> scope.launch {
                if (Prefs.overrideMode(ctx) == Prefs.OVERRIDE_NONE && !Prefs.calibRunning(ctx)) {
                    if (Prefs.chargingBlocked(ctx)) ChargeControl.setChargingEnabled(ctx, true)
                    stopSelfSafely()
                } else {
                    evaluate()
                }
            }

            else -> scope.launch { evaluate() } // ACTION_START / ACTION_REEVALUATE / restart
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

    // ------------------------------------------------------------------
    // Plug events
    // ------------------------------------------------------------------

    private fun onConnected() {
        val ctx = applicationContext
        if (isPhantom()) {
            // Our own charge-switch write echoing back — don't reset anything,
            // just re-evaluate. First line of defence against the replug loop.
            evaluate()
            return
        }
        // Genuine plug-in: clear MANUAL overrides only. Deliberately do NOT lift a
        // smart-charge pause here — evaluate() governs that purely by battery
        // level, so even a stray connect can never bounce charging back on above
        // the resume level. This is the guarantee that stops the pause↔charge loop.
        if (Prefs.overrideMode(ctx) != Prefs.OVERRIDE_NONE) {
            Prefs.setOverrideMode(ctx, Prefs.OVERRIDE_NONE)
            if (Prefs.chargingBlocked(ctx)) ChargeControl.setChargingEnabled(ctx, true)
        } else if (Prefs.smartEnabled(ctx) &&
            !Prefs.chargingBlocked(ctx) &&
            ChargeControl.readChargingEnabled(ctx) == false
        ) {
            // Repair a desync (kernel left "off" after a kill/uninstall).
            ChargeControl.setChargingEnabled(ctx, true)
        }
        evaluate()
    }

    private fun onDisconnected() {
        val ctx = applicationContext
        if (isPhantom()) return
        Prefs.setOverrideMode(ctx, Prefs.OVERRIDE_NONE)
        if (Prefs.chargingBlocked(ctx)) ChargeControl.setChargingEnabled(ctx, true)
        updateNotification()
        maybeExit()
    }

    // ------------------------------------------------------------------
    // Core logic
    // ------------------------------------------------------------------

    private fun evaluate() {
        val ctx = applicationContext
        lastEvalAt = SystemClock.elapsedRealtime()
        if (!RootShell.isRootAvailable()) {
            stopSelfSafely()
            return
        }
        val (level, plugged) = snapshot()

        if (Prefs.calibRunning(ctx)) {
            CalibrationManager.onTelemetry(ctx, TelemetryReader.read(ctx))
        }

        when (Prefs.overrideMode(ctx)) {
            Prefs.OVERRIDE_FORCE -> {
                if (Prefs.chargingBlocked(ctx)) ChargeControl.setChargingEnabled(ctx, true)
            }

            Prefs.OVERRIDE_STOP -> {
                if (!Prefs.chargingBlocked(ctx)) ChargeControl.setChargingEnabled(ctx, false)
            }

            else -> {
                if (Prefs.smartEnabled(ctx)) {
                    val blocked = Prefs.chargingBlocked(ctx)
                    if (!blocked && plugged && level >= Prefs.stopAt(ctx)) {
                        ChargeControl.setChargingEnabled(ctx, false)
                    } else if (blocked && level <= Prefs.resumeAt(ctx)) {
                        ChargeControl.setChargingEnabled(ctx, true)
                    }
                } else if (Prefs.chargingBlocked(ctx)) {
                    // Smart off: never leave charging blocked.
                    ChargeControl.setChargingEnabled(ctx, true)
                }
            }
        }
        updateNotification(level)
        if (!Prefs.smartEnabled(ctx) &&
            Prefs.overrideMode(ctx) == Prefs.OVERRIDE_NONE &&
            !Prefs.chargingBlocked(ctx) &&
            !Prefs.calibRunning(ctx)
        ) {
            maybeExit()
        }
    }

    private fun snapshot(): Pair<Int, Boolean> {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val l = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val s = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val level = if (l >= 0 && s > 0) l * 100 / s else 0
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return level to plugged
    }

    private fun maybeExit() {
        val ctx = applicationContext
        if (!Prefs.smartEnabled(ctx) &&
            Prefs.overrideMode(ctx) == Prefs.OVERRIDE_NONE &&
            !Prefs.calibRunning(ctx)
        ) {
            stopSelfSafely()
        }
    }

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "Smart Charge", NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            description = "Shows smart charge status"
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

    private fun updateNotification(level: Int = -1) {
        val ctx = applicationContext
        val t = TelemetryReader.read(ctx)
        val lvl = if (level >= 0) level else t.level
        val eta = if (!Prefs.chargingBlocked(ctx)) {
            val calib = Prefs.calibratedFullMah(ctx)
            val manual = Prefs.manualDesignMah(ctx)
            val effFull = when {
                calib > 0 -> calib.toDouble()
                t.fullMah > 0 -> t.fullMah
                manual > 0 -> manual.toDouble()
                else -> t.designMah
            }
            (TelemetryReader.frameworkMinutesToFull(ctx, t)
                ?: TelemetryReader.timeToFullMinutes(t, effFull))
                ?.takeIf { it > 0 }
                ?.let { " · full in ${formatMinutes(it)}" }
                ?: ""
        } else ""

        val text = when {
            Prefs.calibRunning(ctx) -> "$lvl% · Calibrating — keep charging to 100%"
            Prefs.overrideMode(ctx) == Prefs.OVERRIDE_FORCE ->
                "$lvl% · Force charge (resets on re-plug)$eta"
            Prefs.overrideMode(ctx) == Prefs.OVERRIDE_STOP ->
                "$lvl% · Charging stopped (resets on re-plug)"
            Prefs.smartEnabled(ctx) -> {
                if (Prefs.chargingBlocked(ctx)) {
                    "$lvl% · Paused, resumes at ${Prefs.resumeAt(ctx)}%"
                } else {
                    "$lvl% · Smart ${Prefs.stopAt(ctx)}% / ${Prefs.resumeAt(ctx)}%$eta"
                }
            }
            else -> "$lvl%$eta"
        }
        if (text == lastNotifText) return
        lastNotifText = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
