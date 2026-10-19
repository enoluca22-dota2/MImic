package com.example.mimic.downloader.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.mimic.downloader.data.model.DownloadCategory
import com.example.mimic.downloader.data.model.DownloadStatus

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val downloadId: Long,
    val title: String,
    val sourceUrl: String,
    val webpageUrl: String,
    val thumbnailUrl: String?,
    val filename: String,
    val formatLabel: String,
    val resolutionLabel: String?,
    val category: DownloadCategory,
    val fileSizeBytes: Long?,
    val status: DownloadStatus,
    val fileUri: String?,
    /** When the job ended (completed, failed or cancelled). */
    val completedAt: Long,
    /** User-facing reason for FAILED entries. */
    val errorMessage: String? = null,
    /** Human-readable folder the file was saved to (e.g. "Movies/ENAGELYUCA"). */
    val location: String? = null,
)
