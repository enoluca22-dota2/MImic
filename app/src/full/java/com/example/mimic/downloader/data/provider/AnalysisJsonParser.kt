package com.example.mimic.downloader.data.provider

import com.example.mimic.downloader.data.model.PlaylistEntry
import com.example.mimic.downloader.data.model.PlaylistInfo
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.yausername.youtubedl_android.mapper.VideoInfo

/**
 * Interprets the JSON printed by `yt-dlp -J --flat-playlist` for any URL.
 *
 * The same call returns either a single item (with its complete format list) or a collection
 * (playlist, channel, album, multi-video post). youtubedl-android's own `getInfo()` model has
 * no playlist fields, which is why the raw JSON is read here with the library's ObjectMapper.
 */
object AnalysisJsonParser {

    sealed interface Parsed {
        data class Single(val info: VideoInfo) : Parsed
        data class Collection(val playlist: PlaylistInfo) : Parsed
    }

    /** [mix]: the collection is a YouTube Mix preview (see [EntryCollector]). */
    fun parse(root: JsonNode, sourceUrl: String, mapper: ObjectMapper, mix: Boolean = false): Parsed {
        val isCollection = root.text("_type") == "playlist" || root.path("entries").isArray
        if (!isCollection) return Parsed.Single(mapper.treeToValue(root, VideoInfo::class.java))

        val entryNodes = root.path("entries").filter { !it.isNull && !it.isMissingNode }
        // Some single posts come back as a one-item "playlist" that already carries full format
        // data — treat those as the single item they really are.
        if (entryNodes.size == 1 && entryNodes[0].path("formats").isArray) {
            return Parsed.Single(mapper.treeToValue(entryNodes[0], VideoInfo::class.java))
        }

        val collector = EntryCollector(mix)
        entryNodes.forEach(collector::add)
        val entries = collector.entries
        return Parsed.Collection(
            PlaylistInfo(
                sourceUrl = sourceUrl,
                title = root.text("title") ?: "Playlist",
                uploader = root.text("uploader") ?: root.text("channel"),
                thumbnailUrl = root.text("thumbnail") ?: root.path("thumbnails").lastOrNull()?.text("url")
                    ?: entries.firstOrNull { it.isAvailable }?.thumbnailUrl,
                entries = entries,
                id = root.text("id"),
                reportedCount = if (mix) null else root.path("playlist_count").takeIf { it.isNumber }?.asInt()?.takeIf { it >= 0 },
                isMixPreview = mix,
            ),
        )
    }

    /**
     * Turns playlist items into entries one at a time, so the final JSON and the item lines yt-dlp
     * prints while it is still reading a long playlist give exactly the same list.
     *
     * The same video can be listed twice in one playlist: the repeat is kept (so the count matches
     * YouTube) but marked, so it's downloaded once. An identical (position, id) pair is the
     * extractor repeating itself and is dropped. A Mix repeats its recommendations after a few
     * dozen songs: there repeats are left out and the songs are numbered in the order shown, and
     * an item without a watch link of its own is unavailable (downloading "position N" of a Mix
     * would get a different song, as YouTube reshuffles it).
     */
    class EntryCollector(private val mix: Boolean = false) {
        private val seenAt = HashMap<String, Int>()
        private val seenPairs = HashSet<Pair<Int, String>>()
        val entries = ArrayList<PlaylistEntry>()

        fun add(entry: JsonNode) {
            val position = entry.path("playlist_index").asInt(0).takeIf { it > 0 } ?: (entries.size + 1)
            val id = entry.text("id")
            if (id != null && !seenPairs.add(position to id)) return
            val duplicateOf = id?.let { seenAt[it] }
            if (mix && duplicateOf != null) return
            val index = if (mix) entries.size + 1 else position
            if (id != null) seenAt.putIfAbsent(id, index)
            val url = entryUrl(entry)
            entries += PlaylistEntry(
                // Untitled entries are usually private or removed items; say so instead of inventing a title.
                title = entry.text("title") ?: id?.let { "Untitled item ($it)" } ?: "Untitled item $index",
                url = url,
                index = index,
                durationSeconds = entry.path("duration").takeIf { it.isNumber }?.asDouble()?.toLong()?.takeIf { it > 0 },
                thumbnailUrl = entry.text("thumbnail") ?: entry.path("thumbnails").lastOrNull()?.text("url"),
                uploader = entry.text("channel") ?: entry.text("uploader"),
                unavailableReason = unavailableReason(entry) ?: if (mix && url == null) "No link of its own" else null,
                videoId = id,
                duplicateOf = duplicateOf,
            )
        }
    }

