package com.example.mimic.downloader.download

import com.example.mimic.downloader.data.local.db.DownloadEntity
import com.example.mimic.downloader.data.model.DownloadCategory
import com.example.mimic.downloader.data.model.DownloadStatus
import com.example.mimic.downloader.data.model.FormatKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real, byte-based progress. The lines below are the exact output of yt-dlp 2026.08 for a
 * YouTube 360p video+audio download with the app's templates (captured on a desktop run).
 */
class ProgressTrackerTest {

    @Test
    fun `merge download progresses over both streams without restarting`() {
        val t = ProgressTracker()
        assertNull(t.onLine("[ytdsizes] [223779, 252182]|[223778, 252180]|NA|475961"))
        val total = 223779L + 252182L

        val first = t.onLine("[ytdprog] downloading|1024|223779|NA|NA|NA|395")!!
        assertEquals(total, first.totalBytes)
        assertEquals(1024f / total, first.fraction!!, 1e-4f)

        val videoDone = t.onLine("[ytdprog] finished|223779|223779|NA|460942.15|NA|395")!!
        assertEquals(223779f / total, videoDone.fraction!!, 1e-4f) // ~47%, not 100%

        val audioHalf = t.onLine("[ytdprog] downloading|126091|252182|NA|4153425.5|0|251")!!
        assertTrue(audioHalf.fraction!! > videoDone.fraction!!) // keeps going up across streams

        val done = t.onLine("[ytdprog] finished|252182|252182|NA|4153425.5|NA|251")!!
        assertEquals(1f, done.fraction!!, 1e-6f)
        assertEquals(total, done.downloadedBytes)
    }

    @Test
    fun `progress goes 1 percent at a time, not 0 then 100`() {
        val t = ProgressTracker()
        t.onLine("[ytdsizes] NA|NA|1000000|NA")
        val seen = (1..99).map { pct -> t.onLine("[ytdprog] downloading|${pct * 10_000}|1000000|NA|500000|1|18")!!.percent!! } +
            t.onLine("[ytdprog] finished|1000000|1000000|NA|500000|NA|18")!!.percent!!
        assertEquals((1..100).map { it.toFloat() }, seen.map { Math.round(it).toFloat() })
    }

    @Test
    fun `single stream with empty per-stream lists uses the filesize field`() {
        // Real TikTok output from the app (yt-dlp 2026.08.19).
        val t = ProgressTracker()
        t.onLine("[ytdsizes] []|[]|2953029|NA")
        val s = t.onLine("[ytdprog] downloading|1047552|2953029|NA|1018272.35|1|h264_540p_2240963-0")!!
        assertEquals(2953029L, s.totalBytes)
        assertEquals(1047552f / 2953029f, s.fraction!!, 1e-5f)
    }

    @Test
    fun `starts at zero`() {
        val t = ProgressTracker()
        t.onLine("[ytdsizes] NA|NA|52100000|NA")
        val s = t.onLine("[ytdprog] downloading|0|52100000|NA|NA|NA|22")!!
        assertEquals(0f, s.fraction!!, 0f)
        assertEquals(0L, s.downloadedBytes)
    }

    @Test
    fun `reaches 100 percent only when yt-dlp reports the download finished`() {
        val t = ProgressTracker()
        // Announced (approximate) size smaller than the real stream.
        t.onLine("[ytdsizes] NA|NA|NA|1000")
        val beyond = t.onLine("[ytdprog] downloading|1000|NA|1000|NA|NA|hls")!!
        assertTrue("not done yet: ${beyond.fraction}", beyond.fraction!! < 1f)
        val stillGoing = t.onLine("[ytdprog] downloading|1400|NA|1500|NA|NA|hls")!!
        assertTrue(stillGoing.fraction!! < 1f)
        assertEquals(1f, t.onLine("[ytdprog] finished|1500|1500|NA|NA|NA|hls")!!.fraction!!, 0f)
    }

    @Test
    fun `progress only increases`() {
        val t = ProgressTracker()
        t.onLine("[ytdsizes] [3000, 1000]|[NA, NA]|NA|NA")
        val fractions = listOf(
            "downloading|100|3000|NA|NA|NA|137", "downloading|1500|3000|NA|NA|NA|137", "finished|3000|3000|NA|NA|NA|137",
            "downloading|10|1000|NA|NA|NA|140", "downloading|900|1000|NA|NA|NA|140", "finished|1000|1000|NA|NA|NA|140",
        ).map { t.onLine("[ytdprog] $it")!!.fraction!! }
        assertEquals(fractions.sorted(), fractions)
        assertEquals(1f, fractions.last(), 0f)
        assertTrue(fractions.dropLast(1).all { it < 1f })
    }

