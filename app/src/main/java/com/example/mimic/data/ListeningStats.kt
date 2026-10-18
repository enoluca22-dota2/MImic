package com.example.mimic.data

import com.example.mimic.data.db.PlayHistoryEntity

/**
 * Listening statistics (MImic 3.2), computed on the phone from the play history. A "play" is a
 * song that started playing (see PlaybackService); listening time is estimated from each played
 * song's length, so it is an upper bound when songs were skipped early — the screen says so.
 */
data class ListeningStats(
    val plays: Int,
    val distinctSongs: Int,
    val estimatedMinutes: Long,
    val topSongs: List<Pair<Song, Int>>,
    val topArtists: List<Pair<String, Int>>,
    /** Plays per day, oldest first, one entry per day of the window (zeros included). */
    val playsPerDay: List<Int>,
    /** Consecutive days up to today with at least one play. */
    val streakDays: Int,
) {
    companion object {
        const val DAY_MS = 86_400_000L

        fun compute(history: List<PlayHistoryEntity>, songs: List<Song>, now: Long, days: Int, zoneOffsetMs: Long = 0): ListeningStats {
            val byId = songs.associateBy { it.id }
            val since = now - days * DAY_MS
            val window = history.filter { it.playedAt in since..now }
            val counts = window.groupingBy { it.songId }.eachCount()
            val topSongs = counts.entries.mapNotNull { (id, n) -> byId[id]?.let { it to n } }
                .sortedWith(compareByDescending<Pair<Song, Int>> { it.second }.thenBy { it.first.title.lowercase() })
                .take(10)
            val artistCounts = HashMap<String, Int>()
            for ((id, n) in counts) {
                val artist = byId[id]?.artist ?: continue
                if (artist == MetadataFallback.UNKNOWN_ARTIST) continue
                artistCounts[artist] = (artistCounts[artist] ?: 0) + n
            }
            val minutes = window.sumOf { (byId[it.songId]?.durationMs ?: 0L) } / 60_000L
            fun dayIndex(t: Long) = Math.floorDiv(t + zoneOffsetMs, DAY_MS)
            val today = dayIndex(now)
            val perDay = IntArray(days)
            for (h in window) {
                val i = (days - 1) - (today - dayIndex(h.playedAt)).toInt()
                if (i in 0 until days) perDay[i]++
            }
            val activeDays = history.map { dayIndex(it.playedAt) }.toHashSet()
            var streak = 0
            while (today - streak in activeDays) streak++
            return ListeningStats(
                plays = window.size,
                distinctSongs = counts.size,
                estimatedMinutes = minutes,
                topSongs = topSongs,
                topArtists = artistCounts.entries.sortedByDescending { it.value }.take(8).map { it.key to it.value },
                playsPerDay = perDay.toList(),
                streakDays = streak,
            )
        }
    }
}
