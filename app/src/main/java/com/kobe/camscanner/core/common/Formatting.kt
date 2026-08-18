package com.kobe.camscanner.core.common

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Formatting {

    private val dayFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val fileStampFormat = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US)
    private val prettyDateFormat = SimpleDateFormat("d MMM yyyy", Locale.US)

    /** "Today 14:02" / "Yesterday 09:31" / "12 Aug 2026" - what a library list actually needs. */
    fun relativeDate(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val then = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val yesterday = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -1)
        }
        return when {
            isSameDay(then, today) -> "Today " + timeFormat.format(Date(timestamp))
            isSameDay(then, yesterday) -> "Yesterday " + timeFormat.format(Date(timestamp))
            else -> dayFormat.format(Date(timestamp))
        }
    }

    fun date(timestamp: Long): String = dayFormat.format(Date(timestamp))

    fun prettyDate(timestamp: Long): String = prettyDateFormat.format(Date(timestamp))

    fun fileStamp(timestamp: Long = System.currentTimeMillis()): String =
        fileStampFormat.format(Date(timestamp))

    fun fileSize(bytes: Long): String = when {
        bytes <= 0L -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    fun pageCount(count: Int): String = if (count == 1) "1 page" else "$count pages"

    private fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}
