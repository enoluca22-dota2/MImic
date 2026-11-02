package com.example.mimic.downloader.ui

import com.example.mimic.downloader.data.model.AnalysisResult
import com.example.mimic.downloader.data.model.MixPage
import com.example.mimic.downloader.data.model.MixPreview
import com.example.mimic.downloader.data.model.PlaylistEntry
import com.example.mimic.downloader.data.model.PlaylistInfo
import com.example.mimic.downloader.data.model.QuickFormat
import com.example.mimic.downloader.data.platform.ContentKind
import com.example.mimic.downloader.data.platform.Platforms
import com.example.mimic.downloader.data.platform.YouTubeListType
import com.example.mimic.downloader.data.platform.YouTubeProvider
import com.example.mimic.downloader.data.provider.AnalysisJsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 4.6 playlist downloads: exact list detection, Mix previews, duplicates, and song selection. */
class PlaylistSelectionTest {

    // ---- list detection ----------------------------------------------------------------------

    @Test
    fun `list ids are classified by prefix`() {
        assertEquals(YouTubeListType.PLAYLIST, YouTubeListType.of("PLBCF2DAC6FFB574DE"))
        assertEquals(YouTubeListType.ALBUM, YouTubeListType.of("OLAK5uy_kXyz"))
        assertEquals(YouTubeListType.CURATED, YouTubeListType.of("RDCLAK5uy_abc"))
        assertEquals(YouTubeListType.MIX, YouTubeListType.of("RDdQw4w9WgXcQ"))
        assertEquals(YouTubeListType.MIX, YouTubeListType.of("RDMMxyz"))
        assertEquals(YouTubeListType.CHANNEL_UPLOADS, YouTubeListType.of("UUabc"))
        assertEquals(YouTubeListType.PERSONAL, YouTubeListType.of("LL"))
        assertEquals(YouTubeListType.PERSONAL, YouTubeListType.of("WL"))
        assertTrue(YouTubeListType.PLAYLIST.isExpandable && YouTubeListType.ALBUM.isExpandable && YouTubeListType.CURATED.isExpandable)
        assertFalse(YouTubeListType.MIX.isExpandable)
        assertNull(YouTubeListType.MIX.refusal)
        assertNotNull(YouTubeListType.CHANNEL_UPLOADS.refusal)
        assertNull(YouTubeListType.PLAYLIST.refusal)
    }

    @Test
    fun `youtube and youtube music playlist links are playlists with their list id`() {
        val cases = mapOf(
            "https://www.youtube.com/playlist?list=PLBCF2DAC6FFB574DE" to "PLBCF2DAC6FFB574DE",
            "https://music.youtube.com/playlist?list=PLBCF2DAC6FFB574DE&si=abc" to "PLBCF2DAC6FFB574DE",
            "https://music.youtube.com/browse/VLPLBCF2DAC6FFB574DE" to "PLBCF2DAC6FFB574DE",
            "https://www.youtube.com/watch?list=PLBCF2DAC6FFB574DE" to "PLBCF2DAC6FFB574DE",
            "https://m.youtube.com/playlist?list=OLAK5uy_kXyz" to "OLAK5uy_kXyz",
        )
        cases.forEach { (url, id) ->
            val d = Platforms.detect(url)
            assertEquals(url, ContentKind.PLAYLIST, d.kind)
            assertEquals(url, id, d.listId)
        }
    }

    @Test
    fun `a video opened from a playlist or a mix offers the choice, from a channel list it does not`() {
        val fromPlaylist = Platforms.detect("https://www.youtube.com/watch?v=jNQXAC9IVRw&list=PLBCF2DAC6FFB574DE&index=3")
        assertTrue(fromPlaylist.needsPlaylistChoice)
        assertNull(fromPlaylist.ignoredList)

        val fromMix = Platforms.detect("https://www.youtube.com/watch?v=jNQXAC9IVRw&list=RDjNQXAC9IVRw&start_radio=1")
        assertEquals(ContentKind.VIDEO, fromMix.kind)
        assertTrue(fromMix.needsPlaylistChoice)
        assertTrue(fromMix.playlistContext!!.isMix)
        assertNull(fromMix.ignoredList)
        // Choosing "Preview Mix" analyzes the Mix's own link, which is a Mix preview.
        assertTrue(Platforms.detect(fromMix.playlistContext!!.playlistUrl).isMixPreview)

        val fromChannel = Platforms.detect("https://www.youtube.com/watch?v=jNQXAC9IVRw&list=UUabcdefghij")
        assertFalse(fromChannel.needsPlaylistChoice)
        assertEquals(YouTubeListType.CHANNEL_UPLOADS, fromChannel.ignoredList?.listType)
    }