    @Test
    fun `unknown total means no percentage`() {
        val t = ProgressTracker()
        t.onLine("[ytdsizes] NA|NA|NA|NA")
        val s = t.onLine("[ytdprog] downloading|25800000|NA|NA|1200000|NA|hls-1")!!
        assertNull(s.fraction)
        assertNull(s.totalBytes)
        assertEquals(25_800_000L, s.downloadedBytes)
        // Becomes determinate as soon as yt-dlp knows the size.
        val later = t.onLine("[ytdprog] downloading|25800000|NA|54630000|1200000|25|hls-1")!!
        assertNotNull(later.fraction)
        assertEquals(25.8f / 54.63f, later.fraction!!, 1e-3f)
    }

    @Test
    fun `merge whose second stream size is unknown stays indeterminate until it is`() {
        val t = ProgressTracker()
        t.onLine("[ytdsizes] [1000, NA]|[NA, NA]|NA|NA")
        assertNull(t.onLine("[ytdprog] downloading|500|1000|NA|NA|NA|137")!!.fraction)
        t.onLine("[ytdprog] finished|1000|1000|NA|NA|NA|137")
        val audio = t.onLine("[ytdprog] downloading|100|400|NA|NA|NA|140")!!
        assertEquals(1100f / 1400f, audio.fraction!!, 1e-4f)
    }

    @Test
    fun `shrinking size estimates never move the bar backwards`() {
        val t = ProgressTracker()
        t.onLine("[ytdsizes] NA|NA|NA|NA")
        val a = t.onLine("[ytdprog] downloading|500|NA|1000|NA|NA|x")!!.fraction!!
        val b = t.onLine("[ytdprog] downloading|520|NA|1300|NA|NA|x")!!.fraction!!
        assertTrue(b >= a)
    }

    @Test
    fun `other lines are ignored`() {
        val t = ProgressTracker()
        assertNull(t.onLine("[youtube] abc: Downloading webpage"))
        assertNull(t.onLine("[download] Destination: media.f395.mp4"))
        assertNull(t.onLine("[ytdprog] garbage"))
    }

    private fun row(status: DownloadStatus, percent: Float = 0f, done: Long = 0, total: Long? = null, error: String? = null) = DownloadEntity(
        sourceUrl = "u", webpageUrl = "u", title = "T", thumbnailUrl = null, extractorKey = null, formatId = "b",
        formatKind = FormatKind.VIDEO, resolutionLabel = null, container = "mp4", videoCodec = null, audioCodec = null,
        requiresAudioMerge = true, requiresAudioExtraction = false, category = DownloadCategory.VIDEO, fileBaseName = "t",
        status = status, progressPercent = percent, downloadedBytes = done, totalBytes = total, errorMessage = error,
        createdAt = 0, updatedAt = 0,
    )

    @Test
    fun `display rule matches the spec texts`() {
        val mb = 1024L * 1024
        val known = row(DownloadStatus.DOWNLOADING, 47f, (24.6 * mb).toLong(), (52.1 * mb).toLong())
        assertEquals(0.47f, ProgressDisplay.fraction(known)!!, 1e-6f)
        assertTrue(ProgressDisplay.text(known).startsWith("Downloading 47% · 24.6 MB / 52.1 MB"))

        val unknown = row(DownloadStatus.DOWNLOADING, 0f, (24.6 * mb).toLong(), null)
        assertNull(ProgressDisplay.fraction(unknown))
        assertEquals("Downloading… 24.6 MB", ProgressDisplay.text(unknown))

        // Processing never shows the download's percentage.
        val processing = row(DownloadStatus.PROCESSING, 100f, 10, 10)
        assertNull(ProgressDisplay.fraction(processing))
        assertEquals("Processing…", ProgressDisplay.text(processing))

        assertNull(ProgressDisplay.fraction(row(DownloadStatus.FETCHING_INFO)))
        assertEquals("Downloading…", ProgressDisplay.text(row(DownloadStatus.DOWNLOADING)))
        assertNull("retry wait is indeterminate", ProgressDisplay.fraction(row(DownloadStatus.DOWNLOADING, 30f, 3, 10, error = "Connection failed.")))
    }
}
