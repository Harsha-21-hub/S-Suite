package com.hesi.slog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

class SLogViewModel(application: Application) : AndroidViewModel(application) {

    private var uid: String = ""
    private var email: String = ""

    private val _currentMonth = MutableStateFlow(YearMonth.now())
    val currentMonth: StateFlow<YearMonth> = _currentMonth.asStateFlow()

    /** Logs I own or that were shared with me, in my order. */
    private val _logs = MutableStateFlow<List<LogItem>>(emptyList())
    val logs: StateFlow<List<LogItem>> = _logs.asStateFlow()

    /** logId -> ticks of every member (recent window + visible month). */
    private val _records = MutableStateFlow<Map<String, List<DayRecord>>>(emptyMap())
    val records: StateFlow<Map<String, List<DayRecord>>> = _records.asStateFlow()

    /** logId -> my streaks on that log (month / best ever / current). Half-done days don't count. */
    private val _logStats = MutableStateFlow<Map<String, StreakStats>>(emptyMap())
    val logStats: StateFlow<Map<String, StreakStats>> = _logStats.asStateFlow()

    /** Whole-day streaks: days on which every active log was fully done. */
    private val _dayStats = MutableStateFlow(StreakStats())
    val dayStats: StateFlow<StreakStats> = _dayStats.asStateFlow()

    /** Best whole-day streak saved on my user entry (null until loaded). */
    private val _storedDayBest = MutableStateFlow<Int?>(null)
    /** Highest whole-day milestone already celebrated (null = never saved). */
    @Volatile
    private var celebratedDay: Int? = null

    /** logId -> what the log asked for on each day (history-aware). */
    private val _plans = MutableStateFlow<Map<String, LogPlan>>(emptyMap())
    val plans: StateFlow<Map<String, LogPlan>> = _plans.asStateFlow()

    /** Calendar marks for the month on screen (days up to today): done/total of what was due. */
    private val _dayMarks = MutableStateFlow<Map<LocalDate, DayProgress>>(emptyMap())
    val dayMarks: StateFlow<Map<LocalDate, DayProgress>> = _dayMarks.asStateFlow()

    /** Streak milestone popup (3, 10, 50, 100 ... days). Null = nothing to show. */
    data class Celebration(val days: Int, val labels: List<String>)

    private val _celebration = MutableStateFlow<Celebration?>(null)
    val celebration: StateFlow<Celebration?> = _celebration.asStateFlow()
    private val celebrationQueue = ArrayDeque<Celebration>()
    /** Milestones celebrated in this session (before the database echoes them back). */
    private val celebratedLocal = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Text of a .csv/.slog file opened from another app ("Open with S Log"), waiting for preview. */
    private val _pendingImport = MutableStateFlow<String?>(null)
    val pendingImport: StateFlow<String?> = _pendingImport.asStateFlow()

    /** Delete-account progress: busy flag + error text for the dialog. */
    private val _deletingAccount = MutableStateFlow(false)
    val deletingAccount: StateFlow<Boolean> = _deletingAccount.asStateFlow()
    private val _deleteAccountError = MutableStateFlow<String?>(null)
    val deleteAccountError: StateFlow<String?> = _deleteAccountError.asStateFlow()

    fun clearDeleteAccountError() {
        _deleteAccountError.value = null
    }

    /** On success Firebase signs out by itself and the app returns to the login screen. */
    fun deleteAccount(password: String) {
        if (password.isBlank()) {
            _deleteAccountError.value = "Enter your password."
            return
        }
        if (_deletingAccount.value) return
        viewModelScope.launch {
            _deletingAccount.value = true
            _deleteAccountError.value = null
            try {
                AlarmScheduler.cancelAll(getApplication<Application>())
                FirebaseRepo.deleteAccount(password)
            } catch (e: com.google.firebase.auth.FirebaseAuthInvalidCredentialsException) {
                _deleteAccountError.value = "Wrong password."
            } catch (e: Exception) {
                _deleteAccountError.value = e.localizedMessage ?: "Couldn't delete. Check your connection."
            } finally {
                _deletingAccount.value = false
            }
        }
    }

    fun offerImport(text: String) {
        _pendingImport.value = text
    }

    fun clearImport() {
        _pendingImport.value = null
    }

    /** One-off messages for a toast (share errors, import results...). */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private var logsRegistration: ListenerRegistration? = null
    private var profileRegistration: ListenerRegistration? = null
    private val recordRegistrations = mutableMapOf<String, ListenerRegistration>()
    private var windowStart: String = ""

