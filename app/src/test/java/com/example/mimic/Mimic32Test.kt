package com.example.mimic

import com.example.mimic.data.GroupSort
import com.example.mimic.data.GroupSorting
import com.example.mimic.data.LibraryConfig
import com.example.mimic.data.LibraryQueries
import com.example.mimic.data.LibraryTab
import com.example.mimic.data.UnifiedLibrary
import com.example.mimic.data.movedTab
import com.example.mimic.data.QueueSnapshot
import com.example.mimic.data.SmartCollections
import com.example.mimic.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun song(id: Long, artist: String = "a", album: String = "al$id") =
    Song(id, "t$id", artist, album, 200_000, "content://$id", null, id)

/** MImic 3.2: queue resume, Continue Listening albums, Smart Mixes. */
class QueueSnapshotTest {

    @Test fun ids_round_trip_and_ignore_garbage() {
        val ids = listOf(5L, -9_000_000_000_123L, 7L)
        assertEquals(ids, QueueSnapshot.decodeIds(QueueSnapshot.encodeIds(ids)))
        assertEquals(listOf(1L, 3L), QueueSnapshot.decodeIds("1,x,3,"))
        assertEquals(emptyList<Long>(), QueueSnapshot.decodeIds(null))
    }

    @Test fun resumes_saved_song_at_saved_position() {
        val snap = QueueSnapshot(listOf(1, 2, 3), index = 1, positionMs = 42_000)
        assertEquals(1 to 42_000L, snap.resumePoint(listOf(1, 2, 3)))
    }

    @Test fun saved_song_gone_resumes_next_survivor_from_start() {
        val snap = QueueSnapshot(listOf(1, 2, 3, 4), index = 1, positionMs = 42_000)
        // Song 2 was deleted and 3 isn't reachable offline: resume at 4 (index 1 of [1, 4]).
        assertEquals(1 to 0L, snap.resumePoint(listOf(1, 4)))
    }

    @Test fun nothing_survived_means_nothing_to_restore() {
        assertNull(QueueSnapshot(listOf(1, 2), 0, 0).resumePoint(emptyList()))
    }

    @Test fun out_of_range_index_falls_back_to_first() {
        assertEquals(0 to 0L, QueueSnapshot(listOf(1, 2), index = 9, positionMs = 5).resumePoint(listOf(1, 2)))
    }
}

class RecentAlbumsTest {
    @Test fun albums_in_most_recent_play_order_once_each() {
        val lib = listOf(song(1, album = "A"), song(2, album = "B"), song(3, album = "A"), song(4, album = "C"))
        val albums = LibraryQueries.recentAlbums(recentIds = listOf(3, 2, 1, 99), songs = lib, limit = 10)
        assertEquals(listOf("A", "B"), albums.map { it.title })
        assertEquals(2, albums.first().songs.size) // the whole album, not just the played song
    }

    @Test fun limit_applies_to_albums() {
        val lib = (1L..5L).map { song(it) }
        assertEquals(2, LibraryQueries.recentAlbums(listOf(1, 2, 3, 4), lib, 2).size)
    }
}

class SmartMixTest {
    @Test fun favorite_artists_mix_interleaves_artists_and_excludes_others() {
        val lib = (1L..6L).map { song(it, artist = "big") } + listOf(song(10, artist = "small"), song(20, artist = "other"))
        val mix = SmartCollections.favoriteArtistsMix(
            lib, favoriteArtistKeys = setOf(lib.first().artistKey), favoriteSongIds = setOf(10L),
            recentlyPlayedIds = emptyList(), seed = 1, limit = 50,
        )
        assertTrue(mix.none { it.artist == "other" })
        assertEquals(7, mix.size)
        // Round-robin: "small" appears within the first two songs, not after all six "big" ones.
        assertTrue(mix.take(2).any { it.artist == "small" })
    }

