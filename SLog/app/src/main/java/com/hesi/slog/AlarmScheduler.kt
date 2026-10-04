package com.hesi.slog

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * Schedules one exact alarm per (log, time) for the signed-in user's enabled logs.
 * Remembers what it scheduled so alarms for deleted logs/times get cancelled.
 */
object AlarmScheduler {

    private const val PREFS = "slog_alarms"
    private const val KEY = "scheduled"

    private fun requestCode(logId: String, time: String) = "$logId|$time".hashCode()

    private fun pendingIntent(context: Context, logId: String, logName: String, time: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("logId", logId)
            putExtra("logName", logName)
            putExtra("timeLabel", time)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(logId, time),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun scheduleExactAlarms(context: Context, logs: List<LogItem>, uid: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getStringSet(KEY, emptySet()) ?: emptySet()
        val current = mutableSetOf<String>()

        for (log in logs) {
            if (!log.isActiveFor(uid)) continue
            for (time in log.times) {
                scheduleOne(context, alarmManager, log.id, log.name, time)
                current.add("${log.id}|$time")
            }
        }

        // Cancel alarms that no longer exist (deleted log, removed time, switched off)
        for (old in previous - current) {
            val logId = old.substringBefore("|")
            val time = old.substringAfter("|")
            alarmManager.cancel(pendingIntent(context, logId, "", time))
        }
        prefs.edit().putStringSet(KEY, current).apply()
    }

    fun cancelAll(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(KEY, emptySet())?.forEach { key ->
            alarmManager.cancel(pendingIntent(context, key.substringBefore("|"), "", key.substringAfter("|")))
        }
        prefs.edit().remove(KEY).apply()
    }

    /** Schedules the next occurrence (today if still ahead, otherwise tomorrow). */
    fun scheduleOne(context: Context, alarmManager: AlarmManager, logId: String, logName: String, time: String) {
        val parts = time.split(":")
        if (parts.size != 2) return
        val hour = parts[0].trim().toIntOrNull() ?: return
        val minute = parts[1].trim().toIntOrNull() ?: return

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        val pi = pendingIntent(context, logId, logName, time)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pi)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pi)
            }
        } catch (_: SecurityException) {
        }
    }
}
