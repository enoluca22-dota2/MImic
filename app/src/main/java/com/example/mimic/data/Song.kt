package com.example.mimic.data

/**
 * Where a track's audio comes from right now. A downloaded server track is [DOWNLOADED].
 * ([CLOUD] is kept only so old saved state still parses; nothing streams from a cloud drive since 4.1.)
 */
enum class SongSource { LOCAL, SERVER, DOWNLOADED, CLOUD }

/** Which library a track belongs to, whether it is streamed or downloaded. */
enum class MusicProvider(val label: String) {
    PHONE("Phone"),
    NAVIDROME("Navidrome"),
}

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
    /**
     * Remote identity, null for on-device files: a Navidrome track id (or, for songs downloaded
     * from a cloud drive before 4.1, its old "onedrive:/gdrive:" id — see LegacyCloudIds).
     */
    val remoteId: String? = null,
    val albumKey: String = "album:" + album.trim().lowercase(),
    val artistKey: String = MetadataFallback.artistKey(artist),
    /** DOWNLOADED only: the offline file is gone or unreadable (playback falls back to streaming). */
    val fileMissing: Boolean = false,
    /** disc * 1000 + track when known (album track order), else 0. */
    val trackNo: Int = 0,
    /** LOCAL only: the file's folder relative to the storage root ("Music/WhatsApp/"), for folder exclusion (4.3). */
    val folderPath: String = "",
) {
    /** Playable without a network connection. */
    val isOffline: Boolean get() = source == SongSource.LOCAL || (source == SongSource.DOWNLOADED && !fileMissing)
    val isDownloaded: Boolean get() = source == SongSource.DOWNLOADED
    /** Comes from a remote library (Navidrome, or an old cloud-drive download), so it has a remote id. */
    val isFromServer: Boolean get() = remoteId != null
    val provider: MusicProvider get() = providerOf(remoteId)

    companion object {
        fun providerOf(remoteId: String?): MusicProvider = when {
            remoteId == null -> MusicProvider.PHONE
            // A song downloaded from a (removed) cloud drive is simply a file on this phone now.
            LegacyCloudIds.isCloud(remoteId) -> MusicProvider.PHONE
            else -> MusicProvider.NAVIDROME
        }
    }
}
