package com.example.mimic

import android.app.Application
import com.example.mimic.data.DownloadRepository
import com.example.mimic.data.FavoritesRepository
import com.example.mimic.data.MusicRepository
import com.example.mimic.data.SettingsRepository
import com.example.mimic.data.cloud.CloudLibraryRepository
import com.example.mimic.data.sync.CloudLibrarySyncWorker
import com.example.mimic.data.db.MimicDatabase
import com.example.mimic.data.server.ServerCredentialsRepository

/**
 * Simple manual dependency container. Avoids an annotation-processing DI framework
 * (fewer moving parts on this bleeding-edge AGP) while keeping singletons testable:
 * tests can construct [AppContainer] with an in-memory database.
 */
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

    /** Google Drive + OneDrive (MImic 3.1): shared by the UI, the player service and the sync worker. */
    val cloudLibrary: CloudLibraryRepository by lazy {
        CloudLibraryRepository(application, database.cloudDao(), downloadRepository, settingsRepository)
    }
}

class MimicApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Periodic background refresh of cloud libraries (survives reboots via WorkManager).
        CloudLibrarySyncWorker.schedule(this, enabled = true)
    }
}
