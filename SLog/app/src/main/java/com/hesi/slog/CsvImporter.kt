package com.hesi.slog

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads logs from shared files. Two formats:
 *
 * .csv   name,times
 *        Gym,"07:00;19:00"
 *        Read,21:30
 *        Water,08:00,12:00,16:00      (extra columns are treated as more times)
 *        Header row optional. Times may also be 12-hour ("7 PM").
 *
 * .slog  S Log's own JSON file (see LogExporter): names, times and tick history.
 */
object CsvImporter {

    /** Picks the right reader from the file content. */
    fun parseAny(content: String, existingNames: Set<String>): List<CsvRow> {
        val text = content.removePrefix("﻿")
        return if (text.trimStart().startsWith("{")) parseSlog(text, existingNames)
        else parse(text, existingNames)
    }

    fun parse(content: String, existingNames: Set<String>): List<CsvRow> {
        val rows = mutableListOf<CsvRow>()
        val seen = existingNames.map { it.trim().lowercase() }.toMutableSet()

        content.removePrefix("﻿").lines().forEachIndexed { i, line ->
            if (line.isBlank()) return@forEachIndexed
            val cells = splitCsvLine(line)
            val name = cells.getOrNull(0)?.trim().orEmpty()

            if (i == 0 && name.equals("name", ignoreCase = true)) return@forEachIndexed // header

            val rawTimes = cells.drop(1)
                .flatMap { it.split(';', '|') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            rows.add(buildRow(i + 1, name, rawTimes, emptyMap(), seen))
        }
        return rows
    }

    /** Reads a .slog file. Throws IllegalArgumentException if it isn't one. */
    fun parseSlog(content: String, existingNames: Set<String>): List<CsvRow> {
        val root = JSONObject(content)
        require(root.optString("format") == "slog") { "This isn't an S Log (.slog) file." }
        val seen = existingNames.map { it.trim().lowercase() }.toMutableSet()
        val logs = root.optJSONArray("logs") ?: JSONArray()
        val rows = mutableListOf<CsvRow>()

        for (i in 0 until logs.length()) {
            val o = logs.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            val rawTimes = stringList(o.optJSONArray("times"))

            val history = dateMap(o.optJSONObject("history")) { it.distinct() }
            // v2 files: first day + earlier times, so imported streaks match the sender's
            val timesLog = dateMap(o.optJSONObject("timesLog")) { TimeUtils.normalize(it) }
            val startDate = o.optString("startDate").trim().takeIf { DATE.matches(it) } ?: ""
            rows.add(buildRow(i + 1, name, rawTimes, history, seen, startDate, timesLog))
        }
        return rows
    }

    private val DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")

    /** {"2026-10-01": ["07:00", ...]} -> date -> valid times (dates and times checked). */
    private fun dateMap(obj: JSONObject?, clean: (List<String>) -> List<String>): Map<String, List<String>> {
        if (obj == null) return emptyMap()
        val out = sortedMapOf<String, List<String>>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val date = keys.next()
            if (!DATE.matches(date)) continue
            val times = clean(stringList(obj.optJSONArray(date)).mapNotNull { TimeUtils.parse(it) })
            if (times.isNotEmpty()) out[date] = times
        }
        return out
    }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
    }

    private fun buildRow(
        number: Int,
        name: String,
        rawTimes: List<String>,
        history: Map<String, List<String>>,
        seen: MutableSet<String>,
        startDate: String = "",
        timesLog: Map<String, List<String>> = emptyMap()
    ): CsvRow {
        val times = TimeUtils.normalize(rawTimes)
        val bad = rawTimes.filter { TimeUtils.parse(it) == null }

        val problems = mutableListOf<String>()
        if (name.isBlank()) problems.add("Missing name")
        if (bad.isNotEmpty()) problems.add("Skipped invalid time: " + bad.joinToString(", "))
        if (name.isNotBlank() && times.isEmpty()) problems.add("No times")

        val key = name.lowercase()
        val duplicate = name.isNotBlank() && key in seen
        if (duplicate) problems.add("A log with this name already exists")
        if (name.isNotBlank()) seen.add(key)

        // keep only ticks for times this log has (or had, for v2 files with time changes)
        val known = (times + timesLog.values.flatten()).toSet()
        val cleanHistory = history
            .mapValues { (_, ticks) -> ticks.filter { it in known } }
            .filterValues { it.isNotEmpty() }

        return CsvRow(number, name, times, problems, duplicate, cleanHistory, startDate, timesLog)
    }

    /** Splits one CSV line, honouring "quoted, values" and "" escapes. */
    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out.add(cur.toString()); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }
}
