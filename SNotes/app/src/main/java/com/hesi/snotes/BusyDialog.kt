package com.hesi.snotes

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Small non-cancelable "working…" card used while a PDF is imported or a PDF
 * notebook is exported. Matches the app's dark dialog style.
 */
class BusyDialog private constructor(private val dialog: AlertDialog, private val label: TextView) {

    fun update(text: String) {
        label.text = text
    }

    fun dismiss() {
        val host = dialog.context
        val activity = (host as? Activity)
            ?: ((host as? android.content.ContextWrapper)?.baseContext as? Activity)
        if (activity != null && (activity.isFinishing || activity.isDestroyed)) return
        runCatching { if (dialog.isShowing) dialog.dismiss() }
    }

    companion object {
        fun show(ctx: Context, text: String): BusyDialog {
            val d = ctx.resources.displayMetrics.density
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_dialog_dark)
                setPadding((22 * d).toInt(), (20 * d).toInt(), (22 * d).toInt(), (20 * d).toInt())
            }
            val spinner = ProgressBar(ctx).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(DrawingView.RED)
            }
            row.addView(spinner, LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt()))
            val label = TextView(ctx).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 13f
                letterSpacing = 0.08f
                typeface = Fonts.bold(ctx)
                setPadding((16 * d).toInt(), 0, 0, 0)
            }
            row.addView(label)
            val dialog = AlertDialog.Builder(ctx).setView(row).setCancelable(false).create()
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setWindowAnimations(R.style.DialogAnim)
            dialog.show()
            return BusyDialog(dialog, label)
        }
    }
}
