package com.example.mimic.downloader.data.repository

import android.content.Context
import com.example.mimic.downloader.core.FileActions
import com.example.mimic.downloader.data.local.db.HistoryDao
import com.example.mimic.downloader.data.local.db.HistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class HistoryRepository(
    private val appContext: Context,
    private val historyDao: HistoryDao,
) {
    fun observeAll(): Flow<List<HistoryEntity>> = historyDao.observeAll()
    fun observeRecent(limit: Int): Flow<List<HistoryEntity>> = historyDao.observeRecent(limit)
    suspend fun clearAll() = historyDao.clearAll()
    suspend fun delete(id: Long) = historyDao.deleteById(id)

    /**
     * Duplicate detection: the most recent earlier download of the same media whose file still
     * exists. Matched on the extractor's canonical page URL (plus the URL as entered), which
     * survives different link forms (youtu.be vs youtube.com/watch, tracking parameters…) far
     * better than comparing file names.
     */
    suspend fun findExistingDownload(vararg urls: String?): HistoryEntity? = withContext(Dispatchers.IO) {
        val candidates = urls.filterNotNull().filter { it.isNotBlank() }.distinct()
        if (candidates.isEmpty()) return@withContext null
        historyDao.findByUrls(candidates).firstOrNull { entry ->
            entry.fileUri != null && FileActions.exists(appContext, entry.fileUri)
        }
    }
}
