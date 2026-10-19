package com.example.mimic.downloader.diagnostics

import com.example.mimic.downloader.data.provider.ProviderException
import java.net.URI
import java.util.Locale

/**
 * What a failed (or successful) link analysis most likely means. The categories keep three
 * things apart that look alike on screen: the network being refused (bot check, 403, rate limit),
 * yt-dlp failing to extract, and content that genuinely needs an account.
 */
enum class DiagCategory(val label: String, val meaning: String) {
    OK("Formats found", "The link was read and formats were listed."),
    INVALID_URL("Not a link", "The text didn't contain a usable link."),
    UNSUPPORTED_URL("Unsupported link", "Not a YouTube/TikTok media link, so nothing was sent to the internet."),
    NO_INTERNET("No internet", "Android reported no internet connection when the analysis started."),
    NETWORK("Network error", "The connection failed (DNS, reset or unreachable). Points to the network, not to YouTube or the video."),
    TIMEOUT("Timed out", "No answer in time. Points to a slow or filtered network."),
    BOT_CHECK(
        "YouTube bot check",
        "YouTube asked to confirm this isn't a bot. This depends on the network/IP address and session (VPN, Private DNS, " +
            "carrier, shared Wi-Fi), not on the video, and it isn't a real sign-in requirement for public videos.",
    ),
    AUTH_AGE("Age-restricted", "YouTube only plays this video to signed-in, age-verified accounts."),
    AUTH_PRIVATE("Private video", "Only the owner's chosen accounts can watch this video."),
    AUTH_MEMBERS("Members only", "Only channel members can watch this video."),
    AUTH_OTHER("Sign-in required", "YouTube said this content needs an account. Compare with the yt-dlp error below."),
    GEO("Region-blocked", "Not available in the country the connection appears to come from."),
    RATE_LIMITED("Rate-limited", "YouTube is limiting requests from this connection/session for a while."),
    FORBIDDEN("Refused (HTTP 403)", "YouTube refused a request. Often fixed by a newer yt-dlp; sometimes caused by the network."),
    UNAVAILABLE("Unavailable", "The video is removed, private, a live stream, or not published yet."),
    NO_FORMATS("No formats", "yt-dlp read the page but got no downloadable streams."),
    DEVICE_FFMPEG(
        "ffmpeg can't run here",
        "This device can't run the bundled ffmpeg (usually 16 KB memory pages), so streams can't be merged or converted. " +
            "Points to the device, not the network or YouTube.",
    ),
    STORAGE("Storage problem", "Saving the file failed (storage full or not writable)."),
    ENGINE("Engine didn't start", "The bundled python/yt-dlp couldn't start on this device. Points to the app/device, not the network."),
    EXTRACTOR("yt-dlp error", "yt-dlp failed while reading YouTube's answer. Often fixed by a newer yt-dlp."),
    CANCELLED("Cancelled", "Stopped before it finished."),
    UNKNOWN("Unknown error", "No specific reason was reported."),
    ;

    val isFailure: Boolean get() = this != OK && this != CANCELLED

