package com.example.mimic.data

import com.example.mimic.data.db.PlayCount

/**
 * Pure, side-effect-free library queries. Kept separate from the ViewModel and UI so
 * search, "recently added", "smart" suggestions and playlist reordering can be unit
 * tested on the JVM without Android or a database.
 */
object LibraryQueries {

    fun search(songs: List<Song>, query: String): List<Song> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return songs.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.artist.contains(q, ignoreCase = true) ||
                it.album.contains(q, ignoreCase = true)
        }
    }

    fun recentlyAdded(songs: List<Song>, limit: Int): List<Song> =
        songs.sortedByDescending { it.dateAddedSec }.take(limit)

    /** Songs by artists the user plays most, excluding ones already played. */
    fun suggested(songs: List<Song>, playCounts: List<PlayCount>, limit: Int): List<Song> {
        val byId = songs.associateBy { it.id }
        val playedIds = playCounts.map { it.songId }.toSet()
        val likedArtists = playCounts.mapNotNull { byId[it.songId]?.artist }.toSet()
        return songs.filter { it.artist in likedArtists && it.id !in playedIds }.take(limit)
    }

    /**
     * Albums the user played recently, most recent first ("Continue Listening" / Quick Access).
     * [recentIds] is the play history (distinct song ids, newest first); songs no longer in the
     * library are skipped, and each album appears once, at its most recent play.
     */
    fun recentAlbums(recentIds: List<Long>, songs: List<Song>, limit: Int): List<AlbumGroup> {
        val byId = songs.associateBy { it.id }
        val keys = LinkedHashSet<String>()
        for (id in recentIds) {
            val song = byId[id] ?: continue
            keys += song.albumKey
            if (keys.size >= limit) break
        }
        if (keys.isEmpty()) return emptyList()
        val members = songs.filter { it.albumKey in keys }
        val groups = UnifiedLibrary.albums(members).associateBy { it.key }
        return keys.mapNotNull { groups[it] }
    }

    /** Move the item at [from] to [to], returning a new list (used by drag/up-down reorder). */
    fun <T> List<T>.moved(from: Int, to: Int): List<T> {
        if (from !in indices || to !in indices) return this
        val copy = toMutableList()
        copy.add(to, copy.removeAt(from))
        return copy
    }
}
