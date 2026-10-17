package com.example.mimic

import android.app.Application
import com.example.mimic.data.MusicRepository
import com.example.mimic.data.SettingsRepository
import com.example.mimic.data.db.MimicDatabase

/**
 * Simple manual dependency container. Avoids an annotation-processing DI framework
 * (fewer moving parts on this bleeding-edge AGP) while keeping singletons testable:
 * tests can construct [AppContainer] with an in-memory database.
 */
class AppContainer(application: Application) {
    val database: MimicDatabase = MimicDatabase.get(application)
    val musicRepository: MusicRepository = MusicRepository(application)
    val settingsRepository: SettingsRepository = SettingsRepository(application)

    val favoriteDao get() = database.favoriteDao()
    val historyDao get() = database.historyDao()
    val playlistDao get() = database.playlistDao()
    val customThemeDao get() = database.customThemeDao()
}

class MimicApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
