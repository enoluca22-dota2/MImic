package com.example.mimic.downloader.data.provider

/**
 * Error taxonomy surfaced to the UI. Every [message] is written for a normal user; the raw
 * provider text (yt-dlp stderr, ffmpeg output, exception traces) is kept in [detail] and only
 * ever goes to Logcat, never to the screen.
 */
sealed class ProviderException(
    message: String,
    cause: Throwable? = null,
    val detail: String? = null,
) : Exception(message, cause) {

    /** Whether trying the same download again a little later has a realistic chance of working. */
    open val isRetryable: Boolean = false

    class InvalidUrl : ProviderException(
        "That doesn't look like a valid link. Copy the full address (starting with https://) and try again.",
    )

    class UnsupportedUrl(detail: String? = null) :
        ProviderException("Unsupported website. YouTube and TikTok are currently supported.", detail = detail)

    class VideoUnavailable(detail: String? = null) :
        ProviderException("This video is unavailable. It may have been removed or made private.", detail = detail)

    class NoFormatsAvailable(detail: String? = null) :
        ProviderException("No downloadable formats were found for this media.", detail = detail)

    class FormatUnavailable(detail: String? = null) :
        ProviderException(
            "The selected quality is no longer offered for this video. Detect the link again and pick another one.",
            detail = detail,
        )

    class NoInternet : ProviderException(CONNECTION_FAILED) {
        override val isRetryable = true
    }

    class NetworkError(detail: String? = null, cause: Throwable? = null) :
        ProviderException(CONNECTION_FAILED, cause, detail) {
        override val isRetryable = true
    }

    class Timeout(detail: String? = null) :
        ProviderException(CONNECTION_FAILED, detail = detail) {
        override val isRetryable = true
    }

    class ServerError(detail: String? = null) :
        ProviderException("The website is having problems right now. Try again later.", detail = detail) {
        override val isRetryable = true
    }

    class Blocked(detail: String? = null) :
        ProviderException("The website refused the download. Try again, or update yt-dlp in Settings.", detail = detail) {
        override val isRetryable = true
    }

    class RateLimited(detail: String? = null) :
        ProviderException("Too many requests to this website. Wait a few minutes and try again.", detail = detail)

    class AuthenticationRequired(detail: String? = null) :
        ProviderException("This content is private, age-restricted or members-only and can't be downloaded.", detail = detail)

    class GeoRestricted(detail: String? = null) :
        ProviderException("This content isn't available in your region.", detail = detail)

    class StorageFull(detail: String? = null) :
        ProviderException("Not enough free storage space. Free up some space and try again.", detail = detail)

    class StorageAccess(message: String, detail: String? = null, cause: Throwable? = null) :
        ProviderException(message, cause, detail)

    class ConversionFailed(detail: String? = null, val unsupportedDevice: Boolean = false) :
        ProviderException(
            if (unsupportedDevice) {
                "MP3 conversion isn't supported on this device yet (it uses 16 KB memory pages). Video formats " +
                    "marked \"with audio\" still download normally."
            } else {
                "The download finished, but converting it to MP3 failed."
            },
            detail = detail,
        )

    /** Video and audio arrived as separate streams but this device's ffmpeg can't combine them. */
    class MergeUnsupported(detail: String? = null) :
        ProviderException(
            "This device can't combine separate video and audio streams yet (it uses 16 KB memory pages). " +
                "Choose a format marked \"with audio\" instead.",
            detail = detail,
        )

    class EngineUnavailable(cause: Throwable? = null) :
        ProviderException("The download engine couldn't start. Restart the app and try again.", cause)

    class ExtractorError(detail: String? = null, cause: Throwable? = null) :
        ProviderException(DOWNLOAD_FAILED, cause, detail)

    class Cancelled : ProviderException("Cancelled")

    class Unknown(detail: String? = null, cause: Throwable? = null) :
        ProviderException(DOWNLOAD_FAILED, cause, detail)

    companion object {
        const val CONNECTION_FAILED = "Connection failed. Check your internet connection and try again."
        const val DOWNLOAD_FAILED = "Download failed. You can retry this item."
        const val UNABLE_TO_RETRIEVE = "Unable to retrieve this media."

        private val HTTP_5XX = Regex("""http error 5\d\d""")

        /** Classifies a raw yt-dlp/ffmpeg/IO message into a typed, user-readable exception. */
        fun classify(rawMessage: String?, cause: Throwable? = null): ProviderException {
            val text = rawMessage.orEmpty()
            val lower = text.lowercase()
            val detail = text.takeIf { it.isNotBlank() }
            return when {
                lower.contains("no space left") || lower.contains("enospc") -> StorageFull(detail)
                lower.contains("unsupported url") -> UnsupportedUrl(detail)
                lower.contains("private video") || lower.contains("sign in") || lower.contains("confirm your age") ||
                    lower.contains("not granting access") || lower.contains("log in") || lower.contains("cookies") ||
                    lower.contains("members-only") || lower.contains("members only") ||
                    lower.contains("login required") -> AuthenticationRequired(detail)
                lower.contains("not available in your country") || lower.contains("geo restrict") ||
                    lower.contains("geo-restrict") -> GeoRestricted(detail)
                lower.contains("this video is unavailable") || lower.contains("video unavailable") ||
                    lower.contains("has been removed") || lower.contains("live stream recording is not available") ||
                    lower.contains("live event will begin") || lower.contains("premieres in") -> VideoUnavailable(detail)
                lower.contains("requested format is not available") -> FormatUnavailable(detail)
                lower.contains("no video formats found") || lower.contains("no video could be found") ||
                    lower.contains("no media found") || lower.contains("does not contain any video") -> NoFormatsAvailable(detail)
                lower.contains("http error 404") || lower.contains("http error 410") -> VideoUnavailable(detail)
                lower.contains("http error 429") || lower.contains("too many requests") -> RateLimited(detail)
                lower.contains("http error 403") || lower.contains("forbidden") ||
                    lower.contains("ip address is blocked") || lower.contains("blocked from accessing") -> Blocked(detail)
                HTTP_5XX.containsMatchIn(lower) -> ServerError(detail)
                lower.contains("timed out") || lower.contains("timeout") -> Timeout(detail)
                lower.contains("unable to download webpage") || lower.contains("temporary failure in name resolution") ||
                    lower.contains("unknownhost") || lower.contains("unable to resolve host") ||
                    lower.contains("network is unreachable") || lower.contains("connection reset") ||
                    lower.contains("connection refused") || lower.contains("connection aborted") ||
                    lower.contains("remote end closed") || lower.contains("incompleteread") ->
                    NetworkError(detail, cause)
                text.isBlank() -> Unknown(cause = cause)
                else -> ExtractorError(detail, cause)
            }
        }
    }
}
