package com.example.mimic.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.mimic.ui.designsystem.ThemeRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    val themeId: String = ThemeRegistry.DEFAULT_ID,
    val sleepTimerMinutes: Int = 0,
    val playerLayout: PlayerLayoutConfig = PlayerLayoutConfig(),
    val dynamicPlayer: DynamicPlayerConfig = DynamicPlayerConfig(),
    val homeConfig: HomeConfig = HomeConfig(),
    val glassQuality: GlassQuality = GlassQuality.BALANCED,
    /** Library tabs / views / sorting (MImic 3.2). */
    val libraryConfig: LibraryConfig = LibraryConfig(),
    /** Recent searches, newest first (MImic 3.2). Stays on the phone. */
    val recentSearches: List<String> = emptyList(),
    /** Playback speed, crossfade, EQ, ReplayGain ... (MImic 3.2). */
    val audio: AudioPrefs = AudioPrefs(),
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
        // Dynamic Now Playing (4.1)
        val DYN_GRADIENT = booleanPreferencesKey("dyn_gradient")
        val DYN_BLUR = booleanPreferencesKey("dyn_blur")
        val DYN_ANIMATED = booleanPreferencesKey("dyn_animated")
        val DYN_INTENSITY = stringPreferencesKey("dyn_intensity")
        val DYN_SHAPE = stringPreferencesKey("dyn_shape")
        val DYN_GLASS = booleanPreferencesKey("dyn_glass")
        val DYN_SIZE = stringPreferencesKey("dyn_size")
        val DYN_BRIGHTNESS = floatPreferencesKey("dyn_brightness")
        val DYN_CONTRAST = booleanPreferencesKey("dyn_contrast")
        val DYN_REDUCED = booleanPreferencesKey("dyn_reduced_motion")
        val LOCK_STYLE = stringPreferencesKey("lock_style")
        val CONTROL_STYLE = stringPreferencesKey("player_control_style")
        val GLASS_QUALITY = stringPreferencesKey("glass_quality")
        val RECENT_SEARCHES = stringPreferencesKey("recent_searches")
        val SPEED = floatPreferencesKey("audio_speed")
        val KEEP_PITCH = booleanPreferencesKey("audio_keep_pitch")
        val CROSSFADE = intPreferencesKey("audio_crossfade_sec")
        val SKIP_SILENCE = booleanPreferencesKey("audio_skip_silence")
        val REPLAY_GAIN = stringPreferencesKey("audio_replay_gain")
        val EQ_ON = booleanPreferencesKey("audio_eq_enabled")
        val EQ_BANDS = stringPreferencesKey("audio_eq_bands")
        val BASS = intPreferencesKey("audio_bass_boost")
        val HAPTIC_SEEK = booleanPreferencesKey("audio_haptic_seek")
        val RESUME_ON_CONNECT = booleanPreferencesKey("audio_resume_on_connect")
        val LIB_ORDER = stringPreferencesKey("library_tab_order")
        val LIB_HIDDEN = stringPreferencesKey("library_hidden_tabs")
        val LIB_ALBUMS_GRID = booleanPreferencesKey("library_albums_grid")
        val LIB_ARTISTS_GRID = booleanPreferencesKey("library_artists_grid")
        val LIB_SONG_SORT = stringPreferencesKey("library_song_sort")
        val LIB_SONGS_REVERSED = booleanPreferencesKey("library_songs_reversed")
        val LIB_GROUP_SORT = stringPreferencesKey("library_group_sort")
        val LIB_GROUPS_REVERSED = booleanPreferencesKey("library_groups_reversed")
        // Library scanning (4.3)
        val SCAN_EXCLUDED = androidx.datastore.preferences.core.stringSetPreferencesKey("scan_excluded_folders")
        val SCAN_MIN_SEC = intPreferencesKey("scan_min_duration_sec")
    }

    /** Which phone music is shown (folders, minimum length) — 4.3. */
    val scanConfig: Flow<LibraryScanConfig> = context.dataStore.data.map { p ->
        LibraryScanConfig(
            excludedFolders = p[Keys.SCAN_EXCLUDED].orEmpty(),
            minDurationSec = (p[Keys.SCAN_MIN_SEC] ?: LibraryScanConfig.DEFAULT_MIN_SEC).coerceIn(0, 600),
        )
    }

    suspend fun setScanConfig(c: LibraryScanConfig) {
        context.dataStore.edit {
            it[Keys.SCAN_EXCLUDED] = c.excludedFolders
            it[Keys.SCAN_MIN_SEC] = c.minDurationSec
        }
    }

    /** All app settings as backup entries (4.3). */
    suspend fun backupPrefs() = com.example.mimic.data.backup.PrefsBackup.snapshot(context.dataStore)

    /** Restores backed-up settings over the current ones (keys not in the backup stay). */
    suspend fun restorePrefs(values: Map<String, com.example.mimic.data.backup.BackupPref>) =
        com.example.mimic.data.backup.PrefsBackup.restore(context.dataStore, values)

    private fun decodeTabs(csv: String?): List<LibraryTab> =
        csv?.split(",")?.mapNotNull { runCatching { LibraryTab.valueOf(it) }.getOrNull() } ?: emptyList()

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
            dynamicPlayer = DynamicPlayerConfig(
                gradient = prefs[Keys.DYN_GRADIENT] ?: true,
                artworkBlur = prefs[Keys.DYN_BLUR] ?: true,
                animatedBackground = prefs[Keys.DYN_ANIMATED] ?: true,
                intensity = decode(prefs[Keys.DYN_INTENSITY], DynamicIntensity.BALANCED),
                artShape = decode(prefs[Keys.DYN_SHAPE], DynamicArtShape.EXTRA_ROUNDED),
                glassControls = prefs[Keys.DYN_GLASS] ?: true,
                artSize = decode(prefs[Keys.DYN_SIZE], DynamicArtSize.NORMAL),
                brightness = (prefs[Keys.DYN_BRIGHTNESS] ?: 0f).coerceIn(-0.4f, 0.4f),
                autoContrast = prefs[Keys.DYN_CONTRAST] ?: true,
                reduceMotion = prefs[Keys.DYN_REDUCED] ?: false,
                lockStyle = decode(prefs[Keys.LOCK_STYLE], LockStyle.DYNAMIC),
            ),
            homeConfig = HomeConfig(
                sectionOrder = decodeSections(prefs[Keys.HOME_ORDER]).ifEmpty { HomeSection.entries },
                hidden = decodeSections(prefs[Keys.HOME_HIDDEN]).toSet(),
                navStyle = decode(prefs[Keys.NAV_STYLE], NavStyle.AUTO),
            ),
            glassQuality = decode(prefs[Keys.GLASS_QUALITY], GlassQuality.safeDefault()),
            libraryConfig = LibraryConfig(
                tabOrder = decodeTabs(prefs[Keys.LIB_ORDER]).ifEmpty { LibraryTab.entries },
                hiddenTabs = decodeTabs(prefs[Keys.LIB_HIDDEN]).toSet(),
                albumsGrid = prefs[Keys.LIB_ALBUMS_GRID] ?: true,
                artistsGrid = prefs[Keys.LIB_ARTISTS_GRID] ?: false,
                songSort = decode(prefs[Keys.LIB_SONG_SORT], SongSort.NAME),
                songsReversed = prefs[Keys.LIB_SONGS_REVERSED] ?: false,
                groupSort = decode(prefs[Keys.LIB_GROUP_SORT], GroupSort.NAME),
                groupsReversed = prefs[Keys.LIB_GROUPS_REVERSED] ?: false,
            ),
            audio = AudioPrefs(
                speed = (prefs[Keys.SPEED] ?: 1f).coerceIn(0.5f, 2f),
                keepPitch = prefs[Keys.KEEP_PITCH] ?: true,
                crossfadeSec = (prefs[Keys.CROSSFADE] ?: 0).coerceIn(0, AudioPrefs.MAX_CROSSFADE_SEC),
                skipSilence = prefs[Keys.SKIP_SILENCE] ?: false,
                replayGain = decode(prefs[Keys.REPLAY_GAIN], ReplayGainMode.OFF),
                eqEnabled = prefs[Keys.EQ_ON] ?: false,
                eqBands = AudioPrefs.decodeBands(prefs[Keys.EQ_BANDS]),
                bassBoost = (prefs[Keys.BASS] ?: 0).coerceIn(0, 1000),
                hapticSeek = prefs[Keys.HAPTIC_SEEK] ?: true,
                resumeOnConnect = prefs[Keys.RESUME_ON_CONNECT] ?: false,
            ),
            recentSearches = prefs[Keys.RECENT_SEARCHES]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList(),
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

    suspend fun setDynamicPlayer(c: DynamicPlayerConfig) {
        context.dataStore.edit {
            it[Keys.DYN_GRADIENT] = c.gradient
            it[Keys.DYN_BLUR] = c.artworkBlur
            it[Keys.DYN_ANIMATED] = c.animatedBackground
            it[Keys.DYN_INTENSITY] = c.intensity.name
            it[Keys.DYN_SHAPE] = c.artShape.name
            it[Keys.DYN_GLASS] = c.glassControls
            it[Keys.DYN_SIZE] = c.artSize.name
            it[Keys.DYN_BRIGHTNESS] = c.brightness.coerceIn(-0.4f, 0.4f)
            it[Keys.DYN_CONTRAST] = c.autoContrast
            it[Keys.DYN_REDUCED] = c.reduceMotion
            it[Keys.LOCK_STYLE] = c.lockStyle.name
        }
    }

    suspend fun setHomeConfig(config: HomeConfig) {
        context.dataStore.edit {
            it[Keys.HOME_ORDER] = config.sectionOrder.joinToString(",") { s -> s.name }
            it[Keys.HOME_HIDDEN] = config.hidden.joinToString(",") { s -> s.name }
            it[Keys.NAV_STYLE] = config.navStyle.name
        }
    }

    suspend fun setAudio(a: AudioPrefs) {
        context.dataStore.edit {
            it[Keys.SPEED] = a.speed
            it[Keys.KEEP_PITCH] = a.keepPitch
            it[Keys.CROSSFADE] = a.crossfadeSec
            it[Keys.SKIP_SILENCE] = a.skipSilence
            it[Keys.REPLAY_GAIN] = a.replayGain.name
            it[Keys.EQ_ON] = a.eqEnabled
            it[Keys.EQ_BANDS] = AudioPrefs.encodeBands(a.eqBands)
            it[Keys.BASS] = a.bassBoost
            it[Keys.HAPTIC_SEEK] = a.hapticSeek
            it[Keys.RESUME_ON_CONNECT] = a.resumeOnConnect
        }
    }

    suspend fun setRecentSearches(list: List<String>) {
        context.dataStore.edit { it[Keys.RECENT_SEARCHES] = list.joinToString("\n") { q -> q.replace('\n', ' ') } }
    }

    suspend fun setLibraryConfig(config: LibraryConfig) {
        context.dataStore.edit {
            it[Keys.LIB_ORDER] = config.tabOrder.joinToString(",") { t -> t.name }
            it[Keys.LIB_HIDDEN] = config.hiddenTabs.joinToString(",") { t -> t.name }
            it[Keys.LIB_ALBUMS_GRID] = config.albumsGrid
            it[Keys.LIB_ARTISTS_GRID] = config.artistsGrid
            it[Keys.LIB_SONG_SORT] = config.songSort.name
            it[Keys.LIB_SONGS_REVERSED] = config.songsReversed
            it[Keys.LIB_GROUP_SORT] = config.groupSort.name
            it[Keys.LIB_GROUPS_REVERSED] = config.groupsReversed
        }
    }

    suspend fun setGlassQuality(quality: GlassQuality) {
        context.dataStore.edit { it[Keys.GLASS_QUALITY] = quality.name }
    }
}
