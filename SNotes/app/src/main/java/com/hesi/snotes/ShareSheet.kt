package com.hesi.snotes

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.RectF
import android.view.LayoutInflater
import android.widget.PopupMenu
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Requirement G8/G9 - dark, compact "SHARE / EXPORT" sheet, used from both the
 * editor and the home list. A radio group chooses between:
 *   (o) SHARE AS  [format v] [theme v]      -> share in that format + colour
 *   ( ) SAVE TO GALLERY                      -> save images to the gallery
 * with SAVE / CANCEL side by side. High quality, cropped to the ink.
 */
object ShareSheet {

    private enum class Fmt { PDF, JPG, PNG, HESI }

    fun show(
        ctx: Context,
        name: String,
        page: RectF,
        strokes: List<Stroke>,
        inkDark: Boolean,
        texts: List<TextObject> = emptyList(),
        images: List<ImageObject> = emptyList(),
        noteId: String? = null
    ) {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_share, null)
        val rgMode = view.findViewById<RadioGroup>(R.id.rgMode)
        val ddFormat = view.findViewById<TextView>(R.id.ddFormat)
        val ddTheme = view.findViewById<TextView>(R.id.ddTheme)
        val rbNotebook = view.findViewById<android.widget.RadioButton>(R.id.rbNotebook)
        val btnSave = view.findViewById<TextView>(R.id.btnShareSave)
        val btnCancel = view.findViewById<TextView>(R.id.btnShareCancel)

        var fmt = Fmt.PDF
        var outDark = false

        ddFormat.setOnClickListener {
            val menu = PopupMenu(ctx, ddFormat)
            menu.menu.add(0, 0, 0, ctx.getString(R.string.fmt_pdf))
            menu.menu.add(0, 1, 1, ctx.getString(R.string.fmt_jpg))
            menu.menu.add(0, 2, 2, ctx.getString(R.string.fmt_png))
            menu.setOnMenuItemClickListener { item ->
                fmt = when (item.itemId) { 1 -> Fmt.JPG; 2 -> Fmt.PNG; else -> Fmt.PDF }
                ddFormat.text = item.title
                true
            }
            menu.show()
        }
        ddTheme.setOnClickListener {
            val menu = PopupMenu(ctx, ddTheme)
            menu.menu.add(0, 0, 0, ctx.getString(R.string.theme_light))
            menu.menu.add(0, 1, 1, ctx.getString(R.string.theme_dark))
            menu.setOnMenuItemClickListener { item ->
                outDark = item.itemId == 1
                ddTheme.text = item.title
                true
            }
            menu.show()
        }

        // Dim the dropdowns when "save to gallery" is chosen.
        fun syncEnabled() {
            val shareAs = rgMode.checkedRadioButtonId == R.id.rbShareAs
            ddFormat.isEnabled = shareAs
            ddTheme.isEnabled = shareAs
            ddFormat.alpha = if (shareAs) 1f else 0.4f
            ddTheme.alpha = if (shareAs) 1f else 0.4f
        }
        rgMode.setOnCheckedChangeListener { _, _ -> syncEnabled() }
        syncEnabled()

        Fonts.apply(view)

        val dialog = AlertDialog.Builder(ctx).setView(view).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val mode = rgMode.checkedRadioButtonId
            val gallery = mode == R.id.rbGallery
            if (mode == R.id.rbNotebook) {
                if (noteId == null) {
                    android.widget.Toast.makeText(ctx, "Notebook export is unavailable", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    HesiNotebook.share(ctx, name, pageMode = NoteStore.readPageMode(ctx, noteId), page, strokes, texts, images)
                }
            } else {
                doExport(ctx, name, page, strokes, inkDark, fmt, outDark, gallery, texts, images)
            }
            dialog.dismiss()
        }

        dialog.setOnShowListener { Fonts.applyToDialog(it as Dialog) }
        dialog.window?.setWindowAnimations(R.style.DialogAnim)
        dialog.show()
    }

    private fun doExport(
        ctx: Context, name: String, page: RectF, strokes: List<Stroke>,
        inkDark: Boolean, fmt: Fmt, outDark: Boolean, toGallery: Boolean,
        texts: List<TextObject>, images: List<ImageObject>
    ) {
        if (!toGallery && fmt == Fmt.PDF) {
            Exporter.sharePdf(ctx, strokes, page, outDark = outDark, inkDark = inkDark, name = name, texts = texts, images = images)
            return
        }
        // Requirement 8: a multi-page notebook exports as separate pages (p1, p2,
        // …) for JPG/PNG and gallery too - the same pagination the PDF uses.
        val bitmaps = Exporter.renderPages(
            strokes, page, outDark = outDark, inkDark = inkDark, split = true,
            texts = texts, images = images
        )
        when {
            toGallery -> Exporter.saveToGallery(ctx, bitmaps, name)
            fmt == Fmt.PNG -> Exporter.shareImages(ctx, bitmaps, asPng = true, name = name)
            else -> Exporter.shareImages(ctx, bitmaps, asPng = false, name = name)
        }
    }
}
