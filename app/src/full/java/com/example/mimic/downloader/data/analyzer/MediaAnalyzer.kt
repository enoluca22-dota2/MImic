package com.example.mimic.downloader.data.analyzer

import com.example.mimic.downloader.core.UrlValidator
import com.example.mimic.downloader.data.model.AnalysisResult
import com.example.mimic.downloader.data.platform.DetectedPlatform
import com.example.mimic.downloader.data.platform.Platforms
import com.example.mimic.downloader.data.provider.MediaProvider
import com.example.mimic.downloader.data.provider.ProviderException
import com.example.mimic.downloader.diagnostics.AnalysisTrace
import com.example.mimic.downloader.diagnostics.DownloadDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Entry point for every link (typed, pasted, shared or copied):
 *
 *   Downloader
 *   ├── YouTubeProvider
 *   └── TikTokProvider
 *
 * The link is matched against the two providers first; anything that isn't a supported YouTube or
 * TikTok media link is rejected here, before the extractor runs — no generic/website extraction.
 * Supported links are fetched by the shared yt-dlp engine ([MediaProvider]); downloads always go
 * through the one download queue.
 */
class MediaAnalyzer(
    private val mediaProvider: MediaProvider,
    private val diagnostics: DownloadDiagnostics? = null,
) {

    fun detect(url: String): DetectedPlatform = Platforms.detect(url)

    /**
     * With Download Diagnostics on, the whole analysis runs with an [AnalysisTrace] in its
     * coroutine context and the outcome is recorded (cancelled analyses too, as "Cancelled").
     */
    suspend fun analyze(rawUrl: String): Result<AnalysisResult> {
        val trace = diagnostics?.trace(rawUrl) ?: return analyzeTraced(rawUrl, null)
        val result = try {
            withContext(trace) { analyzeTraced(rawUrl, trace) }
        } catch (e: CancellationException) {
            diagnostics.finish(trace, ProviderException.Cancelled())
            throw e
        }
        diagnostics.finish(trace, result.exceptionOrNull())
        return result
    }

    private suspend fun analyzeTraced(rawUrl: String, trace: AnalysisTrace?): Result<AnalysisResult> {
        val t0 = System.currentTimeMillis()
        val url = UrlValidator.normalize(rawUrl) ?: UrlValidator.extractFirstUrl(rawUrl)
        if (url == null) {
            trace?.stage("parse link", t0, "no link found in the text")
            return Result.failure(ProviderException.InvalidUrl())
        }
        val detected = detect(url)
        trace?.parse = if (detected.isSupported) {
            listOfNotNull(detected.displayName, detected.kind?.label, detected.playlistContext?.let { "inside a playlist" }).joinToString(" · ")
        } else {
            "not a supported YouTube/TikTok media link (${detected.displayName})"
        }
        trace?.stage("parse link", t0, if (detected.isSupported) "ok" else "unsupported")
        if (!detected.isSupported) return Result.failure(ProviderException.UnsupportedUrl())
        return try {
            Result.success(mediaProvider.analyze(url, detected))
        } catch (e: ProviderException) {
            Result.failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(ProviderException.classify(e.message, e))
        }
    }
}
