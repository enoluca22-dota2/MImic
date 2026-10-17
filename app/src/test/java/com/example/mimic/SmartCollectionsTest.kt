package com.example.mimic

import com.example.mimic.data.SmartCollections
import com.example.mimic.data.Song
import com.example.mimic.data.db.PlayCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun s(id: Long, added: Long = id) =
    Song(id, "t$id", "artist", "album", 200_000, "content://$id", null, added)

class SmartCollectionsTest {

    private val lib = listOf(s(1, added = 10), s(2, added = 40), s(3, added = 20), s(4, added = 30))

    @Test fun neverPlayed_excludes_played_and_sorts_newest_first() {
        val played = setOf(1L) // only song 1 has been played
        val result = SmartCollections.neverPlayed(lib, played, 10).map { it.id }
        assertEquals(listOf(2L, 4L, 3L), result) // added 40, 30, 20
    }

    @Test fun neverPlayed_respects_limit() {
        assertEquals(2, SmartCollections.neverPlayed(lib, emptySet(), 2).size)
    }

    @Test fun leastPlayed_keeps_given_order_and_maps_to_songs() {
        val counts = listOf(PlayCount(3, 1), PlayCount(1, 2), PlayCount(2, 5)) // ascending
        val result = SmartCollections.leastPlayed(lib, counts, 10).map { it.id }
        assertEquals(listOf(3L, 1L, 2L), result)
    }

    @Test fun forgottenFavorites_are_favorites_not_recently_played() {
        val favorites = setOf(1L, 2L, 3L)
        val recent = setOf(2L)
        val result = SmartCollections.forgottenFavorites(lib, favorites, recent, 10).map { it.id }.toSet()
        assertEquals(setOf(1L, 3L), result)
    }

    @Test fun randomDiscovery_is_deterministic_for_a_seed() {
        val a = SmartCollections.randomDiscovery(lib, seed = 42L, limit = 3).map { it.id }
        val b = SmartCollections.randomDiscovery(lib, seed = 42L, limit = 3).map { it.id }
        assertEquals(a, b)
        assertEquals(3, a.size)
        assertTrue(a.all { it in setOf(1L, 2L, 3L, 4L) })
    }

    @Test fun randomDiscovery_differs_across_seeds() {
        val big = (1L..20L).map { s(it) } // collision between two seeds is negligible for 20 items
        val a = SmartCollections.randomDiscovery(big, seed = 1L, limit = 20).map { it.id }
        val b = SmartCollections.randomDiscovery(big, seed = 2L, limit = 20).map { it.id }
        assertTrue(a != b) // guards that the seed is actually used
    }
}