    init {
        viewModelScope.launch(Dispatchers.Default) {
            combine(_logs, _records, _currentMonth, _storedDayBest) { logs, records, month, storedDayBest ->
                // never let one bad calculation stop the live updates
                try {
                    computeStats(logs, records, month, storedDayBest)
                } catch (e: Exception) {
                    android.util.Log.e("SLog", "computeStats", e)
                }
            }.collect { }
        }
    }

    fun clearToast() {
        _toast.value = null
    }

    /** Called once the user is signed in (and again if a different user signs in). */
    fun start(uid: String, email: String) {
        if (this.uid == uid && logsRegistration != null) return
        stop()
        this.uid = uid
        this.email = email
        windowStart = computeWindowStart(_currentMonth.value)

        // user entry gone: account deleted on another device? -> checkAccount signs out (login screen)
        profileRegistration = FirebaseRepo.listenProfile(uid, onGone = {
            viewModelScope.launch { FirebaseRepo.checkAccount() }
        }) { best, celebrated ->
            celebratedDay = celebrated
            _storedDayBest.value = best
        }

        logsRegistration = FirebaseRepo.listenLogs(email) { items ->
            val sorted = items.sortedWith(compareBy<LogItem>({ it.orderFor(uid) }, { it.createdAt }))
            _logs.value = sorted
            syncRecordListeners(sorted.map { it.id }.toSet())
            AlarmScheduler.scheduleExactAlarms(getApplication<Application>(), sorted, uid)
        }
    }

    fun stop() {
        logsRegistration?.remove()
        logsRegistration = null
        profileRegistration?.remove()
        profileRegistration = null
        _storedDayBest.value = null
        celebratedDay = null
        synchronized(celebrationQueue) {
            celebrationQueue.clear()
            celebratedLocal.clear()
            _celebration.value = null
        }
        recordRegistrations.values.forEach { it.remove() }
        recordRegistrations.clear()
        recordRetries.clear()
        _logs.value = emptyList()
        _records.value = emptyMap()
        _plans.value = emptyMap()
        _dayMarks.value = emptyMap()
        uid = ""
        email = ""
    }

    fun signOutCleanup() {
        AlarmScheduler.cancelAll(getApplication<Application>())
        stop()
    }

    override fun onCleared() {
        stop()
    }

    // ------------------------------------------------------------- listeners

    /** Ticks from ~6 months ago (for streaks) or the start of the visible month, whichever is earlier. */
    private fun computeWindowStart(month: YearMonth): String {
        val recent = LocalDate.now().minusDays(180)
        val monthStart = month.atDay(1)
        return (if (monthStart.isBefore(recent)) monthStart else recent).format(DateUtils.dbFormatter)
    }

    private fun syncRecordListeners(ids: Set<String>) {
        (recordRegistrations.keys - ids).forEach { gone ->
            recordRegistrations.remove(gone)?.remove()
            _records.value = _records.value - gone
        }
        (ids - recordRegistrations.keys).forEach { id -> attachRecords(id) }
    }

    /** Failed re-listen attempts per log (reset when the log's listeners restart). */
    private val recordRetries = mutableMapOf<String, Int>()

    private fun attachRecords(id: String) {
        recordRegistrations[id] = FirebaseRepo.listenRecords(
            id, windowStart,
            onError = {
                // A brand-new log isn't on the server for a moment, so the server refuses to show
                // its ticks and Firestore stops the listener. Listen again shortly (ticks are kept).
                recordRegistrations.remove(id)?.remove()
                val n = (recordRetries[id] ?: 0) + 1
                recordRetries[id] = n
                if (n <= 8) viewModelScope.launch {
                    delay(minOf(800L * n, 6000L))
                    if (uid.isNotEmpty() && id !in recordRegistrations && _logs.value.any { it.id == id }) {
                        attachRecords(id)
                    }
                }
            }
        ) { recs ->
            _records.value = _records.value + (id to recs)
        }
    }

    private fun restartRecordListeners() {
        recordRegistrations.values.forEach { it.remove() }
        recordRegistrations.clear()
        recordRetries.clear()
        syncRecordListeners(_logs.value.map { it.id }.toSet())
    }

