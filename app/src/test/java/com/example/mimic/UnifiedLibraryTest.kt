package com.example.mimic

import com.example.mimic.data.HomeConfig
import com.example.mimic.data.HomeSection
import com.example.mimic.data.MetadataFallback
import com.example.mimic.data.Song
import com.example.mimic.data.SongSort
import com.example.mimic.data.SongSource
import com.example.mimic.data.SourceFilter
import com.example.mimic.data.UnifiedLibrary
import com.example.mimic.data.server.SubsonicClient
import com.example.mimic.data.server.remoteStableId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedLibraryTest {

    private fun local(id: Long, title: String, artist: String = "A", album: String = "Al", albumId: Long = 1, added: Long = id) =
        Song(id, title, artist, album, 1000, "content://$id", null, added, source = SongSource.LOCAL, albumKey = "local:album:$albumId")

    private fun server(rid: String, title: String, artist: String = "A", album: String = "Al", albumId: String = "s1", added: Long = 0) =
        Song(
            remoteStableId(rid), title, artist, album, 1000, "http://srv/stream?id=$rid", null, added,
            source = SongSource.SERVER, remoteId = rid, albumKey = SubsonicClient.serverAlbumKey(albumId, album),
        )

    private fun download(rid: String, title: String, missing: Boolean = false, albumId: String? = null) =
        Song(
            remoteStableId(rid), title, "A", "Al", 1000, "file:///dl/$rid.mp3", null, 5,
            source = SongSource.DOWNLOADED, remoteId = rid, albumKey = SubsonicClient.serverAlbumKey(albumId, "Al"),
            fileMissing = missing,
        )

    @Test fun download_replaces_its_server_entry_one_logical_song() {
        val merged = UnifiedLibrary.merge(
            local = listOf(local(1, "Aerials")),
            server = listOf(server("r1", "Aerials"), server("r2", "Toxicity")),
            downloads = listOf(download("r1", "Aerials")),
        )
        assertEquals(3, merged.size)
        val r1 = merged.filter { it.remoteId == "r1" }
        assertEquals(1, r1.size)
        assertEquals(SongSource.DOWNLOADED, r1[0].source)
        assertTrue(r1[0].uri.startsWith("file:"))
        // Pre-3.0 download (no album id) adopts the server's album identity.
        assertEquals("server:album:s1", r1[0].albumKey)
    }

    @Test fun local_and_server_songs_with_same_title_are_not_merged() {
        val merged = UnifiedLibrary.merge(listOf(local(1, "Aerials")), listOf(server("r1", "Aerials")), emptyList())
        assertEquals(2, merged.size)
    }

    @Test fun missing_download_streams_when_server_known_and_stays_flagged() {
        val merged = UnifiedLibrary.merge(emptyList(), listOf(server("r1", "X")), listOf(download("r1", "X", missing = true)))
        val s = merged.single()
        assertTrue(s.fileMissing)
        assertTrue(s.uri.startsWith("http://srv/stream"))
        assertFalse(s.isOffline)
    }

    @Test fun downloads_survive_without_server_catalog() {
        val merged = UnifiedLibrary.merge(emptyList(), emptyList(), listOf(download("r1", "X")))
        assertEquals(1, merged.size)
        assertTrue(merged[0].isOffline)
    }

    @Test fun source_filters() {
        val lib = UnifiedLibrary.merge(listOf(local(1, "L")), listOf(server("r1", "S"), server("r2", "D")), listOf(download("r2", "D")))
        assertEquals(listOf("L"), UnifiedLibrary.filter(lib, SourceFilter.PHONE).map { it.title })
        assertEquals(setOf("S", "D"), UnifiedLibrary.filter(lib, SourceFilter.NAVIDROME).map { it.title }.toSet())
        assertEquals(listOf("D"), UnifiedLibrary.filter(lib, SourceFilter.DOWNLOADED).map { it.title })
        assertEquals(3, UnifiedLibrary.filter(lib, SourceFilter.ALL).size)
    }

    @Test fun albums_group_by_source_aware_key_not_title() {
        val songs = listOf(
            local(1, "a", artist = "Queen", album = "Greatest Hits", albumId = 10),
            local(2, "b", artist = "Queen", album = "Greatest Hits", albumId = 10),
            local(3, "c", artist = "ABBA", album = "Greatest Hits", albumId = 20),
        )
        val albums = UnifiedLibrary.albums(songs)
        assertEquals(2, albums.size)
        assertEquals(setOf("Queen", "ABBA"), albums.map { it.artist }.toSet())
        assertEquals(2, albums.first { it.artist == "Queen" }.songs.size)
    }

    @Test fun artists_group_case_insensitively_with_albums() {
        val songs = listOf(local(1, "a", artist = "Drake", albumId = 1), local(2, "b", artist = "drake ", albumId = 2), local(3, "c", artist = "SZA"))
        val artists = UnifiedLibrary.artists(songs)
        assertEquals(2, artists.size)
        val drake = artists.first { it.key == MetadataFallback.artistKey("Drake") }
        assertEquals(2, drake.songs.size)
        assertEquals(2, drake.albums.size)
    }

    @Test fun sorting() {
        val songs = listOf(local(1, "beta", artist = "Z", added = 5), local(2, "Alpha", artist = "Y", added = 9), local(3, "gamma", artist = "X", added = 1))
        assertEquals(listOf("Alpha", "beta", "gamma"), UnifiedLibrary.sort(songs, SongSort.NAME).map { it.title })
        assertEquals(listOf("gamma", "Alpha", "beta"), UnifiedLibrary.sort(songs, SongSort.ARTIST).map { it.title })
        assertEquals(listOf("Alpha", "beta", "gamma"), UnifiedLibrary.sort(songs, SongSort.RECENTLY_ADDED).map { it.title })
        val played = mapOf(3L to 100L, 1L to 50L)
        assertEquals(listOf("gamma", "beta", "Alpha"), UnifiedLibrary.sort(songs, SongSort.RECENTLY_PLAYED, played).map { it.title })
    }
}