    @Test fun favorite_artists_mix_puts_just_played_songs_last() {
        val lib = (1L..4L).map { song(it, artist = "x") }
        val mix = SmartCollections.favoriteArtistsMix(lib, setOf(lib.first().artistKey), emptySet(), listOf(2L), 7, 50)
        assertEquals(2L, mix.last().id)
    }

    @Test fun favorite_artists_mix_empty_without_favorites() {
        assertTrue(SmartCollections.favoriteArtistsMix(listOf(song(1)), emptySet(), emptySet(), emptyList(), 1, 10).isEmpty())
    }

    @Test fun shuffle_discovery_caps_artists_and_is_deterministic() {
        val lib = (1L..30L).map { song(it, artist = if (it <= 20) "same" else "a$it") }
        val a = SmartCollections.shuffleDiscovery(lib, emptyMap(), seed = 3, limit = 20)
        assertEquals(a, SmartCollections.shuffleDiscovery(lib, emptyMap(), seed = 3, limit = 20))
        assertTrue(a.count { it.artist == "same" } <= 3)
        assertEquals(13, a.size) // 3 of "same" + the 10 other artists
    }

    @Test fun shuffle_discovery_prefers_rarely_played() {
        val lib = (1L..40L).map { song(it, artist = "a$it") }
        val heavy = (1L..20L).associateWith { 500 } // the first half was played a lot
        var rareHits = 0
        for (seed in 1L..20L) rareHits += SmartCollections.shuffleDiscovery(lib, heavy, seed, 10).count { it.id > 20 }
        assertTrue("rarely played songs should dominate, got $rareHits/200", rareHits > 180)
    }
}

class SearchAndLibraryConfigTest {
    private fun t(id: Long, title: String, artist: String = "x", album: String = "y") =
        Song(id, title, artist, album, 1, "u$id", null, id)

    @Test fun search_ranks_title_prefix_first_and_ignores_accents() {
        val lib = listOf(t(1, "Love Song", artist = "Beyoncé"), t(2, "Beyonce Live"), t(3, "Intro", album = "Beyoncé Hits"))
        assertEquals(listOf(2L, 1L, 3L), LibraryQueries.search(lib, "beyonce").map { it.id })
    }

    @Test fun search_requires_every_word_across_fields() {
        val lib = listOf(t(1, "Aerials", artist = "System Of A Down"), t(2, "Aerials (cover)", artist = "Someone"))
        assertEquals(listOf(1L), LibraryQueries.search(lib, "aerials system").map { it.id })
        assertTrue(LibraryQueries.search(lib, "   ").isEmpty())
    }

    @Test fun recent_searches_dedupe_newest_first_capped() {
        var r = emptyList<String>()
        for (q in listOf("a", "b", "A", " ", "c")) r = LibraryQueries.pushRecent(r, q, max = 2)
        assertEquals(listOf("c", "A"), r)
    }

    @Test fun library_tabs_complete_hide_and_never_empty() {
        val cfg = LibraryConfig(tabOrder = listOf(LibraryTab.DOWNLOADS, LibraryTab.SONGS), hiddenTabs = setOf(LibraryTab.SONGS))
        assertEquals(LibraryTab.DOWNLOADS, cfg.visibleTabs().first())
        assertEquals(LibraryTab.entries.size - 1, cfg.visibleTabs().size)
        assertEquals(listOf(LibraryTab.SONGS), LibraryConfig(hiddenTabs = LibraryTab.entries.toSet()).visibleTabs())
        assertEquals(LibraryTab.ALBUMS, LibraryTab.entries.movedTab(LibraryTab.ALBUMS, -1).first())
    }

    @Test fun album_sort_by_size_and_reversed() {
        val lib = listOf(t(1, "a", album = "Big"), t(2, "b", album = "Big"), t(3, "c", album = "Small"))
        val albums = UnifiedLibrary.albums(lib)
        assertEquals(listOf("Big", "Small"), GroupSorting.albums(albums, GroupSort.SIZE, false).map { it.title })
        assertEquals(listOf("Small", "Big"), GroupSorting.albums(albums, GroupSort.SIZE, true).map { it.title })
    }
}
