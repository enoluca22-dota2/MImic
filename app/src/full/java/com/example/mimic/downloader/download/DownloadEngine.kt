package com.example.mimic.downloader.download

import android.content.Context
import android.util.Log
import com.example.mimic.downloader.core.MimeTypes
import com.example.mimic.downloader.core.NetworkMonitor
import com.example.mimic.downloader.data.local.datastore.NetworkPolicy
import com.example.mimic.downloader.data.local.datastore.SettingsDataStore
import com.example.mimic.downloader.data.local.db.DownloadDao
import com.example.mimic.downloader.data.local.db.DownloadEntity
import com.example.mimic.downloader.data.local.db.HistoryDao
import com.example.mimic.downloader.data.local.db.HistoryEntity
import com.example.mimic.downloader.data.model.DownloadStatus
import com.example.mimic.downloader.data.model.FormatKind
import com.example.mimic.downloader.data.provider.DownloadJobRequest
import com.example.mimic.downloader.data.provider.MediaProvider
import com.example.mimic.downloader.data.provider.ProviderException
import com.example.mimic.downloader.data.provider.StoragePublisher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the actual download queue: picks up QUEUED rows in [DownloadDao] up to the configured
 * concurrency, drives yt-dlp through [MediaProvider], persists progress, and publishes finished
 * files via [StoragePublisher]. Independent of Activity/Service lifecycle by design — the hosting
 * [DownloadService] only keeps the process in the foreground while work exists.
 *
 * The job's [DownloadStatus] is the single source of truth and only this class writes it:
 * QUEUED → FETCHING_INFO → DOWNLOADING → PROCESSING → COMPLETED, or FAILED / CANCELLED / PAUSED.
 * Screens and notifications only observe it.
 *
 * Every job downloads into its own cache dir (`cache/ytd_downloads/<id>`), so concurrent jobs
 * never share files. yt-dlp's `.part` files stay there across pause/retry so the transfer can
 * continue where it stopped; the dir is deleted once the job completes, is cancelled or its
 * record is removed.
 */
