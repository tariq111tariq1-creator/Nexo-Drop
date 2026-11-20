package com.example.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

object Formatters {

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.lastIndex)
        val value = bytes / 1024.0.pow(digitGroups.toDouble())
        return String.format(Locale.US, if (digitGroups == 0) "%.0f %s" else "%.1f %s", value, units[digitGroups])
    }

    fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return "0 B/s"
        return "${formatBytes(bytesPerSec)}/s"
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "0s"
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return if (min > 0) "${min}m ${sec}s" else "${sec}s"
    }

    fun formatTimestamp(epochMs: Long): String {
        val sdf = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
        return sdf.format(Date(epochMs))
    }

    fun formatEta(remainingBytes: Long, speedBytesPerSec: Long): String {
        if (remainingBytes <= 0L || speedBytesPerSec <= 0L) return "…"
        val secs = remainingBytes / speedBytesPerSec
        return when {
            secs < 60L -> "${secs}s"
            secs < 3600L -> "${secs / 60}m ${secs % 60}s"
            else -> "${secs / 3600}h ${(secs % 3600) / 60}m"
        }
    }

    fun formatBackupStamp(epochMs: Long = System.currentTimeMillis()): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(epochMs))
    }
}
