package com.example.mimic.online

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.mimic.MimicApplication
import com.example.mimic.data.online.OnlineDownloadState
import com.example.mimic.data.online.OnlineIds
import com.example.mimic.data.online.OnlineTrack
import com.example.mimic.downloader.download.AudioConverter
import com.example.mimic.online.data.innertube.StreamResolver
import com.example.mimic.online.data.model.Song
import com.example.mimic.online.download.Downloader
import com.example.mimic.online.download.MediaTagger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Downloads one online track for offline playback (MImic 4.4). WorkManager runs it, so a download
 * keeps going with the app closed, survives a process kill, waits for a network (or for Wi-Fi when
 * "Wi-Fi only" is on), and is never queued twice (unique work per videoId).
 *
 * What lands on disk is exactly what YouTube sent — Opus in WebM or AAC in MP4, named for what it
 * is — tagged with the track's own title / artist / album / cover (BitChord's taggers). MP3 is only
 * produced when the user picked it, by a real ffmpeg re-encode. A transfer that dies mid-way is
 * restarted from the beginning (the stream URL expires, so resuming bytes isn't offered).
 */
class OnlineDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val track = OnlineTrack(
        videoId = inputData.getString(K_ID).orEmpty(),
        title = inputData.getString(K_TITLE).orEmpty(),
        artist = inputData.getString(K_ARTIST).orEmpty(),
        album = inputData.getString(K_ALBUM),
        albumId = inputData.getString(K_ALBUM_ID),
        artistId = inputData.getString(K_ARTIST_ID),
        durationMs = inputData.getLong(K_DURATION, 0L),
        artworkUrl = inputData.getString(K_ART),
        trackNo = inputData.getInt(K_TRACK_NO, 0),
    )

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(-1f)

    override suspend fun doWork(): Result {
        if (track.videoId.isBlank()) return Result.failure(workDataOf(K_ERROR to "Missing track"))
        val ctx = applicationContext
        OnlineMusic.init(ctx)
        val container = (ctx as MimicApplication).container
        val repo = container.downloadRepository
        val remoteId = OnlineIds.remoteId(track.videoId)
        if (repo.isDownloaded(remoteId)) {
            val existing = container.downloadDao.get(remoteId)
            val f = existing?.fileUri?.let { Uri.parse(it).path }?.let(::File)
            if (f != null && f.isFile && f.length() > 0) return Result.success() // already offline: no duplicate
        }
        runCatching { setForeground(foreground(-1f)) }

        val format = OnlineQuality.downloadFormat
        val dir = repo.onlineDir
        val base = "yt_" + track.videoId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val part = File(dir, "$base.part")
        return try {
            val stream = StreamResolver.resolveForDownload(track.videoId, Int.MAX_VALUE, requireM4a = format == OnlineQuality.DownloadFormat.M4A)
            val ext = stream.downloadExtension // "m4a" or "webm" — what the bytes really are
            withContext(Dispatchers.IO) {
                part.outputStream().buffered().use { out ->
                    var last = 0L
                    Downloader.fetch(track.videoId, stream, Int.MAX_VALUE, format == OnlineQuality.DownloadFormat.M4A, out) { written, total ->
                        val now = System.currentTimeMillis()
                        if (now - last > 400) {
                            last = now
                            val f = written.toFloat() / total
                            setProgressAsync(workDataOf(K_PROGRESS to f))
                            notify(f)
                        }
                    }
                }
            }
            var file = File(dir, "$base.$ext")
            file.delete()
            if (!part.renameTo(file)) throw IOException("Couldn't save the file")

            // Per-track artwork: fetched for THIS videoId and stored under its own name.
            val bcSong = Song(
                videoId = track.videoId, title = track.title, artist = track.artist,
                thumbnailUrl = track.artworkUrl, albumName = track.album, albumId = track.albumId,
            )
            val art = runCatching { MediaTagger.artworkFor(ctx, bcSong) }.getOrNull()
            val cover = art?.let { a -> File(dir, "$base.cover").also { it.writeBytes(a.bytes) } }

            if (format == OnlineQuality.DownloadFormat.MP3) {
                val coverCopy = cover?.let { c -> File(dir, "$base.cover.tmp").also { c.copyTo(it, overwrite = true) } }
                file = AudioConverter.convertToMp3(
                    ctx, file, 320, AudioConverter.Metadata(track.title, track.artist), coverCopy, key = "online:$base",
                )
            } else {
                MediaTagger.embed(ctx, Uri.fromFile(file), bcSong, ext, lyrics = null, artwork = art)
            }
            if (!file.isFile || file.length() == 0L) throw IOException("The downloaded file is empty")

            container.onlineTracks.remember(listOf(track), inLibrary = true)
            repo.recordOnline(track, file, cover)
            OnlineDiagnostics.recordSuccess(track.videoId, "download")
            Result.success()
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: StreamResolver.PermanentlyUnplayableException) {
            part.delete()
            OnlineDiagnostics.record(track.videoId, "unavailable", e, "download")
            Result.failure(workDataOf(K_ERROR to (e.message ?: "This track can't be downloaded")))
        } catch (e: Exception) {
            part.delete()
            OnlineDiagnostics.record(track.videoId, if (e is IOException) "network" else "download", e, "download")
            // Bounded: three attempts with WorkManager's backoff, then a visible failure (no loops).
            if (e is IOException && runAttemptCount < MAX_ATTEMPTS - 1) Result.retry()
            else Result.failure(workDataOf(K_ERROR to friendly(e)))
        }
    }

    private fun friendly(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            e is IOException -> "No connection to YouTube Music. Try again when you're online."
            "403" in m || "refused" in m -> "YouTube refused this download. Try again later."
            m.isNotBlank() -> m.take(160)
            else -> "Download failed"
        }
    }

    private fun foreground(fraction: Float): ForegroundInfo {
        val n = notification(fraction)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ForegroundInfo(notifId(), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notifId(), n)
    }

    private fun notify(fraction: Float) {
        runCatching { applicationContext.getSystemService(NotificationManager::class.java).notify(notifId(), notification(fraction)) }
    }

    private fun notification(fraction: Float): android.app.Notification {
        ensureChannel(applicationContext)
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(track.title.ifBlank { "Downloading" })
            .setContentText(track.artist)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, (fraction.coerceIn(0f, 1f) * 100).toInt(), fraction < 0f)
            .build()
    }

    private fun notifId() = 6_000_000 + (track.videoId.hashCode() and 0xFFFFF)

    companion object {
        private const val CHANNEL = "online_downloads"
        private const val TAG = "online-dl"
        private const val MAX_ATTEMPTS = 3
        const val K_ID = "id"; const val K_TITLE = "title"; const val K_ARTIST = "artist"; const val K_ALBUM = "album"
        const val K_ALBUM_ID = "albumId"; const val K_ARTIST_ID = "artistId"; const val K_DURATION = "duration"
        const val K_ART = "art"; const val K_TRACK_NO = "trackNo"; const val K_PROGRESS = "progress"; const val K_ERROR = "error"

        private fun uniqueName(videoId: String) = "online-dl-$videoId"

        fun enqueue(context: Context, t: OnlineTrack) {
            val request = OneTimeWorkRequestBuilder<OnlineDownloadWorker>()
                .setInputData(
                    workDataOf(
                        K_ID to t.videoId, K_TITLE to t.title, K_ARTIST to t.artist, K_ALBUM to t.album,
                        K_ALBUM_ID to t.albumId, K_ARTIST_ID to t.artistId, K_DURATION to t.durationMs,
                        K_ART to t.artworkUrl, K_TRACK_NO to t.trackNo,
                    ),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (OnlineQuality.wifiOnlyDownloads) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(TAG)
                .addTag("vid:${t.videoId}")
                .build()
            // KEEP: tapping Download twice (or a whole album containing it) never queues it twice.
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(t.videoId), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, videoId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(videoId))
        }

        fun states(context: Context): Flow<Map<String, OnlineDownloadState>> =
            WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).map { infos ->
                // One state per track. A newer attempt outranks an older failure, and a finished
                // download clears it: running > queued > done > failed.
                val best = HashMap<String, Pair<Int, OnlineDownloadState?>>()
                for (info in infos) {
                    val id = info.tags.firstOrNull { it.startsWith("vid:") }?.removePrefix("vid:") ?: continue
                    val ranked: Pair<Int, OnlineDownloadState?> = when (info.state) {
                        WorkInfo.State.RUNNING -> 4 to OnlineDownloadState.Running(info.progress.getFloat(K_PROGRESS, -1f))
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> 3 to OnlineDownloadState.Queued
                        WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED -> 2 to null
                        WorkInfo.State.FAILED -> 1 to OnlineDownloadState.Failed(info.outputData.getString(K_ERROR) ?: "Download failed")
                    }
                    if (ranked.first > (best[id]?.first ?: 0)) best[id] = ranked
                }
                best.mapNotNull { (id, r) -> r.second?.let { id to it } }.toMap()
            }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Online music downloads", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }
}
