package com.example.mimic

import com.example.mimic.cast.LanMediaServer
import com.example.mimic.data.DynamicIntensity
import com.example.mimic.data.DynamicPlayerConfig
import com.example.mimic.data.LegacyCloudIds
import com.example.mimic.data.MusicProvider
import com.example.mimic.data.Song
import com.example.mimic.data.SongSource
import com.example.mimic.data.animatesBackground
import com.example.mimic.data.art.TrackArtwork
import com.example.mimic.data.server.songIdForRemote
import com.example.mimic.data.transitionMs
import com.example.mimic.share.SongShare
import com.example.mimic.ui.dynamic.DynamicColorMath
import com.example.mimic.ui.dynamic.DynamicScheme
import com.example.mimic.ui.library.formatBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MImic 4.1 logic: artwork identity, Dynamic colors, sharing, casting, removed cloud sources. */
class Mimic41Test {

    // ---- Artwork identity (the shared-cover bug) --------------------------------------------

    @Test fun every_track_gets_its_own_artwork_key() {
        val a = TrackArtwork.uri(101, 1700000000, albumId = 7)
        val b = TrackArtwork.uri(102, 1700000000, albumId = 7) // same album, different track
        assertNotEquals(a, b)
        assertEquals(TrackArtwork.Key(101, 1700000000, 7), TrackArtwork.parse(a))
        assertEquals(TrackArtwork.Key(5, 9, null), TrackArtwork.parse(TrackArtwork.uri(5, 9, null)))
        assertNull(TrackArtwork.parse("content://media/external/audio/albumart/7"))
        assertNull(TrackArtwork.parse("mimic-art://track/notanumber?v=1"))
    }

    @Test fun a_retagged_file_gets_fresh_artwork() {
        val before = TrackArtwork.parse(TrackArtwork.uri(42, 1000, null))!!
        val after = TrackArtwork.parse(TrackArtwork.uri(42, 2000, null))!!
        assertNotEquals(TrackArtwork.cacheName(before), TrackArtwork.cacheName(after))
        assertNotEquals(TrackArtwork.cacheName(before), TrackArtwork.cacheName(TrackArtwork.Key(43, 1000, null)))
    }

    @Test fun folder_name_albums_never_lend_their_art_to_other_songs() {
        // MediaStore names untagged files' album after their folder: that "album" is not real.
        assertFalse(TrackArtwork.isRealAlbum("MImic", "MImic"))
        assertFalse(TrackArtwork.isRealAlbum("ENAGELYUCA", "ENAGELYUCA"))
        assertFalse(TrackArtwork.isRealAlbum(null, "Music"))
        assertFalse(TrackArtwork.isRealAlbum("<unknown>", "Download"))
        assertTrue(TrackArtwork.isRealAlbum("Toxicity", "System Of A Down"))
    }

    @Test fun artwork_is_decoded_at_the_size_it_is_shown() {
        assertEquals(1, TrackArtwork.sampleSize(500, 500, 512))
        assertEquals(2, TrackArtwork.sampleSize(1200, 1200, 512))
        assertEquals(8, TrackArtwork.sampleSize(3000, 3000, 300))
    }

    // ---- Dynamic Now Playing colors ---------------------------------------------------------

    private val extremes = listOf(
        0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFEB3B.toInt(), 0xFF1565C0.toInt(),
        0xFFFF6D00.toInt(), 0xFF00E676.toInt(), 0xFFF8BBD0.toInt(), 0xFF808080.toInt(),
    )

    @Test fun text_always_meets_the_contrast_floor_whatever_the_artwork() {
        for (dominant in extremes) for (vibrant in extremes) for (brightness in listOf(-0.4f, 0f, 0.4f)) for (auto in listOf(true, false)) {
            val s = DynamicColorMath.derive(DynamicColorMath.Swatches(dominant, vibrant, dominant), brightness, auto)
            val topC = DynamicColorMath.contrast(s.top, s.onBackground)
            val bottomC = DynamicColorMath.contrast(s.bottom, s.onBackground)
            assertTrue("top contrast $topC for ${Integer.toHexString(dominant)}", topC >= DynamicColorMath.MIN_TEXT_CONTRAST)
            assertTrue("bottom contrast $bottomC", bottomC >= DynamicColorMath.MIN_TEXT_CONTRAST)
            assertTrue("muted text on top", DynamicColorMath.contrast(s.top, s.onBackgroundMuted) >= 3.0)
        }
    }

