package com.example.mimic.downloader.download

import com.example.mimic.downloader.core.Formatting
import com.example.mimic.downloader.data.local.db.DownloadEntity
import com.example.mimic.downloader.data.model.DownloadStatus
import com.example.mimic.downloader.data.model.FormatKind

/**
 * One rule for how a job's progress is shown — Downloads list, Home, mini bar and the
 * notification all use it, so they can never disagree. A percentage is shown only when yt-dlp
 * reported the total size; otherwise the bar is indeterminate and the text says how much has
 * arrived so far.
 */
object ProgressDisplay {

    /** 0..1 for a determinate bar, or null for an indeterminate one. */
    fun fraction(entity: DownloadEntity): Float? = when {
        entity.status == DownloadStatus.PAUSED -> (entity.progressPercent / 100f).coerceIn(0f, 1f)
        entity.status != DownloadStatus.DOWNLOADING -> null
        entity.errorMessage != null -> null // waiting to retry
        entity.totalBytes == null || entity.totalBytes <= 0 -> null
        else -> (entity.progressPercent / 100f).coerceIn(0f, 1f)
    }

    /** "Downloading 47% · 24.6 MB / 52.1 MB · 1.2 MB/s · 30s remaining", "Downloading… 24.6 MB", "Processing…" */
    fun text(entity: DownloadEntity): String = when (entity.status) {
        DownloadStatus.FETCHING_INFO -> "Fetching info…"
        DownloadStatus.PROCESSING -> if (entity.formatKind == FormatKind.AUDIO && entity.requiresAudioExtraction) {
            "Converting to MP3…"
        } else {
            "Processing…"
        }
        DownloadStatus.DOWNLOADING -> when {
            entity.errorMessage != null -> entity.errorMessage
            fraction(entity) != null -> buildString {
                append("Downloading ")
                append(Formatting.percent(entity.progressPercent))
                append(" · ")
                append(Formatting.bytes(entity.downloadedBytes.coerceAtLeast(1)))
                append(" / ")
                append(Formatting.bytes(entity.totalBytes))
                Formatting.speed(entity.speedBytesPerSec).takeIf { it != "-" }?.let { append(" · ").append(it) }
                Formatting.eta(entity.etaSeconds).takeIf { it != "-" }?.let { append(" · ").append(it) }
            }
            entity.downloadedBytes > 0 -> listOfNotNull(
                "Downloading… ${Formatting.bytes(entity.downloadedBytes)}",
                Formatting.speed(entity.speedBytesPerSec).takeIf { it != "-" },
            ).joinToString(" · ")
            else -> "Downloading…"
        }
        DownloadStatus.PAUSED -> if (entity.progressPercent > 0f) "Paused at ${Formatting.percent(entity.progressPercent)}" else "Paused"
        DownloadStatus.QUEUED -> "Queued"
        DownloadStatus.COMPLETED -> "Completed"
        DownloadStatus.FAILED -> entity.errorMessage ?: "Download failed. You can retry this item."
        DownloadStatus.CANCELLED -> "Cancelled"
    }

    /** Short form for tight spaces (mini bar): "47%" or "24.6 MB" or "Processing…". */
    fun short(entity: DownloadEntity): String = when {
        entity.status == DownloadStatus.DOWNLOADING && fraction(entity) != null -> Formatting.percent(entity.progressPercent)
        entity.status == DownloadStatus.DOWNLOADING && entity.downloadedBytes > 0 -> Formatting.bytes(entity.downloadedBytes)
        else -> text(entity)
    }
}
