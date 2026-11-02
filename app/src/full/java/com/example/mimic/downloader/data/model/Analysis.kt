package com.example.mimic.downloader.data.model

import com.example.mimic.downloader.data.platform.DetectedPlatform
import com.example.mimic.downloader.data.platform.PlaylistContext

/**
 * Output of the analyzer for one YouTube/TikTok link. Everything shown to the user comes from the
 * extractor — never invented.
 */
sealed interface AnalysisResult {
    val detected: DetectedPlatform

    /** One downloadable item with its real format list. */
    data class Single(val media: MediaInfo, override val detected: DetectedPlatform) : AnalysisResult

    /**
     * A YouTube playlist. Items are downloaded individually through the queue. [focus] is set when
     * the user opened it from a `watch?v=…&list=…` link: that video is highlighted.
     */
    data class Collection(
        val playlist: PlaylistInfo,
        override val detected: DetectedPlatform,
        val focus: PlaylistContext? = null,
    ) : AnalysisResult {
        /** Index into [PlaylistInfo.entries] of the video the link pointed at, if found. */
        val focusedEntry: Int?
            get() {
                val f = focus ?: return null
                val byId = f.videoId?.let { id -> playlist.entries.indexOfFirst { it.url?.contains(id) == true } }?.takeIf { it >= 0 }
                return byId ?: f.index?.let { idx -> playlist.entries.indexOfFirst { it.index == idx } }?.takeIf { it >= 0 }
            }
    }
}

data class PlaylistEntry(
    val title: String,
    /** URL of the individual item, when the extractor gives one. */
    val url: String?,
    /**
     * 1-based position inside [PlaylistInfo.sourceUrl]. Used to download items that have no URL
     * of their own (e.g. the 3rd video of a multi-video post) via `--playlist-items`.
     */
    val index: Int,
    val durationSeconds: Long?,
    val thumbnailUrl: String?,
    /** Channel/uploader when the extractor lists it. */
    val uploader: String? = null,
    /** Why the item can't be downloaded (private, deleted…), or null if it looks available. */
    val unavailableReason: String? = null,
    /** YouTube video id, when known; used to spot the same video listed twice. */
    val videoId: String? = null,
    /** Set when this video already appears earlier in the list: that entry's [index]. */
    val duplicateOf: Int? = null,
) {
    val isAvailable: Boolean get() = unavailableReason == null

    /** Can be ticked: available, and not a repeat of an earlier entry (downloaded once). */
    val isSelectable: Boolean get() = isAvailable && duplicateOf == null

    /** Stable identity for selection and list keys. */
    val key: String get() = videoId ?: "#$index"
}

data class PlaylistInfo(
    val sourceUrl: String,
    val title: String,
    val uploader: String?,
    val thumbnailUrl: String?,
    val entries: List<PlaylistEntry>,
    /** The playlist's id (list=…), used to group its items in the queue. */
    val id: String? = null,
    /** How many videos the site says the list has (`playlist_count`), when it says. */
    val reportedCount: Int? = null,
    /** Set for a YouTube Mix: only a window of its recommendations has been read. */
    val mix: MixPage? = null,
) {
    val selectable: List<PlaylistEntry> get() = entries.filter { it.isSelectable }
    val unavailableCount: Int get() = entries.count { !it.isAvailable }
    val duplicateCount: Int get() = entries.count { it.isAvailable && it.duplicateOf != null }

    /** Videos the site counts but didn't list (hidden unavailable videos). */
    val hiddenCount: Int get() = reportedCount?.let { (it - entries.size).coerceAtLeast(0) } ?: 0

    /**
     * Adds the next Mix window ([page], read from positions up to [through]) after the songs
     * already shown. YouTube reshuffles a Mix on every request, so songs already listed are left
     * where they are and only videos not seen yet are appended (in the order YouTube returned
     * them), renumbered after the existing ones.
     */
    fun withMixPage(page: PlaylistInfo, through: Int): PlaylistInfo {
        val seen = entries.mapTo(HashSet()) { it.key }
        val added = page.entries.filter { it.duplicateOf == null && seen.add(it.key) }
            .mapIndexed { i, e -> e.copy(index = entries.size + i + 1) }
        val more = added.isNotEmpty() && page.mix?.hasMore == true && through < MixPreview.MAX_POSITION
        return copy(entries = entries + added, mix = MixPage(through, more))
    }
}

/** Mix preview window: read up to [fetchedThrough]; [hasMore] = "Load more" is offered. */
data class MixPage(val fetchedThrough: Int, val hasMore: Boolean)

/** Limits for Mix previews (normal playlists are never limited). */
object MixPreview {
    /** Songs read per request ("Load 25 more"). */
    const val PAGE = 25

    /** Safety limit: positions of a Mix that are ever read, however often "Load more" is pressed. */
    const val MAX_POSITION = 100

    fun firstPage(): IntRange = 1..PAGE

    fun nextPage(page: MixPage): IntRange? =
        (page.fetchedThrough + 1).takeIf { page.hasMore && it <= MAX_POSITION }?.let { it..minOf(it + PAGE - 1, MAX_POSITION) }
}

/**
 * How a playlist / batch item is downloaded when there's no per-item format list to pick from:
 * the choice becomes a yt-dlp format *selector* resolved against each item's real formats.
 */
sealed interface QuickFormat {
    val label: String

    /** Best video+audio, optionally capped at [maxHeight] (never upscaled; lower if unavailable). */
    data class BestVideo(val maxHeight: Int? = null) : QuickFormat {
        override val label: String get() = maxHeight?.let { "Up to ${it}p" } ?: "Best available"
    }

    /** Best audio converted to MP3; [bitrateKbps] null = Original quality (VBR). */
    data class AudioMp3(val bitrateKbps: Int? = null) : QuickFormat {
        override val label: String get() = bitrateKbps?.let { "MP3 · $it kbps" } ?: "MP3 · Original (VBR)"
    }

    companion object {
        val choices: List<QuickFormat> = listOf(
            BestVideo(), BestVideo(1080), BestVideo(720), BestVideo(480),
            AudioMp3(), AudioMp3(320), AudioMp3(192),
        )
    }
}
