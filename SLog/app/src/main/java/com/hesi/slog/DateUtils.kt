package com.hesi.slog

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

object DateUtils {
    val dbFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val displayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/yyyy")

    fun getCurrentDateString(): String = LocalDate.now().format(dbFormatter)

    fun getDaysInMonth(yearMonth: YearMonth): List<LocalDate> =
        (1..yearMonth.lengthOfMonth()).map { yearMonth.atDay(it) }

    fun parseTimeToMinutes(timeStr: String): Int {
        val parts = timeStr.trim().split(":")
        if (parts.size == 2) {
            val h = parts[0].trim().toIntOrNull()
            val m = parts[1].trim().toIntOrNull()
            if (h != null && m != null) return h * 60 + m
        }
        return 0
    }

    fun formatTo12Hour(timeStr: String): String {
        val parts = timeStr.trim().split(":")
        if (parts.size == 2) {
            var h = parts[0].trim().toIntOrNull()
            val m = parts[1].trim().toIntOrNull()
            if (h != null && m != null) {
                val minutes = m.toString().padStart(2, '0')
                val amPm = if (h >= 12) "PM" else "AM"
                if (h == 0) h = 12
                if (h > 12) h -= 12
                return "$h:$minutes $amPm"
            }
        }
        return timeStr
    }
}
