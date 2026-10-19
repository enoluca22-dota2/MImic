package com.example.mimic.downloader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidatorTest {

    @Test
    fun `accepts a normal https url`() {
        assertTrue(UrlValidator.isValid("https://example.com/watch?v=abc123"))
    }

    @Test
    fun `accepts a normal http url`() {
        assertTrue(UrlValidator.isValid("http://example.com/video"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("https://example.com/x", UrlValidator.normalize("  https://example.com/x  "))
    }

    @Test
    fun `rejects blank input`() {
        assertNull(UrlValidator.normalize(""))
        assertNull(UrlValidator.normalize("   "))
    }

    @Test
    fun `rejects a non-url string`() {
        assertNull(UrlValidator.normalize("not a url at all"))
    }

    @Test
    fun `rejects a scheme without a host`() {
        assertNull(UrlValidator.normalize("https:///path"))
    }

    @Test
    fun `rejects urls missing a scheme`() {
        assertNull(UrlValidator.normalize("example.com/video"))
    }

    @Test
    fun `rejects ftp and other unsupported schemes`() {
        assertNull(UrlValidator.normalize("ftp://example.com/file"))
    }

    @Test
    fun `extracts the first url from a share-sheet text blob`() {
        val text = "Check this out! https://example.com/watch?v=xyz — pretty cool"
        assertEquals("https://example.com/watch?v=xyz", UrlValidator.extractFirstUrl(text))
    }

    @Test
    fun `returns null when text has no url`() {
        assertNull(UrlValidator.extractFirstUrl("no links here"))
    }
}
