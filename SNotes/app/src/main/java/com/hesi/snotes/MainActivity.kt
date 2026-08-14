package com.hesi.snotes

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity(), DrawingView.Listener {

    companion object {
        const val EXTRA_NOTE_ID = "note_id"

        /** Shown at the top of the presets list and used on a fresh install. */
        const val DEFAULT_PEN_WIDTH = 10f
        const val DEFAULT_ERASER_WIDTH = 36f
        const val DEFAULT_LASER_WIDTH = 6f
        const val DEFAULT_SHAPE_WIDTH = 8f

        val PALETTE = intArrayOf(
            0xFFD71921.toInt(), // Nothing red
            0xFF000000.toInt(),
            0xFFFFFFFF.toInt(),
            0xFF8E8E8E.toInt(),
            0xFF2979FF.toInt(),
            0xFF00C853.toInt(),
            0xFFFFD600.toInt(),
            0xFFFF6D00.toInt(),
            0xFFAA00FF.toInt(),
            0xFFFF4081.toInt(),
            0xFF00B8D4.toInt(),
            0xFF6D4C41.toInt()
        )
        private const val AUTOSAVE_DELAY = 1500L
    }

    private lateinit var drawing: DrawingView
    private lateinit var root: FrameLayout
    private lateinit var topBar: LinearLayout
    // Portrait-only controls (null in landscape).
    private var portraitToolkit: View? = null
    private var portraitOverflow: View? = null
    private var btnToolkitToggle: ImageButton? = null
    private var btnOverflow: ImageButton? = null
    private lateinit var panelPen: LinearLayout
    private lateinit var panelPalette: LinearLayout
    private lateinit var panelEraser: LinearLayout
    private lateinit var panelLaser: LinearLayout
    private lateinit var panelShapes: LinearLayout
    private lateinit var shapeGrid: LinearLayout
    private lateinit var shapeScroll: ScrollView
    private lateinit var txtShapesTitle: TextView
    private lateinit var txtShapeVal: TextView
    private lateinit var seekShape: SeekBar
    private lateinit var selectionBar: LinearLayout
    private lateinit var txtTitle: TextView
    private lateinit var txtPenVal: TextView
    private lateinit var txtEraserVal: TextView
    private lateinit var txtLaserVal: TextView

    // Direct, non-dialog object editor. It stays on the canvas like a normal
    // editing toolbar instead of interrupting the page with an AlertDialog.
    private lateinit var objectEditor: LinearLayout
    private lateinit var objectEditorTitle: TextView
    private lateinit var objectTextField: EditText
    private lateinit var objectSize: SeekBar
    private lateinit var objectSizeLabel: TextView
    private lateinit var objectDelete: TextView
    private lateinit var objectDone: TextView

    private lateinit var btnBack: ImageButton
    private lateinit var btnUndo: ImageButton
    private lateinit var btnRedo: ImageButton
    private lateinit var btnReset: ImageButton
    private lateinit var btnTheme: ImageButton
    private lateinit var btnShare: ImageButton
    private lateinit var btnHide: ImageButton
    private lateinit var btnShow: ImageButton
    private lateinit var btnPen: ImageButton
    private lateinit var btnEraser: ImageButton
    private lateinit var btnStrokeEraser: ImageButton
    private lateinit var btnLaser: ImageButton
    private lateinit var btnSelect: ImageButton
    private lateinit var btnShapes: ImageButton
    private lateinit var btnStylus: ImageButton
    private lateinit var btnPresets: ImageButton
    private lateinit var btnClear: ImageButton
    private lateinit var btnText: ImageButton
    private lateinit var btnImage: ImageButton

    private var currentToast: Toast? = null
    private lateinit var noteId: String
    private var dark = false
    private var focusHidden = false
    private var autoHidden = false
    private var loaded = false
    private var replacingImage: ImageObject? = null
    private var pendingTextX = 0f
    private var pendingTextY = 0f
    private var objectEditBefore: DocumentState? = null
    private var imageReplaceBefore: DocumentState? = null

    private val handler = Handler(Looper.getMainLooper())
    private val showRunnable = Runnable { if (!focusHidden) setBarsVisible(true) }
    private val autosave = Runnable { saveNote() }
    private val recentColors = ArrayList<Int>()

    // Requirement C4: system image picker for inserting images.
    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { onImagePicked(it) }
        }

    private val prefs by lazy { getSharedPreferences("snotes", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        noteId = intent.getStringExtra(EXTRA_NOTE_ID) ?: run { finish(); return }

        root = findViewById(R.id.root)
        drawing = findViewById(R.id.drawing)
        topBar = findViewById(R.id.topBar)
        // Portrait extras: present only in layout-port. Wire them if they exist.
        portraitToolkit = findViewById(R.id.portraitToolkit)
        portraitOverflow = findViewById(R.id.portraitOverflow)
        btnToolkitToggle = findViewById(R.id.btnToolkitToggle)
        btnOverflow = findViewById(R.id.btnOverflow)
        btnToolkitToggle?.setOnClickListener {
            hidePanels()
            portraitOverflow?.visibility = View.GONE
            portraitToolkit?.let { it.visibility = if (it.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        btnOverflow?.setOnClickListener {
            hidePanels()
            portraitToolkit?.visibility = View.GONE
            portraitOverflow?.let { it.visibility = if (it.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }

        panelPen = findViewById(R.id.panelPen)
        panelPalette = findViewById(R.id.panelPalette)
        panelEraser = findViewById(R.id.panelEraser)
        panelLaser = findViewById(R.id.panelLaser)
        panelShapes = findViewById(R.id.panelShapes)
        shapeGrid = findViewById(R.id.shapeGrid)
        shapeScroll = findViewById(R.id.shapeScroll)
        txtShapesTitle = findViewById(R.id.txtShapesTitle)
        txtShapeVal = findViewById(R.id.txtShapeVal)
        seekShape = findViewById(R.id.seekShape)
        selectionBar = findViewById(R.id.selectionBar)
        txtTitle = findViewById(R.id.txtTitle)
        txtPenVal = findViewById(R.id.txtPenVal)
        txtEraserVal = findViewById(R.id.txtEraserVal)
        txtLaserVal = findViewById(R.id.txtLaserVal)
        objectEditor = findViewById(R.id.objectEditor)
        objectEditorTitle = findViewById(R.id.objectEditorTitle)
        objectTextField = findViewById(R.id.objectTextField)
        objectSize = findViewById(R.id.objectSize)
        objectSizeLabel = findViewById(R.id.objectSizeLabel)
        objectDelete = findViewById(R.id.objectDelete)
        objectDone = findViewById(R.id.objectDone)

        btnBack = findViewById(R.id.btnBack)
        btnUndo = findViewById(R.id.btnUndo)
        btnRedo = findViewById(R.id.btnRedo)
        btnReset = findViewById(R.id.btnReset)
        btnTheme = findViewById(R.id.btnTheme)
        btnShare = findViewById(R.id.btnShare)
        btnHide = findViewById(R.id.btnHide)
        btnShow = findViewById(R.id.btnShow)
        btnPen = findViewById(R.id.btnPen)
        btnEraser = findViewById(R.id.btnEraser)
        btnStrokeEraser = findViewById(R.id.btnStrokeEraser)
        btnLaser = findViewById(R.id.btnLaser)
        btnSelect = findViewById(R.id.btnSelect)
        btnShapes = findViewById(R.id.btnShapes)
        btnStylus = findViewById(R.id.btnStylus)
        btnPresets = findViewById(R.id.btnPresets)
        btnClear = findViewById(R.id.btnClear)
        btnText = findViewById(R.id.btnText)
        btnImage = findViewById(R.id.btnImage)

        drawing.listener = this
        drawing.onObjectsChanged = { scheduleSave() }

        // ---- Restore state ----
        dark = prefs.getBoolean("dark", false)
        drawing.penWidth = prefs.getFloat("pen_w", DEFAULT_PEN_WIDTH)
        drawing.eraserWidth = prefs.getFloat("eraser_w", DEFAULT_ERASER_WIDTH)
        drawing.laserWidth = prefs.getFloat("laser_w", DEFAULT_LASER_WIDTH)
        drawing.stylusOnly = prefs.getBoolean("stylus_only", false)
        drawing.penColor = Color.BLACK
        runCatching {
            drawing.shapeKind = Shapes.Kind.valueOf(prefs.getString("shape_kind", "RECT")!!)
        }
        prefs.getString("recent_colors", "")!!.split(",")
            .mapNotNull { it.toIntOrNull() }
            .forEach { recentColors.add(it) }

        txtTitle.text = NoteStore.readName(this, noteId)
        drawing.pageMode = NoteStore.readPageMode(this, noteId)

        // Requirement D5 - notes opened slowly because the whole file was parsed
        // and every stroke outline was baked (seal/buildOutline) on the MAIN
        // thread inside onCreate, blocking the first frame. Now the editor opens
        // instantly and the strokes are parsed on a background thread, then handed
        // back to the view. `loaded` guards autosave so a quick open+close can
        // never overwrite the real note with an empty canvas.
        Thread {
            val data = NoteStore.load(this, noteId)
            val objs = NoteStore.loadObjects(this, noteId)
            runOnUiThread {
                data?.let { drawing.loadNote(it.first, it.second) }
                drawing.setObjects(objs.first, objs.second)
                if (dark) drawing.setDarkTheme(true)
                drawing.restoreViewport(
                    prefs.getFloat("view_${noteId}_pan_x", Float.NaN),
                    prefs.getFloat("view_${noteId}_pan_y", Float.NaN),
                    prefs.getFloat("view_${noteId}_zoom", Float.NaN)
                )
                loaded = true
            }
        }.start()

        buildPalette()
        buildShapePills()
        wireButtons()
        wireSliders()
        applyTheme()
        selectTool(DrawingView.Tool.PEN, silent = true)
        onHistory(canUndo = false, canRedo = false)
        Fonts.apply(root)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(autosave)
        saveNote()
        saveViewport()
    }

    override fun onDestroy() {
        handler.removeCallbacks(autosave)
        handler.removeCallbacks(showRunnable)
        super.onDestroy()
    }

    private fun saveViewport() {
        if (!loaded) return
        val state = drawing.viewportState()
        prefs.edit()
            .putFloat("view_${noteId}_pan_x", state.first)
            .putFloat("view_${noteId}_pan_y", state.second)
            .putFloat("view_${noteId}_zoom", state.third)
            .apply()
    }

    /** Writes are debounced, so the pen never waits on the disk. */
    private fun scheduleSave() {
        handler.removeCallbacks(autosave)
        handler.postDelayed(autosave, AUTOSAVE_DELAY)
    }

    private fun saveNote() {
        if (!loaded) return   // never overwrite the note before it has loaded (D5)
        NoteStore.saveAsync(this, noteId, drawing.page, lightFormStrokes())
        NoteStore.saveObjects(this, noteId, drawing.textObjects, drawing.imageObjects)
        prefs.edit()
            .putFloat("pen_w", drawing.penWidth)
            .putFloat("eraser_w", drawing.eraserWidth)
            .putFloat("laser_w", drawing.laserWidth)
            .putBoolean("stylus_only", drawing.stylusOnly)
            .putString("shape_kind", drawing.shapeKind.name)
            .putString("recent_colors", recentColors.joinToString(","))
            .apply()
    }

    /** Notes are stored theme-independent (black ink on white). */
    private fun lightFormStrokes(): List<Stroke> {
        if (!dark) return drawing.strokes
        return drawing.strokes.map { s ->
            if (!s.eraser && (s.color == Color.WHITE || s.color == Color.BLACK)) {
                val flipped = if (s.color == Color.WHITE) Color.BLACK else Color.WHITE
                Stroke(s.points, flipped, s.width, s.eraser, s.straight, s.pressures)
            } else s
        }
    }


    // =====================================================================
    //  Wiring
    // =====================================================================

    private fun wireButtons() {
        btnBack.setOnClickListener { finish() }
        // undo()/redo() already reschedule the save via onHistory(), so there is
        // no second scheduleSave() here (requirement 11).
        btnUndo.setOnClickListener { drawing.undo() }
        btnRedo.setOnClickListener { drawing.redo() }
        btnReset.setOnClickListener {
            drawing.resetZoom()
            hidePanels()
            showLabel("Zoom Reset")
        }

        btnTheme.setOnClickListener {
            dark = !dark
            prefs.edit().putBoolean("dark", dark).apply()
            drawing.setDarkTheme(dark)
            applyTheme()
            showLabel(if (dark) "Dark Mode" else "Light Mode")
        }

        btnShare.setOnClickListener { showShareDialog() }

        btnHide.setOnClickListener {
            focusHidden = true
            setBarsVisible(false)
            btnShow.visibility = View.VISIBLE
            showLabel("Focus Mode")
        }
        btnShow.setOnClickListener {
            focusHidden = false
            btnShow.visibility = View.GONE
            setBarsVisible(true)
        }

        // Tap selects the tool; tapping the active tool opens its dropdown,
        // positioned directly beneath the tool it belongs to (requirement 12).
        btnPen.setOnClickListener {
            if (drawing.tool == DrawingView.Tool.PEN) openPanelUnder(panelPen, btnPen)
            else { selectTool(DrawingView.Tool.PEN); hidePanels() }
        }
        btnEraser.setOnClickListener {
            if (drawing.tool == DrawingView.Tool.ERASER) openPanelUnder(panelEraser, btnEraser)
            else { selectTool(DrawingView.Tool.ERASER); hidePanels() }
        }
        btnStrokeEraser.setOnClickListener {
            if (drawing.tool == DrawingView.Tool.STROKE_ERASER) openPanelUnder(panelEraser, btnStrokeEraser)
            else { selectTool(DrawingView.Tool.STROKE_ERASER); hidePanels() }
        }
        // The laser has its own size dropdown, same tap-again gesture.
        btnLaser.setOnClickListener {
            if (drawing.tool == DrawingView.Tool.LASER) openPanelUnder(panelLaser, btnLaser)
            else { selectTool(DrawingView.Tool.LASER); hidePanels() }
        }
        btnSelect.setOnClickListener { selectTool(DrawingView.Tool.SELECT); hidePanels() }
        btnShapes.setOnClickListener { openPanelUnder(panelShapes, btnShapes) }

        // Text insertion is a real canvas tool: select TEXT, then tap anywhere on
        // the page to create a Paint-style text box at that exact position.
        btnText.setOnClickListener { hidePanels(); selectTool(DrawingView.Tool.TEXT) }
        btnImage.setOnClickListener { hidePanels(); hideObjectEditor(); imagePicker.launch("image/*") }

        // Palm rejection is its own toolbar button now, not a chip in the pen panel.
        btnStylus.setOnClickListener {
            drawing.stylusOnly = !drawing.stylusOnly
            tintTools()
            showLabel(
                if (drawing.stylusOnly) "Stylus Only: ON  ·  finger pans"
                else "Stylus Only: OFF  ·  finger draws"
            )
        }

        btnPresets.setOnClickListener { showPresetsDialog() }

        btnClear.setOnClickListener {
            Fonts.show(
                themedDialog()
                    .setMessage(R.string.clear_all_q)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        drawing.clearAll()
                        scheduleSave()
                        showLabel("Canvas Cleared")
                    }
                    .setNegativeButton(R.string.cancel, null)
            )
        }

        findViewById<TextView>(R.id.btnSelDelete).setOnClickListener {
            drawing.deleteSelection(); scheduleSave()
        }
        findViewById<TextView>(R.id.btnSelDone).setOnClickListener { drawing.clearSelection() }
    }

    @SuppressLint("SetTextI18n")
    private fun wireSliders() {
        val seekPen = findViewById<SeekBar>(R.id.seekPen)
        val seekEraser = findViewById<SeekBar>(R.id.seekEraser)
        val seekLaser = findViewById<SeekBar>(R.id.seekLaser)
        seekPen.progress = drawing.penWidth.toInt().coerceIn(1, 40)
        seekEraser.progress = drawing.eraserWidth.toInt().coerceIn(8, 120)
        seekLaser.progress = drawing.laserWidth.toInt().coerceIn(2, 24)
        txtPenVal.text = "SIZE ${seekPen.progress}"
        txtEraserVal.text = "SIZE ${seekEraser.progress}"
        txtLaserVal.text = "SIZE ${seekLaser.progress}"

        seekPen.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                drawing.penWidth = p.toFloat()
                txtPenVal.text = "SIZE $p"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        seekEraser.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                drawing.eraserWidth = p.toFloat()
                txtEraserVal.text = "SIZE $p"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        seekLaser.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                drawing.laserWidth = p.toFloat()
                txtLaserVal.text = "SIZE $p"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Requirement 1: shape thickness, independent of the pen size (min 2).
        seekShape.progress = (drawing.shapeWidth.toInt() - 2).coerceIn(0, seekShape.max)
        txtShapeVal.text = "SHAPE SIZE ${seekShape.progress + 2}"
        seekShape.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                drawing.shapeWidth = (p + 2).toFloat()
                txtShapeVal.text = "SHAPE SIZE ${p + 2}"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // =====================================================================
    //  Palette
    // =====================================================================

    private fun buildPalette() {
        panelPalette.removeAllViews()
        val d = resources.displayMetrics.density
        val size = (40 * d).toInt()
        val gap = (6 * d).toInt()   // fits 6 columns inside the portrait panel too

        fun swatch(color: Int): View {
            val v = View(this)
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            g.setColor(color)
            g.setStroke(
                (if (color == drawing.penColor) 3f * d else 1.5f * d).toInt(),
                if (color == drawing.penColor) DrawingView.RED else 0x66808080
            )
            v.background = g
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = gap
            v.layoutParams = lp
            v.setOnClickListener { pickColor(color) }
            return v
        }

        var row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for ((i, c) in PALETTE.withIndex()) {
            if (i > 0 && i % 6 == 0) {
                panelPalette.addView(row)
                row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.setPadding(0, gap, 0, 0)
            }
            row.addView(swatch(c))
        }
        panelPalette.addView(row)

        if (recentColors.isNotEmpty()) {
            val label = TextView(this)
            label.text = getString(R.string.recent)
            label.textSize = 11f
            label.typeface = Fonts.regular(this)
            label.letterSpacing = 0.2f
            label.setPadding(0, gap, 0, gap / 2)
            label.setTextColor(if (dark) 0xFF9A9A9A.toInt() else 0xFF6A6A6A.toInt())
            panelPalette.addView(label)
            val recRow = LinearLayout(this)
            recRow.orientation = LinearLayout.HORIZONTAL
            for (c in recentColors) recRow.addView(swatch(c))
            panelPalette.addView(recRow)
        }
    }

    private fun pickColor(color: Int) {
        drawing.penColor = color
        if (drawing.tool != DrawingView.Tool.PEN && drawing.tool != DrawingView.Tool.SHAPE && drawing.tool != DrawingView.Tool.TEXT) {
            selectTool(DrawingView.Tool.PEN, silent = true)
        }
        recentColors.remove(color)
        recentColors.add(0, color)
        while (recentColors.size > 8) recentColors.removeAt(recentColors.size - 1)
        tintTools()
        buildPalette()
        showLabel("Color Selected")
    }

    // =====================================================================
    //  Manual shapes
    // =====================================================================

    private val shapeItems = ArrayList<LinearLayout>()
    private val shapePreviewViews = ArrayList<ImageView>()

    private fun buildShapePills() {
        shapeGrid.removeAllViews()
        shapeItems.clear()
        shapePreviewViews.clear()
        val d = resources.displayMetrics.density
        val box = (44 * d).toInt()
        val perRow = 4
        var row: LinearLayout? = null
        for ((i, k) in Shapes.ALL.withIndex()) {
            if (i % perRow == 0) {
                row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                shapeGrid.addView(row)
            }
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER_HORIZONTAL
            item.setPadding((6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt())
            // equal-width cells so the grid stays tidy
            item.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            val icon = ImageView(this)
            icon.layoutParams = LinearLayout.LayoutParams(box, box)
            icon.setImageBitmap(makeShapeIcon(k, box))
            item.addView(icon)

            val name = TextView(this)
            name.text = Shapes.label(k)
            name.textSize = 9f
            name.typeface = Fonts.bold(this)
            name.letterSpacing = 0.03f
            name.gravity = Gravity.CENTER
            val nameLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            nameLp.topMargin = (4 * d).toInt()
            name.layoutParams = nameLp
            item.addView(name)

            item.setOnClickListener {
                drawing.shapeKind = k
                selectTool(DrawingView.Tool.SHAPE, silent = true)
                tintShapePills()
                tintTools()
                showLabel("Shape: ${Shapes.label(k)}")
            }
            shapeItems.add(item)
            shapePreviewViews.add(icon)
            row?.addView(item)
        }
        tintShapePills()
    }

    /** Renders a shape into a small themed bitmap for the chooser. */
    private fun makeShapeIcon(kind: Shapes.Kind, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val pad = sizePx * 0.24f
        // build with a diagonal box so every shape draws with real extent
        val pts = Shapes.build(kind, pad, pad, sizePx - pad, sizePx - pad)
        val path = Path()
        if (pts.size >= 2) {
            path.moveTo(pts[0], pts[1])
            var i = 2
            while (i < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.055f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = if (dark) Color.WHITE else Color.BLACK
        }
        c.drawPath(path, p)
        return bmp
    }

    private fun tintShapePills() {
        for ((i, item) in shapeItems.withIndex()) {
            val on = drawing.tool == DrawingView.Tool.SHAPE && Shapes.ALL[i] == drawing.shapeKind
            item.setBackgroundColor(if (on) 0x33D71921 else Color.TRANSPARENT)
            (item.getChildAt(1) as? TextView)?.setTextColor(if (on) DrawingView.RED else fg())
        }
    }

    // =====================================================================
    //  Tools / dropdown panels
    // =====================================================================

    private fun selectTool(t: DrawingView.Tool, silent: Boolean = false) {
        if (t != DrawingView.Tool.SELECT) hideObjectEditor()
        drawing.tool = t
        tintTools()
        tintShapePills()
        if (silent) return
        val label = when (t) {
            DrawingView.Tool.PEN -> "Pen"
            DrawingView.Tool.ERASER -> "Standard Eraser"
            DrawingView.Tool.STROKE_ERASER -> "Stroke Eraser"
            DrawingView.Tool.LASER -> "Laser Pointer"
            DrawingView.Tool.SELECT -> "Select Tool"
            DrawingView.Tool.SHAPE -> "Shape: ${Shapes.label(drawing.shapeKind)}"
            DrawingView.Tool.TEXT -> "Text Tool"
        }
        showLabel(label)
    }

    private fun hidePanels() {
        panelPen.visibility = View.GONE
        panelEraser.visibility = View.GONE
        panelLaser.visibility = View.GONE
        panelShapes.visibility = View.GONE
    }

    private fun anyPanelOpen() =
        panelPen.visibility == View.VISIBLE || panelEraser.visibility == View.VISIBLE ||
                panelLaser.visibility == View.VISIBLE || panelShapes.visibility == View.VISIBLE

    /**
     * Requirement 12 - the dropdown opens directly under the tool that owns it
     * instead of spanning the whole bar, and requirement 4 - it fades and slides
     * in rather than popping.
     */
    private fun openPanelUnder(panel: View, anchor: View) {
        if (panel.visibility == View.VISIBLE) { hidePanels(); return }
        hidePanels()
        // Measure first (width is wrap/fixed), then position horizontally.
        panel.visibility = View.INVISIBLE
        panel.post {
            val a = IntArray(2); anchor.getLocationInWindow(a)
            val r = IntArray(2); root.getLocationInWindow(r)
            val anchorCenterX = (a[0] - r[0]) + anchor.width / 2f
            val pad = 8f * resources.displayMetrics.density
            val maxX = root.width - panel.width - pad
            val x = (anchorCenterX - panel.width / 2f).coerceIn(pad, maxX.coerceAtLeast(pad))
            panel.translationX = x
            panel.visibility = View.VISIBLE
            // fade + slight downward slide in
            val d = resources.displayMetrics.density
            panel.alpha = 0f
            panel.translationY = -10f * d
            panel.animate().alpha(1f).translationY(0f).setDuration(160).start()
        }
    }

    // =====================================================================
    //  Presets
    // =====================================================================

    private fun showPresetsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_presets, null)
        val group = view.findViewById<RadioGroup>(R.id.presetGroup)
        val load = view.findViewById<TextView>(R.id.presetSave)
        val delete = view.findViewById<TextView>(R.id.presetDelete)
        val cancel = view.findViewById<TextView>(R.id.presetCancel)
        val presets = PresetStore.list(this)

        val defaultRb = RadioButton(this).apply {
            id = View.generateViewId(); text = getString(R.string.preset_default)
            setTextColor(fg()); textSize = 14f; typeface = Fonts.regular(this@MainActivity)
            buttonTintList = android.content.res.ColorStateList.valueOf(DrawingView.RED)
            isChecked = true
        }
        group.addView(defaultRb)
        for (p in presets) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId(); tag = p.name; text = p.name
                setTextColor(fg()); textSize = 14f; typeface = Fonts.regular(this@MainActivity)
                buttonTintList = android.content.res.ColorStateList.valueOf(DrawingView.RED)
            }
            group.addView(rb)
        }
        delete.isEnabled = presets.isNotEmpty()
        delete.alpha = if (presets.isNotEmpty()) 1f else 0.35f

        val dialog = AlertDialog.Builder(this, R.style.SNotesDialogDark).setView(view).create()

        // "+ NEW PRESET" sits after the last preset and opens the save flow.
        val newRow = TextView(this).apply {
            text = getString(R.string.preset_new)
            setTextColor(DrawingView.RED); textSize = 14f; typeface = Fonts.bold(this@MainActivity)
            letterSpacing = 0.06f
            val d = resources.displayMetrics.density
            setPadding((6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt(), (10 * d).toInt())
            setOnClickListener { dialog.dismiss(); savePresetDialog { showPresetsDialog() } }
        }
        group.addView(newRow)

        // The preset dialog uses a dark drawable in XML for the dark theme.
        // When the app is in light mode, recolor the complete dialog surface
        // and every text control so the preset names remain readable.
        stylePresetDialog(view, dark)

        // Requirement 1: LOAD applies whichever preset is selected.
        load.setOnClickListener {
            val checked = group.checkedRadioButtonId
            if (checked == defaultRb.id) applyDefaultPreset()
            else {
                val name = group.findViewById<RadioButton>(checked)?.tag as? String
                presets.firstOrNull { it.name == name }?.let(::applyPreset)
            }
            wireSliders(); buildPalette(); buildShapePills()
            dialog.dismiss()
        }
        delete.setOnClickListener {
            val checkedId = group.checkedRadioButtonId
            if (checkedId == defaultRb.id) { showLabel("Can't delete the default"); return@setOnClickListener }
            val name = group.findViewById<RadioButton>(checkedId)?.tag as? String
            if (name == null) { showLabel("Select a preset first"); return@setOnClickListener }
            PresetStore.delete(this, name); showLabel("Preset deleted")
            dialog.dismiss(); showPresetsDialog()
        }
        cancel.setOnClickListener { dialog.dismiss() }
        Fonts.apply(view)
        dialog.setOnShowListener { Fonts.applyToDialog(it as android.app.Dialog) }
        dialog.show()
    }

    private fun stylePresetDialog(view: View, isDark: Boolean) {
        val density = resources.displayMetrics.density
        val surface = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(if (isDark) 0xFF0B0B0B.toInt() else Color.WHITE)
            setStroke(
                (1f * density).toInt().coerceAtLeast(1),
                if (isDark) 0xFF2A2A2A.toInt() else 0xFFDADADA.toInt()
            )
        }
        view.background = surface

        val fgColor = if (isDark) Color.WHITE else Color.BLACK
        fun tintTree(v: View) {
            when (v) {
                is TextView -> v.setTextColor(fgColor)
                is android.view.ViewGroup -> {
                    for (i in 0 until v.childCount) tintTree(v.getChildAt(i))
                }
            }
        }
        tintTree(view)

        // Action labels use the S Notes red accent in either theme.
        view.findViewById<TextView>(R.id.presetSave)?.setTextColor(DrawingView.RED)
        view.findViewById<TextView>(R.id.presetDelete)?.setTextColor(
            if (isDark) 0xFFB0B0B0.toInt() else 0xFF666666.toInt()
        )
        view.findViewById<TextView>(R.id.presetCancel)?.setTextColor(
            if (isDark) 0xFFB0B0B0.toInt() else 0xFF666666.toInt()
        )

        // Radio controls keep the red selection indicator on both themes.
        val group = view.findViewById<RadioGroup>(R.id.presetGroup)
        for (i in 0 until (group?.childCount ?: 0)) {
            (group?.getChildAt(i) as? RadioButton)?.apply {
                setTextColor(fgColor)
                buttonTintList = android.content.res.ColorStateList.valueOf(DrawingView.RED)
            }
        }
    }

    /** The built-in starting point: plain black/white ink at size 10. */
    private fun applyDefaultPreset() {
        // Requirement 5: one default that resets EVERY tool, not just the pen.
        drawing.penColor = if (dark) Color.WHITE else Color.BLACK
        drawing.penWidth = DEFAULT_PEN_WIDTH
        drawing.eraserWidth = DEFAULT_ERASER_WIDTH
        drawing.laserWidth = DEFAULT_LASER_WIDTH
        drawing.shapeWidth = DEFAULT_SHAPE_WIDTH
        drawing.shapeKind = Shapes.Kind.RECT
        prefs.edit()
            .putFloat("pen_w", DEFAULT_PEN_WIDTH)
            .putFloat("eraser_w", DEFAULT_ERASER_WIDTH)
            .putFloat("laser_w", DEFAULT_LASER_WIDTH)
            .apply()
        selectTool(DrawingView.Tool.PEN, silent = true)
        wireSliders()
        tintTools()
        buildPalette()
        buildShapePills()
        showLabel("Default preset  ·  all tools reset")
    }

    private fun savePresetDialog(after: (() -> Unit)? = null) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        input.hint = getString(R.string.preset_name)
        input.gravity = Gravity.CENTER
        Fonts.show(
            themedDialog()
                .setTitle(R.string.preset_save_current)
                .setView(input)
                .setPositiveButton(R.string.ok) { _, _ ->
                    val name = input.text.toString().trim().ifBlank { "PRESET" }
                    PresetStore.save(
                        this,
                        PresetStore.Preset(
                            name,
                            drawing.penColor,
                            drawing.penWidth,
                            drawing.eraserWidth,
                            false, // legacy field: automatic shape detection is gone
                            true,  // legacy field: pressure is always automatic
                            true   // legacy field: beautify has been removed
                        )
                    )
                    showLabel("Preset Saved")
                    after?.invoke()
                }
                .setNegativeButton(R.string.cancel, null)
        )
    }

    private fun deletePresetDialog(presets: List<PresetStore.Preset>) {
        val names = presets.map { it.name }.toTypedArray()
        Fonts.show(
            themedDialog()
                .setTitle(R.string.preset_delete)
                .setItems(names) { _, which ->
                    PresetStore.delete(this, presets[which].name)
                    showLabel("Preset Deleted")
                }
                .setNegativeButton(R.string.cancel, null)
        )
    }

    private fun applyPreset(p: PresetStore.Preset) {
        drawing.penColor = if (dark && p.penColor == Color.BLACK) Color.WHITE
        else if (!dark && p.penColor == Color.WHITE) Color.BLACK
        else p.penColor
        drawing.penWidth = p.penWidth
        drawing.eraserWidth = p.eraserWidth
        selectTool(DrawingView.Tool.PEN, silent = true)
        wireSliders()
        tintTools()
        buildPalette()
        showLabel("Preset: ${p.name}")
    }

    // =====================================================================
    //  Theme
    // =====================================================================

    private fun fg() = if (dark) Color.WHITE else Color.BLACK
    private fun barBg() = if (dark) 0xF20A0A0A.toInt() else 0xF2FFFFFF.toInt()

    /** Rounded, subtly outlined card behind a floating dropdown (requirement 12). */
    private fun panelBg(): GradientDrawable {
        val d = resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * d
            setColor(barBg())
            setStroke((1.5f * d).toInt(), if (dark) 0xFF2C2C2C.toInt() else 0xFFDADADA.toInt())
        }
    }

    private fun applyTheme() {
        val f = fg()
        val b = barBg()
        window.decorView.setBackgroundColor(if (dark) Color.BLACK else Color.WHITE)
        val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)
        topBar.setBackgroundColor(b)
        panelPen.background = panelBg()
        panelEraser.background = panelBg()
        panelLaser.background = panelBg()
        panelShapes.background = panelBg()

        // Portrait-only floating menus must follow the active theme too.
        // They previously kept a permanent dark background, which made their
        // black icons disappear in light mode. Reuse the same themed surface
        // as the normal floating dropdowns.
        portraitToolkit?.background = panelBg()
        portraitOverflow?.background = panelBg()
        txtTitle.setTextColor(f)
        txtPenVal.setTextColor(f)
        txtEraserVal.setTextColor(f)
        txtLaserVal.setTextColor(f)
        txtShapesTitle.setTextColor(f)
        txtShapeVal.setTextColor(f)
        btnShow.setColorFilter(DrawingView.RED)
        btnShow.setBackgroundColor(b)
        for (btn in arrayOf(btnBack, btnUndo, btnRedo, btnReset, btnTheme, btnShare, btnHide)) {
            btn.setColorFilter(f)
        }
        btnToolkitToggle?.setColorFilter(f)
        btnOverflow?.setColorFilter(f)
        tintTools()
        buildShapePills()   // regenerate previews with the new ink colour
        buildPalette()
        updateHistoryTint()
    }

    private fun tintTools() {
        val f = fg()
        btnPen.setColorFilter(if (drawing.tool == DrawingView.Tool.PEN) DrawingView.RED else f)
        btnEraser.setColorFilter(if (drawing.tool == DrawingView.Tool.ERASER) DrawingView.RED else f)
        btnStrokeEraser.setColorFilter(
            if (drawing.tool == DrawingView.Tool.STROKE_ERASER) DrawingView.RED else f
        )
        btnLaser.setColorFilter(if (drawing.tool == DrawingView.Tool.LASER) DrawingView.RED else f)
        btnSelect.setColorFilter(if (drawing.tool == DrawingView.Tool.SELECT) DrawingView.RED else f)
        btnShapes.setColorFilter(if (drawing.tool == DrawingView.Tool.SHAPE) DrawingView.RED else f)
        btnStylus.setColorFilter(if (drawing.stylusOnly) DrawingView.RED else f)
        btnPresets.setColorFilter(f)
        btnClear.setColorFilter(f)
        btnText.setColorFilter(f)
        btnImage.setColorFilter(f)
    }

    private fun updateHistoryTint() {
        btnUndo.alpha = if (drawing.canUndo()) 1f else 0.3f
        btnRedo.alpha = if (drawing.canRedo()) 1f else 0.3f
    }

    // =====================================================================
    //  DrawingView.Listener
    // =====================================================================

    /** Touching the page dismisses whichever dropdown is open. */
    override fun onCanvasTouch() {
        if (anyPanelOpen()) {
            hidePanels()
        }
    }

    override fun onGesture() {
        if (focusHidden) return
        if (!autoHidden) {
            autoHidden = true
            setBarsVisible(false)
        }
        handler.removeCallbacks(showRunnable)
        handler.postDelayed(showRunnable, 700)
    }

    override fun onSelection(active: Boolean) {
        selectionBar.visibility = if (active) View.VISIBLE else View.GONE
    }

    override fun onHistory(canUndo: Boolean, canRedo: Boolean) {
        updateHistoryTint()
        if (canUndo || canRedo) scheduleSave()
    }

    override fun onStylusDetected() {
        prefs.edit().putBoolean("stylus_only", true).apply()
        tintTools()
        showLabel("Stylus detected - palm rejection ON")
    }

    // ---------- Text / image objects ----------

    override fun onCreateTextAt(wx: Float, wy: Float) {
        pendingTextX = wx; pendingTextY = wy
        showTextEditor(null)
    }

    override fun onEditText(obj: TextObject) {
        showTextEditor(obj)
    }

    override fun onEditImage(obj: ImageObject) {
        imageReplaceBefore = drawing.captureState()
        replacingImage = obj
        hideObjectEditor()
        imagePicker.launch("image/*")
    }

    /** obj == null -> creating a NEW text at (pendingTextX/Y); else editing. */
    private fun showTextEditor(obj: TextObject?) {
        objectEditBefore = if (obj == null) null else drawing.captureState()
        objectEditorTitle.text = getString(if (obj == null) R.string.insert_text else R.string.edit_text)
        objectTextField.visibility = View.VISIBLE
        objectTextField.setText(obj?.text ?: "")
        objectTextField.setSelection(objectTextField.text.length)
        objectSize.visibility = View.GONE
        objectSizeLabel.visibility = View.GONE
        objectDelete.visibility = if (obj == null) View.GONE else View.VISIBLE
        objectEditor.visibility = View.VISIBLE
        objectEditor.bringToFront()
        objectTextField.requestFocus()
        objectTextField.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(objectTextField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }

        objectDone.setOnClickListener {
            val text = objectTextField.text.toString()
            if (obj == null) {
                // create-on-done: one undo step; blank leaves nothing behind
                if (text.isNotBlank()) {
                    drawing.addTextObject(text, pendingTextX, pendingTextY)
                    selectTool(DrawingView.Tool.SELECT, silent = true)
                }
            } else {
                if (text.isBlank()) {
                    drawing.deleteObject(obj)
                } else {
                    obj.text = text
                    drawing.refitTextWidth(obj)
                    drawing.notifyObjectEdited()
                    drawing.commitDocumentState(objectEditBefore)
                }
                selectTool(DrawingView.Tool.SELECT, silent = true)
            }
            hideObjectEditor()
        }
        objectDelete.setOnClickListener {
            if (obj != null) drawing.deleteObject(obj)
            hideObjectEditor()
        }
    }

    private fun hideObjectEditor() {
        objectEditor.visibility = View.GONE
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(objectTextField.windowToken, 0)
        objectTextField.clearFocus()
        objectEditBefore = null
    }

    private fun onImagePicked(uri: Uri) {
        Thread {
            val bmp = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    val raw = BitmapFactory.decodeStream(input) ?: return@use null
                    downscale(raw, 1600)
                }
            }.getOrNull()
            if (bmp == null) {
                runOnUiThread { showLabel("Couldn't load image") }
                return@Thread
            }
            val name = "img_${System.currentTimeMillis()}.webp"
            runCatching {
                java.io.FileOutputStream(NoteStore.objImageFile(this, noteId, name)).use { out ->
                    // WebP lossless is markedly smaller than PNG with no quality
                    // loss; existing .png images still decode fine on load.
                    bmp.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
                }
            }
            runOnUiThread {
                val replace = replacingImage
                if (replace != null) {
                    replace.bitmap = bmp
                    replace.name = name
                    drawing.commitDocumentState(imageReplaceBefore)
                    imageReplaceBefore = null
                    replacingImage = null
                    drawing.notifyObjectEdited()
                    scheduleSave()
                    showLabel("Image replaced")
                } else {
                    drawing.addImageObject(name, bmp)
                    scheduleSave()
                    selectTool(DrawingView.Tool.SELECT, silent = true)
                    showLabel("Image added - drag, resize or rotate")
                }
            }
        }.start()
    }

    private fun downscale(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width; val h = src.height
        val longest = max(w, h)
        if (longest <= maxDim) return src
        val s = maxDim.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(src, (w * s).toInt(), (h * s).toInt(), true)
        if (scaled != src) src.recycle()
        return scaled
    }

    private fun setBarsVisible(visible: Boolean) {
        autoHidden = !visible
        val v = if (visible) View.VISIBLE else View.GONE
        topBar.visibility = v
        if (!visible) {
            hidePanels()
            portraitToolkit?.visibility = View.GONE
            portraitOverflow?.visibility = View.GONE
        }
    }

    // =====================================================================
    //  Rename / storage / export
    // =====================================================================

    private fun themedDialog(): AlertDialog.Builder = AlertDialog.Builder(this, R.style.SNotesDialogDark)

    /** Requirement 1 - the redesigned Share sheet (shared with the home list). */
    private fun showShareDialog() {
        ShareSheet.show(
            this,
            txtTitle.text.toString(),
            drawing.page,
            drawing.strokes,
            inkDark = dark,
            texts = drawing.textObjects,
            images = drawing.imageObjects,
            noteId = noteId
        )
    }

    private fun showLabel(text: String) {
        currentToast?.cancel()
        currentToast = Toast.makeText(this, text, Toast.LENGTH_SHORT)
        currentToast?.show()
    }
}
