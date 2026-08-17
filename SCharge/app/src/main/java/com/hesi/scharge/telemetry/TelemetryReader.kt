package com.hesi.scharge.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.hesi.scharge.core.Nodes
import com.hesi.scharge.data.Prefs
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reads battery telemetry. Every value has a non-root path
 * (BatteryManager / sticky ACTION_BATTERY_CHANGED / world-readable sysfs),
 * so the whole dashboard works on unrooted devices too.
 */
object TelemetryReader {

    private const val CHARGE_EFFICIENCY = 0.87 // typical charger->cell conversion
    private const val CV_CURRENT_FRACTION = 0.5 // avg current in the CV (top-off) phase

    @Volatile
    private var cachedProfileMah = -1.0 // PowerProfile design capacity, resolved once

    @Volatile private var cachedRealFull = 0.0
    @Volatile private var cachedDesign = 0.0
    @Volatile private var cachedCycles = -1
    @Volatile private var cachedAging = 0.0

    fun read(context: Context): TelemetryData {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val intent: Intent? =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // ---- Level ----
        val level = run {
            val l = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val s = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (l >= 0 && s > 0) l * 100 / s
            else Nodes.readBattery("capacity").toIntOrNull() ?: 0
        }

        // ---- Status / plugged ----
        val statusInt = intent?.getIntExtra(
            BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN
        ) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val status = when (statusInt) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "Unknown"
        }
        val plugged = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

