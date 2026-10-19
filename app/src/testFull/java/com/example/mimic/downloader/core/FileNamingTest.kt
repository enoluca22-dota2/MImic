package com.example.mimic.downloader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileNamingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `strips illegal filesystem characters`() {
        assertEquals("My Video", FileNaming.sanitize("My:Video?"))
    }

    @Test
    fun `long multi-byte titles are cut to a filesystem-safe byte length without splitting characters`() {
        val title = "日本語のタイトル🎵".repeat(20)
        val sanitized = FileNaming.sanitize(title)
        assertTrue(sanitized.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(title.startsWith(sanitized))
        assertFalse(sanitized.last().isHighSurrogate())
    }

    @Test
    fun `percent signs survive sanitizing`() {
        assertEquals("100% Hits", FileNaming.sanitize("100% Hits"))
    }

    @Test
    fun `collapses repeated whitespace`() {
        assertEquals("A B C", FileNaming.sanitize("A   B\tC"))
    }

    @Test
    fun `prevents path traversal segments`() {
        val sanitized = FileNaming.sanitize("../../etc/passwd")
        assertFalse(sanitized.contains(".."))
    }

    @Test
    fun `falls back to a generic name when nothing usable remains`() {
        assertEquals("MImic Download", FileNaming.sanitize("???///"))
    }

    @Test
    fun `truncates very long titles`() {
        val huge = "a".repeat(500)
        assertTrue(FileNaming.sanitize(huge).length <= 120)
    }

    @Test
    fun `dedupe against directory returns the base name when free`() {
        val dir = tempFolder.newFolder()
        val name = FileNaming.dedupeAgainstDirectory(dir, "Video", "mp4")
        assertEquals("Video.mp4", name)
    }

    @Test
    fun `dedupe against directory appends an incrementing suffix on collision`() {
        val dir = tempFolder.newFolder()
        java.io.File(dir, "Video.mp4").createNewFile()
        java.io.File(dir, "Video (1).mp4").createNewFile()
        val name = FileNaming.dedupeAgainstDirectory(dir, "Video", "mp4")
        assertEquals("Video (2).mp4", name)
    }

    @Test
    fun `dedupe against names set avoids collisions`() {
        val existing = setOf("Song.mp3", "Song (1).mp3")
        val name = FileNaming.dedupeAgainstNames(existing, "Song", "mp3")
        assertEquals("Song (2).mp3", name)
    }

    @Test
    fun `never overwrites by construction`() {
        val dir = tempFolder.newFolder()
        java.io.File(dir, "Clip.mp4").createNewFile()
        val name = FileNaming.dedupeAgainstDirectory(dir, "Clip", "mp4")
        assertFalse(java.io.File(dir, name).exists())
    }
}
