package com.example.mimic

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mimic.data.db.MimicDatabase
import com.example.mimic.data.toDefinition
import com.example.mimic.ui.designsystem.ThemeStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MImic 2.9: a real v3 database (2.5–2.8.4 schema) holding a custom theme and a favorite is
 * upgraded with the production migrations. The theme must survive with the default flat style
 * and no other data may be touched. Uses its own throwaway file — never the app's mimic.db.
 */
@RunWith(AndroidJUnit4::class)
class ThemeMigrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-test-v3.db"

    @After fun cleanup() {
        context.deleteDatabase(name)
    }

    @Test fun v3_custom_theme_survives_upgrade_to_v4() = runBlocking {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            V3_SCHEMA.forEach(db::execSQL)
            db.execSQL(
                "INSERT INTO custom_themes VALUES ('custom_1', 'My Old Theme', 1, 4280138068, 4279739210, " +
                    "4279374354, 4279769112, 4280558628, 4294967295, 4289967027, 1700000000000)",
            )
            db.execSQL("INSERT INTO favorites VALUES (42, 1700000000000)")
            db.version = 3
        }

        val room = Room.databaseBuilder(context, MimicDatabase::class.java, name)
            .addMigrations(MimicDatabase.MIGRATION_1_2, MimicDatabase.MIGRATION_2_3, MimicDatabase.MIGRATION_3_4)
            .build()
        try {
            val themes = room.customThemeDao().all().first()
            assertEquals(1, themes.size)
            assertNull(themes[0].styleJson)
            val def = themes[0].toDefinition()
            assertEquals("My Old Theme", def.name)
            assertEquals(0xFF1DB954L, def.colors.primary)
            assertEquals(ThemeStyle(), def.style)
            assertEquals(listOf(42L), room.favoriteDao().favoriteIds().first())
        } finally {
            room.close()
        }
    }

    private companion object {
        /** Exactly what Room created for schema version 3 (taken from a real 2.8.4 install). */
        val V3_SCHEMA = listOf(
            "CREATE TABLE `custom_themes` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `isDark` INTEGER NOT NULL, `primary` INTEGER NOT NULL, `secondary` INTEGER NOT NULL, `background` INTEGER NOT NULL, `surface` INTEGER NOT NULL, `surfaceElevated` INTEGER NOT NULL, `textPrimary` INTEGER NOT NULL, `textSecondary` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE `downloads` (`remoteId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `fileUri` TEXT NOT NULL, `coverArtUri` TEXT, `downloadedAt` INTEGER NOT NULL, PRIMARY KEY(`remoteId`))",
            "CREATE TABLE `favorites` (`songId` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`songId`))",
            "CREATE TABLE `play_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `songId` INTEGER NOT NULL, `playedAt` INTEGER NOT NULL)",
            "CREATE TABLE `playlist_songs` (`playlistId` INTEGER NOT NULL, `songId` INTEGER NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `songId`))",
            "CREATE TABLE `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
            "CREATE INDEX `index_play_history_playedAt` ON `play_history` (`playedAt`)",
            "CREATE INDEX `index_play_history_songId` ON `play_history` (`songId`)",
            "CREATE INDEX `index_playlist_songs_playlistId` ON `playlist_songs` (`playlistId`)",
            "CREATE INDEX `index_playlist_songs_position` ON `playlist_songs` (`position`)",
        )
    }
}
