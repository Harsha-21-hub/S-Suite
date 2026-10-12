package com.sdrive.shared.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.sdrive.shared.App
import com.sdrive.shared.Cat
import com.sdrive.shared.Platform
import com.sdrive.shared.AppState
import com.sdrive.shared.Kind
import com.sdrive.shared.Storage
import com.sdrive.shared.player.PlayerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Entry(val file: File, val kind: Kind, val size: Long, val mtime: Long)

class BrowserModel {
    var dir by mutableStateOf(Storage.root())
    var entries by mutableStateOf<List<Entry>>(emptyList())
    var selected by mutableStateOf<Set<File>>(emptySet())
    var sort by mutableIntStateOf(0) // 0 name, 1 newest, 2 largest
    var showHidden by mutableStateOf(false)
    var clip by mutableStateOf<List<File>>(emptyList())
    var clipCut by mutableStateOf(false)
    var busy by mutableStateOf<String?>(null)
    var version by mutableIntStateOf(0)
    /** Set by Transfer → Send: show "pick files to send" banner. */
    var pickHint by mutableStateOf(false)
    val list = LazyListState()

    fun reload() { version++ }

    fun read(): List<Entry> {
        val files = dir.listFiles().orEmpty().filter { showHidden || !it.name.startsWith(".") }
        val e = files.map { Entry(it, Storage.kind(it), if (it.isFile) it.length() else 0L, it.lastModified()) }
        val dirsFirst = compareBy<Entry> { it.kind != Kind.DIR }
        return when (sort) {
            1 -> e.sortedWith(dirsFirst.thenByDescending { it.mtime })
            2 -> e.sortedWith(dirsFirst.thenByDescending { it.size }.thenBy { it.file.name.lowercase() })
            else -> e.sortedWith(dirsFirst.thenBy { it.file.name.lowercase() })
        }
    }
}

fun kindIcon(k: Kind) = when (k) {
    Kind.DIR -> Ic.folder
    Kind.VIDEO -> Ic.movie
    Kind.AUDIO -> Ic.music
    Kind.IMAGE -> Ic.image
    else -> Ic.file
}

fun kindColor(k: Kind) = when (k) {
    Kind.DIR -> Pal.accent
    Kind.VIDEO -> Pal.video
    Kind.AUDIO -> Pal.audio
    Kind.IMAGE -> Pal.image
    else -> Pal.dim
}