    @Test
    fun `a pasted mix link opens as a mix preview read from its watch page`() {
        // The link from the bug report (shared from the YouTube app on a Xiaomi phone).
        val d = Platforms.detect("https://youtube.com/playlist?list=RDeCind0-qMaI&playnext=1&si=MFfvEdJ43_29CUU7")
        assertEquals(ContentKind.PLAYLIST, d.kind)
        assertEquals("RDeCind0-qMaI", d.listId)
        assertTrue(d.isMixPreview)
        assertNull(d.listType?.refusal)
        assertEquals("https://www.youtube.com/watch?v=eCind0-qMaI&list=RDeCind0-qMaI", YouTubeProvider.mixUrl("RDeCind0-qMaI"))
        assertEquals("eCind0-qMaI", YouTubeProvider.mixSeed("RDAMVMeCind0-qMaI"))
        assertNull(YouTubeProvider.mixSeed("RDEMabc"))
        assertEquals("https://www.youtube.com/playlist?list=RDEMabc&playnext=1", YouTubeProvider.mixUrl("RDEMabc"))
        // Normal playlists and YouTube Music curated lists are never treated as Mixes.
        assertFalse(Platforms.detect("https://www.youtube.com/playlist?list=PLBCF2DAC6FFB574DE").isMixPreview)
        assertFalse(Platforms.detect("https://music.youtube.com/playlist?list=RDCLAK5uy_abc").isMixPreview)
    }

    // ---- Mix preview pages ------------------------------------------------------------------------

    private fun mixPage(ids: List<String>, hasMore: Boolean, through: Int) = PlaylistInfo(
        sourceUrl = "https://www.youtube.com/playlist?list=RDx",
        title = "Mix",
        uploader = null,
        thumbnailUrl = null,
        entries = ids.mapIndexed { i, id -> PlaylistEntry("T $id", "https://www.youtube.com/watch?v=$id", i + 1, 200, null, videoId = id) },
        id = "RDx",
        mix = MixPage(through, hasMore),
    )

    @Test
    fun `load more appends only new songs after the shown ones, keeping their order`() {
        val first = mixPage(listOf("a", "b", "c"), hasMore = true, through = 25)
        // YouTube reshuffles: the next window repeats b and lists d, e.
        val next = mixPage(listOf("b", "d", "e"), hasMore = true, through = 50)
        val merged = first.withMixPage(next, 50)
        assertEquals(listOf("a", "b", "c", "d", "e"), merged.entries.map { it.videoId })
        assertEquals(listOf(1, 2, 3, 4, 5), merged.entries.map { it.index })
        assertEquals(MixPage(50, true), merged.mix)
        // A window with nothing new stops "Load more".
        assertFalse(merged.withMixPage(mixPage(listOf("a", "e"), true, 75), 75).mix!!.hasMore)
    }

    @Test
    fun `mix pages are 25 songs and stop at the safety limit`() {
        assertEquals(1..25, MixPreview.firstPage())
        assertEquals(26..50, MixPreview.nextPage(MixPage(25, true)))
        assertEquals(76..100, MixPreview.nextPage(MixPage(75, true)))
        assertNull(MixPreview.nextPage(MixPage(100, true)))
        assertNull(MixPreview.nextPage(MixPage(25, false)))
        val ids = (1..25).map { "n$it" }
        assertFalse(mixPage(listOf("a"), true, 75).withMixPage(mixPage(ids, true, 100), 100).mix!!.hasMore)
    }

