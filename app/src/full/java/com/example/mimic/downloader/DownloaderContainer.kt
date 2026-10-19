package com.example.mimic.downloader

import android.content.Context
import android.util.Log
import com.example.mimic.downloader.core.NetworkMonitor
import com.example.mimic.downloader.data.analyzer.MediaAnalyzer
import com.example.mimic.downloader.data.local.datastore.SettingsDataStore
import com.example.mimic.downloader.data.local.db.DownloadDao
import com.example.mimic.downloader.data.local.db.DownloaderDatabase
import com.example.mimic.downloader.data.local.db.HistoryDao
import com.example.mimic.downloader.data.provider.MediaProvider
import com.example.mimic.downloader.data.provider.ProviderException
import com.example.mimic.downloader.data.provider.StoragePublisher
import com.example.mimic.downloader.data.provider.YtDlpMediaProvider
import com.example.mimic.downloader.data.provider.YtDlpUpdater
import com.example.mimic.downloader.data.repository.DownloadRepository
import com.example.mimic.downloader.data.repository.HistoryRepository
import com.example.mimic.downloader.download.CompletedDownload
import com.example.mimic.downloader.download.DownloadCompletionListener
import com.example.mimic.downloader.download.DownloadEngine
import com.example.mimic.downloader.download.DownloadNotifications
import com.example.mimic.downloader.download.DownloadService
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * The downloader's object graph (ported from ENAGELYUCA's AppContainer, minus its own player,
 * library and radio — MImic's player and library are used instead). One per process, reachable
 * from the UI, the download service and the broadcast receivers alike, so the engine keeps
 * running independently of any screen.
 */
class DownloaderContainer private constructor(private val appContext: Context) {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Completed once the bundled yt-dlp/python/ffmpeg binaries are unpacked and initialized. */
    val engineReady = CompletableDeferred<Unit>()

    private val database: DownloaderDatabase by lazy { DownloaderDatabase.build(appContext) }
    val downloadDao: DownloadDao by lazy { database.downloadDao() }
    val historyDao: HistoryDao by lazy { database.historyDao() }

    val settingsDataStore: SettingsDataStore by lazy { SettingsDataStore(appContext) }
    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(appContext) }

    private val ytDlpProvider: YtDlpMediaProvider by lazy {
        YtDlpMediaProvider(networkMonitor) {
            try {
                engineReady.await()
            } catch (e: Exception) {
                throw ProviderException.EngineUnavailable(e)
            }
            // Never run yt-dlp while its self-update is rewriting it (race seen on first launch).
            ytDlpUpdater.awaitNotUpdating()
        }
    }
    val mediaProvider: MediaProvider get() = ytDlpProvider

    /** yt-dlp self-updates: throttled, never while a download/analysis uses the binary. */
    val ytDlpUpdater: YtDlpUpdater by lazy {
        YtDlpUpdater(
            context = appContext,
            isBusy = { downloadEngine.activeCount.value > 0 || ytDlpProvider.isAnalyzing },
            isOnline = { networkMonitor.isOnline() },
        )
    }
    private val storagePublisher: StoragePublisher by lazy { StoragePublisher(appContext) }
    val downloadNotifications: DownloadNotifications by lazy { DownloadNotifications(appContext) }

    private val _completed = MutableSharedFlow<CompletedDownload>(extraBufferCapacity = 16)

    /** Every published file — MImic rescans its library so new music shows up right away. */
    val completed: SharedFlow<CompletedDownload> = _completed.asSharedFlow()

    /** Created (and its queue watcher started) on first access. */
    val downloadEngine: DownloadEngine by lazy {
        DownloadEngine(
            context = appContext,
            downloadDao = downloadDao,
            historyDao = historyDao,
            mediaProvider = mediaProvider,
            storagePublisher = storagePublisher,
            settingsDataStore = settingsDataStore,
            notifications = downloadNotifications,
            networkMonitor = networkMonitor,
            scope = applicationScope,
            requestForeground = { DownloadService.start(appContext) },
            completionListener = DownloadCompletionListener { _completed.emit(it) },
        ).also { it.start() }
    }

    val analyzer: MediaAnalyzer by lazy { MediaAnalyzer(mediaProvider) }
    val downloadRepository: DownloadRepository by lazy { DownloadRepository(downloadDao, downloadEngine) }
    val historyRepository: HistoryRepository by lazy { HistoryRepository(appContext, historyDao) }

    /** Unpacks the bundled runtimes (first run only), then keeps yt-dlp current. Off the main thread. */
    private fun initEngine() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.init(appContext)
                FFmpeg.init(appContext)
                engineReady.complete(Unit)
            } catch (e: Throwable) {
                // Throwable: a broken runtime unpack surfaces as an Error and must become a message.
                Log.e(TAG, "Failed to initialize the download engine's native binaries", e)
                engineReady.completeExceptionally(e)
                return@launch
            }
            // Resume a queue left over from before the app was killed.
            downloadEngine
            // yt-dlp ships pinned; YouTube changes often. Check at most once a day, a little
            // after start-up, never while something is downloading. Failures keep the current one.
            delay(15_000)
            runCatching { ytDlpUpdater.autoUpdateWhenIdle() }
                .onFailure { Log.w(TAG, "yt-dlp update check failed; keeping the current version", it) }
        }
    }

    companion object {
        private const val TAG = "MImicDownloader"

        @Volatile private var instance: DownloaderContainer? = null

        fun get(context: Context): DownloaderContainer = instance ?: synchronized(this) {
            instance ?: DownloaderContainer(context.applicationContext).also {
                instance = it
                it.downloadNotifications.ensureChannels()
                it.initEngine()
            }
        }
    }
}
