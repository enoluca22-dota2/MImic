package com.example.mimic.data

/** Where a track's audio comes from. A downloaded server track is [DOWNLOADED], not [SERVER]. */
enum class SongSource { LOCAL, SERVER, DOWNLOADED }

/**
 * A single track — from the device's media library, the music server, or an offline download.
 *
 * URIs are kept as [String]s (not android.net.Uri) so the model and all library-query
 * logic are plain Kotlin and unit-testable on the JVM. Media3 and Coil both accept
 * String URIs directly.
 *
 * Identity: [id] is the one logical id used by favorites, history, playlists and the queue. A
 * server track and its offline download share the same id (see remoteStableId), so the same
 * recording never appears twice. [albumKey]/[artistKey] are source-aware grouping keys
 * (MediaStore album id, server album id) so two different albums that merely share a title are
 * never merged.
 */
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: String,
    val albumArtUri: String?,
    val dateAddedSec: Long,
    val folder: String = "",
    val genre: String? = null,
    val source: SongSource = SongSource.LOCAL,
    /** Server track id for SERVER / DOWNLOADED songs, null for on-device files. */
    val remoteId: String? = null,
    val albumKey: String = "album:" + album.trim().lowercase(),
    val artistKey: String = MetadataFallback.artistKey(artist),
    /** DOWNLOADED only: the offline file is gone or unreadable (playback falls back to streaming). */
    val fileMissing: Boolean = false,
) {
    /** Playable without a network connection. */
    val isOffline: Boolean get() = source == SongSource.LOCAL || (source == SongSource.DOWNLOADED && !fileMissing)
    val isDownloaded: Boolean get() = source == SongSource.DOWNLOADED
    val isFromServer: Boolean get() = remoteId != null
}
