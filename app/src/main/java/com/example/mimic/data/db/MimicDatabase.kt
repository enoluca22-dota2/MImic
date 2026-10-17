package com.example.mimic.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        FavoriteEntity::class,
        PlayHistoryEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class MimicDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile
        private var instance: MimicDatabase? = null

        fun get(context: Context): MimicDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MimicDatabase::class.java,
                    "mimic.db",
                )
                    // Future schema changes add .addMigrations(...) here; version bumps
                    // are handled explicitly so user data is preserved.
                    .build()
                    .also { instance = it }
            }
    }
}
