package com.hesi.slog

import com.google.firebase.firestore.DocumentSnapshot

/**
 * A log (habit) stored in Firestore at `logs/{id}`.
 * A log can be shared: everyone whose e-mail is in [memberEmails] can see and tick it.
 * Per-person settings (enabled switch + list position) live in [userState], keyed by uid.
 *
 * History (so changes never rewrite the past):
 *  - [startDate]  first day the log counts (yyyy-MM-dd). Days before it ignore this log.
 *  - [timesLog]   date -> times in effect from that date. Adding/removing a time today only
 *                 changes today and later; earlier days keep the times they had.
 *  - userState[uid].activeLog  date -> on/off from that date (same idea for the switch).
 */
data class LogItem(
    val id: String,
    val name: String,
    val times: List<String>,          // always sorted "HH:mm"
    val ownerUid: String,
    val ownerEmail: String,
    val ownerName: String,
    val memberEmails: List<String>,
    val userState: Map<String, Map<String, Any?>>,
    val createdAt: Long,
    val startDate: String = "",
    val timesLog: Map<String, List<String>> = emptyMap()
) {
    fun isActiveFor(uid: String): Boolean = userState[uid]?.get("active") as? Boolean ?: true
    fun orderFor(uid: String): Long = (userState[uid]?.get("order") as? Number)?.toLong() ?: Long.MAX_VALUE
    /** Best streak ever for this person on this log, as saved in the database. */
    fun bestFor(uid: String): Int = (userState[uid]?.get("best") as? Number)?.toInt() ?: 0
    /** Highest streak milestone already celebrated (null = never saved). */
    fun celebratedFor(uid: String): Int? = (userState[uid]?.get("celebrated") as? Number)?.toInt()
    fun isOwner(uid: String): Boolean = ownerUid == uid
    fun isShared(): Boolean = memberEmails.size > 1

    /** date -> on/off for this person (empty for logs that were never switched since this update). */
    fun activeLogFor(uid: String): Map<String, Boolean> =
        (userState[uid]?.get("activeLog") as? Map<*, *>)
            ?.mapNotNull { (k, v) -> if (k is String && v is Boolean) k to v else null }
            ?.toMap() ?: emptyMap()

    /** First day of the log: saved start date, else the day it was created, else today. */
    fun startKey(): String = startDate.takeIf { it.isNotBlank() }
        ?: createdAt.takeIf { it > 0 }?.let {
            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                .format(DateUtils.dbFormatter)
        }
        ?: DateUtils.getCurrentDateString()

    /**
     * [timesLog] after changing the times to [newTimes] from day [from] on (the day you're looking
     * at, usually today). Days before [from] keep their times; any later change is replaced.
     */
    fun timesLogAfterChange(newTimes: List<String>, from: String = DateUtils.getCurrentDateString()): Map<String, List<String>> {
        val start = startKey()
        val day = if (from < start) start else from
        val map = timesLog.filterKeys { it < day }.toMutableMap()
        if (timesLog.isEmpty() && start < day) map[start] = times
        map[day] = TimeUtils.normalize(newTimes)
        return map
    }

    /** activeLog after switching this log on/off from day [from] on (same idea). */
    fun activeLogAfterToggle(uid: String, active: Boolean, from: String = DateUtils.getCurrentDateString()): Map<String, Boolean> {
        val start = startKey()
        val day = if (from < start) start else from
        val old = activeLogFor(uid)
        val map = old.filterKeys { it < day }.toMutableMap()
        if (old.isEmpty() && start < day) map[start] = isActiveFor(uid)
        map[day] = active
        return map
    }

    companion object {
        val DATE_RE = Regex("""^\d{4}-\d{2}-\d{2}$""")

        fun from(doc: DocumentSnapshot): LogItem? {
            val name = doc.getString("name") ?: return null
            @Suppress("UNCHECKED_CAST")
            val state = (doc.get("userState") as? Map<String, Map<String, Any?>>) ?: emptyMap()
            return LogItem(
                id = doc.id,
                name = name,
                times = TimeUtils.normalize((doc.get("times") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()),
                ownerUid = doc.getString("ownerUid") ?: "",
                ownerEmail = doc.getString("ownerEmail") ?: "",
                ownerName = doc.getString("ownerName") ?: "",
                memberEmails = (doc.get("memberEmails") as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                userState = state,
                // estimate = the device time while the new log is still waiting for the server
                createdAt = doc.getTimestamp(
                    "createdAt",
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )?.toDate()?.time ?: 0L,
                startDate = doc.getString("startDate")?.takeIf { DATE_RE.matches(it) } ?: "",
                timesLog = (doc.get("timesLog") as? Map<*, *>)
                    ?.mapNotNull { (k, v) ->
                        if (k is String && DATE_RE.matches(k) && v is List<*>)
                            k to TimeUtils.normalize(v.mapNotNull { it as? String })
                        else null
                    }
                    ?.toMap() ?: emptyMap()
            )
        }
    }
}

/**
 * One person's ticks for one log on one day: `logs/{logId}/records/{date}_{uid}`.
 * Ticks are stored as the time strings ("07:00"), so editing or re-ordering times never breaks them.
 */
data class DayRecord(
    val id: String,
    val logId: String,
    val uid: String,
    val userName: String,
    val date: String,                 // yyyy-MM-dd
    val checked: List<String>
) {
    fun isChecked(time: String): Boolean = checked.contains(time)
    fun completedFor(log: LogItem): Int = checked.count { it in log.times }

    companion object {
        fun docId(date: String, uid: String) = "${date}_$uid"

        fun from(logId: String, doc: DocumentSnapshot): DayRecord? {
            val uid = doc.getString("uid") ?: return null
            val date = doc.getString("date") ?: return null
            return DayRecord(
                id = doc.id,
                logId = logId,
                uid = uid,
                userName = doc.getString("userName") ?: "",
                date = date,
                checked = (doc.get("checked") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            )
        }
    }
}

/** A log read from an imported .csv or .slog file, shown in the preview before anything is saved. */
data class CsvRow(
    val lineNumber: Int,
    val name: String,
    val times: List<String>,
    val problems: List<String>,
    val isDuplicate: Boolean,
    /** Only in .slog files: date (yyyy-MM-dd) -> ticked times. */
    val history: Map<String, List<String>> = emptyMap(),
    /** Only in .slog files (v2): first day + how the times changed over time. */
    val startDate: String = "",
    val timesLog: Map<String, List<String>> = emptyMap()
) {
    val isValid: Boolean get() = name.isNotBlank()
}

/** How logs are shared: a .csv file, a .slog file, or both. */
enum class ExportFormat { CSV, SLOG, BOTH }