    @Test
    fun `ticking 15 of 21 mix songs downloads exactly those 15`() {
        val pl = mixPage((1..21).map { "m" + it.toString().padStart(10, '0') }, hasMore = true, through = 25)
        val ticked = pl.entries.filterIndexed { i, _ -> i % 7 != 3 }.take(15).map { it.key }.toSet()
        val d = DetectPhase.Detected(AnalysisResult.Collection(pl, Platforms.detect(pl.sourceUrl)), selection = ticked)
        assertEquals(15, d.selectedEntries.size)
        assertEquals(ticked, d.selectedEntries.map { it.key }.toSet())
    }

    // ---- parser: exact entries, duplicates, counts --------------------------------------------

    private val mapper = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @Test
    fun `parser keeps a repeated video but marks it, and drops extractor repeats`() {
        val json = """
            {"_type":"playlist","id":"PLx","title":"L","playlist_count":5,"entries":[
              {"ie_key":"Youtube","id":"aaaaaaaaaaa","title":"A","playlist_index":1},
              {"ie_key":"Youtube","id":"bbbbbbbbbbb","title":"B","playlist_index":2},
              {"ie_key":"Youtube","id":"bbbbbbbbbbb","title":"B","playlist_index":2},
              {"ie_key":"Youtube","id":"aaaaaaaaaaa","title":"A again","playlist_index":3}
            ]}
        """.trimIndent()
        val pl = (AnalysisJsonParser.parse(mapper.readTree(json), "u", mapper) as AnalysisJsonParser.Parsed.Collection).playlist
        assertEquals(3, pl.entries.size)
        assertEquals(1, pl.entries[2].duplicateOf)
        assertEquals(2, pl.selectable.size)
        assertEquals(1, pl.duplicateCount)
        assertEquals(5, pl.reportedCount)
        assertEquals(2, pl.hiddenCount)
        assertEquals("https://www.youtube.com/watch?v=aaaaaaaaaaa", pl.entries[0].url)
    }

    @Test
    fun `json is found among item progress lines`() {
        val out = "[mimic-item] aaa\n[mimic-item] bbb\n{\"_type\":\"playlist\"}\n"
        assertEquals("{\"_type\":\"playlist\"}", AnalysisJsonParser.jsonPart(out))
    }

    // ---- selection ----------------------------------------------------------------------------

    private fun playlist(n: Int) = PlaylistInfo(
        sourceUrl = "https://www.youtube.com/playlist?list=PLx",
        title = "Fifteen",
        uploader = null,
        thumbnailUrl = null,
        entries = (1..n).map { i ->
            val id = "v" + i.toString().padStart(10, '0')
            PlaylistEntry("Song $i", "https://www.youtube.com/watch?v=$id", i, 200, null, videoId = id)
        },
        id = "PLx",
    )

    @Test
    fun `deselecting 2 of 15 songs downloads exactly the other 13`() {
        val pl = playlist(15)
        val all = pl.selectable.map { it.key }.toSet()
        val skip = setOf(pl.entries[3].key, pl.entries[9].key)
        val d = DetectPhase.Detected(AnalysisResult.Collection(pl, Platforms.detect(pl.sourceUrl)), selection = all - skip)
        val chosen = d.selectedEntries
        assertEquals(13, chosen.size)
        assertTrue(chosen.none { it.key in skip })
        assertEquals(chosen.sortedBy { it.index }, chosen)
    }

    @Test
    fun `changing the format keeps the selection`() {
        val pl = playlist(15)
        val d = DetectPhase.Detected(AnalysisResult.Collection(pl, Platforms.detect(pl.sourceUrl)), selection = setOf(pl.entries[0].key))
        val changed = d.copy(quickFormat = QuickFormat.BestVideo(720))
        assertEquals(d.selectedEntries, changed.selectedEntries)
    }

    @Test
    fun `unavailable and repeated entries are never selected`() {
        val base = playlist(3).entries
        val entries = listOf(base[0], base[1].copy(unavailableReason = "Private video"), base[2].copy(videoId = base[0].videoId, duplicateOf = 1))
        val pl = playlist(3).copy(entries = entries)
        val d = DetectPhase.Detected(AnalysisResult.Collection(pl, Platforms.detect(pl.sourceUrl)), selection = entries.map { it.key }.toSet())
        assertEquals(listOf(base[0]), d.selectedEntries)
    }
}
