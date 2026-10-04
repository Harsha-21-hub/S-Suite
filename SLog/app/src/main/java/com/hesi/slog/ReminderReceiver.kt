package com.hesi.slog

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val logId = intent.getStringExtra("logId") ?: return
        val logName = intent.getStringExtra("logName") ?: "Task"
        val timeLabel = intent.getStringExtra("timeLabel") ?: ""

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val user = FirebaseRepo.currentUser ?: return@launch
                var fetchFailed = false
                val log = try {
                    FirebaseRepo.fetchLog(logId)
                } catch (e: Exception) {
                    // Permission denied = log no longer shared with me. Anything else = offline/not cached,
                    // so still remind with what we have.
                    val denied = (e as? FirebaseFirestoreException)?.code ==
                            FirebaseFirestoreException.Code.PERMISSION_DENIED
                    fetchFailed = !denied
                    null
                }

                // Log deleted, time removed, switched off, or no longer shared with me -> stop
                if (!fetchFailed && log == null) return@launch
                if (log != null && (timeLabel !in log.times || !log.isActiveFor(user.uid))) return@launch

                val record = try {
                    FirebaseRepo.fetchMyRecord(logId, user.uid, DateUtils.getCurrentDateString())
                } catch (e: Exception) {
                    null
                }
                if (record?.isChecked(timeLabel) == true) {
                    // already done before the reminder -> a little celebration instead of a nudge
                    showCongrats(context, log?.name ?: logName, timeLabel)
                } else {
                    showNotification(context, logId, log?.name ?: logName, timeLabel, log?.times ?: listOf(timeLabel))
                }

                // Same reminder again tomorrow
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                AlarmScheduler.scheduleOne(context, alarmManager, logId, log?.name ?: logName, timeLabel)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showNotification(
        context: Context,
        logId: String,
        logName: String,
        timeLabel: String,
        times: List<String>
    ) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channelId = SLogNotifications.channelId(context) // S Log tone unless a custom sound is set
        val contentIntent = openAppIntent(context)

        val formattedTime = DateUtils.formatTo12Hour(timeLabel)

        // Today's AI message for this log (a new week is generated automatically when needed)
        val message = try {
            AiMessages.messageFor(context, logId, logName, timeLabel, times)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } ?: "Time for '$logName' at $formattedTime. Lock in! 🚀"

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("S LOG: " + logName.uppercase(Locale.ROOT))
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(logName.hashCode() + timeLabel.hashCode(), notification)
    }

    /** "GYM ✓  Done before the reminder. Iconic. 🎉" */
    private fun showCongrats(context: Context, logName: String, timeLabel: String) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, SLogNotifications.channelId(context))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("S LOG: " + logName.uppercase(Locale.ROOT) + " ✓")
            .setContentText(CONGRATS.random())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        notificationManager.notify(logName.hashCode() + timeLabel.hashCode(), notification)
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return PendingIntent.getActivity(context, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)
    }

    private companion object {
        val CONGRATS = listOf(
            "Done before the reminder. Iconic. 🎉",
            "Already ticked? Main character energy ✨",
            "You beat the reminder. Slay. 🏆",
            "Early W. No cap. 🔥",
            "Ahead of schedule? Legend behaviour 👑",
            "Done and dusted. Proud of you, bestie 🫶",
            "Not you finishing early. We love that 💯",
            "Reminder? You didn't even need it 😎",
            "Already done. That's the spirit 🙌",
            "Streak fed early. Chef's kiss 🤌"
        )
    }
}
