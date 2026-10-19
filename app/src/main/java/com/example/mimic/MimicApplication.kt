package com.example.mimic

import android.app.Application
import com.example.mimic.data.DownloadRepository
import com.example.mimic.data.FavoritesRepository
import com.example.mimic.data.MusicRepository
import com.example.mimic.data.SettingsRepository
import com.example.mimic.data.db.MimicDatabase
import com.example.mimic.data.server.ServerCredentialsRepository

/**
 * Simple manual dependency container. Avoids an annotation-processing DI framework
 * (fewer moving parts on this bleeding-edge AGP) while keeping singletons testable:
 * tests can construct [AppContainer] with an in-memory database.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class]) // CastController (4.1)
class AppContainer(application: Application) {
    val database: MimicDatabase = MimicDatabase.get(application)
    val musicRepository: MusicRepository = MusicRepository(application)
    val settingsRepository: SettingsRepository = SettingsRepository(application)
    val serverCredentialsRepository: ServerCredentialsRepository = ServerCredentialsRepository(application)
    /** Last play queue + position (MImic 3.2), written by the player service, restored by the UI. */
    val playbackStateStore: com.example.mimic.data.PlaybackStateStore = com.example.mimic.data.PlaybackStateStore(application)

    val favoriteDao get() = database.favoriteDao()
    val historyDao get() = database.historyDao()
    val playlistDao get() = database.playlistDao()
    val customThemeDao get() = database.customThemeDao()
    val downloadDao get() = database.downloadDao()
    val favoriteGroupDao get() = database.favoriteGroupDao()
    val pendingStarDao get() = database.pendingStarDao()

    val downloadRepository: DownloadRepository by lazy { DownloadRepository(application, downloadDao) }
    val favoritesRepository: FavoritesRepository by lazy {
        FavoritesRepository(favoriteDao, favoriteGroupDao, pendingStarDao)
    }

    /** Google Cast (4.1): one controller + LAN media server per process. */
    val castController: com.example.mimic.cast.CastController by lazy { com.example.mimic.cast.CastController(application) }

    /** GitHub Releases updater (Settings → App Updates, MImic 4.0). */
    val updateManager: com.example.mimic.update.UpdateManager by lazy {
        com.example.mimic.update.UpdateManager(
            application,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
        )
    }
}

class MimicApplication : Application(), coil.ImageLoaderFactory {
    val container: AppContainer by lazy { AppContainer(this) }

    /** One app-wide image loader that also understands per-track artwork keys (mimic-art://, 4.1). */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .components { add(com.example.mimic.data.art.TrackArtFetcher.Factory(this@MimicApplication)) }
        .build()

    override fun onCreate() {
        super.onCreate()
        // 4.1 removed Google Drive / OneDrive: stop the old periodic cloud refresh if it was scheduled.
        androidx.work.WorkManager.getInstance(this).cancelUniqueWork("mimic-cloud-sync")
        // Full edition: media downloader (unpacks yt-dlp once, resumes an interrupted queue).
        com.example.mimic.downloader.Downloader.init(this)
        // In-app updates: drop leftover update APKs; daily check (notification only) if enabled.
        container.updateManager.cleanUpOldDownloads()
        com.example.mimic.update.UpdateCheckWorker.schedule(this, container.updateManager.autoCheckEnabled)
    }
}
