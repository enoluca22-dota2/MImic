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
}
