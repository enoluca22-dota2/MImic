package com.example.mimic.downloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mimic.downloader.DownloaderContainer
import com.example.mimic.downloader.core.UrlValidator
import com.example.mimic.downloader.data.analyzer.AnalysisErrorInfo
import com.example.mimic.downloader.data.analyzer.toAnalysisError
import com.example.mimic.downloader.data.local.datastore.AppSettings
import com.example.mimic.downloader.data.local.datastore.NetworkPolicy
import com.example.mimic.downloader.data.local.datastore.SpeedLimit
import com.example.mimic.downloader.data.local.db.DownloadEntity
import com.example.mimic.downloader.data.local.db.HistoryEntity
import com.example.mimic.downloader.data.model.AnalysisResult
import com.example.mimic.downloader.data.model.DownloadCategory
import com.example.mimic.downloader.data.model.DownloadStatus
import com.example.mimic.downloader.data.model.MediaFormat
import com.example.mimic.downloader.data.model.MediaInfo
import com.example.mimic.downloader.data.model.PlaylistEntry
import com.example.mimic.downloader.data.model.PlaylistInfo
import com.example.mimic.downloader.data.model.QuickFormat
import com.example.mimic.downloader.data.model.suggestedCategory
import com.example.mimic.downloader.data.platform.DetectedPlatform
import com.example.mimic.downloader.data.platform.PlaylistContext
import com.example.mimic.downloader.data.platform.YouTubeListType
import com.example.mimic.downloader.data.platform.YouTubeProvider
import com.example.mimic.downloader.data.provider.AnalysisProgress
import com.example.mimic.downloader.data.provider.ProviderException
import com.example.mimic.downloader.data.provider.YtDlpUpdater
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Where the analyzer is for the link(s) in the field (from ENAGELYUCA's Home screen). */
sealed interface DetectPhase {
    data object Idle : DetectPhase
    /** Until the first playlist item is listed (then the list itself is shown, still [Detected.loading]). */
    data class Analyzing(val detected: DetectedPlatform) : DetectPhase

    /** `watch?v=…&list=…`: the user picks "This video" or "Whole playlist" before anything is fetched. */
    data class ChooseScope(val url: String, val detected: DetectedPlatform) : DetectPhase

    /**
     * A finished analysis. For a playlist, [selection] holds the [PlaylistEntry.key]s that are
     * ticked and [quickFormat] the chosen format; both live here (not in the UI) so they survive
     * scrolling, format changes and recomposition. [note] explains an ignored `list=` parameter.
     * [loading]: the playlist is still being read; [result] holds the songs listed so far and more
     * are appended as yt-dlp reads them (nothing can be downloaded until the list is complete).
     * [tickNew]: songs that arrive are ticked (a playlist, until "Deselect all"; never in a Mix).
     */
    data class Detected(
        val result: AnalysisResult,
        val existing: HistoryEntity? = null,
        val selection: Set<String> = emptySet(),
        val quickFormat: QuickFormat = QuickFormat.AudioMp3(),
        val note: String? = null,
        val loading: Boolean = false,
        val tickNew: Boolean = true,
    ) : DetectPhase {
        val playlist: PlaylistInfo? get() = (result as? AnalysisResult.Collection)?.playlist

        /** What "Download selected" queues: ticked, selectable, each video once, in list order. */
        val selectedEntries: List<PlaylistEntry>
            get() = playlist?.entries.orEmpty().filter { it.isSelectable && it.key in selection }.distinctBy { it.key }
    }
    data class Error(val url: String, val detected: DetectedPlatform?, val info: AnalysisErrorInfo) : DetectPhase
    data class Batch(val items: List<BatchItem>) : DetectPhase
}

enum class BatchStatus { NEEDS_CHOICE, ANALYZING, READY, FAILED }

data class BatchItem(
    val url: String,
    val detected: DetectedPlatform,
    val status: BatchStatus = BatchStatus.ANALYZING,
    val result: AnalysisResult? = null,
    val error: String? = null,
    val alreadyDownloaded: Boolean = false,
) {
    val title: String?
        get() = when (val r = result) {
            is AnalysisResult.Single -> r.media.title
            is AnalysisResult.Collection -> "${r.playlist.title} (${r.playlist.entries.size} items)"
            null -> null
        }
}

private val DetectPhase.isLoadingPlaylist: Boolean get() = this is DetectPhase.Detected && loading

/**
 * The selection after more of a playlist arrived ([next] replaces [prev], the list shown so far):
 * what the user ticked or unticked is kept; songs seen for the first time are ticked when
 * [tickNew], and in a Mix only the song it was started from ([seed]) is.
 */
internal fun mergeSelection(selection: Set<String>, prev: PlaylistInfo?, next: PlaylistInfo, tickNew: Boolean, seed: String?): Set<String> {
    val known = prev?.entries.orEmpty().mapTo(HashSet()) { it.key }
    val selectable = next.selectable
    val keys = selectable.mapTo(HashSet()) { it.key }
    val fresh = selectable.filter { e ->
        e.key !in known && if (next.isMixPreview) seed != null && e.videoId == seed else tickNew
    }
    return selection.filterTo(HashSet()) { it in keys } + fresh.map { it.key }
}

data class DownloaderUiState(
    val urlText: String = "",
    val phase: DetectPhase = DetectPhase.Idle,
) {
    val urls: List<String> get() = UrlValidator.extractAllUrls(urlText)
    val urlHint: String?
        get() = if (urlText.isNotBlank() && urls.isEmpty()) "Enter a full link starting with https://" else null
    val canAnalyze: Boolean
        get() = phase !is DetectPhase.Analyzing && phase !is DetectPhase.ChooseScope && !phase.isLoadingPlaylist && urls.isNotEmpty() &&
            (phase !is DetectPhase.Batch || phase.items.none { it.status == BatchStatus.ANALYZING })
}

/**
 * Downloads tab: link analysis, format choice and the queue. All jobs go through the one
 * persisted queue (DownloadEngine), exactly as in ENAGELYUCA.
 */
class DownloaderViewModel(app: Application) : AndroidViewModel(app) {

    private val c = DownloaderContainer.get(app)
    private val analyzer = c.analyzer
    private val downloads = c.downloadRepository
    private val history = c.historyRepository

    private val _state = MutableStateFlow(DownloaderUiState())
    val state: StateFlow<DownloaderUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val queue: StateFlow<List<DownloadEntity>> = downloads.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val historyEntries: StateFlow<List<HistoryEntity>> = history.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val waitingForWifi: StateFlow<Boolean> = downloads.waitingForWifi
    val settings: StateFlow<AppSettings> = c.settingsDataStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private var analyzeJob: Job? = null

    /** Identifies the analysis whose partial playlists may still be shown (yt-dlp's output thread). */
    @Volatile private var currentRun: Any? = null

    // ---- Link field / analysis -------------------------------------------------------------

    fun onUrlChanged(text: String) {
        _state.update {
            val keep = it.phase is DetectPhase.Analyzing || it.phase.isLoadingPlaylist
            it.copy(urlText = text, phase = if (keep) it.phase else DetectPhase.Idle)
        }
    }

    /** Shared/pasted text often contains more than the link ("Check this out: https://…"). */
    fun consumeSharedText(text: String) {
        val urls = UrlValidator.extractAllUrls(text)
        analyzeJob?.cancel()
        if (urls.isEmpty()) {
            _state.update {
                it.copy(phase = DetectPhase.Error(text, null, AnalysisErrorInfo("No link found", "That text doesn't contain a YouTube or TikTok link.", emptySet())))
            }
            return
        }
        _state.update { it.copy(urlText = urls.joinToString("\n"), phase = DetectPhase.Idle) }
        analyze()
    }

    fun analyze() {
        val current = _state.value
        if (current.phase is DetectPhase.Analyzing) return
        val urls = current.urls
        when {
            urls.isEmpty() -> _state.update {
                it.copy(phase = DetectPhase.Error(current.urlText, null, ProviderException.InvalidUrl().toAnalysisError(null)))
            }
            urls.size == 1 -> analyzeSingle(urls.single())
            else -> analyzeBatch(urls)
        }
    }

    private fun analyzeSingle(url: String) {
        val detected = analyzer.detect(url)
        if (detected.needsPlaylistChoice) {
            _state.update { it.copy(urlText = url, phase = DetectPhase.ChooseScope(url, detected)) }
            return
        }
        // watch?v=…&list=<channel/private list>: only the video is analyzed, and the user is told why.
        val note = when (detected.ignoredList?.listType) {
            null -> null
            YouTubeListType.CHANNEL_UPLOADS -> "This link came from a channel's uploads list. Whole channels aren't downloaded, so only this video is shown."
            else -> "This link came from a private list (Liked / Watch later), so only this video is shown."
        }
        runAnalysis(url, url, detected, focus = null, note = note)
    }

    fun chooseCurrentVideo() {
        val choice = _state.value.phase as? DetectPhase.ChooseScope ?: return
        runAnalysis(choice.url, choice.url, choice.detected, focus = null)
    }

    fun choosePlaylist() {
        val choice = _state.value.phase as? DetectPhase.ChooseScope ?: return
        val ctx = choice.detected.playlistContext ?: return
        runAnalysis(choice.url, ctx.playlistUrl, analyzer.detect(ctx.playlistUrl), focus = ctx)
    }

    private fun runAnalysis(fieldUrl: String, url: String, detected: DetectedPlatform, focus: PlaylistContext?, note: String? = null) {
        analyzeJob?.cancel()
        val run = Any()
        currentRun = run
        _state.update { it.copy(urlText = fieldUrl, phase = DetectPhase.Analyzing(detected)) }
        val seed = focus?.videoId ?: detected.listId?.let { YouTubeProvider.mixSeed(it) }
        // A playlist is shown while it is read: the songs listed so far, more appended as they come.
        val progress = AnalysisProgress { partial ->
            _state.update { s ->
                if (currentRun !== run) return@update s
                val phase = when (val p = s.phase) {
                    is DetectPhase.Analyzing -> {
                        val tickNew = !partial.isMixPreview
                        DetectPhase.Detected(
                            AnalysisResult.Collection(partial, detected, focus),
                            selection = mergeSelection(emptySet(), null, partial, tickNew, seed),
                            note = note,
                            loading = true,
                            tickNew = tickNew,
                        )
                    }
                    is DetectPhase.Detected -> {
                        val r = p.result as? AnalysisResult.Collection
                        if (!p.loading || r == null) return@update s
                        p.copy(result = r.copy(playlist = partial), selection = mergeSelection(p.selection, r.playlist, partial, p.tickNew, seed))
                    }
                    else -> return@update s
                }
                s.copy(phase = phase)
            }
        }
        analyzeJob = viewModelScope.launch(progress) {
            val outcome = analyzer.analyze(url)
            if (currentRun !== run) return@launch
            currentRun = null
            outcome.fold(
                onSuccess = { result ->
                    val withFocus = if (result is AnalysisResult.Collection && focus != null) result.copy(focus = focus) else result
                    // Duplicate check: the same media downloaded before, with its file still there.
                    val existing = (withFocus as? AnalysisResult.Single)?.let { findExisting(it.media) }
                    _state.update { s ->
                        // The complete list replaces the one shown while loading; the user's ticks are kept.
                        val shown = (s.phase as? DetectPhase.Detected)?.takeIf { it.loading }
                        val pl = (withFocus as? AnalysisResult.Collection)?.playlist
                        val tickNew = shown?.tickNew ?: (pl?.isMixPreview != true)
                        val selection = pl?.let { mergeSelection(shown?.selection.orEmpty(), shown?.playlist, it, tickNew, seed) }.orEmpty()
                        s.copy(
                            phase = DetectPhase.Detected(
                                withFocus, existing, selection,
                                quickFormat = shown?.quickFormat ?: QuickFormat.AudioMp3(),
                                note = note,
                                tickNew = tickNew,
                            ),
                        )
                    }
                },
                onFailure = { e ->
                    if (e !is ProviderException.Cancelled) {
                        _state.update { it.copy(phase = DetectPhase.Error(url, detected, e.toAnalysisError(detected))) }
                    }
                },
            )
        }
    }

    /** When a Mix can't be previewed: analyze just the song it was started from. */
    fun openMixSong() {
        val err = _state.value.phase as? DetectPhase.Error ?: return
        val seed = err.detected?.listId?.let { YouTubeProvider.mixSeed(it) } ?: return
        val url = "https://www.youtube.com/watch?v=$seed"
        runAnalysis(url, url, analyzer.detect(url), focus = null)
    }

    private fun analyzeBatch(urls: List<String>) {
        // Several links at once: a Mix is never expanded here (its songs have to be chosen one
        // by one); a video opened from a Mix is just that video.
        val items = urls.map { url ->
            val detected = analyzer.detect(url)
            when {
                detected.isMixPreview -> BatchItem(url, detected, BatchStatus.FAILED, error = "YouTube Mix — open this link on its own to choose its songs")
                detected.needsPlaylistChoice && detected.playlistContext?.isMix != true -> BatchItem(url, detected, BatchStatus.NEEDS_CHOICE)
                else -> BatchItem(url, detected, BatchStatus.ANALYZING)
            }
        }
        _state.update { it.copy(phase = DetectPhase.Batch(items)) }
        analyzeJob = viewModelScope.launch {
            analyzeBatchItems(items.filter { it.status == BatchStatus.ANALYZING }.map { it.url to it.url })
        }
    }

    fun chooseBatchScope(url: String, wholePlaylist: Boolean) {
        val batch = _state.value.phase as? DetectPhase.Batch ?: return
        val item = batch.items.firstOrNull { it.url == url && it.status == BatchStatus.NEEDS_CHOICE } ?: return
        val target = if (wholePlaylist) item.detected.playlistContext?.playlistUrl ?: url else url
        _state.update { s -> s.copy(phase = DetectPhase.Batch(batch.items.map { if (it.url == url) it.copy(status = BatchStatus.ANALYZING) else it })) }
        viewModelScope.launch { analyzeBatchItems(listOf(url to target)) }
    }

    private suspend fun analyzeBatchItems(targets: List<Pair<String, String>>) = coroutineScope {
        val permits = Semaphore(2)
        targets.map { (url, target) ->
            async {
                permits.withPermit {
                    val updated = analyzer.analyze(target).fold(
                        onSuccess = { r ->
                            val existing = (r as? AnalysisResult.Single)?.let { findExisting(it.media) }
                            BatchItem(url, analyzer.detect(url), BatchStatus.READY, r, alreadyDownloaded = existing != null)
                        },
                        onFailure = { e ->
                            val detected = analyzer.detect(url)
                            BatchItem(url, detected, BatchStatus.FAILED, error = e.toAnalysisError(detected).title)
                        },
                    )
                    _state.update { s ->
                        val batch = s.phase as? DetectPhase.Batch ?: return@update s
                        s.copy(phase = DetectPhase.Batch(batch.items.map { if (it.url == url) updated else it }))
                    }
                }
            }
        }.awaitAll()
    }

    fun cancelAnalyze() {
        stopAnalysis()
        _state.update { it.copy(phase = DetectPhase.Idle) }
    }

    private fun stopAnalysis() {
        currentRun = null
        analyzeJob?.cancel()
        analyzeJob = null
    }

    fun reset(clearUrl: Boolean) {
        // Closing a playlist that is still loading stops reading it.
        if (_state.value.phase.isLoadingPlaylist) stopAnalysis()
        _state.update { it.copy(phase = DetectPhase.Idle, urlText = if (clearUrl) "" else it.urlText) }
    }

    private suspend fun findExisting(media: MediaInfo): HistoryEntity? =
        history.findExistingDownload(media.webpageUrl, media.sourceUrl)

    // ---- Queueing ------------------------------------------------------------------------------

    fun download(media: MediaInfo, format: MediaFormat, fileBaseName: String, startNow: Boolean) {
        viewModelScope.launch {
            downloads.enqueue(media, format, format.kind.suggestedCategory(), fileBaseName.ifBlank { media.title }, startNow)
            _messages.tryEmit(if (startNow) "Added to downloads" else "Saved for later — start it from the queue")
            reset(clearUrl = true)
        }
    }

    // ---- Playlist picker -------------------------------------------------------------------------

    private fun updatePicker(block: (DetectPhase.Detected, PlaylistInfo) -> DetectPhase.Detected) {
        _state.update { s ->
            val d = s.phase as? DetectPhase.Detected
            val pl = d?.playlist
            if (d != null && pl != null) s.copy(phase = block(d, pl)) else s
        }
    }

    fun toggleEntry(key: String) = updatePicker { d, pl ->
        if (pl.selectable.none { it.key == key }) d
        else d.copy(selection = if (key in d.selection) d.selection - key else d.selection + key)
    }

    /** While a playlist is loading, the songs still to come follow the last Select / Deselect all. */
    fun selectAll() = updatePicker { d, pl -> d.copy(selection = pl.selectable.map { it.key }.toSet(), tickNew = !pl.isMixPreview) }

    fun deselectAll() = updatePicker { d, _ -> d.copy(selection = emptySet(), tickNew = false) }

    /** Changing the format keeps the selection. */
    fun setQuickFormat(choice: QuickFormat) = updatePicker { d, _ -> d.copy(quickFormat = choice) }

    /**
     * Queues exactly the ticked songs. Songs already waiting or downloading in the queue, and songs
     * already downloaded (file still on the phone), are skipped and counted in the message.
     */
    fun downloadSelected() {
        val d = _state.value.phase as? DetectPhase.Detected ?: return
        val pl = d.playlist ?: return
        val chosen = d.selectedEntries
        if (chosen.isEmpty() || d.loading) return
        viewModelScope.launch {
            val inQueue = downloads.observeAll().first().filter { !it.status.isTerminal }.map { it.sourceUrl }.toSet()
            val (already, rest) = chosen.partition { it.url != null && it.url in inQueue }
            val (downloaded, fresh) = rest.partition { e -> e.url != null && history.findExistingDownload(e.url) != null }
            val n = if (fresh.isNotEmpty()) downloads.enqueuePlaylist(pl.sourceUrl, pl.id, pl.title, fresh, d.quickFormat) else 0
            val skipped = (if (already.isNotEmpty()) " · ${already.size} already in the queue" else "") +
                (if (downloaded.isNotEmpty()) " · ${downloaded.size} already downloaded" else "")
            _messages.tryEmit("Queued $n of ${pl.selectable.size} from \"${pl.title}\"$skipped")
            reset(clearUrl = true)
        }
    }

    fun downloadAllReady(choice: QuickFormat, includeAlreadyDownloaded: Boolean) {
        val batch = _state.value.phase as? DetectPhase.Batch ?: return
        viewModelScope.launch {
            var queued = 0
            batch.items.filter { it.status == BatchStatus.READY && (includeAlreadyDownloaded || !it.alreadyDownloaded) }.forEach { item ->
                when (val r = item.result) {
                    is AnalysisResult.Single -> {
                        downloads.enqueueQuick(r.media.webpageUrl, r.media.title, r.media.thumbnailUrl, choice, uploader = r.media.uploader)
                        queued++
                    }
                    is AnalysisResult.Collection ->
                        queued += downloads.enqueuePlaylist(r.playlist.sourceUrl, r.playlist.id, r.playlist.title, r.playlist.selectable, choice)
                    null -> Unit
                }
            }
            _messages.tryEmit("Queued $queued item${if (queued == 1) "" else "s"}")
            reset(clearUrl = true)
        }
    }

    // ---- Queue actions ---------------------------------------------------------------------------

    fun pause(id: Long) = viewModelScope.launch { downloads.pause(id) }
    fun resume(id: Long) = viewModelScope.launch { downloads.resume(id) }
    fun cancel(id: Long) = viewModelScope.launch { downloads.cancel(id) }
    fun retry(id: Long) = viewModelScope.launch { downloads.retry(id) }
    fun remove(id: Long) = viewModelScope.launch { downloads.deleteRecord(id) }
    fun pauseAll() = viewModelScope.launch { downloads.pauseAll() }
    fun resumeAll() = viewModelScope.launch { downloads.resumeAll() }
    fun retryAllFailed() = viewModelScope.launch { downloads.retryAllFailed() }

    /** Removes finished rows from the queue; files and history stay. */
    fun clearFinished() = viewModelScope.launch {
        queue.value.filter { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.CANCELLED }.forEach { downloads.deleteRecord(it.id) }
    }

    fun deleteHistoryEntry(id: Long) = viewModelScope.launch { history.delete(id) }

    // ---- Settings ------------------------------------------------------------------------------

    fun setConcurrent(n: Int) = viewModelScope.launch { c.settingsDataStore.setConcurrentDownloads(n) }
    fun setWifiOnly(on: Boolean) = viewModelScope.launch {
        c.settingsDataStore.setNetworkPolicy(if (on) NetworkPolicy.WIFI_ONLY else NetworkPolicy.ANY_NETWORK)
    }
    fun setSpeedLimit(limit: SpeedLimit) = viewModelScope.launch { c.settingsDataStore.setSpeedLimit(limit) }

    private val _ytDlpUpdating = MutableStateFlow(false)
    val ytDlpUpdating: StateFlow<Boolean> = _ytDlpUpdating.asStateFlow()

    /** "Update the extractor": YouTube changes often; a stale yt-dlp is the usual cause of failures. */
    fun updateYtDlp() {
        if (_ytDlpUpdating.value) return
        _ytDlpUpdating.value = true
        viewModelScope.launch {
            val outcome = runCatching { c.engineReady.await(); c.ytDlpUpdater.update() }.getOrNull()
            _ytDlpUpdating.value = false
            _messages.tryEmit(
                when (outcome) {
                    YtDlpUpdater.Outcome.UPDATED -> "yt-dlp updated"
                    YtDlpUpdater.Outcome.ALREADY_CURRENT -> "yt-dlp is already up to date"
                    YtDlpUpdater.Outcome.BUSY -> "Wait until downloads finish, then try again"
                    YtDlpUpdater.Outcome.OFFLINE -> "No internet connection"
                    else -> "Couldn't update yt-dlp — the current version is kept"
                },
            )
        }
    }
}