    @Test fun colors_follow_the_artwork() {
        val blue = DynamicColorMath.derive(DynamicColorMath.Swatches(0xFF1E5BD8.toInt(), 0xFF4FC3F7.toInt(), 0xFF0D2A66.toInt()), 0f, true)
        val orange = DynamicColorMath.derive(DynamicColorMath.Swatches(0xFFE8711B.toInt(), 0xFFFFB74D.toInt(), 0xFF6B2E05.toInt()), 0f, true)
        // Blue artwork → blue-dominant background; orange artwork → red/orange-dominant.
        assertTrue(DynamicColorMath.b(blue.top) > DynamicColorMath.r(blue.top))
        assertTrue(DynamicColorMath.r(orange.top) > DynamicColorMath.b(orange.top))
        assertNotEquals(blue.top, orange.top)
        assertNotEquals(DynamicScheme.fallback.top, blue.top)
    }

    @Test fun contrast_math_matches_wcag() {
        assertEquals(21.0, DynamicColorMath.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, DynamicColorMath.contrast(0xFF777777.toInt(), 0xFF777777.toInt()), 0.001)
    }

    @Test fun transitions_stay_between_500_and_900_ms_and_respect_reduced_motion() {
        for (i in DynamicIntensity.entries) {
            val ms = DynamicPlayerConfig(intensity = i).transitionMs(systemReducedMotion = false)
            assertTrue(ms in 500..900)
        }
        assertEquals(0, DynamicPlayerConfig().transitionMs(systemReducedMotion = true))
        assertEquals(0, DynamicPlayerConfig(reduceMotion = true).transitionMs(false))
        assertFalse(DynamicPlayerConfig().animatesBackground(systemReducedMotion = true))
        assertFalse(DynamicPlayerConfig(animatedBackground = false).animatesBackground(false))
        assertTrue(DynamicPlayerConfig().animatesBackground(false))
    }

    // ---- Sharing ------------------------------------------------------------------------------

    private fun song(title: String, artist: String, uri: String) =
        Song(1, title, artist, "A", 1000, uri, null, 0)

    @Test fun shared_files_get_a_readable_safe_name_and_the_right_type() {
        assertEquals("Muse - Uprising.mp3", SongShare.displayName(song("Uprising", "Muse", ""), "mp3"))
        assertEquals("AC_DC - T_N_T.flac", SongShare.displayName(song("T/N/T", "AC/DC", ""), "flac"))
        assertEquals("Track.m4a", SongShare.displayName(song("Track", "Unknown artist", ""), "m4a"))
        assertEquals("audio/mpeg", SongShare.mimeFor("MP3"))
        assertEquals("audio/flac", SongShare.mimeFor("flac"))
        assertEquals("audio/mp4", SongShare.mimeFor("m4a"))
        assertEquals("mp3", SongShare.extensionOf("file:///data/user/0/x/files/downloads/abc.mp3"))
        assertEquals("", SongShare.extensionOf("content://media/external/audio/media/42"))
    }

    // ---- Casting --------------------------------------------------------------------------------

    @Test fun cast_server_understands_range_requests() {
        assertEquals(LongRange(0, -1), LanMediaServer.parseRange("bytes=0-"))
        assertEquals(LongRange(100, 199), LanMediaServer.parseRange("bytes=100-199"))
        assertNull(LanMediaServer.parseRange("bytes=200-100"))
        assertNull(LanMediaServer.parseRange("bytes=0-1,5-6"))
        assertNull(LanMediaServer.parseRange("items=0-1"))
        assertNull(LanMediaServer.parseRange(null))
    }

    // ---- Removed cloud sources ------------------------------------------------------------------

    @Test fun songs_downloaded_from_cloud_drives_keep_their_ids_and_play_as_phone_music() {
        val id = "onedrive:acct:item%2F1"
        assertTrue(LegacyCloudIds.isCloud(id))
        assertTrue(LegacyCloudIds.isCloud("gdrive:p:f"))
        assertFalse(LegacyCloudIds.isCloud("tr-12345"))
        assertFalse(LegacyCloudIds.isCloud("onedrive-like-but-not"))
        val stable = LegacyCloudIds.stableId(id)
        assertEquals(stable, songIdForRemote(id))
        assertTrue(stable >= (1L shl 61) && stable < (1L shl 62))
        val dl = Song(stable, "t", "a", "al", 1, "file:///x.mp3", null, 0, source = SongSource.DOWNLOADED, remoteId = id)
        assertEquals(MusicProvider.PHONE, dl.provider)
        assertEquals(MusicProvider.NAVIDROME, Song(1, "t", "a", "al", 1, "http://s/x", null, 0, remoteId = "tr-1").provider)
    }

    @Test fun storage_sizes_read_naturally() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("120 MB", formatBytes(120L * 1024 * 1024))
        assertEquals("2.0 GB", formatBytes(2L * 1024 * 1024 * 1024))
    }
}