    companion object {
        fun of(error: Throwable?, rawText: String?): DiagCategory {
            if (error == null) return OK
            val lower = rawText.orEmpty().lowercase(Locale.ROOT)
            if ("ffmpeg not found" in lower || "ffprobe and ffmpeg not found" in lower) return DEVICE_FFMPEG
            return when (error) {
                is ProviderException.InvalidUrl -> INVALID_URL
                is ProviderException.UnsupportedUrl -> UNSUPPORTED_URL
                is ProviderException.NoInternet -> NO_INTERNET
                is ProviderException.NetworkError -> NETWORK
                is ProviderException.Timeout -> TIMEOUT
                is ProviderException.BotCheck -> BOT_CHECK
                is ProviderException.AuthenticationRequired -> when {
                    "confirm your age" in lower || "age-restricted" in lower || "inappropriate" in lower -> AUTH_AGE
                    "private video" in lower -> AUTH_PRIVATE
                    "members" in lower -> AUTH_MEMBERS
                    else -> AUTH_OTHER
                }
                is ProviderException.GeoRestricted -> GEO
                is ProviderException.RateLimited -> RATE_LIMITED
                is ProviderException.Blocked -> FORBIDDEN
                is ProviderException.VideoUnavailable -> UNAVAILABLE
                is ProviderException.NoFormatsAvailable, is ProviderException.FormatUnavailable -> NO_FORMATS
                is ProviderException.EngineUnavailable -> ENGINE
                is ProviderException.MergeUnsupported -> DEVICE_FFMPEG
                is ProviderException.ConversionFailed -> if (error.unsupportedDevice) DEVICE_FFMPEG else EXTRACTOR
                is ProviderException.StorageFull, is ProviderException.StorageAccess -> STORAGE
                is ProviderException.ExtractorError, is ProviderException.ServerError -> EXTRACTOR
                is ProviderException.Cancelled -> CANCELLED
                else -> UNKNOWN
            }
        }
    }
}

/** Facts read from yt-dlp's (verbose) output — all values already sanitized. */
data class YtDlpFacts(
    val version: String? = null,
    val environment: List<String> = emptyList(),
    val clients: List<String> = emptyList(),
    /** "web: LOGIN_REQUIRED" — YouTube's answer per player client (verbose runs only). */
    val playability: List<String> = emptyList(),
    val httpStatuses: List<Int> = emptyList(),
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val lastStep: String? = null,
)

/**
 * Pure text helpers for Download Diagnostics (unit-tested). Everything that ends up in a report
 * passes through [clean]: links are cut down to site + path (YouTube links keep only the video or
 * playlist id), IP addresses are removed, and anything that looks like a cookie, header, key or
 * token is replaced with "[removed]".
 */
object DiagnosticText {
    private val URL = Regex("""https?://[^\s"'<>\]\[(){},]+""")
    private val IPV4 = Regex("""(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])""")
    private val IPV6_FULL = Regex("""(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{1,4}:){3,7}[0-9A-Fa-f]{1,4}(?![0-9A-Fa-f:])""")
    private val IPV6_SHORT = Regex("""(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{1,4}:){1,6}:(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4}){0,5})?(?![0-9A-Fa-f:])""")
    private val SECRET_VALUE = Regex(
        """(?i)\b(cookies?|set-cookie|authorization|x-goog-[a-z-]+|x-youtube-identity-token|visitor_?data|visitorid|""" +
            """po_?token|sapisid|apisid|session_?token|access_?token|refresh_?token|id_?token|token|password|passwd|secret|api_?key|key|sig|signature)""" +
            """(["']?\s*[:=]\s*)("[^"]*"|'[^']*'|[^\s,;&}]+)""",
    )
    private val HEADER_LINE = Regex("""(?im)^(\s*["']?)(cookie|set-cookie|authorization|proxy-authorization|x-goog-[a-z-]+|x-youtube-[a-z-]+)(["']?\s*:).*$""")
    private val LONG_TOKEN = Regex("""[A-Za-z0-9_\-+=%]{40,}""")
    private val MEDIA_ID = Regex("""^[A-Za-z0-9_-]{6,64}$""")

    private val HTTP_STATUS = Regex("""(?i)HTTP Error (\d{3})""")
    private val CLIENT = Regex("""Downloading ([a-z0-9_]+(?:\.[a-z0-9_]+)?) (?:client config|player API JSON)""")
    private val PLAYABILITY = Regex("""\[youtube] [^:]+: ([a-z0-9_.]+) player response playability status: (\S+)""")
    private val VERSION = Regex("""\[debug] yt-dlp version (\S+)""")
    private val ENV_KEYS = listOf("[debug] yt-dlp version", "[debug] Python ", "[debug] exe versions", "[debug] JS runtimes",
        "[debug] Optional libraries", "[debug] Proxy map", "[debug] Request Handlers", "[debug] Plugin directories")

