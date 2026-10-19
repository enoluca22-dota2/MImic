package com.example.mimic.downloader.download

import com.example.mimic.downloader.data.provider.YtDlpUpdater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** FFmpeg command line (metadata/artwork), leftover-process matching and the yt-dlp update throttle. */
class ProcessAndUpdateTest {

    private val input = File("/cache/ytd_downloads/7/media.webm")
    private val output = File("/cache/ytd_downloads/7/media.mp3")

    @Test
    fun `mp3 command maps audio only, tags it and keeps VBR null`() {
        val cmd = AudioConverter.buildCommand("ffmpeg", input, output, null, AudioConverter.Metadata("Song", "Artist"), null)
        assertEquals("ffmpeg", cmd.first())
        assertEquals(output.absolutePath, cmd.last())
        assertTrue(cmd.containsAll(listOf("-map", "0:a:0", "-c:a", "libmp3lame", "-q:a", "2")))
        assertTrue(cmd.windowed(2).any { it == listOf("-metadata", "title=Song") })
        assertTrue(cmd.windowed(2).any { it == listOf("-metadata", "artist=Artist") })
        assertFalse("no cover input without artwork", cmd.contains("attached_pic"))
        assertFalse(cmd.contains("-b:a"))
    }

    @Test
    fun `mp3 command embeds the cover as attached picture`() {
        val cover = File("/cache/ytd_downloads/7/cover.img")
        val cmd = AudioConverter.buildCommand("ffmpeg", input, output, 320, null, cover)
        assertEquals(2, cmd.count { it == "-i" })
        assertTrue(cmd.windowed(2).any { it == listOf("-map", "1:v:0") })
        assertTrue(cmd.windowed(2).any { it == listOf("-disposition:v:0", "attached_pic") })
        assertTrue(cmd.windowed(2).any { it == listOf("-b:a", "320k") })
        assertTrue(cmd.windowed(2).any { it == listOf("-id3v2_version", "3") })
    }

    @Test
    fun `blank metadata is not written`() {
        val cmd = AudioConverter.buildCommand("ffmpeg", input, output, 192, AudioConverter.Metadata(" ", null), null)
        assertFalse(cmd.any { it.startsWith("title=") || it.startsWith("artist=") })
    }

    @Test
    fun `leftover processes are matched by the job temp dir only`() {
        val marker = "/data/user/0/com.example.mimic.downloader/cache/ytd_downloads/7"
        val merge = listOf("libffmpeg.so", "-i", "$marker/media.f137.mp4", "-i", "$marker/media.f140.m4a", "$marker/media.temp.mp4").joinToString("\u0000")
        val other = listOf("libffmpeg.so", "-i", "/data/user/0/com.example.mimic.downloader/cache/ytd_downloads/8/media.mp4").joinToString("\u0000")
        assertTrue(ProcessReaper.cmdlineMatches(merge, marker))
        assertFalse(ProcessReaper.cmdlineMatches(other, marker))
        assertFalse(ProcessReaper.cmdlineMatches(merge, ""))
    }

    @Test
    fun `yt-dlp is not updated on every launch`() {
        val day = YtDlpUpdater.AUTO_INTERVAL_MS
        val now = 10 * day
        assertTrue("never checked", YtDlpUpdater.isDue(now, 0L, busy = false, online = true))
        assertFalse("checked an hour ago", YtDlpUpdater.isDue(now, now - 3_600_000, busy = false, online = true))
        assertTrue("a day later", YtDlpUpdater.isDue(now, now - day, busy = false, online = true))
        assertFalse("downloads running", YtDlpUpdater.isDue(now, 0L, busy = true, online = true))
        assertFalse("offline", YtDlpUpdater.isDue(now, 0L, busy = false, online = false))
        assertTrue("clock moved backwards", YtDlpUpdater.isDue(now, now + day, busy = false, online = true))
    }
}
