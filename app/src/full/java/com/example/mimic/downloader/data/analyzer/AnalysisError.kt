package com.example.mimic.downloader.data.analyzer

import com.example.mimic.downloader.data.platform.DetectedPlatform
import com.example.mimic.downloader.data.provider.ProviderException

enum class ErrorAction { RETRY, COPY_URL }

/** A user-facing explanation of why a link couldn't be analyzed, with the actions that make sense. */
data class AnalysisErrorInfo(
    val title: String,
    val message: String,
    val actions: Set<ErrorAction>,
)

const val UNSUPPORTED_WEBSITE_MESSAGE = "Unsupported website. YouTube and TikTok are currently supported."

/**
 * Turns a failure into something a normal user understands. Unsupported links get one fixed
 * message; failures on YouTube/TikTok say "Unable to retrieve this media." plus the reason.
 * Technical details (yt-dlp output) only ever go to Logcat.
 */
fun Throwable.toAnalysisError(detected: DetectedPlatform?): AnalysisErrorInfo {
    val site = detected?.spec?.displayName ?: "YouTube/TikTok"
    val retrieveTitle = ProviderException.UNABLE_TO_RETRIEVE
    return when (this) {
        is ProviderException.InvalidUrl -> AnalysisErrorInfo(
            "Not a valid link",
            "Paste the full YouTube or TikTok address, starting with https://",
            emptySet(),
        )
        is ProviderException.UnsupportedUrl -> AnalysisErrorInfo("Unsupported website", UNSUPPORTED_WEBSITE_MESSAGE, setOf(ErrorAction.COPY_URL))
        is ProviderException.NoInternet, is ProviderException.NetworkError, is ProviderException.Timeout -> AnalysisErrorInfo(
            "Connection failed",
            ProviderException.CONNECTION_FAILED,
            setOf(ErrorAction.RETRY),
        )
        is ProviderException.EngineUnavailable -> AnalysisErrorInfo(
            "Downloader not ready",
            message ?: "Restart the app and try again.",
            setOf(ErrorAction.RETRY),
        )
        is ProviderException.AuthenticationRequired -> AnalysisErrorInfo(
            retrieveTitle,
            "This video needs a login (private, age-restricted or members-only), which the app can't access.",
            setOf(ErrorAction.COPY_URL),
        )
        is ProviderException.GeoRestricted -> AnalysisErrorInfo(retrieveTitle, "$site doesn't make this video available in your region.", setOf(ErrorAction.RETRY))
        is ProviderException.Blocked -> AnalysisErrorInfo(
            retrieveTitle,
            "$site refused the request from this network. Try again later, on another network, or update yt-dlp in Settings.",
            setOf(ErrorAction.RETRY, ErrorAction.COPY_URL),
        )
        is ProviderException.RateLimited -> AnalysisErrorInfo(
            retrieveTitle,
            "$site is limiting requests right now. Wait a few minutes and try again.",
            setOf(ErrorAction.RETRY),
        )
        is ProviderException.VideoUnavailable if detail.orEmpty().contains("live", ignoreCase = true) -> AnalysisErrorInfo(
            retrieveTitle,
            "This is a live stream that's still running or hasn't been published as a recording yet. " +
                "It can be downloaded once the broadcast has ended and YouTube makes the recording available.",
            setOf(ErrorAction.RETRY),
        )
        is ProviderException.NoFormatsAvailable, is ProviderException.VideoUnavailable -> AnalysisErrorInfo(
            retrieveTitle,
            "The video may be private, removed, or not a downloadable video.",
            setOf(ErrorAction.RETRY, ErrorAction.COPY_URL),
        )
        else -> AnalysisErrorInfo(
            retrieveTitle,
            "It may be private, region-restricted or temporarily unavailable. If this keeps happening, update yt-dlp in Settings.",
            setOf(ErrorAction.RETRY, ErrorAction.COPY_URL),
        )
    }
}
