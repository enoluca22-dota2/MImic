package com.example.mimic.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.mimic.MimicApplication
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Periodic cloud-library refresh (MImic 3.1). Picks up songs uploaded through the Drive/OneDrive
 * website or apps while MImic was closed. It's a metadata sync only (incremental changes; no
 * audio) and needs a network connection. Android decides the exact timing — runs can be delayed
 * by Doze / battery optimizations, so this is "eventually", not push. WorkManager re-schedules
 * it after a reboot by itself.
 */
class CloudLibrarySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as MimicApplication).container
        if (!container.settingsRepository.settings.first().backgroundCloudSync) return Result.success()
        // Skip accounts refreshed in the last hour (e.g. by opening the app).
        container.cloudLibrary.syncAll(maxAgeMs = 60 * 60_000L)
        return Result.success() // per-account errors are stored and shown; no retry storm
    }

    companion object {
        private const val NAME = "mimic-cloud-sync"

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<CloudLibrarySyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
