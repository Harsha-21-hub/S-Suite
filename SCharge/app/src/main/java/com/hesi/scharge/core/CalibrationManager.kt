package com.hesi.scharge.core

import com.hesi.scharge.data.Prefs
import com.hesi.scharge.telemetry.TelemetryData
import android.content.Context

/**
 * Estimates the battery's ACTUAL (current full) capacity by coulomb counting a
 * charge session that STARTS at a low level (<=15%):
 *
 *   full = (chargeNow - startCharge) / ((level - startLevel) / 100)
 *
 * Present charge comes from BatteryManager's CHARGE_COUNTER (no root), so this
 * works on rooted and unrooted devices alike.
 *
 * Because the charge counter is ABSOLUTE, this measurement is inherently robust
 * to charge disruptions: whatever unplugging/re-plugging happens in between, the
 * result only depends on the start point and the current point. So there is no
 * fragile pause/resume tolerance — a disrupted session simply continues once
 * charging resumes.
 *
 * Cases handled:
 *  1. Start <=15%, disrupted, resumed with a 1-2% variation -> continues.
 *  2. Start <=15%, charged to 100% -> real calibrated capacity.
 *  3. Start <=15%, reaches/disrupted at >=85% and NOT re-plugged -> a provisional
 *     ESTIMATE is stored and displayed (with "~"); if later re-plugged to 100%
 *     it is refined to the real value.
 *  4. Any mix of the above.
 */
object CalibrationManager {

    const val START_MAX_LEVEL = 15          // calibration may only START at/below this
    private const val COMPLETE_MIN_LEVEL = 85
    private const val MIN_SPAN = 40         // min % charged for a trustworthy estimate
    private const val CAP_MIN = 500.0
    private const val CAP_MAX = 20000.0

    fun isCalibrated(c: Context): Boolean = Prefs.calibratedFullMah(c) > 0

    fun isRunning(c: Context): Boolean = Prefs.calibRunning(c)

    fun isProvisional(c: Context): Boolean = Prefs.calibProvisional(c)

    fun canStart(t: TelemetryData): Boolean =
        t.plugged && t.chargeNowMah > 0 && t.level in 1..START_MAX_LEVEL

    fun start(c: Context, t: TelemetryData): Boolean {
        if (!canStart(t)) return false
        Prefs.setCalibRunning(c, true)
        Prefs.setCalibProvisional(c, false)
        Prefs.setCalibStartCharge(c, t.chargeNowMah.toFloat())
        Prefs.setCalibStartLevel(c, t.level)
        return true
    }

    fun cancel(c: Context) {
        Prefs.setCalibRunning(c, false)
        Prefs.setCalibProvisional(c, false)
    }

    /** Feed the latest telemetry (from the UI tick and the background monitor). */
    fun onTelemetry(c: Context, t: TelemetryData) {
        val running = Prefs.calibRunning(c)
        val provisional = Prefs.calibProvisional(c)
        if (!running && !provisional) return

        val startCharge = Prefs.calibStartCharge(c).toDouble()
        val startLevel = Prefs.calibStartLevel(c)
        val nowCharge = t.chargeNowMah
        val nowLevel = t.level

        // If the battery dipped below the start point, re-anchor lower (keeps the
        // span measured from the true low; coulomb counting stays valid).
        if (running && nowCharge > 0 && nowLevel < startLevel) {
            Prefs.setCalibStartLevel(c, nowLevel)
            Prefs.setCalibStartCharge(c, nowCharge.toFloat())
            return
        }

        val span = nowLevel - startLevel
        val canCompute = nowCharge > startCharge && startCharge > 0 && span >= MIN_SPAN
        val estFull = if (canCompute) (nowCharge - startCharge) / (span / 100.0) else 0.0
        val full = t.status.equals("Full", true) || nowLevel >= 100

        if (running) {
            when {
                full && t.plugged -> {
                    val real = when {
                        estFull in CAP_MIN..CAP_MAX -> estFull
                        nowCharge in CAP_MIN..CAP_MAX -> nowCharge
                        else -> 0.0
                    }
                    if (real > 0) Prefs.setCalibratedFullMah(c, real.toInt())
                    Prefs.setCalibProvisional(c, false)
                    Prefs.setCalibRunning(c, false)
                }
                nowLevel >= COMPLETE_MIN_LEVEL && canCompute -> {
                    if (estFull in CAP_MIN..CAP_MAX) {
                        Prefs.setCalibratedFullMah(c, estFull.toInt())
                        Prefs.setCalibProvisional(c, true)
                    }
                    // Disrupted at >=85%: keep the estimate as provisional so a
                    // later charge to 100% can refine it to the real value.
                    if (!t.plugged) Prefs.setCalibRunning(c, false)
                }
                // else: charging below 85%, or unplugged below 85% -> keep running
                // and wait; the measurement survives the gap.
            }
        } else {
            // Provisional estimate stored: refine to the real value at 100%.
            if (full && t.plugged && estFull in CAP_MIN..CAP_MAX) {
                Prefs.setCalibratedFullMah(c, estFull.toInt())
                Prefs.setCalibProvisional(c, false)
            }
        }
    }
}
