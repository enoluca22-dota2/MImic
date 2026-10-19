package com.example.mimic.downloader.data.analyzer

import com.example.mimic.downloader.core.UrlValidator
import com.example.mimic.downloader.data.model.AnalysisResult
import com.example.mimic.downloader.data.platform.DetectedPlatform
import com.example.mimic.downloader.data.platform.Platforms
import com.example.mimic.downloader.data.provider.MediaProvider
import com.example.mimic.downloader.data.provider.ProviderException

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
class MediaAnalyzer(private val mediaProvider: MediaProvider) {

    fun detect(url: String): DetectedPlatform = Platforms.detect(url)

    suspend fun analyze(rawUrl: String): Result<AnalysisResult> {
        val url = UrlValidator.normalize(rawUrl) ?: UrlValidator.extractFirstUrl(rawUrl)
            ?: return Result.failure(ProviderException.InvalidUrl())
        val detected = detect(url)
        if (!detected.isSupported) return Result.failure(ProviderException.UnsupportedUrl())
        return try {
            Result.success(mediaProvider.analyze(url, detected))
        } catch (e: ProviderException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(ProviderException.classify(e.message, e))
        }
    }
}
