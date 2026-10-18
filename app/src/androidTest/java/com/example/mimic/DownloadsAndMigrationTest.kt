package com.example.mimic

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mimic.data.DownloadRepository
import com.example.mimic.data.SongSource
import com.example.mimic.data.db.DownloadEntity
import com.example.mimic.data.db.FavoriteGroupEntity
import com.example.mimic.data.db.MimicDatabase
import com.example.mimic.data.db.PendingStarEntity
import com.example.mimic.data.server.remoteStableId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * MImic 3.0 data layer on a real device: the v4 -> v5 upgrade keeps every user record, and the
 * downloads table drives the reactive song list Home observes (a new verified download shows up
 * without any refresh; a deleted file becomes a recoverable "missing" entry, not a vanished one).
 */
@RunWith(AndroidJUnit4::class)
class DownloadsAndMigrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-test-v4.db"

    @After fun cleanup() {
        context.deleteDatabase(name)
    }

    @Test fun v4_database_upgrades_to_v5_keeping_downloads_favorites_playlists_themes() = runBlocking {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            ThemeMigrationTest.V3_SCHEMA.forEach(db::execSQL)
            db.execSQL("ALTER TABLE `custom_themes` ADD COLUMN `styleJson` TEXT") // = v4
            db.execSQL(
                "INSERT INTO downloads VALUES ('srv1', 'Aerials', 'System Of A Down', 'Toxicity', 240000, " +
                    "'file:///data/x/srv1.mp3', 'http://h/cover', 1700000000000)",
            )
            db.execSQL("INSERT INTO favorites VALUES (42, 1700000000000)")
            db.execSQL("INSERT INTO playlists (id, name, createdAt) VALUES (7, 'Roadtrip', 1)")
            db.execSQL("INSERT INTO playlist_songs VALUES (7, 42, 0)")
            db.execSQL(
                "INSERT INTO custom_themes VALUES ('custom_1', 'Mine', 1, 1, 2, 3, 4, 5, 6, 7, 8, '{\"glass\":true}')",
            )
            db.version = 4
        }
        val room = Room.databaseBuilder(context, MimicDatabase::class.java, name)
            .addMigrations(MimicDatabase.MIGRATION_1_2, MimicDatabase.MIGRATION_2_3, MimicDatabase.MIGRATION_3_4, MimicDatabase.MIGRATION_4_5)
            .build()
        try {
            val dl = room.downloadDao().all().first()
            assertEquals(1, dl.size)
            assertEquals("Aerials", dl[0].title)
            assertNull(dl[0].albumId)
            assertNull(dl[0].accountKey)
            assertEquals(0L, dl[0].sizeBytes)
            assertEquals(listOf(42L), room.favoriteDao().favoriteIds().first())
            assertEquals(listOf(42L), room.playlistDao().songIdsIn(7).first())
            assertEquals("{\"glass\":true}", room.customThemeDao().get("custom_1")?.styleJson)
            // New v5 tables work.
            room.favoriteGroupDao().add(FavoriteGroupEntity(FavoriteGroupEntity.KIND_ALBUM, "server:album:a1", "Toxicity", "SOAD", 1))
            assertEquals(1, room.favoriteGroupDao().all().first().size)
            room.pendingStarDao().put(PendingStarEntity("srv1", "u@h", true, 1))
            room.pendingStarDao().put(PendingStarEntity("srv1", "u@h", false, 2)) // latest intent wins
            val pending = room.pendingStarDao().forAccount("u@h")
            assertEquals(1, pending.size)
            assertFalse(pending[0].star)
        } finally {
            room.close()
        }
    }

    @Test fun downloads_flow_emits_new_download_and_flags_missing_file() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MimicDatabase::class.java).build()
        try {
            val repo = DownloadRepository(context, db.downloadDao())
            assertTrue(repo.songs.first().isEmpty())

            val audio = File(context.cacheDir, "dl-test.mp3").apply { writeBytes(ByteArray(2048) { 1 }) }
            // Observe like Home does (subscribed BEFORE the download is recorded), then record it.
            val emissions = Channel<List<com.example.mimic.data.Song>>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.Default) { repo.songs.collect { emissions.send(it) } }
            assertTrue(withTimeout(5000) { emissions.receive() }.isEmpty())
            db.downloadDao().upsert(
                DownloadEntity(
                    remoteId = "abc", title = "Song", artist = "[Unknown Artist]", album = "Album",
                    durationMs = 1000, fileUri = Uri.fromFile(audio).toString(), coverArtUri = null,
                    downloadedAt = 1, albumId = "al1", accountKey = "u@h", sizeBytes = 2048,
                ),
            )
            val songs = withTimeout(5000) { emissions.receive() } // pushed by Room, no re-query
            collector.cancel()
            assertEquals(1, songs.size)
            val s = songs[0]
            assertEquals(remoteStableId("abc"), s.id) // same identity as the streamed server track
            assertEquals(SongSource.DOWNLOADED, s.source)
            assertEquals("Unknown Artist", s.artist) // legacy placeholder normalized
            assertEquals("server:album:al1", s.albumKey)
            assertFalse(s.fileMissing)
            assertTrue(s.isOffline)

            // File disappears -> still listed (recoverable), flagged missing, not offline-playable.
            audio.delete()
            db.downloadDao().upsert(db.downloadDao().get("abc")!!.copy(title = "Song"))
            val after = repo.songs.first()
            assertEquals(1, after.size)
            assertTrue(after[0].fileMissing)
            assertFalse(after[0].isOffline)

            // Removing the download removes only the local record/file.
            repo.remove("abc")
            assertTrue(repo.songs.first().isEmpty())
        } finally {
            db.close()
        }
    }
}
