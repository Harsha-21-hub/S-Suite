package com.hesi.slog

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationConstants
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorder
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

val DialogBackground = Color(0xFF111111)
private val DraggingBackground = Color(0xFF151515)

/** Single-line text that shrinks its font size until it fits. */
@Composable
fun AutoResizeText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 18.sp
) {
    var currentSize by remember(text) { mutableStateOf(fontSize) }
    Text(
        text = text,
        modifier = modifier,
        color = Color.White,
        fontSize = currentSize,
        fontFamily = FontFamily(Font(R.font.ndot_regular)),
        softWrap = false,
        maxLines = 1,
        onTextLayout = { result ->
            if (result.hasVisualOverflow && currentSize.value > 12f) {
                currentSize = currentSize * 0.9f
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SLogScreen(
    viewModel: SLogViewModel,
    account: AuthState.SignedIn,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val uid = account.uid

    // Custom notification sound picker (default = the S Log tone, res/raw/slog_sound.ogg)
    var customSound by remember { mutableStateOf(SLogNotifications.hasCustomSound(context)) }
    val soundPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            SLogNotifications.setCustomSound(context, uri)
            customSound = true
            Toast.makeText(context, "Notification sound changed.", Toast.LENGTH_SHORT).show()
        }
    }

    val ndotFont = remember { FontFamily(Font(R.font.ndot_regular)) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val currentMonth by viewModel.currentMonth.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val records by viewModel.records.collectAsState()
    val logStats by viewModel.logStats.collectAsState()
    val dayStats by viewModel.dayStats.collectAsState()
    val plans by viewModel.plans.collectAsState()
    val dayMarks by viewModel.dayMarks.collectAsState()
    val celebration by viewModel.celebration.collectAsState()
    val pendingImport by viewModel.pendingImport.collectAsState()
    val deletingAccount by viewModel.deletingAccount.collectAsState()
    val deleteAccountError by viewModel.deleteAccountError.collectAsState()
    val toast by viewModel.toast.collectAsState()

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearToast()
        }
    }

    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showMonthPicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }
    var deletingLog by remember { mutableStateOf<LogItem?>(null) }
    var showProfile by remember { mutableStateOf(false) }
    var showDeleteAccount by remember { mutableStateOf(false) }
    var showSummaryPicker by remember { mutableStateOf(false) }
    var summaryTime by remember { mutableStateOf(EndOfDayReceiver.summaryTime(context)) }
    var timePickerLog by remember { mutableStateOf<LogItem?>(null) }
    var isNewLogTime by remember { mutableStateOf(false) }
    var pendingLogName by remember { mutableStateOf("") }
    var editingLog by remember { mutableStateOf<LogItem?>(null) }
    var editName by remember { mutableStateOf("") }
    var editTimes by remember { mutableStateOf("") }
    var editError by remember { mutableStateOf<String?>(null) }
    // Logs to share as a file (one log from its share icon, or all from Settings)
    var exportTarget by remember { mutableStateOf<List<LogItem>?>(null) }
    var csvPreview by remember { mutableStateOf<List<CsvRow>?>(null) }

    // Calendar starts collapsed to the current week. Drag down to expand, up to collapse.
    var isCollapsed by remember { mutableStateOf(true) }

    // Logs being deleted: they animate out first, then the delete is sent
    var removingIds by remember { mutableStateOf(setOf<String>()) }
    val listMemory = remember { ListMemory() }
    val scope = rememberCoroutineScope()

    // Switching dates slides the list in from the side of the new date
    val dateAnim = remember { Animatable(1f) }
    var dateDirection by remember { mutableIntStateOf(0) }
    var lastDate by remember { mutableStateOf(selectedDate) }
    LaunchedEffect(selectedDate) {
        if (selectedDate != lastDate) {
            dateDirection = if (selectedDate.isAfter(lastDate)) 1 else -1
            lastDate = selectedDate
            dateAnim.snapTo(0f)
            dateAnim.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
        }
    }

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                val rows = CsvImporter.parseAny(text, logs.map { it.name }.toSet())
                if (rows.isEmpty()) {
                    Toast.makeText(context, "No rows found in that file.", Toast.LENGTH_LONG).show()
                } else {
                    csvPreview = rows
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Couldn't read file: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }
    val openCsvPicker = {
        // "*/*" so .slog files (unknown type to Android) can be picked too
        csvLauncher.launch(arrayOf("*/*"))
    }

    val accent = Accent
    val today = LocalDate.now()


    val reorderState = rememberReorderableLazyListState(
        onMove = { from, to ->
            // by key: on past days the list hides logs that didn't exist yet
            val fromIdx = logs.indexOfFirst { it.id == from.key }
            val toIdx = logs.indexOfFirst { it.id == to.key }
            if (fromIdx >= 0 && toIdx >= 0) {
                val reordered = logs.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
                viewModel.updateLogOrder(reordered)
            }
        }
    )

    val collapseGesture = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures { change, dragAmount ->
            change.consume()
            if (dragAmount < -6f) isCollapsed = true   // drag up   -> collapse
            if (dragAmount > 6f) isCollapsed = false   // drag down -> expand
        }
    }

    // ---------------------------------------------------------------- Calendar
    val calendarSection: @Composable ColumnScope.() -> Unit = {
        Column {
            Box(modifier = Modifier.fillMaxWidth()) {
                // Nothing-style avatar with initials ("Sorra Sri Harsha" -> SSH);
                // tap for profile / sign out / delete account
                NothingAvatar(
                    initials = initialsOf(account.name),
                    font = ndotFont,
                    size = 50.dp,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .clip(CircleShape)
                        .clickable { showProfile = true }
                )
                IconButton(
                    onClick = { showSettings = true },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(48.dp)
                ) {
                    // dotted gear (matches the other dot icons); turns a little while Settings is open
                    val gearTurn by animateFloatAsState(
                        if (showSettings) 45f else 0f, tween(350, easing = FastOutSlowInEasing), label = "gear"
                    )
                    NothingGear(size = 30.dp, rotation = gearTurn)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Whole-day streaks (Max, Monthly, Current): a day counts when at least one
                // tick was made that day (partly done counts)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    StreakStat("Max", dayStats.best, DotGlyphs.CROWN, ndotFont, Color.White)
                    StreakStat("Monthly", dayStats.month, DotGlyphs.MONTH, ndotFont, Color.White)
                    StreakStat("Current", dayStats.current, DotGlyphs.FLAME, ndotFont, accent)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.changeMonth(-1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Prev", tint = Color.White)
                    }
                    AnimatedContent(
                        targetState = currentMonth,
                        transitionSpec = {
                            val forward = targetState > initialState
                            (slideInHorizontally(tween(260)) { if (forward) it / 2 else -it / 2 } + fadeIn(tween(260))) togetherWith
                                    (slideOutHorizontally(tween(260)) { if (forward) -it / 2 else it / 2 } + fadeOut(tween(180)))
                        },
                        label = "monthTitle"
                    ) { shown ->
                        val monthText = shown.format(DateTimeFormatter.ofPattern("MM/yyyy"))
                        val styledMonth = buildAnnotatedString {
                            withStyle(SpanStyle(color = Color.White)) { append(monthText[0].toString()) }
                            withStyle(SpanStyle(color = accent)) { append(monthText[1].toString()) }
                            withStyle(SpanStyle(color = Color.White)) { append(monthText.substring(2, 5)) }
                            withStyle(SpanStyle(color = accent)) { append(monthText.substring(5, 7)) }
                        }
                        Text(
                            text = styledMonth,
                            modifier = Modifier.clickable { showMonthPicker = true },
                            fontSize = 20.sp,
                            fontFamily = ndotFont
                        )
                    }
                    IconButton(onClick = { viewModel.changeMonth(1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next", tint = Color.White)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Calendar grid. In portrait the whole grid also responds to the up/down drag.
            Column(modifier = if (isLandscape) Modifier else collapseGesture) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    listOf("S", "M", "T", "W", "T", "F", "S").forEach { day ->
                        Text(
                            text = day,
                            modifier = Modifier.weight(1f),
                            color = accent,
                            fontFamily = ndotFont,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // The grid slides left/right when the month changes
                AnimatedContent(
                    targetState = currentMonth,
                    transitionSpec = {
                        val forward = targetState > initialState
                        (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { if (forward) it else -it } +
                                fadeIn(tween(300))) togetherWith
                                (slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { if (forward) -it else it } +
                                        fadeOut(tween(200)))
                    },
                    label = "monthGrid"
                ) { shownMonth ->
                Column {
                val daysInMonth = DateUtils.getDaysInMonth(shownMonth)
                val firstDayOffset = daysInMonth.first().dayOfWeek.value % 7
                val weekCount = ceil((firstDayOffset + daysInMonth.size) / 7.0).toInt()
                val selectedIndex = daysInMonth.indexOf(selectedDate)
                val todayIndex = daysInMonth.indexOf(today)
                val focusIndex = when {
                    selectedIndex >= 0 -> selectedIndex
                    todayIndex >= 0 -> todayIndex
                    else -> 0
                }
                val focusWeek = (firstDayOffset + focusIndex) / 7

                for (week in 0 until weekCount) {
                    // landscape: always the whole month (there's room, and no drag control)
                    val visible = isLandscape || !isCollapsed || week == focusWeek
                    val rowHeight by animateDpAsState(
                        targetValue = if (visible) 45.dp else 0.dp,
                        animationSpec = tween(AnimationConstants.DefaultDurationMillis),
                        label = "rowHeight"
                    )

                    AnimatedVisibility(
                        visible = visible,
                        enter = expandVertically(tween(AnimationConstants.DefaultDurationMillis)) +
                                fadeIn(tween(AnimationConstants.DefaultDurationMillis)),
                        exit = shrinkVertically(tween(AnimationConstants.DefaultDurationMillis)) +
                                fadeOut(tween(AnimationConstants.DefaultDurationMillis))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(rowHeight)
                                .clipToBounds(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            for (dayOfWeek in 0 until 7) {
                                val dayIndex = week * 7 + dayOfWeek - firstDayOffset
                                if (dayIndex < 0 || dayIndex >= daysInMonth.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                } else {
                                    val date = daysInMonth[dayIndex]
                                    // judged with the logs and times that existed ON that day
                                    val progress = if (shownMonth == currentMonth) dayMarks[date] else null
                                    val isFull = progress?.isFull == true
                                    // at least one tick that day -> the day counts (amber ✓)
                                    val counts = progress?.counts == true
                                    val isPartial = progress?.isPartial == true
                                    val somethingDue = (progress?.total ?: 0) > 0
                                    val isPast = date.isBefore(today)
                                    val isFuture = date.isAfter(today)
                                    val isSelected = date == selectedDate

                                    val dayColor = when {
                                        isFull || counts -> accent
                                        isFuture || date.isEqual(today) -> Color.White
                                        else -> Color.Gray
                                    }
                                    val selectedBg by animateColorAsState(
                                        if (isSelected) Color.DarkGray else Color.Transparent,
                                        tween(220),
                                        label = "daySel"
                                    )
                                    val markScale by animateFloatAsState(
                                        if (isFull || counts || (isPast && somethingDue)) 1f else 0f,
                                        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                                        label = "mark"
                                    )

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(if (rowHeight > 0.dp) 1f else 0.01f)
                                            .padding(4.dp)
                                            .alpha(if (date.isBefore(today.minusDays(2)) || isFuture) 0.4f else 1f)
                                            .clip(CircleShape)
                                            .background(selectedBg)
                                            .clickable(enabled = !isFuture) { selectedDate = date },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(date.dayOfMonth.toString(), color = dayColor, fontFamily = ndotFont)
                                        val iconModifier = Modifier
                                            .fillMaxSize(0.65f)
                                            .graphicsLayer { scaleX = markScale; scaleY = markScale; alpha = markScale }
                                        when {
                                            // done = every time due that day was ticked
                                            isFull -> Icon(Icons.Default.Check, "Done", iconModifier, tint = Color.Green)
                                            // partly done = counts for the streak
                                            counts -> Icon(Icons.Default.Check, "Partly done", iconModifier, tint = Color(0xFFFFB300))
                                            // past day with nothing ticked at all = missed.
                                            // Days before your logs existed get no mark.
                                            isPast && somethingDue ->
                                                Icon(Icons.Default.Close, "Missed", iconModifier, tint = accent)
                                            // today, part done: small dot, not a tick
                                            isPartial -> Box(
                                                modifier = Modifier
                                                    .align(Alignment.BottomCenter)
                                                    .padding(bottom = 3.dp)
                                                    .size(5.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFFFFB300))
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }
                }
            }
        }
    }

    // -------------------------------------------------------------------- Logs
    val logsSection: @Composable ColumnScope.() -> Unit = {
        val selectedDateStr = selectedDate.format(DateUtils.dbFormatter)
        // Only today and the previous 2 days can be edited
        val isEditable = !(selectedDate.isBefore(today.minusDays(2)) || selectedDate.isAfter(today))

        // Drag control  ↑ (DRAG) ↓ : drag down for the whole month, up for this week (tap toggles).
        // Portrait only - landscape always shows the whole month.
        if (!isLandscape) Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .then(collapseGesture)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { isCollapsed = !isCollapsed },
            contentAlignment = Alignment.Center
        ) {
            DragPill(expanded = !isCollapsed, font = ndotFont)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "LOGS FOR " + selectedDate
                    .format(DateTimeFormatter.ofPattern("dd MMM yyyy"))
                    .uppercase(Locale.ROOT),
                modifier = Modifier.weight(1f),
                color = accent,
                fontSize = 16.sp,
                fontFamily = ndotFont
            )
            TextButton(onClick = openCsvPicker) {
                Text("IMPORT", color = Color.Gray, fontSize = 12.sp, fontFamily = ndotFont)
            }
            if (logs.isNotEmpty() && isEditable) {
                TextButton(onClick = { showDeleteAllConfirm = true }) {
                    Text("DELETE ALL", color = Color.Gray, fontSize = 12.sp, fontFamily = ndotFont)
                }
            }
        }

        // Daily summary: what's still left today
        if (selectedDate == today) {
            val activeNow = logs.filter { it.isActiveFor(uid) }
            val totalToday = activeNow.sumOf { it.times.size }
            if (totalToday > 0) {
                val left = Streaks.leftFor(activeNow, records, uid, selectedDateStr)
                val leftColor by animateColorAsState(
                    if (left.isEmpty()) Color.Green else Color.LightGray, tween(300), label = "leftColor"
                )
                Text(
                    text = if (left.isEmpty()) "ALL DONE TODAY ✓"
                    else "LEFT TODAY (${left.size}): " + Streaks.describe(left),
                    color = leftColor,
                    fontSize = 12.sp,
                    fontFamily = ndotFont,
                    maxLines = 2,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .animateContentSize(tween(250))
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Logs that existed on the selected day (a log added later isn't part of earlier days)
        val dayLogs = logs.filter { plans[it.id]?.exists(selectedDate) ?: true }
        // after the first load settles, logs that show up are new -> they animate in
        LaunchedEffect(logs.isNotEmpty()) {
            if (logs.isNotEmpty() && !listMemory.ready) {
                delay(600)
                listMemory.known.addAll(logs.map { it.id })
                listMemory.ready = true
            }
        }
        val isPastDay = selectedDate.isBefore(today)

        if (logs.isEmpty()) {
            Text(
                "No logs yet. Tap + to add one, or IMPORT a CSV file.",
                color = Color.Gray,
                fontFamily = ndotFont
            )
        } else if (dayLogs.isEmpty()) {
            Text(
                "No logs on this day yet. Your logs start later.",
                color = Color.Gray,
                fontFamily = ndotFont
            )
        } else {
            LazyColumn(
                state = reorderState.listState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = dateAnim.value
                        alpha = 0.25f + 0.75f * p
                        translationX = (1f - p) * dateDirection * 48.dp.toPx()
                    }
                    .reorderable(reorderState)
            ) {
                items(dayLogs, key = { it.id }) { log ->
                    ReorderableItem(reorderState, key = log.id) { isDragging ->
                        val plan = plans[log.id]
                        // the times this log had on that day (later changes don't rewrite the past)
                        val dayTimes = if (isPastDay && plan != null) plan.timesOn(selectedDate) else log.times
                        val activeThatDay = if (isPastDay && plan != null) plan.activeOn(selectedDate) else log.isActiveFor(uid)
                        val logRecords = records[log.id].orEmpty().filter { it.date == selectedDateStr }
                        val myRecord = logRecords.firstOrNull { it.uid == uid }
                        val friendRecords = logRecords.filter { it.uid != uid }
                        val isActive = log.isActiveFor(uid)
                        val stats = logStats[log.id] ?: StreakStats()
                        val rowAlpha by animateFloatAsState(
                            if (isEditable && activeThatDay) 1f else 0.4f, tween(250), label = "rowAlpha"
                        )
                        val dragScale by animateFloatAsState(
                            if (isDragging) 1.03f else 1f,
                            spring(stiffness = Spring.StiffnessMediumLow),
                            label = "dragScale"
                        )
                        val dragBg by animateColorAsState(
                            if (isDragging) DraggingBackground else Color.Transparent, tween(150), label = "dragBg"
                        )

                        // new logs grow in; deleted logs shrink out before the delete is sent.
                        // Logs already known (first load, scrolling back, other dates) just show.
                        val removing = log.id in removingIds
                        val appear = remember(log.id) {
                            val fresh = listMemory.known.add(log.id) && listMemory.ready
                            MutableTransitionState(!fresh).apply { targetState = true }
                        }
                        LaunchedEffect(removing) { appear.targetState = !removing }
                        // full name: inside the list item the outer Column's ColumnScope version
                        // can't be used (Compose scope rule), so call the plain one
                        androidx.compose.animation.AnimatedVisibility(
                            visibleState = appear,
                            enter = expandVertically(tween(280, easing = FastOutSlowInEasing)) + fadeIn(tween(280)),
                            exit = shrinkVertically(tween(260, easing = FastOutSlowInEasing)) + fadeOut(tween(200))
                        ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .graphicsLayer {
                                    alpha = rowAlpha
                                    scaleX = dragScale
                                    scaleY = dragScale
                                }
                                .background(dragBg),
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(
                                modifier = Modifier.padding(top = 4.dp, end = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.Menu,
                                    contentDescription = "Drag",
                                    modifier = Modifier.detectReorder(reorderState),
                                    tint = Color.DarkGray
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                StreakStat("Max", stats.best, DotGlyphs.CROWN, ndotFont, Color.White, big = false)
                                StreakStat("Monthly", stats.month, DotGlyphs.MONTH, ndotFont, Color.LightGray, big = false)
                                StreakStat("Current", stats.current, DotGlyphs.FLAME, ndotFont, accent, big = false)
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .requiredHeight(32.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AutoResizeText(
                                        text = log.name.uppercase(Locale.ROOT),
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 8.dp)
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier.size(32.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Switch(
                                                checked = isActive,
                                                onCheckedChange = { viewModel.toggleLogActive(log, it, selectedDateStr) },
                                                modifier = Modifier.scale(0.65f),
                                                enabled = isEditable,
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = Color.White,
                                                    checkedTrackColor = accent,
                                                    uncheckedThumbColor = Color.Gray
                                                )
                                            )
                                        }
                                        SmallIconAction(Icons.Default.Share, "Share") {
                                            exportTarget = listOf(log)
                                        }
                                        SmallIconAction(Icons.Default.Edit, "Edit", enabled = isEditable) {
                                            editingLog = log
                                            editName = log.name
                                            editTimes = log.times.joinToString(", ")
                                            editError = null
                                        }
                                        SmallIconAction(Icons.Default.Delete, "Delete", enabled = isEditable) {
                                            deletingLog = log
                                        }
                                    }
                                }

                                if (log.isShared()) {
                                    val others = log.memberEmails.size - 1
                                    Text(
                                        text = if (log.isOwner(uid)) "SHARED WITH $others"
                                        else "SHARED BY ${log.ownerName.uppercase(Locale.ROOT)}",
                                        color = Color.Gray,
                                        fontSize = 10.sp,
                                        fontFamily = ndotFont
                                    )
                                }

                                FlowRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .offset(y = (-4).dp)
                                        .animateContentSize(tween(250)),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(0.dp)
                                ) {
                                    // times are always sorted earliest-first
                                    dayTimes.forEach { time ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(
                                                checked = myRecord?.isChecked(time) ?: false,
                                                onCheckedChange = { checked ->
                                                    viewModel.toggleCheckbox(log, selectedDateStr, time, checked)
                                                },
                                                modifier = Modifier.scale(0.85f),
                                                enabled = activeThatDay && isEditable,
                                                colors = CheckboxDefaults.colors(
                                                    checkedColor = accent,
                                                    uncheckedColor = Color.Gray,
                                                    checkmarkColor = Color.White
                                                )
                                            )
                                            Text(
                                                text = DateUtils.formatTo12Hour(time),
                                                color = Color.LightGray,
                                                fontSize = 14.sp,
                                                fontFamily = ndotFont
                                            )
                                        }
                                    }

                                    // a new time applies from the day being viewed on (earlier days keep theirs)
                                    val canAddTime = activeThatDay && isEditable
                                    Box(
                                        modifier = Modifier
                                            .clickable(enabled = canAddTime) { timePickerLog = log }
                                            .padding(8.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Add,
                                            contentDescription = "Add Time",
                                            modifier = Modifier.size(20.dp),
                                            tint = if (canAddTime) accent else Color.DarkGray
                                        )
                                    }
                                }

                                // Friends' progress on this log for the selected day
                                if (log.isShared() && friendRecords.isNotEmpty()) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        friendRecords.forEach { r ->
                                            val done = dayTimes.count { r.isChecked(it) }
                                            val full = dayTimes.isNotEmpty() && done >= dayTimes.size
                                            Text(
                                                text = "${r.userName.uppercase(Locale.ROOT)} $done/${dayTimes.size}",
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Color(0xFF1C1C1C))
                                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                                color = if (full) Color.Green else Color.LightGray,
                                                fontSize = 11.sp,
                                                fontFamily = ndotFont
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ Layout
    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = Color.Black,
        floatingActionButton = {
            val canAdd = !(selectedDate.isBefore(today.minusDays(2)) || selectedDate.isAfter(today))
            AnimatedVisibility(
                visible = canAdd,
                enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
                exit = scaleOut(tween(180)) + fadeOut(tween(180))
            ) {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = accent,
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Log")
                }
            }
        }
    ) { paddingValues ->
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(end = 16.dp)
                ) {
                    calendarSection()
                }
                VerticalDivider(thickness = 1.dp, color = Color.DarkGray)
                Column(
                    modifier = Modifier
                        .weight(1.3f)
                        .fillMaxHeight()
                        .padding(start = 16.dp)
                ) {
                    logsSection()
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
            ) {
                calendarSection()
                Column(modifier = Modifier.weight(1f)) {
                    logsSection()
                }
            }
        }
    }

    // Max-streak milestone (3, 10, 50, 100 ... days): animated dot-matrix popup
    var shownCelebration by remember { mutableStateOf<SLogViewModel.Celebration?>(null) }
    if (celebration != null) shownCelebration = celebration
    AnimatedVisibility(
        visible = celebration != null,
        enter = fadeIn(tween(250)),
        exit = fadeOut(tween(300))
    ) {
        shownCelebration?.let { c ->
            MilestoneCelebration(
                days = c.days,
                labels = c.labels,
                font = ndotFont,
                onDismiss = { viewModel.dismissCelebration() }
            )
        }
    }
    }

    // ----------------------------------------------------------------- Dialogs

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) {
                    Text("CLOSE", color = accent, fontFamily = ndotFont)
                }
            },
            title = { Text("SETTINGS", color = Color.White, fontFamily = ndotFont) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SettingsButton("IMPORT LOGS (.CSV / .SLOG)", ndotFont) {
                        showSettings = false
                        openCsvPicker()
                    }
                    if (logs.isNotEmpty()) {
                        SettingsButton("SHARE ALL LOGS", ndotFont) {
                            showSettings = false
                            exportTarget = logs
                        }
                    }
                    SettingsButton("ALLOW BACKGROUND EXECUTION", ndotFont) {
                        val intent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        intent.data = Uri.parse("package:" + context.packageName)
                        context.startActivity(intent)
                    }
                    SettingsButton("SELECT CUSTOM NOTIFICATION SOUND", ndotFont) {
                        soundPickerLauncher.launch(arrayOf("audio/*"))
                    }
                    if (customSound) {
                        SettingsButton("RESET TO S LOG TONE", ndotFont) {
                            SLogNotifications.resetToDefaultSound(context)
                            customSound = false
                            Toast.makeText(context, "Back to the S Log tone.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    SettingsButton(
                        "DAILY SUMMARY: " + DateUtils.formatTo12Hour(TimeUtils.format(summaryTime.first, summaryTime.second)),
                        ndotFont
                    ) {
                        showSettings = false
                        showSummaryPicker = true
                    }
                }
            },
            containerColor = DialogBackground
        )
    }

    // A .csv / .slog file opened from another app ("Open with S Log")
    LaunchedEffect(pendingImport) {
        val text = pendingImport ?: return@LaunchedEffect
        viewModel.clearImport()
        try {
            val rows = CsvImporter.parseAny(text, logs.map { it.name }.toSet())
            if (rows.isEmpty()) Toast.makeText(context, "No logs found in that file.", Toast.LENGTH_LONG).show()
            else csvPreview = rows
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't read file: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    if (showProfile) {
        ProfileDialog(
            name = account.name,
            email = account.email,
            initials = initialsOf(account.name),
            font = ndotFont,
            onSignOut = {
                showProfile = false
                onSignOut()
            },
            onDeleteAccount = {
                showProfile = false
                viewModel.clearDeleteAccountError()
                showDeleteAccount = true
            },
            onDismiss = { showProfile = false }
        )
    }

    if (showDeleteAccount) {
        DeleteAccountDialog(
            font = ndotFont,
            busy = deletingAccount,
            error = deleteAccountError,
            onDelete = { password -> viewModel.deleteAccount(password) },
            onCancel = {
                if (!deletingAccount) {
                    showDeleteAccount = false
                    viewModel.clearDeleteAccountError()
                }
            }
        )
    }

    if (showSummaryPicker) {
        val summaryState = rememberTimePickerState(initialHour = summaryTime.first, initialMinute = summaryTime.second)
        AlertDialog(
            onDismissRequest = { showSummaryPicker = false },
            confirmButton = {
                Button(
                    onClick = {
                        EndOfDayReceiver.setSummaryTime(context, summaryState.hour, summaryState.minute)
                        summaryTime = summaryState.hour to summaryState.minute
                        showSummaryPicker = false
                        Toast.makeText(
                            context,
                            "Daily summary at " + DateUtils.formatTo12Hour(TimeUtils.format(summaryState.hour, summaryState.minute)),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Text("SAVE", fontFamily = ndotFont)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSummaryPicker = false }) {
                    Text("CANCEL", color = Color.LightGray, fontFamily = ndotFont)
                }
            },
            title = { Text("DAILY SUMMARY TIME", color = Color.White, fontFamily = ndotFont) },
            text = {
                Column {
                    Text(
                        "Every day at this time you get a notification listing what's still left.",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TimePicker(
                        state = summaryState,
                        colors = TimePickerDefaults.colors(
                            clockDialColor = Color.DarkGray,
                            clockDialSelectedContentColor = Color.White,
                            clockDialUnselectedContentColor = Color.LightGray,
                            selectorColor = accent,
                            containerColor = DialogBackground,
                            periodSelectorSelectedContainerColor = accent,
                            periodSelectorUnselectedContainerColor = Color.DarkGray,
                            periodSelectorSelectedContentColor = Color.White,
                            timeSelectorSelectedContainerColor = accent,
                            timeSelectorUnselectedContainerColor = Color.DarkGray,
                            timeSelectorSelectedContentColor = Color.White
                        )
                    )
                }
            },
            containerColor = DialogBackground
        )
    }

    if (showDeleteAllConfirm) {
        ConfirmDialog(
            title = "DELETE ALL LOGS?",
            message = "This deletes every log you created, including all ticks, on all your devices. " +
                    "Logs shared with you are only removed from your list. This can't be undone.",
            font = ndotFont,
            onYes = {
                showDeleteAllConfirm = false
                val ids = logs.map { it.id }.toSet()
                removingIds = removingIds + ids
                scope.launch {
                    delay(280)
                    viewModel.deleteAllLogs()
                    delay(1500)
                    removingIds = removingIds - ids // anything that couldn't be deleted shows again
                }
            },
            onNo = { showDeleteAllConfirm = false }
        )
    }

    deletingLog?.let { log ->
        val owner = log.isOwner(uid)
        ConfirmDialog(
            title = if (owner) "DELETE LOG?" else "LEAVE LOG?",
            message = when {
                owner && log.isShared() -> "'${log.name}' and all its ticks will be deleted for you and everyone it's shared with."
                owner -> "'${log.name}' and all its ticks will be deleted on all your devices."
                else -> "'${log.name}' will be removed from your list. The owner keeps it."
            },
            font = ndotFont,
            onYes = {
                deletingLog = null
                removingIds = removingIds + log.id
                scope.launch {
                    delay(280) // let the row shrink away first
                    viewModel.deleteLog(log)
                    delay(1500)
                    removingIds = removingIds - log.id
                }
            },
            onNo = { deletingLog = null }
        )
    }

    csvPreview?.let { rows ->
        CsvPreviewDialog(
            rows = rows,
            font = ndotFont,
            onImport = { selected, includeHistory ->
                csvPreview = null
                viewModel.importRows(selected, includeHistory)
            },
            onCancel = { csvPreview = null }
        )
    }

    exportTarget?.let { target ->
        ExportDialog(
            title = if (target.size == 1) "SHARE \"${target[0].name.uppercase(Locale.ROOT)}\""
            else "SHARE ${target.size} LOGS",
            font = ndotFont,
            onPick = { format ->
                exportTarget = null
                try {
                    LogExporter.share(context, target, records, uid, account.name, format)
                } catch (e: Exception) {
                    Toast.makeText(context, "Couldn't share: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { exportTarget = null }
        )
    }

    if (showAddDialog) {
        var newLogName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        if (newLogName.isNotBlank()) {
                            pendingLogName = newLogName.trim()
                            isNewLogTime = true
                            showAddDialog = false
                            // Placeholder so the time picker opens; the real log is created on SAVE
                            timePickerLog = LogItem(
                                id = "", name = pendingLogName, times = emptyList(),
                                ownerUid = uid, ownerEmail = account.email, ownerName = account.name,
                                memberEmails = listOf(account.email), userState = emptyMap(), createdAt = 0L
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Text("NEXT", fontFamily = ndotFont)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("CANCEL", color = Color.LightGray, fontFamily = ndotFont)
                }
            },
            title = { Text("NEW LOG", color = Color.White, fontFamily = ndotFont) },
            text = {
                OutlinedTextField(
                    value = newLogName,
                    onValueChange = { newLogName = it },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = Color.White, fontFamily = ndotFont),
                    label = { Text("Name", color = Color.LightGray, fontFamily = ndotFont) },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent)
                )
            },
            containerColor = DialogBackground
        )
    }

    editingLog?.let { logToEdit ->
        AlertDialog(
            onDismissRequest = { editingLog = null },
            confirmButton = {
                Button(
                    onClick = {
                        val (times, bad) = TimeUtils.parseList(editTimes)
                        when {
                            editName.isBlank() -> editError = "Name can't be empty."
                            bad.isNotEmpty() -> editError = "Invalid time: " + bad.joinToString(", ")
                            else -> {
                                viewModel.updateLog(logToEdit, editName, times, selectedDate.format(DateUtils.dbFormatter))
                                editingLog = null
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Text("SAVE", fontFamily = ndotFont)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingLog = null }) {
                    Text("CANCEL", color = Color.LightGray, fontFamily = ndotFont)
                }
            },
            title = { Text("EDIT LOG", color = Color.White, fontFamily = ndotFont) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        textStyle = LocalTextStyle.current.copy(color = Color.White, fontFamily = ndotFont),
                        label = { Text("Name", color = Color.LightGray, fontFamily = ndotFont) },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = editTimes,
                        onValueChange = {
                            editTimes = it
                            editError = null
                        },
                        textStyle = LocalTextStyle.current.copy(color = Color.White, fontFamily = ndotFont),
                        label = { Text("Times", color = Color.LightGray, fontFamily = ndotFont) },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent)
                    )
                    Text(
                        text = editError ?: "Format: 07:00, 12:30, 19:00 (saved earliest first)",
                        modifier = Modifier.padding(top = 6.dp),
                        color = if (editError != null) accent else Color.Gray,
                        fontSize = 11.sp,
                        fontFamily = ndotFont
                    )
                }
            },
            containerColor = DialogBackground
        )
    }

    timePickerLog?.let { targetLog ->
        val timePickerState = rememberTimePickerState()
        AlertDialog(
            onDismissRequest = {
                timePickerLog = null
                isNewLogTime = false
            },
            confirmButton = {
                Button(
                    onClick = {
                        val time = TimeUtils.format(timePickerState.hour, timePickerState.minute)
                        if (isNewLogTime) {
                            // starts on the day being viewed, so it shows up right there
                            viewModel.addLog(pendingLogName, listOf(time), selectedDate.format(DateUtils.dbFormatter))
                        } else {
                            viewModel.appendTimeToLog(targetLog, time, selectedDate.format(DateUtils.dbFormatter))
                        }
                        timePickerLog = null
                        isNewLogTime = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Text("SAVE", fontFamily = ndotFont)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    timePickerLog = null
                    isNewLogTime = false
                }) {
                    Text("CANCEL", color = Color.LightGray, fontFamily = ndotFont)
                }
            },
            title = {
                Text(
                    text = if (isNewLogTime) "SET INITIAL TIME" else "ADD TIME",
                    color = Color.White,
                    fontFamily = ndotFont
                )
            },
            text = {
                TimePicker(
                    state = timePickerState,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = Color.DarkGray,
                        clockDialSelectedContentColor = Color.White,
                        clockDialUnselectedContentColor = Color.LightGray,
                        selectorColor = accent,
                        containerColor = DialogBackground,
                        periodSelectorSelectedContainerColor = accent,
                        periodSelectorUnselectedContainerColor = Color.DarkGray,
                        periodSelectorSelectedContentColor = Color.White,
                        timeSelectorSelectedContainerColor = accent,
                        timeSelectorUnselectedContainerColor = Color.DarkGray,
                        timeSelectorSelectedContentColor = Color.White
                    )
                )
            },
            containerColor = DialogBackground
        )
    }

    if (showMonthPicker) {
        var pickerYear by remember { mutableIntStateOf(currentMonth.year) }
        val months = listOf(
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
            "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"
        )
        AlertDialog(
            onDismissRequest = { showMonthPicker = false },
            confirmButton = {},
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { pickerYear-- }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Prev", tint = Color.White)
                    }
                    Text(pickerYear.toString(), color = accent, fontSize = 24.sp, fontFamily = ndotFont)
                    IconButton(onClick = { pickerYear++ }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next", tint = Color.White)
                    }
                }
            },
            text = {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(months.indices.toList()) { monthIndex ->
                        val isSelected = currentMonth.year == pickerYear &&
                                currentMonth.monthValue == monthIndex + 1
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (isSelected) accent else Color.Transparent)
                                .clickable {
                                    viewModel.setMonth(YearMonth.of(pickerYear, monthIndex + 1))
                                    showMonthPicker = false
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = months[monthIndex],
                                color = if (isSelected) Color.White else Color.Gray,
                                fontFamily = ndotFont
                            )
                        }
                    }
                }
            },
            containerColor = DialogBackground
        )
    }
}

@Composable
private fun SmallIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true,
    tint: Color = Color.DarkGray,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(4.dp)
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp), tint = tint)
    }
}

@Composable
private fun SettingsButton(label: String, font: FontFamily, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, fontFamily = font)
    }
}

/** "Sorra Sri Harsha" -> "SSH", "Ravi Teja" -> "RT", "Teja" -> "T" (max 3 letters). */
fun initialsOf(name: String): String =
    name.trim().split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .take(3)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "?" }

/** Remembers which logs the list has already shown, so only brand-new logs animate in. */
private class ListMemory {
    val known = HashSet<String>()
    var ready = false
}
