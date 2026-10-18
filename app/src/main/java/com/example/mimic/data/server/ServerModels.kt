package com.example.mimic.data.server

/** Connection details for a Subsonic/Navidrome server. */
data class ServerCredentials(
    val baseUrl: String,
    val username: String,
    val password: String,
)

data class RemoteAlbum(
    val id: String,
    val name: String,
    val artist: String,
    val coverArtId: String?,
    val songCount: Int,
)

data class RemoteSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverArtId: String?,
    val suffix: String?,
    val albumId: String? = null,
    val artistId: String? = null,
    /** When the server first saw the file (epoch seconds), for "Recently added". 0 = unknown. */
    val createdSec: Long = 0L,
    val genre: String? = null,
)

sealed interface ServerStatus {
    data object Disconnected : ServerStatus
    data object Connecting : ServerStatus
    data object Connected : ServerStatus
    data class Error(val message: String) : ServerStatus
}

/** Stable positive Long id for a remote track, so it fits MImic's Long-keyed player/queue. */
fun remoteStableId(remoteId: String): Long =
    (remoteId.hashCode().toLong() and 0x7FFFFFFFL) or 0x100000000L // high bit marks "remote"

/** The one song id for any remote id: cloud refs get collision-resistant 62-bit ids (MImic 3.1). */
fun songIdForRemote(remoteId: String): Long =
    if (com.example.mimic.data.cloud.CloudRef.parse(remoteId) != null) com.example.mimic.data.cloud.cloudStableId(remoteId)
    else remoteStableId(remoteId)
