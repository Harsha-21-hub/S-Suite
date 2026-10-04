package com.hesi.slog

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri

/**
 * One place for S Log's notification channel and its sound.
 *  - Default sound: res/raw/slog_sound.ogg (the S Log tone).
 *  - Settings -> SELECT CUSTOM NOTIFICATION SOUND picks another one; RESET goes back to the default.
 * Android fixes a channel's sound when the channel is created, so a new sound = a new channel.
 */
object SLogNotifications {
    private const val PREFS = "slog_prefs"
    private const val KEY_CHANNEL = "current_channel_id"
    private const val KEY_CUSTOM = "custom_sound_uri"
    private const val DEFAULT_CHANNEL = "slog_reminders_tone_v1"
    private const val CHANNEL_NAME = "S LOG Reminders"

    /** Channels from older versions (no S Log tone) - removed so they don't linger in Settings. */
    private val OLD_CHANNELS = listOf("slog_exact_reminders")

    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .build()

    fun defaultSound(context: Context): Uri =
        Uri.parse("android.resource://${context.packageName}/${R.raw.slog_sound}")

    fun hasCustomSound(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CUSTOM, null) != null

    /** The channel to post on (created with the right sound if it doesn't exist yet). */
    fun channelId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val custom = prefs.getString(KEY_CUSTOM, null)
        val id = if (custom != null) prefs.getString(KEY_CHANNEL, null) ?: DEFAULT_CHANNEL else DEFAULT_CHANNEL
        if (nm.getNotificationChannel(id) == null) {
            val sound = if (custom != null && id != DEFAULT_CHANNEL) Uri.parse(custom) else defaultSound(context)
            nm.createNotificationChannel(
                NotificationChannel(id, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(sound, audioAttributes)
                    enableVibration(true)
                }
            )
        }
        OLD_CHANNELS.filter { it != id }.forEach { nm.deleteNotificationChannel(it) }
        return id
    }

    /** Use [uri] (picked in Settings) for every S Log notification from now on. */
    fun setCustomSound(context: Context, uri: Uri) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        prefs.getString(KEY_CHANNEL, null)?.takeIf { it != DEFAULT_CHANNEL }?.let { nm.deleteNotificationChannel(it) }
        nm.deleteNotificationChannel(DEFAULT_CHANNEL)
        val id = "slog_channel_" + System.currentTimeMillis()
        prefs.edit().putString(KEY_CUSTOM, uri.toString()).putString(KEY_CHANNEL, id).apply()
        nm.createNotificationChannel(
            NotificationChannel(id, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(uri, audioAttributes)
                enableVibration(true)
            }
        )
    }

    /** Back to the S Log tone. */
    fun resetToDefaultSound(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        prefs.getString(KEY_CHANNEL, null)?.takeIf { it != DEFAULT_CHANNEL }?.let { nm.deleteNotificationChannel(it) }
        prefs.edit().remove(KEY_CUSTOM).remove(KEY_CHANNEL).apply()
        channelId(context)
    }
}
