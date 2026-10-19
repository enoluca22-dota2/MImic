package com.example.mimic.downloader.data.provider

import com.example.mimic.downloader.data.model.AnalysisResult
import com.example.mimic.downloader.data.platform.DetectedPlatform

/**
 * Abstraction over whatever extraction/download backend the app uses. The UI and repositories only
 * ever depend on this interface, so the underlying implementation (currently yt-dlp via
 * youtubedl-android) can be swapped without touching ViewModels or Compose screens.
 */
interface MediaProvider {
    /**
     * Analyzes a URL with the extractor and returns real, provider-reported metadata: a single
     * item with its formats, or a collection (playlist/album/multi-item post). Never fabricated.
     */
    suspend fun analyze(url: String, detected: DetectedPlatform): AnalysisResult

    /** Streams a single download. Throws [ProviderException.Cancelled] if [processId] is destroyed mid-run. */
    suspend fun download(
        job: DownloadJobRequest,
        processId: String,
        onProgress: (percent: Float, etaSeconds: Long?, rawLine: String) -> Unit,
    ): DownloadResult

    /** Best-effort kill of an in-flight download started with [processId]. */
    fun cancel(processId: String): Boolean
}

data class DownloadJobRequest(
    val sourceUrl: String,
    val formatId: String,
    val formatKind: com.example.mimic.downloader.data.model.FormatKind,
    val requiresAudioMerge: Boolean,
    val requiresAudioExtraction: Boolean,
    val outputDirectory: String,
    val outputFileNameNoExt: String,
    val container: String?,
    /** Height in pixels of the format the user picked, when known — used to build a same-quality
     *  fallback selector for when the exact format id isn't offered by the client used at
     *  download time (which can differ from the client used to list formats). */
    val heightPx: Int?,
    /** 1-based item inside [sourceUrl] for collection items that have no URL of their own. */
    val playlistIndex: Int? = null,
    /** yt-dlp --limit-rate value (e.g. "2M"); null = unlimited. */
    val rateLimit: String? = null,
    /** Second try with YouTube's alternate player clients (see DownloadRequestBuilder). */
    val alternateClients: Boolean = false,
)

data class DownloadResult(
    val finalFilePath: String,
    /** Height of the video yt-dlp actually delivered, when it reported one. */
    val actualHeight: Int? = null,
)
