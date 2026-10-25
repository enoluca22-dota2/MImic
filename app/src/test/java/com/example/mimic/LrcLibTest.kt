package com.example.mimic

import com.example.mimic.data.audio.LrcLib
import com.example.mimic.data.lyrics.LyricsCandidate
import com.example.mimic.data.lyrics.LyricsMatcher
import com.example.mimic.data.lyrics.LyricsQuery
import com.example.mimic.data.lyrics.LyricsTarget
import com.example.mimic.data.lyrics.LyricsWorker
import com.example.mimic.data.server.ServerCredentials
import com.example.mimic.data.server.SubsonicClient
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 4.5: lyrics matching + the LRCLIB client. The cases are the ones measured against the live
 * LRCLIB API on 2026-10-25 when lyrics were missing: YouTube-upload metadata found nothing, and
 * LRCLIB's frequent 503s were reported as "no lyrics".
 */
class LrcLibTest {
    private val json = """[
      {"id":1,"trackName":"Mirrors","artistName":"Justin Timberlake","albumName":"The 20/20 Experience","duration":484.0,"instrumental":false,"plainLyrics":"plain","syncedLyrics":"[00:01.00]Synced line"},
      {"id":2,"trackName":"Mirrors (Radio Edit)","artistName":"Justin Timberlake","duration":321.0,"instrumental":false,"plainLyrics":"edit","syncedLyrics":null},
      {"id":3,"trackName":"Mirrors","artistName":"Someone Else","duration":484.0,"instrumental":false,"plainLyrics":"cover","syncedLyrics":null},
      {"id":4,"trackName":"Mirrors (Live)","artistName":"Justin Timberlake","duration":484.0,"instrumental":false,"plainLyrics":"live","syncedLyrics":"[00:02.00]Live line"}
    ]"""
    private val cands = LrcLib.parse(json)

    private fun found(q: LyricsQuery, list: List<LyricsCandidate> = cands): LyricsCandidate? =
        (LyricsMatcher.match(q, list) as? LyricsMatcher.Match.Found)?.candidate

    // ---- metadata cleaning (the root cause) ----

    @Test fun vevo_upload_is_cleaned_to_the_catalogue_name() {
        val q = LyricsQuery.of("Justin Timberlake - Mirrors (Official Video)", "JustinTimberlakeVEVO", durationMs = 484_000)
        assertEquals("Mirrors", q.title)
        assertEquals("Justin Timberlake", q.artist)
        assertEquals("[00:01.00]Synced line", found(q)?.synced)
    }

    @Test fun mimic_youtube_fallback_metadata_is_cleaned_too() {
        // What 4.4's YouTubeSearch stored for that video: artist "JustinTimberlake", title unchanged.
        val q = LyricsQuery.of("Justin Timberlake - Mirrors", "JustinTimberlake", durationMs = 484_000)
        assertEquals("Mirrors", q.title)
        assertEquals("Justin Timberlake", q.artist)
    }

    @Test fun packaging_tags_are_removed() {
        assertEquals("Houdini", LyricsQuery.of("Houdini (Lyrics)", "Dua Lipa").title)
        assertEquals("Houdini", LyricsQuery.of("Dua Lipa - Houdini (Official Music Video)", "Dua Lipa").title)
        assertEquals("Houdini", LyricsQuery.of("Houdini [4K] | Dua Lipa", "Dua Lipa").title)
        assertEquals("Mirrors", LyricsQuery.of("Mirrors", "Justin Timberlake - Topic").title)
    }

    @Test fun lyrics_channel_uploads_get_an_artist_title_reading() {
        val v = LyricsQuery.variants("Dua Lipa - Houdini (Lyrics)", "7clouds")
        assertTrue(v.any { it.title == "Houdini" && it.artist == "Dua Lipa" })
    }

