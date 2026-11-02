package com.example.mimic.downloader.ui

import com.example.mimic.downloader.data.model.AnalysisResult
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

/** 4.6 playlist downloads: exact list detection, live loading, Mix previews, duplicates, and song selection. */
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

    // ---- live loading -------------------------------------------------------------------------

    private val mapper = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private fun id(i: Int) = "v" + i.toString().padStart(10, '0')

    /** One item line as yt-dlp prints it while reading (the fields of YtDlpMediaProvider.ITEM_FIELDS). */
    private fun itemJson(pos: Int, videoId: String, count: Int?, title: String? = "Song $pos") =
        """{"id":"$videoId",${title?.let { "\"title\":\"$it\"," } ?: ""}"duration":200,"ie_key":"Youtube","playlist_index":$pos,""" +
            """${count?.let { "\"playlist_count\":$it," } ?: ""}"playlist_title":"Big list","playlist_uploader":"Me"}"""

    private fun stream(n: Int, total: Int?, mix: Boolean = false): AnalysisJsonParser.PlaylistStream {
        val s = AnalysisJsonParser.PlaylistStream("https://www.youtube.com/playlist?list=PLbig", "PLbig", mix)
        (1..n).forEach { s.add(mapper.readTree(itemJson(it, id(it), total))) }
        return s
    }

    @Test
    fun `the list shown while loading is exactly the final list, in order, with the real total`() {
        val partial = stream(120, total = 250).snapshot()
        assertEquals(120, partial.entries.size)
        assertEquals(250, partial.reportedCount)
        assertEquals("Big list", partial.title)
        assertEquals((1..120).toList(), partial.entries.map { it.index })

        val lines = (1..250).joinToString(",") { itemJson(it, id(it), 250) }
        val json = """{"_type":"playlist","id":"PLbig","title":"Big list","playlist_count":250,"entries":[$lines]}"""
        val final = (AnalysisJsonParser.parse(mapper.readTree(json), "u", mapper) as AnalysisJsonParser.Parsed.Collection).playlist
        assertEquals(250, final.entries.size)
        assertEquals(partial.entries, final.entries.take(120))
        assertEquals(stream(250, 250).snapshot().entries, final.entries)
        assertFalse(final.isMixPreview)
    }

    @Test
    fun `an unknown total is not invented, and private items stay in place`() {
        val s = AnalysisJsonParser.PlaylistStream("u", "PLx", mix = false)
        s.add(mapper.readTree(itemJson(1, id(1), null)))
        s.add(mapper.readTree(itemJson(2, id(2), null, title = null)))
        s.add(mapper.readTree(itemJson(3, id(3), null)))
        val pl = s.snapshot()
        assertNull(pl.reportedCount)
        assertEquals(listOf(1, 2, 3), pl.entries.map { it.index })
        assertEquals("Private or removed video", pl.entries[1].unavailableReason)
        assertEquals(2, pl.selectable.size)
    }

    @Test
    fun `200 songs, 50 unticked while loading, downloads exactly the other 150`() {
        var shown: PlaylistInfo? = null
        var selection = emptySet<String>()
        var unticked = emptySet<String>()
        for (n in listOf(40, 100, 160, 200)) {
            val next = stream(n, 200).snapshot()
            selection = mergeSelection(selection, shown, next, tickNew = true, seed = null)
            shown = next
            if (n == 100) {
                // The user unticks 50 of the songs shown so far; the list keeps loading.
                unticked = next.entries.filter { it.index % 2 == 0 }.map { it.key }.toSet()
                selection = selection - unticked
            }
        }
        val lines = (1..200).joinToString(",") { itemJson(it, id(it), 200) }
        val final = (AnalysisJsonParser.parse(mapper.readTree("""{"_type":"playlist","id":"PLbig","entries":[$lines]}"""), "u", mapper) as AnalysisJsonParser.Parsed.Collection).playlist
        selection = mergeSelection(selection, shown, final, tickNew = true, seed = null)
        val d = DetectPhase.Detected(AnalysisResult.Collection(final, Platforms.detect("https://www.youtube.com/playlist?list=PLbig")), selection = selection)
        assertEquals(150, d.selectedEntries.size)
        assertTrue(d.selectedEntries.none { it.key in unticked })
    }

    @Test
    fun `after deselect all, songs that arrive later are not ticked`() {
        val first = stream(25, 60).snapshot()
        val sel = mergeSelection(emptySet(), null, first, tickNew = true, seed = null)
        assertEquals(25, sel.size)
        val later = mergeSelection(emptySet(), first, stream(60, 60).snapshot(), tickNew = false, seed = null)
        assertTrue(later.isEmpty())
    }

    @Test
    fun `a mix preview is read once up to the limit, without repeats, only its first song ticked`() {
        assertEquals(1..100, MixPreview.window)
        // The Mix repeats song 2 at position 4: it is left out and the songs are numbered as shown.
        val ids = listOf("eCind0-qMaI", "aaaaaaaaaaa", "bbbbbbbbbbb", "aaaaaaaaaaa", "ccccccccccc")
        val s = AnalysisJsonParser.PlaylistStream("https://www.youtube.com/watch?v=eCind0-qMaI&list=RDeCind0-qMaI", "RDeCind0-qMaI", mix = true)
        ids.forEachIndexed { i, v -> s.add(mapper.readTree(itemJson(i + 1, v, null))) }
        val pl = s.snapshot()
        assertTrue(pl.isMixPreview)
        assertNull(pl.reportedCount)
        assertEquals(listOf("eCind0-qMaI", "aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), pl.entries.map { it.videoId })
        assertEquals(listOf(1, 2, 3, 4), pl.entries.map { it.index })
        val sel = mergeSelection(emptySet(), null, pl, tickNew = false, seed = YouTubeProvider.mixSeed("RDeCind0-qMaI"))
        assertEquals(setOf("eCind0-qMaI"), sel)
    }

    @Test
    fun `ticking 15 of 21 mix songs downloads exactly those 15`() {
        val pl = stream(21, null, mix = true).snapshot()
        val ticked = pl.entries.filterIndexed { i, _ -> i % 7 != 3 }.take(15).map { it.key }.toSet()
        val d = DetectPhase.Detected(AnalysisResult.Collection(pl, Platforms.detect("https://www.youtube.com/playlist?list=RDeCind0-qMaI")), selection = ticked)
        assertEquals(15, d.selectedEntries.size)
        assertEquals(ticked, d.selectedEntries.map { it.key }.toSet())
    }

    // ---- parser: exact entries, duplicates, counts --------------------------------------------

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
