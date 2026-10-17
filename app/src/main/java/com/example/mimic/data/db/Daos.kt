package com.example.mimic.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT songId FROM favorites ORDER BY addedAt DESC")
    fun favoriteIds(): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE songId = :songId")
    suspend fun remove(songId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE songId = :songId)")
    suspend fun isFavorite(songId: Long): Boolean
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun record(event: PlayHistoryEntity)

    /** Most recent distinct songs, newest first. */
    @Query(
        "SELECT songId FROM play_history GROUP BY songId " +
            "ORDER BY MAX(playedAt) DESC LIMIT :limit",
    )
    fun recentlyPlayedIds(limit: Int): Flow<List<Long>>

    @Query(
        "SELECT songId, COUNT(*) AS count FROM play_history " +
            "GROUP BY songId ORDER BY count DESC LIMIT :limit",
    )
    fun mostPlayed(limit: Int): Flow<List<PlayCount>>

    @Query("SELECT COUNT(*) FROM play_history WHERE songId = :songId")
    suspend fun playCount(songId: Long): Int

    /** All distinct song ids that have ever been played (for "Never played"). */
    @Query("SELECT DISTINCT songId FROM play_history")
    fun playedSongIds(): Flow<List<Long>>

    /** Least-played songs (played at least once), lowest count first. */
    @Query(
        "SELECT songId, COUNT(*) AS count FROM play_history " +
            "GROUP BY songId ORDER BY count ASC, MAX(playedAt) ASC LIMIT :limit",
    )
    fun leastPlayed(limit: Int): Flow<List<PlayCount>>

    @Query("DELETE FROM play_history WHERE playedAt < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY downloadedAt DESC")
    fun all(): Flow<List<DownloadEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE remoteId = :remoteId")
    suspend fun delete(remoteId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM downloads WHERE remoteId = :remoteId)")
    suspend fun isDownloaded(remoteId: String): Boolean
}

@Dao
interface CustomThemeDao {
    @Query("SELECT * FROM custom_themes ORDER BY createdAt DESC")
    fun all(): Flow<List<CustomThemeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(theme: CustomThemeEntity)

    @Query("DELETE FROM custom_themes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM custom_themes WHERE id = :id")
    suspend fun get(id: String): CustomThemeEntity?
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun playlists(): Flow<List<PlaylistEntity>>

    @Insert
    suspend fun create(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :playlistId")
    suspend fun rename(playlistId: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clearMembers(playlistId: Long)

    @Query(
        "SELECT songId FROM playlist_songs WHERE playlistId = :playlistId " +
            "ORDER BY position ASC",
    )
    fun songIdsIn(playlistId: Long): Flow<List<Long>>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMember(member: PlaylistSongEntity)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeMember(playlistId: Long, songId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembers(members: List<PlaylistSongEntity>)

    /** Replace the full ordering of a playlist atomically (used by drag-to-reorder). */
    @Transaction
    suspend fun reorder(playlistId: Long, orderedSongIds: List<Long>) {
        clearMembers(playlistId)
        upsertMembers(
            orderedSongIds.mapIndexed { index, songId ->
                PlaylistSongEntity(playlistId, songId, index)
            },
        )
    }
}
