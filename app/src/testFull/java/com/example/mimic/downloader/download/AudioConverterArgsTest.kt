package com.example.mimic.downloader.download

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioConverterArgsTest {

    @Test
    fun `original quality VBR keeps a null bitrate as encoder-chosen VBR`() {
        assertEquals(listOf("-q:a", AudioConverter.VBR_QUALITY), AudioConverter.encoderArgs(null))
    }

    @Test
    fun `fixed bitrates are passed through as constant bitrate`() {
        assertEquals(listOf("-b:a", "320k"), AudioConverter.encoderArgs(320))
        assertEquals(listOf("-b:a", "128k"), AudioConverter.encoderArgs(128))
    }
}
