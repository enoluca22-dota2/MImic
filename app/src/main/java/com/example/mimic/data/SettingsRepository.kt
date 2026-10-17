package com.example.mimic.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Available visual themes. GLASS/AMOLED are dark variants with different surfaces. */
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED, GLASS }

/** Preset accent colors (stored by name so new presets stay forward-compatible). */
enum class AccentColor { GREEN, BLUE, PURPLE, ORANGE, PINK, RED }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: AccentColor = AccentColor.GREEN,
    val sleepTimerMinutes: Int = 0,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Lightweight user preferences backed by DataStore. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val ACCENT = stringPreferencesKey("accent_color")
        val SLEEP = intPreferencesKey("sleep_timer_minutes")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            accent = prefs[Keys.ACCENT]?.let { runCatching { AccentColor.valueOf(it) }.getOrNull() }
                ?: AccentColor.GREEN,
            sleepTimerMinutes = prefs[Keys.SLEEP] ?: 0,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME] = mode.name }
    }

    suspend fun setAccent(accent: AccentColor) {
        context.dataStore.edit { it[Keys.ACCENT] = accent.name }
    }

    suspend fun setSleepTimerMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.SLEEP] = minutes }
    }
}
