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
 * What a YouTube `list=` id is, from its prefix. Only real, fixed lists ([isExpandable]) are opened
 * as playlists. Mixes are endless recommendation feeds: they open as a limited Mix preview
 * (25 songs at a time, see MixPreview), never expanded in full. Channel uploads and account-only
 * lists are refused with an explanation.
 */
enum class YouTubeListType(val label: String) {
    PLAYLIST("Playlist"),
    ALBUM("Album"),
    CURATED("YouTube Music playlist"),
    MIX("Mix"),
    CHANNEL_UPLOADS("Channel uploads"),
    PERSONAL("Private list");

    val isExpandable: Boolean get() = this == PLAYLIST || this == ALBUM || this == CURATED

    /** Title + message shown when this kind of list can't be opened as a playlist; null if it can. */
    val refusal: Pair<String, String>?
        get() = when (this) {
            CHANNEL_UPLOADS -> "This is a whole channel" to
                "This list holds every upload of a YouTube channel. MImic downloads playlists, not entire channels. Paste a playlist or video link instead."
            PERSONAL -> "This list is private to your account" to
                "Liked videos, Watch later and Liked music are only visible when signed in, and the downloader doesn't use your account. Copy the songs into a public or unlisted playlist and paste that link."
            else -> null
        }

    companion object {
        fun of(listId: String): YouTubeListType = when {
            // YouTube Music's curated playlists start with RDCLAK but are fixed lists, not Mixes.
            listId.startsWith("RDCLAK") -> CURATED
            listId.startsWith("RD") || listId.startsWith("UL") || listId.startsWith("PU") -> MIX
            listId.startsWith("OLAK5uy_") -> ALBUM
            listId.startsWith("UU") -> CHANNEL_UPLOADS
            listId == "LL" || listId == "WL" || listId == "LM" -> PERSONAL
            else -> PLAYLIST
        }
    }
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
 * m./music. subdomains and /embed/). Playlist links — youtube.com/playlist?list=…,
 * music.youtube.com/playlist?list=…, a bare watch?list=… and music.youtube.com/browse/VL… — open
 * the item picker. Channels, search pages and the home page are not media links and are rejected.
 */
object YouTubeProvider : SourceProvider("youtube", "YouTube", listOf("youtube.com", "youtu.be", "youtube-nocookie.com")) {
    override fun classify(uri: URI): ContentKind? {
        val s = uri.segments()
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        return when {
            host.endsWith("youtu.be") -> if (s.isNotEmpty()) ContentKind.VIDEO else null
            s.firstOrNull() == "watch" -> when {
                uri.queryParam("v") != null -> ContentKind.VIDEO
                uri.listParam() != null -> ContentKind.PLAYLIST
                else -> null
            }
            s.firstOrNull() == "shorts" && s.size >= 2 -> ContentKind.SHORT
            s.firstOrNull() == "live" && s.size >= 2 -> ContentKind.LIVE
            s.firstOrNull() == "embed" && s.size >= 2 -> ContentKind.VIDEO
            s.firstOrNull() == "playlist" && uri.listParam() != null -> ContentKind.PLAYLIST
            s.firstOrNull() == "browse" && browseListId(s) != null -> ContentKind.PLAYLIST
            else -> null
        }
    }

    /** The list id of a playlist link (`list=…` or YouTube Music's `/browse/VL…`), else null. */
    fun collectionListId(uri: URI): String? {
        if (classify(uri) != ContentKind.PLAYLIST) return null
        return uri.listParam() ?: browseListId(uri.segments())
    }

    /**
     * `watch?v=AAA&list=BBB&index=5` (and youtu.be/AAA?list=BBB): a video opened from a
     * playlist. The user decides between that one video and the whole playlist; the original
     * URL (with all its parameters) is kept either way.
     */
    override fun playlistContext(uri: URI): PlaylistContext? {
        val kind = classify(uri) ?: return null
        if (kind == ContentKind.PLAYLIST) return null
        val list = uri.listParam() ?: return null
        val index = uri.queryParam("index")?.toIntOrNull()?.takeIf { it > 0 }
        val videoId = uri.queryParam("v") ?: uri.segments().lastOrNull()
        return PlaylistContext(list, index, videoId)
    }

    fun playlistUrl(listId: String) = "https://www.youtube.com/playlist?list=$listId"

    /**
     * The video a Mix was started from, when its id says so (`RD<id>`, YouTube Music's
     * `RDAMVM<id>`, `RDMM<id>`). YouTube only lists a Mix from a watch page, so this is needed to
     * fetch it at all.
     */
    fun mixSeed(listId: String): String? =
        listOf("RDAMVM", "RDMM", "RD").firstNotNullOfOrNull { p ->
            listId.takeIf { it.startsWith(p) }?.removePrefix(p)?.takeIf { VIDEO_ID.matches(it) }
        }

    /** Where yt-dlp reads a Mix from: its watch page (the playlist page answers "unviewable"). */
    fun mixUrl(listId: String): String =
        mixSeed(listId)?.let { "https://www.youtube.com/watch?v=$it&list=$listId" }
            ?: "https://www.youtube.com/playlist?list=$listId&playnext=1"

    private fun URI.listParam(): String? =
        queryParam("list")?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.takeIf { PLAYLIST_ID.matches(it) }

    /** music.youtube.com/browse/VLPL… is the same playlist as list=PL…. */
    private fun browseListId(segments: List<String>): String? =
        segments.getOrNull(1)?.takeIf { it.startsWith("VL") }?.removePrefix("VL")?.takeIf { PLAYLIST_ID.matches(it) }

    private val PLAYLIST_ID = Regex("[A-Za-z0-9_-]{2,64}")
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
}

/** The playlist a single-video link was opened from ([index] is YouTube's 1-based `index=`). */
data class PlaylistContext(val listId: String, val index: Int?, val videoId: String?) {
    /** The playlist page itself, which yt-dlp expands into items. */
    val playlistUrl: String get() = YouTubeProvider.playlistUrl(listId)

    val listType: YouTubeListType get() = YouTubeListType.of(listId)

    /** YouTube Mixes ("RD…") are generated per viewer and endless; they only open as a Mix preview. */
    val isMix: Boolean get() = listType == YouTubeListType.MIX
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
    /** For a playlist link: its list id (used to tell real playlists from Mixes and channels). */
    val listId: String? = null,
) {
    val isSupported: Boolean get() = spec != null && kind != null
    val displayName: String get() = spec?.displayName ?: hostLabel(url)
    val isCollection: Boolean get() = kind == ContentKind.PLAYLIST

    val listType: YouTubeListType? get() = listId?.let { YouTubeListType.of(it) }

    /** A Mix link: opened as a limited preview of recommendations, not as a playlist. */
    val isMixPreview: Boolean get() = isCollection && listType == YouTubeListType.MIX

    /** The user has to choose between "Current video" and "Playlist" / "Mix preview" for this link. */
    val needsPlaylistChoice: Boolean
        get() = isSupported && !isCollection && playlistContext?.let { it.listType.isExpandable || it.isMix } == true

    /** A video link whose `list=` is a channel / private list: only the video is used. */
    val ignoredList: PlaylistContext?
        get() = playlistContext?.takeIf { isSupported && !isCollection && !it.listType.isExpandable && !it.isMix }

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
        val listId = if (provider == YouTubeProvider) YouTubeProvider.collectionListId(uri) else null
        return DetectedPlatform(provider.takeIf { kind != null }, kind, trimmed, context, listId)
    }
}
