package com.hesi.scharge.ui

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hesi.scharge.core.CalibrationManager
import com.hesi.scharge.data.Prefs
import com.hesi.scharge.service.formatMinutes
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------------------------------------------------------------- palette

private val Black = Color(0xFF000000)
private val Panel = Color(0xFF0E0E0E)
private val PanelLine = Color(0xFF1E1E1E)
private val DotDim = Color(0xFF333333)
private val White = Color(0xFFFFFFFF)
private val Grey = Color(0xFF8A8A8A)
private val Red = Color(0xFFE53935)
private val Mono = FontFamily.Monospace

private fun f3(v: Double): String = String.format(Locale.US, "%.3f", v)
private fun f2(v: Double): String = String.format(Locale.US, "%.2f", v)
private fun f1(v: Double): String = String.format(Locale.US, "%.1f", v)

// ---------------------------------------------------------------- root

@Composable
fun SChargeApp(vm: AppViewModel) {
    val state by vm.state.collectAsState()

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Black,
            surface = Panel,
            primary = Red,
            onBackground = White,
            onSurface = White
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Black) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Header(vm)
                Spacer(Modifier.height(12.dp))
                BatteryCard(state)
                Spacer(Modifier.height(12.dp))
                SmartCard(state, vm)
                Spacer(Modifier.height(12.dp))
                CalibrateCard(state, vm)
                Spacer(Modifier.height(12.dp))
                InfoGrid(state, vm)
                Spacer(Modifier.height(12.dp))
                GraphSection(state)
                Spacer(Modifier.height(24.dp))
            }
        }

        if (state.showTutorial) {
            TutorialOverlay(onDismiss = { vm.dismissTutorial() })
        }
    }
}

// ---------------------------------------------------------------- header

@Composable
private fun Header(vm: AppViewModel) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "S CHARGE",
                color = White,
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                letterSpacing = 6.sp
            )
            Spacer(Modifier.size(8.dp))
            Box(
                Modifier
                    .size(8.dp)
                    .background(Red, CircleShape)
            )
        }
        TextButton(
            onClick = { vm.openTutorial() },
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Text(text = "?", color = White, fontFamily = Mono, fontSize = 20.sp)
        }
        IconButton(
            onClick = { vm.forceRefresh() },
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh all stats",
                tint = White
            )
        }
    }
}

// ---------------------------------------------------------------- battery ring

