package com.hesi.scharge.data

import android.content.Context
import android.content.SharedPreferences

object Prefs {

    const val OVERRIDE_NONE = "none"
    const val OVERRIDE_FORCE = "force"
    const val OVERRIDE_STOP = "stop"

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("scharge_prefs", Context.MODE_PRIVATE)

    fun smartEnabled(c: Context): Boolean = sp(c).getBoolean("smart_enabled", false)
    fun setSmartEnabled(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("smart_enabled", v).apply()
    }

    fun stopAt(c: Context): Int = sp(c).getInt("stop_at", 95)
    fun setStopAt(c: Context, v: Int) {
        sp(c).edit().putInt("stop_at", v).apply()
    }

    fun resumeAt(c: Context): Int = sp(c).getInt("resume_at", 85)
    fun setResumeAt(c: Context, v: Int) {
        sp(c).edit().putInt("resume_at", v).apply()
    }

    fun overrideMode(c: Context): String =
        sp(c).getString("override_mode", OVERRIDE_NONE) ?: OVERRIDE_NONE

    fun setOverrideMode(c: Context, v: String) {
        sp(c).edit().putString("override_mode", v).apply()
    }

    fun controlPath(c: Context): String = sp(c).getString("control_path", "") ?: ""
    fun setControlPath(c: Context, v: String) {
        sp(c).edit().putString("control_path", v).apply()
    }

    fun chargingBlocked(c: Context): Boolean = sp(c).getBoolean("charging_blocked", false)
    fun setChargingBlocked(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("charging_blocked", v).apply()
    }

    // ---- manual design capacity override (0 = use auto-detected value) ----
    fun manualDesignMah(c: Context): Int = sp(c).getInt("manual_design_mah", 0)
    fun setManualDesignMah(c: Context, v: Int) {
        sp(c).edit().putInt("manual_design_mah", v.coerceAtLeast(0)).apply()
    }

    // ---- calibrated actual (full) capacity (0 = not calibrated) ----
    fun calibratedFullMah(c: Context): Int = sp(c).getInt("calibrated_full_mah", 0)
    fun setCalibratedFullMah(c: Context, v: Int) {
        sp(c).edit().putInt("calibrated_full_mah", v.coerceAtLeast(0)).apply()
    }

    // ---- calibration run state ----
    fun calibRunning(c: Context): Boolean = sp(c).getBoolean("calib_running", false)
    fun setCalibRunning(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("calib_running", v).apply()
    }

    fun calibStartCharge(c: Context): Float = sp(c).getFloat("calib_start_charge", 0f)
    fun setCalibStartCharge(c: Context, v: Float) {
        sp(c).edit().putFloat("calib_start_charge", v).apply()
    }

    fun calibStartLevel(c: Context): Int = sp(c).getInt("calib_start_level", 0)
    fun setCalibStartLevel(c: Context, v: Int) {
        sp(c).edit().putInt("calib_start_level", v).apply()
    }

    // Level at the moment the charger was pulled mid-calibration (-1 = not paused).
    fun calibPauseLevel(c: Context): Int = sp(c).getInt("calib_pause_level", -1)
    fun setCalibPauseLevel(c: Context, v: Int) {
        sp(c).edit().putInt("calib_pause_level", v).apply()
    }

    // True when calibratedFullMah is a provisional ESTIMATE (disrupted at >=85%),
    // refined to the real value if charging later reaches 100%.
    fun calibProvisional(c: Context): Boolean = sp(c).getBoolean("calib_provisional", false)
    fun setCalibProvisional(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("calib_provisional", v).apply()
    }

    // ---- accumulated charged mAh since first launch (for cycle estimate) ----
    // Stored as centi-mAh (mAh x 100) in a Long to avoid float drift over time.
    fun chargeAccumMah(c: Context): Double =
        sp(c).getLong("charge_accum_cmah", 0L) / 100.0

    fun addChargeAccumMah(c: Context, deltaMah: Double) {
        if (deltaMah <= 0) return
        val cur = sp(c).getLong("charge_accum_cmah", 0L)
        sp(c).edit().putLong("charge_accum_cmah", cur + Math.round(deltaMah * 100.0)).apply()
    }

    // Frozen wear-based initial cycle estimate (-1 = not computed yet), the
    // accumulated mAh at that moment, and the design capacity it was based on.
    fun initialCycleEstimate(c: Context): Int = sp(c).getInt("init_cycles", -1)
    fun setInitialCycleEstimate(c: Context, v: Int) {
        sp(c).edit().putInt("init_cycles", v).apply()
    }

    fun accumAtInitial(c: Context): Double = sp(c).getLong("accum_at_init_cmah", 0L) / 100.0
    fun setAccumAtInitial(c: Context, mah: Double) {
        sp(c).edit().putLong("accum_at_init_cmah", Math.round(mah * 100.0)).apply()
    }

    fun designAtInitial(c: Context): Int = sp(c).getInt("design_at_init", 0)
    fun setDesignAtInitial(c: Context, v: Int) {
        sp(c).edit().putInt("design_at_init", v).apply()
    }

    // ---- observed charging-rate anchor (time-to-full), wall-clock ms ----
    fun rateAnchorMs(c: Context): Long = sp(c).getLong("rate_anchor_ms", 0L)
    fun rateAnchorLevel(c: Context): Int = sp(c).getInt("rate_anchor_level", 0)
    fun setRateAnchor(c: Context, ms: Long, level: Int) {
        sp(c).edit().putLong("rate_anchor_ms", ms).putInt("rate_anchor_level", level).apply()
    }

    // ---- battery optimization exemption ----
    fun batteryOptGranted(c: Context): Boolean = sp(c).getBoolean("batt_opt_granted", false)
    fun setBatteryOptGranted(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("batt_opt_granted", v).apply()
    }

    // ---- onboarding ----
    fun tutorialShown(c: Context): Boolean = sp(c).getBoolean("tutorial_shown", false)
    fun setTutorialShown(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("tutorial_shown", v).apply()
    }
}
