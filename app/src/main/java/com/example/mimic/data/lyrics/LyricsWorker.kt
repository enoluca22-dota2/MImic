package com.example.mimic.data.lyrics

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.mimic.MimicApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Background lyrics lookups (MImic 4.5), so lyrics are saved before the song is ever played:
 *  - [enqueue]: one song, right after a download finishes (Navidrome, online, link downloader).
 *    Waits for a connection; a lookup failure only ever affects lyrics — the download is already
 *    recorded as successful before this is queued.
 *  - [scan]: Find Missing Lyrics — every given song without saved lyrics, at a pace LRCLIB is fine
 *    with, with progress, cancellation and a summary. Songs already resolved are skipped, so a
 *    scan that Android stops (WorkManager's execution limit) carries on where it was when it is
 *    rescheduled. Audio files are never touched or re-downloaded.
 */
class LyricsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val repo get() = (applicationContext as MimicApplication).container.lyricsRepository

    override suspend fun doWork(): Result =
        if (inputData.getString(KEY_MODE) == MODE_SCAN) scanAll() else one()

    private suspend fun one(): Result {
        val t = targetOf(inputData) ?: return Result.success()
        return when (repo.resolve(t, allowNetwork = true)) {
            is LyricsOutcome.Failed -> if (runAttemptCount < 3) Result.retry() else Result.success()
            else -> Result.success()
        }
    }

    private suspend fun scanAll(): Result {
        val file = File(applicationContext.filesDir, SCAN_FILE)
        val targets = runCatching { readTargets(file.readText()) }.getOrDefault(emptyList())
        val saved = repo.savedRows().associateBy { it.songId }
        var found = 0; var unavailable = 0; var failed = 0; var choose = 0; var skipped = 0
        targets.forEachIndexed { i, t ->
            if (isStopped) return Result.success(summary(targets.size, i, found, unavailable, failed, choose, skipped, cancelled = true))
            val row = saved[t.songId]?.takeIf { it.trackKey == LyricsRepository.trackKey(t) }
            // Rows are read without their text (savedRows), so "has lyrics" is judged by status.
            val done = row != null && (row.lyricsStatus == LyricsStatus.SYNCED || row.lyricsStatus == LyricsStatus.PLAIN || row.userChosen ||
                (row.lyricsStatus == LyricsStatus.UNAVAILABLE && System.currentTimeMillis() - row.updatedAt < LyricsRepository.UNAVAILABLE_RECHECK_MS) ||
                row.lyricsStatus == LyricsStatus.NEEDS_CHOICE)
            if (done) {
                skipped++
            } else {
                when (repo.resolve(t, allowNetwork = true, force = row?.lyricsStatus == LyricsStatus.FAILED)) {
                    is LyricsOutcome.Found -> found++
                    is LyricsOutcome.Unavailable -> unavailable++
                    is LyricsOutcome.Failed -> failed++
                    is LyricsOutcome.Choose -> choose++
                }
                // LRCLIB is run by volunteers: a scan of a whole library goes at a gentle pace.
                delay(SCAN_SPACING_MS)
            }
            setProgress(summary(targets.size, i + 1, found, unavailable, failed, choose, skipped, cancelled = false))
        }
        return Result.success(summary(targets.size, targets.size, found, unavailable, failed, choose, skipped, cancelled = false))
    }

    data class ScanState(
        val running: Boolean,
        val total: Int,
        val done: Int,
        val found: Int,
        val unavailable: Int,
        val failed: Int,
        val choose: Int,
        val skipped: Int,
        val finished: Boolean,
        val cancelled: Boolean,
    )

    companion object {
        private const val KEY_MODE = "mode"
        private const val MODE_SCAN = "scan"
        private const val SCAN_WORK = "mimic-lyrics-scan"
        private const val SCAN_FILE = "lyrics_scan.json"
        private const val SCAN_SPACING_MS = 700L

        /** Look up (and save) lyrics for one song in the background, once online. */
        fun enqueue(context: Context, t: LyricsTarget) {
            if (t.isRadio || t.title.isBlank()) return
            val req = OneTimeWorkRequestBuilder<LyricsWorker>()
                .setInputData(dataOf(t))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("mimic-lyrics-${t.songId}", ExistingWorkPolicy.KEEP, req)
        }

        /** Find Missing Lyrics over [targets] (replaces a scan already running). */
        fun scan(context: Context, targets: List<LyricsTarget>) {
            File(context.filesDir, SCAN_FILE).writeText(writeTargets(targets))
            val req = OneTimeWorkRequestBuilder<LyricsWorker>()
                .setInputData(workDataOf(KEY_MODE to MODE_SCAN))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(SCAN_WORK, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancelScan(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(SCAN_WORK)

        fun scanState(context: Context): Flow<ScanState?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(SCAN_WORK).map { infos ->
                val info = infos.lastOrNull() ?: return@map null
                val d = if (info.state.isFinished) info.outputData else info.progress
                ScanState(
                    running = !info.state.isFinished,
                    total = d.getInt("total", 0), done = d.getInt("done", 0), found = d.getInt("found", 0),
                    unavailable = d.getInt("unavailable", 0), failed = d.getInt("failed", 0), choose = d.getInt("choose", 0),
                    skipped = d.getInt("skipped", 0),
                    finished = info.state.isFinished,
                    cancelled = info.state == WorkInfo.State.CANCELLED || d.getBoolean("cancelled", false),
                )
            }

        private fun summary(total: Int, done: Int, found: Int, unavailable: Int, failed: Int, choose: Int, skipped: Int, cancelled: Boolean): Data =
            workDataOf(
                "total" to total, "done" to done, "found" to found, "unavailable" to unavailable,
                "failed" to failed, "choose" to choose, "skipped" to skipped, "cancelled" to cancelled,
            )

        private fun dataOf(t: LyricsTarget): Data = workDataOf(
            "id" to t.songId, "title" to t.title, "artist" to t.artist, "album" to t.album,
            "duration" to t.durationMs, "uri" to t.uri, "remote" to t.remoteId,
        )

        private fun targetOf(d: Data): LyricsTarget? {
            val id = d.getLong("id", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE } ?: return null
            return LyricsTarget(
                id, d.getString("title").orEmpty(), d.getString("artist").orEmpty(), d.getString("album").orEmpty(),
                d.getLong("duration", 0L), d.getString("uri").orEmpty(), d.getString("remote"),
            )
        }

        internal fun writeTargets(list: List<LyricsTarget>): String = JSONArray().apply {
            list.forEach { t ->
                put(
                    JSONObject().put("id", t.songId).put("title", t.title).put("artist", t.artist).put("album", t.album)
                        .put("duration", t.durationMs).put("uri", t.uri).put("remote", t.remoteId ?: JSONObject.NULL),
                )
            }
        }.toString()

        internal fun readTargets(json: String): List<LyricsTarget> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LyricsTarget(
                    o.getLong("id"), o.optString("title"), o.optString("artist"), o.optString("album"),
                    o.optLong("duration"), o.optString("uri"), o.optString("remote").takeIf { !o.isNull("remote") && it.isNotEmpty() },
                )
            }
        }
    }
}
