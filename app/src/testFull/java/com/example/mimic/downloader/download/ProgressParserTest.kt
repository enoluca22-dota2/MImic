package com.example.mimic.downloader.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressParserTest {

    @Test
    fun `parses total size and speed from a standard download line`() {
        val line = "[download]  42.0% of   10.00MiB at    1.20MiB/s ETA 00:07"
        val parsed = ProgressParser.parse(line)
        assertEquals(10L * 1024 * 1024, parsed.totalBytes)
        assertEquals((1.20 * 1024 * 1024).toLong(), parsed.speedBytesPerSecond)
    }

    @Test
    fun `parses an approximate size prefixed with a tilde`() {
        val line = "[download]  10.0% of ~  50.00MiB at    2.00MiB/s ETA 00:20"
        val parsed = ProgressParser.parse(line)
        assertEquals(50L * 1024 * 1024, parsed.totalBytes)
    }

    @Test
    fun `parses gigabyte and kilobyte units`() {
        val gig = ProgressParser.parse("[download]   5.0% of    1.50GiB at   10.00MiB/s ETA 02:00")
        assertEquals((1.5 * 1024 * 1024 * 1024).toLong(), gig.totalBytes)

        val kilo = ProgressParser.parse("[download]  90.0% of  512.00KiB at   50.00KiB/s ETA 00:01")
        assertEquals((512.0 * 1024).toLong(), kilo.totalBytes)
    }

    @Test
    fun `returns nulls for a line with no size or speed information`() {
        val parsed = ProgressParser.parse("[youtube] Extracting URL: https://example.com")
        assertNull(parsed.totalBytes)
        assertNull(parsed.speedBytesPerSecond)
    }

    @Test
    fun `still extracts size when speed is absent`() {
        val parsed = ProgressParser.parse("[download] 100% of 10.00MiB in 00:08")
        assertEquals(10L * 1024 * 1024, parsed.totalBytes)
        assertNull(parsed.speedBytesPerSecond)
    }
}
