package com.example.mimic.downloader.data.analyzer

import com.example.mimic.downloader.data.platform.Platforms
import com.example.mimic.downloader.data.provider.ProviderException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisErrorTest {

    @Test
    fun `unsupported links get the fixed message`() {
        val info = ProviderException.UnsupportedUrl().toAnalysisError(Platforms.detect("https://vimeo.com/1"))
        assertEquals("Unsupported website. YouTube and TikTok are currently supported.", info.message)
    }

    @Test
    fun `extraction failures name the site`() {
        val yt = ProviderException.classify("ERROR: [youtube] x: Some unexpected extractor error")
            .toAnalysisError(Platforms.detect("https://www.youtube.com/watch?v=x"))
        assertEquals("Unable to retrieve this media.", yt.title)
        val tt = ProviderException.classify("ERROR: [TikTok] 1: Unable to extract webpage video data")
            .toAnalysisError(Platforms.detect("https://www.tiktok.com/@u/video/1"))
        assertEquals("Unable to retrieve this media.", tt.title)
    }

    @Test
    fun `running live streams are explained`() {
        val info = ProviderException.classify("ERROR: [youtube] jfKfPfyJRdk: This live stream recording is not available.")
            .toAnalysisError(Platforms.detect("https://www.youtube.com/live/jfKfPfyJRdk"))
        assertTrue(info.message.contains("live stream"))
    }
}
