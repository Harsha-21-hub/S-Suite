package com.hesi.slog

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * AI notification messages, generated on the phone by the model in assets/.
 *
 * - When a log is created (or renamed / imported) the model writes 7 messages for it: one per day
 *   of the coming week. They're stored in SharedPreferences "slog_ai_queue" -> "queue_<logId>" as
 *   {"generatedAt": <epochDay>, "name": "<log name>", "category": "...", "messages": [...]}.
 * - Each reminder uses the message of the day (several times in a day -> the next ones in turn).
 * - Once a week is over the batch is "stale" and a new week is generated in the background
 *   (daily check worker; it only runs the model for stale or missing logs).
 */
object AiMessages {
    private const val PREFS = "slog_ai_queue"
    const val DAYS = 7

    data class Batch(val generatedAt: Long, val name: String, val category: String, val messages: List<String>) {
        fun isStale(today: Long = LocalDate.now().toEpochDay()) = today - generatedAt >= DAYS || today < generatedAt
    }

    // ---------------------------------------------------------------- storage
    fun save(context: Context, logId: String, name: String, category: String, messages: List<String>) {
        val json = JSONObject()
            .put("generatedAt", LocalDate.now().toEpochDay())
            .put("name", name)
            .put("category", category)
            .put("messages", JSONArray(messages))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("queue_$logId", json.toString())
            .apply()
    }

