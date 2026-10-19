package com.example.mimic.downloader.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.mimic.MainActivity
import com.example.mimic.R
import com.example.mimic.downloader.core.Formatting
import com.example.mimic.downloader.data.local.db.DownloadEntity
import com.example.mimic.downloader.data.model.DownloadStatus

object DownloadIntentActions {
    const val ACTION_PAUSE = "com.example.mimic.downloader.action.PAUSE"
    const val ACTION_RESUME = "com.example.mimic.downloader.action.RESUME"
    const val ACTION_RETRY = "com.example.mimic.downloader.action.RETRY"
    const val ACTION_CANCEL = "com.example.mimic.downloader.action.CANCEL"
    const val EXTRA_DOWNLOAD_ID = "download_id"
}

/**
 * Builds the notification channels and per-download notifications. Per-download progress
 * notifications are grouped under the foreground-service summary so several parallel downloads
 * collapse into one entry instead of flooding the shade.
 */
class DownloadNotifications(private val context: Context) {

    companion object {
        const val CHANNEL_PROGRESS = "downloads_progress"
        const val CHANNEL_STATUS = "downloads_status"
        const val SUMMARY_NOTIFICATION_ID = 1
        const val RESUME_AFTER_BOOT_NOTIFICATION_ID = 2
        private const val GROUP_ACTIVE = "com.example.mimic.downloader.ACTIVE_DOWNLOADS"
        fun notificationIdFor(downloadId: Long): Int = (2_000_000 + downloadId).toInt()
        fun completedNotificationIdFor(downloadId: Long): Int = (4_000_000 + downloadId).toInt()

        /** "Downloading" for a single item, "Downloading playlist 4 / 18" for a playlist item. */
        fun progressTitle(entity: DownloadEntity): String {
            val index = entity.batchIndex
            val size = entity.batchSize
            return if (entity.batchId != null && index != null && size != null && size > 1) {
                "Downloading playlist $index / $size"
            } else {
                "Downloading"
            }
        }

        /**
         * Text of the foreground summary. [rows] is the whole queue; when everything running
         * belongs to one playlist it reads "Downloading playlist 4 / 18" (4 = items finished + 1).
         */
        fun summaryText(rows: List<DownloadEntity>): String {
            val active = rows.filter { it.status.isActive }
            if (active.isEmpty()) return "Preparing downloads…"
            val batchIds = active.map { it.batchId }.distinct()
            val batchId = batchIds.singleOrNull()
            if (batchId != null) {
                val batch = rows.filter { it.batchId == batchId }
                val total = active.first().batchSize ?: batch.size
                val done = batch.count { it.status.isTerminal }
                if (total > 1) return "Downloading playlist ${(done + 1).coerceAtMost(total)} / $total"
            }
            return if (active.size == 1) active.first().title else "Downloading ${active.size} items"
        }
    }

    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val progress = NotificationChannel(
            CHANNEL_PROGRESS,
            context.getString(R.string.notification_channel_downloads_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.notification_channel_downloads_desc) }
        val status = NotificationChannel(
            CHANNEL_STATUS,
            context.getString(R.string.notification_channel_status_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.notification_channel_status_desc) }
        manager?.createNotificationChannel(progress)
        manager?.createNotificationChannel(status)
    }

    fun summaryNotification(text: String) = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setGroup(GROUP_ACTIVE)
        .setGroupSummary(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(openAppPendingIntent())
        .build()

    /** Downloading / <title> / 42% · 1.2 MB/s · 0:31 left */
    fun progressNotification(entity: DownloadEntity) = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(progressTitle(entity))
        .setContentText(entity.title)
        // Same text/progress rule as the in-app list (ProgressDisplay): no percentage without a real total.
        // The title already says "Downloading": "47% · 24.6 MB / 52.1 MB · 1.2 MB/s".
        .setSubText(ProgressDisplay.text(entity).removePrefix("Downloading "))
        .setProgress(100, entity.progressPercent.toInt().coerceIn(0, 100), ProgressDisplay.fraction(entity) == null)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setGroup(GROUP_ACTIVE)
        .setContentIntent(openAppPendingIntent())
        .addAction(0, context.getString(R.string.action_pause), actionPendingIntent(DownloadIntentActions.ACTION_PAUSE, entity.id))
        .addAction(0, context.getString(R.string.action_cancel), actionPendingIntent(DownloadIntentActions.ACTION_CANCEL, entity.id))
        .build()

    /** PROCESSING: merging / converting / saving. Can still be cancelled. */
    fun processingNotification(entity: DownloadEntity, what: String) = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(progressTitle(entity))
        .setContentText(entity.title)
        .setSubText(what)
        .addAction(0, context.getString(R.string.action_cancel), actionPendingIntent(DownloadIntentActions.ACTION_CANCEL, entity.id))
        .setProgress(0, 0, true)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setGroup(GROUP_ACTIVE)
        .setContentIntent(openAppPendingIntent())
        .build()

    fun pausedNotification(entity: DownloadEntity) = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
        .setSmallIcon(android.R.drawable.ic_media_pause)
        .setContentTitle(entity.title)
        .setContentText("Paused at ${Formatting.percent(entity.progressPercent)}")
        .setOngoing(false)
        .setSilent(true)
        .setContentIntent(openAppPendingIntent())
        .addAction(0, context.getString(R.string.action_resume), actionPendingIntent(DownloadIntentActions.ACTION_RESUME, entity.id))
        .addAction(0, context.getString(R.string.action_cancel), actionPendingIntent(DownloadIntentActions.ACTION_CANCEL, entity.id))
        .build()

    fun completedNotification(entity: DownloadEntity, fileUri: String?, mimeType: String?) =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download complete")
            .setContentText("${entity.fileBaseName}.${entity.container ?: ""}".trimEnd('.'))
            .setAutoCancel(true)
            .setContentIntent(if (fileUri != null) openFilePendingIntent(entity.id, fileUri, mimeType) else openAppPendingIntent())
            .apply {
                if (fileUri != null) {
                    addAction(0, context.getString(R.string.action_open), openFilePendingIntent(entity.id, fileUri, mimeType))
                }
            }
            .build()

    fun failedNotification(entity: DownloadEntity) = NotificationCompat.Builder(context, CHANNEL_STATUS)
        .setSmallIcon(android.R.drawable.stat_notify_error)
        .setContentTitle("Download failed")
        .setContentText("${entity.title} · Tap to retry")
        .setStyle(NotificationCompat.BigTextStyle().bigText("${entity.title}\n${entity.errorMessage ?: "Unknown error"}\nTap to retry."))
        .setAutoCancel(true)
        // Tapping the notification retries the download directly.
        .setContentIntent(actionPendingIntent(DownloadIntentActions.ACTION_RETRY, entity.id))
        .addAction(0, context.getString(R.string.action_retry), actionPendingIntent(DownloadIntentActions.ACTION_RETRY, entity.id))
        .build()

    fun resumeAfterBootNotification(pendingCount: Int) = NotificationCompat.Builder(context, CHANNEL_STATUS)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(if (pendingCount == 1) "1 download is waiting" else "$pendingCount downloads are waiting")
        .setContentText("Tap to continue downloading")
        .setAutoCancel(true)
        .setContentIntent(openAppPendingIntent())
        .build()

    fun notify(id: Int, notification: android.app.Notification) {
        // Without POST_NOTIFICATIONS (Android 13+) notify() is silently dropped; that's fine.
        runCatching { manager?.notify(id, notification) }
    }

    fun cancel(id: Int) {
        manager?.cancel(id)
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openFilePendingIntent(downloadId: Long, fileUri: String, mimeType: String?): PendingIntent {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(fileUri), mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // A chooser never throws ActivityNotFoundException; it shows "No apps can perform this action" instead.
        val chooser = Intent.createChooser(view, null).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return PendingIntent.getActivity(
            context, downloadId.toInt(), chooser,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionPendingIntent(action: String, downloadId: Long): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(DownloadIntentActions.EXTRA_DOWNLOAD_ID, downloadId)
        }
        return PendingIntent.getBroadcast(
            context, (action + downloadId).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
