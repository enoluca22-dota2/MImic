package com.example.mimic.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FavoriteEntity::class,
        PlayHistoryEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        CustomThemeEntity::class,
        DownloadEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class MimicDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun customThemeDao(): CustomThemeDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var instance: MimicDatabase? = null

        /** v1 → v2: add the custom_themes table. Existing user data is untouched. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `custom_themes` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `isDark` INTEGER NOT NULL, " +
                        "`primary` INTEGER NOT NULL, `secondary` INTEGER NOT NULL, " +
                        "`background` INTEGER NOT NULL, `surface` INTEGER NOT NULL, " +
                        "`surfaceElevated` INTEGER NOT NULL, `textPrimary` INTEGER NOT NULL, " +
                        "`textSecondary` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
            }
        }

        /** v2 → v3: add the downloads table. Existing user data is untouched. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `downloads` (" +
                        "`remoteId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, " +
                        "`album` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `fileUri` TEXT NOT NULL, " +
                        "`coverArtUri` TEXT, `downloadedAt` INTEGER NOT NULL, PRIMARY KEY(`remoteId`))",
                )
            }
        }

        /** v3 → v4: custom themes gain a nullable style column. Existing rows keep NULL (flat style). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `custom_themes` ADD COLUMN `styleJson` TEXT")
            }
        }

        fun get(context: Context): MimicDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MimicDatabase::class.java,
                    "mimic.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
    }
}
