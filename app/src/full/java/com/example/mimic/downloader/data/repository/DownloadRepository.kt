package com.example.mimic.downloader.data.repository

import com.example.mimic.downloader.core.FileNaming
import com.example.mimic.downloader.data.local.db.DownloadDao
import com.example.mimic.downloader.data.local.db.DownloadEntity
import com.example.mimic.downloader.data.model.DownloadCategory
import com.example.mimic.downloader.data.model.DownloadStatus
import com.example.mimic.downloader.data.model.FormatKind
import com.example.mimic.downloader.data.model.MediaFormat
import com.example.mimic.downloader.data.model.MediaInfo
import com.example.mimic.downloader.data.model.PlaylistEntry
import com.example.mimic.downloader.data.model.QuickFormat
import com.example.mimic.downloader.download.DownloadEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the persisted download queue (Room); the engine picks QUEUED rows up by itself. Every
 * entry point — single item, playlist item, batch item — ends up as one [DownloadEntity] in the
 * same queue, so there is exactly one download system.
 */
class DownloadRepository(
    private val downloadDao: DownloadDao,
    private val downloadEngine: DownloadEngine,
) {
    fun observeAll(): Flow<List<DownloadEntity>> = downloadDao.observeAll()

    val waitingForWifi: StateFlow<Boolean> get() = downloadEngine.waitingForWifi

    /** Strictly increasing queue positions, even for many rows created in the same millisecond. */
    private val lastPosition = AtomicLong(0)
    private fun nextQueuePosition(): Long =
        lastPosition.updateAndGet { maxOf(it + 1, System.currentTimeMillis()) }

    /**
     * Persists a new job for [media] + [format]. When [startNow] is false this is "Download
     * Later": the row is created as PAUSED so the engine never auto-picks it up until the user
     * explicitly resumes it from the Downloads screen.
     */
    suspend fun enqueue(
        media: MediaInfo,
        format: MediaFormat,
        category: DownloadCategory,
        fileBaseName: String,
        startNow: Boolean,
    ): Long {
        val now = System.currentTimeMillis()
        val isAudio = format.kind == FormatKind.AUDIO
        val convertToMp3 = isAudio && !format.nativeAudio
        val entity = DownloadEntity(
            sourceUrl = media.sourceUrl,
            webpageUrl = media.webpageUrl,
            title = media.title,
            thumbnailUrl = media.thumbnailUrl,
            extractorKey = media.extractorKey,
            formatId = format.formatId,
            formatKind = format.kind,
            resolutionLabel = format.resolutionLabel,
            // MP3 options end up converted, so the persisted container reflects the real output
            // file; native audio (M4A) keeps the source container.
            container = if (convertToMp3) "mp3" else format.container,
            videoCodec = format.videoCodec,
            audioCodec = format.audioCodec,
            requiresAudioMerge = format.kind == FormatKind.VIDEO && !format.hasAudio,
            requiresAudioExtraction = convertToMp3,
            // null = "Original quality (VBR)"; must stay null all the way to the encoder.
            audioBitrateKbps = format.mp3BitrateKbps,
            category = category,
            fileBaseName = FileNaming.sanitize(fileBaseName),
            status = if (startNow) DownloadStatus.QUEUED else DownloadStatus.PAUSED,
            createdAt = now,
            updatedAt = now,
            queuePosition = nextQueuePosition(),
            uploader = media.uploader,
        )
        return downloadDao.insert(entity)
    }

    /**
     * Groups items queued together from one playlist ("Downloading playlist 4 / 18").
     * [sourceIndex] is the item's 1-based position in the source playlist.
     */
    data class Batch(val id: String, val title: String?, val index: Int, val size: Int, val sourceIndex: Int? = null)

    /**
     * Queues the selected playlist items, one job each, in playlist order. One item failing
     * later never affects the others: they are independent rows that only share batch info.
     */
    suspend fun enqueuePlaylist(
        collectionUrl: String,
        playlistId: String?,
        playlistTitle: String?,
        entries: List<PlaylistEntry>,
        choice: QuickFormat,
        batchId: String = newBatchId(playlistId),
    ): Int {
        entries.forEachIndexed { i, entry ->
            val batch = Batch(batchId, playlistTitle, index = i + 1, size = entries.size, sourceIndex = entry.index)
            if (entry.url != null) {
                enqueueQuick(entry.url, entry.title, entry.thumbnailUrl, choice, uploader = entry.uploader, batch = batch)
            } else {
                // No URL of its own: download it by position inside the playlist.
                enqueueQuick(collectionUrl, entry.title, entry.thumbnailUrl, choice, playlistIndex = entry.index, uploader = entry.uploader, batch = batch)
            }
        }
        return entries.size
    }

    /**
     * Queues an item that has no per-item format list (playlist entries, multi-URL batches).
     * The [choice] becomes a yt-dlp format selector resolved against the item's real formats at
     * download time — "up to 720p" picks the best stream that is ≤ 720p, never an invented one.
     */
    suspend fun enqueueQuick(
        sourceUrl: String,
        title: String,
        thumbnailUrl: String?,
        choice: QuickFormat,
        playlistIndex: Int? = null,
        startNow: Boolean = true,
        uploader: String? = null,
        batch: Batch? = null,
    ): Long {
        val now = System.currentTimeMillis()
        val base = when (choice) {
            is QuickFormat.BestVideo -> DownloadEntity(
                sourceUrl = sourceUrl,
                webpageUrl = sourceUrl,
                title = title,
                thumbnailUrl = thumbnailUrl,
                extractorKey = null,
                // Merged with the best audio by DownloadRequestBuilder; the height cap comes from
                // resolutionLabel ("720p" → [height<=720]).
                formatId = "bestvideo" + (choice.maxHeight?.let { "[height<=$it]" } ?: ""),
                formatKind = FormatKind.VIDEO,
                resolutionLabel = choice.maxHeight?.let { "${it}p" },
                container = "mp4",
                videoCodec = null,
                audioCodec = null,
                requiresAudioMerge = true,
                requiresAudioExtraction = false,
                category = DownloadCategory.VIDEO,
                fileBaseName = FileNaming.sanitize(title),
                status = if (startNow) DownloadStatus.QUEUED else DownloadStatus.PAUSED,
                createdAt = now,
                updatedAt = now,
                queuePosition = nextQueuePosition(),
                playlistIndex = playlistIndex,
            )
            is QuickFormat.AudioMp3 -> DownloadEntity(
                sourceUrl = sourceUrl,
                webpageUrl = sourceUrl,
                title = title,
                thumbnailUrl = thumbnailUrl,
                extractorKey = null,
                formatId = "bestaudio",
                formatKind = FormatKind.AUDIO,
                resolutionLabel = null,
                container = "mp3",
                videoCodec = null,
                audioCodec = null,
                requiresAudioMerge = false,
                requiresAudioExtraction = true,
                audioBitrateKbps = choice.bitrateKbps,
                category = DownloadCategory.MUSIC,
                fileBaseName = FileNaming.sanitize(title),
                status = if (startNow) DownloadStatus.QUEUED else DownloadStatus.PAUSED,
                createdAt = now,
                updatedAt = now,
                queuePosition = nextQueuePosition(),
                playlistIndex = playlistIndex,
            )
        }
        val entity = base.copy(
            uploader = uploader,
            batchId = batch?.id,
            batchTitle = batch?.title,
            batchIndex = batch?.index,
            batchSize = batch?.size,
            playlistSourceIndex = batch?.sourceIndex,
        )
        return downloadDao.insert(entity)
    }

    companion object {
        /** Batch id for one "download these playlist items" action (the Library links it to its playlist). */
        fun newBatchId(playlistId: String?): String = "pl-${playlistId ?: "items"}-${System.currentTimeMillis()}"
    }

    suspend fun pause(id: Long) = downloadEngine.pause(id)
    suspend fun resume(id: Long) = downloadEngine.resume(id)
    suspend fun cancel(id: Long) = downloadEngine.cancel(id)
    suspend fun retry(id: Long) = downloadEngine.retry(id)
    suspend fun deleteRecord(id: Long) = downloadEngine.deleteRecord(id)

    suspend fun pauseAll() = downloadEngine.pauseAll()
    suspend fun resumeAll() = downloadEngine.resumeAll()
    suspend fun cancelAll() = downloadEngine.cancelAll()
    suspend fun retryAllFailed() = downloadEngine.retryAllFailed()
    suspend fun move(id: Long, up: Boolean) = downloadEngine.move(id, up)
}
