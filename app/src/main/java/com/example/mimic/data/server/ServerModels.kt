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
