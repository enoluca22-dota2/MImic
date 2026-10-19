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

    fun parse(root: JsonNode, sourceUrl: String, mapper: ObjectMapper): Parsed {
        val isCollection = root.text("_type") == "playlist" || root.path("entries").isArray
        if (!isCollection) return Parsed.Single(mapper.treeToValue(root, VideoInfo::class.java))

        val entryNodes = root.path("entries").filter { !it.isNull && !it.isMissingNode }
        // Some single posts come back as a one-item "playlist" that already carries full format
        // data — treat those as the single item they really are.
        if (entryNodes.size == 1 && entryNodes[0].path("formats").isArray) {
            return Parsed.Single(mapper.treeToValue(entryNodes[0], VideoInfo::class.java))
        }

        val entries = entryNodes.mapIndexed { i, entry ->
            PlaylistEntry(
                // Untitled entries are usually private or removed items; say so instead of inventing a title.
                title = entry.text("title") ?: entry.text("id")?.let { "Untitled item ($it)" } ?: "Untitled item ${i + 1}",
                unavailableReason = unavailableReason(entry),
                url = entryUrl(entry),
                index = entry.path("playlist_index").asInt(i + 1).takeIf { it > 0 } ?: (i + 1),
                durationSeconds = entry.path("duration").takeIf { it.isNumber }?.asDouble()?.toLong()?.takeIf { it > 0 },
                thumbnailUrl = entry.text("thumbnail") ?: entry.path("thumbnails").lastOrNull()?.text("url"),
                uploader = entry.text("channel") ?: entry.text("uploader"),
            )
        }
        return Parsed.Collection(
            PlaylistInfo(
                sourceUrl = sourceUrl,
                title = root.text("title") ?: "Playlist",
                uploader = root.text("uploader") ?: root.text("channel"),
                thumbnailUrl = root.text("thumbnail") ?: root.path("thumbnails").lastOrNull()?.text("url"),
                entries = entries,
                id = root.text("id"),
            ),
        )
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
        val candidates = listOfNotNull(entry.text("webpage_url"), entry.text("url"))
        candidates.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }?.let { return it }
        // Older yt-dlp flat YouTube entries carry just the video id.
        val id = entry.text("id") ?: return null
        return when (entry.text("ie_key")?.lowercase()) {
            "youtube" -> "https://www.youtube.com/watch?v=$id"
            else -> null
        }
    }

    private fun JsonNode.text(field: String): String? =
        path(field).takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
}
