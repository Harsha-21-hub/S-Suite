package com.hesi.scharge.telemetry

data class TelemetryData(
    val level: Int = 0,               // battery %
    val levelPrecise: Double = 0.0,   // decimal battery % (live from charge counter)
    val voltage: Double = 0.0,        // V
    val current: Double = 0.0,        // A, positive while charging
    val power: Double = 0.0,          // W — device consumption (phone's live usage)
    val batteryPower: Double = 0.0,   // W — battery terminal power (V_batt x I_batt)
    val temperature: Double = 0.0,    // °C
    val status: String = "Unknown",
    val healthStatus: String = "Unknown", // Good / Overheat / Cold / Dead ...
    val plugged: Boolean = false,
    val inputPower: Double = 0.0,     // W measured at the charger input (or battery side)
    val chargeNowMah: Double = 0.0,   // present charge in the cell (mAh)
    val fullMah: Double = 0.0,        // actual (current) full capacity (mAh)
    val designMah: Double = 0.0,      // factory design capacity (mAh)
    val healthPercent: Double = 0.0,  // fullMah / designMah * 100
    val cycles: Int = -1              // charge cycle count, -1 if unavailable
)
