package com.hesi.snotes

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Requirement B3 - when creating a notebook, pick one of three page modes shown
 * as a grid of cards with a preview image: infinite length, limited pages, or
 * infinite pages. onPick returns the chosen NoteStore.PAGE_* value.
 */
object NewNotebookDialog {

    fun show(ctx: Context, onPick: (Int) -> Unit) {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_newnotebook, null)
        val infinite = view.findViewById<LinearLayout>(R.id.optInfinite)
        val multi = view.findViewById<LinearLayout>(R.id.optMulti)
        val cancel = view.findViewById<TextView>(R.id.newCancel)

        Fonts.apply(view)

        val dialog = AlertDialog.Builder(ctx).setView(view).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        infinite.setOnClickListener { onPick(NoteStore.PAGE_INFINITE); dialog.dismiss() }
        multi.setOnClickListener { onPick(NoteStore.PAGE_MULTI_INFINITE); dialog.dismiss() }
        cancel.setOnClickListener { dialog.dismiss() }

        dialog.setOnShowListener { Fonts.applyToDialog(it as Dialog) }
        dialog.window?.setWindowAnimations(R.style.DialogAnim)
        dialog.show()
    }
}
