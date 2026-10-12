package com.sdrive.shared

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.SecureRandom

/** Process-wide application context, initialised by [InitProvider] before anything else runs. */
object App {
    const val NAME = "S Drive"
    /** Folder for received files on every device: Download/S-Drive (PC: Downloads\\S-Drive). */
    const val RECEIVE_FOLDER = "S-Drive"
    lateinit var ctx: Context
    val isWatch: Boolean by lazy { ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH) }
    fun toast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }
}

/** Light-weight UI-observable flags shared between activity and composables. */
object AppState {
    var resumeTick by mutableIntStateOf(0)
    /** Bumped when a dialog closes so the list underneath takes bezel focus back. */
    var focusTick by mutableIntStateOf(0)
    /** Bumped when the app is launched with extra open=transfer (PC tool): jump to the Transfer screen. */
    var openTransferTick by mutableIntStateOf(0)
    var pip by mutableStateOf(false)
    @Volatile var videoPlaying = false
}

class InitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        App.ctx = context!!.applicationContext
        return true
    }
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}

object Prefs {
    private val sp: SharedPreferences by lazy {
        App.ctx.getSharedPreferences("sdrive", Context.MODE_PRIVATE)
    }

    /** 6-digit PIN that other devices / the PC browser must enter to access this device. */
    val pin: String
        get() {
            sp.getString("pin", null)?.let { return it }
            return regenPin()
        }

    fun regenPin(): String {
        val p = (SecureRandom().nextInt(900_000) + 100_000).toString()
        sp.edit().putString("pin", p).apply()
        return p
    }

    var deviceName: String
        get() = sp.getString("name", null) ?: defaultName()
        set(v) = sp.edit().putString("name", v.trim().take(40)).apply()

    private fun defaultName(): String {
        val n = try {
            Settings.Global.getString(App.ctx.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (_: Exception) { null }
        return n?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    fun peerPin(host: String): String? = sp.getString("peer:$host", null)
    fun setPeerPin(host: String, pin: String?) {
        sp.edit().apply { if (pin == null) remove("peer:$host") else putString("peer:$host", pin) }.apply()
    }

    fun resume(path: String): Long = sp.getLong("pos:$path", 0L)
    fun setResume(path: String, pos: Long) {
        sp.edit().apply { if (pos <= 0) remove("pos:$path") else putLong("pos:$path", pos) }.apply()
    }

    var subtitleScale: Float
        get() = sp.getFloat("subScale", 1f)
        set(v) = sp.edit().putFloat("subScale", v).apply()

    /** Keep the screen awake while a transfer is running and the app is open. */
    var keepAwake: Boolean
        get() = sp.getBoolean("keepAwake", true)
        set(v) = sp.edit().putBoolean("keepAwake", v).apply()

    /** Transfer master switch (ON/OFF on the Transfer screen, also switchable from the PC page). */
    var transferOn: Boolean
        get() = sp.getBoolean("transferOn", true)
        set(v) = sp.edit().putBoolean("transferOn", v).apply()

    /** First-launch permission prompts already shown. */
    var autoAsked: Boolean
        get() = sp.getBoolean("autoAsked", false)
        set(v) = sp.edit().putBoolean("autoAsked", v).apply()

    /** Last "IP:port" used by the phone's ADB terminal. */
    var lastAdb: String
        get() = sp.getString("lastAdb", "") ?: ""
        set(v) = sp.edit().putString("lastAdb", v).apply()
}

/** Platform hooks the phone app plugs in (the watch leaves them null). */
interface VideoOptimizer {
    /** Re-encode [src] to a small watch-friendly H.264 file. Report progress via [h].note(). */
    suspend fun optimize(ctx: Context, src: Src, h: Transfers.Handle): File
}

/** ADB client (phone app only) used by the Terminal screen to reach the watch over Wireless debugging. */
interface AdbBackend {
    /** "host:port" while connected, null otherwise. */
    val status: StateFlow<String?>
    suspend fun pair(host: String, port: Int, code: String)
    suspend fun connect(host: String, port: Int)
    suspend fun disconnect()
    /** Runs one shell command on the watch and streams its output. Cancel the coroutine to stop it. */
    suspend fun shell(cmd: String, onOutput: (String) -> Unit)
    /** Streams an APK to the watch and installs it. */
    suspend fun install(apk: File, onOutput: (String) -> Unit)
}

object Platform {
    @Volatile var optimizer: VideoOptimizer? = null
    @Volatile var adb: AdbBackend? = null
}