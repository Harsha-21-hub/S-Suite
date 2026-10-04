package com.hesi.slog

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.max

/**
 * Streak numbers shown in the app.
 *  month   = longest run of complete days inside the month on screen
 *  best    = longest run ever (also remembered in the database, so it survives old data)
 *  current = run of complete days up to today (today counts once it's complete)
 *
 * A day counts for a log when at least ONE of its times that day was ticked (partly done counts).
 * The whole day counts when at least one tick was made that day (partly done days count too).
 * Every day is judged with the logs and times that existed ON THAT DAY (see [LogPlan]), so adding
 * a log or a time today never changes the ticks, marks or streaks of earlier days.
 */
data class StreakStats(val month: Int = 0, val best: Int = 0, val current: Int = 0)

/**
 * One calendar day: ticks done / due, and logs started (>= 1 tick) / due.
 * total 0 = nothing was due that day (no mark).
 */
data class DayProgress(val done: Int, val total: Int, val logsStarted: Int = 0, val logsDue: Int = 0) {
    /** every time ticked (green ✓) */
    val isFull get() = total > 0 && done >= total
    /** counts for the streak (amber ✓): at least one tick that day, on any log that was due */
    val counts get() = total > 0 && done > 0
    val isPartial get() = done > 0 && !isFull
}

/**
 * What a log asked for on any given day.
 *  - before its first day: nothing (the log didn't exist yet)
 *  - times: the [LogItem.timesLog] entry in effect that day (else the current times)
 *  - on/off: the person's activeLog entry in effect that day (else the current switch)
 */
class LogPlan(val log: LogItem, uid: String, records: List<DayRecord> = emptyList()) {

    /** First day. For logs from before this update, also any earlier day that has ticks (imports). */
    val start: LocalDate = run {
        val saved = parse(log.startKey()) ?: LocalDate.now()
        val earliestTick = if (log.startDate.isBlank()) {
            records.filter { it.checked.isNotEmpty() }.mapNotNull { parse(it.date) }.minOrNull()
        } else null
        if (earliestTick != null && earliestTick.isBefore(saved)) earliestTick else saved
    }

    private val timesSteps: List<Pair<LocalDate, List<String>>> =
        log.timesLog.mapNotNull { (k, v) -> parse(k)?.let { it to v } }.sortedBy { it.first }

    private val activeSteps: List<Pair<LocalDate, Boolean>> =
        log.activeLogFor(uid).mapNotNull { (k, v) -> parse(k)?.let { it to v } }.sortedBy { it.first }

    private val currentActive = log.isActiveFor(uid)

    fun exists(date: LocalDate): Boolean = !date.isBefore(start)

    /** Times of the log on [date] (empty before the log started). */
    fun timesOn(date: LocalDate): List<String> {
        if (!exists(date)) return emptyList()
        if (timesSteps.isEmpty()) return log.times
        return timesSteps.lastOrNull { !it.first.isAfter(date) }?.second ?: timesSteps.first().second
    }

    fun activeOn(date: LocalDate): Boolean {
        if (activeSteps.isEmpty()) return currentActive
        return activeSteps.lastOrNull { !it.first.isAfter(date) }?.second ?: activeSteps.first().second
    }

    /** Times that had to be ticked on [date] for the whole-day streak. */
    fun requiredOn(date: LocalDate): List<String> = if (activeOn(date)) timesOn(date) else emptyList()

    companion object {
        fun parse(s: String): LocalDate? = runCatching { LocalDate.parse(s, DateUtils.dbFormatter) }.getOrNull()
    }
}

object Streaks {

    /** Max-streak milestones that get a celebration: 3, 10, 50, 100, 150 ... every 50, plus every 365. */
    fun isMilestone(n: Int): Boolean = n == 3 || n == 10 || (n >= 50 && n % 50 == 0) || (n > 0 && n % 365 == 0)

    /** Biggest milestone <= n (0 if none). */
    fun milestoneFloor(n: Int): Int {
        var m = n
        while (m > 0 && !isMilestone(m)) m--
        return m
    }

    fun longestRun(days: Collection<LocalDate>): Int {
        var best = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in days.toSortedSet()) {
            run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
            best = max(best, run)
            prev = d
        }
        return best
    }

    fun currentRun(days: Set<LocalDate>, today: LocalDate = LocalDate.now()): Int {
        var d = if (days.contains(today)) today else today.minusDays(1)
        var run = 0
        while (days.contains(d)) {
            run++
            d = d.minusDays(1)
        }
        return run
    }

    fun stats(days: Set<LocalDate>, month: YearMonth, storedBest: Int): StreakStats = StreakStats(
        month = longestRun(days.filter { YearMonth.from(it) == month }),
        best = max(storedBest, longestRun(days)),
        current = currentRun(days)
    )

    /** My records of one log, by date. */
    fun myRecords(records: List<DayRecord>, uid: String): Map<String, DayRecord> =
        records.filter { it.uid == uid }.associateBy { it.date }

    /** Dates on which [uid] ticked at least one of the times the log had THAT day (partly done counts). */
    fun completeDays(plan: LogPlan, records: List<DayRecord>, uid: String): Set<LocalDate> =
        records.asSequence()
            .filter { it.uid == uid }
            .mapNotNull { r ->
                val d = LogPlan.parse(r.date) ?: return@mapNotNull null
                val need = plan.timesOn(d)
                if (need.any { r.isChecked(it) }) d else null
            }
            .toSet()

    /** Ticks done / due on [date] over every log that was on and existed that day. */
    fun dayProgress(plans: List<LogPlan>, mine: Map<String, Map<String, DayRecord>>, date: LocalDate): DayProgress {
        val key = date.format(DateUtils.dbFormatter)
        var done = 0
        var total = 0
        var started = 0
        var due = 0
        for (p in plans) {
            val need = p.requiredOn(date)
            if (need.isEmpty()) continue
            total += need.size
            due++
            val r = mine[p.log.id]?.get(key)
            val n = if (r != null) need.count { r.isChecked(it) } else 0
            done += n
            if (n > 0) started++
        }
        return DayProgress(done, total, started, due)
    }

    /** Days (from [from] to [to]) on which every due log was at least partly done. */
    fun completeWholeDays(
        plans: List<LogPlan>,
        records: Map<String, List<DayRecord>>,
        uid: String,
        from: LocalDate,
        to: LocalDate = LocalDate.now()
    ): Set<LocalDate> {
        if (plans.isEmpty()) return emptySet()
        val mine = plans.associate { it.log.id to myRecords(records[it.log.id].orEmpty(), uid) }
        val first = plans.minOf { it.start }.let { if (it.isAfter(from)) it else from }
        val out = HashSet<LocalDate>()
        var d = first
        while (!d.isAfter(to)) {
            if (dayProgress(plans, mine, d).counts) out.add(d)
            d = d.plusDays(1)
        }
        return out
    }

    /** What's still left for [uid] on [date]: (log, time) pairs, in list order and time order. */
    fun leftFor(
        activeLogs: List<LogItem>,
        records: Map<String, List<DayRecord>>,
        uid: String,
        date: String
    ): List<Pair<LogItem, String>> = activeLogs.flatMap { log ->
        val mine = records[log.id].orEmpty().firstOrNull { it.uid == uid && it.date == date }
        log.times.filter { mine?.isChecked(it) != true }.map { log to it }
    }

    /** "GYM 7:00 PM · READ 9:30 PM" */
    fun describe(left: List<Pair<LogItem, String>>): String =
        left.joinToString(" · ") { (log, time) -> log.name.uppercase() + " " + DateUtils.formatTo12Hour(time) }
}
