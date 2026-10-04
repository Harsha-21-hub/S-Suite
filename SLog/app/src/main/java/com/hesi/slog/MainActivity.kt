package com.hesi.slog

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.IntentCompat

class MainActivity : ComponentActivity() {

    private val viewModel: SLogViewModel by viewModels()
    private val authViewModel: AuthViewModel by viewModels()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            }
        }
        EndOfDayReceiver.schedule(this)

        // Opened with a .slog / .csv file ("Open with S Log" or shared to S Log)
        if (savedInstanceState == null) handleIncomingFile(intent)

        setContent {
            // primary/onPrimary set so buttons are red with WHITE text (default was purple text)
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    onPrimary = Color.White,
                    background = Color.Black
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val authState by authViewModel.state.collectAsState()
                    when (val state = authState) {
                        AuthState.SignedOut -> {
                            LaunchedEffect(Unit) { viewModel.signOutCleanup() }
                            AuthScreen(authViewModel)
                        }
                        AuthState.Checking -> CheckingScreen()
                        is AuthState.NeedsPin -> {
                            LaunchedEffect(Unit) { viewModel.signOutCleanup() }
                            PinScreen(authViewModel, state.email)
                        }
                        is AuthState.SignedIn -> {
                            LaunchedEffect(state.uid) {
                                viewModel.start(state.uid, state.email)
                                // AI messages: a new week for each log once its week is over
                                AiMessages.scheduleWeeklyRefresh(applicationContext)
                            }
                            SLogScreen(
                                viewModel = viewModel,
                                account = state,
                                onSignOut = {
                                    viewModel.signOutCleanup()
                                    authViewModel.signOut()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingFile(intent)
    }

    /** Reads a .slog / .csv file another app opened with S Log and shows the import preview. */
    private fun handleIncomingFile(intent: Intent) {
        val uri: Uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        try {
            val text = contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                if (bytes.size > 5_000_000) throw IllegalArgumentException("File is too big.")
                String(bytes, Charsets.UTF_8)
            } ?: return
            viewModel.offerImport(text)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open file: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }
}
