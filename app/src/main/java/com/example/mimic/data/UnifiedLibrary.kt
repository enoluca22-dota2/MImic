package com.example.mimic.data

/** Library / search / Home source filter (MImic 3.0; per-provider since 3.1). */
enum class SourceFilter(val label: String) {
    ALL("All"),
    PHONE("Phone"),
    NAVIDROME("Navidrome"),
    DOWNLOADED("Downloaded");

    /** Provider filters include that provider's downloads: a downloaded Navidrome song is still Navidrome. */
    fun matches(song: Song): Boolean = when (this) {
        ALL -> true
        PHONE -> song.provider == MusicProvider.PHONE
        NAVIDROME -> song.provider == MusicProvider.NAVIDROME
        DOWNLOADED -> song.isDownloaded
    }
}

enum class SongSort(val label: String) {
    NAME("Name"),
    ARTIST("Artist"),
    ALBUM("Album"),
    RECENTLY_ADDED("Recently added"),
    RECENTLY_PLAYED("Recently played"),
}

/** An album with a stable, source-aware [key] (never just its title). */
data class AlbumGroup(
    val key: String,
    val title: String,
    val artist: String,
    val artUri: String?,
    val songs: List<Song>,
)

data class ArtistGroup(
    val key: String,
    val name: String,
    val songs: List<Song>,
    val albums: List<AlbumGroup>,
)

/**
 * Pure merge / filter / sort / grouping of the three song sources into ONE library — the single
 * source of truth every screen observes. JVM-tested; no Android, no I/O.
 */
object UnifiedLibrary {

    /**
     * Merge on-device, remote-catalog (Navidrome) and downloaded songs. A remote
     * track and its download share
     * one id, so the download *replaces* the streamed entry (one logical song, never two). A
     * download whose file is missing keeps its identity and, when the server catalog is loaded,
     * plays from the stream instead; it stays flagged [Song.fileMissing] so the UI can offer a
     * re-download. On-device files are never merged with server tracks, even with equal titles.
     */
    fun merge(local: List<Song>, server: List<Song>, downloads: List<Song>): List<Song> {
        val serverById = server.associateBy { it.id }
        val downloadIds = HashSet<Long>(downloads.size)
        val merged = ArrayList<Song>(local.size + server.size + downloads.size)
        merged += local
        for (d in downloads) {
            if (!downloadIds.add(d.id)) continue
            val streamed = serverById[d.id]
            merged += if (streamed == null) d else d.copy(
                // Missing file + server reachable: same identity, stream instead.
                uri = if (d.fileMissing) streamed.uri else d.uri,
                // The server's album id / genre / date added are authoritative (downloads made
                // before 3.0 didn't record the album id), so the download groups with its album.
                albumKey = streamed.albumKey,
                genre = d.genre ?: streamed.genre,
                dateAddedSec = if (streamed.dateAddedSec > 0) streamed.dateAddedSec else d.dateAddedSec,
            )
        }
        for (s in server) if (s.id !in downloadIds) merged += s
        return merged
    }

    fun filter(songs: List<Song>, filter: SourceFilter): List<Song> =
        if (filter == SourceFilter.ALL) songs else songs.filter(filter::matches)

    fun sort(songs: List<Song>, sort: SongSort, lastPlayed: Map<Long, Long> = emptyMap()): List<Song> {
        val byTitle = compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        return when (sort) {
            SongSort.NAME -> songs.sortedWith(byTitle)
            SongSort.ARTIST -> songs.sortedWith(compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }.then(byTitle))
            SongSort.ALBUM -> songs.sortedWith(compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.album }.then(byTitle))
            SongSort.RECENTLY_ADDED -> songs.sortedWith(compareByDescending<Song> { it.dateAddedSec }.then(byTitle))
            SongSort.RECENTLY_PLAYED -> songs.sortedWith(compareByDescending<Song> { lastPlayed[it.id] ?: 0L }.then(byTitle))
        }
    }

    fun search(songs: List<Song>, query: String): List<Song> = LibraryQueries.search(songs, query)

    /** Albums keyed by [Song.albumKey]; the album artist is the most common track artist. */
    fun albums(songs: List<Song>): List<AlbumGroup> =
        songs.groupBy { it.albumKey }
            .map { (key, list) -> albumOf(key, list) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

    fun album(songs: List<Song>, key: String): AlbumGroup? =
        songs.filter { it.albumKey == key }.takeIf { it.isNotEmpty() }?.let { albumOf(key, it) }

    fun artists(songs: List<Song>): List<ArtistGroup> =
        songs.groupBy { it.artistKey }
            .map { (key, list) -> artistOf(key, list) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    fun artist(songs: List<Song>, key: String): ArtistGroup? =
        songs.filter { it.artistKey == key }.takeIf { it.isNotEmpty() }?.let { artistOf(key, it) }

    private fun albumOf(key: String, list: List<Song>): AlbumGroup {
        // Track order when tags/file names give one; titles otherwise (and among unnumbered tracks).
        val tracks = list.sortedWith(
            compareBy<Song> { if (it.trackNo > 0) 0 else 1 }.thenBy { it.trackNo }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
        val artist = list.groupingBy { it.artist }.eachCount().maxByOrNull { it.value }?.key
            ?: MetadataFallback.UNKNOWN_ARTIST
        return AlbumGroup(
            key = key,
            title = list.first().album,
            artist = artist,
            artUri = list.firstNotNullOfOrNull { it.albumArtUri },
            songs = tracks,
        )
    }

    private fun artistOf(key: String, list: List<Song>): ArtistGroup = ArtistGroup(
        key = key,
        // Most common spelling among the artist's tracks.
        name = list.groupingBy { it.artist }.eachCount().maxByOrNull { it.value }!!.key,
        songs = list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }),
        albums = albums(list),
    )
}
