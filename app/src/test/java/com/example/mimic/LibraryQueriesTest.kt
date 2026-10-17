package com.example.mimic

import com.example.mimic.data.LibraryQueries
import com.example.mimic.data.LibraryQueries.moved
import com.example.mimic.data.Song
import com.example.mimic.data.db.PlayCount
import com.example.mimic.ui.components.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun song(
    id: Long,
    title: String = "t$id",
    artist: String = "artist",
    album: String = "album",
    added: Long = id,
) = Song(id, title, artist, album, 200_000, "content://$id", null, added)

class LibraryQueriesTest {

    private val library = listOf(
        song(1, title = "Bohemian Rhapsody", artist = "Queen", album = "A Night at the Opera", added = 10),
        song(2, title = "Under Pressure", artist = "Queen", album = "Hot Space", added = 30),
        song(3, title = "Imagine", artist = "John Lennon", album = "Imagine", added = 20),
        song(4, title = "Jam", artist = "Michael Jackson", album = "Dangerous", added = 40),
    )

    @Test fun search_blank_returns_empty() {
        assertTrue(LibraryQueries.search(library, "   ").isEmpty())
    }

    @Test fun search_matches_title_artist_album_case_insensitive() {
        assertEquals(setOf(1L, 2L), LibraryQueries.search(library, "queen").map { it.id }.toSet())
        assertEquals(listOf(1L), LibraryQueries.search(library, "bohemian").map { it.id })
        assertEquals(listOf(3L), LibraryQueries.search(library, "IMAGINE").map { it.id }.distinct())
    }

    @Test fun recentlyAdded_sorts_desc_and_limits() {
        val result = LibraryQueries.recentlyAdded(library, 2).map { it.id }
        assertEquals(listOf(4L, 2L), result)
    }

    @Test fun suggested_returns_same_artist_unplayed_only() {
        // User has played song 1 (Queen). Suggest other Queen songs not yet played.
        val counts = listOf(PlayCount(songId = 1, count = 5))
        val result = LibraryQueries.suggested(library, counts, 10).map { it.id }
        assertEquals(listOf(2L), result) // song 2 is Queen and unplayed; song 1 excluded (played)
    }

    @Test fun suggested_empty_when_no_history() {
        assertTrue(LibraryQueries.suggested(library, emptyList(), 10).isEmpty())
    }

    @Test fun moved_reorders_within_bounds() {
        val ids = listOf(10L, 20L, 30L, 40L)
        with(LibraryQueries) {
            assertEquals(listOf(20L, 30L, 10L, 40L), ids.moved(0, 2))
            assertEquals(listOf(40L, 10L, 20L, 30L), ids.moved(3, 0))
            // Out-of-bounds is a no-op.
            assertEquals(ids, ids.moved(0, 9))
        }
    }

    @Test fun formatDuration_renders_minutes_seconds() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:05", formatDuration(5_000))
        assertEquals("1:01", formatDuration(61_000))
        assertEquals("3:20", formatDuration(200_000))
    }
}
