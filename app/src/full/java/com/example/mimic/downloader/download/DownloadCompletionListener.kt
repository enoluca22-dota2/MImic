package com.example.mimic.downloader.download

import com.example.mimic.downloader.data.local.db.DownloadEntity

/** A file the engine has just published successfully (never a failed or cancelled job). */
data class CompletedDownload(
    val entity: DownloadEntity,
    val fileUri: String,
    val sizeBytes: Long?,
    val extension: String,
    val mimeType: String,
    /** Where it was saved, as shown to the user ("Music/ENAGELYUCA"). */
    val location: String?,
)

/**
 * Told about every completed download (the Library indexes it). The engine only knows this
 * interface; a listener that fails never affects the download itself.
 */
fun interface DownloadCompletionListener {
    suspend fun onDownloadCompleted(download: CompletedDownload)
}
