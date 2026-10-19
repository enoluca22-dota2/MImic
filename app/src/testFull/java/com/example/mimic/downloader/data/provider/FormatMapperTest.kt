package com.example.mimic.downloader.data.provider

import com.example.mimic.downloader.data.model.FormatKind
import com.example.mimic.downloader.data.model.Metric
import com.example.mimic.downloader.data.model.QualityTier
import com.fasterxml.jackson.databind.ObjectMapper
import com.yausername.youtubedl_android.mapper.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises FormatMapper against real yt-dlp --dump-json shaped payloads, decoded through the
 * same Jackson mapper youtubedl-android uses, rather than hand-built domain objects — so these
 * tests fail if our mapping assumptions about yt-dlp's JSON schema stop holding.
 */
class FormatMapperTest {

    private val mapper = ObjectMapper()

    private fun parse(json: String): VideoInfo = mapper.readValue(json, VideoInfo::class.java)

    @Test
    fun `maps a video-only and audio-only format pair, flagging the merge requirement`() {
        val info = parse(
            """
            {
              "title": "Sample Video",
              "webpage_url": "https://example.com/watch?v=1",
              "uploader": "Someone",
              "duration": 125,
              "thumbnail": "https://example.com/thumb.jpg",
              "extractor_key": "Generic",
              "formats": [
                {"format_id": "137", "ext": "mp4", "vcodec": "avc1", "acodec": "none", "height": 1080, "fps": 30, "tbr": 4500, "filesize": 104857600},
                {"format_id": "140", "ext": "m4a", "vcodec": "none", "acodec": "mp4a", "abr": 128, "asr": 44100, "filesize": 5242880}
              ]
            }
            """.trimIndent(),
        )

        val media = FormatMapper.toMediaInfo("https://example.com/watch?v=1", info)

        assertEquals("Sample Video", media.title)
        assertEquals(125L, media.durationSeconds)
        assertEquals(1, media.videoFormats.size)
        val video = media.videoFormats.first()
        assertEquals(FormatKind.VIDEO, video.kind)
        assertEquals("1080p", video.resolutionLabel)
        assertTrue("video-only stream must be flagged as having no audio", !video.hasAudio)
        assertEquals(Metric.Known(104857600L), video.fileSizeBytes)

        // Audio is offered as fixed MP3 bitrates, all sourced from the best real audio stream.
        // "Original quality (VBR)" first — null bitrate, never replaced by a default — then fixed bitrates.
        val mp3 = media.audioFormats.filter { !it.nativeAudio }
        assertEquals(listOf(null, 320, 256, 192, 128), mp3.map { it.mp3BitrateKbps })
        // The source really has an m4a stream, so its original audio is offered too — last, not re-encoded.
        val m4a = media.audioFormats.last()
        assertTrue(m4a.nativeAudio)
        assertEquals("M4A · Original", m4a.displayLabel)
        assertEquals(Metric.Known(5242880L), m4a.fileSizeBytes)
        assertTrue(media.audioFormats.all { it.formatId == "140" })
        // A single resolution needs no "Best available" pseudo-option.
        assertTrue(media.videoFormats.none { it.isBestAvailable })
        val vbr = media.audioFormats.first()
        assertTrue(vbr.isVbrAudio)
        assertEquals("MP3 · Original quality (VBR)", vbr.displayLabel)
        assertEquals(Metric.Unknown, vbr.fileSizeBytes)
        val mp3At320 = media.audioFormats[1]
        assertEquals("MP3 · 320 kbps", mp3At320.displayLabel)
        assertEquals(Metric.Estimated(125L * 320 * 1000 / 8), mp3At320.fileSizeBytes)
        // Every row needs a distinct key even though they share the source format id.
        assertEquals(media.audioFormats.size, media.audioFormats.map { it.key }.toSet().size)

        assertEquals("Full HD", video.qualityName)
    }

    @Test
    fun `unknown filesize stays Unknown rather than a fabricated value`() {
        val info = parse(
            """
            {"title": "No size info", "formats": [
              {"format_id": "18", "ext": "mp4", "vcodec": "avc1", "acodec": "mp4a", "height": 360}
            ]}
            """.trimIndent(),
        )
        val media = FormatMapper.toMediaInfo("https://example.com/x", info)
        assertEquals(Metric.Unknown, media.videoFormats.first().fileSizeBytes)
    }

    @Test
    fun `approximate filesize is marked Estimated, not Known`() {
        val info = parse(
            """
            {"title": "Approx size", "formats": [
              {"format_id": "22", "ext": "mp4", "vcodec": "avc1", "acodec": "mp4a", "height": 720, "filesize_approx": 20000000}
            ]}
            """.trimIndent(),
        )
        val media = FormatMapper.toMediaInfo("https://example.com/x", info)
        assertEquals(Metric.Estimated(20000000L), media.videoFormats.first().fileSizeBytes)
    }

    @Test
    fun `storyboard mhtml formats are filtered out`() {
        val info = parse(
            """
            {"title": "Has storyboard", "formats": [
              {"format_id": "sb0", "ext": "mhtml", "vcodec": "none", "acodec": "none"},
              {"format_id": "18", "ext": "mp4", "vcodec": "avc1", "acodec": "mp4a", "height": 360}
            ]}
            """.trimIndent(),
        )
        val media = FormatMapper.toMediaInfo("https://example.com/x", info)
        assertEquals(1, media.videoFormats.size)
        assertEquals("18", media.videoFormats.first().formatId)
    }

    @Test
    fun `the single available video quality is tiered HIGH, not BEST-only-and-arbitrary`() {
        val info = parse(
            """
            {"title": "One quality", "formats": [
              {"format_id": "18", "ext": "mp4", "vcodec": "avc1", "acodec": "mp4a", "height": 360}
            ]}
            """.trimIndent(),
        )
        val media = FormatMapper.toMediaInfo("https://example.com/x", info)
        assertEquals(QualityTier.HIGH, media.videoFormats.first().tier)
    }

    @Test
    fun `highest resolution among several is tiered BEST`() {
        val info = parse(
            """
            {"title": "Multiple qualities", "formats": [
              {"format_id": "a", "ext": "mp4", "vcodec": "avc1", "acodec": "mp4a", "height": 2160},
              {"format_id": "b", "ext": "mp4", "vcodec": "avc1", "acodec": "mp4a", "height": 720},
              {"format_id": "c", "ext": "mp4", "vcodec": "avc1", "acodec": "mp4a", "height": 240}
            ]}
            """.trimIndent(),
        )
        val media = FormatMapper.toMediaInfo("https://example.com/x", info)
        assertEquals(QualityTier.BEST, media.videoFormats.first { it.formatId == "a" }.tier)
        assertEquals(QualityTier.LOW, media.videoFormats.first { it.formatId == "c" }.tier)
    }

    @Test
    fun `no subtitles are ever fabricated`() {
        val info = parse("""{"title": "x", "formats": []}""")
        val media = FormatMapper.toMediaInfo("https://example.com/x", info)
        assertTrue(media.subtitles.isEmpty())
    }

    @Test
    fun `missing title falls back without inventing content`() {
        val info = parse("""{"formats": []}""")
        val media = FormatMapper.toMediaInfo("https://example.com/x", info)
        assertEquals("Untitled", media.title)
        assertNull(media.durationSeconds)
    }
}
