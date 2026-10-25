package com.example.mimic

import com.example.mimic.data.Song
import com.example.mimic.data.SongSource
import com.example.mimic.data.online.OnlineIds
import com.example.mimic.party.ListenTogether
import com.example.mimic.party.PartyInvite
import com.example.mimic.party.PartyPlayback
import com.example.mimic.party.PartyQueue
import com.example.mimic.party.PartyTrack
import com.example.mimic.party.PartyTracks
import com.example.mimic.party.ServerClock
import com.example.mimic.party.partyQueueIndexOf
import com.example.mimic.player.detectSingleMove
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 4.5 Listen Together: the parts that decide who joins what, and which song each phone plays. */
class PartyTest {

    // ---- invites & server address ----

    @Test fun app_invite_link_carries_code_and_server() {
        val inv = PartyInvite.parse("mimic://party/ab12cd?server=http%3A%2F%2Fmy-pc.tail1.ts.net%3A8000")
        assertEquals(PartyInvite("AB12CD", "http://my-pc.tail1.ts.net:8000"), inv)
    }

    @Test fun web_invite_uses_its_own_origin_as_the_server() {
        assertEquals(PartyInvite("XY34ZW", "https://party.example.com"), PartyInvite.parse("https://party.example.com/invite/XY34ZW"))
    }

    @Test fun a_typed_code_is_an_invite_without_server() {
        assertEquals(PartyInvite("AB12CD", null), PartyInvite.parse(" ab-12-cd "))
    }

    @Test fun links_round_trip() {
        val server = "http://100.64.0.5:8000"
        assertEquals(PartyInvite("AB12CD", server), PartyInvite.parse(PartyInvite.appLink("ab12cd", server)))
        assertEquals(PartyInvite("AB12CD", server), PartyInvite.parse(PartyInvite.webLink("AB12CD", server)))
    }

    @Test fun junk_is_not_an_invite() {
        assertNull(PartyInvite.parse("https://youtube.com/watch?v=abc"))
        assertNull(PartyInvite.parse("mimic://party/TOOLONGCODE"))
        assertNull(PartyInvite.parse("hello"))
    }

    @Test fun server_addresses_are_normalised_or_refused() {
        assertEquals("http://100.64.0.5:8000", ListenTogether.normalizeServerUrl("100.64.0.5:8000"))
        assertEquals("https://pc.tail1.ts.net", ListenTogether.normalizeServerUrl("https://PC.tail1.ts.net/"))
        assertNull(ListenTogether.normalizeServerUrl("ftp://x"))
        assertNull(ListenTogether.normalizeServerUrl("http://host/some/path"))
        assertNull(ListenTogether.normalizeServerUrl("http://host?x=1"))
        assertNull(ListenTogether.normalizeServerUrl("has space"))
    }

    // ---- track identity: no audio, no tokens leave the phone ----

    private fun song(id: Long, title: String, artist: String, dur: Long, source: SongSource = SongSource.LOCAL, remote: String? = null, art: String? = null) =
        Song(id, title, artist, "Album", dur, "content://media/$id", art, 0L, source = source, remoteId = remote)

    @Test fun online_song_is_shared_by_video_id_with_public_artwork() {
        val s = song(OnlineIds.songId("dQw4w9WgXcQ"), "Song", "Artist", 200_000, SongSource.ONLINE, OnlineIds.remoteId("dQw4w9WgXcQ"), "https://i.ytimg.com/a.jpg")
        val t = PartyTracks.fromSong(s)!!
        assertEquals("dQw4w9WgXcQ", t.videoId)
        assertEquals("https://i.ytimg.com/a.jpg", t.thumbnailUrl)
    }

    @Test fun navidrome_cover_url_with_login_token_is_never_shared() {
        val s = song(5_000_000_000, "Aerials", "System Of A Down", 235_000, SongSource.SERVER, "nd-1", "http://pc:4533/rest/getCoverArt?u=me&t=SECRET&s=salt")
        val t = PartyTracks.fromSong(s)!!
        assertNull(t.thumbnailUrl)
        assertTrue(t.videoId.startsWith("m:"))
        assertFalse(t.toJson().toString().contains("SECRET"))
    }

