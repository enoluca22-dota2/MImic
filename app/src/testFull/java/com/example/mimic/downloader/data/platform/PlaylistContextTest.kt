package com.example.mimic.downloader.data.platform

import com.example.mimic.downloader.data.model.AnalysisResult
import com.example.mimic.downloader.data.model.PlaylistEntry
import com.example.mimic.downloader.data.model.PlaylistInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** YouTube playlist links: direct playlists, videos opened inside a playlist, and the user's choice. */
class PlaylistContextTest {

    @Test
    fun `direct playlist url is a collection and needs no choice`() {
        val d = Platforms.detect("https://www.youtube.com/playlist?list=PLBCF2DAC6FFB574DE")
        assertEquals(ContentKind.PLAYLIST, d.kind)
        assertTrue(d.isCollection)
        assertFalse(d.needsPlaylistChoice)
    }

    @Test
    fun `video inside a playlist keeps v, list and index and asks for a choice`() {
        val url = "https://www.youtube.com/watch?v=AAA&list=PLBBB_x-1&index=5"
        val d = Platforms.detect(url)
        assertEquals(ContentKind.VIDEO, d.kind)
        assertTrue(d.needsPlaylistChoice)
        val ctx = d.playlistContext!!
        assertEquals("PLBBB_x-1", ctx.listId)
        assertEquals(5, ctx.index)
        assertEquals("AAA", ctx.videoId)
        assertEquals("https://www.youtube.com/playlist?list=PLBBB_x-1", ctx.playlistUrl)
        // The original URL is never rewritten: "Current video" analyzes exactly this.
        assertEquals(url, d.url)
    }

    @Test
    fun `short links and extra parameters keep their playlist context`() {
        val d = Platforms.detect("https://youtu.be/AAA?list=PL123&si=tracking&t=30")
        assertTrue(d.needsPlaylistChoice)
        assertEquals("AAA", d.playlistContext!!.videoId)
        assertNull(d.playlistContext!!.index)

        val mobile = Platforms.detect("https://m.youtube.com/watch?list=PL123&v=AAA&pp=xyz")
        assertEquals("PL123", mobile.playlistContext!!.listId)
    }

    @Test
    fun `plain videos, shorts without list and tiktok never ask`() {
        listOf(
            "https://www.youtube.com/watch?v=AAA",
            "https://www.youtube.com/watch?v=AAA&t=10s",
            "https://www.youtube.com/shorts/AAA",
            "https://www.tiktok.com/@u/video/123?list=PL1",
        ).forEach { assertFalse(it, Platforms.detect(it).needsPlaylistChoice) }
    }

    @Test
    fun `garbage list values are ignored`() {
        assertFalse(Platforms.detect("https://www.youtube.com/watch?v=AAA&list=").needsPlaylistChoice)
        assertFalse(Platforms.detect("https://www.youtube.com/watch?v=AAA&list=%3Cscript%3E").needsPlaylistChoice)
    }

    @Test
    fun `mixes are flagged`() {
        assertTrue(Platforms.detect("https://www.youtube.com/watch?v=AAA&list=RDAAA").playlistContext!!.isMix)
    }

    @Test
    fun `playlist result highlights the video the link pointed at`() {
        val entries = (1..6).map { PlaylistEntry("T$it", "https://www.youtube.com/watch?v=V$it", it, null, null) }
        val playlist = PlaylistInfo("https://www.youtube.com/playlist?list=PL1", "L", null, null, entries, id = "PL1")
        val detected = Platforms.detect(playlist.sourceUrl)

        val byId = AnalysisResult.Collection(playlist, detected, PlaylistContext("PL1", index = 2, videoId = "V4"))
        assertEquals(3, byId.focusedEntry) // video id wins over a stale index=

        val byIndex = AnalysisResult.Collection(playlist, detected, PlaylistContext("PL1", index = 2, videoId = "gone"))
        assertEquals(1, byIndex.focusedEntry)

        assertNull(AnalysisResult.Collection(playlist, detected).focusedEntry)
    }
}
