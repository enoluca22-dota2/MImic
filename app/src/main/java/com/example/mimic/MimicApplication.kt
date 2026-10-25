package com.example.mimic

import android.app.Application
import com.example.mimic.data.DownloadRepository
import com.example.mimic.data.FavoritesRepository
import com.example.mimic.data.MusicRepository
import com.example.mimic.data.SettingsRepository
import com.example.mimic.data.db.MimicDatabase
import com.example.mimic.data.server.ServerCredentialsRepository
import kotlinx.coroutines.flow.first

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
    /** Online (YouTube Music) track metadata, 4.4. */
    val onlineTracks: com.example.mimic.data.online.OnlineTrackStore by lazy {
        com.example.mimic.data.online.OnlineTrackStore(database.onlineTrackDao())
    }

    val downloadRepository: DownloadRepository by lazy { DownloadRepository(application, downloadDao) }
    val favoritesRepository: FavoritesRepository by lazy {
        FavoritesRepository(favoriteDao, favoriteGroupDao, pendingStarDao)
    }

    /** Internet radio (4.2): bundled catalog + Radio Browser + saved stations + chosen countries. */
    val radioRepository: com.example.mimic.data.radio.RadioRepository by lazy {
        com.example.mimic.data.radio.RadioRepository(application, database.radioDao(), com.example.mimic.data.radio.RadioCountryStore(application))
    }

    /** Google Cast (4.1): one controller + LAN media server per process. */
    /** Backup & Restore to a user-picked file (4.3). */
    val backupManager: com.example.mimic.data.backup.BackupManager by lazy {
        com.example.mimic.data.backup.BackupManager(
            application, database, settingsRepository, radioRepository.countries, serverCredentialsRepository,
            com.example.mimic.BuildConfig.VERSION_NAME,
        )
    }

    /** Optional MImic Google account + sync between phones (4.4). */
    val mimicAccount: com.example.mimic.data.account.MimicAccount by lazy { com.example.mimic.data.account.MimicAccount(application) }
    val accountSync: com.example.mimic.data.account.AccountSync by lazy {
        com.example.mimic.data.account.AccountSync(application, mimicAccount, backupManager, onlineTracks)
    }

    /** Lyrics from every source, saved for offline use (4.5). */
    val lyricsRepository: com.example.mimic.data.lyrics.LyricsRepository by lazy {
        com.example.mimic.data.lyrics.LyricsRepository(application, database.lyricsDao()) {
            val creds = serverCredentialsRepository.credentials.first()
            if (creds == null || !serverCredentialsRepository.connected.first()) null
            else lyricsServerClient?.takeIf { it.first == creds }?.second
                ?: com.example.mimic.data.server.SubsonicClient(creds).also { lyricsServerClient = creds to it }
        }
    }
    @Volatile private var lyricsServerClient: Pair<com.example.mimic.data.server.ServerCredentials, com.example.mimic.data.server.SubsonicClient>? = null

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
        // Full edition: online music (stream resolver warm-up, YouTube Music session if connected).
        com.example.mimic.online.OnlineMusic.init(this)
        // In-app updates: drop leftover update APKs; daily check (notification only) if enabled.
        container.updateManager.cleanUpOldDownloads()
        com.example.mimic.update.UpdateCheckWorker.schedule(this, container.updateManager.autoCheckEnabled)
    }
}
