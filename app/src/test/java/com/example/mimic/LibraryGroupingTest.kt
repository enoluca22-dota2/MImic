package com.example.mimic

import com.example.mimic.data.LibraryGrouping
import com.example.mimic.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun s(
    id: Long, artist: String, album: String, folder: String = "Music", genre: String? = null,
) = Song(id, "t$id", artist, album, 200_000, "content://$id", null, id, folder, genre)

class LibraryGroupingTest {

    private val lib = listOf(
        s(1, "Queen", "Opera", folder = "Rock", genre = "Rock"),
        s(2, "Queen", "Opera", folder = "Rock", genre = "Rock"),
        s(3, "Bowie", "Low", folder = "Rock", genre = null),
        s(4, "Miles", "Kind of Blue", folder = "Jazz", genre = "Jazz"),
    )

    @Test fun albums_group_by_album_with_artist_subtitle() {
        val albums = LibraryGrouping.albums(lib)
        assertEquals(listOf("Kind of Blue", "Low", "Opera"), albums.map { it.name })
        assertEquals("Queen", albums.first { it.name == "Opera" }.subtitle)
        assertEquals(2, albums.first { it.name == "Opera" }.songs.size)
    }

    @Test fun artists_group_and_count() {
        val artists = LibraryGrouping.artists(lib)
        assertEquals(listOf("Bowie", "Miles", "Queen"), artists.map { it.name })
        assertEquals("2 songs", artists.first { it.name == "Queen" }.subtitle)
    }

    @Test fun folders_group_by_folder() {
        val folders = LibraryGrouping.folders(lib)
        assertEquals(setOf("Jazz", "Rock"), folders.map { it.name }.toSet())
    }

    @Test fun genres_skip_null_and_blank() {
        val genres = LibraryGrouping.genres(lib)
        assertEquals(setOf("Jazz", "Rock"), genres.map { it.name }.toSet())
        assertTrue(genres.sumOf { it.songs.size } == 3) // song 3 has no genre
    }
}