    /** One link → "https://www.youtube.com/watch?v=ID" style: no other parameters, no foreign paths. */
    fun link(raw: String): String {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return "[link]"
        val host = uri.host?.lowercase(Locale.ROOT) ?: return "[link]"
        val isYouTube = host == "youtu.be" || host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com")
        val isTikTok = host.endsWith("tiktok.com")
        if (!isYouTube && !isTikTok) return "${uri.scheme ?: "https"}://$host/…"
        val path = uri.rawPath.orEmpty().takeIf { it.length <= 120 } ?: "/…"
        val kept = uri.rawQuery.orEmpty().split('&').mapNotNull { p ->
            val (k, v) = p.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if ((k == "v" || k == "list") && MEDIA_ID.matches(v)) "$k=$v" else null
        }
        return "https://$host$path" + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
    }

    /** Removes links' private parts, IP addresses and secret-looking values from a log line. */
    fun clean(text: String): String {
        var t = HEADER_LINE.replace(text) { "${it.groupValues[1]}${it.groupValues[2]}${it.groupValues[3]} [removed]" }
        t = URL.replace(t) { link(it.value) }
        t = SECRET_VALUE.replace(t) { "${it.groupValues[1]}${it.groupValues[2]}[removed]" }
        t = IPV4.replace(t, "[ip]")
        t = IPV6_FULL.replace(t, "[ip]")
        t = IPV6_SHORT.replace(t) { if (it.value.count { c -> c == ':' } >= 2) "[ip]" else it.value }
        t = LONG_TOKEN.replace(t, "[removed]")
        return t
    }

    /** Drops yt-dlp's "[debug]" lines so the error text classifies exactly like a non-verbose run. */
    fun withoutDebug(stderr: String?): String =
        stderr.orEmpty().lineSequence().filterNot { it.trimStart().startsWith("[debug]") }.joinToString("\n").trim()

    fun facts(stderr: String?): YtDlpFacts {
        val lines = stderr.orEmpty().lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        return YtDlpFacts(
            version = lines.firstNotNullOfOrNull { VERSION.find(it)?.groupValues?.get(1) }?.let(::clean),
            environment = lines.filter { l -> ENV_KEYS.any { l.startsWith(it) } }.map { clean(it.removePrefix("[debug] ")).take(300) },
            clients = lines.flatMap { l -> CLIENT.findAll(l).map { it.groupValues[1] }.toList() }.distinct(),
            playability = lines.mapNotNull { l -> PLAYABILITY.find(l)?.let { "${it.groupValues[1]}: ${it.groupValues[2]}" } }.distinct(),
            httpStatuses = lines.flatMap { l -> HTTP_STATUS.findAll(l).mapNotNull { it.groupValues[1].toIntOrNull() }.toList() }.distinct(),
            errors = lines.filter { "ERROR:" in it }.map { clean(it).take(600) }.distinct().take(8),
            warnings = lines.filter { "WARNING:" in it }.map { clean(it).take(400) }.distinct().take(12),
            lastStep = lines.lastOrNull { it.startsWith("[") && !it.startsWith("[debug]") && "ERROR:" !in it }?.let { clean(it).take(300) },
        )
    }

    /**
     * The last [max] lines of yt-dlp's output, cleaned. Left out: the command line itself and the
     * engine lines that the report already lists once under "Engine details".
     */
    fun tail(stderr: String?, max: Int = 80): List<String> =
        stderr.orEmpty().lines().map { it.trimEnd() }
            .filter { l ->
                l.isNotBlank() && !l.startsWith("[debug] Command-line config") && !l.startsWith("[debug] Encodings") &&
                    !l.startsWith("[debug] Loaded ") && ENV_KEYS.none { l.startsWith(it) }
            }
            .takeLast(max).map { clean(it).take(400) }
}
