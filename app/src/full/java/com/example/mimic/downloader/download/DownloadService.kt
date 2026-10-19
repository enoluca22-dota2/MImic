package com.example.mimic.downloader.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.mimic.downloader.DownloaderContainer
import com.example.mimic.downloader.data.local.db.DownloadDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service (type dataSync) that keeps the process alive while downloads run. It owns no
 * download logic: the [DownloadEngine] does the work, and the queue itself lives in Room, so a
 * killed process picks up where it left off on the next launch.
 *
 * A foreground service is the right tool here rather than WorkManager: transfers are
 * user-initiated, long, need live progress + pause/cancel, and are driven by an external process
 * (yt-dlp) that WorkManager's 10-minute execution window would cut off.
 */
class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var notifications: DownloadNotifications
    private lateinit var engine: DownloadEngine
    private lateinit var downloadDao: DownloadDao
    private var stopWatchJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val container = DownloaderContainer.get(application)
        notifications = container.downloadNotifications
        notifications.ensureChannels()
        engine = container.downloadEngine
        downloadDao = container.downloadDao
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        engine.start()
        watchActiveCount()
        return START_STICKY
    }

    private fun promoteToForeground() {
        val notification = notifications.summaryNotification(
            if (engine.activeCount.value == 0) "Preparing downloads…" else "Downloading",
        )
        ServiceCompat.startForeground(
            this,
            DownloadNotifications.SUMMARY_NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private fun watchActiveCount() {
        if (stopWatchJob != null) return
        // The one foreground notification mirrors the queue (the jobs' state is the source of truth).
        serviceScope.launch {
            downloadDao.observeAll()
                .map { DownloadNotifications.summaryText(it) }
                .distinctUntilChanged()
                .collect { notifications.notify(DownloadNotifications.SUMMARY_NOTIFICATION_ID, notifications.summaryNotification(it)) }
        }
        stopWatchJob = serviceScope.launch {
            engine.activeCount.collect { count ->
                if (count == 0) {
                    // Give the queue a moment to hand out the next job before deciding we're done.
                    delay(1500)
                    if (!engine.hasRunnableWork()) stopSelfCleanly()
                }
            }
        }
    }

    /** Android 15+ caps dataSync services at 6h per day; stop gracefully instead of crashing. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "dataSync foreground time limit reached; stopping service")
        stopSelfCleanly()
    }

    private fun stopSelfCleanly() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DownloadService"

        /**
         * Starts (or re-promotes) the service. Android 12+ refuses to start a foreground service
         * while the app is in the background; in that case the download still runs as long as
         * the process lives, and the service is started again the next time the app is opened.
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Couldn't start the download service from the background", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "Couldn't start the download service", e)
            }
        }
    }
}
