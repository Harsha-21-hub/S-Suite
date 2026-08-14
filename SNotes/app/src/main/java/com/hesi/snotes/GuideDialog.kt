package com.hesi.snotes

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Requirement G10 - the feature guide, styled after Screens.kt's TutorialOverlay:
 * a dark card, each feature shown as a small red bar + bold white title + grey
 * body, and a red "GOT IT" button. Content describes S Notes.
 */
object GuideDialog {

    // title to body
    private val ITEMS = listOf(
        "Tools & dropdowns" to
                "Tap PEN, ERASER, LASER or SHAPES to select a tool. Tap it again to open its " +
                "options. A red chevron shows that the tool has more options.",
        "Laser pointer" to
                "The laser trail stays visible while you write and slowly fades away after " +
                "you stop.",
        "Shapes" to
                "Choose a shape and its size, then drag in any direction. Lines, arrows and " +
                "triangles follow the direction of your drag.",
        "Text & images" to
                "Add TEXT or an IMAGE to the page. You can write over them. Use the SELECT " +
                "tool to move, resize or edit them.",
        "Pages" to
                "When creating a notebook, choose infinite page length or multiple infinite " +
                "page length. Infinite grows freely; multiple infinite grows with page-sized sheets.",
        "Zoom & focus" to
                "Pinch to zoom. RESET ZOOM returns to the default zoom while keeping your current " +
                "page position. FOCUS hides the bars for more writing space.",
        "Select" to
                "Use the SELECT tool to select strokes, shapes, text or images. You can move, " +
                "resize or delete selected items.",
        "Share / export" to
                "Export a note as PDF, JPG or PNG in light or dark mode. You can also save it " +
                "directly to your gallery.",
        "Home" to
                "Long-press a note to select multiple notes, then export or delete them together. " +
                "Use the ⋮ menu to rename a note."
    )

    fun show(ctx: Context) {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_guide, null)
        val items = view.findViewById<LinearLayout>(R.id.guideItems)
        val close = view.findViewById<TextView>(R.id.guideClose)
        val gotIt = view.findViewById<TextView>(R.id.guideGotIt)

        val d = ctx.resources.displayMetrics.density
        for ((title, body) in ITEMS) items.addView(item(ctx, title, body, d))

        Fonts.apply(view)

        val dialog = AlertDialog.Builder(ctx).setView(view).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        close.setOnClickListener { dialog.dismiss() }
        gotIt.setOnClickListener { dialog.dismiss() }
        dialog.setOnShowListener { Fonts.applyToDialog(it as Dialog) }
        dialog.window?.setWindowAnimations(R.style.DialogAnim)
        dialog.show()
        // Requirement 7: bound the dialog so the scrolling list flexes and the
        // GOT IT button never falls off the bottom of the screen (esp. portrait).
        val dm = ctx.resources.displayMetrics
        dialog.window?.setLayout(
            (dm.widthPixels * 0.92f).toInt(),
            (dm.heightPixels * 0.88f).toInt()
        )
    }

    private fun item(ctx: Context, title: String, body: String, d: Float): LinearLayout {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, (18 * d).toInt())
        }
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val bar = android.view.View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((10 * d).toInt(), (3 * d).toInt())
            background = GradientDrawable().apply {
                setColor(DrawingView.RED); cornerRadius = 2 * d
            }
        }
        val titleTv = TextView(ctx).apply {
            text = title.uppercase()
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Fonts.bold(ctx)
            letterSpacing = 0.08f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = (10 * d).toInt()
            layoutParams = lp
        }
        titleRow.addView(bar)
        titleRow.addView(titleTv)

        val bodyTv = TextView(ctx).apply {
            text = body
            setTextColor(0xFF8A8A8A.toInt())
            textSize = 11f
            setLineSpacing(2f * d, 1f)
            typeface = Fonts.regular(ctx)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (7 * d).toInt()
            layoutParams = lp
        }
        col.addView(titleRow)
        col.addView(bodyTv)
        return col
    }
}
