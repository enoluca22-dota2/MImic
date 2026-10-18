package com.example.mimic

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mimic.data.DownloadRepository
import com.example.mimic.data.MusicProvider
import com.example.mimic.data.cloud.CloudCatalogMapper
import com.example.mimic.data.cloud.CloudRef
import com.example.mimic.data.cloud.CloudProviderType
import com.example.mimic.data.db.CloudAccountEntity
import com.example.mimic.data.db.CloudItemEntity
import com.example.mimic.data.db.MimicDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/**
 * MImic 3.1 on a real device: the v5 -> v6 upgrade keeps every existing record and adds the cloud
 * tables; disconnecting a cloud account never touches downloads; a cloud download is verified
 * against the provider hash before it is recorded.
 */
@RunWith(AndroidJUnit4::class)
class CloudMigrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-test-v5.db"

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun v4_and_v5_data_survive_the_upgrade_to_v6() = runBlocking {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            ThemeMigrationTest.V3_SCHEMA.forEach(db::execSQL)
            db.execSQL("ALTER TABLE `custom_themes` ADD COLUMN `styleJson` TEXT")
            db.execSQL("INSERT INTO downloads VALUES ('srv1', 'Aerials', 'SOAD', 'Toxicity', 240000, 'file:///x.mp3', NULL, 1)")
            db.execSQL("INSERT INTO favorites VALUES (42, 1)")
            db.execSQL("INSERT INTO playlists (id, name, createdAt) VALUES (7, 'Roadtrip', 1)")
            db.execSQL("INSERT INTO playlist_songs VALUES (7, 42, 0)")
            db.version = 4
        }
        val room = Room.databaseBuilder(context, MimicDatabase::class.java, name)
            .addMigrations(
                MimicDatabase.MIGRATION_1_2, MimicDatabase.MIGRATION_2_3, MimicDatabase.MIGRATION_3_4,
                MimicDatabase.MIGRATION_4_5, MimicDatabase.MIGRATION_5_6,
            ).build()
        try {
            assertEquals("Aerials", room.downloadDao().all().first().single().title)
            assertEquals(listOf(42L), room.favoriteDao().favoriteIds().first())
            assertEquals(listOf(42L), room.playlistDao().songIdsIn(7).first())
            // New v6 tables are usable, and an account's tree can be replaced and removed.
            val dao = room.cloudDao()
            val acct = CloudAccountEntity("onedrive:A", "onedrive", "A", "Me", null, "ROOT", "Music", null, 0, null, 1)
            dao.upsertAccount(acct)
            dao.replaceItems("onedrive:A", listOf(CloudItemEntity("onedrive:A", "s1", "ROOT", "a.mp3", false, null, 1, 0, 0, "v1", null)))
            assertEquals(1, dao.itemsOnce("onedrive:A").size)
            dao.markSynced("onedrive:A", "delta-1", 5)
            assertEquals("delta-1", dao.account("onedrive:A")!!.syncToken)
            dao.removeAccount("onedrive:A")
            assertNull(dao.account("onedrive:A"))
            assertTrue(dao.itemsOnce("onedrive:A").isEmpty())
            assertEquals(1, room.downloadDao().all().first().size) // downloads untouched
        } finally {
            room.close()
        }
    }

    @Test fun cloud_download_is_verified_and_survives_disconnect() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MimicDatabase::class.java).build()
        try {
            val repo = DownloadRepository(context, db.downloadDao())
            val acct = CloudAccountEntity("gdrive:P", "gdrive", "P", "Me", null, "ROOT", "Music", null, 0, null, 1)
            val item = CloudItemEntity("gdrive:P", "f1", "ROOT", "Song.mp3", false, "audio/mpeg", 4096, 0, 0, "v1", null)
            val song = CloudCatalogMapper.map(acct, listOf(item)).songs.single()
            val bytes = ByteArray(4096) { (it % 251).toByte() }
            val md5 = "md5:" + MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

            // Wrong hash: rejected, nothing recorded, no partial file left.
            val bad = repo.saveCloudDownload(song, acct.accountKey, "mp3", ByteArrayInputStream(bytes), 4096, "md5:00", {})
            assertTrue(bad.isFailure)
            assertTrue(repo.songs.first().isEmpty())
            // Truncated: rejected.
            assertTrue(repo.saveCloudDownload(song, acct.accountKey, "mp3", ByteArrayInputStream(bytes.copyOf(100)), 4096, null, {}).isFailure)

            // Correct: recorded with the same identity as the streamed cloud song.
            assertEquals(4096L, repo.saveCloudDownload(song, acct.accountKey, "mp3", ByteArrayInputStream(bytes), 4096, md5, {}).getOrThrow())
            val dl = repo.songs.first().single()
            assertEquals(song.id, dl.id)
            assertEquals(CloudRef(CloudProviderType.GOOGLE_DRIVE, "P", "f1").remoteId, dl.remoteId)
            assertEquals(MusicProvider.GOOGLE_DRIVE, dl.provider)
            assertEquals(song.albumKey, dl.albumKey)
            assertTrue(dl.isOffline)

            // Disconnecting the account (cloud tables) leaves the offline copy playable.
            db.cloudDao().removeAccount(acct.accountKey)
            assertTrue(repo.songs.first().single().isOffline)
            repo.remove(dl.remoteId!!)
        } finally {
            db.close()
        }
    }
}
