package com.hesi.slog

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Daily summary: once a day (default 11:59 PM = end of the day, changeable in Settings) a notification lists
 * what's still left today, e.g. "GYM 8:45 PM · READ 9:30 PM". Nothing left -> no notification.
 */
class EndOfDayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                try {
                    val user = FirebaseRepo.currentUser ?: return@launch
                    val today = DateUtils.getCurrentDateString()
                    val activeLogs = FirebaseRepo.fetchLogs(FirebaseRepo.emailOf(user))
                        .filter { it.isActiveFor(user.uid) && it.times.isNotEmpty() }
                        .sortedWith(compareBy<LogItem>({ it.orderFor(user.uid) }, { it.createdAt }))

                    // my ticks for today, per log
                    val records = mutableMapOf<String, List<DayRecord>>()
                    for (log in activeLogs) {
                        val mine = try {
                            FirebaseRepo.fetchMyRecord(log.id, user.uid, today)
                        } catch (e: Exception) {
                            null
                        }
                        records[log.id] = listOfNotNull(mine)
                    }

                    val left = Streaks.leftFor(activeLogs, records, user.uid, today)
                    if (left.isNotEmpty()) showSummaryNotification(context, left)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } finally {
                schedule(context)
                pendingResult.finish()
            }
        }
    }

    private fun showSummaryNotification(context: Context, left: List<Pair<LogItem, String>>) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = SLogNotifications.channelId(context) // S Log tone unless a custom sound is set

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val contentIntent = PendingIntent.getActivity(
            context, 0, openIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val title = "S LOG: ${left.size} left today"
        val lines = left.joinToString("\n") { (log, time) ->
            "• " + log.name.uppercase() + "  " + DateUtils.formatTo12Hour(time)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(Streaks.describe(left))
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(9999, notification)
    }

    companion object {
        private const val PREFS = "slog_prefs"
        private const val KEY_HOUR = "summary_hour"
        private const val KEY_MINUTE = "summary_minute"

        /** (hour, minute) of the daily summary; default 23:59 (end of the day). */
        fun summaryTime(context: Context): Pair<Int, Int> {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return p.getInt(KEY_HOUR, 23) to p.getInt(KEY_MINUTE, 59)
        }

        fun setSummaryTime(context: Context, hour: Int, minute: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_HOUR, hour)
                .putInt(KEY_MINUTE, minute)
                .apply()
            schedule(context)
        }

        /** Schedules the next summary (today if the time is still ahead, otherwise tomorrow). */
        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                9999,
                Intent(context, EndOfDayReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val (hour, minute) = summaryTime(context)
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (calendar.timeInMillis <= System.currentTimeMillis()) {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent
                        )
                    } else {
                        // no exact-alarm permission: still deliver, a few minutes late at most
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent
                        )
                    }
                } else {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent
                    )
                }
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
    }
}
