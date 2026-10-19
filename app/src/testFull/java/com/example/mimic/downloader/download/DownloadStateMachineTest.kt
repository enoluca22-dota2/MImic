package com.example.mimic.downloader.download

import com.example.mimic.downloader.data.local.db.DownloadEntity
import com.example.mimic.downloader.data.model.DownloadCategory
import com.example.mimic.downloader.data.model.DownloadStatus
import com.example.mimic.downloader.data.model.DownloadStatus.CANCELLED
import com.example.mimic.downloader.data.model.DownloadStatus.COMPLETED
import com.example.mimic.downloader.data.model.DownloadStatus.DOWNLOADING
import com.example.mimic.downloader.data.model.DownloadStatus.FAILED
import com.example.mimic.downloader.data.model.DownloadStatus.FETCHING_INFO
import com.example.mimic.downloader.data.model.DownloadStatus.PAUSED
import com.example.mimic.downloader.data.model.DownloadStatus.PROCESSING
import com.example.mimic.downloader.data.model.DownloadStatus.QUEUED
import com.example.mimic.downloader.data.model.FormatKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The job state machine, the yt-dlp output → state mapping and the notification texts. */
class DownloadStateMachineTest {

    @Test
    fun `happy path is legal`() {
        val path = listOf(QUEUED, FETCHING_INFO, DOWNLOADING, PROCESSING, COMPLETED)
        path.zipWithNext().forEach { (a, b) -> assertTrue("$a→$b", DownloadStatus.canTransition(a, b)) }
    }

    @Test
    fun `finished jobs never jump back into work except via retry`() {
        listOf(COMPLETED, FAILED, CANCELLED).forEach { done ->
            listOf(FETCHING_INFO, DOWNLOADING, PROCESSING, COMPLETED, PAUSED).forEach { next ->
                assertFalse("$done→$next", DownloadStatus.canTransition(done, next))
            }
        }
        assertTrue(DownloadStatus.canTransition(FAILED, QUEUED))
        assertTrue(DownloadStatus.canTransition(CANCELLED, QUEUED))
        assertFalse(DownloadStatus.canTransition(COMPLETED, QUEUED))
    }

    @Test
    fun `cancel is possible from every unfinished state, pause only before processing`() {
        listOf(QUEUED, FETCHING_INFO, DOWNLOADING, PROCESSING, PAUSED).forEach { assertTrue(it.name, it.canCancel) }
        listOf(COMPLETED, FAILED, CANCELLED).forEach { assertFalse(it.name, it.canCancel) }
        assertTrue(DOWNLOADING.canPause)
        assertFalse(PROCESSING.canPause)
        assertTrue(FAILED.canRetry && CANCELLED.canRetry)
        assertFalse(COMPLETED.canRetry)
    }

    @Test
    fun `active set is fetching, downloading and processing`() {
        assertEquals(setOf(FETCHING_INFO, DOWNLOADING, PROCESSING), DownloadStatus.entries.filter { it.isActive }.toSet())
        assertTrue(COMPLETED.isTerminal && FAILED.isTerminal && CANCELLED.isTerminal)
    }

    @Test
    fun `yt-dlp output lines drive the state`() {
        assertNull(DownloadEngine.stateForOutputLine("[youtube] AAA: Downloading webpage", 0f))
        assertNull(DownloadEngine.stateForOutputLine("[download] Destination: media.f137.mp4", 0f))
        assertEquals(DOWNLOADING, DownloadEngine.stateForOutputLine("[download]  12.5% of 10.00MiB at 1.00MiB/s ETA 00:09", 12.5f))
        assertEquals(PROCESSING, DownloadEngine.stateForOutputLine("[Merger] Merging formats into \"media.mp4\"", 100f))
        assertEquals(PROCESSING, DownloadEngine.stateForOutputLine("[FixupM3u8] Fixing MPEG-TS in MP4 container", 100f))
    }

    private fun entity(id: Long, status: DownloadStatus, batch: String? = null, index: Int? = null, size: Int? = null) = DownloadEntity(
        id = id, sourceUrl = "u$id", webpageUrl = "u$id", title = "Title $id", thumbnailUrl = null, extractorKey = null,
        formatId = "best", formatKind = FormatKind.VIDEO, resolutionLabel = null, container = "mp4", videoCodec = null,
        audioCodec = null, requiresAudioMerge = true, requiresAudioExtraction = false, category = DownloadCategory.VIDEO,
        fileBaseName = "t$id", status = status, createdAt = id, updatedAt = id,
        batchId = batch, batchIndex = index, batchSize = size,
    )

    @Test
    fun `single download notification reads Downloading`() {
        assertEquals("Downloading", DownloadNotifications.progressTitle(entity(1, DOWNLOADING)))
        assertEquals("Title 1", DownloadNotifications.summaryText(listOf(entity(1, DOWNLOADING))))
    }

    @Test
    fun `playlist notification reads Downloading playlist 4 of 18`() {
        val rows = (1..18).map { i ->
            val status = when {
                i <= 2 -> COMPLETED
                i == 3 -> FAILED // a failed item still counts as done; the rest carry on
                i == 4 -> DOWNLOADING
                else -> QUEUED
            }
            entity(i.toLong(), status, "pl-1", i, 18)
        }
        assertEquals("Downloading playlist 4 / 18", DownloadNotifications.progressTitle(rows[3]))
        assertEquals("Downloading playlist 4 / 18", DownloadNotifications.summaryText(rows))
    }

    @Test
    fun `mixed queue falls back to a count`() {
        val rows = listOf(entity(1, DOWNLOADING), entity(2, PROCESSING, "pl", 1, 3), entity(3, QUEUED))
        assertEquals("Downloading 2 items", DownloadNotifications.summaryText(rows))
        assertEquals("Preparing downloads…", DownloadNotifications.summaryText(listOf(entity(1, QUEUED))))
    }
}
