package com.sdrive.shared.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdrive.shared.Cat
import com.sdrive.shared.FolderGroup
import com.sdrive.shared.IFile
import com.sdrive.shared.Kind
import com.sdrive.shared.MediaIndex
import com.sdrive.shared.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun catIcon(c: Cat): ImageVector = when (c) {
    Cat.DOWNLOADS -> Ic.download
    Cat.IMAGES -> Ic.image
    Cat.VIDEOS -> Ic.movie
    Cat.AUDIO -> Ic.music
    Cat.DOCUMENTS -> Ic.doc
    Cat.APPS -> Ic.apps
    Cat.ARCHIVES -> Ic.archive
}

private fun fileIcon(k: Kind): ImageVector = when (k) {
    Kind.DOC, Kind.SUBTITLE -> Ic.doc
    Kind.APK -> Ic.apps
    Kind.ARCHIVE -> Ic.archive
    else -> kindIcon(k)
}

private val catDate = SimpleDateFormat("d MMM yy", Locale.getDefault())

/** Scroll position per category, kept while the app runs (like the folder browser). */
private val catListStates = HashMap<Cat, LazyListState>()

// ======================= tiles shown on top of the home (storage root) =======================

/** One row of two category tiles (Google-Files style), used at the top of the home screen. */
@Composable
fun CategoryTiles(pair: List<Cat>) {
    val nav = LocalNav.current
    val watch = LocalIsWatch.current
    val snap by MediaIndex.snap.collectAsState()
    Row(Modifier.curved().fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (watch) 5.dp else 8.dp)) {
        pair.forEach { c ->
            val st = snap?.stats?.get(c)
            if (watch) {
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Pal.surface)
                        .clickable { nav.go(Screen.Category(c)) }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Ico(catIcon(c), Pal.text, 16.dp)
                    Txt(c.label, size = 10.sp, align = TextAlign.Center)
                    Txt(st?.count?.toString() ?: "…", size = 9.sp, color = Pal.dim, align = TextAlign.Center)
                }
            } else {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Pal.surface)
                        .clickable { nav.go(Screen.Category(c)) }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Pal.surface2), contentAlignment = Alignment.Center) {
                        Ico(catIcon(c), Pal.text, 22.dp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(c.label, size = 15.sp)
                        Txt(if (st == null) "…" else "${st.count} · ${Storage.fmt(st.bytes)}", size = 11.sp, color = Pal.dim)
                    }
                }
            }
        }
        if (pair.size == 1) Spacer(Modifier.weight(1f))
    }
}

// ======================= category -> folders =======================

/** A category (Videos, Audio, Documents…) classified by the folders its files are in. */
@Composable
fun CategoryScreen(cat: Cat) {
    val nav = LocalNav.current
    val browser = LocalBrowser.current
    val watch = LocalIsWatch.current
    val snap by MediaIndex.snap.collectAsState()
    val folders by produceState<List<FolderGroup>?>(null, snap, cat) {
        val s = snap
        value = if (s == null) null else withContext(Dispatchers.Default) { s.folders(cat) }
    }
    val st = snap?.stats?.get(cat)
    val thumbs = cat == Cat.IMAGES || cat == Cat.VIDEOS || cat == Cat.AUDIO
    val state = remember(cat) { catListStates.getOrPut(cat) { LazyListState() } }

    WList(state = state) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(cat.label.uppercase(), size = if (watch) 15.sp else 22.sp, align = TextAlign.Center, font = DotFont)
                Txt("${st?.count ?: 0} ${cat.unit} · ${Storage.fmt(st?.bytes ?: 0L)}", size = 11.sp, color = Pal.dim, align = TextAlign.Center)
            }
        }
        val list = folders
        if (list == null) {
            item { Txt("Indexing…", Modifier.padding(16.dp), color = Pal.dim) }
        } else if (list.isEmpty()) {
            item { Txt("No ${cat.unit} found", Modifier.padding(16.dp), color = Pal.dim) }
        } else {
            item {
                Chip("All ${cat.label.lowercase()}", { nav.go(Screen.CatFiles(cat, null, cat.label)) },
                    icon = catIcon(cat), sub = "${st?.count ?: 0} in ${list.size} folders")
            }
            item { SectionTitle("By folder") }
            items(list, key = { it.dir }) { g ->
                CatRow(
                    name = g.name,
                    sub = "${g.count} ${cat.unit} · ${Storage.fmt(g.bytes)}",
                    path = g.cover.path,
                    kind = if (thumbs) g.cover.kind else Kind.DIR,
                    icon = Ic.folder,
                    selected = false,
                    onClick = { nav.go(Screen.CatFiles(cat, g.dir, g.name)) },
                    // Long-press: open this folder in the normal file browser.
                    onLongClick = { browser.dir = File(g.dir); nav.home() },
                )
            }
        }
    }
}