@Composable
private fun BatteryCard(state: UiState) {
    val level = state.telemetry.level.coerceIn(0, 100)
    PanelBox {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(190.dp)) {
                    val radius = size.minDimension / 2f - 6.dp.toPx()
                    val dots = 50
                    // Red marker at the current % (12 o'clock = 0%). White for the
                    // charged portion, dim grey for what remains.
                    val marker = ((level / 100f) * dots).roundToInt().coerceIn(0, dots - 1)
                    for (i in 0 until dots) {
                        val angle = Math.toRadians(i * 360.0 / dots - 90.0)
                        val x = center.x + radius * cos(angle).toFloat()
                        val y = center.y + radius * sin(angle).toFloat()
                        val color = when {
                            i == marker -> Red
                            i < marker -> White
                            else -> DotDim
                        }
                        drawCircle(
                            color = color,
                            radius = if (i == marker) 4.5.dp.toPx() else 2.2.dp.toPx(),
                            center = Offset(x, y)
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$level%",
                        color = White,
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 44.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .background(Red, CircleShape)
                        )
                        Spacer(Modifier.size(6.dp))
                        val statusText =
                            if (state.chargingBlocked && state.telemetry.plugged) {
                                "PAUSED BY S CHARGE"
                            } else {
                                state.telemetry.status.uppercase(Locale.US)
                            }
                        Text(
                            text = statusText,
                            color = Grey,
                            fontFamily = Mono,
                            fontSize = 11.sp,
                            letterSpacing = 2.sp
                        )
                    }
                    val eta = state.chargeTimeMin
                    if (state.telemetry.status.equals("Charging", true) &&
                        !state.chargingBlocked && eta != null && eta > 0
                    ) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "FULL IN ${formatMinutes(eta)}",
                            color = Red,
                            fontFamily = Mono,
                            fontSize = 11.sp,
                            letterSpacing = 2.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                // PWR here is device / battery-side power (V_batt x I_batt).
                MiniStat("VOLT", "${f3(state.telemetry.voltage)} V", Modifier.weight(1f))
                MiniStat("CURR", "${f3(state.telemetry.current)} A", Modifier.weight(1f))
                MiniStat("PWR", "${f3(state.telemetry.power)} W", Modifier.weight(1f))
                MiniStat("TEMP", "${f1(state.telemetry.temperature)} °C", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = Grey,
            fontFamily = Mono,
            fontSize = 10.sp,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            color = White,
            fontFamily = Mono,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
    }
}

// ---------------------------------------------------------------- smart card

@Composable
private fun SmartCard(state: UiState, vm: AppViewModel) {
    val hasRoot = state.rootAvailable == true

    PanelBox {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SMART CHARGE",
                    color = White,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    letterSpacing = 3.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = state.smartEnabled && hasRoot,
                    onCheckedChange = { vm.setSmartEnabled(it) },
                    enabled = hasRoot,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = White,
                        checkedTrackColor = Red,
                        uncheckedThumbColor = Grey,
                        uncheckedTrackColor = PanelLine
                    )
                )
            }

            when (state.rootAvailable) {
                null -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "CHECKING ROOT ACCESS…",
                        color = Grey,
                        fontFamily = Mono,
                        fontSize = 11.sp,
                        letterSpacing = 2.sp
                    )
                }

                false -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "SMART CHARGE UNAVAILABLE — ROOT REQUIRED",
                        color = Red,
                        fontFamily = Mono,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp
                    )
                }

                true -> Unit
            }

            Spacer(Modifier.height(14.dp))

            ThresholdSlider(
                label = "STOP AT",
                value = state.stopAt,
                range = 50f..100f,
                enabled = hasRoot,
                onCommit = { vm.setStopAt(it) }
            )

            Spacer(Modifier.height(10.dp))

            ThresholdSlider(
                label = "RESUME AT",
                value = state.resumeAt,
                range = 30f..99f,
                enabled = hasRoot,
                onCommit = { vm.setResumeAt(it) }
            )

            if (state.smartEnabled && state.chargingBlocked &&
                state.overrideMode == Prefs.OVERRIDE_NONE
            ) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "CHARGING PAUSED — RESUMES AT ${state.resumeAt}%",
                    color = Grey,
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp
                )
            }

            Spacer(Modifier.height(14.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { vm.forceCharge() },
                    enabled = hasRoot,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text(
                        text = "FORCE CHARGE",
                        fontFamily = Mono,
                        fontSize = 12.sp,
                        letterSpacing = 2.sp,
                        color = if (hasRoot) White else Grey
                    )
                }
                Spacer(Modifier.size(12.dp))
                Button(
                    onClick = { vm.stopCharge() },
                    enabled = hasRoot,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Red,
                        disabledContainerColor = PanelLine
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text(
                        text = "STOP CHARGE",
                        fontFamily = Mono,
                        fontSize = 12.sp,
                        letterSpacing = 2.sp,
                        color = if (hasRoot) White else Grey
                    )
                }
            }

            if (state.overrideMode != Prefs.OVERRIDE_NONE) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "MANUAL ${state.overrideMode.uppercase(Locale.US)} ACTIVE — RESETS ON RE-PLUG",
                        color = Grey,
                        fontFamily = Mono,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.clearOverride() }) {
                        Text(
                            text = "CLEAR",
                            color = Red,
                            fontFamily = Mono,
                            fontSize = 11.sp,
                            letterSpacing = 2.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThresholdSlider(
    label: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onCommit: (Int) -> Unit
) {
    var drag by remember(value) { mutableStateOf(value.toFloat()) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label ${drag.roundToInt()}%",
            color = Grey,
            fontFamily = Mono,
            fontSize = 11.sp,
            letterSpacing = 2.sp
        )
        Slider(
            value = drag,
            onValueChange = { drag = it },
            onValueChangeFinished = { onCommit(drag.roundToInt()) },
            valueRange = range,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = White,
                activeTrackColor = Red,
                inactiveTrackColor = PanelLine,
                disabledThumbColor = Grey,
                disabledActiveTrackColor = PanelLine
            )
        )
    }
}

// ---------------------------------------------------------------- calibrate card

