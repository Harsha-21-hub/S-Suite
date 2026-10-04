package com.hesi.slog

object TimeUtils {

    private val time24 = Regex("""^(\d{1,2}):(\d{2})$""")
    private val time12 = Regex("""^(\d{1,2})(?::(\d{2}))?\s*([AaPp])\.?[Mm]\.?$""")

    /** Parses "7:00", "07:00", "19:30", "7 PM", "7:30pm" into "HH:mm", or null if invalid. */
    fun parse(raw: String): String? {
        val s = raw.trim()
        time24.matchEntire(s)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            return if (h in 0..23 && min in 0..59) format(h, min) else null
        }
        time12.matchEntire(s)?.let { m ->
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            val pm = m.groupValues[3].equals("p", ignoreCase = true)
            if (h !in 1..12 || min !in 0..59) return null
            if (h == 12) h = 0
            if (pm) h += 12
            return format(h, min)
        }
        return null
    }

    fun format(hour: Int, minute: Int): String =
        hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')

    /** Valid, de-duplicated, sorted earliest-first. */
    fun normalize(times: List<String>): List<String> =
        times.mapNotNull { parse(it) }.distinct().sortedBy { DateUtils.parseTimeToMinutes(it) }

    /** Splits user text like "07:00, 12:30; 7 PM" into normalized times + the bits that were invalid. */
    fun parseList(text: String): Pair<List<String>, List<String>> {
        val parts = text.split(',', ';', '|', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val bad = parts.filter { parse(it) == null }
        return normalize(parts) to bad
    }
}