    @Test fun featured_artists_are_credits_not_title() {
        val q = LyricsQuery.of("Stay (with Justin Bieber)", "The Kid LAROI", durationMs = 141_000)
        assertEquals("Stay", q.title)
        assertTrue("justin bieber" in q.artists)
        val c = LyricsCandidate("LRCLIB", "9", "STAY (with Justin Bieber)", "The Kid LAROI", "", 141_844, "[00:01.00]x", null)
        assertEquals(c, found(q, listOf(c)))
    }

    @Test fun unknown_placeholders_are_not_artists() {
        assertFalse(LyricsQuery.of("Song", "Unknown artist").artistKnown)
        assertFalse(LyricsQuery.of("Song", "<unknown>").artistKnown)
    }

    // ---- matching never picks another recording ----

    @Test fun synced_match_with_same_duration_wins() {
        assertEquals("[00:01.00]Synced line", found(LyricsQuery.of("Mirrors", "Justin Timberlake", durationMs = 485_000))?.synced)
    }

    @Test fun different_length_recording_is_rejected() {
        // The 8-minute official video is not the album recording: no lyrics rather than wrong ones.
        assertNull(found(LyricsQuery.of("Mirrors", "Justin Timberlake", durationMs = 501_000)))
    }

    @Test fun other_artists_lyrics_are_never_used() {
        assertNull(found(LyricsQuery.of("Mirrors", "Justin Timberlake", durationMs = 484_000), cands.filter { it.artist == "Someone Else" }))
    }

    @Test fun live_and_studio_versions_never_mix() {
        // 4.4 accepted any title prefix, so "Mirrors (Live)" passed for "Mirrors".
        assertNull(found(LyricsQuery.of("Mirrors", "Justin Timberlake", durationMs = 484_000), cands.filter { it.providerId == "4" }))
        assertEquals("[00:02.00]Live line", found(LyricsQuery.of("Mirrors (Live)", "Justin Timberlake", durationMs = 484_000))?.synced)
        val remix = LyricsCandidate("LRCLIB", "5", "Mirrors (Remix)", "Justin Timberlake", "", 484_000, "[00:01.00]r", null)
        assertNull(found(LyricsQuery.of("Mirrors", "Justin Timberlake", durationMs = 484_000), listOf(remix)))
    }

    @Test fun unknown_artist_with_several_artists_asks_the_user() {
        val a = LyricsCandidate("LRCLIB", "1", "Home", "Artist A", "", 200_000, "[00:01.00]a", null)
        val b = LyricsCandidate("LRCLIB", "2", "Home", "Artist B", "", 201_000, "[00:01.00]b", null)
        val m = LyricsMatcher.match(LyricsQuery.of("Home", "", durationMs = 200_500), listOf(a, b))
        assertTrue(m is LyricsMatcher.Match.Ambiguous)
    }

    @Test fun title_alone_identifies_nothing() {
        val a = LyricsCandidate("LRCLIB", "1", "Home", "Artist A", "", 200_000, "[00:01.00]a", null)
        assertTrue(LyricsMatcher.match(LyricsQuery.of("Home", ""), listOf(a)) is LyricsMatcher.Match.None)
    }

    @Test fun unknown_duration_with_different_texts_asks_the_user() {
        val a = LyricsCandidate("LRCLIB", "1", "Home", "Band", "", 200_000, null, "words one")
        val b = LyricsCandidate("LRCLIB", "2", "Home", "Band", "", 320_000, null, "other words")
        assertTrue(LyricsMatcher.match(LyricsQuery.of("Home", "Band"), listOf(a, b)) is LyricsMatcher.Match.Ambiguous)
    }

    @Test fun plain_is_kept_plain_and_instrumental_is_no_lyrics() {
        val plain = LyricsCandidate("LRCLIB", "1", "Song", "Band", "", 180_000, null, "just words")
        assertEquals("just words", found(LyricsQuery.of("Song", "Band", durationMs = 180_000), listOf(plain))?.bestText)
        val inst = plain.copy(instrumental = true)
        assertNull(found(LyricsQuery.of("Song", "Band", durationMs = 180_000), listOf(inst)))
    }