@Composable
private fun CalibrateCard(state: UiState, vm: AppViewModel) {
    PanelBox {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "CALIBRATION",
                    color = White,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    letterSpacing = 3.sp,
                    modifier = Modifier.weight(1f)
                )
                if (state.calibrated && !state.calibRunning) {
                    Text(
                        text = "${state.effectiveFullMah.roundToInt()} mAh",
                        color = White,
                        fontFamily = Mono,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            if (state.calibRunning) {
                Text(
                    text = "CALIBRATING — NOW AT ${state.telemetry.level}%. KEEP CHARGING; " +
                        "IT FINISHES AT 100% (OR 85%+ ONCE ENOUGH HAS GONE IN).",
                    color = Grey,
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "A brief unplug is fine — re-plug at the same % to resume.",
                    color = Grey,
                    fontFamily = Mono,
                    fontSize = 10.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { vm.cancelCalibration() },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Text("CANCEL", fontFamily = Mono, fontSize = 12.sp, color = White)
                }
            } else {
                Text(
                    text = if (state.calibrated) {
                        "Actual capacity is calibrated. To recalibrate, let the battery drop to " +
                            "${CalibrationManager.START_MAX_LEVEL}% or below, then plug in."
                    } else {
                        "Measures your battery's real (actual) capacity — this is how ACTUAL " +
                            "capacity and health work without root. It can only START at " +
                            "${CalibrationManager.START_MAX_LEVEL}% or below; then charge up (it " +
                            "finishes at 100%, or 85%+ once enough has gone in)."
                    },
                    color = Grey,
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(12.dp))
                if (state.calibEligible) {
                    Button(
                        onClick = { vm.startCalibration() },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Red),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                    ) {
                        Text(
                            text = if (state.calibrated) "RECALIBRATE" else "START CALIBRATION",
                            fontFamily = Mono,
                            fontSize = 12.sp,
                            letterSpacing = 2.sp,
                            color = White
                        )
                    }
                } else {
                    Text(
                        text = "AVAILABLE ONLY AT ${CalibrationManager.START_MAX_LEVEL}% OR BELOW, PLUGGED IN.",
                        color = Red,
                        fontFamily = Mono,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- info grid

@Composable
private fun InfoGrid(state: UiState, vm: AppViewModel) {
    val t = state.telemetry
    var showCapacityDialog by remember { mutableStateOf(false) }

    val healthText = when {
        state.fullIsCalibrated && state.effectiveHealth > 0 ->
            if (t.healthStatus != "Unknown") "${t.healthStatus}, ${f2(state.effectiveHealth)} %"
            else "${f2(state.effectiveHealth)} %"
        else -> "Need to Calibrate"
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Row 1: STATUS | CHARGE CYCLES
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            InfoTile(
                "STATUS",
                if (state.chargingBlocked && t.plugged) "Paused" else t.status,
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            Spacer(Modifier.size(12.dp))
            InfoTile(
                "CHARGE CYCLES",
                when {
                    state.cyclesValue < 0 -> "N/A"
                    state.cyclesEstimated -> "~${state.cyclesValue}"
                    else -> state.cyclesValue.toString()
                },
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                subLabel = if (state.cyclesEstimated && state.cyclesValue >= 0) "estimated" else null
            )
        }
        Spacer(Modifier.height(12.dp))
        // Row 2: INPUT PWR | BATTERY PWR
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            InfoTile(
                "INPUT PWR",
                if (t.inputPower > 0.01) "${f3(t.inputPower)} W" else "—",
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                subLabel = "from charger"
            )
            Spacer(Modifier.size(12.dp))
            InfoTile(
                "BATTERY PWR",
                "${f3(t.batteryPower)} W",
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                subLabel = if (t.batteryPower >= 0) "into cell" else "from cell"
            )
        }
        Spacer(Modifier.height(12.dp))
        // Row 3: BATTERY HEALTH | PRESENT CHARGE
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            InfoTile(
                "BATTERY HEALTH",
                healthText,
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                subLabel = if (state.fullIsCalibrated && state.effectiveHealth > 0)
                    "of design capacity" else "use calibration below"
            )
            Spacer(Modifier.size(12.dp))
            InfoTile(
                "PRESENT CHARGE",
                if (t.chargeNowMah > 0) "${t.chargeNowMah.roundToInt()} mAh" else "N/A",
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                subLabel = "live"
            )
        }
        Spacer(Modifier.height(12.dp))
        // Row 4: ACTUAL CAPACITY | DESIGN CAPACITY
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            // Trusted only from a real calibration; "Need to Calibrate" until then.
            InfoTile(
                "ACTUAL CAPACITY",
                when {
                    !state.fullIsCalibrated -> "Need to Calibrate"
                    state.fullIsProvisional -> "~${state.effectiveFullMah.roundToInt()} mAh"
                    else -> "${state.effectiveFullMah.roundToInt()} mAh"
                },
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                subLabel = when {
                    !state.fullIsCalibrated -> "use calibration below"
                    state.fullIsProvisional -> "estimated · charge to 100% for exact"
                    else -> "calibrated"
                }
            )
            Spacer(Modifier.size(12.dp))
            // Auto-detected (needs root); tap to enter/override manually.
            InfoTile(
                "DESIGN CAPACITY",
                if (state.effectiveDesignMah > 0) "${state.effectiveDesignMah.roundToInt()} mAh" else "TAP TO SET",
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                subLabel = if (state.designIsManual) "manual · tap to edit" else "auto · tap to edit",
                onClick = { showCapacityDialog = true }
            )
        }
    }

    if (showCapacityDialog) {
        DesignCapacityDialog(
            current = if (state.designIsManual) state.effectiveDesignMah.roundToInt() else 0,
            onSave = { vm.setManualDesignMah(it); showCapacityDialog = false },
            onAuto = { vm.setManualDesignMah(0); showCapacityDialog = false },
            onDismiss = { showCapacityDialog = false }
        )
    }
}

@Composable
private fun InfoTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    subLabel: String? = null,
    onClick: (() -> Unit)? = null
) {
    val base = modifier.background(Panel, RoundedCornerShape(16.dp))
    val boxModifier = if (onClick != null) base.clickable { onClick() } else base
    Box(modifier = boxModifier.padding(14.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 10.dp, height = 3.dp)
                        .background(Red, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = label,
                    color = Grey,
                    fontFamily = Mono,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                color = White,
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            if (subLabel != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subLabel.uppercase(Locale.US),
                    color = Grey,
                    fontFamily = Mono,
                    fontSize = 8.sp,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
private fun DesignCapacityDialog(
    current: Int,
    onSave: (Int) -> Unit,
    onAuto: () -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(if (current > 0) current.toString() else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        titleContentColor = White,
        textContentColor = Grey,
        title = { Text("Design capacity (mAh)", fontFamily = Mono, color = White) },
        text = {
            Column {
                Text(
                    "Auto-detection needs root. If it is wrong or blank (unrooted), enter your " +
                        "battery's rated design capacity here. Actual and present capacity stay " +
                        "automatic.",
                    fontFamily = Mono,
                    fontSize = 12.sp,
                    color = Grey
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { s -> text = s.filter { it.isDigit() }.take(6) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    placeholder = { Text("e.g. 4700", fontFamily = Mono, color = Grey) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.toIntOrNull() ?: 0) }) {
                Text("SAVE", color = Red, fontFamily = Mono)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onAuto) {
                    Text("AUTO", color = White, fontFamily = Mono)
                }
                TextButton(onClick = onDismiss) {
                    Text("CANCEL", color = Grey, fontFamily = Mono)
                }
            }
        }
    )
}

// ---------------------------------------------------------------- graphs

@Composable
private fun GraphSection(state: UiState) {
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (landscape) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Sparkline("VOLT", f3(state.telemetry.voltage), state.voltHistory, White, Modifier.weight(1f))
            Spacer(Modifier.size(12.dp))
            Sparkline("CURR", f3(state.telemetry.current), state.currHistory, Red, Modifier.weight(1f))
            Spacer(Modifier.size(12.dp))
            Sparkline("PWR", f3(state.telemetry.power), state.powHistory, Grey, Modifier.weight(1f))
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth()) {
            Sparkline("VOLT", f3(state.telemetry.voltage), state.voltHistory, White, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Sparkline("CURR", f3(state.telemetry.current), state.currHistory, Red, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Sparkline("PWR", f3(state.telemetry.power), state.powHistory, Grey, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Sparkline(
    label: String,
    valueText: String,
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Panel, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    color = Grey,
                    fontFamily = Mono,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = valueText,
                    color = White,
                    fontFamily = Mono,
                    fontSize = 10.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
            ) {
                if (values.size >= 2) {
                    val min = values.min()
                    val max = values.max()
                    val span = (max - min).let { if (it < 1e-6f) 1f else it }
                    val stepX = size.width / (values.size - 1)
                    val path = Path()
                    values.forEachIndexed { i, v ->
                        val x = i * stepX
                        val y = size.height * 0.92f -
                                ((v - min) / span) * size.height * 0.84f
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                    val lastY = size.height * 0.92f -
                            ((values.last() - min) / span) * size.height * 0.84f
                    drawCircle(
                        color = color,
                        radius = 3.dp.toPx(),
                        center = Offset(size.width, lastY)
                    )
                } else {
                    drawLine(
                        color = PanelLine,
                        start = Offset(0f, size.height / 2f),
                        end = Offset(size.width, size.height / 2f),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- tutorial

@Composable
private fun TutorialOverlay(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF2000000))
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .background(Panel, RoundedCornerShape(20.dp))
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "HOW S CHARGE WORKS",
                    color = White,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    letterSpacing = 2.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Text(text = "\u2715", color = White, fontFamily = Mono, fontSize = 18.sp)
                }
            }
            Spacer(Modifier.height(14.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                TutorialItem(
                    "Smart charge",
                    "Turn it on, then set STOP AT and RESUME AT. Charging pauses at the stop % and " +
                        "resumes once you fall to the resume %. Runs in the background. Needs root."
                )
                TutorialItem(
                    "Force / Stop charge",
                    "Manual overrides. FORCE keeps charging past the stop %, STOP holds it. Both " +
                        "reset automatically the next time you unplug and re-plug."
                )
                TutorialItem(
                    "Calibration",
                    "Measures your real (ACTUAL) capacity — works on rooted AND unrooted. It can " +
                        "start only at 15% or below; plug in and charge up (finishes at 100%, or 85%+ " +
                        "once enough has gone in). A brief unplug is fine if you re-plug at the same %."
                )
                TutorialItem(
                    "Capacities & health",
                    "ACTUAL CAPACITY and BATTERY HEALTH show \"Need to Calibrate\" until you run a " +
                        "calibration — then they show real numbers. DESIGN auto-detects (tap to set it " +
                        "manually if blank/wrong). PRESENT CHARGE is live and needs no root."
                )
                TutorialItem(
                    "Three power readings",
                    "PWR (under the ring) is what the phone itself is using. BATTERY PWR is the power " +
                        "into/out of the cell. INPUT PWR is what the charger delivers. All three differ."
                )
                TutorialItem(
                    "Charge time & notification",
                    "While charging, S Charge estimates time to full (accounting for the slower top-off " +
                        "near 100%) and shows it under the ring and in a notification that appears only " +
                        "while charging. Enable notifications and set battery use to Unrestricted."
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Red),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("GOT IT", fontFamily = Mono, fontSize = 13.sp, letterSpacing = 2.sp, color = White)
            }
        }
    }
}

@Composable
private fun TutorialItem(title: String, body: String) {
    Column(modifier = Modifier.padding(bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 10.dp, height = 3.dp)
                    .background(Red, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = title.uppercase(Locale.US),
                color = White,
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.sp
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(text = body, color = Grey, fontFamily = Mono, fontSize = 11.sp)
    }
}

// ---------------------------------------------------------------- helpers

@Composable
private fun PanelBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        content()
    }
}

// ---------------------------------------------------------------- battery optimization

@Composable
fun BatteryOptimizationDialog(onGrant: () -> Unit, onDismiss: () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Black,
            surface = Panel,
            primary = Red,
            onBackground = White,
            onSurface = White
        )
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Panel,
            titleContentColor = White,
            textContentColor = Grey,
            title = {
                Text("Allow background access", fontFamily = Mono, color = White)
            },
            text = {
                Text(
                    "S Charge needs unrestricted (background) battery access so charge " +
                        "monitoring, calibration and the charging notification are not killed by " +
                        "the system. You can still use the app without it, but background " +
                        "monitoring may stop when the app is closed.",
                    fontFamily = Mono,
                    fontSize = 12.sp,
                    color = Grey
                )
            },
            confirmButton = {
                TextButton(onClick = onGrant) {
                    Text("GRANT ACCESS", color = Red, fontFamily = Mono)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("LATER", color = Grey, fontFamily = Mono)
                }
            }
        )
    }
}
