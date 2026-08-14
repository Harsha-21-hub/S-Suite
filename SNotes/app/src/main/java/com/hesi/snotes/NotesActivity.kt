package com.hesi.snotes

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotesActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_OPEN_HESI = 7401
    }

    private lateinit var rootNotes: FrameLayout
    private lateinit var recycler: RecyclerView
    private lateinit var txtEmpty: TextView
    private lateinit var txtHeader: TextView
    private lateinit var txtStorage: TextView
    private lateinit var btnNew: TextView
    private lateinit var btnOpenNotebook: TextView
    private lateinit var btnGuide: ImageButton
    private lateinit var btnTrash: ImageButton
    private lateinit var btnThemeList: ImageButton
    private lateinit var btnHomeMenu: ImageButton
    private lateinit var homeMenuPanel: LinearLayout
    private lateinit var notebookActions: LinearLayout
    private lateinit var notebookActionDivider: View

    // Requirement 9: selection header
    private lateinit var header: FrameLayout
    private lateinit var selHeader: LinearLayout
    private lateinit var txtSelCount: TextView
    private lateinit var btnSelClose: ImageButton
    private lateinit var btnSelectAll: ImageButton
    private lateinit var btnSelExport: ImageButton
    private lateinit var btnSelDelete: ImageButton

    private lateinit var adapter: NotesAdapter

    private val prefs by lazy { getSharedPreferences("snotes", MODE_PRIVATE) }
    private var dark = false

    /** The same list doubles as the trash bin. */
    private var inTrash = false

    // Requirement 9: multi-select state
    private var selectionMode = false
    private val selectedIds = LinkedHashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notes)

        rootNotes = findViewById(R.id.rootNotes)
        recycler = findViewById(R.id.recycler)
        txtEmpty = findViewById(R.id.txtEmpty)
        txtHeader = findViewById(R.id.txtHeader)
        txtStorage = findViewById(R.id.txtStorage)
        btnNew = findViewById(R.id.btnNew)
        btnOpenNotebook = findViewById(R.id.btnOpenNotebook)
        btnGuide = findViewById(R.id.btnGuide)
        btnTrash = findViewById(R.id.btnTrash)
        btnThemeList = findViewById(R.id.btnThemeList)
        btnHomeMenu = findViewById(R.id.btnHomeMenu)
        homeMenuPanel = findViewById(R.id.homeMenuPanel)
        notebookActions = findViewById(R.id.notebookActions)
        notebookActionDivider = findViewById(R.id.notebookActionDivider)
        header = findViewById(R.id.header)
        selHeader = findViewById(R.id.selHeader)
        txtSelCount = findViewById(R.id.txtSelCount)
        btnSelClose = findViewById(R.id.btnSelClose)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnSelExport = findViewById(R.id.btnSelExport)
        btnSelDelete = findViewById(R.id.btnSelDelete)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.setHasFixedSize(true)
        recycler.setItemViewCacheSize(12)
        adapter = NotesAdapter()
        recycler.adapter = adapter

        btnOpenNotebook.setOnClickListener { openNotebookPicker() }

        btnNew.setOnClickListener {
            if (inTrash) {
                confirmEmptyTrash()
            } else {
                NewNotebookDialog.show(this) { pageMode ->
                    val n = NoteStore.list(this).size + 1
                    val id = NoteStore.create(this, "NOTE $n", pageMode)
                    openNote(id)
                }
            }
        }
        btnHomeMenu.setOnClickListener {
            homeMenuPanel.visibility = if (homeMenuPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        btnTrash.setOnClickListener {
            homeMenuPanel.visibility = View.GONE
            exitSelection()
            inTrash = !inTrash
            refresh()
        }
        btnThemeList.setOnClickListener {
            homeMenuPanel.visibility = View.GONE
            dark = !dark
            prefs.edit().putBoolean("dark", dark).apply()
            applyTheme()
            adapter.notifyDataSetChanged()
        }
        // The hamburger home menu exists only in portrait mode. Landscape keeps
        // the classic inline Trash + Theme buttons.
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        btnHomeMenu.visibility = if (isPortrait) View.VISIBLE else View.GONE
        homeMenuPanel.visibility = View.GONE

        btnGuide.setOnClickListener { showGuide() }

        btnSelClose.setOnClickListener { exitSelection() }
        btnSelectAll.setOnClickListener { selectAll() }
        btnSelExport.setOnClickListener { exportSelected() }
        btnSelDelete.setOnClickListener { deleteSelected() }

        Fonts.apply(rootNotes)
        handleIncomingNotebookIntent(intent)
    }

    override fun onBackPressed() {
        when {
            selectionMode -> exitSelection()
            inTrash -> { inTrash = false; refresh() }
            else -> super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        dark = prefs.getBoolean("dark", false)
        applyTheme()
        refresh()
    }

    @SuppressLint("NotifyDataSetChanged", "SetTextI18n")
    private fun refresh() {
        val trashMode = inTrash
        // Cheap chrome first; the file IO below runs off the main thread.
        txtHeader.text = if (trashMode) getString(R.string.trash) else getString(R.string.app_name_caps)
        btnNew.setText(if (trashMode) R.string.empty_trash else R.string.new_note)
        txtEmpty.setText(if (trashMode) R.string.trash_empty else R.string.no_notes)

        Thread {
            val items = if (trashMode) NoteStore.listTrash(this) else NoteStore.list(this)
            val trashN = NoteStore.trashCount(this)
            val totalStr = NoteStore.formatSize(NoteStore.totalBytes(this))
            runOnUiThread {
                if (isFinishing || trashMode != inTrash) return@runOnUiThread
                adapter.items = items
                adapter.notifyDataSetChanged()
                txtEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                if (trashMode) {
                    val hasTrash = items.isNotEmpty()
                    notebookActions.visibility = if (hasTrash) View.VISIBLE else View.GONE
                    btnNew.visibility = if (hasTrash) View.VISIBLE else View.GONE
                    btnOpenNotebook.visibility = View.GONE
                    notebookActionDivider.visibility = View.GONE
                } else {
                    notebookActions.visibility = View.VISIBLE
                    btnNew.visibility = View.VISIBLE
                    btnOpenNotebook.visibility = View.VISIBLE
                    notebookActionDivider.visibility = View.VISIBLE
                }
                (btnNew.layoutParams as LinearLayout.LayoutParams).apply {
                    width = 0
                    weight = 1f
                }.also { btnNew.layoutParams = it }

                txtStorage.text = if (trashN > 0)
                    getString(R.string.storage_line_trash, items.size, totalStr, trashN)
                else
                    getString(R.string.storage_line, items.size, totalStr)
                Fonts.apply(rootNotes)
            }
        }.start()
    }

    private fun openNotebookPicker() {
        if (inTrash) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(HesiNotebook.MIME, "application/octet-stream"))
        }
        runCatching { startActivityForResult(intent, REQUEST_OPEN_HESI) }
            .onFailure { Toast.makeText(this, "Unable to open notebook picker", Toast.LENGTH_SHORT).show() }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingNotebookIntent(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OPEN_HESI && resultCode == RESULT_OK) {
            handleIncomingNotebookIntent(data)
        }
    }

    private fun handleIncomingNotebookIntent(intent: Intent?) {
        val uri = intent?.data ?: runCatching { intent?.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM) }.getOrNull() ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) { }
        Thread {
            val result = runCatching { HesiNotebook.importFromUri(this, uri) }
            runOnUiThread {
                result.onSuccess { id -> openNote(id) }
                    .onFailure {
                        Toast.makeText(this, "Could not open .hesi notebook", Toast.LENGTH_LONG).show()
                    }
            }
        }.start()
    }

    private fun openNote(id: String) {
        startActivity(
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_NOTE_ID, id)
        )
    }

    private fun fg() = if (dark) Color.WHITE else Color.BLACK

    private fun applyTheme() {
        val bg = if (dark) Color.BLACK else Color.WHITE
        rootNotes.setBackgroundColor(bg)
        txtHeader.setTextColor(fg())
        txtStorage.setTextColor(if (dark) 0xFF8A8A8A.toInt() else 0xFF7A7A7A.toInt())
        txtEmpty.setTextColor(if (dark) 0xFF8A8A8A.toInt() else 0xFF7A7A7A.toInt())
        txtSelCount.setTextColor(fg())
        btnGuide.imageTintList = ColorStateList.valueOf(fg())
        btnHomeMenu.imageTintList = ColorStateList.valueOf(fg())
        btnThemeList.imageTintList = ColorStateList.valueOf(fg())
        btnTrash.imageTintList = ColorStateList.valueOf(if (inTrash) DrawingView.RED else fg())
        btnSelClose.setColorFilter(fg())
        btnSelectAll.setColorFilter(fg())
        btnSelExport.setColorFilter(fg())
        btnSelDelete.setColorFilter(DrawingView.RED)

        // The portrait home menu must remain visible in both themes. The old
        // layout used bg_dialog_dark unconditionally, which made the black
        // trash/theme icons disappear against the dark popup in light mode.
        // Keep the same rounded-card geometry but derive its fill/border from
        // the current theme so the popup and its already-tinted icons always
        // have contrast.
        homeMenuPanel.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.displayMetrics.density * 20f
            setColor(if (dark) 0xFF0E0E0E.toInt() else 0xFFFFFFFF.toInt())
            setStroke(
                (resources.displayMetrics.density).toInt().coerceAtLeast(1),
                if (dark) 0xFF1E1E1E.toInt() else 0xFFE0E0E0.toInt()
            )
        }

        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        btnHomeMenu.visibility = if (isPortrait) View.VISIBLE else View.GONE
        homeMenuPanel.visibility = if (isPortrait) homeMenuPanel.visibility else View.GONE
        window.decorView.setBackgroundColor(bg)
        val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)
    }

    // =====================================================================
    //  Selection (requirement 9)
    // =====================================================================

    @SuppressLint("NotifyDataSetChanged")
    private fun enterSelection(id: String) {
        if (inTrash) return
        selectionMode = true
        selectedIds.add(id)
        updateSelectionUi()
        adapter.notifyDataSetChanged()
    }

    private fun toggleSelect(id: String) {
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        if (selectedIds.isEmpty()) { exitSelection(); return }
        updateSelectionUi()
        adapter.notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun exitSelection() {
        if (!selectionMode && selectedIds.isEmpty()) return
        selectionMode = false
        selectedIds.clear()
        updateSelectionUi()
        adapter.notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun selectAll() {
        for (m in adapter.items) selectedIds.add(m.id)
        updateSelectionUi()
        adapter.notifyDataSetChanged()
    }

    private fun updateSelectionUi() {
        header.visibility = if (selectionMode) View.INVISIBLE else View.VISIBLE
        selHeader.visibility = if (selectionMode) View.VISIBLE else View.GONE
        txtSelCount.text = getString(R.string.selected_count, selectedIds.size)
    }

    private fun exportSelected() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) return
        val bitmaps = ArrayList<Bitmap>()
        for (id in ids) {
            val data = NoteStore.load(this, id) ?: continue
            val objs = NoteStore.loadObjects(this, id)
            bitmaps.addAll(
                Exporter.renderPages(
                    data.second, data.first, outDark = false, inkDark = false, split = true,
                    texts = objs.first, images = objs.second
                )
            )
        }
        if (bitmaps.isEmpty()) return
        Exporter.shareImages(this, bitmaps, asPng = false, name = "S Notes")
        exitSelection()
    }

    private fun deleteSelected() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) return
        Fonts.show(
            AlertDialog.Builder(this, R.style.SNotesDialogDark)
                .setTitle(getString(R.string.selected_count, ids.size))
                .setMessage(R.string.delete_choice_q)
                .setPositiveButton(R.string.move_to_trash) { _, _ ->
                    for (id in ids) NoteStore.moveToTrash(this, id)
                    exitSelection()
                    refresh()
                    Toast.makeText(this, R.string.moved_to_trash, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.cancel, null)
        )
    }

    private fun showGuide() {
        GuideDialog.show(this)
    }

    private fun confirmEmptyTrash() {
        Fonts.show(
            AlertDialog.Builder(this, R.style.SNotesDialogDark)
                .setTitle(R.string.empty_trash)
                .setMessage(R.string.empty_trash_q)
                .setPositiveButton(R.string.delete_forever) { _, _ ->
                    NoteStore.emptyTrash(this)
                    refresh()
                    Toast.makeText(this, R.string.trash_emptied, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.cancel, null)
        )
    }

    // =====================================================================

    private inner class NotesAdapter : RecyclerView.Adapter<NotesAdapter.VH>() {

        var items: List<NoteStore.Meta> = emptyList()

        // Requirement 14: both timestamps carry a date AND time now.
        private val fmtSaved = SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault())
        private val fmtCreated = SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault())

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.txtName)
            val date: TextView = v.findViewById(R.id.txtDate)
            val created: TextView = v.findViewById(R.id.txtCreated)
            val type: TextView = v.findViewById(R.id.txtType)
            val size: TextView = v.findViewById(R.id.txtSize)
            val more: ImageButton = v.findViewById(R.id.btnMore)
            val check: ImageView = v.findViewById(R.id.imgCheck)
            val root: LinearLayout = v.findViewById(R.id.itemRoot)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_note, parent, false))

        override fun getItemCount() = items.size

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(h: VH, pos: Int) {
            val m = items[pos]
            val sub = if (dark) 0xFF8A8A8A.toInt() else 0xFF7A7A7A.toInt()
            h.name.text = m.name.uppercase(Locale.getDefault())
            h.date.text = getString(R.string.item_saved, fmtSaved.format(Date(m.modified)))
            h.created.text = getString(R.string.item_created, fmtCreated.format(Date(m.created)))
            h.type.text = if (NoteStore.readPageMode(this@NotesActivity, m.id) == NoteStore.PAGE_MULTI_INFINITE)
                getString(R.string.page_type_multi) else getString(R.string.page_type_infinite)
            h.size.text = NoteStore.formatSize(m.bytes)
            h.name.setTextColor(fg())
            h.date.setTextColor(sub)
            h.created.setTextColor(sub)
            h.type.setTextColor(DrawingView.RED)
            h.size.setTextColor(sub)
            h.more.setColorFilter(fg())

            val selected = selectedIds.contains(m.id)
            if (selectionMode) {
                h.more.visibility = View.GONE
                h.check.visibility = View.VISIBLE
                if (selected) {
                    h.check.setColorFilter(DrawingView.RED); h.check.alpha = 1f
                    h.root.setBackgroundColor(0x22D71921)
                } else {
                    h.check.setColorFilter(sub); h.check.alpha = 0.32f
                    h.root.setBackgroundColor(Color.TRANSPARENT)
                }
            } else {
                h.more.visibility = View.VISIBLE
                h.check.visibility = View.GONE
                h.root.setBackgroundColor(Color.TRANSPARENT)
            }

            h.root.setOnClickListener {
                when {
                    selectionMode -> toggleSelect(m.id)
                    inTrash -> restoreDialog(m)
                    else -> openNote(m.id)
                }
            }
            h.root.setOnLongClickListener {
                if (!inTrash && !selectionMode) { enterSelection(m.id); true } else false
            }
            h.more.setOnClickListener { showMenu(h.more, m) }
            Fonts.apply(h.itemView)
        }

        private fun showMenu(anchor: View, m: NoteStore.Meta) {
            val menu = PopupMenu(this@NotesActivity, anchor)
            if (inTrash) {
                menu.menu.add(0, 10, 0, getString(R.string.restore))
                menu.menu.add(0, 11, 1, getString(R.string.delete_forever))
            } else {
                menu.menu.add(0, 1, 0, getString(R.string.rename))
                menu.menu.add(0, 2, 1, getString(R.string.share_export))
                menu.menu.add(0, 6, 2, getString(R.string.delete))
            }
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> renameDialog(m)
                    2 -> shareNote(m)
                    6 -> deleteDialog(m)
                    10 -> restoreDialog(m)
                    11 -> permanentDialog(m)
                }
                true
            }
            menu.show()
        }

        /** Requirement 8 - the themed rename dialog. */
        private fun renameDialog(m: NoteStore.Meta) {
            RenameDialog.show(this@NotesActivity, m.name) { name ->
                NoteStore.rename(this@NotesActivity, m.id, name)
                refresh()
            }
        }

        private fun deleteDialog(m: NoteStore.Meta) {
            Fonts.show(
                AlertDialog.Builder(this@NotesActivity, R.style.SNotesDialogDark)
                    .setTitle(m.name.uppercase(Locale.getDefault()))
                    .setMessage(R.string.delete_choice_q)
                    .setPositiveButton(R.string.move_to_trash) { _, _ ->
                        NoteStore.moveToTrash(this@NotesActivity, m.id)
                        refresh()
                        Toast.makeText(
                            this@NotesActivity, R.string.moved_to_trash, Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNeutralButton(R.string.delete_forever) { _, _ -> permanentDialog(m) }
                    .setNegativeButton(R.string.cancel, null)
            )
        }

        private fun permanentDialog(m: NoteStore.Meta) {
            Fonts.show(
                AlertDialog.Builder(this@NotesActivity, R.style.SNotesDialogDark)
                    .setTitle(R.string.delete_forever)
                    .setMessage(R.string.delete_forever_q)
                    .setPositiveButton(R.string.delete_forever) { _, _ ->
                        NoteStore.deleteForever(this@NotesActivity, m.id)
                        refresh()
                    }
                    .setNegativeButton(R.string.cancel, null)
            )
        }

        private fun restoreDialog(m: NoteStore.Meta) {
            Fonts.show(
                AlertDialog.Builder(this@NotesActivity, R.style.SNotesDialogDark)
                    .setTitle(m.name.uppercase(Locale.getDefault()))
                    .setMessage(R.string.restore_q)
                    .setPositiveButton(R.string.restore) { _, _ ->
                        NoteStore.restoreFromTrash(this@NotesActivity, m.id)
                        refresh()
                    }
                    .setNeutralButton(R.string.delete_forever) { _, _ -> permanentDialog(m) }
                    .setNegativeButton(R.string.cancel, null)
            )
        }

        /** Requirement 1/4 - the same Share sheet as the editor, from the list. */
        private fun shareNote(m: NoteStore.Meta) {
            val data = NoteStore.load(this@NotesActivity, m.id) ?: return
            val objs = NoteStore.loadObjects(this@NotesActivity, m.id)
            ShareSheet.show(
                this@NotesActivity, m.name, data.first, data.second, inkDark = false,
                texts = objs.first, images = objs.second, noteId = m.id
            )
        }
    }
}