class MetadataFallbackTest {
    @Test fun title_prefers_tag_then_filename() {
        assertEquals("Real Title", MetadataFallback.title("Real Title", "file.mp3"))
        assertEquals("my song", MetadataFallback.title(null, "my song.mp3"))
        assertEquals("my song", MetadataFallback.title("", "/music/my song.flac"))
        assertEquals("Ünïcødé 曲", MetadataFallback.title(null, "Ünïcødé 曲.mp3"))
        assertEquals(MetadataFallback.UNKNOWN_TITLE, MetadataFallback.title(null, null))
    }

    @Test fun unknown_markers_normalize() {
        listOf(null, "", "  ", "<unknown>", "[Unknown Artist]", "Unknown artist").forEach {
            assertEquals(MetadataFallback.UNKNOWN_ARTIST, MetadataFallback.artist(it))
        }
        assertEquals(MetadataFallback.UNKNOWN_ALBUM, MetadataFallback.album("[Unknown Album]"))
        assertEquals("Toxicity", MetadataFallback.album(" Toxicity "))
        assertEquals("System Of A Down", MetadataFallback.artist("System Of A Down"))
    }
}

class HomeOrderTest {
    @Test fun saved_2x_order_gains_new_sections_at_default_positions() {
        val old = listOf(HomeSection.RECENTLY_PLAYED, HomeSection.MOST_PLAYED, HomeSection.FAVORITES, HomeSection.RECENTLY_ADDED, HomeSection.SUGGESTED)
        val order = HomeConfig(sectionOrder = old).completeOrder()
        assertEquals(HomeSection.entries.size, order.size)
        assertEquals(HomeSection.DOWNLOADS, order[order.indexOf(HomeSection.RECENTLY_PLAYED) + 1])
        assertEquals(HomeSection.FAVORITE_ARTISTS, order[order.indexOf(HomeSection.FAVORITES) + 1])
        // The user's own relative order is preserved.
        val kept = order.filter { it in old }
        assertEquals(old, kept)
    }

    @Test fun default_order_is_the_dashboard_order() {
        assertEquals(HomeSection.entries, HomeConfig().completeOrder())
        assertEquals(HomeSection.DOWNLOADS, HomeConfig().visibleOrdered()[1])
    }
}