    /**
     * A playlist being read: items arrive one by one (yt-dlp's per-item `--print` lines, which
     * also carry the playlist's title and count) and [snapshot] shows what's listed so far.
     */
    class PlaylistStream(private val sourceUrl: String, private val listId: String?, private val mix: Boolean) {
        private val collector = EntryCollector(mix)
        private var title: String? = null
        private var uploader: String? = null
        private var count: Int? = null

        val size: Int get() = collector.entries.size

        fun add(item: JsonNode) {
            title = title ?: item.text("playlist_title")
            uploader = uploader ?: item.text("playlist_uploader") ?: item.text("playlist_channel")
            count = count ?: item.path("playlist_count").takeIf { it.isNumber }?.asInt()?.takeIf { it > 0 }
            collector.add(item)
        }

        fun snapshot(): PlaylistInfo {
            val entries = collector.entries.toList()
            return PlaylistInfo(
                sourceUrl = sourceUrl,
                title = title ?: if (mix) "Mix" else "Playlist",
                uploader = uploader,
                thumbnailUrl = entries.firstOrNull { it.isAvailable }?.thumbnailUrl,
                entries = entries,
                id = listId,
                reportedCount = if (mix) null else count,
                isMixPreview = mix,
            )
        }
    }

    private val UNAVAILABLE_TITLES = mapOf(
        "[private video]" to "Private video",
        "[deleted video]" to "Deleted video",
        "[unavailable video]" to "Unavailable video",
    )

    /** yt-dlp keeps private/deleted playlist items as placeholders; mark them so they aren't queued by default. */
    fun unavailableReason(entry: JsonNode): String? {
        UNAVAILABLE_TITLES[entry.text("title")?.lowercase()]?.let { return it }
        return when (entry.text("availability")) {
            "private" -> "Private video"
            "needs_auth", "subscriber_only", "premium_only" -> "Requires sign-in"
            // YouTube lists private/removed items with an id but no title or duration.
            else -> if (entry.text("title") == null) "Private or removed video" else null
        }
    }

    /**
     * The entry's own page URL if the extractor gave one. Items that only exist inside their
     * parent (e.g. the 2nd video of a post) return null and are downloaded by index instead.
     */
    private fun entryUrl(entry: JsonNode): String? {
        val id = entry.text("id")
        // A YouTube entry's own watch URL, never a URL that still carries the list (which would
        // make yt-dlp expand the playlist again for every item).
        if (entry.text("ie_key")?.lowercase() == "youtube" && id != null && YOUTUBE_VIDEO_ID.matches(id)) {
            return "https://www.youtube.com/watch?v=$id"
        }
        val candidates = listOfNotNull(entry.text("webpage_url"), entry.text("url"))
        candidates.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }?.let { return it }
        // Older yt-dlp flat YouTube entries carry just the video id.
        id ?: return null
        return when (entry.text("ie_key")?.lowercase()) {
            "youtube" -> "https://www.youtube.com/watch?v=$id"
            else -> null
        }
    }

    private val YOUTUBE_VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

    /** yt-dlp's stdout with `--print` progress lines mixed in: the JSON document is the last `{` line. */
    fun jsonPart(stdout: String): String =
        stdout.lineSequence().lastOrNull { it.trimStart().startsWith("{") }?.trim() ?: stdout.trim()

    private fun JsonNode.text(field: String): String? =
        path(field).takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
}
