package com.hesi.scharge.core

import android.content.Context
import android.os.SystemClock
import com.hesi.scharge.data.Prefs
import com.hesi.scharge.telemetry.TelemetryData
import kotlin.math.roundToInt

/**
 * Estimated charge-cycle count for devices whose kernel exposes no real cycle
 * node. Two parts, combined so it shows a sensible number immediately and then
 * refines over time:
 *
 *   estimate = initialWearCycles (frozen once from battery health) +
 *              observed equivalent-full-cycles since that freeze
 *
 * - initialWearCycles: derived from wear = (design - actual)/design mapped
 *   through a Li-ion aging curve (health -> cycles). Computed the first time a
 *   valid health is available (i.e. after calibration, or from the kernel's
 *   charge_full on rooted), then frozen so later charging doesn't double-count
 *   it. Re-frozen automatically if the design capacity changes.
 * - observed: charged mAh accumulated since the freeze, divided by the usable
 *   (actual, else design) capacity.
 *
 * This is an estimate, never a recovered hardware value.
 */
object CycleEstimator {

    private const val MIN_DT_MS = 250L
    private const val MAX_DT_MS = 60_000L      // ignore long gaps (app closed, reboot)
    private const val FLUSH_INTERVAL_MS = 30_000L

    // Typical Li-ion capacity retention vs equivalent full cycles.
    private val AGING = listOf(
        0 to 100.0, 50 to 99.5, 100 to 99.0, 150 to 98.0, 200 to 97.0,
        300 to 95.5, 400 to 93.5, 500 to 91.0, 650 to 87.0, 800 to 83.0, 1000 to 78.0
    )

    @Volatile
    private var lastTs = 0L

    @Volatile
    private var pendingMah = 0.0

    @Volatile
    private var lastFlush = 0L

    /** Call once per telemetry tick (UI loop and background monitor). */
    fun onTelemetry(context: Context, t: TelemetryData) {
        val now = SystemClock.elapsedRealtime()
        if (!t.plugged || t.current <= 0.02) {
            lastTs = 0L
            flush(context)
            return
        }
        val prev = lastTs
        lastTs = now
        if (prev in 1 until now) {
            val dt = now - prev
            if (dt in MIN_DT_MS..MAX_DT_MS) {
                // mAh = A * (dt_ms / 3_600_000) * 1000 = A * dt_ms / 3600
                pendingMah += t.current * dt / 3600.0
            }
        }
        if (now - lastFlush >= FLUSH_INTERVAL_MS) flush(context)
    }

    private fun flush(context: Context) {
        if (pendingMah >= 0.01) {
            Prefs.addChargeAccumMah(context, pendingMah)
            pendingMah = 0.0
        }
        lastFlush = SystemClock.elapsedRealtime()
    }

    /**
     * Estimated cycles, or -1 only when neither design nor actual capacity is
     * known. 0 is a valid result.
     */
    fun estimatedCycles(context: Context, designMah: Double, actualMah: Double): Int {
        val divisor = if (actualMah > 0) actualMah else designMah
        if (divisor <= 0) return -1

        val currentAccum = Prefs.chargeAccumMah(context) + pendingMah
        val haveHealth = actualMah > 0 && designMah > 0

        // Freeze the wear-based baseline once we have a valid health, or re-freeze
        // if the design capacity changed (e.g. user set it manually).
        if (haveHealth &&
            (Prefs.initialCycleEstimate(context) < 0 ||
                Prefs.designAtInitial(context) != designMah.roundToInt())
        ) {
            val health = (actualMah / designMah * 100.0)
            Prefs.setInitialCycleEstimate(context, cyclesFromHealth(health))
            Prefs.setAccumAtInitial(context, currentAccum)
            Prefs.setDesignAtInitial(context, designMah.roundToInt())
        }

        val initial = Prefs.initialCycleEstimate(context)
        if (initial < 0) {
            // No health yet: show only what we've observed so far.
            return (currentAccum / divisor).toInt().coerceAtLeast(0)
        }
        val observedSince =
            ((currentAccum - Prefs.accumAtInitial(context)) / divisor).coerceAtLeast(0.0)
        return (initial + observedSince).toInt().coerceAtLeast(0)
    }

    /** Maps battery health (%) to equivalent full cycles via the aging curve. */
    private fun cyclesFromHealth(health: Double): Int {
        if (health >= 100.0) return 0
        if (health <= AGING.last().second) return AGING.last().first
        for (i in 0 until AGING.size - 1) {
            val (c1, h1) = AGING[i]
            val (c2, h2) = AGING[i + 1]
            if (health <= h1 && health >= h2) {
                val frac = if (h1 == h2) 0.0 else (h1 - health) / (h1 - h2)
                return (c1 + frac * (c2 - c1)).roundToInt()
            }
        }
        return 0
    }
}
