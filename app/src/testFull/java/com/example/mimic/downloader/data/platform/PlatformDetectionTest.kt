package com.example.mimic.downloader.data.platform

import com.example.mimic.downloader.core.UrlValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformDetectionTest {

    private fun detect(url: String) = Platforms.detect(url)

    @Test
    fun `only youtube and tiktok are providers`() {
        assertEquals(listOf("youtube", "tiktok"), Platforms.all.map { it.id })
    }

    @Test
    fun `recognizes every supported youtube link form`() {
        val cases = mapOf(
            "https://www.youtube.com/watch?v=jNQXAC9IVRw" to ContentKind.VIDEO,
            "https://m.youtube.com/watch?v=jNQXAC9IVRw&t=10s" to ContentKind.VIDEO,
            "https://music.youtube.com/watch?v=jNQXAC9IVRw" to ContentKind.VIDEO,
            "https://youtu.be/jNQXAC9IVRw?si=tracking" to ContentKind.VIDEO,
            "https://www.youtube.com/shorts/BGQWPY4IigY" to ContentKind.SHORT,
            "https://www.youtube.com/live/jfKfPfyJRdk" to ContentKind.LIVE,
            "https://www.youtube.com/embed/jNQXAC9IVRw" to ContentKind.VIDEO,
            "https://www.youtube.com/playlist?list=PLBCF2DAC6FFB574DE" to ContentKind.PLAYLIST,
        )
        cases.forEach { (url, kind) ->
            val d = detect(url)
            assertEquals(url, "youtube", d.spec?.id)
            assertEquals(url, kind, d.kind)
            assertTrue(url, d.isSupported)
        }
    }

    @Test
    fun `recognizes every supported tiktok link form`() {
        listOf(
            "https://www.tiktok.com/@scout2015/video/6718335390845095173",
            "https://vm.tiktok.com/ZMabc123/",
            "https://vt.tiktok.com/ZSabc123/",
            "https://www.tiktok.com/t/ZTabc123/",
            "https://m.tiktok.com/v/6718335390845095173.html",
        ).forEach { url ->
            val d = detect(url)
            assertEquals(url, "tiktok", d.spec?.id)
            assertEquals(url, ContentKind.VIDEO, d.kind)
        }
    }

    @Test
    fun `every other website is unsupported`() {
        listOf(
            "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC",
            "https://www.instagram.com/reel/C1a2b3/",
            "https://www.facebook.com/reel/123",
            "https://x.com/user/status/1",
            "https://twitter.com/user/status/1",
            "https://www.reddit.com/r/videos/comments/abc/t/",
            "https://clips.twitch.tv/Clip",
            "https://vimeo.com/76979871",
            "https://soundcloud.com/a/b",
            "https://example.com/video.mp4",
            "https://www.w3schools.com/html/mov_bbb.mp4",
        ).forEach { url ->
            val d = detect(url)
            assertFalse(url, d.isSupported)
            assertNull(url, d.spec)
        }
    }

    @Test
    fun `youtube and tiktok pages that are not videos are rejected`() {
        listOf(
            "https://www.youtube.com/",
            "https://www.youtube.com/@SomeChannel",
            "https://www.youtube.com/results?search_query=x",
            "https://www.youtube.com/watch",
            "https://www.tiktok.com/",
            "https://www.tiktok.com/@someone",
            "https://www.tiktok.com/@someone/photo/123",
        ).forEach { assertFalse(it, detect(it).isSupported) }
    }

    @Test
    fun `lookalike domains are not matched`() {
        assertFalse(detect("https://notyoutube.com/watch?v=x").isSupported)
        assertFalse(detect("https://youtube.com.evil.example/watch?v=x").isSupported)
        assertFalse(detect("https://tiktok.com.evil.example/@u/video/1").isSupported)
    }

    @Test
    fun `extracts every link from pasted or shared text`() {
        val text = """
            Check these out:
            https://www.youtube.com/watch?v=abc,
            https://vm.tiktok.com/ZMabc/.
            duplicate https://www.youtube.com/watch?v=abc
        """.trimIndent()
        assertEquals(listOf("https://www.youtube.com/watch?v=abc", "https://vm.tiktok.com/ZMabc/"), UrlValidator.extractAllUrls(text))
        assertTrue(UrlValidator.extractAllUrls("no links here").isEmpty())
        assertTrue(UrlValidator.extractAllUrls("").isEmpty())
    }
}
