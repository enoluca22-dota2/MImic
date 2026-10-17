package com.example.mimic

import com.example.mimic.data.server.ServerCredentials
import com.example.mimic.data.server.SubsonicClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for MImic 2.8.1: stream/cover URLs must be STABLE for a given client so
 * Coil can cache cover art instead of re-downloading on every recomposition/scroll (the root
 * cause of the physical-phone scroll jank + audio starvation over Tailscale). The salt is fixed
 * per client instance, not per call.
 */
class SubsonicClientUrlTest {

    private val creds = ServerCredentials("http://example.ts.net:4533", "enoluca", "secret")

    @Test
    fun `cover url is identical across repeated calls on the same client`() {
        val client = SubsonicClient(creds)
        val first = client.coverArtUrl("al-42")
        val second = client.coverArtUrl("al-42")
        val third = client.coverArtUrl("al-42")
        assertEquals(first, second)
        assertEquals(second, third)
    }

    @Test
    fun `stream url is identical across repeated calls on the same client`() {
        val client = SubsonicClient(creds)
        assertEquals(client.streamUrl("song-7"), client.streamUrl("song-7"))
    }

    @Test
    fun `url never contains the raw password`() {
        val client = SubsonicClient(creds)
        val url = client.streamUrl("song-7")
        assertTrue(url.contains("t=") && url.contains("s="))
        assertTrue("password must never appear in the URL", !url.contains("secret"))
    }

    @Test
    fun `different client instances use different salts`() {
        // Each session gets its own salt (still never a fixed well-known value).
        val a = SubsonicClient(creds).streamUrl("song-7")
        val b = SubsonicClient(creds).streamUrl("song-7")
        assertNotEquals(a, b)
    }
}
