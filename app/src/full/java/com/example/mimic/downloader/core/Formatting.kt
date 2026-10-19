package com.example.mimic.downloader.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

object Formatting {
    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

    fun bytes(value: Long?): String {
        if (value == null || value <= 0) return "Unknown"
        val digitGroups = (log10(value.toDouble()) / log10(1024.0)).toInt().coerceIn(0, UNITS.size - 1)
        val size = value / 1024.0.pow(digitGroups.toDouble())
        return String.format(Locale.US, "%.1f %s", size, UNITS[digitGroups])
    }

    fun speed(bytesPerSecond: Long?): String {
        if (bytesPerSecond == null || bytesPerSecond <= 0) return "-"
        return "${bytes(bytesPerSecond)}/s"
    }

    fun duration(totalSeconds: Long?): String {
        if (totalSeconds == null || totalSeconds < 0) return "Unknown"
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun eta(seconds: Long?): String {
        if (seconds == null || seconds < 0) return "-"
        if (seconds < 60) return "${seconds}s remaining"
        val minutes = seconds / 60
        if (minutes < 60) return "${minutes}m remaining"
        val hours = minutes / 60
        val remMinutes = minutes % 60
        return "${hours}h ${remMinutes}m remaining"
    }

    fun percent(value: Float): String = "${value.toInt().coerceIn(0, 100)}%"

    fun relativeTime(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val diff = nowMillis - epochMillis
        val seconds = diff / 1000
        return when {
            seconds < 60 -> "Just now"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86400 -> "${seconds / 3600}h ago"
            abs(seconds) < 86400 * 7 -> "${seconds / 86400}d ago"
            else -> java.text.SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(java.util.Date(epochMillis))
        }
    }
}
