package com.hesi.snotes

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * One font for the whole app.
 *
 * Requirement 6 - the app no longer uses the dotted "Ndot" face. It now loads a
 * clean, non-dotted "Nothing style" font from:
 *      app/src/main/assets/fonts/nothing.ttf        (regular)
 *      app/src/main/assets/fonts/nothing_bold.ttf   (optional, bold)
 *
 * Drop your preferred non-dotted Nothing/grotesk TTF at those paths. Until then
 * the app falls back to the system MONOSPACE (also non-dotted), so it always
 * builds and never shows the dotted font again. Nothing else needs to change.
 */
object Fonts {

    private var loaded = false
    private var regular: Typeface? = null
    private var bold: Typeface? = null

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val am = ctx.applicationContext.assets
        regular = runCatching { Typeface.createFromAsset(am, "fonts/nothing.ttf") }.getOrNull()
        bold = runCatching { Typeface.createFromAsset(am, "fonts/nothing_bold.ttf") }.getOrNull()
            ?: regular
    }

    fun regular(ctx: Context): Typeface {
        load(ctx)
        return regular ?: Typeface.MONOSPACE
    }

    fun bold(ctx: Context): Typeface {
        load(ctx)
        return bold ?: Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    /** True when the real dotted face is present (used to skip the fake-bold pass). */
    fun hasCustom(ctx: Context): Boolean {
        load(ctx)
        return regular != null
    }

    /**
     * Walks a view tree once and re-types every TextView (buttons + EditTexts are
     * TextViews too). Cheap: it is a single pass done at inflate time only.
     */
    fun apply(v: View) {
        val ctx = v.context
        load(ctx)
        if (regular == null) return // nothing to do, XML monospace already applied
        applyInternal(v, ctx)
    }

    private fun applyInternal(v: View, ctx: Context) {
        if (v is TextView) {
            val wasBold = v.typeface?.isBold == true || v.typeface?.style == Typeface.BOLD
            v.typeface = if (wasBold) bold(ctx) else regular(ctx)
        } else if (v is ViewGroup) {
            for (i in 0 until v.childCount) applyInternal(v.getChildAt(i), ctx)
        }
    }

    /** Shows an AlertDialog and re-types its contents with the app font. */
    fun show(builder: AlertDialog.Builder): AlertDialog {
        val d = builder.create()
        d.setOnShowListener { dlg -> applyToDialog(dlg as Dialog) }
        d.show()
        return d
    }

    fun applyToDialog(d: Dialog) {
        d.window?.decorView?.let { apply(it) }
    }
}
