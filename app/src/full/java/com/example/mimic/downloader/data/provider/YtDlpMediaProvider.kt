package com.example.mimic.downloader.data.provider

import android.util.Log
import com.example.mimic.downloader.core.NetworkMonitor
import com.example.mimic.downloader.core.UrlValidator
import com.example.mimic.downloader.data.model.AnalysisResult
import com.example.mimic.downloader.data.platform.DetectedPlatform
import com.example.mimic.downloader.diagnostics.AnalysisTrace
import com.example.mimic.downloader.diagnostics.DiagnosticText
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The shared extraction/download engine for YouTube and TikTok (yt-dlp via youtubedl-android).
 * It only ever receives links the YouTube/TikTok providers accepted (see MediaAnalyzer).
 *
 * [awaitEngineReady] suspends until the bundled python/ffmpeg binaries are unpacked (done in the
 * background at app start); calling yt-dlp before that throws, which used to race with links
 * shared into the app on a cold start.
 */
class YtDlpMediaProvider(
    private val networkMonitor: NetworkMonitor,
    private val awaitEngineReady: suspend () -> Unit,
) : MediaProvider {

    private val activeAnalyses = AtomicInteger(0)

    /** True while a link is being analyzed (the updater must not swap yt-dlp underneath it). */
    val isAnalyzing: Boolean get() = activeAnalyses.get() > 0

    override suspend fun analyze(url: String, detected: DetectedPlatform) = withContext(Dispatchers.IO) {
        val normalized = UrlValidator.normalize(url) ?: throw ProviderException.InvalidUrl()
        // Download Diagnostics (4.3.1): present only while recording is on; then each step is
        // timed and yt-dlp runs with -v so the report shows its version, clients and HTTP codes.
        val trace = coroutineContext[AnalysisTrace]
        val tStart = System.currentTimeMillis()
        if (!networkMonitor.isOnline()) {
            trace?.stage("check internet", tStart, "offline")
            throw ProviderException.NoInternet()
        }
        trace?.stage("check internet", tStart, "online")
        val processId = "an-${UUID.randomUUID()}"
        // Counted as busy BEFORE waiting, so a yt-dlp self-update can't start in between and
        // replace the binary under this analysis (MImic 4.0 fix: "bad local file header").
        activeAnalyses.incrementAndGet()
        try {
            val tEngine = System.currentTimeMillis()
            try {
                awaitEngineReady()
            } catch (e: Exception) {
                trace?.stage("wait for engine", tEngine, "failed: ${e.javaClass.simpleName}")
                throw e
            }
            trace?.stage("wait for engine", tEngine, "ready")
            // One call for every kind of link: -J prints a single JSON document that is either one
            // item with its full format list or a collection; --flat-playlist keeps collections
            // fast (no per-item format lookups); --no-playlist makes "video inside a playlist" URLs
            // resolve to that video. Detection deliberately does NOT restrict player_client, so
            // YouTube reports its full resolution ladder; the 403-dodging client fallback is only
            // applied at download time (DownloadRequestBuilder).
            val request = YoutubeDLRequest(normalized).apply {
                addOption("-J")
                addOption("--flat-playlist")
                addOption("--no-playlist")
                addOption("--retries", "3")
                addOption("--socket-timeout", "20")
                if (trace != null) addOption("-v")
            }
            // Cancellable (leaving the screen kills yt-dlp) and bounded: a stuck extractor
            // surfaces as a connection error instead of an endless spinner.
            val tRun = System.currentTimeMillis()
            val response = try {
                withTimeout(ANALYZE_TIMEOUT_MS) {
                    runKillable(processId) { YoutubeDL.execute(request, processId) }
                }
            } catch (e: TimeoutCancellationException) {
                YoutubeDL.destroyProcessById(processId)
                trace?.stage("run yt-dlp", tRun, "timed out after ${ANALYZE_TIMEOUT_MS / 1000} s")
                throw ProviderException.Timeout("analysis exceeded ${ANALYZE_TIMEOUT_MS / 1000}s")
            } catch (e: CancellationException) {
                trace?.stage("run yt-dlp", tRun, "cancelled")
                throw e
            } catch (e: Exception) {
                trace?.let {
                    it.stderr = e.message
                    it.exitInfo = "failed (${e.javaClass.simpleName})"
                    it.stage("run yt-dlp", tRun, "failed")
                }
                throw e
            }
            trace?.let {
                it.stderr = response.err
                it.exitInfo = "exit code ${response.exitCode}, ${response.elapsedTime} ms"
                it.stage("run yt-dlp", tRun, "finished")
            }
            val tRead = System.currentTimeMillis()
            val mapper = YoutubeDL.objectMapper
            val root = mapper.readTree(response.out.trim())
                ?: throw ProviderException.NoFormatsAvailable("empty extractor output")
            when (val parsed = AnalysisJsonParser.parse(root, normalized, mapper)) {
                is AnalysisJsonParser.Parsed.Single -> {
                    val media = FormatMapper.toMediaInfo(normalized, parsed.info)
                    val best = media.videoFormats.mapNotNull { it.heightPx }.maxOrNull()
                    trace?.formats = "${media.audioFormats.size} audio, ${media.videoFormats.size} video" + (best?.let { " (best ${it}p)" } ?: "")
                    if (media.videoFormats.isEmpty() && media.audioFormats.isEmpty()) {
                        trace?.stage("read formats", tRead, "no formats")
                        throw ProviderException.NoFormatsAvailable()
                    }
                    trace?.stage("read formats", tRead, "ok")
                    AnalysisResult.Single(media, detected)
                }
                is AnalysisJsonParser.Parsed.Collection -> {
                    trace?.formats = "collection with ${parsed.playlist.entries.size} items"
                    trace?.stage("read formats", tRead, if (parsed.playlist.entries.isEmpty()) "empty collection" else "ok")
                    if (parsed.playlist.entries.isEmpty()) throw ProviderException.NoFormatsAvailable("empty collection")
                    AnalysisResult.Collection(parsed.playlist, detected)
                }
            }
        } catch (e: ProviderException) {
            Log.w(TAG, "analyze($normalized): ${e.javaClass.simpleName} ${e.detail ?: e.message}", e)
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: YoutubeDL.CanceledException) {
            throw ProviderException.Cancelled()
        } catch (e: IOException) {
            Log.w(TAG, "analyze: I/O failure", e)
            throw ProviderException.NetworkError(e.message, e)
        } catch (e: Exception) {
            // With diagnostics on, the text also holds yt-dlp's -v "[debug]" lines; they are left
            // out here so the error is classified (and logged) exactly as in a normal run.
            val text = DiagnosticText.withoutDebug(e.message)
            Log.w(TAG, "analyze failed: $text", e)
            throw ProviderException.classify(text, e)
        } finally {
            activeAnalyses.decrementAndGet()
        }
    }

    /**
     * Runs a blocking yt-dlp call so that cancelling the coroutine destroys the process (and
     * its children) instead of leaving it running in the background.
     */
    private suspend fun <T> runKillable(processId: String, block: () -> T): T = coroutineScope {
        val call = async(Dispatchers.IO) { block() }
        try {
            call.await()
        } catch (e: CancellationException) {
            YoutubeDL.destroyProcessById(processId)
            throw e
        }
    }

    override suspend fun download(
        job: DownloadJobRequest,
        processId: String,
        onProgress: (percent: Float, etaSeconds: Long?, rawLine: String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (!networkMonitor.isOnline()) throw ProviderException.NoInternet()
        awaitEngineReady()
        File(job.outputDirectory).mkdirs()
        val request = DownloadRequestBuilder.build(job)
        try {
            val response = YoutubeDL.execute(request, processId) { progress, eta, line ->
                onProgress(
                    progress.takeIf { it >= 0f } ?: -1f,
                    eta.takeIf { it >= 0L },
                    line,
                )
            }
            val finalPath = response.out.lineSequence()
                .map { it.trim() }
                .lastOrNull { it.isNotEmpty() && File(it).exists() }
                ?: findNewestFileInDirectory(job.outputDirectory, job.outputFileNameNoExt)
                ?: throw ProviderException.Unknown("Download finished but the output file could not be located.")
            // Without a working ffmpeg (e.g. devices with 16 KB memory pages, where the bundled
            // build can't load) yt-dlp downloads the video and audio streams but doesn't merge
            // them. Never publish one half of it as the finished video.
            if (job.requiresAudioMerge && isUnmerged(finalPath, response.err)) {
                throw ProviderException.MergeUnsupported("unmerged output: ${File(finalPath).name}")
            }
            val delivered = response.out.lineSequence()
                .map { it.trim() }
                .lastOrNull { it.startsWith(DownloadRequestBuilder.FINAL_PREFIX) }
                ?.removePrefix(DownloadRequestBuilder.FINAL_PREFIX)?.trim()
            DownloadResult(
                finalFilePath = finalPath,
                actualHeight = delivered?.substringBefore('|')?.toIntOrNull()?.takeIf { it > 0 },
            )
        } catch (e: ProviderException) {
            throw e
        } catch (e: YoutubeDL.CanceledException) {
            throw ProviderException.Cancelled()
        } catch (e: InterruptedException) {
            throw ProviderException.Cancelled()
        } catch (e: IOException) {
            Log.w(TAG, "download: I/O failure", e)
            throw ProviderException.classify(e.message, e).let {
                if (it is ProviderException.ExtractorError) ProviderException.NetworkError(e.message, e) else it
            }
        } catch (e: Exception) {
            Log.w(TAG, "download failed: ${e.message}", e)
            throw ProviderException.classify(e.message, e)
        }
    }

    override fun cancel(processId: String): Boolean = YoutubeDL.destroyProcessById(processId)

    /** yt-dlp left per-stream files ("media.f137.mp4") because it couldn't merge them. */
    private fun isUnmerged(finalPath: String, stderr: String?): Boolean =
        UNMERGED_NAME.matches(File(finalPath).name) ||
            stderr.orEmpty().contains("formats won't be merged") ||
            stderr.orEmpty().contains("merging of multiple formats but ffmpeg is not installed")

    private fun findNewestFileInDirectory(directory: String, baseName: String): String? {
        val dir = File(directory)
        return dir.listFiles { file ->
            file.isFile && file.nameWithoutExtension.startsWith(baseName) && !file.name.endsWith(".part")
        }
            ?.maxByOrNull { it.lastModified() }
            ?.absolutePath
    }

    private companion object {
        const val TAG = "YtDlpMediaProvider"
        const val ANALYZE_TIMEOUT_MS = 90_000L
        val UNMERGED_NAME = Regex("""^.+\.f[0-9A-Za-z_-]+\.[0-9A-Za-z]+$""")
    }
}
