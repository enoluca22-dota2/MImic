package com.example.mimic.online

import com.example.mimic.data.online.OnlineTrack
import com.example.mimic.online.data.innertube.StreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.ConcurrentHashMap

/**
 * Regular YouTube search (NewPipeExtractor, already bundled for stream resolution).
 *
 * Why it exists: YouTube Music's song catalog is region-limited. Where it isn't offered — measured
 * from Albania on 2026-10-25: catalog song search answers "No results" while YouTube itself
 * answers normally — MImic falls back to YouTube's own results instead of showing nothing. No
 * region is spoofed; these are the results YouTube serves to this connection, labelled as coming
 * from YouTube. Pages are fetched on demand ("Load more").
 */
object YouTubeSearch {
    class Page1(val tracks: List<OnlineTrack>, val nextToken: String?)

    private val pending = ConcurrentHashMap<String, Pair<SearchExtractor, Page>>()

    suspend fun search(query: String): Page1 = withContext(Dispatchers.IO) {
        StreamResolver.ensureNewPipe()
        val yt = ServiceList.YouTube
        val ex = yt.getSearchExtractor(yt.searchQHFactory.fromQuery(query, listOf("videos"), ""))
        ex.fetchPage()
        val page = ex.initialPage
        Page1(page.items.filterIsInstance<StreamInfoItem>().mapNotNull(::toTrack), remember(ex, page.nextPage))
    }

    suspend fun more(token: String): Page1 = withContext(Dispatchers.IO) {
        val (ex, next) = pending.remove(token) ?: return@withContext Page1(emptyList(), null)
        val page = ex.getPage(next)
        Page1(page.items.filterIsInstance<StreamInfoItem>().mapNotNull(::toTrack), remember(ex, page.nextPage))
    }

    private fun remember(ex: SearchExtractor, next: Page?): String? {
        if (next == null || !Page.isValid(next)) return null
        if (pending.size > 20) pending.clear()
        val token = "yts-" + System.nanoTime()
        pending[token] = ex to next
        return token
    }

    private fun toTrack(item: StreamInfoItem): OnlineTrack? {
        val id = item.url.substringAfter("v=", "").take(11).takeIf { it.length == 11 } ?: return null
        if (item.duration <= 0) return null // live streams and premieres aren't songs
        val artist = cleanArtist(item.uploaderName.orEmpty())
        return OnlineTrack(
            videoId = id,
            title = cleanTitle(item.name.orEmpty(), artist),
            artist = artist.ifBlank { "YouTube" },
            album = null,
            durationMs = item.duration * 1000,
            // The video's own thumbnail (largest offered) — per track, never a shared placeholder.
            artworkUrl = item.thumbnails.maxByOrNull { it.width * it.height }?.url,
            isVideo = true,
        )
    }

    /** "Artist - Topic" / "ArtistVEVO" channels are the artist. */
    internal fun cleanArtist(uploader: String): String =
        uploader.removeSuffix(" - Topic").let { if (it.endsWith("VEVO") && it.length > 4) it.dropLast(4) else it }.trim()

    /** "Artist - Song (Official Video)" → "Song"; anything else is kept as YouTube shows it. */
    internal fun cleanTitle(title: String, artist: String): String {
        var t = title.trim()
        if (artist.isNotBlank() && t.startsWith("$artist - ", ignoreCase = true)) t = t.substring(artist.length + 3)
        t = t.replace(Regex("""\s*[(\[](official\s+(music\s+)?(video|audio|hd video|lyric video|visualizer))[)\]]""", RegexOption.IGNORE_CASE), "")
        return t.trim().ifBlank { title }
    }
}
