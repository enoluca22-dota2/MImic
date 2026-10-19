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
) {
    val isAvailable: Boolean get() = unavailableReason == null
}

data class PlaylistInfo(
    val sourceUrl: String,
    val title: String,
    val uploader: String?,
    val thumbnailUrl: String?,
    val entries: List<PlaylistEntry>,
    /** The playlist's id (list=…), used to group its items in the queue. */
    val id: String? = null,
)

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
