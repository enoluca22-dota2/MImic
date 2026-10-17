package com.example.mimic

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mimic.data.db.FavoriteEntity
import com.example.mimic.data.db.MimicDatabase
import com.example.mimic.data.db.PlayHistoryEntity
import com.example.mimic.data.db.PlaylistEntity
import com.example.mimic.data.db.PlaylistSongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the Room layer on-device with an in-memory database:
 * favorites, play-count/history aggregation, and playlist persistence + reordering.
 */
@RunWith(AndroidJUnit4::class)
class MimicDatabaseTest {

    private lateinit var db: MimicDatabase

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, MimicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    @Test fun favorites_add_and_remove() = runBlocking {
        val dao = db.favoriteDao()
        assertFalse(dao.isFavorite(42))
        dao.add(FavoriteEntity(42, System.currentTimeMillis()))
        assertTrue(dao.isFavorite(42))
        assertEquals(listOf(42L), dao.favoriteIds().first())
        dao.remove(42)
        assertFalse(dao.isFavorite(42))
        assertTrue(dao.favoriteIds().first().isEmpty())
    }

    @Test fun history_counts_and_recent_order() = runBlocking {
        val dao = db.historyDao()
        dao.record(PlayHistoryEntity(songId = 1, playedAt = 100))
        dao.record(PlayHistoryEntity(songId = 1, playedAt = 300))
        dao.record(PlayHistoryEntity(songId = 2, playedAt = 200))
        assertEquals(2, dao.playCount(1))
        assertEquals(1, dao.playCount(2))
        // Most recent distinct songs: song 1 last played at 300, song 2 at 200.
        assertEquals(listOf(1L, 2L), dao.recentlyPlayedIds(10).first())
        // Most played first.
        assertEquals(1L, dao.mostPlayed(10).first().first().songId)
    }

    @Test fun playlist_membership_persists_and_reorders() = runBlocking {
        val dao = db.playlistDao()
        val id = dao.create(PlaylistEntity(name = "Roadtrip", createdAt = 0))
        listOf(10L, 20L, 30L).forEach { songId ->
            dao.insertMember(PlaylistSongEntity(id, songId, dao.nextPosition(id)))
        }
        assertEquals(listOf(10L, 20L, 30L), dao.songIdsIn(id).first())

        dao.reorder(id, listOf(30L, 10L, 20L))
        assertEquals(listOf(30L, 10L, 20L), dao.songIdsIn(id).first())

        dao.removeMember(id, 10L)
        assertEquals(listOf(30L, 20L), dao.songIdsIn(id).first())

        dao.clearMembers(id)
        dao.deletePlaylist(id)
        assertTrue(dao.playlists().first().isEmpty())
    }
}
