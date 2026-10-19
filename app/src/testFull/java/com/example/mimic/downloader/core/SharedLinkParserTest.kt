package com.example.mimic.downloader.core

import com.example.mimic.downloader.core.SharedLinkParser.ACTION_SEND
import com.example.mimic.downloader.core.SharedLinkParser.ACTION_VIEW
import com.example.mimic.downloader.core.SharedLinkParser.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Share (ACTION_SEND) and "Open with" (ACTION_VIEW) intents, as MainActivity receives them. */
class SharedLinkParserTest {

    private fun send(text: CharSequence?, subject: String? = null, type: String? = "text/plain", data: String? = null) =
        SharedLinkParser.parse(ACTION_SEND, type, text, subject, data)

    private fun links(r: Result?) = (r as Result.Links).urls

    @Test
    fun `youtube app share keeps the link`() {
        assertEquals(listOf("https://youtu.be/AAA?si=x"), links(send("https://youtu.be/AAA?si=x")))
    }

    @Test
    fun `youtube playlist share keeps every parameter`() {
        val url = "https://www.youtube.com/watch?v=AAA&list=BBB&index=5"
        assertEquals(listOf(url), links(send(url)))
        assertEquals(listOf("https://www.youtube.com/playlist?list=PL1"), links(send("Watch this playlist: https://www.youtube.com/playlist?list=PL1")))
    }

    @Test
    fun `tiktok share with surrounding text`() {
        val text = "Check out this video! https://vm.tiktok.com/ZMabc123/ #fyp"
        assertEquals(listOf("https://vm.tiktok.com/ZMabc123/"), links(send(text)))
    }

    @Test
    fun `extra text wins, subject and data are fallbacks, duplicates removed`() {
        assertEquals(listOf("https://youtu.be/S"), links(send(null, subject = "https://youtu.be/S")))
        assertEquals(listOf("https://youtu.be/D"), links(send("", data = "https://youtu.be/D")))
        assertEquals(
            listOf("https://youtu.be/A", "https://youtu.be/B"),
            links(send("https://youtu.be/A https://youtu.be/B", subject = "https://youtu.be/A")),
        )
    }

    @Test
    fun `empty and malformed shares say so instead of doing nothing`() {
        assertTrue(send("") is Result.NoLink)
        assertTrue(send(null) is Result.NoLink)
        assertEquals("just words", (send("just words") as Result.NoLink).sharedText)
        assertTrue(send("htp:/broken youtube") is Result.NoLink)
        assertTrue(send("https://", type = "text/plain") is Result.NoLink)
        // A shared image is not a link.
        assertTrue(send("https://youtu.be/A", type = "image/png") is Result.NoLink)
    }

    @Test
    fun `view intent opens the tapped link unchanged`() {
        val url = "https://www.youtube.com/watch?v=AAA&list=BBB&index=5"
        assertEquals(listOf(url), links(SharedLinkParser.parse(ACTION_VIEW, null, null, null, url)))
        assertEquals(
            listOf("https://www.tiktok.com/@u/video/123"),
            links(SharedLinkParser.parse(ACTION_VIEW, null, null, null, "https://www.tiktok.com/@u/video/123")),
        )
        assertTrue(SharedLinkParser.parse(ACTION_VIEW, null, null, null, null) is Result.NoLink)
        assertTrue(SharedLinkParser.parse(ACTION_VIEW, null, null, null, "content://x/y") is Result.NoLink)
    }

    @Test
    fun `other intents are ignored`() {
        assertNull(SharedLinkParser.parse("android.intent.action.MAIN", null, null, null, null))
        assertNull(SharedLinkParser.parse(null, null, "https://youtu.be/A", null, null))
    }
}
