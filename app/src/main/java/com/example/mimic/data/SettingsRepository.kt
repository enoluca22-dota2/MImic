package com.example.mimic.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
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
    val playerLayout: PlayerLayoutConfig = PlayerLayoutConfig(),
    val homeConfig: HomeConfig = HomeConfig(),
    val glassQuality: GlassQuality = GlassQuality.BALANCED,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Helper: decode an enum by name with a safe fallback. */
private inline fun <reified T : Enum<T>> decode(value: String?, fallback: T): T =
    value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

/** Lightweight user preferences backed by DataStore. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_ID = stringPreferencesKey("theme_id")
        val SLEEP = intPreferencesKey("sleep_timer_minutes")
        val LAYOUT = stringPreferencesKey("player_layout")
        val ART_SHAPE = stringPreferencesKey("artwork_shape")
        val ART_SIZE = stringPreferencesKey("artwork_size")
        val SHOW_SHUFFLE_REPEAT = booleanPreferencesKey("show_shuffle_repeat")
        val SHOW_SECONDARY = booleanPreferencesKey("show_secondary_actions")
        val HOME_ORDER = stringPreferencesKey("home_section_order")
        val HOME_HIDDEN = stringPreferencesKey("home_hidden")
        val NAV_STYLE = stringPreferencesKey("nav_style")
        val PLAYER_BG = stringPreferencesKey("player_background")
        val CONTROL_STYLE = stringPreferencesKey("player_control_style")
        val GLASS_QUALITY = stringPreferencesKey("glass_quality")
    }

    private fun decodeSections(csv: String?): List<HomeSection> =
        csv?.split(",")?.mapNotNull { runCatching { HomeSection.valueOf(it) }.getOrNull() } ?: emptyList()

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeId = prefs[Keys.THEME_ID] ?: ThemeRegistry.DEFAULT_ID,
            sleepTimerMinutes = prefs[Keys.SLEEP] ?: 0,
            playerLayout = PlayerLayoutConfig(
                layout = decode(prefs[Keys.LAYOUT], PlayerLayout.CLASSIC),
                artworkShape = decode(prefs[Keys.ART_SHAPE], ArtworkShape.ROUNDED),
                artworkSize = decode(prefs[Keys.ART_SIZE], ArtworkSize.LARGE),
                showShuffleRepeat = prefs[Keys.SHOW_SHUFFLE_REPEAT] ?: true,
                showSecondaryActions = prefs[Keys.SHOW_SECONDARY] ?: true,
                background = decode(prefs[Keys.PLAYER_BG], PlayerBackground.AUTO),
                controlStyle = decode(prefs[Keys.CONTROL_STYLE], ControlStyle.AUTO),
            ),
            homeConfig = HomeConfig(
                sectionOrder = decodeSections(prefs[Keys.HOME_ORDER]).ifEmpty { HomeSection.entries },
                hidden = decodeSections(prefs[Keys.HOME_HIDDEN]).toSet(),
                navStyle = decode(prefs[Keys.NAV_STYLE], NavStyle.AUTO),
            ),
            glassQuality = decode(prefs[Keys.GLASS_QUALITY], GlassQuality.safeDefault()),
        )
    }

    suspend fun setThemeId(id: String) {
        context.dataStore.edit { it[Keys.THEME_ID] = id }
    }

    suspend fun setSleepTimerMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.SLEEP] = minutes }
    }

    suspend fun setPlayerLayout(config: PlayerLayoutConfig) {
        context.dataStore.edit {
            it[Keys.LAYOUT] = config.layout.name
            it[Keys.ART_SHAPE] = config.artworkShape.name
            it[Keys.ART_SIZE] = config.artworkSize.name
            it[Keys.SHOW_SHUFFLE_REPEAT] = config.showShuffleRepeat
            it[Keys.SHOW_SECONDARY] = config.showSecondaryActions
            it[Keys.PLAYER_BG] = config.background.name
            it[Keys.CONTROL_STYLE] = config.controlStyle.name
        }
    }

    suspend fun setHomeConfig(config: HomeConfig) {
        context.dataStore.edit {
            it[Keys.HOME_ORDER] = config.sectionOrder.joinToString(",") { s -> s.name }
            it[Keys.HOME_HIDDEN] = config.hidden.joinToString(",") { s -> s.name }
            it[Keys.NAV_STYLE] = config.navStyle.name
        }
    }

    suspend fun setGlassQuality(quality: GlassQuality) {
        context.dataStore.edit { it[Keys.GLASS_QUALITY] = quality.name }
    }
}