private val dateFmt = SimpleDateFormat("d MMM yy", Locale.getDefault())

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(m: BrowserModel) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val ui = LocalUi.current
    val watch = LocalIsWatch.current
    val scope = rememberCoroutineScope()
    val tick = AppState.resumeTick
    val root = remember(tick) { Storage.root() }
    val allFiles = remember(tick) { Storage.hasAllFiles() }
    // Disk lookups done once, not on every recomposition (they are slow on the watch's storage).
    val recvDir = remember(tick, allFiles) { Storage.receiveDir() }
    val recvRel = remember(recvDir) { Storage.rel(recvDir) }

    LaunchedEffect(root) { if (!Storage.inside(root, m.dir)) m.dir = root }
    LaunchedEffect(m.dir, m.sort, m.showHidden, m.version, tick) {
        m.entries = withContext(Dispatchers.IO) { try { m.read() } catch (_: Exception) { emptyList() } }
    }
    LaunchedEffect(m.dir) { try { m.list.scrollToItem(0) } catch (_: Exception) {} }

    BackHandler(enabled = m.selected.isNotEmpty() || m.dir.path != root.path) {
        if (m.selected.isNotEmpty()) m.selected = emptySet()
        else m.dir.parentFile?.takeIf { Storage.inside(root, it) }?.let { m.dir = it }
    }

    fun open(e: Entry) {
        when (e.kind) {
            Kind.DIR -> m.dir = e.file
            Kind.VIDEO, Kind.AUDIO -> {
                val list = m.entries.filter { it.kind == e.kind }.map { it.file }
                nav.go(Screen.Play(list, list.indexOf(e.file).coerceAtLeast(0)))
            }
            Kind.IMAGE -> nav.go(Screen.Image(e.file))
            else -> openExternal(ctx, e.file)
        }
    }

    fun requestAllFiles() {
        val act = ctx.findActivity() ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            val tries = listOf(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName)),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            )
            for (i in tries) { try { act.startActivity(i); return } catch (_: Exception) {} }
            ui.confirm = Confirm(
                "Grant from PC",
                "This watch has no settings page for it. Run once on your PC (or phone Terminal → Grant all-files access):\nadb shell \"appops set --uid ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow\"",
                "OK",
            ) {}
        } else {
            ActivityCompat.requestPermissions(act, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 7)
        }
    }

    fun paste() {
        val items = m.clip; val cut = m.clipCut; val target = m.dir
        m.busy = if (cut) "Moving…" else "Copying…"
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                var e: String? = null
                items.forEach { f -> try { Storage.copyInto(f, target, cut) } catch (x: Exception) { e = x.message } }
                e
            }
            m.busy = null; m.clip = emptyList(); m.reload()
            App.toast(err ?: if (cut) "Moved" else "Copied")
        }
    }

    val now by produceState(System.currentTimeMillis()) { while (true) { delay(20_000); value = System.currentTimeMillis() } }
    val playing = PlayerEngine.peek()?.takeIf { it.player.currentMediaItem != null }

    WList(state = m.list) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (watch) Txt(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)), size = 12.sp, color = Pal.accent, weight = FontWeight.Bold)
                if (m.dir.path == root.path) Txt(App.NAME.uppercase(), size = if (watch) 15.sp else 22.sp, align = TextAlign.Center, font = DotFont)
                else Txt(m.dir.name, size = if (watch) 15.sp else 22.sp, weight = FontWeight.Bold, align = TextAlign.Center)
                if (!watch) Txt(Storage.rel(m.dir), size = 12.sp, color = Pal.dim)
                if (m.dir.path == root.path) Txt("${Storage.fmt(root.usableSpace)} free of ${Storage.fmt(root.totalSpace)}", size = 11.sp, color = Pal.dim)
            }
        }
        if (m.pickHint && m.selected.isEmpty()) item {
            Row(Modifier.curved().fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Chip("Pick files to send", { }, Modifier.weight(1f), icon = Ic.send, sub = "Long-press files, then tap Send")
                Spacer(Modifier.width(6.dp))
                RoundBtn(Ic.close, { m.pickHint = false }, size = 34.dp)
            }
        }
        if (!allFiles) item {
            Chip("Allow access to all files", { requestAllFiles() }, icon = Ic.lock, iconTint = Pal.warn,
                sub = "Needed to browse & play everything")
        }
        if (playing != null) item {
            val title = playing.player.mediaMetadata.title?.toString() ?: playing.player.mediaMetadata.displayTitle?.toString() ?: "Now playing"
            Chip(title, { nav.go(Screen.Play(null)) }, icon = if (playing.player.isPlaying) Ic.pause else Ic.play,
                sub = "Now playing", iconTint = Pal.audio)
        }
        if (m.selected.isEmpty()) {
            item {
                Row(Modifier.curved(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val s = if (watch) 36.dp else 44.dp
                    RoundBtn(Ic.wifi, { nav.go(Screen.Transfer) }, size = s, bg = Pal.accent, tint = Pal.bg)
                    RoundBtn(Ic.up, { m.dir.parentFile?.takeIf { Storage.inside(root, it) }?.let { m.dir = it } }, size = s, enabled = m.dir.path != root.path)
                    RoundBtn(Ic.add, {
                        ui.prompt = Prompt("New folder name") { n ->
                            if (File(m.dir, Storage.safeName(n)).mkdirs()) m.reload() else App.toast("Couldn't create folder")
                        }
                    }, size = s)
                    RoundBtn(Ic.sort, {
                        m.sort = (m.sort + 1) % 3
                        App.toast(arrayOf("Sort: name", "Sort: newest", "Sort: largest")[m.sort])
                    }, size = s)
                    if (!watch && Platform.adb != null) RoundBtn(Ic.terminal, { nav.go(Screen.Terminal) }, size = s)
                }
            }
            if (m.dir.path == root.path) {
                // Transfer is always the first thing on the home screen, categories right below it.
                item {
                    Chip("Transfer", { nav.go(Screen.Transfer) }, icon = Ic.wifi,
                        sub = "Send · receive · PC link & PIN")
                }
                item { SectionTitle("Categories") }
                Cat.entries.chunked(2).forEach { pair ->
                    item(key = "cat-" + pair[0].name) { CategoryTiles(pair) }
                }
                item { SectionTitle(if (allFiles) "Internal storage" else App.NAME) }
            }
            if (m.dir.path == root.path) item {
                Chip("Received files", { m.dir = recvDir }, icon = Ic.down, sub = recvRel)
            }
        } else {
            item {
                Column(Modifier.curved().fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Txt("${m.selected.size} selected", size = 13.sp, color = Pal.accent, weight = FontWeight.Bold)
                    Spacer(Modifier.size(4.dp))
                    val sel = m.selected.toList()
                    val s = if (watch) 34.dp else 44.dp
                    val actions = buildList<Pair<androidx.compose.ui.graphics.vector.ImageVector, () -> Unit>> {
                        add(Ic.send to { nav.go(Screen.Send(sel)); m.selected = emptySet(); m.pickHint = false })
                        add(Ic.share to { shareSystem(ctx, sel) })
                        add(Ic.copy to { m.clip = sel; m.clipCut = false; m.selected = emptySet(); App.toast("Open a folder, then Paste") })
                        add(Ic.cut to { m.clip = sel; m.clipCut = true; m.selected = emptySet(); App.toast("Open a folder, then Paste") })
                        if (sel.size == 1) add(Ic.edit to {
                            val f = sel[0]
                            ui.prompt = Prompt("Rename", f.name) { n ->
                                val d = File(f.parentFile, Storage.safeName(n))
                                if (!d.exists() && f.renameTo(d)) { m.selected = emptySet(); m.reload() } else App.toast("Rename failed")
                            }
                        })
                        add(Ic.selectAll to { m.selected = m.entries.map { it.file }.toSet() })
                        add(Ic.delete to {
                            ui.confirm = Confirm("Delete ${sel.size} item(s)?", "This can't be undone.", "Delete", danger = true) {
                                scope.launch {
                                    withContext(Dispatchers.IO) { sel.forEach { it.deleteRecursively() } }
                                    m.selected = emptySet(); m.reload()
                                }
                            }
                        })
                        add(Ic.close to { m.selected = emptySet() })
                    }
                    actions.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                            row.forEach { (icon, act) ->
                                RoundBtn(icon, act, size = s,
                                    bg = when (icon) { Ic.delete -> Pal.danger; Ic.send -> Pal.accent; else -> Pal.surface2 },
                                    tint = if (icon == Ic.delete || icon == Ic.send) Pal.bg else Pal.text)
                            }
                        }
                    }
                }
            }
        }
        if (m.clip.isNotEmpty()) item {
            Row(Modifier.curved().fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Chip("Paste ${m.clip.size} here", { paste() }, Modifier.weight(1f), icon = if (m.clipCut) Ic.cut else Ic.copy)
                Spacer(Modifier.width(6.dp))
                RoundBtn(Ic.close, { m.clip = emptyList() }, size = 34.dp)
            }
        }
        m.busy?.let { b -> item { Txt(b, color = Pal.accent) } }
        if (m.entries.isEmpty()) item { Txt("Empty folder", Modifier.padding(16.dp), color = Pal.dim) }

        items(m.entries, key = { it.file.path }) { e ->
            val sel = e.file in m.selected
            val c = kindColor(e.kind)
            Row(
                Modifier.curved().fillMaxWidth()
                    .clip(RoundedCornerShape(if (watch) 22.dp else 14.dp))
                    .background(if (sel) Pal.accentDim else Pal.surface)
                    .combinedClickable(
                        onClick = {
                            if (m.selected.isNotEmpty()) m.selected = if (sel) m.selected - e.file else m.selected + e.file
                            else open(e)
                        },
                        onLongClick = { m.selected = if (sel) m.selected - e.file else m.selected + e.file },
                    )
                    .padding(horizontal = 10.dp, vertical = if (watch) 7.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(if (watch) 30.dp else 40.dp).clip(CircleShape).background(c.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Ico(kindIcon(e.kind), c, if (watch) 17.dp else 22.dp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Txt(e.file.name, size = if (watch) 13.sp else 15.sp, lines = if (watch) 1 else 2)
                    Txt(
                        if (e.kind == Kind.DIR) "Folder · ${dateFmt.format(Date(e.mtime))}" else "${Storage.fmt(e.size)} · ${dateFmt.format(Date(e.mtime))}",
                        size = 11.sp, color = Pal.dim,
                    )
                }
                if (sel) Ico(Ic.check, Pal.accent, 16.dp)
            }
        }
        item {
            Txt(if (m.showHidden) "Hide hidden files" else "Show hidden files",
                Modifier.padding(8.dp).curved().background(Color.Transparent)
                    .combinedClickable(onClick = { m.showHidden = !m.showHidden }),
                size = 11.sp, color = Pal.dim)
        }
    }
}