    private fun computeStats(
        logs: List<LogItem>,
        records: Map<String, List<DayRecord>>,
        month: YearMonth,
        storedDayBest: Int?
    ) {
        val me = uid
        if (me.isEmpty()) return
        val user = FirebaseRepo.currentUser
        val today = LocalDate.now()

        // what every log asked for on every day (logs/times added later don't touch earlier days)
        val plans = logs.associate { it.id to LogPlan(it, me, records[it.id].orEmpty()) }
        _plans.value = plans

        val reached = mutableListOf<Pair<Int, String>>() // (milestone, label)

        val perLog = mutableMapOf<String, StreakStats>()
        for (log in logs) {
            val plan = plans.getValue(log.id)
            val stats = Streaks.stats(Streaks.completeDays(plan, records[log.id].orEmpty(), me), month, log.bestFor(me))
            perLog[log.id] = stats
            // remember a new best on the log (only ever goes up)
            if (stats.best > log.bestFor(me)) FirebaseRepo.saveLogBest(log.id, me, stats.best)

            val base = maxOf(
                celebratedLocal[log.id] ?: 0,
                log.celebratedFor(me) ?: Streaks.milestoneFloor(log.bestFor(me))
            )
            val m = Streaks.milestoneFloor(stats.best)
            if (m > base) {
                celebratedLocal[log.id] = m
                FirebaseRepo.saveLogCelebrated(log.id, me, m)
                reached += m to log.name.uppercase()
            }
        }
        _logStats.value = perLog

        val from = LogPlan.parse(windowStart) ?: today.minusDays(180)
        val planList = logs.map { plans.getValue(it.id) }
        val whole = Streaks.stats(
            Streaks.completeWholeDays(planList, records, me, from, today), month, storedDayBest ?: 0
        )
        _dayStats.value = whole
        // only save once the saved value is loaded, so a bigger saved best is never overwritten
        if (storedDayBest != null && whole.best > storedDayBest && user != null) {
            FirebaseRepo.saveDayBest(user, whole.best)
        }
        if (storedDayBest != null && user != null) {
            val base = maxOf(celebratedLocal[DAY_KEY] ?: 0, celebratedDay ?: Streaks.milestoneFloor(storedDayBest))
            val m = Streaks.milestoneFloor(whole.best)
            if (m > base) {
                celebratedLocal[DAY_KEY] = m
                FirebaseRepo.saveDayCelebrated(user, m)
                reached.add(0, m to "ALL LOGS")
            }
        }

        // calendar marks for the month on screen
        val mine = logs.associate { it.id to Streaks.myRecords(records[it.id].orEmpty(), me) }
        val marks = HashMap<LocalDate, DayProgress>()
        for (d in DateUtils.getDaysInMonth(month)) {
            if (d.isAfter(today)) break
            marks[d] = Streaks.dayProgress(planList, mine, d)
        }
        _dayMarks.value = marks

        if (reached.isNotEmpty()) {
            // one popup per milestone, listing everything that reached it
            reached.groupBy({ it.first }, { it.second }).toSortedMap().forEach { (days, labels) ->
                queueCelebration(Celebration(days, labels))
            }
        }
    }

    private fun queueCelebration(c: Celebration) {
        synchronized(celebrationQueue) {
            val showing = _celebration.value
            val waitingIdx = celebrationQueue.indexOfFirst { it.days == c.days }
            when {
                showing == null -> _celebration.value = c
                // same milestone as the popup on screen / one waiting -> one popup listing everything
                showing.days == c.days ->
                    _celebration.value = showing.copy(labels = (showing.labels + c.labels).distinct())
                waitingIdx >= 0 -> {
                    val w = celebrationQueue[waitingIdx]
                    celebrationQueue[waitingIdx] = w.copy(labels = (w.labels + c.labels).distinct())
                }
                else -> {
                    // smallest milestone first
                    val at = celebrationQueue.indexOfFirst { it.days > c.days }
                    if (at < 0) celebrationQueue.addLast(c) else celebrationQueue.add(at, c)
                }
            }
        }
    }

    fun dismissCelebration() {
        synchronized(celebrationQueue) {
            _celebration.value = celebrationQueue.removeFirstOrNull()
        }
    }

    // ----------------------------------------------------------------- month

    fun changeMonth(offset: Long) = setMonth(_currentMonth.value.plusMonths(offset))

    fun setMonth(yearMonth: YearMonth) {
        _currentMonth.value = yearMonth
        val needed = computeWindowStart(yearMonth)
        if (uid.isNotEmpty() && needed < windowStart) {
            windowStart = needed
            restartRecordListeners()
        }
    }

    // ------------------------------------------------------------------ logs

    /** [startDate]: the day being viewed (a log added while looking at yesterday starts yesterday). */
    fun addLog(name: String, times: List<String>, startDate: String = DateUtils.getCurrentDateString()) {
        val user = FirebaseRepo.currentUser ?: return
        val today = DateUtils.getCurrentDateString()
        val id = FirebaseRepo.addLog(user, name, times, _logs.value.size, startDate = minOf(startDate, today))
        enqueueAiMessages(id, name)
    }

    /** 7 AI notification messages for the coming week, generated on the phone. */
    private fun enqueueAiMessages(logId: String, name: String) {
        AiMessages.requestBatch(getApplication<Application>(), logId, name.trim())
    }