class DownloadEngine(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val historyDao: HistoryDao,
    private val mediaProvider: MediaProvider,
    private val storagePublisher: StoragePublisher,
    private val settingsDataStore: SettingsDataStore,
    private val notifications: DownloadNotifications,
    private val networkMonitor: NetworkMonitor,
    private val scope: CoroutineScope,
    /** Asks for the foreground service to be running; called whenever a job is about to start. */
    private val requestForeground: () -> Unit,
    /** Told about each published file (the Library); never able to fail the download. */
    private val completionListener: DownloadCompletionListener? = null,
) {
    companion object {
        private const val TAG = "DownloadEngine"
        private const val MAX_AUTO_RETRIES = 3
        private const val BASE_BACKOFF_MS = 4000L
        /** ~5 UI updates per second: continuous-looking without flooding Room/Compose. */
        private const val PROGRESS_PERSIST_INTERVAL_MS = 200L
        private const val NOTIFICATION_INTERVAL_MS = 1000L
        private const val STALL_AFTER_MS = 3000L

        /** Fixed temp name for yt-dlp's `-o` template; the user's filename is applied at publish time. */
        const val TEMP_FILE_BASENAME = "media"

        /** yt-dlp postprocessor prefixes: once one of these prints, the transfer is over. */
        private val PROCESSING_PREFIXES = listOf("[Merger]", "[FixupM3u8]", "[FixupM4a]", "[FixupTimestamp]", "[FixupDuplicateMoov]", "[VideoConvertor]", "[ExtractAudio]", "[Metadata]", "[EmbedThumbnail]")

        /** Maps a yt-dlp output line to the state it implies, or null if it says nothing new. */
        fun stateForOutputLine(line: String, percent: Float): DownloadStatus? {
            val trimmed = line.trimStart()
            return when {
                PROCESSING_PREFIXES.any { trimmed.startsWith(it) } -> DownloadStatus.PROCESSING
                trimmed.startsWith(ProgressTracker.PROGRESS_PREFIX) -> DownloadStatus.DOWNLOADING
                trimmed.startsWith("[download]") && percent > 0f -> DownloadStatus.DOWNLOADING
                else -> null
            }
        }
    }

    private var started = false
    private val activeIds = MutableStateFlow<Set<Long>>(emptySet())
    private val processIdByDownloadId = ConcurrentHashMap<Long, String>()

    /** The coroutine running each active job, so cancel/pause can stop it (and whatever it waits on). */
    private val jobsById = ConcurrentHashMap<Long, Job>()

    /** Number of downloads currently running (including ones waiting to auto-retry). */
    val activeCount: StateFlow<Int> = activeIds.map { it.size }.stateIn(scope, SharingStarted.Eagerly, 0)

    /** True when queued work exists but is held back because the user chose "Wi-Fi only". */
    private val _waitingForWifi = MutableStateFlow(false)
    val waitingForWifi: StateFlow<Boolean> = _waitingForWifi

    fun tempDirFor(id: Long) = File(context.cacheDir, "ytd_downloads/$id")

    fun start() {
        if (started) return
        started = true
        scope.launch {
            recoverAfterProcessDeath()
            combine(
                downloadDao.observeByStatus(DownloadStatus.QUEUED),
                settingsDataStore.settings,
                networkMonitor.state,
                activeIds,
            ) { queued, settings, network, active ->
                val blockedByPolicy = settings.networkPolicy == NetworkPolicy.WIFI_ONLY &&
                    network != NetworkMonitor.State.UNMETERED
                _waitingForWifi.value = blockedByPolicy && queued.isNotEmpty()
                if (blockedByPolicy) return@combine emptyList()
                val freeSlots = (settings.concurrentDownloads - active.size).coerceAtLeast(0)
                // Queue order = user order (queuePosition), falling back to creation time.
                queued.filter { it.id !in active }.sortedBy { it.effectiveQueuePosition }.take(freeSlots)
            }.collect { toStart -> toStart.forEach { launchJob(it) } }
        }
    }

    /** True if something is running or is queued and allowed to start right now. */
    suspend fun hasRunnableWork(): Boolean {
        if (activeCount.value > 0) return true
        if (waitingForWifi.value) return false
        return downloadDao.getByStatuses(listOf(DownloadStatus.QUEUED)).isNotEmpty()
    }

    suspend fun pause(id: Long) {
        val current = downloadDao.getById(id) ?: return
        if (!current.status.canPause) return
        downloadDao.setStatusAndClearSpeed(id, DownloadStatus.PAUSED, System.currentTimeMillis())
        stopRunningWork(id)
    }

    /**
     * Stops the job for good: kills yt-dlp, ffmpeg and any child they left, cancels the job's
     * coroutine and keeps the row as CANCELLED so it can be retried or removed. Other jobs —
     * including other items of the same playlist — are not touched.
     */
    suspend fun cancel(id: Long) {
        val current = downloadDao.getById(id) ?: return
        if (!current.status.canCancel) return
        val now = System.currentTimeMillis()
        downloadDao.setStatusAndClearSpeed(id, DownloadStatus.CANCELLED, now)
        downloadDao.setErrorMessage(id, null, now)
        stopRunningWork(id)
        notifications.cancel(DownloadNotifications.notificationIdFor(id))
        recordHistory(current, DownloadStatus.CANCELLED, fileUri = null, sizeBytes = null, extension = current.container, error = null)
        // A running job cleans up its own dir when it unwinds; this covers queued/paused ones.
        if (id !in activeIds.value) deleteTempDir(id)
    }

    suspend fun resume(id: Long) {
        val current = downloadDao.getById(id) ?: return
        if (current.status != DownloadStatus.PAUSED) return
        downloadDao.setStatus(id, DownloadStatus.QUEUED, System.currentTimeMillis())
    }

    /** Re-queues only this failed/cancelled item; nothing else in its playlist is restarted. */
    suspend fun retry(id: Long) {
        val current = downloadDao.getById(id) ?: return
        if (!current.status.canRetry) return
        notifications.cancel(DownloadNotifications.completedNotificationIdFor(id))
        downloadDao.retry(id, DownloadStatus.QUEUED, System.currentTimeMillis())
    }

    /** Kills every process working on [id] and cancels its coroutine. Safe to call when nothing runs. */
    private suspend fun stopRunningWork(id: Long) {
        processIdByDownloadId.remove(id)?.let { mediaProvider.cancel(it) }
        AudioConverter.cancel(converterKey(id))
        jobsById[id]?.cancel(CancellationException("stopped by user"))
        // yt-dlp's own ffmpeg children (merging) survive the python process being destroyed.
        withContext(Dispatchers.IO) { ProcessReaper.killProcessesReferencing(tempDirFor(id).absolutePath) }
    }

    private fun converterKey(id: Long) = "download-$id"

    // --- Queue-wide actions (built on the per-item ones above) --------------------------------

    suspend fun pauseAll() {
        downloadDao.getByStatuses(listOf(DownloadStatus.QUEUED) + DownloadStatus.ACTIVE).forEach { pause(it.id) }
    }

    suspend fun resumeAll() {
        downloadDao.getByStatuses(listOf(DownloadStatus.PAUSED)).forEach { resume(it.id) }
    }

    suspend fun cancelAll() {
        downloadDao.getByStatuses(listOf(DownloadStatus.QUEUED, DownloadStatus.PAUSED) + DownloadStatus.ACTIVE)
            .forEach { cancel(it.id) }
    }

    suspend fun retryAllFailed() {
        downloadDao.getByStatuses(listOf(DownloadStatus.FAILED)).forEach { retry(it.id) }
    }

    /**
     * Moves a waiting (queued or paused) job one place up or down in the queue by swapping its
     * position with its neighbour. Running jobs aren't reordered.
     */
    suspend fun move(id: Long, up: Boolean) {
        val waiting = downloadDao.getByStatuses(listOf(DownloadStatus.QUEUED, DownloadStatus.PAUSED))
        val index = waiting.indexOfFirst { it.id == id }
        if (index < 0) return
        val neighbour = waiting.getOrNull(if (up) index - 1 else index + 1) ?: return
        val self = waiting[index]
        val selfPos = self.effectiveQueuePosition
        val otherPos = neighbour.effectiveQueuePosition
        downloadDao.setQueuePosition(self.id, otherPos)
        downloadDao.setQueuePosition(neighbour.id, if (otherPos == selfPos) selfPos + (if (up) 1 else -1) else selfPos)
    }

    /** Removes a finished/failed/cancelled record and any partial data it left in the cache. */
    suspend fun deleteRecord(id: Long) {
        if (id in activeIds.value) return
        downloadDao.deleteById(id)
        notifications.cancel(DownloadNotifications.completedNotificationIdFor(id))
        deleteTempDir(id)
    }

    /**
     * If the process died mid-transfer (swiped away, killed for memory, crash), rows are left in
     * an active state with nothing running them. Put them back in the queue, and drop temp dirs
     * whose record no longer exists or that belong to a cancelled job.
     */
    private suspend fun recoverAfterProcessDeath() {
        val now = System.currentTimeMillis()
        downloadDao.getByStatuses(DownloadStatus.ACTIVE.toList()).forEach {
            downloadDao.setStatusAndClearSpeed(it.id, DownloadStatus.QUEUED, now)
        }
        withContext(Dispatchers.IO) {
            val keep = downloadDao.getByStatuses(listOf(DownloadStatus.QUEUED, DownloadStatus.PAUSED, DownloadStatus.FAILED))
                .map { it.id }.toSet()
            File(context.cacheDir, "ytd_downloads").listFiles()?.forEach { dir ->
                val id = dir.name.toLongOrNull()
                if (id == null || id !in keep) dir.deleteRecursively()
            }
        }
    }

    private fun launchJob(entity: DownloadEntity) {
        // Atomic check-and-add: a stale queue emission must never start the same job twice.
        if (entity.id in activeIds.getAndUpdate { it + entity.id }) return
        requestForeground()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runJob(entity)
            } catch (e: CancellationException) {
                // Stopped by pause()/cancel(): the state they wrote stands; just tidy up.
                withContext(NonCancellable) { afterStopped(entity.id) }
            } finally {
                jobsById.remove(entity.id)
                processIdByDownloadId.remove(entity.id)
                activeIds.update { it - entity.id }
            }
        }
        jobsById[entity.id] = job
        job.start()
    }

    private suspend fun runJob(entity: DownloadEntity) {
        val id = entity.id
        val tempDir = tempDirFor(id)
        // QUEUED → FETCHING_INFO only if nobody paused/cancelled it since the queue emitted it.
        if (downloadDao.transition(id, DownloadStatus.QUEUED, DownloadStatus.FETCHING_INFO, System.currentTimeMillis()) == 0) return
        downloadDao.markStarted(id, DownloadStatus.FETCHING_INFO, System.currentTimeMillis())

        var attempt = 0
        var alternateClients = false
        while (true) {
            try {
                downloadAndPublish(entity, tempDir, alternateClients)
                deleteTempDir(id)
                return
            } catch (e: ProviderException.Cancelled) {
                afterStopped(id)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e as? ProviderException ?: ProviderException.classify(e.message, e)
                // Technical details go to Logcat only; the row keeps the user-facing message.
                Log.w(TAG, "Download $id failed (attempt ${attempt + 1}): ${error.javaClass.simpleName} ${error.detail ?: ""}", e)
                if (downloadDao.getById(id)?.status?.isActive != true) {
                    // Paused/cancelled while the process was dying: that's not a failure.
                    afterStopped(id)
                    return
                }

                // Refused (403) or "format not available" with yt-dlp's default YouTube clients:
                // retry once right away with the alternate clients before counting a failure.
                if (!alternateClients && (error is ProviderException.Blocked || error is ProviderException.FormatUnavailable)) {
                    alternateClients = true
                    Log.i(TAG, "Download $id: retrying with alternate YouTube clients")
                    continue
                }

                if (error.isRetryable && attempt < MAX_AUTO_RETRIES) {
                    attempt++
                    val backoffMs = BASE_BACKOFF_MS * (1L shl (attempt - 1))
                    downloadDao.setErrorMessage(
                        id,
                        "${error.message} Retrying automatically ($attempt/$MAX_AUTO_RETRIES)…",
                        System.currentTimeMillis(),
                    )
                    delay(backoffMs)
                    // The user may have paused or cancelled while we were waiting.
                    if (downloadDao.getById(id)?.status?.isActive != true) return
                    continue
                }
                handleFailure(id, error)
                return
            }
        }
    }

    /**
     * yt-dlp's default "[download]  42.0% of 10.00MiB at 1.20MiB/s ETA 00:07" line, used only if
     * the progress template isn't honoured. The percentage comes from yt-dlp itself.
     */
    private fun legacyProgress(line: String, percent: Float, eta: Long?): ProgressTracker.Snapshot? {
        if (percent < 0f || !line.trimStart().startsWith("[download]")) return null
        val parsed = ProgressParser.parse(line)
        val fraction = (percent / 100f).coerceIn(0f, 1f)
        val downloaded = parsed.totalBytes?.let { (fraction * it).toLong() } ?: 0L
        return ProgressTracker.Snapshot(downloaded, parsed.totalBytes, fraction, parsed.speedBytesPerSecond, eta)
    }

    private suspend fun setState(id: Long, status: DownloadStatus) {
        val current = downloadDao.getById(id)?.status ?: return
        if (current == status) return
        if (!current.isActive) return // paused/cancelled meanwhile: never overwrite the user's choice
        if (!DownloadStatus.canTransition(current, status)) {
            Log.w(TAG, "Ignoring illegal transition $current → $status for $id")
            return
        }
        downloadDao.transition(id, current, status, System.currentTimeMillis())
    }

    private suspend fun downloadAndPublish(entity: DownloadEntity, tempDir: File, alternateClients: Boolean) {
        val id = entity.id
        val processId = "dl-$id-${System.currentTimeMillis()}"
        processIdByDownloadId[id] = processId
        val settings = settingsDataStore.settings.first()
        val lastPersist = java.util.concurrent.atomic.AtomicLong(0L)
        var reported: DownloadStatus = DownloadStatus.FETCHING_INFO

        val jobRequest = DownloadJobRequest(
            sourceUrl = entity.sourceUrl,
            formatId = entity.formatId,
            formatKind = entity.formatKind,
            requiresAudioMerge = entity.requiresAudioMerge,
            requiresAudioExtraction = entity.requiresAudioExtraction,
            outputDirectory = tempDir.absolutePath,
            // Never pass the user's title into yt-dlp's -o template: "%" in a title
            // ("100% Hits") is parsed as a template field and breaks the download.
            outputFileNameNoExt = TEMP_FILE_BASENAME,
            container = entity.container,
            heightPx = entity.resolutionLabel?.removeSuffix("p")?.toIntOrNull(),
            playlistIndex = entity.playlistIndex,
            rateLimit = settings.speedLimit.ytDlpRate,
            alternateClients = alternateClients,
        )

        val tracker = ProgressTracker()
        var lastNotify = 0L
        // State changes and progress for this job are written by ONE coroutine, in the order
        // yt-dlp reported them, so the stored percentage can never go backwards through a
        // reordered write.
        val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        val writer = scope.launch { for (write in writes) write() }
        // yt-dlp can go quiet (e.g. between the video and audio streams, or a slow server).
        // The bar then simply stays where it is; the speed/ETA of the last tick is cleared so
        // the text doesn't suggest data is still flowing.
        val lastProgressAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val speedShown = java.util.concurrent.atomic.AtomicBoolean(false)
        // The newest value the throttle held back; written as soon as the throttle window ends,
        // so a download that goes quiet still shows exactly where it stopped.
        val pending = java.util.concurrent.atomic.AtomicReference<ProgressTracker.Snapshot?>(null)
        val progressLock = Any()
        fun persist(progress: ProgressTracker.Snapshot, now: Long) {
            lastPersist.set(now)
            speedShown.set((progress.speedBytesPerSecond ?: 0L) > 0)
            val percent = progress.percent ?: 0f
            writes.trySend {
                downloadDao.updateProgress(
                    id, percent, progress.downloadedBytes, progress.totalBytes,
                    progress.speedBytesPerSecond ?: 0L, progress.etaSeconds, now,
                )
            }
        }
        val stallWatch = scope.launch {
            while (true) {
                delay(250)
                val now = System.currentTimeMillis()
                synchronized(progressLock) {
                    if (now - lastPersist.get() >= PROGRESS_PERSIST_INTERVAL_MS) pending.getAndSet(null)?.let { persist(it, now) }
                }
                if (speedShown.get() && now - lastProgressAt.get() > STALL_AFTER_MS) {
                    speedShown.set(false)
                    writes.trySend { downloadDao.clearSpeed(id) }
                }
            }
        }
        val result = try {
            mediaProvider.download(jobRequest, processId) { libraryPercent, libraryEta, line ->
            if (settings.debugLoggingEnabled) Log.d(TAG, "[$id] $line")
            // Stale callbacks from a process we've already killed (pause/cancel) must not
            // resurrect the progress notification.
            if (processIdByDownloadId[id] != processId) return@download
            val snapshot = tracker.onLine(line)
            val next = if (snapshot != null) DownloadStatus.DOWNLOADING else stateForOutputLine(line, libraryPercent)
            if (next != null && next != reported && !(reported == DownloadStatus.PROCESSING && next == DownloadStatus.DOWNLOADING)) {
                reported = next
                writes.trySend { setState(id, next) }
                if (next == DownloadStatus.PROCESSING && settings.notificationsEnabled) {
                    notifications.notify(DownloadNotifications.notificationIdFor(id), notifications.processingNotification(entity, "Merging…"))
                }
            }
            if (reported == DownloadStatus.PROCESSING) return@download

            // Real progress: our byte-based template, or yt-dlp's classic line as a fallback.
            val progress = snapshot ?: legacyProgress(line, libraryPercent, libraryEta) ?: return@download
            val now = System.currentTimeMillis()
            lastProgressAt.set(now)
            val done = progress.fraction == 1f
            // One lock for "hold back" and "write", so the timer flush can never write an
            // older value after a newer one.
            val written = synchronized(progressLock) {
                if (now - lastPersist.get() < PROGRESS_PERSIST_INTERVAL_MS && !done) {
                    pending.set(progress)
                    false
                } else {
                    pending.set(null)
                    persist(progress, now)
                    true
                }
            }
            if (!written) return@download
            val percent = progress.percent ?: 0f
            // Android drops notification updates beyond a few per second; once a second is plenty.
            if (settings.notificationsEnabled && (now - lastNotify >= NOTIFICATION_INTERVAL_MS || done)) {
                lastNotify = now
                notifications.notify(
                    DownloadNotifications.notificationIdFor(id),
                    notifications.progressNotification(
                        entity.copy(
                            status = DownloadStatus.DOWNLOADING,
                            progressPercent = percent,
                            downloadedBytes = progress.downloadedBytes,
                            totalBytes = progress.totalBytes,
                            speedBytesPerSec = progress.speedBytesPerSecond ?: 0L,
                            etaSeconds = progress.etaSeconds,
                        )
                    ),
                )
            }
            }
        } finally {
            stallWatch.cancel()
            writes.close()
            withContext(NonCancellable) { writer.join() }
        }
        processIdByDownloadId.remove(id)

        // Transfer (and yt-dlp's own merge) done: everything from here is local processing.
        if (reported == DownloadStatus.FETCHING_INFO) setState(id, DownloadStatus.DOWNLOADING)
        setState(id, DownloadStatus.PROCESSING)
        if (downloadDao.getById(id)?.status != DownloadStatus.PROCESSING) throw ProviderException.Cancelled()

        // Label with the quality yt-dlp really delivered, not the one that was asked for.
        var labelled = entity
        val actualLabel = result.actualHeight?.let { "${it}p" }
        if (entity.formatKind == FormatKind.VIDEO && actualLabel != null && actualLabel != entity.resolutionLabel) {
            Log.i(TAG, "Download $id: asked for ${entity.resolutionLabel ?: "best"}, got $actualLabel")
            downloadDao.setResolutionLabel(id, actualLabel)
            labelled = entity.copy(resolutionLabel = actualLabel)
        }

        var tempFile = File(result.finalFilePath)
        if (entity.requiresAudioExtraction) {
            if (settings.notificationsEnabled) {
                notifications.notify(DownloadNotifications.notificationIdFor(id), notifications.processingNotification(entity, "Converting to MP3…"))
            }
            val cover = fetchCoverArt(entity.thumbnailUrl, tempDir)
            tempFile = AudioConverter.convertToMp3(
                context = context,
                inputFile = tempFile,
                bitrateKbps = entity.audioBitrateKbps,
                metadata = AudioConverter.Metadata(title = entity.title, artist = entity.uploader, comment = entity.webpageUrl),
                cover = cover,
                key = converterKey(id),
            )
        }
        // Cancelled while converting? Don't publish a file the user no longer wants.
        if (downloadDao.getById(id)?.status != DownloadStatus.PROCESSING) throw ProviderException.Cancelled()

        val sizeBytes = tempFile.length().takeIf { it > 0 }
        val extension = tempFile.extension
        val finalUri = storagePublisher.publish(
            tempFile = tempFile,
            desiredBaseName = entity.fileBaseName,
            category = entity.category,
            mimeType = MimeTypes.forExtension(extension),
            customTreeUri = settings.customDownloadTreeUri,
        )
        // Published: the temp copy is no longer needed. Clean up before reporting COMPLETED.
        deleteTempDir(id)
        // Library first, then COMPLETED: anything that reacts to "completed" (Open, the
        // notification, the Downloads list) already finds the file in the Library and opens it
        // in ENAGELYUCA's player instead of falling back to an external app.
        completionListener?.let { listener ->
            runCatching {
                listener.onDownloadCompleted(
                    CompletedDownload(
                        entity = labelled,
                        fileUri = finalUri,
                        sizeBytes = sizeBytes,
                        extension = extension,
                        mimeType = MimeTypes.forExtension(extension),
                        location = storagePublisher.locationLabel(entity.category, settings.customDownloadTreeUri),
                    )
                )
            }.onFailure { if (it is CancellationException) throw it else Log.w(TAG, "Library couldn't index download $id", it) }
        }
        val completedAt = System.currentTimeMillis()
        downloadDao.markCompleted(id, DownloadStatus.COMPLETED, completedAt, finalUri, sizeBytes, completedAt)
        recordHistory(labelled, DownloadStatus.COMPLETED, finalUri, sizeBytes, extension, error = null, customTreeUri = settings.customDownloadTreeUri)
        notifications.cancel(DownloadNotifications.notificationIdFor(id))
        if (settings.notificationsEnabled && settings.notifyOnCompletion) {
            val updated = downloadDao.getById(id) ?: entity
            notifications.notify(
                DownloadNotifications.completedNotificationIdFor(id),
                notifications.completedNotification(updated, finalUri, MimeTypes.forExtension(extension)),
            )
        }
    }

    /**
     * Downloads the item's thumbnail into its temp dir for embedding as MP3 cover art. Any
     * failure just means "no artwork" — it never fails the download.
     */
    private suspend fun fetchCoverArt(thumbnailUrl: String?, tempDir: File): File? = withContext(Dispatchers.IO) {
        if (thumbnailUrl.isNullOrBlank() || !thumbnailUrl.startsWith("https://")) return@withContext null
        val target = File(tempDir, "cover.img")
        runCatching {
            val connection = URL(thumbnailUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                if (connection.contentLengthLong > 10L * 1024 * 1024) return@runCatching null
                connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
                target.takeIf { it.length() > 0 }
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.i(TAG, "No cover art: ${it.javaClass.simpleName}") }.getOrNull()
    }

    /** The job's processes were stopped by pause/cancel: clean up according to the state the user chose. */
    private suspend fun afterStopped(id: Long) {
        notifications.cancel(DownloadNotifications.notificationIdFor(id))
        val current = downloadDao.getById(id)
        when (current?.status) {
            null, DownloadStatus.CANCELLED -> deleteTempDir(id)
            DownloadStatus.PAUSED ->
                notifications.notify(DownloadNotifications.notificationIdFor(id), notifications.pausedNotification(current))
            else -> Unit
        }
    }

    private suspend fun handleFailure(id: Long, error: ProviderException) {
        // Only an active job can fail; if the user cancelled/paused meanwhile, keep their choice.
        val before = downloadDao.getById(id) ?: return
        if (!before.status.isActive) return
        downloadDao.markFailed(id, DownloadStatus.FAILED, error.message, System.currentTimeMillis())
        notifications.cancel(DownloadNotifications.notificationIdFor(id))
        val updated = downloadDao.getById(id) ?: return
        recordHistory(updated, DownloadStatus.FAILED, fileUri = null, sizeBytes = null, extension = updated.container, error = error.message)
        val settings = settingsDataStore.settings.first()
        if (settings.notificationsEnabled && settings.notifyOnError) {
            notifications.notify(
                DownloadNotifications.completedNotificationIdFor(id),
                notifications.failedNotification(updated),
            )
        }
    }

    /** One History entry per finished attempt: completed, failed or cancelled. */
    private suspend fun recordHistory(
        entity: DownloadEntity,
        status: DownloadStatus,
        fileUri: String?,
        sizeBytes: Long?,
        extension: String?,
        error: String?,
        customTreeUri: String? = null,
    ) {
        runCatching {
            historyDao.insert(
                HistoryEntity(
                    downloadId = entity.id,
                    title = entity.title,
                    sourceUrl = entity.sourceUrl,
                    webpageUrl = entity.webpageUrl,
                    thumbnailUrl = entity.thumbnailUrl,
                    filename = listOfNotNull(entity.fileBaseName, extension?.takeIf { it.isNotBlank() }).joinToString("."),
                    formatLabel = qualityLabel(entity),
                    resolutionLabel = entity.resolutionLabel,
                    category = entity.category,
                    fileSizeBytes = sizeBytes,
                    status = status,
                    fileUri = fileUri,
                    completedAt = System.currentTimeMillis(),
                    errorMessage = error,
                    location = if (status == DownloadStatus.COMPLETED) storagePublisher.locationLabel(entity.category, customTreeUri) else null,
                )
            )
        }.onFailure { Log.w(TAG, "Couldn't write history for ${entity.id}", it) }
    }

    private suspend fun deleteTempDir(id: Long) = withContext(Dispatchers.IO) {
        tempDirFor(id).deleteRecursively()
    }

    private fun qualityLabel(entity: DownloadEntity): String = when (entity.formatKind) {
        FormatKind.AUDIO -> when {
            !entity.requiresAudioExtraction -> entity.container?.uppercase() ?: "Audio"
            else -> entity.audioBitrateKbps?.let { "MP3 $it kbps" } ?: "MP3 VBR"
        }
        FormatKind.VIDEO -> entity.container?.uppercase() ?: "Video"
    }
}