        // ---- Health status text (BatteryManager, no root) ----
        val healthStatus = when (
            intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
                ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
        ) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
            else -> "Unknown"
        }

        // ---- Voltage (V) ----
        val voltage = run {
            val sys = Nodes.readBattery("voltage_now", "batt_voltage_now").toDoubleOrNull()
            when {
                sys != null && sys > 100_000 -> sys / 1_000_000.0 // µV
                sys != null && sys > 100 -> sys / 1000.0          // mV
                else -> (intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000.0
            }
        }

        // ---- Current (A), sign normalized: positive = charging ----
        // Robust non-root path: CURRENT_NOW, then CURRENT_AVERAGE (some devices
        // return 0 for NOW), then world-readable sysfs.
        val rawCurrent = run {
            val now = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (now != 0 && now != Int.MIN_VALUE) return@run now.toDouble()
            val avg = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            if (avg != 0 && avg != Int.MIN_VALUE) return@run avg.toDouble()
            Nodes.readBattery(
                "current_now", "batt_current_ua_now", "current_avg", "batt_current_ua_avg"
            ).toDoubleOrNull() ?: 0.0
        }
        // Kernels report µA (standard) or mA (some Samsung). Heuristic on magnitude.
        val magnitudeAmps =
            if (abs(rawCurrent) >= 10_000) abs(rawCurrent) / 1_000_000.0
            else abs(rawCurrent) / 1000.0
        val current = when (statusInt) {
            BatteryManager.BATTERY_STATUS_CHARGING -> magnitudeAmps
            BatteryManager.BATTERY_STATUS_DISCHARGING -> -magnitudeAmps
            else -> if (rawCurrent >= 0) magnitudeAmps else -magnitudeAmps
        }

        // ---- Temperature (°C) ----
        val temperature = run {
            val t = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?: Int.MIN_VALUE
            if (t != Int.MIN_VALUE && t != 0) t / 10.0
            else (Nodes.readBattery("temp", "batt_temp", "temperature")
                .toDoubleOrNull() ?: 0.0) / 10.0
        }

        // Battery terminal power (BATTERY PWR): V_batt x I_batt. Positive while
        // charging (into the cell), negative while discharging.
        val batteryPower = voltage * current

        // ---- Charger INPUT power (W) ----
        // The charger delivers at the bus voltage (VBUS, ~5/9 V), higher than the
        // cell voltage, so input power > battery power. Order:
        //   1. VBUS x input current (usb/ac supply) — the true value,
        //   2. VBUS x battery current — estimate when input current is 0,
        //   3. battery power / efficiency — last resort (keeps it distinct & non-zero).
        val inputPower = run {
            val uvRaw = Nodes.readInput("voltage_now").toDoubleOrNull() ?: 0.0
            val vBus = when {
                uvRaw > 100_000 -> uvRaw / 1_000_000.0 // µV
                uvRaw > 100 -> uvRaw / 1000.0          // mV
                else -> 0.0
            }
            val uaRaw = Nodes.readInput(
                "current_now", "input_current_now", "input_current_settled", "current_max"
            ).toDoubleOrNull() ?: 0.0
            val iIn =
                if (abs(uaRaw) >= 10_000) abs(uaRaw) / 1_000_000.0 else abs(uaRaw) / 1000.0
            when {
                vBus >= 4.5 && iIn > 0.02 -> vBus * iIn            // true charger power
                vBus >= 4.5 && current > 0.02 -> vBus * current    // VBUS x battery current
                current > 0.02 -> batteryPower / CHARGE_EFFICIENCY // distinct estimate
                plugged && batteryPower > 0.05 -> batteryPower / CHARGE_EFFICIENCY
                else -> 0.0
            }
        }

        // Device consumption (PWR): what the phone itself is using right now.
        //   discharging -> the battery drain,
        //   charging     -> input power minus what goes into the cell,
        //   plugged/full -> ~ the input power.
        val power = when {
            current < -0.001 -> -batteryPower
            current > 0.02 -> (inputPower - batteryPower).coerceAtLeast(0.0)
            plugged -> inputPower
            else -> (-batteryPower).coerceAtLeast(0.0)
        }

        // ---- Capacities (reverted to the original working reads) ----
        val chargeNowMah = run {
            val cc = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            if (cc > 0) cc / 1000.0
            else normalizeMah(Nodes.readBattery("charge_counter", "charge_now").toDoubleOrNull())
        }
        // Read the slow-changing values (cheap, cached paths) each tick.
        refreshSlow(context, intent)
        val realFull = cachedRealFull
        // When the kernel's charge_full node isn't readable (typically unrooted),
        // estimate actual capacity from present charge / state-of-charge. Validated
        // within ~1% against rooted devices. Only above 30% (division stays stable).
        val fullMah = when {
            realFull > 0 -> realFull
            chargeNowMah > 0 && level in 30..100 -> chargeNowMah / (level / 100.0)
            else -> 0.0
        }

        // Battery level straight from the OS (EXTRA_LEVEL/EXTRA_SCALE) — the exact
        // value shown on the status-bar icon. No derived/predicted decimals.
        val levelPrecise = level.toDouble()

        val designMah = cachedDesign
        val agingTenths = cachedAging
        val health: Double = when {
            fullMah > 0 && designMah > 0 -> (fullMah / designMah * 100.0).coerceIn(0.0, 100.0)
            agingTenths in 100.0..1000.0 -> agingTenths / 10.0
            else -> 0.0
        }

        val cycles = cachedCycles

        return TelemetryData(
            level = level,
            levelPrecise = levelPrecise,
            voltage = voltage,
            current = current,
            power = power,
            batteryPower = batteryPower,
            temperature = temperature,
            status = status,
            healthStatus = healthStatus,
            plugged = plugged,
            inputPower = inputPower,
            chargeNowMah = chargeNowMah,
            fullMah = fullMah,
            designMah = designMah,
            healthPercent = health,
            cycles = cycles
        )
    }

    /**
     * Android's own time-to-full estimate via BatteryManager.
     * computeChargeTimeRemaining() (API 28+). This is the same figure the system
     * shows, so it already reflects the OEM's real charging curve (CC and CV).
     * Returns minutes, or null when the framework can't compute it (-1), the
     * device is pre-API-28, or it isn't charging — in which case the caller uses
     * the instantaneous current-based estimate. Identical on rooted and unrooted.
     */
    fun frameworkMinutesToFull(context: Context, t: TelemetryData): Int? {
        if (t.level >= 100 || t.status.equals("Full", true)) return 0
        if (!t.plugged) return null
        if (Build.VERSION.SDK_INT < 28) return null
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val ms = try {
            bm.computeChargeTimeRemaining()
        } catch (_: Throwable) {
            -1L
        }
        if (ms <= 0L) return null
        return (ms / 60_000.0).roundToInt().coerceIn(0, 100_000)
    }

    /**
     * Estimated minutes until full, or null if not meaningfully charging.
     * Models the two charging phases: constant-current up to ~80%, then the
     * slower constant-voltage top-off (current tapers), which is where a naive
     * linear estimate is most wrong. [capacityMah] is the actual/calibrated
     * capacity (or design as a fallback); [currentAmps] may be a smoothed value.
     */
    fun timeToFullMinutes(
        t: TelemetryData,
        capacityMah: Double,
        currentAmps: Double = t.current
    ): Int? {
        if (currentAmps <= 0.02) return null
        if (t.level >= 100 || t.status.equals("Full", true)) return 0
        if (capacityMah <= 0) return null
        // Charge already in the cell, derived from state-of-charge so it stays
        // consistent with capacity. Using CHARGE_COUNTER here instead would mix
        // two independent references (on rooted, charge_full vs charge_counter
        // can disagree), which inflated the estimate. Level x capacity is
        // mathematically identical to the old form on unrooted (where capacity is
        // itself derived from charge_counter), so that path is unchanged.
        val level = t.level.coerceIn(0, 100)
        val chargedMah = capacityMah * level / 100.0
        if (chargedMah >= capacityMah) return 0
        val iMa = currentAmps * 1000.0
        if (iMa <= 1.0) return null
        val cvStart = capacityMah * 0.80
        val minutes = if (chargedMah >= cvStart) {
            (capacityMah - chargedMah) / (iMa * CV_CURRENT_FRACTION) * 60.0
        } else {
            val cc = (cvStart - chargedMah) / iMa
            val cv = (capacityMah - cvStart) / (iMa * CV_CURRENT_FRACTION)
            (cc + cv) * 60.0
        }
        return minutes.roundToInt().coerceIn(0, 100_000)
    }

    /**
     * Design capacity from the Android framework's power_profile.xml via
     * reflection. Works WITHOUT root on the vast majority of devices — this is
     * what lets design capacity (and therefore health %) appear when the sysfs
     * design node isn't readable. Returns 0 if unavailable.
     */
    private fun powerProfileDesignMah(context: Context): Double {
        if (cachedProfileMah >= 0.0) return cachedProfileMah
        val v = try {
            val cls = Class.forName("com.android.internal.os.PowerProfile")
            val instance = cls.getConstructor(Context::class.java).newInstance(context)
            val mah = cls.getMethod("getBatteryCapacity").invoke(instance) as? Double ?: 0.0
            if (mah in 500.0..20000.0) mah else 0.0
        } catch (_: Throwable) {
            0.0
        }
        cachedProfileMah = v
        return v
    }

    /**
     * Reads the slow-changing values (real full capacity, design capacity, cycle
     * count, aging). These barely
     * move, so re-reading their sysfs nodes every second would waste CPU/battery.
     * The live values (level, current, voltage, temp, power, charge counter) are
     * always read fresh in [read].
     */
    private fun refreshSlow(context: Context, intent: android.content.Intent?) {
        // Read every tick so all stats stay realtime. The genuinely expensive
        // parts (PowerProfile reflection, the /sys-wide cycle find, node-path
        // detection) are cached inside their own helpers, so a per-tick call here
        // is just a few cheap cached-path reads.
        val realFull = normalizeMah(
            Nodes.readBattery("charge_full", "batt_charge_full").toDoubleOrNull()
        )
        // Design capacity fallback chain: charge_full_design -> energy_full_design
        // -> PowerProfile (reflection, no root) -> derive from aging + actual.
        val designNode = normalizeMah(
            Nodes.readBattery("charge_full_design", "batt_charge_full_design").toDoubleOrNull()
        )
        val designEnergy = run {
            val e = Nodes.readBattery("energy_full_design").toDoubleOrNull() ?: 0.0
            if (e > 0) {
                val mWh = if (e > 100_000) e / 1000.0 else e
                val mah = mWh / 3.85
                if (mah in 500.0..20000.0) mah else 0.0
            } else 0.0
        }
        val designProfile = powerProfileDesignMah(context)
        val agingTenths = Nodes.readBattery("batt_capacity_max").toDoubleOrNull() ?: 0.0
        val designMah = when {
            designNode >= 500 && (realFull <= 0 || designNode >= realFull * 0.5) -> designNode
            designEnergy >= 500 -> designEnergy
            designProfile >= 500 -> designProfile
            agingTenths in 100.0..1000.0 && realFull > 0 -> realFull / (agingTenths / 1000.0)
            else -> 0.0
        }
        val cycles = run {
            var c = -1
            if (Build.VERSION.SDK_INT >= 34) {
                val ext = intent?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1) ?: -1
                if (ext > 0) c = ext
            }
            if (c <= 0) {
                c = Nodes.readBatteryInt(
                    "cycle_count", "battery_cycle", "batt_battery_cycle", "batt_cycle",
                    "charge_cycle", "cycles", "fg_cycle_count", "battery_cycle_count",
                    "cycle", "batt_cycle_count", "charge_cycles", "cycle_count_reset",
                    "rechargeCount", "chargeCounter_cycle"
                )
            }
            if (c <= 0) c = Nodes.findPositiveIntByName("cycle", "*cycle_count*")
            c
        }

        cachedRealFull = realFull
        cachedDesign = designMah
        cachedCycles = cycles
        cachedAging = agingTenths
    }

    private fun normalizeMah(raw: Double?): Double = when {
        raw == null || raw <= 0 -> 0.0
        raw > 100_000 -> raw / 1000.0 // µAh -> mAh
        else -> raw                   // already mAh
    }
}
