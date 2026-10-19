package com.example.mimic.downloader.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entity: HistoryEntity): Long

    @Query("SELECT * FROM history ORDER BY completedAt DESC")
    fun observeAll(): Flow<List<HistoryEntity>>

    /** Previous downloads of the same media (matched on the extractor's canonical page URL). */
    @Query("SELECT * FROM history WHERE webpageUrl IN (:urls) OR sourceUrl IN (:urls) ORDER BY completedAt DESC")
    suspend fun findByUrls(urls: List<String>): List<HistoryEntity>

    /** Home's "Recent downloads": only entries that produced a file. */
    @Query("SELECT * FROM history WHERE status = 'COMPLETED' ORDER BY completedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryEntity>>

    /** Completed downloads that produced a file, oldest first (the Library imports them). */
    @Query("SELECT * FROM history WHERE status = 'COMPLETED' AND fileUri IS NOT NULL ORDER BY completedAt ASC")
    suspend fun getCompletedWithFile(): List<HistoryEntity>

    @Query("DELETE FROM history")
    suspend fun clearAll()

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Long)
}
