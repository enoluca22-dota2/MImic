package com.example.mimic.downloader.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.mimic.downloader.data.model.DownloadCategory
import com.example.mimic.downloader.data.model.DownloadStatus
import com.example.mimic.downloader.data.model.FormatKind

/**
 * A persisted download job. This is the single source of truth for the queue: the download
 * engine reads/writes this table, and it survives process death and device restarts.
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceUrl: String,
    val webpageUrl: String,
    val title: String,
    val thumbnailUrl: String?,
    val extractorKey: String?,
    val formatId: String,
    val formatKind: FormatKind,
    val resolutionLabel: String?,
    val container: String?,
    val videoCodec: String?,
    val audioCodec: String?,
    val requiresAudioMerge: Boolean,
    val requiresAudioExtraction: Boolean,
    /** Target MP3 bitrate for audio jobs; null = source-matched VBR. */
    val audioBitrateKbps: Int? = null,
    val category: DownloadCategory,
    val fileBaseName: String,
    val status: DownloadStatus,
    val progressPercent: Float = 0f,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val speedBytesPerSec: Long = 0,
    val etaSeconds: Long? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null,
    val fileUri: String? = null,
    /** Queue order among waiting jobs (lower starts first). Set to the creation time; reordering swaps values. */
    @ColumnInfo(defaultValue = "0") val queuePosition: Long = 0,
    /** For items inside a multi-item post/collection without their own URL (yt-dlp --playlist-items). */
    val playlistIndex: Int? = null,
    /** Items enqueued together from one playlist share a batch id (used for "playlist 4 / 18"). */
    val batchId: String? = null,
    val batchTitle: String? = null,
    /** 1-based position of this item inside the playlist, as shown to the user. */
    val batchIndex: Int? = null,
    /** Number of items the user selected from that playlist. */
    val batchSize: Int? = null,
    val uploader: String? = null,
    /** 1-based position of the item in its source playlist (keeps the Library playlist in that order). */
    val playlistSourceIndex: Int? = null,
) {
    /** Rows created before queue ordering existed have position 0; they keep creation order. */
    val effectiveQueuePosition: Long get() = queuePosition.takeIf { it > 0 } ?: createdAt
}
