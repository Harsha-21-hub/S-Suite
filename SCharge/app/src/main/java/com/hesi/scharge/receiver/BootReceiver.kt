package com.hesi.scharge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hesi.scharge.data.Prefs
import com.hesi.scharge.service.SmartChargeService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Kernel switches reset on reboot — clear our mirrored state.
        Prefs.setOverrideMode(context, Prefs.OVERRIDE_NONE)
        Prefs.setChargingBlocked(context, false)
        if (Prefs.smartEnabled(context)) {
            SmartChargeService.start(context)
        }
    }
}
