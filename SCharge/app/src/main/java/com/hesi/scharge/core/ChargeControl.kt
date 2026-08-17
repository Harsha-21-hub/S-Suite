package com.hesi.scharge.core

import android.content.Context
import android.os.SystemClock
import com.hesi.scharge.data.Prefs

data class ChargeSwitch(val path: String, val onValue: String, val offValue: String)

/**
 * Turns charging on/off through whichever kernel switch this device has.
 * Samsung's batt_slate_mode is listed first (SM-T510 and most Samsung
 * kernels use it), followed by common switches used by other vendors.
 * The first switch that exists is detected once, persisted, and reused.
 */
object ChargeControl {

    private val SWITCHES = listOf(
        ChargeSwitch("/sys/class/power_supply/battery/batt_slate_mode", "0", "1"),
        ChargeSwitch("/sys/class/power_supply/battery/charging_enabled", "1", "0"),
        ChargeSwitch("/sys/class/power_supply/battery/battery_charging_enabled", "1", "0"),
        ChargeSwitch("/sys/class/power_supply/battery/input_suspend", "0", "1"),
        ChargeSwitch("/sys/class/power_supply/battery/charge_disable", "0", "1"),
        ChargeSwitch("/sys/class/power_supply/battery/mmi_charging_enable", "1", "0"),
        ChargeSwitch("/sys/class/power_supply/main/input_suspend", "0", "1"),
        ChargeSwitch("/sys/class/power_supply/dc/input_suspend", "0", "1")
    )

    @Volatile
    private var detected: ChargeSwitch? = null

    /** elapsedRealtime of the last time charging was blocked (phantom-unplug guard). */
    @Volatile
    var lastBlockAt: Long = 0L
        private set

    /** elapsedRealtime of the last time charging was re-enabled (phantom-connect guard). */
    @Volatile
    var lastUnblockAt: Long = 0L
        private set

    fun detect(context: Context): ChargeSwitch? {
        detected?.let { return it }
        if (!RootShell.isRootAvailable()) return null

        val cachedPath = Prefs.controlPath(context)
        if (cachedPath.isNotEmpty()) {
            val sw = SWITCHES.firstOrNull { it.path == cachedPath }
            if (sw != null && exists(sw.path)) {
                detected = sw
                return sw
            }
        }
        for (sw in SWITCHES) {
            if (exists(sw.path)) {
                detected = sw
                Prefs.setControlPath(context, sw.path)
                return sw
            }
        }
        return null
    }

    private fun exists(path: String): Boolean =
        RootShell.exec("[ -e $path ] && echo OK").contains("OK")

    /** Returns true if a switch exists and the value was written. */
    fun setChargingEnabled(context: Context, enabled: Boolean): Boolean {
        val sw = detect(context) ?: return false
        val value = if (enabled) sw.onValue else sw.offValue
        RootShell.exec("echo $value > ${sw.path}")
        if (!enabled) lastBlockAt = SystemClock.elapsedRealtime()
        else lastUnblockAt = SystemClock.elapsedRealtime()
        Prefs.setChargingBlocked(context, !enabled)
        return true
    }

    /**
     * Reads the real charging-enabled state straight from the kernel switch,
     * rather than trusting the mirrored [Prefs.chargingBlocked] flag.
     *
     * @return true = enabled, false = blocked, null = unknown / can't read.
     */
    fun readChargingEnabled(context: Context): Boolean? {
        val sw = detect(context) ?: return null
        val raw = RootShell.exec("cat ${sw.path} 2>/dev/null").trim()
        if (raw.isEmpty()) return null
        return raw == sw.onValue
    }

    fun isChargingBlocked(context: Context): Boolean = Prefs.chargingBlocked(context)
}
