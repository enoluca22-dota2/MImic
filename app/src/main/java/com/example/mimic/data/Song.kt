package com.example.mimic.data

/**
 * Where a track's audio comes from right now. A downloaded server/cloud track is [DOWNLOADED];
 * [CLOUD] is streamed from Google Drive / OneDrive (MImic 3.1).
 */
enum class SongSource { LOCAL, SERVER, DOWNLOADED, CLOUD }

/** Which library a track belongs to, whether it is streamed or downloaded (MImic 3.1). */
enum class MusicProvider(val label: String) {
    PHONE("Phone"),
    NAVIDROME("Navidrome"),
    GOOGLE_DRIVE("Google Drive"),
    ONEDRIVE("OneDrive"),
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
     * Remote identity, null for on-device files: a Navidrome track id, or a namespaced cloud id
     * "onedrive:<account>:<item>" / "gdrive:<account>:<file>" (see CloudRef).
     */
    val remoteId: String? = null,
    val albumKey: String = "album:" + album.trim().lowercase(),
    val artistKey: String = MetadataFallback.artistKey(artist),
    /** DOWNLOADED only: the offline file is gone or unreadable (playback falls back to streaming). */
    val fileMissing: Boolean = false,
    /** disc * 1000 + track when known (album track order), else 0. */
    val trackNo: Int = 0,
) {
    /** Playable without a network connection. */
    val isOffline: Boolean get() = source == SongSource.LOCAL || (source == SongSource.DOWNLOADED && !fileMissing)
    val isDownloaded: Boolean get() = source == SongSource.DOWNLOADED
    /** Comes from a remote library (Navidrome or a cloud drive), so it can be downloaded. */
    val isFromServer: Boolean get() = remoteId != null
    val provider: MusicProvider get() = providerOf(remoteId)

    companion object {
        fun providerOf(remoteId: String?): MusicProvider = when {
            remoteId == null -> MusicProvider.PHONE
            else -> com.example.mimic.data.cloud.CloudRef.parse(remoteId)?.provider?.musicProvider ?: MusicProvider.NAVIDROME
        }
    }
}
