package com.example.mimic.data

/**
 * Library tabs (MImic 3.0 categories). Names are persisted — never rename one. GENRES / FOLDERS /
 * SMART open their own screens instead of switching the list.
 */
enum class LibraryTab(val label: String) {
    SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists"), PLAYLISTS("Playlists"),
    FAVORITES("Favorites"), DOWNLOADS("Downloads"),
    GENRES("Genres"), FOLDERS("Folders"), SMART("Smart Mixes"),
}

/** Sort for album / artist lists. */
enum class GroupSort(val label: String) {
    NAME("Name"), ARTIST("Artist"), RECENTLY_ADDED("Recently added"), SIZE("Most songs"),
}

/**
 * How the user arranged Library (MImic 3.2): tab order + hidden tabs, grid or list for albums
 * and artists, and sort field + direction. Persisted in settings, so it survives restarts.
 */
data class LibraryConfig(
    val tabOrder: List<LibraryTab> = LibraryTab.entries,
    val hiddenTabs: Set<LibraryTab> = emptySet(),
    val albumsGrid: Boolean = true,
    val artistsGrid: Boolean = false,
    val songSort: SongSort = SongSort.NAME,
    /** Reverse the sort's natural order (Name Z–A, Recently added oldest first, ...). */
    val songsReversed: Boolean = false,
    val groupSort: GroupSort = GroupSort.NAME,
    val groupsReversed: Boolean = false,
) {
    /** The user's tab order, completed with tabs added in newer versions (appended). */
    fun completeOrder(): List<LibraryTab> =
        tabOrder.distinct() + LibraryTab.entries.filter { it !in tabOrder }

    /** Visible tabs in order; hiding everything is not allowed, so Songs comes back then. */
    fun visibleTabs(): List<LibraryTab> =
        completeOrder().filter { it !in hiddenTabs }.ifEmpty { listOf(LibraryTab.SONGS) }
}

object GroupSorting {
    fun albums(list: List<AlbumGroup>, sort: GroupSort, reversed: Boolean): List<AlbumGroup> {
        val byTitle = compareBy<AlbumGroup, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        val sorted = when (sort) {
            GroupSort.NAME -> list.sortedWith(byTitle)
            GroupSort.ARTIST -> list.sortedWith(compareBy<AlbumGroup, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }.then(byTitle))
            GroupSort.RECENTLY_ADDED -> list.sortedWith(compareByDescending<AlbumGroup> { a -> a.songs.maxOf { it.dateAddedSec } }.then(byTitle))
            GroupSort.SIZE -> list.sortedWith(compareByDescending<AlbumGroup> { it.songs.size }.then(byTitle))
        }
        return if (reversed) sorted.asReversed() else sorted
    }

    fun artists(list: List<ArtistGroup>, sort: GroupSort, reversed: Boolean): List<ArtistGroup> {
        val byName = compareBy<ArtistGroup, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val sorted = when (sort) {
            GroupSort.NAME, GroupSort.ARTIST -> list.sortedWith(byName)
            GroupSort.RECENTLY_ADDED -> list.sortedWith(compareByDescending<ArtistGroup> { a -> a.songs.maxOf { it.dateAddedSec } }.then(byName))
            GroupSort.SIZE -> list.sortedWith(compareByDescending<ArtistGroup> { it.songs.size }.then(byName))
        }
        return if (reversed) sorted.asReversed() else sorted
    }
}

/** Move [tab] one step up/down in the order (pure). */
fun List<LibraryTab>.movedTab(tab: LibraryTab, delta: Int): List<LibraryTab> {
    val idx = indexOf(tab)
    val target = (idx + delta).coerceIn(0, size - 1)
    if (idx < 0 || target == idx) return this
    return toMutableList().apply { removeAt(idx); add(target, tab) }
}
