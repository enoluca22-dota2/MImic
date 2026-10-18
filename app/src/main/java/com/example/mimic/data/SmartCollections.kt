package com.example.mimic.data

import com.example.mimic.data.db.PlayCount

/**
 * Pure, deterministic generators for "smart" library collections built entirely from the user's
 * own library and recorded play history — no server, no fabricated data. Kept side-effect free and
 * JVM-testable; the ViewModel feeds it Room/MediaStore state.
 *
 * These are generated views, never stored playlists, so they never mutate the user's manual
 * playlists. Local and remote songs keep their own ids (remote ids are high-bit marked elsewhere).
 */
object SmartCollections {

    /** Songs that have never been played, newest additions first. */
    fun neverPlayed(songs: List<Song>, playedIds: Set<Long>, limit: Int): List<Song> =
        songs.asSequence()
            .filter { it.id !in playedIds }
            .sortedByDescending { it.dateAddedSec }
            .take(limit)
            .toList()

    /** Played-at-least-once songs with the fewest plays. [playCounts] must already be ascending. */
    fun leastPlayed(songs: List<Song>, playCounts: List<PlayCount>, limit: Int): List<Song> {
        val byId = songs.associateBy { it.id }
        return playCounts.asSequence()
            .mapNotNull { byId[it.songId] }
            .take(limit)
            .toList()
    }

    /** Favorited songs the user hasn't played recently — "you used to love these". */
    fun forgottenFavorites(
        songs: List<Song>,
        favoriteIds: Set<Long>,
        recentlyPlayedIds: Set<Long>,
        limit: Int,
    ): List<Song> {
        val byId = songs.associateBy { it.id }
        return favoriteIds.asSequence()
            .filter { it !in recentlyPlayedIds }
            .mapNotNull { byId[it] }
            .take(limit)
            .toList()
    }

    /**
     * A reproducible "random discovery" sample — deterministic for a given [seed] (e.g. the day),
     * so the same set shows for the whole day instead of reshuffling on every recomposition.
     */
    fun randomDiscovery(songs: List<Song>, seed: Long, limit: Int): List<Song> =
        songs.shuffled(java.util.Random(seed)).take(limit)

    /**
     * Favorite Artists mix: songs by artists the user favorited (artist ♡) or whose songs they
     * liked. Artists are interleaved round-robin so one prolific artist can't fill the mix, and the
     * songs played most recently go to the end so the mix doesn't open with what was just heard.
     * Deterministic for a given [seed].
     */
    fun favoriteArtistsMix(
        songs: List<Song>,
        favoriteArtistKeys: Set<String>,
        favoriteSongIds: Set<Long>,
        recentlyPlayedIds: List<Long>,
        seed: Long,
        limit: Int,
    ): List<Song> {
        val artistKeys = favoriteArtistKeys + songs.filter { it.id in favoriteSongIds }.map { it.artistKey }
        if (artistKeys.isEmpty()) return emptyList()
        val recent = recentlyPlayedIds.take(15).toSet()
        val rnd = java.util.Random(seed)
        val perArtist = songs.filter { it.artistKey in artistKeys }
            .groupBy { it.artistKey }
            .toSortedMap()
            .values
            .map { list -> list.shuffled(rnd).sortedBy { it.id in recent }.toMutableList() }
            .shuffled(rnd)
        val out = ArrayList<Song>(limit)
        while (out.size < limit && perArtist.any { it.isNotEmpty() }) {
            for (q in perArtist) if (q.isNotEmpty() && out.size < limit) out += q.removeAt(0)
        }
        return out.sortedBy { it.id in recent }
    }

    /**
     * Shuffle Discovery: a daily sample that favors songs played least (weight 1 / (1 + plays)),
     * at most [perArtist] songs per artist so the same few names don't repeat. Weighted sampling
     * without replacement (Efraimidis–Spirakis keys), deterministic for a given [seed].
     */
    fun shuffleDiscovery(
        songs: List<Song>,
        playCounts: Map<Long, Int>,
        seed: Long,
        limit: Int,
        perArtist: Int = 3,
    ): List<Song> {
        val rnd = java.util.Random(seed)
        val ranked = songs
            .sortedBy { it.id } // stable input order => same result for the same library + seed
            .map { s ->
                val weight = 1.0 / (1 + (playCounts[s.id] ?: 0))
                s to Math.pow(rnd.nextDouble(), 1.0 / weight)
            }
            .sortedByDescending { it.second }
        val used = HashMap<String, Int>()
        val out = ArrayList<Song>(limit)
        for ((s, _) in ranked) {
            if (out.size >= limit) break
            val n = used[s.artistKey] ?: 0
            if (n >= perArtist) continue
            used[s.artistKey] = n + 1
            out += s
        }
        return out
    }
}
