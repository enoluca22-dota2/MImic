package com.example.mimic.downloader.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.mimic.downloader.DownloaderContainer
import kotlinx.coroutines.launch

/**
 * Handles Pause/Resume/Retry/Cancel notification action taps by calling the engine directly.
 * Resuming work re-promotes [DownloadService] through the engine; the notification tap gives the
 * app a short window in which starting a foreground service from the background is allowed.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val downloadId = intent.getLongExtra(DownloadIntentActions.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId < 0) return
        val container = DownloaderContainer.get(context)
        val engine = container.downloadEngine
        val pendingResult = goAsync()
        container.applicationScope.launch {
            try {
                when (action) {
                    DownloadIntentActions.ACTION_PAUSE -> engine.pause(downloadId)
                    DownloadIntentActions.ACTION_CANCEL -> engine.cancel(downloadId)
                    DownloadIntentActions.ACTION_RESUME -> engine.resume(downloadId)
                    DownloadIntentActions.ACTION_RETRY -> {
                        container.downloadNotifications.cancel(DownloadNotifications.completedNotificationIdFor(downloadId))
                        engine.retry(downloadId)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
