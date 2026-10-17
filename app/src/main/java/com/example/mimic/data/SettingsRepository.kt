package com.example.mimic.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.mimic.ui.designsystem.ThemeRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    val themeId: String = ThemeRegistry.DEFAULT_ID,
    val sleepTimerMinutes: Int = 0,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Lightweight user preferences backed by DataStore. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_ID = stringPreferencesKey("theme_id")
        val SLEEP = intPreferencesKey("sleep_timer_minutes")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeId = prefs[Keys.THEME_ID] ?: ThemeRegistry.DEFAULT_ID,
            sleepTimerMinutes = prefs[Keys.SLEEP] ?: 0,
        )
    }

    suspend fun setThemeId(id: String) {
        context.dataStore.edit { it[Keys.THEME_ID] = id }
    }

    suspend fun setSleepTimerMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.SLEEP] = minutes }
    }
}
