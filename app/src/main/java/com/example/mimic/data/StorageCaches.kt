package com.example.mimic.data

import android.content.Context
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Temporary caches vs. permanent data (MImic 4.3, Settings → Library & Storage).
 *
 * "Clear" only empties caches MImic can rebuild by itself: the per-track artwork extracted from
 * phone files and the downloaded cover/station-logo images. Offline downloads, music files,
 * playlists and settings live elsewhere and are never touched.
 */
class StorageCaches(private val context: Context) {
    data class Usage(val artworkBytes: Long, val imageBytes: Long, val downloadsBytes: Long) {
        val clearableBytes get() = artworkBytes + imageBytes
    }

    private fun trackArtDir() = File(context.cacheDir, "trackart")

    @OptIn(ExperimentalCoilApi::class)
    suspend fun usage(): Usage = withContext(Dispatchers.IO) {
        Usage(
            artworkBytes = sizeOf(trackArtDir()),
            imageBytes = context.imageLoader.diskCache?.size ?: 0L,
            downloadsBytes = sizeOf(File(context.filesDir, "downloads")),
        )
    }

    @OptIn(ExperimentalCoilApi::class)
    suspend fun clear() = withContext(Dispatchers.IO) {
        trackArtDir().listFiles()?.forEach { it.delete() }
        context.imageLoader.diskCache?.clear()
        context.imageLoader.memoryCache?.clear()
    }

    companion object {
        fun sizeOf(f: File): Long = when {
            f.isFile -> f.length()
            f.isDirectory -> f.listFiles()?.sumOf { sizeOf(it) } ?: 0L
            else -> 0L
        }

        fun format(bytes: Long): String = when {
            bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
            bytes >= 1L shl 10 -> String.format(java.util.Locale.US, "%d KB", bytes shr 10)
            else -> "$bytes B"
        }
    }
}
