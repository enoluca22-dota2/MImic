package com.example.mimic.downloader.download

/**
 * Extracts total size and transfer speed from yt-dlp's raw progress line, e.g.:
 * "[download]  42.0% of   10.00MiB at    1.20MiB/s ETA 00:07"
 * These fields aren't exposed structurally by youtubedl-android's callback, only [line] is.
 */
object ProgressParser {

    private val SIZE_SPEED_REGEX = Regex(
        """of\s+~?\s*([\d.]+)\s*(\w+)\s+at\s+([\d.]+)\s*(\w+)/s""",
        RegexOption.IGNORE_CASE
    )
    private val SIZE_ONLY_REGEX = Regex("""of\s+~?\s*([\d.]+)\s*(\w+)""", RegexOption.IGNORE_CASE)

    data class ParsedProgress(val totalBytes: Long?, val speedBytesPerSecond: Long?)

    fun parse(line: String): ParsedProgress {
        val full = SIZE_SPEED_REGEX.find(line)
        if (full != null) {
            val (sizeVal, sizeUnit, speedVal, speedUnit) = full.destructured
            return ParsedProgress(
                totalBytes = toBytes(sizeVal, sizeUnit),
                speedBytesPerSecond = toBytes(speedVal, speedUnit),
            )
        }
        val sizeOnly = SIZE_ONLY_REGEX.find(line)
        if (sizeOnly != null) {
            val (sizeVal, sizeUnit) = sizeOnly.destructured
            return ParsedProgress(totalBytes = toBytes(sizeVal, sizeUnit), speedBytesPerSecond = null)
        }
        return ParsedProgress(null, null)
    }

    private fun toBytes(value: String, unit: String): Long? {
        val number = value.toDoubleOrNull() ?: return null
        val u = unit.uppercase()
        val multiplier = when {
            u.startsWith("K") -> 1024.0
            u.startsWith("M") -> 1024.0 * 1024
            u.startsWith("G") -> 1024.0 * 1024 * 1024
            u.startsWith("T") -> 1024.0 * 1024 * 1024 * 1024
            else -> 1.0
        }
        return (number * multiplier).toLong()
    }
}
