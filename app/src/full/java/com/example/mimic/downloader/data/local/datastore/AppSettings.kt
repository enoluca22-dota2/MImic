package com.example.mimic.downloader.data.local.datastore

/**
 * The five user-selectable theme modes. Stored by name in DataStore, so the original three
 * values (LIGHT, DARK, SYSTEM) keep working for existing installs.
 *  - LIGHT / DARK: plain Material surfaces.
 *  - GLASS: the Liquid Glass theme (palette follows the device's light/dark setting).
 *  - GALACTIC: the deep-space Galactic Glass theme (always dark).
 *  - SYSTEM: not a look of its own; resolves to LIGHT or DARK from Android at runtime.
 */
enum class AppThemeMode { LIGHT, DARK, GLASS, GALACTIC, SYSTEM }

enum class NetworkPolicy { WIFI_ONLY, ANY_NETWORK }

/** Optional download speed cap, passed to yt-dlp as --limit-rate. */
enum class SpeedLimit(val label: String, val ytDlpRate: String?) {
    UNLIMITED("Unlimited", null),
    MB_1("1 MB/s", "1M"),
    MB_2("2 MB/s", "2M"),
    MB_5("5 MB/s", "5M"),
    MB_10("10 MB/s", "10M"),
}

data class AppSettings(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val concurrentDownloads: Int = 2,
    val askBeforeDownloading: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val notifyOnCompletion: Boolean = true,
    val notifyOnError: Boolean = true,
    val networkPolicy: NetworkPolicy = NetworkPolicy.ANY_NETWORK,
    val customDownloadTreeUri: String? = null,
    val debugLoggingEnabled: Boolean = false,
    /** Offer to analyze a media link found on the clipboard when the app is opened. */
    val clipboardDetection: Boolean = true,
    val speedLimit: SpeedLimit = SpeedLimit.UNLIMITED,
    /**
     * Library: also index music and videos from outside ENAGELYUCA's folders (needs the media
     * read permission). Off by default: the Library starts with what the app downloaded.
     */
    val includeDeviceMedia: Boolean = false,
    /** Library: extra folders the user added (SAF tree URIs with a persisted read grant). */
    val libraryFolders: Set<String> = emptySet(),
) {
    companion object {
        const val MIN_CONCURRENT_DOWNLOADS = 1
        const val MAX_CONCURRENT_DOWNLOADS = 5
    }
}
