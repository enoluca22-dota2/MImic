package com.example.mimic.data

/** A named collection of songs (an album, artist, folder or genre). */
data class SongGroup(
    val name: String,
    val subtitle: String,
    val songs: List<Song>,
)

/** Pure grouping of the scanned library into browsable collections. Unit-testable. */
object LibraryGrouping {

    private fun countLabel(n: Int) = if (n == 1) "1 song" else "$n songs"

    fun albums(songs: List<Song>): List<SongGroup> =
        songs.groupBy { it.album }
            .map { (album, list) -> SongGroup(album, list.first().artist, list) }
            .sortedBy { it.name.lowercase() }

    fun artists(songs: List<Song>): List<SongGroup> =
        songs.groupBy { it.artist }
            .map { (artist, list) -> SongGroup(artist, countLabel(list.size), list) }
            .sortedBy { it.name.lowercase() }

    fun folders(songs: List<Song>): List<SongGroup> =
        songs.groupBy { it.folder }
            .map { (folder, list) -> SongGroup(folder, countLabel(list.size), list) }
            .sortedBy { it.name.lowercase() }

    fun genres(songs: List<Song>): List<SongGroup> =
        songs.filter { !it.genre.isNullOrBlank() }
            .groupBy { it.genre!! }
            .map { (genre, list) -> SongGroup(genre, countLabel(list.size), list) }
            .sortedBy { it.name.lowercase() }
}
