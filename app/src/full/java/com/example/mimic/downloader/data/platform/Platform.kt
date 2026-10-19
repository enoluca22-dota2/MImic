package com.example.mimic.downloader.data.platform

import java.net.URI
import java.util.Locale

/** What a supported link points at. */
enum class ContentKind(val label: String) {
    VIDEO("Video"),
    SHORT("Short"),
    LIVE("Live"),
    PLAYLIST("Playlist"),
}

/**
 * One of the two sites this app supports. A provider owns its domains and decides which of its
 * URLs are supported media links; everything else is rejected before any extraction runs.
 * Extraction itself goes through the shared yt-dlp engine.
 */
sealed class SourceProvider(
    val id: String,
    val displayName: String,
    private val domains: List<String>,
) {
    fun ownsHost(host: String): Boolean = domains.any { host == it || host.endsWith(".$it") }

    /** The kind of media [uri] points to, or null if it isn't a supported link on this site. */
    abstract fun classify(uri: URI): ContentKind?

    /** For a single-video link opened from inside a playlist: that playlist's id. */
    open fun playlistContext(uri: URI): PlaylistContext? = null

    protected fun URI.segments(): List<String> = path.orEmpty().split('/').filter { it.isNotBlank() }
    protected fun URI.queryParam(name: String): String? =
        rawQuery?.split('&')?.map { it.split('=', limit = 2) }?.firstOrNull { it[0] == name }?.getOrNull(1)?.takeIf { it.isNotBlank() }
}

/**
 * YouTube: youtube.com/watch?v=…, youtu.be/…, youtube.com/shorts/…, youtube.com/live/… (plus the
 * m./music. subdomains and /embed/). Playlist links open the existing item picker. Channels,
 * search pages and the home page are not media links and are rejected.
 */
object YouTubeProvider : SourceProvider("youtube", "YouTube", listOf("youtube.com", "youtu.be", "youtube-nocookie.com")) {
    override fun classify(uri: URI): ContentKind? {
        val s = uri.segments()
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        return when {
            host.endsWith("youtu.be") -> if (s.isNotEmpty()) ContentKind.VIDEO else null
            s.firstOrNull() == "watch" -> if (uri.queryParam("v") != null) ContentKind.VIDEO else null
            s.firstOrNull() == "shorts" && s.size >= 2 -> ContentKind.SHORT
            s.firstOrNull() == "live" && s.size >= 2 -> ContentKind.LIVE
            s.firstOrNull() == "embed" && s.size >= 2 -> ContentKind.VIDEO
            s.firstOrNull() == "playlist" && uri.queryParam("list") != null -> ContentKind.PLAYLIST
            else -> null
        }
    }

    /**
     * `watch?v=AAA&list=BBB&index=5` (and youtu.be/AAA?list=BBB): a video opened from a
     * playlist. The user decides between that one video and the whole playlist; the original
     * URL (with all its parameters) is kept either way.
     */
    override fun playlistContext(uri: URI): PlaylistContext? {
        val kind = classify(uri) ?: return null
        if (kind == ContentKind.PLAYLIST) return null
        val list = uri.queryParam("list")?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: return null
        if (!PLAYLIST_ID.matches(list)) return null
        val index = uri.queryParam("index")?.toIntOrNull()?.takeIf { it > 0 }
        val videoId = uri.queryParam("v") ?: uri.segments().lastOrNull()
        return PlaylistContext(list, index, videoId)
    }

    fun playlistUrl(listId: String) = "https://www.youtube.com/playlist?list=$listId"

    private val PLAYLIST_ID = Regex("[A-Za-z0-9_-]{2,64}")
}

/** The playlist a single-video link was opened from ([index] is YouTube's 1-based `index=`). */
data class PlaylistContext(val listId: String, val index: Int?, val videoId: String?) {
    /** The playlist page itself, which yt-dlp expands into items. */
    val playlistUrl: String get() = YouTubeProvider.playlistUrl(listId)

    /** YouTube Mixes ("RD…") are generated per viewer and endless; they are offered but flagged. */
    val isMix: Boolean get() = listId.startsWith("RD")
}

/**
 * TikTok: tiktok.com/@user/video/…, and the vm./vt. short share links (tiktok.com/t/… too).
 * Profiles, photo posts and other pages are rejected.
 */
object TikTokProvider : SourceProvider("tiktok", "TikTok", listOf("tiktok.com")) {
    override fun classify(uri: URI): ContentKind? {
        val s = uri.segments()
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        return when {
            host.startsWith("vm.") || host.startsWith("vt.") -> if (s.isNotEmpty()) ContentKind.VIDEO else null
            s.firstOrNull() == "t" && s.size >= 2 -> ContentKind.VIDEO
            s.size >= 3 && s[0].startsWith("@") && s[1] == "video" -> ContentKind.VIDEO
            s.firstOrNull() == "v" && s.size >= 2 -> ContentKind.VIDEO // m.tiktok.com/v/<id>.html
            else -> null
        }
    }
}

/** Result of recognizing a URL. [spec] is null when the link isn't a supported YouTube/TikTok link. */
data class DetectedPlatform(
    val spec: SourceProvider?,
    val kind: ContentKind?,
    val url: String,
    /** Set when a single-video link also names a playlist (`watch?v=…&list=…`). */
    val playlistContext: PlaylistContext? = null,
) {
    val isSupported: Boolean get() = spec != null && kind != null
    val displayName: String get() = spec?.displayName ?: hostLabel(url)
    val isCollection: Boolean get() = kind == ContentKind.PLAYLIST

    /** The user has to choose between "Current video" and "Playlist" for this link. */
    val needsPlaylistChoice: Boolean get() = isSupported && !isCollection && playlistContext != null

    private fun hostLabel(url: String): String =
        runCatching { URI(url).host?.removePrefix("www.")?.removePrefix("m.") }.getOrNull() ?: "Website"
}

object Platforms {

    /** The only supported sites. */
    val all: List<SourceProvider> = listOf(YouTubeProvider, TikTokProvider)

    /** Recognizes [url] from its host and path alone — no network. */
    fun detect(url: String): DetectedPlatform {
        val trimmed = url.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return DetectedPlatform(null, null, trimmed)
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return DetectedPlatform(null, null, trimmed)
        val provider = all.firstOrNull { it.ownsHost(host) } ?: return DetectedPlatform(null, null, trimmed)
        val kind = runCatching { provider.classify(uri) }.getOrNull()
        val context = if (kind != null) runCatching { provider.playlistContext(uri) }.getOrNull() else null
        return DetectedPlatform(provider.takeIf { kind != null }, kind, trimmed, context)
    }
}
