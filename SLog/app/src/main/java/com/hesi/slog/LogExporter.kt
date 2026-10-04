package com.hesi.slog

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Shares logs as files through Android's share sheet (WhatsApp, Gmail, Drive...).
 *  - .csv  : name + times, opens in Excel/Sheets, importable by S Log
 *  - .slog : S Log's own file - names, times, on/off and your tick history
 */
object LogExporter {

    /** CSV:  name,times  ->  "Gym","07:00;19:00" */
    fun toCsv(logs: List<LogItem>): String = buildString {
        append("name,times\n")
        logs.forEach { log ->
            append(quote(log.name)).append(',').append(quote(log.times.joinToString(";"))).append('\n')
        }
    }

    /**
     * .slog JSON (format "slog", version 2). History = my ticks that are still loaded (~6 months),
     * plus the log's first day and time changes so the receiver sees the same streaks.
     */
    fun toSlog(
        logs: List<LogItem>,
        records: Map<String, List<DayRecord>>,
        uid: String,
        userName: String
    ): String {
        val root = JSONObject()
        root.put("format", "slog")
        root.put("version", 2)
        root.put("app", "S Log")
        root.put("exportedAt", ZonedDateTime.now().toString())
        root.put("exportedBy", userName)
        val arr = JSONArray()
        logs.forEach { log ->
            val o = JSONObject()
            o.put("name", log.name)
            o.put("times", JSONArray(log.times))
            o.put("active", log.isActiveFor(uid))
            val plan = LogPlan(log, uid, records[log.id].orEmpty())
            o.put("startDate", plan.start.format(DateUtils.dbFormatter))
            val timesLog = JSONObject()
            log.timesLog.toSortedMap().forEach { (date, times) -> timesLog.put(date, JSONArray(times)) }
            o.put("timesLog", timesLog)
            val history = JSONObject()
            records[log.id].orEmpty()
                .filter { it.uid == uid }
                .sortedBy { it.date }
                .forEach { r ->
                    // ticks for the times the log had on that day
                    val day = LogPlan.parse(r.date) ?: return@forEach
                    val ticks = r.checked.filter { it in plan.timesOn(day) }
                    if (ticks.isNotEmpty()) history.put(r.date, JSONArray(ticks))
                }
            o.put("history", history)
            arr.put(o)
        }
        root.put("logs", arr)
        return root.toString(2)
    }

    /** Writes the file(s) and opens the share sheet. */
    fun share(
        context: Context,
        logs: List<LogItem>,
        records: Map<String, List<DayRecord>>,
        uid: String,
        userName: String,
        format: ExportFormat
    ) {
        if (logs.isEmpty()) return
        val dir = File(context.cacheDir, "exports").apply {
            deleteRecursively()
            mkdirs()
        }
        val base = if (logs.size == 1) safeName(logs[0].name) else "slog_logs_${LocalDate.now()}"

        val files = mutableListOf<File>()
        if (format == ExportFormat.CSV || format == ExportFormat.BOTH) {
            files += File(dir, "$base.csv").apply { writeText(toCsv(logs)) }
        }
        if (format == ExportFormat.SLOG || format == ExportFormat.BOTH) {
            files += File(dir, "$base.slog").apply { writeText(toSlog(logs, records, uid, userName)) }
        }

        val authority = context.packageName + ".fileprovider"
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(context, authority, it) })

        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = if (files[0].name.endsWith(".csv")) "text/csv" else "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        send.putExtra(Intent.EXTRA_SUBJECT, "S Log: " + if (logs.size == 1) logs[0].name else "${logs.size} logs")
        send.putExtra(Intent.EXTRA_TEXT, "Import this in S Log (IMPORT button).")
        // let the receiving app read the files
        val clip = ClipData.newRawUri(files[0].name, uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        send.clipData = clip
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        context.startActivity(Intent.createChooser(send, "Share logs"))
    }

    private fun quote(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(' ', '_').ifBlank { "log" }.take(40)
}
