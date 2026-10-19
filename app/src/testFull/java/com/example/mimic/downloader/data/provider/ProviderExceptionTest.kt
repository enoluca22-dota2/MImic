package com.example.mimic.downloader.data.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderExceptionTest {

    private fun classify(text: String) = ProviderException.classify(text)

    @Test
    fun `maps common yt-dlp failures to user-facing types`() {
        assertTrue(classify("ERROR: [youtube] abc: Private video. Sign in if you've been granted access") is ProviderException.AuthenticationRequired)
        assertTrue(classify("ERROR: [youtube] abc: Video unavailable") is ProviderException.VideoUnavailable)
        assertTrue(classify("ERROR: Unsupported URL: https://example.com") is ProviderException.UnsupportedUrl)
        assertTrue(classify("ERROR: unable to download video data: HTTP Error 403: Forbidden") is ProviderException.Blocked)
        assertTrue(classify("HTTP Error 429: Too Many Requests") is ProviderException.RateLimited)
        assertTrue(classify("HTTP Error 503: Service Unavailable") is ProviderException.ServerError)
        assertTrue(classify("The read operation timed out") is ProviderException.Timeout)
        assertTrue(classify("[Errno 28] No space left on device") is ProviderException.StorageFull)
        assertTrue(classify("Requested format is not available") is ProviderException.FormatUnavailable)
        assertTrue(classify("Unable to download webpage: <urlopen error [Errno 7] No address associated with hostname>") is ProviderException.NetworkError)
        assertTrue(classify("WARNING: [Instagram] X: Instagram API is not granting access") is ProviderException.AuthenticationRequired)
        assertTrue(classify("ERROR: [twitter] 1: No video could be found in this tweet") is ProviderException.NoFormatsAvailable)
        assertTrue(classify("ERROR: [generic] page: Unable to download webpage: HTTP Error 404: Not Found") is ProviderException.VideoUnavailable)
        assertTrue(classify("ERROR: [TikTok] 1: Your IP address is blocked from accessing this post") is ProviderException.Blocked)
        assertTrue(classify("ERROR: [vimeo] 1: The web client only works when logged-in. Use --cookies") is ProviderException.AuthenticationRequired)
    }

    @Test
    fun `only transient failures are retried automatically`() {
        assertTrue(classify("HTTP Error 503").isRetryable)
        assertTrue(classify("connection reset by peer").isRetryable)
        assertFalse(classify("Private video").isRetryable)
        assertFalse(classify("Video unavailable").isRetryable)
        assertFalse(classify("No space left on device").isRetryable)
    }

    @Test
    fun `raw provider text never becomes the user message`() {
        val raw = "ERROR: [generic] Traceback (most recent call last): File \"yt_dlp/x.py\", line 1"
        val error = classify(raw)
        assertFalse(error.message.orEmpty().contains("Traceback"))
        assertTrue(error.detail.orEmpty().contains("Traceback"))
    }

    @Test
    fun `user facing texts match the spec`() {
        assertEquals("Connection failed. Check your internet connection and try again.", ProviderException.NoInternet().message)
        assertEquals("Connection failed. Check your internet connection and try again.", classify("Unable to download webpage: timed out").message)
        assertEquals("Download failed. You can retry this item.", classify("ERROR: something odd happened").message)
        assertEquals("Unsupported website. YouTube and TikTok are currently supported.", ProviderException.UnsupportedUrl().message)
        assertFalse(classify("ERROR: [youtube] x: Traceback (most recent call last)").message.orEmpty().contains("Traceback"))
    }
}
