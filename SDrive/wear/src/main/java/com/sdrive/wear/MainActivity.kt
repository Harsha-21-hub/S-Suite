package com.sdrive.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.sdrive.shared.AppState
import com.sdrive.shared.ui.SDriveApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent { SDriveApp(isWatch = true) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        AppState.resumeTick++
    }

    /** "adb shell am start ... --es open transfer" (PC tool) opens the Transfer screen. */
    private fun handle(i: Intent?) {
        if (i?.getStringExtra("open") == "transfer") AppState.openTransferTick++
    }
}