// ======================= category -> files =======================

@Composable
fun CatFilesScreen(cat: Cat, dir: String?, title: String) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val browser = LocalBrowser.current
    val ui = LocalUi.current
    val watch = LocalIsWatch.current
    val scope = rememberCoroutineScope()
    val snap by MediaIndex.snap.collectAsState()
    var version by remember { mutableIntStateOf(0) }
    val files by produceState<List<IFile>?>(null, snap, cat, dir, version) {
        val s = snap
        value = if (s == null) null else withContext(Dispatchers.Default) {
            s.files(cat, dir).filter { File(it.path).exists() }
        }
    }
    var selected by remember { mutableStateOf<Set<File>>(emptySet()) }
    val list = files.orEmpty()
    val total = remember(files) { list.sumOf { it.size } }

    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    fun open(f: IFile) {
        val file = f.file
        when (f.kind) {
            Kind.VIDEO, Kind.AUDIO -> {
                val same = list.filter { it.kind == f.kind }.map { it.file }
                nav.go(Screen.Play(same, same.indexOf(file).coerceAtLeast(0)))
            }
            Kind.IMAGE -> nav.go(Screen.Image(file))
            else -> openExternal(ctx, file)
        }
    }

    WList {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(title, size = if (watch) 15.sp else 22.sp, weight = FontWeight.Bold, align = TextAlign.Center)
                Txt("${list.size} ${cat.unit} · ${Storage.fmt(total)}", size = 11.sp, color = Pal.dim, align = TextAlign.Center)
            }
        }
        if (selected.isNotEmpty()) item {
            Column(Modifier.curved().fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt("${selected.size} selected", size = 13.sp, color = Pal.accent, weight = FontWeight.Bold)
                Spacer(Modifier.size(4.dp))
                val sel = selected.toList()
                val s = if (watch) 34.dp else 44.dp
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                    RoundBtn(Ic.send, { nav.go(Screen.Send(sel)); selected = emptySet(); browser.pickHint = false }, size = s, bg = Pal.accent, tint = Pal.bg)
                    RoundBtn(Ic.share, { shareSystem(ctx, sel) }, size = s)
                    RoundBtn(Ic.selectAll, { selected = list.map { it.file }.toSet() }, size = s)
                    RoundBtn(Ic.delete, {
                        ui.confirm = Confirm("Delete ${sel.size} item(s)?", "This can't be undone.", "Delete", danger = true) {
                            scope.launch {
                                withContext(Dispatchers.IO) { sel.forEach { it.delete() } }
                                selected = emptySet(); version++; MediaIndex.invalidate()
                            }
                        }
                    }, size = s, bg = Pal.danger, tint = Pal.bg)
                    RoundBtn(Ic.close, { selected = emptySet() }, size = s)
                }
            }
        }
        if (files == null) item { Txt("Loading…", Modifier.padding(16.dp), color = Pal.dim) }
        else if (list.isEmpty()) item { Txt("Nothing here", Modifier.padding(16.dp), color = Pal.dim) }

        items(list, key = { it.path }) { f ->
            val file = f.file
            val sel = file in selected
            CatRow(
                name = f.name,
                sub = if (dir == null) "${Storage.fmt(f.size)} · ${File(f.dir).name}" else "${Storage.fmt(f.size)} · ${catDate.format(Date(f.mtime))}",
                path = f.path,
                kind = f.kind,
                icon = fileIcon(f.kind),
                selected = sel,
                onClick = {
                    if (selected.isNotEmpty()) selected = if (sel) selected - file else selected + file
                    else open(f)
                },
                onLongClick = { selected = if (sel) selected - file else selected + file },
            )
        }
    }
}

/** Same look as the rows of the file browser; shows a thumbnail for pictures, videos and music. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CatRow(
    name: String,
    sub: String,
    path: String,
    kind: Kind,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val watch = LocalIsWatch.current
    Row(
        Modifier.curved().fillMaxWidth()
            .clip(RoundedCornerShape(if (watch) 22.dp else 14.dp))
            .background(if (selected) Pal.accentDim else Pal.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = if (watch) 7.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(path, kind, icon, Modifier.size(if (watch) 30.dp else 40.dp), CircleShape, if (watch) 17.dp else 22.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Txt(name, size = if (watch) 13.sp else 15.sp, lines = if (watch) 1 else 2)
            Txt(sub, size = 11.sp, color = Pal.dim)
        }
        if (selected) Ico(Ic.check, Pal.accent, 16.dp)
    }
}