    /**
     * Time changes apply from [fromDate] (the day being viewed) on; earlier days keep their times
     * (see LogItem.timesLog).
     */
    fun updateLog(log: LogItem, newName: String, newTimes: List<String>, fromDate: String = DateUtils.getCurrentDateString()) {
        FirebaseRepo.updateLog(log, newName, newTimes, fromDate)
        if (newName.trim() != log.name) enqueueAiMessages(log.id, newName.trim())
    }

    fun appendTimeToLog(log: LogItem, newTime: String, fromDate: String = DateUtils.getCurrentDateString()) {
        FirebaseRepo.updateLog(log, log.name, log.times + newTime, fromDate)
    }

    fun toggleLogActive(log: LogItem, isActive: Boolean, fromDate: String = DateUtils.getCurrentDateString()) {
        FirebaseRepo.setActive(log, uid, isActive, fromDate)
    }

    fun updateLogOrder(reordered: List<LogItem>) {
        _logs.value = reordered
        FirebaseRepo.saveOrder(uid, reordered.map { it.id })
    }

    fun toggleCheckbox(log: LogItem, date: String, time: String, isChecked: Boolean) {
        val user = FirebaseRepo.currentUser ?: return
        FirebaseRepo.setChecked(log.id, user, date, time, isChecked)
    }

    fun deleteLog(log: LogItem) {
        val user = FirebaseRepo.currentUser ?: return
        viewModelScope.launch {
            try {
                FirebaseRepo.deleteOrLeave(log, user)
                AiMessages.remove(getApplication<Application>(), log.id)
            } catch (e: Exception) {
                _toast.value = "Couldn't delete: " + (e.localizedMessage ?: "check your connection")
            }
        }
    }

    /** Deletes every log I own and leaves every log that was shared with me. */
    fun deleteAllLogs() {
        val user = FirebaseRepo.currentUser ?: return
        val all = _logs.value
        viewModelScope.launch {
            var failed = 0
            for (log in all) {
                try {
                    FirebaseRepo.deleteOrLeave(log, user)
                    AiMessages.remove(getApplication<Application>(), log.id)
                } catch (e: Exception) {
                    failed++
                }
            }
            if (failed > 0) _toast.value = "$failed log(s) couldn't be deleted. Try again."
        }
    }

    // -------------------------------------------------------------- sharing

    fun shareLog(log: LogItem, friendEmail: String) {
        val clean = friendEmail.trim().lowercase()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(clean).matches()) {
            _toast.value = "Enter a valid email address."
            return
        }
        if (clean in log.memberEmails) {
            _toast.value = "$clean already has this log."
            return
        }
        viewModelScope.launch {
            try {
                FirebaseRepo.addMember(log.id, clean)
                _toast.value = "Shared '${log.name}' with $clean. They'll see it after signing in with that email."
            } catch (e: Exception) {
                _toast.value = "Couldn't share: " + (e.localizedMessage ?: "check your connection")
            }
        }
    }

    fun removeMember(log: LogItem, memberEmail: String) {
        viewModelScope.launch {
            try {
                FirebaseRepo.removeMember(log.id, memberEmail)
            } catch (e: Exception) {
                _toast.value = "Couldn't remove: " + (e.localizedMessage ?: "check your connection")
            }
        }
    }

    // ------------------------------------------------------------ CSV import

    /** Adds the chosen rows from a .csv/.slog file. [includeHistory]: also copy .slog tick history. */
    fun importRows(rows: List<CsvRow>, includeHistory: Boolean) {
        val user = FirebaseRepo.currentUser ?: return
        var order = _logs.value.size
        var days = 0
        val today = DateUtils.getCurrentDateString()
        rows.forEach { row ->
            // with history: the log starts on its first day in the file, so old days count as before
            val start = if (includeHistory) {
                listOfNotNull(
                    row.startDate.takeIf { it.isNotBlank() },
                    row.history.keys.minOrNull()
                ).minOrNull()?.takeIf { it < today } ?: today
            } else today
            val id = FirebaseRepo.addLog(
                user, row.name, row.times, order++,
                startDate = start,
                timesLog = if (includeHistory) row.timesLog else emptyMap()
            )
            if (includeHistory && row.history.isNotEmpty()) {
                FirebaseRepo.importHistory(id, user, row.history)
                days += row.history.size
            }
            enqueueAiMessages(id, row.name)
        }
        _toast.value = "Imported ${rows.size} log(s)" + if (days > 0) " with $days days of history." else "."
    }

    private companion object {
        const val DAY_KEY = "_day"
    }
}
