package com.example.mimic.downloader.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.mimic.downloader.DownloaderContainer
import com.example.mimic.downloader.data.model.DownloadStatus
import kotlinx.coroutines.launch

/**
 * After a reboot, tells the user unfinished downloads are waiting. It deliberately does NOT
 * start [DownloadService]: Android 15+ forbids starting a dataSync foreground service from
 * BOOT_COMPLETED (ForegroundServiceStartNotAllowedException). Tapping the notification opens
 * the app, which resumes the queue normally.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        val container = DownloaderContainer.get(context)
        container.applicationScope.launch {
            try {
                val pending = container.downloadDao.getByStatuses(
                    listOf(DownloadStatus.QUEUED) + DownloadStatus.ACTIVE
                ).size
                val settings = container.settingsDataStore.settingsSnapshot()
                if (pending > 0 && settings.notificationsEnabled) {
                    container.downloadNotifications.ensureChannels()
                    container.downloadNotifications.notify(
                        DownloadNotifications.RESUME_AFTER_BOOT_NOTIFICATION_ID,
                        container.downloadNotifications.resumeAfterBootNotification(pending),
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
