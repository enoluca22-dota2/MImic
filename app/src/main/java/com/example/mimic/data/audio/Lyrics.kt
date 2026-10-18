package com.example.mimic.data.audio

/** One lyrics line; [timeMs] is null for plain (unsynced) lyrics. */
data class LyricLine(val timeMs: Long?, val text: String)

data class Lyrics(val synced: Boolean, val lines: List<LyricLine>, val source: String) {

    /** Index of the line being sung at [positionMs], or -1 before the first (always -1 if unsynced). */
    fun activeIndex(positionMs: Long): Int {
        if (!synced) return -1
        // Binary search for the last line whose time <= position.
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if ((lines[mid].timeMs ?: 0L) <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    companion object {
        private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        private val META = Regex("""^\[(ar|ti|al|au|by|re|ve|length|offset|#)\s*:.*]$""", RegexOption.IGNORE_CASE)
        private val OFFSET = Regex("""^\[offset\s*:\s*([+-]?\d+)]$""", RegexOption.IGNORE_CASE)

        /**
         * Parses LRC (`[mm:ss.xx] line`, several stamps per line allowed, `[offset:±ms]`
         * honored, other ID tags skipped) or plain text. Returns null for blank input. An
         * original implementation, informed by the MIT-licensed Resonate player's approach.
         */
        fun parse(text: String, source: String): Lyrics? {
            if (text.isBlank()) return null
            var offset = 0L
            val synced = ArrayList<LyricLine>()
            val plain = ArrayList<LyricLine>()
            for (raw in text.lineSequence()) {
                val line = raw.trim().trimStart('﻿')
                if (line.isEmpty()) continue
                OFFSET.matchEntire(line)?.let { offset = it.groupValues[1].toLong(); return@let }
                if (META.matches(line)) continue
                val stamps = STAMP.findAll(line).toList()
                if (stamps.isEmpty()) { plain += LyricLine(null, line); continue }
                // Only leading stamps count; text is whatever follows the last one.
                val words = line.substring(stamps.last().range.last + 1).trim()
                for (s in stamps) {
                    val (m, sec, frac) = s.destructured
                    val fracMs = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                    // LRC offset: positive = lyrics appear sooner.
                    val t = (m.toLong() * 60_000 + sec.toLong() * 1_000 + fracMs - offset).coerceAtLeast(0)
                    synced += LyricLine(t, words)
                }
            }
            return when {
                synced.isNotEmpty() -> Lyrics(true, synced.sortedBy { it.timeMs }, source)
                plain.isNotEmpty() -> Lyrics(false, plain, source)
                else -> null
            }
        }
    }
}
