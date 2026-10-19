package com.example.mimic.downloader.data.provider

import com.example.mimic.downloader.data.model.Watermark
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feeds `yt-dlp -J --flat-playlist` shaped JSON through the analyzer's parser. */
class AnalysisJsonParserTest {

    private val mapper = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private fun parse(json: String, url: String = "https://example.com/x") = AnalysisJsonParser.parse(mapper.readTree(json), url, mapper)

    @Test
    fun `a single video yields its full info`() {
        val parsed = parse("""{"id":"a","title":"One","formats":[{"format_id":"18","ext":"mp4","vcodec":"avc1","acodec":"mp4a","height":360}]}""")
        assertTrue(parsed is AnalysisJsonParser.Parsed.Single)
        assertEquals("One", (parsed as AnalysisJsonParser.Parsed.Single).info.title)
    }

    @Test
    fun `a flat youtube playlist yields entries with their own urls`() {
        val parsed = parse(
            """
            {"_type":"playlist","title":"My list","uploader":"Me","entries":[
              {"_type":"url","ie_key":"Youtube","id":"aaa","url":"https://www.youtube.com/watch?v=aaa","title":"First","duration":61.0},
              {"_type":"url","ie_key":"Youtube","id":"bbb","title":"Second"},
              null
            ]}
            """.trimIndent(),
            "https://www.youtube.com/playlist?list=PL1",
        ) as AnalysisJsonParser.Parsed.Collection
        val p = parsed.playlist
        assertEquals("My list", p.title)
        assertEquals("Me", p.uploader)
        assertEquals(2, p.entries.size)
        assertEquals("https://www.youtube.com/watch?v=aaa", p.entries[0].url)
        assertEquals(61L, p.entries[0].durationSeconds)
        // Old-style flat entries with only an id get a canonical watch URL.
        assertEquals("https://www.youtube.com/watch?v=bbb", p.entries[1].url)
    }

    @Test
    fun `a one-item wrapper with full formats is treated as that single item`() {
        val parsed = parse(
            """{"_type":"playlist","entries":[{"id":"x","title":"Only","formats":[{"format_id":"hd","ext":"mp4","vcodec":"avc1","acodec":"aac","height":720}]}]}""",
        )
        assertTrue(parsed is AnalysisJsonParser.Parsed.Single)
    }

    @Test
    fun `items without their own url are downloaded by position`() {
        val parsed = parse(
            """{"_type":"playlist","title":"Post","entries":[{"id":"1","title":"Video 1","formats":[]},{"id":"2","title":"Video 2","formats":[]}]}""",
            "https://x.com/user/status/1",
        ) as AnalysisJsonParser.Parsed.Collection
        assertNull(parsed.playlist.entries[0].url)
        assertEquals(listOf(1, 2), parsed.playlist.entries.map { it.index })
    }

    @Test
    fun `watermark is only claimed when the extractor distinguishes streams`() {
        val tiktokLike = parse(
            """
            {"id":"t","title":"Clip","duration":10,"formats":[
              {"format_id":"download","ext":"mp4","vcodec":"h264","acodec":"aac","height":1024,"format_note":"watermarked"},
              {"format_id":"play","ext":"mp4","vcodec":"h264","acodec":"aac","height":1024}
            ]}
            """.trimIndent(),
        ) as AnalysisJsonParser.Parsed.Single
        val media = FormatMapper.toMediaInfo("https://www.tiktok.com/@u/video/1", tiktokLike.info)
        // Same resolution: the clean stream wins and is labelled as such.
        assertEquals(Watermark.NO_WATERMARK, media.videoFormats.single().watermark)
        assertEquals("play", media.videoFormats.single().formatId)

        val plain = parse("""{"id":"p","title":"Plain","formats":[{"format_id":"18","ext":"mp4","vcodec":"avc1","acodec":"mp4a","height":360}]}""")
            as AnalysisJsonParser.Parsed.Single
        assertEquals(Watermark.UNKNOWN, FormatMapper.toMediaInfo("https://youtube.com/watch?v=p", plain.info).videoFormats.single().watermark)
    }

    @Test
    fun `best available appears only when there is a real choice of resolutions`() {
        val parsed = parse(
            """
            {"id":"m","title":"Multi","formats":[
              {"format_id":"137","ext":"mp4","vcodec":"avc1","acodec":"none","height":1080},
              {"format_id":"136","ext":"mp4","vcodec":"avc1","acodec":"none","height":720}
            ]}
            """.trimIndent(),
        ) as AnalysisJsonParser.Parsed.Single
        val videos = FormatMapper.toMediaInfo("https://youtube.com/watch?v=m", parsed.info).videoFormats
        assertTrue(videos.first().isBestAvailable)
        assertEquals("Best available", videos.first().displayLabel)
        assertEquals(listOf(1080, 720), videos.drop(1).map { it.heightPx })
    }

    @Test
    fun `private and deleted playlist items are marked unavailable, others keep position and duration`() {
        val parsed = parse(
            """
            {"_type":"playlist","id":"PL9","title":"Mixed","entries":[
              {"_type":"url","ie_key":"Youtube","id":"a","url":"https://www.youtube.com/watch?v=a","title":"Fine","duration":120,
               "thumbnails":[{"url":"https://i.ytimg.com/vi/a/default.jpg"},{"url":"https://i.ytimg.com/vi/a/hqdefault.jpg"}]},
              {"_type":"url","ie_key":"Youtube","id":"b","url":"https://www.youtube.com/watch?v=b","title":"[Private video]"},
              {"_type":"url","ie_key":"Youtube","id":"c","url":"https://www.youtube.com/watch?v=c","title":"[Deleted video]"},
              {"_type":"url","ie_key":"Youtube","id":"d","url":"https://www.youtube.com/watch?v=d","title":"Members","availability":"subscriber_only"},
              {"_type":"url","ie_key":"Youtube","id":"e","url":"https://www.youtube.com/watch?v=e","title":null,"duration":null}
            ]}
            """.trimIndent(),
            "https://www.youtube.com/playlist?list=PL9",
        ) as AnalysisJsonParser.Parsed.Collection
        val e = parsed.playlist.entries
        assertEquals("PL9", parsed.playlist.id)
        assertEquals(listOf(1, 2, 3, 4, 5), e.map { it.index })
        assertTrue(e[0].isAvailable)
        assertEquals(120L, e[0].durationSeconds)
        assertEquals("https://i.ytimg.com/vi/a/hqdefault.jpg", e[0].thumbnailUrl)
        assertEquals("Private video", e[1].unavailableReason)
        assertEquals("Deleted video", e[2].unavailableReason)
        assertEquals("Requires sign-in", e[3].unavailableReason)
        assertEquals("Private or removed video", e[4].unavailableReason)
    }
}
