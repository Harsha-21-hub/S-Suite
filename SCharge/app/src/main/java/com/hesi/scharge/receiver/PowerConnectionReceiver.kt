package com.hesi.scharge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hesi.scharge.data.Prefs
import com.hesi.scharge.service.ChargeMonitorService

/**
 * Starts the no-root charge monitor (and its charge-time notification) the moment
 * a charger is connected, so the notification appears without opening the app.
 *
 * ACTION_POWER_CONNECTED is exempt from the manifest implicit-broadcast ban, so
 * this fires even when the app process isn't running. Starting a foreground
 * service from the background is allowed on Android 12+ only when the app is
 * exempt from battery optimizations — which is why S Charge asks for that. If it
 * isn't exempt the start throws and we ignore it (the app still shows the
 * notification once opened). When Smart Charge / an override is active the root
 * service owns the notification, so we don't start the monitor here.
 */
class PowerConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_POWER_CONNECTED) return
        if (Prefs.smartEnabled(context) ||
            Prefs.overrideMode(context) != Prefs.OVERRIDE_NONE
        ) return
        try {
            ChargeMonitorService.start(context)
        } catch (_: Throwable) {
            // Background FGS start not allowed (not battery-opt exempt) — ignore.
        }
    }
}
