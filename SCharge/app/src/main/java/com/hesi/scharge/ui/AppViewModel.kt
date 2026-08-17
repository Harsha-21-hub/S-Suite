package com.hesi.scharge.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hesi.scharge.core.CalibrationManager
import com.hesi.scharge.core.CycleEstimator
import com.hesi.scharge.core.Nodes
import com.hesi.scharge.core.RootShell
import com.hesi.scharge.data.Prefs
import com.hesi.scharge.service.ChargeMonitorService
import com.hesi.scharge.service.SmartChargeService
import com.hesi.scharge.telemetry.TelemetryData
import com.hesi.scharge.telemetry.TelemetryReader
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val telemetry: TelemetryData = TelemetryData(),
    val rootAvailable: Boolean? = null, // null = still checking
    val smartEnabled: Boolean = false,
    val stopAt: Int = 95,
    val resumeAt: Int = 85,
    val overrideMode: String = Prefs.OVERRIDE_NONE,
    val chargingBlocked: Boolean = false,
    val voltHistory: List<Float> = emptyList(),
    val currHistory: List<Float> = emptyList(),
    val powHistory: List<Float> = emptyList(),
    // resolved capacities (manual / calibrated overrides applied)
    val effectiveDesignMah: Double = 0.0,
    val effectiveFullMah: Double = 0.0,
    val effectiveHealth: Double = 0.0,
    val designIsManual: Boolean = false,
    val fullIsCalibrated: Boolean = false,
    val fullIsProvisional: Boolean = false,
    // charge cycles (real when available, else estimated)
    val cyclesValue: Int = -1,
    val cyclesEstimated: Boolean = false,
    // charge-time prediction (minutes), null when not meaningfully charging
    val chargeTimeMin: Int? = null,
    // calibration
    val calibRunning: Boolean = false,
    val calibrated: Boolean = false,
    val calibEligible: Boolean = false,
    // onboarding
    val showTutorial: Boolean = false
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val volt = ArrayDeque<Float>()
    private val curr = ArrayDeque<Float>()
    private val pow = ArrayDeque<Float>()
    private val maxPoints = 120
    private val refreshing = AtomicBoolean(false)
    private val monitorStarted = AtomicBoolean(false)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val root = RootShell.isRootAvailable()
            _state.update {
                it.copy(
                    rootAvailable = root,
                    smartEnabled = Prefs.smartEnabled(ctx),
                    stopAt = Prefs.stopAt(ctx),
                    resumeAt = Prefs.resumeAt(ctx),
                    overrideMode = Prefs.overrideMode(ctx),
                    chargingBlocked = Prefs.chargingBlocked(ctx),
                    calibRunning = Prefs.calibRunning(ctx),
                    calibrated = CalibrationManager.isCalibrated(ctx),
                    showTutorial = !Prefs.tutorialShown(ctx)
                )
            }
            // Start the root control service only when it has real work — matches
            // the original: NO extra su calls at startup.
            if (root && (
                    Prefs.smartEnabled(ctx) ||
                        Prefs.overrideMode(ctx) != Prefs.OVERRIDE_NONE
                    )
            ) {
                SmartChargeService.start(ctx)
            }
        }
    }

    /** Called every 1 s while the UI is visible; never runs in background. */
    fun refresh() {
        if (!refreshing.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                val t = TelemetryReader.read(ctx)
                push(volt, t.voltage.toFloat())
                push(curr, t.current.toFloat())
                push(pow, t.power.toFloat())

                if (Prefs.calibRunning(ctx)) CalibrationManager.onTelemetry(ctx, t)
                CycleEstimator.onTelemetry(ctx, t)

                val manualDesign = Prefs.manualDesignMah(ctx)
                val calibFull = Prefs.calibratedFullMah(ctx)
                val design = if (manualDesign > 0) manualDesign.toDouble() else t.designMah
                val full = if (calibFull > 0) calibFull.toDouble() else t.fullMah
                val health = when {
                    full > 0 && design > 0 -> (full / design * 100.0).coerceIn(0.0, 100.0)
                    else -> t.healthPercent
                }
                val chargingCurr = curr.filter { it > 0.02f }
                val smoothCurr =
                    if (chargingCurr.size >= 3) chargingCurr.takeLast(15).map { it.toDouble() }.average()
                    else t.current
                val eta = TelemetryReader.frameworkMinutesToFull(ctx, t)
                    ?: TelemetryReader.timeToFullMinutes(t, if (full > 0) full else design, smoothCurr)

                val realCycles = t.cycles
                val cyclesEstimated = realCycles <= 0
                val cyclesValue =
                    if (realCycles > 0) realCycles else CycleEstimator.estimatedCycles(ctx, design, full)

                val root = _state.value.rootAvailable == true
                manageMonitor(ctx, t, root)

                _state.update {
                    it.copy(
                        telemetry = t,
                        smartEnabled = Prefs.smartEnabled(ctx),
                        overrideMode = Prefs.overrideMode(ctx),
                        chargingBlocked = Prefs.chargingBlocked(ctx),
                        voltHistory = volt.toList(),
                        currHistory = curr.toList(),
                        powHistory = pow.toList(),
                        effectiveDesignMah = design,
                        effectiveFullMah = full,
                        effectiveHealth = health,
                        designIsManual = manualDesign > 0,
                        fullIsCalibrated = calibFull > 0,
                        fullIsProvisional = Prefs.calibProvisional(ctx),
                        chargeTimeMin = eta,
                        cyclesValue = cyclesValue,
                        cyclesEstimated = cyclesEstimated,
                        calibRunning = Prefs.calibRunning(ctx),
                        calibrated = CalibrationManager.isCalibrated(ctx),
                        calibEligible = CalibrationManager.canStart(t)
                    )
                }
            } finally {
                refreshing.set(false)
            }
        }
    }

    /**
     * Starts / stops the no-root monitor (calibration progress + charge-time
     * notification). Not started when the root control service already owns the
     * notification (Smart Charge or an override active).
     */
    private fun manageMonitor(ctx: android.content.Context, t: TelemetryData, root: Boolean) {
        val smartOwnsNotif = root &&
            (Prefs.smartEnabled(ctx) || Prefs.overrideMode(ctx) != Prefs.OVERRIDE_NONE)
        val charging = t.plugged && t.level < 100 &&
            (t.status.equals("Charging", true) || t.current > 0.02)
        val need = !smartOwnsNotif && (Prefs.calibRunning(ctx) || charging)
        if (need) {
            if (monitorStarted.compareAndSet(false, true)) ChargeMonitorService.start(ctx)
        } else {
            if (monitorStarted.compareAndSet(true, false)) ChargeMonitorService.stop(ctx)
        }
    }

    /** Manual refresh button: re-detect every sysfs node and re-read all stats. */
    fun forceRefresh() {
        Nodes.clearCache()
        refresh()
    }

    /** Clears the live graphs so they start visualizing fresh on each app open. */
    fun resetGraphs() {
        volt.clear()
        curr.clear()
        pow.clear()
        _state.update {
            it.copy(
                voltHistory = emptyList(),
                currHistory = emptyList(),
                powHistory = emptyList()
            )
        }
    }

    private fun push(q: ArrayDeque<Float>, v: Float) {
        q.addLast(v)
        while (q.size > maxPoints) q.removeFirst()
    }

    fun setSmartEnabled(enabled: Boolean) {
        val ctx = getApplication<Application>()
        if (_state.value.rootAvailable != true) return
        Prefs.setSmartEnabled(ctx, enabled)
        _state.update { it.copy(smartEnabled = enabled) }
        if (enabled) {
            SmartChargeService.start(ctx)
        } else {
            SmartChargeService.start(ctx, SmartChargeService.ACTION_SHUTDOWN)
        }
    }

    fun setStopAt(value: Int) {
        val ctx = getApplication<Application>()
        val stop = value.coerceIn(50, 100)
        var resume = Prefs.resumeAt(ctx)
        if (resume >= stop) {
            resume = stop - 1
            Prefs.setResumeAt(ctx, resume)
        }
        Prefs.setStopAt(ctx, stop)
        _state.update { it.copy(stopAt = stop, resumeAt = resume) }
        reevaluate()
    }

    fun setResumeAt(value: Int) {
        val ctx = getApplication<Application>()
        val stop = Prefs.stopAt(ctx)
        val resume = value.coerceIn(30, stop - 1)
        Prefs.setResumeAt(ctx, resume)
        _state.update { it.copy(resumeAt = resume) }
        reevaluate()
    }

    fun forceCharge() = command(SmartChargeService.ACTION_FORCE_CHARGE)

    fun stopCharge() = command(SmartChargeService.ACTION_STOP_CHARGE)

    fun clearOverride() = command(SmartChargeService.ACTION_CLEAR_OVERRIDE)

    private fun command(action: String) {
        if (_state.value.rootAvailable != true) return
        SmartChargeService.start(getApplication(), action)
    }

    private fun reevaluate() {
        val ctx = getApplication<Application>()
        if (_state.value.rootAvailable == true &&
            (Prefs.smartEnabled(ctx) || Prefs.overrideMode(ctx) != Prefs.OVERRIDE_NONE)
        ) {
            SmartChargeService.start(ctx, SmartChargeService.ACTION_REEVALUATE)
        }
    }

    // ---- manual design capacity (0 clears override -> auto) ----
    fun setManualDesignMah(value: Int) {
        Prefs.setManualDesignMah(getApplication(), value)
        refresh()
    }

    // ---- calibration (works on rooted AND unrooted) ----
    fun startCalibration() {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val t = TelemetryReader.read(ctx)
            if (!CalibrationManager.canStart(t)) return@launch
            CalibrationManager.start(ctx, t)
            _state.update { it.copy(calibRunning = true) }
            val root = _state.value.rootAvailable == true
            if (root && (Prefs.smartEnabled(ctx) || Prefs.overrideMode(ctx) != Prefs.OVERRIDE_NONE)) {
                SmartChargeService.start(ctx) // it feeds calibration
            } else {
                if (monitorStarted.compareAndSet(false, true)) ChargeMonitorService.start(ctx)
            }
            refresh()
        }
    }

    fun cancelCalibration() {
        CalibrationManager.cancel(getApplication())
        _state.update { it.copy(calibRunning = false) }
        refresh()
    }

    fun clearCalibration() {
        Prefs.setCalibratedFullMah(getApplication(), 0)
        _state.update { it.copy(calibrated = false) }
        refresh()
    }

    // ---- tutorial ----
    fun openTutorial() = _state.update { it.copy(showTutorial = true) }

    fun dismissTutorial() {
        Prefs.setTutorialShown(getApplication(), true)
        _state.update { it.copy(showTutorial = false) }
    }
}
