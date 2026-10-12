package com.sdrive.mobile

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import com.sdrive.shared.AppState
import com.sdrive.shared.Platform
import com.sdrive.shared.ui.SDriveApp

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Platform.optimizer = VideoShrinker
        Platform.adb = SDriveAdb
        handle(intent)
        setContent {
            SDriveApp(isWatch = false, incoming = incoming, onIncomingHandled = { incoming = emptyList() })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        AppState.resumeTick++
    }

    /** Files shared into SDrive from Gallery / Files / Quick Share etc. */
    private fun handle(i: Intent?) {
        if (i?.getStringExtra("open") == "transfer") AppState.openTransferTick++
        when (i?.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { incoming = listOf(it) }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { incoming = it.toList() }
        }
    }

    /** Picture-in-picture when you leave the app while a video plays. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (AppState.videoPlaying) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            } catch (_: Exception) {}
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        AppState.pip = isInPictureInPictureMode
    }
}