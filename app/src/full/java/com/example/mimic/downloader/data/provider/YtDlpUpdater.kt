package com.example.mimic.downloader.data.provider

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Controlled yt-dlp updates.
 *
 * - Automatic checks run at most once per [AUTO_INTERVAL_MS] (not on every launch), only when
 *   online and never while a download or analysis is using the current binary (python loads the
 *   yt-dlp zip lazily, so swapping it mid-run can break that run).
 * - youtubedl-android downloads the new release to a temp file first; if that fails the installed
 *   version is untouched. After an update the new binary is smoke-tested (`--version`); if it
 *   doesn't run, the bundled version is restored so the app always has a working extractor.
 */
class YtDlpUpdater(
    private val context: Context,
    private val isBusy: () -> Boolean,
    private val isOnline: () -> Boolean,
) {
    enum class Outcome { UPDATED, ALREADY_CURRENT, BUSY, OFFLINE, FAILED }

    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences("ytdlp_updater", Context.MODE_PRIVATE)

    val lastCheckMillis: Long get() = prefs.getLong(KEY_LAST_CHECK, 0L)

    /** Launch-time check; a no-op unless the last check is old enough and nothing is running. */
    suspend fun autoUpdateIfDue(now: Long = System.currentTimeMillis()): Outcome? {
        if (!isDue(now, lastCheckMillis, isBusy(), isOnline())) return null
        return update(now)
    }

    /**
     * Start-up check that waits for the app to be idle (a download or analysis in progress
     * postpones it, up to [maxWaitMs]) instead of skipping until the next launch — a fresh
     * install's bundled yt-dlp can be months old, and YouTube refuses stale versions (HTTP 403).
     */
    suspend fun autoUpdateWhenIdle(maxWaitMs: Long = 10 * 60_000L, pollMs: Long = 30_000L): Outcome? {
        var waited = 0L
        while (true) {
            if (!isDue(System.currentTimeMillis(), lastCheckMillis, busy = false, online = isOnline())) return null
            if (!isBusy()) return update()
            if (waited >= maxWaitMs) return null
            kotlinx.coroutines.delay(pollMs)
            waited += pollMs
        }
    }

    /** Suspends while an update is replacing the yt-dlp files (analysis/download must not run then). */
    suspend fun awaitNotUpdating() = mutex.withLock { }

    /** Settings → "Update yt-dlp". */
    suspend fun update(now: Long = System.currentTimeMillis()): Outcome = mutex.withLock {
        if (!isOnline()) return Outcome.OFFLINE
        if (isBusy()) return Outcome.BUSY
        withContext(Dispatchers.IO) {
            prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
            val before = YoutubeDL.version(context)
            try {
                when (YoutubeDL.updateYoutubeDL(context)) {
                    YoutubeDL.UpdateStatus.DONE -> {
                        if (smokeTest()) {
                            Log.i(TAG, "yt-dlp updated: $before → ${YoutubeDL.version(context)}")
                            Outcome.UPDATED
                        } else {
                            Log.w(TAG, "Updated yt-dlp doesn't run; restoring the bundled version")
                            restoreBundled()
                            Outcome.FAILED
                        }
                    }
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE, null -> Outcome.ALREADY_CURRENT
                }
            } catch (e: Exception) {
                // Download/check failed before anything was replaced: the current version stays.
                Log.w(TAG, "yt-dlp update failed; keeping $before", e)
                if (!smokeTest()) restoreBundled()
                Outcome.FAILED
            }
        }
    }

    private fun smokeTest(): Boolean = runCatching {
        val request = YoutubeDLRequest(emptyList<String>()).apply { addOption("--version") }
        YoutubeDL.execute(request).out.trim().isNotEmpty()
    }.onFailure { Log.w(TAG, "yt-dlp smoke test failed", it) }.getOrDefault(false)

    private fun restoreBundled() {
        runCatching {
            val dir = File(File(context.noBackupFilesDir, YoutubeDL.baseName), YoutubeDL.ytdlpDirName)
            dir.deleteRecursively()
            YoutubeDL.init_ytdlp(context, dir)
        }.onFailure { Log.e(TAG, "Couldn't restore the bundled yt-dlp", it) }
    }

    companion object {
        private const val TAG = "YtDlpUpdater"
        private const val KEY_LAST_CHECK = "last_check"
        const val AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000

        fun isDue(now: Long, lastCheck: Long, busy: Boolean, online: Boolean): Boolean =
            online && !busy && (lastCheck <= 0L || now - lastCheck >= AUTO_INTERVAL_MS || now < lastCheck)
    }
}