    @Test fun bad_json_is_no_match() {
        assertEquals(emptyList<LyricsCandidate>(), LrcLib.parse("not json"))
    }

    // ---- the network client: retries, honest failures ----

    private lateinit var server: FakeHttp

    @Before fun startServer() {
        server = FakeHttp()
        LrcLib.baseUrl = server.base
        LrcLib.backoffMs = longArrayOf(1, 1)
        LrcLib.minIntervalMs = 0
    }

    @After fun stopServer() {
        server.stop()
        LrcLib.baseUrl = "https://lrclib.net"
        LrcLib.backoffMs = longArrayOf(1_200, 3_000)
        LrcLib.minIntervalMs = 350
    }

    @Test fun a_503_is_retried_then_succeeds() {
        server.enqueue(503)
        server.enqueue(503)
        server.enqueue(200, json)
        val r = LrcLib.search(LyricsQuery.of("Mirrors", "Justin Timberlake"))
        assertTrue(r is LrcLib.Response.Ok && r.candidates.size == 4)
        assertEquals(3, server.requests.size)
    }

    @Test fun persistent_503_is_a_failure_not_no_lyrics() {
        repeat(3) { server.enqueue(503) }
        val r = LrcLib.search(LyricsQuery.of("Mirrors", "Justin Timberlake"))
        assertEquals(LrcLib.Response.Failed(LrcLib.Reason.UNAVAILABLE, 503), r)
    }

    @Test fun get_404_means_no_record() {
        server.enqueue(404, """{"code":404,"name":"TrackNotFound"}""")
        val r = LrcLib.get(LyricsQuery.of("Mirrors", "Justin Timberlake", durationMs = 484_000))
        assertEquals(LrcLib.Response.Ok(emptyList()), r)
        val req = server.requests.first()
        assertTrue(req.first, req.first.startsWith("/api/get?track_name=Mirrors&artist_name=Justin%20Timberlake"))
        assertTrue(req.first.contains("duration=484"))
        assertEquals("MImic (https://github.com/enoluca22-dota2/MImic)", req.second)
    }

    @Test fun navidrome_synced_lyrics_become_lrc() = runBlocking {
        server.enqueue(
            200,
            """{"subsonic-response":{"status":"ok","lyricsList":{"structuredLyrics":[{"lang":"eng","synced":true,"offset":0,
            "line":[{"start":1500,"value":"First"},{"start":62250,"value":"Second"}]}]}}}""",
        )
        val client = SubsonicClient(ServerCredentials(server.base, "u", "p"))
        val text = client.lyrics("song-1", "Band", "Song").getOrThrow()
        assertEquals("[00:01.50]First\n[01:02.25]Second", text)
        assertTrue(server.requests.first().first.contains("getLyricsBySongId"))
    }

    @Test fun scan_targets_survive_the_round_trip() {
        val t = listOf(LyricsTarget(42, "Song", "Band", "", 1000, "content://x/42", null), LyricsTarget(7, "S", "A", "Al", 0, "file:///d.mp3", "yt:abc"))
        assertEquals(t, LyricsWorker.readTargets(LyricsWorker.writeTargets(t)))
    }
}

/** A minimal scripted HTTP server on the JDK's built-in HttpServer (MockWebServer 4 clashes with OkHttp 5 in the full edition). */
class FakeHttp {
    private val queue = java.util.concurrent.LinkedBlockingQueue<Pair<Int, String>>()
    /** (path with query, User-Agent) of every request, in order. */
    val requests: MutableList<Pair<String, String?>> = java.util.Collections.synchronizedList(ArrayList())
    private val http = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            requests += ex.requestURI.rawPath + (ex.requestURI.rawQuery?.let { "?$it" } ?: "") to ex.requestHeaders.getFirst("User-Agent")
            val (code, body) = queue.poll() ?: (500 to "")
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        start()
    }
    val base: String = "http://127.0.0.1:${http.address.port}"
    fun enqueue(code: Int, body: String = "") { queue += code to body }
    fun stop() = http.stop(0)
}
