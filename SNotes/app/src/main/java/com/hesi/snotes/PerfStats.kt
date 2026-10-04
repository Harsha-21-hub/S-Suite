package com.hesi.snotes

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.util.TypedValue
import android.view.FrameMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Floating "stats" card for the editor: app RAM (with Java / native / graphics
 * split), device RAM, app CPU, frames per second + jank, note storage and
 * device storage.
 *
 * It costs nothing while hidden: sampling runs on its own low-priority thread
 * only while the card is visible, once per second (the heavier memory
 * breakdown every 2 s, the storage walk every 10 s). The card can be dragged
 * anywhere and closed with its ×; the toolbar STATS button brings it back.
 */
class PerfStats(
    private val activity: Activity,
    private val host: FrameLayout,
    /** Strokes in the open note (read on the UI thread). */
    private val strokeCount: () -> Int,
    /** Called when the user closes the card with its × button. */
    private val onClosed: () -> Unit
) {
    private val d = activity.resources.displayMetrics.density
    private val ui = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var worker: Handler? = null

    private lateinit var card: LinearLayout
    private lateinit var body: TextView
    private var built = false
    var visible = false
        private set

    // ---- sampling state (worker thread) ----
    private var lastCpuMs = 0L
    private var lastWallMs = 0L
    private var tick = 0
    private var ramLine = "RAM   …"
    private var devRamLine = ""
    private var storageLine = "DISK  …"
    private var devDiskLine = ""
    private val cores = Runtime.getRuntime().availableProcessors()

    // ---- frame metrics (written on the metrics thread, read by the worker) ----
    @Volatile private var frames = 0
    @Volatile private var janky = 0
    private var frameListener: Window.OnFrameMetricsAvailableListener? = null

    // =====================================================================

    fun show() {
        if (visible) return
        visible = true
        if (!built) build()
        card.visibility = View.VISIBLE
        card.bringToFront()
        start()
    }

    fun hide() {
        if (!visible) return
        visible = false
        if (built) card.visibility = View.GONE
        stop()
    }

    fun toggle(): Boolean { if (visible) hide() else show(); return visible }

    /** Theme of the card follows the editor. */
    fun setDark(dark: Boolean) {
        if (!built) return
        (card.background as? GradientDrawable)?.apply {
            setColor(if (dark) 0xE6121212.toInt() else 0xEBFFFFFF.toInt())
            setStroke((1 * d).toInt().coerceAtLeast(1), if (dark) 0xFF2C2C2C.toInt() else 0xFFDADADA.toInt())
        }
        body.setTextColor(if (dark) 0xFFE8E8E8.toInt() else 0xFF1A1A1A.toInt())
    }

    fun release() { stop() }

    // =====================================================================

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun build() {
        built = true
        card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            elevation = 8 * d   // above the canvas + top bar, below the dropdown menus
            background = GradientDrawable().apply { cornerRadius = 14 * d }
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(activity).apply {
            text = "STATS"
            setTextColor(DrawingView.RED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Fonts.bold(activity), Typeface.BOLD)
            letterSpacing = 0.18f
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = TextView(activity).apply {
            text = "×"
            setTextColor(0xFF8A8A8A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding((10 * d).toInt(), 0, (2 * d).toInt(), 0)
            contentDescription = "Hide stats"
            setOnClickListener { hide(); onClosed() }
        }
        header.addView(close)
        card.addView(header)

        body = TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setLineSpacing(2 * d, 1f)
            text = "sampling…"
        }
        // fixed width: a changing number never re-measures the editor
        card.addView(body, LinearLayout.LayoutParams((236 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        )
        lp.leftMargin = (10 * d).toInt()
        lp.topMargin = (96 * d).toInt()
        host.addView(card, lp)

        // drag anywhere
        var dx = 0f; var dy = 0f; var moved = false; var sx = 0f; var sy = 0f
        card.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dx = v.x - ev.rawX; dy = v.y - ev.rawY; sx = ev.rawX; sy = ev.rawY; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(ev.rawX - sx) + abs(ev.rawY - sy) > 6 * d) moved = true
                    if (moved) {
                        val maxX = (host.width - v.width).toFloat().coerceAtLeast(0f)
                        val maxY = (host.height - v.height).toFloat().coerceAtLeast(0f)
                        v.x = (ev.rawX + dx).coerceIn(0f, maxX)
                        v.y = (ev.rawY + dy).coerceIn(0f, maxY)
                    }
                    true
                }
                else -> true
            }
        }
        setDark(false)
    }

    // =====================================================================
    //  Sampling
    // =====================================================================

    private fun start() {
        val t = HandlerThread("snotes-stats", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        thread = t
        val w = Handler(t.looper)
        worker = w
        tick = 0
        lastCpuMs = Process.getElapsedCpuTime()
        lastWallMs = SystemClock.elapsedRealtime()
        frames = 0; janky = 0
        val fl = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            frames++
            // a frame that took longer than ~1.5 refresh intervals is "janky"
            val budget = (1_000_000_000L / refreshRate()) * 3 / 2
            if (m.getMetric(FrameMetrics.TOTAL_DURATION) > budget) janky++
        }
        frameListener = fl
        runCatching { activity.window.addOnFrameMetricsAvailableListener(fl, w) }
        w.post(sampler)
    }

    private fun stop() {
        frameListener?.let { l -> runCatching { activity.window.removeOnFrameMetricsAvailableListener(l) } }
        frameListener = null
        worker?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        worker = null
    }

    private fun refreshRate(): Float =
        runCatching { activity.display?.refreshRate ?: 60f }.getOrDefault(60f).coerceAtLeast(30f)

    private val sampler = object : Runnable {
        override fun run() {
            val w = worker ?: return
            sample()
            w.postDelayed(this, 1000L)
        }
    }

    private fun mb(kb: Long) = kb / 1024.0

    private fun sample() {
        val now = SystemClock.elapsedRealtime()
        val cpu = Process.getElapsedCpuTime()
        val wall = (now - lastWallMs).coerceAtLeast(1)
        // % of the whole CPU (all cores), like a system monitor
        val cpuPct = 100.0 * (cpu - lastCpuMs) / wall / cores
        val oneCore = 100.0 * (cpu - lastCpuMs) / wall
        lastCpuMs = cpu; lastWallMs = now

        val f = frames; val j = janky
        frames = 0; janky = 0
        val fps = f * 1000.0 / wall

        if (tick % 2 == 0) sampleMemory()
        if (tick % 10 == 0) sampleStorage()
        tick++

        val strokes = strokeCount
        val text = String.format(
            Locale.US,
            "%s\n%s\nCPU   %4.1f%%  (%.0f%% of 1 core · %d cores)\nFPS   %3.0f   jank %d%s\n%s\n%s",
            ramLine, devRamLine, cpuPct, oneCore, cores,
            fps, j, if (f == 0) "   (idle)" else "",
            storageLine, devDiskLine
        )
        ui.post {
            if (!visible) return@post
            body.text = text + "\nINK   " + strokes() + " strokes"
        }
    }

    private fun sampleMemory() {
        runCatching {
            val mi = Debug.MemoryInfo()
            Debug.getMemoryInfo(mi)
            val total = mi.totalPss.toLong()
            fun stat(k: String) = mi.getMemoryStat(k)?.toLongOrNull() ?: 0L
            val java = stat("summary.java-heap")
            val native = stat("summary.native-heap")
            val gfx = stat("summary.graphics")
            ramLine = String.format(
                Locale.US, "RAM   %.0f MB  java %.0f · native %.0f · gpu %.0f",
                mb(total), mb(java), mb(native), mb(gfx)
            )
        }
        runCatching {
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val m = ActivityManager.MemoryInfo()
            am.getMemoryInfo(m)
            devRamLine = String.format(
                Locale.US, "      device %.1f / %.1f GB free",
                m.availMem / 1e9, m.totalMem / 1e9
            )
        }
    }

    private fun sampleStorage() {
        runCatching {
            var notes = 0L
            File(activity.filesDir, "notes").walkTopDown().forEach { if (it.isFile) notes += it.length() }
            var cache = 0L
            activity.cacheDir.walkTopDown().forEach { if (it.isFile) cache += it.length() }
            storageLine = "DISK  notes " + NoteStore.formatSize(notes) + " · cache " + NoteStore.formatSize(cache)
            val st = StatFs(activity.filesDir.absolutePath)
            devDiskLine = String.format(
                Locale.US, "      device %.1f / %.1f GB free",
                st.availableBytes / 1e9, st.totalBytes / 1e9
            )
        }
    }
}
