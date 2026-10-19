package com.example.mimic.downloader.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The media downloader's queue and history (MImic 4.0, ported from ENAGELYUCA). A database of its
 * own, next to MimicDatabase, because:
 *  - it only exists in the full edition (the standard edition has no downloader code at all);
 *  - MimicDatabase already has a `downloads` table (offline copies of Navidrome/cloud songs) with
 *    different semantics — keeping the two apart avoids a risky merge of unrelated rows.
 * Downloaded music reaches MImic's library through MediaStore (Music/MImic), the same path as
 * every other phone song, so there is still one music library and one player.
 *
 * Schema changes must add a Migration (never fallbackToDestructiveMigration): the queue and
 * history are user data.
 */
@Database(entities = [DownloadEntity::class, HistoryEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class DownloaderDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun historyDao(): HistoryDao

    companion object {
        const val NAME = "mimic_downloader.db"

        fun build(context: Context): DownloaderDatabase =
            Room.databaseBuilder(context.applicationContext, DownloaderDatabase::class.java, NAME).build()
    }
}
