package com.hesi.scharge.core

/**
 * Produces a 2-decimal battery percentage whose INTEGER part always equals the
 * OS level (the value shown on the status-bar icon), with the decimals
 * interpolated from the charge counter within the current 1% band.
 *
 * Why not just charge_counter / charge_full * 100? That fuel-gauge ratio
 * disagrees with the OS level by up to ~1% (different calibration), so it would
 * show 41.84% while the phone icon shows 42%. Anchoring the integer to the OS
 * level keeps the reading consistent with the system while still giving smooth,
 * real-time decimals that move as charge flows in or out.
 *
 * Stateless across the app otherwise; a tiny in-memory anchor is reset whenever
 * the OS level changes.
 */
object PreciseLevel {

    @Volatile private var anchorLevel = -1
    @Volatile private var anchorCharge = 0.0
    @Volatile private var atTop = false

    @Synchronized
    fun compute(osLevel: Int, chargeNowMah: Double, fullMah: Double, charging: Boolean): Double {
        if (osLevel >= 100) {
            anchorLevel = -1
            return 100.0
        }
        if (osLevel < 0) return 0.0
        if (fullMah <= 0.0 || chargeNowMah <= 0.0) return osLevel.toDouble()

        val perPercent = fullMah / 100.0
        if (osLevel != anchorLevel) {
            anchorLevel = osLevel
            anchorCharge = chargeNowMah
            // Entering the band from below (charging) starts at .00; from above
            // (discharging) starts near .99 and counts down.
            atTop = !charging
        }
        val deltaPct = (chargeNowMah - anchorCharge) / perPercent
        val base = if (atTop) 0.99 else 0.0
        val pos = (base + deltaPct).coerceIn(0.0, 0.99)
        return osLevel + pos
    }
}
