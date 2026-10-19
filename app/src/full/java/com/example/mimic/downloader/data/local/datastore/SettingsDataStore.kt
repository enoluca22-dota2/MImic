package com.example.mimic.downloader.data.local.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "downloader_settings")

/** Persists user preferences (Settings screen) via Jetpack DataStore. */
class SettingsDataStore(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val CONCURRENT_DOWNLOADS = intPreferencesKey("concurrent_downloads")
        val ASK_BEFORE_DOWNLOADING = booleanPreferencesKey("ask_before_downloading")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val NOTIFY_ON_COMPLETION = booleanPreferencesKey("notify_on_completion")
        val NOTIFY_ON_ERROR = booleanPreferencesKey("notify_on_error")
        val NETWORK_POLICY = stringPreferencesKey("network_policy")
        val CUSTOM_DOWNLOAD_TREE_URI = stringPreferencesKey("custom_download_tree_uri")
        val DEBUG_LOGGING = booleanPreferencesKey("debug_logging")
        val CLIPBOARD_DETECTION = booleanPreferencesKey("clipboard_detection")
        val SPEED_LIMIT = stringPreferencesKey("speed_limit")
        val INCLUDE_DEVICE_MEDIA = booleanPreferencesKey("library_include_device_media")
        val LIBRARY_FOLDERS = stringSetPreferencesKey("library_folders")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { AppThemeMode.valueOf(it) }.getOrNull() }
                ?: AppThemeMode.SYSTEM,
            concurrentDownloads = prefs[Keys.CONCURRENT_DOWNLOADS]?.coerceIn(
                AppSettings.MIN_CONCURRENT_DOWNLOADS,
                AppSettings.MAX_CONCURRENT_DOWNLOADS,
            ) ?: 2,
            askBeforeDownloading = prefs[Keys.ASK_BEFORE_DOWNLOADING] ?: true,
            notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: true,
            notifyOnCompletion = prefs[Keys.NOTIFY_ON_COMPLETION] ?: true,
            notifyOnError = prefs[Keys.NOTIFY_ON_ERROR] ?: true,
            networkPolicy = prefs[Keys.NETWORK_POLICY]?.let { runCatching { NetworkPolicy.valueOf(it) }.getOrNull() }
                ?: NetworkPolicy.ANY_NETWORK,
            customDownloadTreeUri = prefs[Keys.CUSTOM_DOWNLOAD_TREE_URI],
            debugLoggingEnabled = prefs[Keys.DEBUG_LOGGING] ?: false,
            clipboardDetection = prefs[Keys.CLIPBOARD_DETECTION] ?: true,
            speedLimit = prefs[Keys.SPEED_LIMIT]?.let { runCatching { SpeedLimit.valueOf(it) }.getOrNull() }
                ?: SpeedLimit.UNLIMITED,
            includeDeviceMedia = prefs[Keys.INCLUDE_DEVICE_MEDIA] ?: false,
            libraryFolders = prefs[Keys.LIBRARY_FOLDERS] ?: emptySet(),
        )
    }

    suspend fun settingsSnapshot(): AppSettings = settings.first()

    suspend fun setThemeMode(mode: AppThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setConcurrentDownloads(count: Int) {
        context.dataStore.edit {
            it[Keys.CONCURRENT_DOWNLOADS] = count.coerceIn(
                AppSettings.MIN_CONCURRENT_DOWNLOADS,
                AppSettings.MAX_CONCURRENT_DOWNLOADS,
            )
        }
    }

    suspend fun setAskBeforeDownloading(value: Boolean) {
        context.dataStore.edit { it[Keys.ASK_BEFORE_DOWNLOADING] = value }
    }

    suspend fun setNotificationsEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFICATIONS_ENABLED] = value }
    }

    suspend fun setNotifyOnCompletion(value: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFY_ON_COMPLETION] = value }
    }

    suspend fun setNotifyOnError(value: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFY_ON_ERROR] = value }
    }

    suspend fun setNetworkPolicy(policy: NetworkPolicy) {
        context.dataStore.edit { it[Keys.NETWORK_POLICY] = policy.name }
    }

    suspend fun setCustomDownloadTreeUri(uri: String?) {
        context.dataStore.edit {
            if (uri == null) it.remove(Keys.CUSTOM_DOWNLOAD_TREE_URI) else it[Keys.CUSTOM_DOWNLOAD_TREE_URI] = uri
        }
    }

    suspend fun setDebugLoggingEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.DEBUG_LOGGING] = value }
    }

    suspend fun setClipboardDetection(value: Boolean) {
        context.dataStore.edit { it[Keys.CLIPBOARD_DETECTION] = value }
    }

    suspend fun setSpeedLimit(limit: SpeedLimit) {
        context.dataStore.edit { it[Keys.SPEED_LIMIT] = limit.name }
    }

    suspend fun setIncludeDeviceMedia(value: Boolean) {
        context.dataStore.edit { it[Keys.INCLUDE_DEVICE_MEDIA] = value }
    }

    suspend fun addLibraryFolder(treeUri: String) {
        context.dataStore.edit { it[Keys.LIBRARY_FOLDERS] = (it[Keys.LIBRARY_FOLDERS] ?: emptySet()) + treeUri }
    }

    suspend fun removeLibraryFolder(treeUri: String) {
        context.dataStore.edit { it[Keys.LIBRARY_FOLDERS] = (it[Keys.LIBRARY_FOLDERS] ?: emptySet()) - treeUri }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
