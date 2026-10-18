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

    @Query("SELECT songId, MAX(playedAt) AS playedAt FROM play_history GROUP BY songId")
    fun lastPlayedAll(): Flow<List<LastPlayed>>

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

    @Query("SELECT * FROM downloads WHERE remoteId = :remoteId")
    suspend fun get(remoteId: String): DownloadEntity?

    @Query("SELECT * FROM downloads")
    suspend fun allOnce(): List<DownloadEntity>

    @Query("SELECT remoteId FROM downloads")
    fun downloadedIds(): Flow<List<String>>
}

@Dao
interface FavoriteGroupDao {
    @Query("SELECT * FROM favorite_groups ORDER BY addedAt DESC")
    fun all(): Flow<List<FavoriteGroupEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(group: FavoriteGroupEntity)

    @Query("DELETE FROM favorite_groups WHERE kind = :kind AND groupKey = :groupKey")
    suspend fun remove(kind: String, groupKey: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_groups WHERE kind = :kind AND groupKey = :groupKey)")
    suspend fun isFavorite(kind: String, groupKey: String): Boolean
}

@Dao
interface PendingStarDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(op: PendingStarEntity)

    @Query("SELECT * FROM pending_server_ops WHERE accountKey = :accountKey ORDER BY createdAt ASC")
    suspend fun forAccount(accountKey: String): List<PendingStarEntity>

    @Query("SELECT COUNT(*) FROM pending_server_ops")
    fun count(): Flow<Int>

    @Query("DELETE FROM pending_server_ops WHERE remoteId = :remoteId AND accountKey = :accountKey")
    suspend fun remove(remoteId: String, accountKey: String)
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

/** Connected cloud accounts and their stored file trees (MImic 3.1). */
@Dao
interface CloudDao {
    @Query("SELECT * FROM cloud_accounts ORDER BY connectedAt")
    fun accounts(): Flow<List<CloudAccountEntity>>

    @Query("SELECT * FROM cloud_accounts ORDER BY connectedAt")
    suspend fun accountsOnce(): List<CloudAccountEntity>

    @Query("SELECT * FROM cloud_accounts WHERE accountKey = :accountKey")
    suspend fun account(accountKey: String): CloudAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccount(account: CloudAccountEntity)

    @Query("UPDATE cloud_accounts SET lastError = :error WHERE accountKey = :accountKey")
    suspend fun setError(accountKey: String, error: String?)

    @Query("UPDATE cloud_accounts SET syncToken = :token, lastSyncAt = :at, lastError = NULL WHERE accountKey = :accountKey")
    suspend fun markSynced(accountKey: String, token: String?, at: Long)

    @Query("UPDATE cloud_accounts SET syncToken = :token WHERE accountKey = :accountKey")
    suspend fun setSyncToken(accountKey: String, token: String?)

    @Query("SELECT * FROM cloud_items")
    fun allItems(): Flow<List<CloudItemEntity>>

    @Query("SELECT * FROM cloud_items WHERE accountKey = :accountKey")
    suspend fun itemsOnce(accountKey: String): List<CloudItemEntity>

    @Query("SELECT * FROM cloud_items WHERE accountKey = :accountKey AND itemId = :itemId")
    suspend fun item(accountKey: String, itemId: String): CloudItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<CloudItemEntity>)

    @Query("DELETE FROM cloud_items WHERE accountKey = :accountKey AND itemId IN (:itemIds)")
    suspend fun deleteItems(accountKey: String, itemIds: List<String>)

    @Query("DELETE FROM cloud_items WHERE accountKey = :accountKey")
    suspend fun clearItems(accountKey: String)

    /** Replace an account's whole tree (a full re-listing) in one transaction. */
    @Transaction
    suspend fun replaceItems(accountKey: String, items: List<CloudItemEntity>) {
        clearItems(accountKey)
        items.chunked(500).forEach { upsertItems(it) }
    }

    /** Disconnect: forget the account and its file list. Downloads are separate and kept. */
    @Transaction
    suspend fun removeAccount(accountKey: String) {
        clearItems(accountKey)
        deleteAccount(accountKey)
    }

    @Query("DELETE FROM cloud_accounts WHERE accountKey = :accountKey")
    suspend fun deleteAccount(accountKey: String)
}