    fun load(context: Context, logId: String): Batch? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("queue_$logId", null)
            ?: return null
        return try {
            if (raw.trimStart().startsWith("[")) {
                // old format: a plain list -> treat as stale so a fresh week gets generated
                val arr = JSONArray(raw)
                Batch(0L, "", "", List(arr.length()) { arr.getString(it) })
            } else {
                val o = JSONObject(raw)
                val arr = o.optJSONArray("messages") ?: JSONArray()
                Batch(
                    o.optLong("generatedAt", 0L),
                    o.optString("name", ""),
                    o.optString("category", ""),
                    List(arr.length()) { arr.getString(it) }
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    fun remove(context: Context, logId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("queue_$logId").apply()
    }

    /** True if this log needs a new week of messages (none yet, a week old, or renamed). */
    fun needsBatch(context: Context, logId: String, logName: String): Boolean {
        val b = load(context, logId) ?: return true
        return b.messages.isEmpty() || b.isStale() || !b.name.equals(logName.trim(), ignoreCase = true)
    }

    /**
     * Message for one reminder: day N of the batch -> message N; the 2nd/3rd... reminder of the day
     * moves along by one so two reminders on the same day don't repeat. Null if nothing stored.
     */
    fun messageFor(context: Context, logId: String, logName: String, timeLabel: String, times: List<String>): String? {
        val b = load(context, logId) ?: run { requestBatch(context, logId, logName); return null }
        if (b.messages.isEmpty()) return null
        val today = LocalDate.now().toEpochDay()
        if (b.isStale(today) || !b.name.equals(logName.trim(), ignoreCase = true)) {
            requestBatch(context, logId, logName) // new week in the background; reuse the old one now
        }
        val day = Math.floorMod(today - b.generatedAt, DAYS.toLong()).toInt()
        val slot = times.sorted().indexOf(timeLabel).coerceAtLeast(0)
        return b.messages[(day + slot) % b.messages.size]
    }

    // ---------------------------------------------------------------- work scheduling
    /** Generate this week's messages for one log now (after create / rename / import). */
    fun requestBatch(context: Context, logId: String, logName: String) {
        val request = OneTimeWorkRequestBuilder<AIBatchWorker>()
            .setInputData(workDataOf("SPECIFIC_LOG_ID" to logId, "SPECIFIC_LOG_NAME" to logName))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("AI_BATCH_$logId", ExistingWorkPolicy.REPLACE, request)
    }

    /** Once a day: renew any log whose week is over (does nothing on most days). Call at app start. */
    fun scheduleWeeklyRefresh(context: Context) {
        val wm = WorkManager.getInstance(context)
        val daily = PeriodicWorkRequestBuilder<AIBatchWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork("AI_WEEKLY", ExistingPeriodicWorkPolicy.KEEP, daily)
        // and one check right now, so logs added on another device / imported get messages soon
        wm.enqueueUniqueWork("AI_CHECK_NOW", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<AIBatchWorker>().build())
    }

    // ---------------------------------------------------------------- category from the log name
    @Volatile
    private var keywordCache: List<Pair<String, String>>? = null // (keyword, category), longest first
    @Volatile
    private var intents: Set<String> = emptySet()
    @Volatile
    private var fallback: String = "other"

    private fun loadVocabMeta(context: Context) {
        if (keywordCache != null) return
        synchronized(this) {
            if (keywordCache != null) return
            val pairs = ArrayList<Pair<String, String>>()
            try {
                val text = context.assets.open("vocab.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(text)
                val list = json.optJSONArray("intents")
                intents = if (list != null) List(list.length()) { list.getString(it) }.toSet() else emptySet()
                fallback = json.optString("fallback_intent", if ("other" in intents) "other" else "planning")
                val kw = json.optJSONObject("category_keywords")
                if (kw != null) {
                    val cats = kw.keys()
                    var order = 0
                    val ordered = ArrayList<Triple<String, String, Int>>()
                    while (cats.hasNext()) {
                        val cat = cats.next()
                        val arr = kw.getJSONArray(cat)
                        for (i in 0 until arr.length()) {
                            ordered.add(Triple(arr.getString(i).lowercase(Locale.ROOT), cat, order++))
                        }
                    }
                    // longest keyword first ("walk the dog" beats "walk"); ties keep the file order
                    ordered.sortedWith(compareByDescending<Triple<String, String, Int>> { it.first.length }.thenBy { it.third })
                        .forEach { pairs.add(it.first to it.second) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            keywordCache = pairs
        }
    }

    /** Category the model understands for a log name, e.g. "Drink 3L water" -> "hydration". */
    fun categoryFor(context: Context, logName: String): String {
        loadVocabMeta(context)
        val pairs = keywordCache.orEmpty()
        if (pairs.isEmpty()) return legacyCategory(logName) // older model without keywords
        val n = " " + logName.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim() + " "
        for ((kw, cat) in pairs) {
            val k = kw.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            if (k.isEmpty()) continue
            // keyword must start a word: "eat" matches "eat breakfast", not "great"
            if (n.contains(" $k")) return if (intents.isEmpty() || cat in intents) cat else fallback
        }
        return fallback
    }

    /** Mapping used by the first model (vocab without category_keywords). */
    private fun legacyCategory(name: String): String {
        val n = name.lowercase(Locale.ROOT)
        fun has(vararg keys: String) = keys.any { n.contains(it) }
        return when {
            has("n2", "no nut", "nonut", " nn", "nofap", "streak", "discipline") -> "n2"
            has("traya", "serum", "scalp", "hair") -> "traya"
            has("osmania", " ou ", "ou_", "ou ", "lab record", "record book", "internal", "semester", " sem") -> "ou_sem"
            has("gate", "cat ", "cat-", "mock", "iitm", "placement", "percentile", "aptitude") -> "prep"
            has("code", "coding", "dev", "leetcode", "dsa", "algo", "commit", "debug", "compose", "pytorch", "flask", "unity", "github", "program") -> "coding"
            has("study", "studying", "revision", "revise", "notes", "flashcard") -> "studying"
            has("read", "book", "chapter", "novel") -> "reading"
            has("email", "inbox", "mail") -> "email"
            has("detox", "screen time", "offline", "unplug", "phone break", "social media") -> "digital_detox"
            has("lift", "iron", "strength", "barbell", "dumbbell", "push day", "pull day", "leg day", " pr") -> "weight_lifting"
            has("gym", "workout", "train", "sweat", "exercise") -> "workout"
            has("run", "jog", "mile", "sprint", "pace") -> "running"
            has("walk", "steps", "stroll") -> "walking"
            has("cycle", "cycling", "bike", "ride", "spin") -> "cycling"
            has("yoga", "asana", "flow") -> "yoga"
            has("stretch", "mobility", "limber") -> "stretching"
            has("water", "drink", "hydrat", "bottle", "aqua", "fluid") -> "hydration"
            has("eat", "meal", "food", "nutrition", "protein", "diet") -> "eating"
            has("cook", "kitchen", "recipe", "meal prep", "mealprep") -> "cooking"
            has("sleep", "rest", "bed", "slumber", "nap") -> "sleeping"
            has("meditat", "zen", "calm", "mindful") -> "meditation"
            has("breath", "pranayama", "lungs") -> "breathing"
            has("vitamin", "supplement", "pill", "multivitamin") -> "vitamins"
            has("skin", "skincare", "glow", "cream", "sunscreen", "moisturize") -> "skincare"
            has("journal", "diary", "reflect", "gratitude") -> "journaling"
            has("plan", "schedule", "agenda", "task", "to-do", "todo") -> "planning"
            has("budget", "money", "saving", "expense", "finance", "spend") -> "finance"
            has("clean", "tidy", "declutter", "laundry", "chore") -> "cleaning"
            has("social", "friend", "connect", "call", "family", "text") -> "socializing"
            has("music", "instrument", "guitar", "piano", "scales") -> "music"
            has("podcast", "pod ", "episode", "listen") -> "podcast"
            has("language", "vocab", "lingo", "duolingo", "lesson", "spanish", "french", "german", "japanese") -> "language"
            else -> "planning"
        }
    }
}
