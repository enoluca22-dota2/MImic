package com.example.mimic.downloader.data.local.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.mimic.downloader.data.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: DownloadEntity): Long

    @Update
    suspend fun update(entity: DownloadEntity)

    @Delete
    suspend fun delete(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: Long): DownloadEntity?

    @Query("SELECT id FROM downloads")
    suspend fun getAllIds(): List<Long>

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = :status ORDER BY CASE WHEN queuePosition > 0 THEN queuePosition ELSE createdAt END ASC, id ASC")
    fun observeByStatus(status: DownloadStatus): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status IN (:statuses) ORDER BY CASE WHEN queuePosition > 0 THEN queuePosition ELSE createdAt END ASC, id ASC")
    suspend fun getByStatuses(statuses: List<DownloadStatus>): List<DownloadEntity>

    @Query("UPDATE downloads SET queuePosition = :position WHERE id = :id")
    suspend fun setQueuePosition(id: Long, position: Long)

    @Query("UPDATE downloads SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: Long, status: DownloadStatus, now: Long)

    @Query(
        """UPDATE downloads
           SET progressPercent = :progress, downloadedBytes = :downloadedBytes,
               totalBytes = :totalBytes, speedBytesPerSec = :speedBps, etaSeconds = :etaSeconds,
               errorMessage = NULL, updatedAt = :now
           WHERE id = :id AND status IN ('FETCHING_INFO', 'DOWNLOADING')"""
    )
    /**
     * A progress tick also clears an "automatic retry" message: data is flowing again.
     * Only a job that is still fetching/downloading accepts progress: a late write from a process
     * that was just cancelled, paused or failed can never change a stopped job's numbers.
     */
    suspend fun updateProgress(
        id: Long,
        progress: Float,
        downloadedBytes: Long,
        totalBytes: Long?,
        speedBps: Long,
        etaSeconds: Long?,
        now: Long,
    )

    @Query(
        """UPDATE downloads
           SET status = :status, errorMessage = :error, retryCount = retryCount + 1,
               speedBytesPerSec = 0, etaSeconds = NULL, updatedAt = :now
           WHERE id = :id"""
    )
    suspend fun markFailed(id: Long, status: DownloadStatus, error: String?, now: Long)

    @Query(
        """UPDATE downloads
           SET status = :status, completedAt = :completedAt, fileUri = :fileUri,
               progressPercent = 100, totalBytes = COALESCE(:sizeBytes, totalBytes),
               downloadedBytes = COALESCE(:sizeBytes, downloadedBytes), errorMessage = NULL,
               speedBytesPerSec = 0, etaSeconds = NULL, updatedAt = :now
           WHERE id = :id"""
    )
    suspend fun markCompleted(id: Long, status: DownloadStatus, completedAt: Long, fileUri: String?, sizeBytes: Long?, now: Long)

    /** A job is (re)starting: clear any error left over from a previous attempt. */
    @Query("UPDATE downloads SET status = :status, errorMessage = NULL, updatedAt = :now WHERE id = :id")
    suspend fun markStarted(id: Long, status: DownloadStatus, now: Long)

    @Query("UPDATE downloads SET errorMessage = :message, speedBytesPerSec = 0, etaSeconds = NULL, updatedAt = :now WHERE id = :id")
    suspend fun setErrorMessage(id: Long, message: String?, now: Long)

    /** State change that also clears the live transfer numbers (used for PAUSED/CANCELLED/QUEUED). */
    @Query("UPDATE downloads SET status = :status, speedBytesPerSec = 0, etaSeconds = NULL, updatedAt = :now WHERE id = :id")
    suspend fun setStatusAndClearSpeed(id: Long, status: DownloadStatus, now: Long)

    /** Atomically moves [id] from [from] to [to]; returns 1 if it happened (guards against races with cancel/pause). */
    @Query("UPDATE downloads SET status = :to, updatedAt = :now WHERE id = :id AND status = :from")
    suspend fun transition(id: Long, from: DownloadStatus, to: DownloadStatus, now: Long): Int

    /** No data for a while: stop showing a transfer speed / ETA that is no longer true. */
    @Query("UPDATE downloads SET speedBytesPerSec = 0, etaSeconds = NULL WHERE id = :id AND status = 'DOWNLOADING'")
    suspend fun clearSpeed(id: Long)

    /** The quality really delivered (e.g. "360p" when 720p wasn't obtainable). */
    @Query("UPDATE downloads SET resolutionLabel = :label WHERE id = :id")
    suspend fun setResolutionLabel(id: Long, label: String?)

    @Query("SELECT * FROM downloads WHERE batchId = :batchId")
    suspend fun getBatch(batchId: String): List<DownloadEntity>

    @Query(
        """UPDATE downloads
           SET status = :status, retryCount = 0, errorMessage = NULL, progressPercent = 0,
               downloadedBytes = 0, totalBytes = NULL, speedBytesPerSec = 0, etaSeconds = NULL, updatedAt = :now
           WHERE id = :id"""
    )
    suspend fun retry(id: Long, status: DownloadStatus, now: Long)
}
