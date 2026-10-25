package com.example.mimic.data

import android.content.Context
import android.net.Uri
import com.example.mimic.data.db.DownloadDao
import com.example.mimic.data.db.DownloadEntity
import com.example.mimic.data.server.RemoteSong
import com.example.mimic.data.server.SubsonicClient
import com.example.mimic.data.server.songIdForRemote
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Offline downloads (MImic 3.0): the one place that writes, verifies, maps and removes downloaded
 * files. The Room `downloads` table is the record; files live in app-private storage.
 *
 * A download is recorded only after the file is complete, readable and renamed into place, so
 * every row the UI observes is a finished download — and because the UI observes the table as a
 * Flow, a new download shows up on Home / Library immediately, without a refresh or restart.
 */
class DownloadRepository(context: Context, private val dao: DownloadDao) {

    private val dir = File(context.filesDir, "downloads")
    private val coverDir = File(dir, "covers")

    /** Every download as a playable [Song]; file existence is checked off the main thread. */
    val songs: Flow<List<Song>> = dao.all()
        .map { rows -> rows.map { it.toSong(fileMissing = !fileOf(it).let { f -> f.isFile && f.canRead() && f.length() > 0 }) } }
        .flowOn(Dispatchers.IO)

    val downloadedIds: Flow<Set<String>> = dao.downloadedIds().map { it.toSet() }

    /** Space used by offline songs + their covers, and free space on that storage (4.1). */
    data class Storage(val usedBytes: Long, val freeBytes: Long)

    suspend fun storage(): Storage = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val used = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        Storage(used, dir.apply { mkdirs() }.usableSpace)
    }

    /** Download [remote] for offline playback; returns the verified file size on success. */
    suspend fun download(client: SubsonicClient, remote: RemoteSong): Result<Long> {
        val ext = remote.suffix?.takeIf { it.isNotBlank() }?.let(::safeName) ?: "mp3"
        val target = File(dir, "${safeName(remote.id)}.$ext")
        client.download(remote, target).onFailure { return Result.failure(it) }

        // Artwork is saved next to the audio so downloaded songs keep their cover offline. A
        // missing cover never fails the download — it just falls back to the server URL.
        var cover: String? = client.coverArtUrl(remote.coverArtId)
        remote.coverArtId?.let { id ->
            val coverFile = File(coverDir, safeName(id) + ".img")
            val ok = coverFile.isFile || client.downloadCover(id, coverFile).isSuccess
            if (ok) cover = Uri.fromFile(coverFile).toString()
        }
        val size = withContext(Dispatchers.IO) { target.length() }
        dao.upsert(
            DownloadEntity(
                remoteId = remote.id, title = remote.title, artist = remote.artist,
                album = remote.album, durationMs = remote.durationMs,
                fileUri = Uri.fromFile(target).toString(),
                coverArtUri = cover,
                downloadedAt = System.currentTimeMillis(),
                albumId = remote.albumId,
                accountKey = client.accountKey,
                sizeBytes = size,
            ),
        )
        return Result.success(size)
    }

    /** The app-private folder online downloads are written into (full edition, 4.4). */
    val onlineDir: File get() = File(dir, "online").apply { mkdirs() }

    /**
     * Record a finished, verified online download (written by the full edition's worker). Its
     * artwork is a file next to it, so every downloaded track keeps its own cover offline.
     */
    suspend fun recordOnline(track: com.example.mimic.data.online.OnlineTrack, file: File, cover: File?) {
        dao.upsert(
            DownloadEntity(
                remoteId = com.example.mimic.data.online.OnlineIds.remoteId(track.videoId),
                title = track.title, artist = track.artist, album = track.album ?: "",
                durationMs = track.durationMs,
                fileUri = Uri.fromFile(file).toString(),
                coverArtUri = cover?.takeIf { it.isFile }?.let { Uri.fromFile(it).toString() } ?: track.artworkUrl,
                downloadedAt = System.currentTimeMillis(),
                albumId = track.albumId,
                accountKey = "youtube",
                sizeBytes = withContext(Dispatchers.IO) { file.length() },
            ),
        )
    }

    /** Whether [remoteId] already has a download row (duplicate prevention). */
    suspend fun isDownloaded(remoteId: String): Boolean = dao.isDownloaded(remoteId)

    /** Only when the user explicitly asks while disconnecting an account. */
    suspend fun removeAllForAccount(accountKey: String) {
        for (row in dao.allOnce()) if (row.accountKey == accountKey) remove(row.remoteId)
    }

    /** Free space left for downloads, in bytes. */
    fun freeBytes(): Long = (if (dir.exists()) dir else dir.parentFile ?: dir).usableSpace

    /** Remove the local copy only. The original on the server is never touched. */
    suspend fun remove(remoteId: String) = withContext(Dispatchers.IO) {
        val row = dao.get(remoteId) ?: return@withContext
        fileOf(row).delete()
        dao.delete(remoteId)
        // Covers are shared per album; delete one only when no other download still uses it.
        val cover = row.coverArtUri?.takeIf { it.startsWith("file:") }
        if (cover != null) {
            val stillUsed = dao.allOnce().any { it.coverArtUri == cover }
            if (!stillUsed) Uri.parse(cover).path?.let { File(it).delete() }
        }
    }

    private fun fileOf(row: DownloadEntity): File = File(Uri.parse(row.fileUri).path ?: "")

    // Rows written before 3.0 hold the server's raw placeholders ("[Unknown Artist]"); normalize
    // them with the same fallback policy as every other source.
    private fun DownloadEntity.toSong(fileMissing: Boolean) = Song(
        id = songIdForRemote(remoteId),
        title = MetadataFallback.title(title, null),
        artist = MetadataFallback.artist(artist),
        album = MetadataFallback.album(album),
        durationMs = durationMs, uri = fileUri, albumArtUri = coverArtUri,
        dateAddedSec = downloadedAt / 1000, folder = "Downloads",
        source = SongSource.DOWNLOADED,
        remoteId = remoteId,
        albumKey = when {
            LegacyCloudIds.isCloud(remoteId) -> albumId ?: "cloud:${accountKey}:album:unknown"
            com.example.mimic.data.online.OnlineIds.isOnline(remoteId) ->
                com.example.mimic.data.online.OnlineIds.albumKey(albumId, MetadataFallback.album(album))
            else -> SubsonicClient.serverAlbumKey(albumId, album)
        },
        artistKey = MetadataFallback.artistKey(MetadataFallback.artist(artist)),
        fileMissing = fileMissing,
    )

    private fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun sha(s: String) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
