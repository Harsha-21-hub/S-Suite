package com.hesi.scharge.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hesi.scharge.data.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()
    private val showBatteryDialog = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        // Telemetry refreshes once a second while the screen is visible (STARTED),
        // so every value updates live. The graphs reset each time the app comes to
        // the foreground, then rebuild, so patterns are read from a clean start.
        // Nothing runs while the app is in the background — no cost when hidden.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.resetGraphs()
                while (isActive) {
                    vm.refresh()
                    delay(1000)
                }
            }
        }

        setContent {
            SChargeApp(vm)
            if (showBatteryDialog.value) {
                BatteryOptimizationDialog(
                    onGrant = { requestBatteryException() },
                    onDismiss = { showBatteryDialog.value = false }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Prompt on new install / reopen if the exemption isn't granted. Once
        // granted the value is stored, so it isn't re-checked or shown again.
        checkBatteryOptimization()
    }

    private fun checkBatteryOptimization() {
        if (Prefs.batteryOptGranted(this)) {
            showBatteryDialog.value = false
            return
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val granted = pm.isIgnoringBatteryOptimizations(packageName)
        if (granted) {
            Prefs.setBatteryOptGranted(this, true)
            showBatteryDialog.value = false
        } else {
            showBatteryDialog.value = true
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryException() {
        showBatteryDialog.value = false
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }
}
