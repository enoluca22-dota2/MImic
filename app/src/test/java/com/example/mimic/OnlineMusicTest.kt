package com.example.mimic

import com.example.mimic.data.MusicProvider
import com.example.mimic.data.SongSource
import com.example.mimic.data.SourceFilter
import com.example.mimic.data.UnifiedLibrary
import com.example.mimic.data.online.OnlineIds
import com.example.mimic.data.online.OnlineTrack
import com.example.mimic.data.online.OnlineTrackEntity
import com.example.mimic.data.online.toSong
import com.example.mimic.data.server.remoteStableId
import com.example.mimic.data.server.songIdForRemote
import com.example.mimic.data.LegacyCloudIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MImic 4.4 online music: identity, URIs and how online tracks join the unified library. */
class OnlineMusicTest {

    private val track = OnlineTrack(
        videoId = "dQw4w9WgXcQ", title = "Never Gonna Give You Up", artist = "Rick Astley",
        album = "Whenever You Need Somebody", albumId = "MPREb_abc", durationMs = 213_000,
        artworkUrl = "https://lh3.googleusercontent.com/a=w544-h544",
    )

    @Test fun online_song_ids_live_in_their_own_range() {
        val id = OnlineIds.songId(track.videoId)
        assertTrue(OnlineIds.isOnlineSongId(id))
        assertTrue(id > 0)
        // Never collides with the Navidrome range (just above 2^32) or legacy cloud ([2^61, 2^62)).
        assertFalse(OnlineIds.isOnlineSongId(remoteStableId("navidrome-track-id")))
        assertFalse(OnlineIds.isOnlineSongId(LegacyCloudIds.stableId("gdrive:a:b")))
        // Stable, and the same through the generic remote-id path the downloads table uses.
        assertEquals(id, OnlineIds.songId(track.videoId))
        assertEquals(id, songIdForRemote("yt:dQw4w9WgXcQ"))
        assertNotEquals(id, OnlineIds.songId("dQw4w9WgXcR"))
    }

    @Test fun a_navidrome_track_with_the_same_title_is_a_different_song() {
        val online = track.toSong()
        val server = online.copy(id = remoteStableId("srv-1"), remoteId = "srv-1", source = SongSource.SERVER, uri = "http://h/stream")
        assertNotEquals(online.id, server.id)
        assertEquals(MusicProvider.YOUTUBE, online.provider)
        assertEquals(MusicProvider.NAVIDROME, server.provider)
        assertTrue(online.isOnline)
        assertFalse(online.isFromServer) // never sent to Navidrome star/download code
        assertEquals(2, UnifiedLibrary.merge(emptyList(), listOf(server), emptyList(), listOf(online)).size)
    }

    @Test fun play_uri_round_trips_and_ignores_other_uris() {
        val uri = OnlineIds.playUri(track.videoId)
        assertEquals("mimic-yt://track?v=dQw4w9WgXcQ", uri)
        assertEquals(track.videoId, OnlineIds.videoIdOfUri(uri))
        assertNull(OnlineIds.videoIdOfUri("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(OnlineIds.videoIdOfUri("content://media/external/audio/media/5"))
        assertNull(OnlineIds.videoIdOfUri(null))
    }

    @Test fun song_keeps_its_own_metadata_and_artwork() {
        val s = track.toSong()
        assertEquals("Never Gonna Give You Up", s.title)
        assertEquals("Rick Astley", s.artist)
        assertEquals(213_000, s.durationMs)
        assertEquals(track.artworkUrl, s.albumArtUri)
        assertEquals("yt:album:MPREb_abc", s.albumKey)
        assertFalse(s.isOffline)
        assertTrue(SourceFilter.ONLINE.matches(s))
        assertFalse(SourceFilter.PHONE.matches(s))
    }

    @Test fun a_download_replaces_the_streamed_online_entry() {
        val streamed = track.toSong()
        val downloaded = streamed.copy(source = SongSource.DOWNLOADED, uri = "file:///x/yt_dQw4w9WgXcQ.webm", albumArtUri = "file:///x/yt_dQw4w9WgXcQ.cover")
        val merged = UnifiedLibrary.merge(emptyList(), emptyList(), listOf(downloaded), listOf(streamed))
        assertEquals(1, merged.size)
        assertEquals(SongSource.DOWNLOADED, merged[0].source)
        assertEquals(MusicProvider.YOUTUBE, merged[0].provider)
        // A missing file falls back to streaming the same identity.
        val missing = UnifiedLibrary.merge(emptyList(), emptyList(), listOf(downloaded.copy(fileMissing = true)), listOf(streamed))
        assertEquals(streamed.uri, missing.single().uri)
    }

    @Test fun every_track_has_its_own_artwork_never_a_neighbours() {
        val a = OnlineTrackEntity.of(track, inLibrary = true).toSong()
        val b = OnlineTrackEntity.of(track.copy(videoId = "other123456", artworkUrl = "https://lh3.googleusercontent.com/b"), inLibrary = true).toSong()
        assertNotEquals(a.id, b.id)
        assertNotEquals(a.albumArtUri, b.albumArtUri)
    }
}
