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
        FavoriteGroupEntity::class,
        PendingStarEntity::class,
        CloudAccountEntity::class,
        CloudItemEntity::class,
        com.example.mimic.data.radio.RadioStationEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class MimicDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun customThemeDao(): CustomThemeDao
    abstract fun downloadDao(): DownloadDao
    abstract fun favoriteGroupDao(): FavoriteGroupDao
    abstract fun pendingStarDao(): PendingStarDao
    abstract fun cloudDao(): CloudDao
    abstract fun radioDao(): com.example.mimic.data.radio.RadioDao

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

        /**
         * v4 → v5 (MImic 3.0): downloads gain album/account/size columns (nullable/defaulted, so
         * every existing download row is kept as-is); new favorite_groups and pending_server_ops
         * tables. Purely additive — no table is dropped or rewritten.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `albumId` TEXT")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `accountKey` TEXT")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `sizeBytes` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `favorite_groups` (" +
                        "`kind` TEXT NOT NULL, `groupKey` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`subtitle` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`kind`, `groupKey`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_server_ops` (" +
                        "`remoteId` TEXT NOT NULL, `accountKey` TEXT NOT NULL, `star` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`remoteId`, `accountKey`))",
                )
            }
        }

        /**
         * v5 → v6 (MImic 3.1): two new tables for Google Drive / OneDrive accounts and their file
         * trees. Purely additive — every existing table and row is untouched.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cloud_accounts` (" +
                        "`accountKey` TEXT NOT NULL, `provider` TEXT NOT NULL, `accountId` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, `email` TEXT, `folderId` TEXT, `folderName` TEXT, " +
                        "`syncToken` TEXT, `lastSyncAt` INTEGER NOT NULL, `lastError` TEXT, " +
                        "`connectedAt` INTEGER NOT NULL, `apiBase` TEXT, PRIMARY KEY(`accountKey`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cloud_items` (" +
                        "`accountKey` TEXT NOT NULL, `itemId` TEXT NOT NULL, `parentId` TEXT, " +
                        "`name` TEXT NOT NULL, `isFolder` INTEGER NOT NULL, `mimeType` TEXT, " +
                        "`size` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `modifiedAt` INTEGER NOT NULL, " +
                        "`contentVersion` TEXT, `hash` TEXT, `title` TEXT, `artist` TEXT, `albumArtist` TEXT, " +
                        "`album` TEXT, `trackNo` INTEGER, `discNo` INTEGER, `durationMs` INTEGER NOT NULL, " +
                        "`genre` TEXT, `year` INTEGER, `artUri` TEXT, `tagsVersion` TEXT, " +
                        "PRIMARY KEY(`accountKey`, `itemId`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_items_accountKey` ON `cloud_items` (`accountKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_items_parentId` ON `cloud_items` (`parentId`)")
            }
        }

        /**
         * v6 → v7 (MImic 4.2): saved / recently played radio stations. Purely additive — every
         * existing table and row (songs, playlists, favorites, history, downloads) is untouched.
         */
        /** Every upgrade step, in order — used by the app AND the migration tests, so they can't drift apart. */
        val ALL_MIGRATIONS: Array<Migration> by lazy { arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7) }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `radio_stations` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `countryCode` TEXT NOT NULL, `city` TEXT, " +
                        "`genre` TEXT, `category` TEXT NOT NULL, `streamUrl` TEXT NOT NULL, `logoUrl` TEXT, " +
                        "`websiteUrl` TEXT, `codec` TEXT, `bitrate` INTEGER, `hls` INTEGER NOT NULL DEFAULT 0, " +
                        "`favorite` INTEGER NOT NULL DEFAULT 0, `favoritedAt` INTEGER, `lastPlayedAt` INTEGER, " +
                        "`playCount` INTEGER NOT NULL DEFAULT 0, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_radio_stations_favorite` ON `radio_stations` (`favorite`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_radio_stations_lastPlayedAt` ON `radio_stations` (`lastPlayedAt`)")
            }
        }

        fun get(context: Context): MimicDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MimicDatabase::class.java,
                    "mimic.db",
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { instance = it }
            }
    }
}