    @Test fun meta_key_ignores_upload_packaging() {
        assertEquals(PartyTracks.metaKey("Mirrors", "Justin Timberlake"), PartyTracks.metaKey("Justin Timberlake - Mirrors (Official Video)", "JustinTimberlakeVEVO"))
    }

    @Test fun library_match_prefers_an_offline_copy_and_checks_length() {
        val t = PartyTrack(PartyTracks.metaKey("Aerials", "System Of A Down"), "Aerials", "System Of A Down", null, 235_000)
        val server = song(5_000_000_001, "Aerials", "System of a Down", 235_500, SongSource.SERVER, "nd-2")
        val local = song(42, "Aerials", "System Of A Down", 234_800)
        val live = song(43, "Aerials (Live)", "System Of A Down", 235_000)
        val longer = song(44, "Aerials", "System Of A Down", 300_000)
        assertEquals(local, PartyTracks.matchInLibrary(t, listOf(server, live, longer, local)))
        assertEquals(server, PartyTracks.matchInLibrary(t, listOf(server, live, longer)))
        assertNull(PartyTracks.matchInLibrary(t, listOf(live, longer)))
    }

    @Test fun downloaded_online_song_plays_from_the_download() {
        val id = "abcdefghijk"
        val dl = song(OnlineIds.songId(id), "S", "A", 100_000, SongSource.DOWNLOADED, OnlineIds.remoteId(id))
        assertEquals(dl, PartyTracks.matchInLibrary(PartyTrack(id, "S", "A", null, 100_000), listOf(dl)))
    }

    // ---- wire format & sync primitives ----

    @Test fun state_frame_with_skip_votes_parses() {
        val p = PartyPlayback.from(JSONObject("""{"seq":7,"track":{"videoId":"v1","title":"T","artist":"A","durationMs":1000},
            "isPlaying":true,"positionMs":500,"anchorMs":1000,"queueSeq":3,"queueIndex":0,"skipVotes":1,"skipVotesNeeded":2,"skipVoters":["m1"],"updatedBy":null}"""))
        assertEquals(7, p.seq)
        assertEquals("v1", p.track?.videoId)
        assertEquals(1, p.skipVotes)
        assertEquals(listOf("m1"), p.skipVoters)
        assertNull(p.updatedBy)
    }

    @Test fun non_https_artwork_from_the_wire_is_dropped() {
        assertNull(PartyTrack.from(JSONObject("""{"videoId":"v","thumbnailUrl":"http://lan/x.jpg"}"""))?.thumbnailUrl)
    }

    @Test fun clock_offset_uses_the_fastest_round_trip() {
        var now = 0L
        val c = ServerClock { now }
        c.record(sentAtLocalMs = 1_000, serverMs = 51_150, receivedAtLocalMs = 1_300) // rtt 300 → offset 50_000
        c.record(sentAtLocalMs = 2_000, serverMs = 52_020, receivedAtLocalMs = 2_040) // rtt 40 → offset 50_000
        assertEquals(40, c.roundTripMs)
        assertEquals(50_000L, c.offsetMs)
        now = 3_000
        assertEquals(53_000L, c.serverNowMs())
    }

    @Test fun queue_index_prefers_the_slot_nearest_the_party_index() {
        val q = PartyQueue(1, 2, listOf("a", "b", "a", "c").map { PartyTrack(it) })
        assertEquals(2, partyQueueIndexOf(q, PartyPlayback(queueIndex = 2), "a"))
        assertEquals(0, partyQueueIndexOf(q, PartyPlayback(queueIndex = 0), "a"))
        assertEquals(-1, partyQueueIndexOf(q, PartyPlayback(queueIndex = 0), "z"))
    }

    @Test fun single_queue_move_is_detected() {
        val m = detectSingleMove(listOf("a", "b", "c", "d"), listOf("a", "c", "d", "b"))
        assertNotNull(m)
        assertEquals("b", m!!.videoId)
        assertNull(detectSingleMove(listOf("a", "b"), listOf("b", "c")))
    }

    @Test fun a_song_matches_its_own_party_track() {
        val s = song(333, "Embedded Song", "MImic Test", 30_067)
        val t = PartyTracks.fromSong(s)!!
        assertEquals(s, PartyTracks.matchInLibrary(t, listOf(s)))
    }
}
