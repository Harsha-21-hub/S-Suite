package com.hesi.snotes

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Requirement 8 - one themed rename dialog, used by the editor and the home list.
 * Card style matches the Share sheet: red accent, side-by-side Cancel / Save.
 */
object RenameDialog {

    fun show(ctx: Context, current: String, onSave: (String) -> Unit) {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_rename, null)
        val field = view.findViewById<EditText>(R.id.renameField)
        val cancel = view.findViewById<TextView>(R.id.renameCancel)
        val save = view.findViewById<TextView>(R.id.renameSave)

        field.setText(current)
        field.setSelection(field.text.length)

        Fonts.apply(view)

        val dialog = AlertDialog.Builder(ctx)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        cancel.setOnClickListener { dialog.dismiss() }
        save.setOnClickListener {
            val name = field.text.toString().trim().ifBlank { "NOTE" }
            onSave(name)
            dialog.dismiss()
        }

        dialog.setOnShowListener { Fonts.applyToDialog(it as Dialog) }
        dialog.window?.setWindowAnimations(R.style.DialogAnim)
        dialog.show()
    }
}
