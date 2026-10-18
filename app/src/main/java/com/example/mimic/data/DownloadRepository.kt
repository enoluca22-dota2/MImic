package com.example.mimic.data

import android.content.Context
import android.net.Uri
import com.example.mimic.data.db.DownloadDao
import com.example.mimic.data.db.DownloadEntity
import com.example.mimic.data.server.RemoteSong
import com.example.mimic.data.server.SubsonicClient
import com.example.mimic.data.cloud.CloudRef
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

    /**
     * Save a cloud track (MImic 3.1) from [body] with the same guarantees as server downloads:
     * bytes go to a ".part" file, the size must match the cloud listing, the provider's content
     * hash (Drive MD5 / OneDrive SHA-1) must match when it has one, and only then is the file
     * renamed into place and recorded. Cancellation (the coroutine) deletes the partial file.
     */
    suspend fun saveCloudDownload(
        song: Song,
        accountKey: String,
        extension: String,
        body: java.io.InputStream,
        expectedSize: Long,
        hash: String?,
        onProgress: (Long) -> Unit,
    ): Result<Long> = withContext(Dispatchers.IO) {
        val remoteId = song.remoteId ?: return@withContext Result.failure(IllegalArgumentException("Not a remote song"))
        val target = File(dir, "cloud_" + sha(remoteId).take(32) + "." + safeName(extension.ifBlank { "mp3" }))
        val part = File(dir, target.name + ".part")
        val algorithm = when {
            hash?.startsWith("md5:") == true -> "MD5"
            hash?.startsWith("sha1:") == true -> "SHA-1"
            else -> null
        }
        try {
            dir.mkdirs()
            val digest = algorithm?.let { java.security.MessageDigest.getInstance(it) }
            var written = 0L
            var lastReport = 0L
            body.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest?.update(buf, 0, n)
                        written += n
                        if (written - lastReport >= 256 * 1024) { onProgress(written); lastReport = written }
                    }
                    out.fd.sync()
                }
            }
            onProgress(written)
            if (expectedSize > 0 && written != expectedSize) {
                throw java.io.IOException("Incomplete download: $written of $expectedSize bytes")
            }
            if (digest != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != hash!!.substringAfter(':')) throw java.io.IOException("Downloaded file failed its integrity check")
            }
            if (written == 0L || !part.canRead()) throw java.io.IOException("Downloaded file is empty or unreadable")
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw java.io.IOException("Could not finalize download")

            // Keep the artwork with the download so it survives disconnecting the account.
            val cover = song.albumArtUri?.takeIf { it.startsWith("file:") }?.let { uri ->
                val src = File(Uri.parse(uri).path ?: "")
                if (!src.isFile) null else {
                    val dst = File(coverDir, src.name)
                    if (!dst.isFile) { coverDir.mkdirs(); src.copyTo(dst, overwrite = true) }
                    Uri.fromFile(dst).toString()
                }
            }
            dao.upsert(
                DownloadEntity(
                    remoteId = remoteId, title = song.title, artist = song.artist, album = song.album,
                    durationMs = song.durationMs, fileUri = Uri.fromFile(target).toString(), coverArtUri = cover,
                    downloadedAt = System.currentTimeMillis(),
                    albumId = song.albumKey, // cloud rows store the full album key
                    accountKey = accountKey, sizeBytes = written,
                ),
            )
            Result.success(written)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            part.delete()
        }
    }

    /** Only when the user explicitly asks while disconnecting an account. */
    suspend fun removeAllForAccount(accountKey: String) {
        for (row in dao.allOnce()) if (row.accountKey == accountKey) remove(row.remoteId)
    }

    /** Free space left for downloads, in bytes. */
    fun freeBytes(): Long = (if (dir.exists()) dir else dir.parentFile ?: dir).usableSpace

    /** Remove the local copy only. The original on the server / in the cloud is never touched. */
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
        albumKey = if (CloudRef.parse(remoteId) != null) albumId ?: "cloud:${accountKey}:album:unknown"
        else SubsonicClient.serverAlbumKey(albumId, album),
        artistKey = MetadataFallback.artistKey(MetadataFallback.artist(artist)),
        fileMissing = fileMissing,
    )

    private fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun sha(s: String) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
