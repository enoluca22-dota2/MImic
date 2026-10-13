package com.example.mimic.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.Size
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts a single *seed* color from a song's album art, off the main thread. The seed is turned
 * into a contrast-safe scheme by [com.example.mimic.ui.designsystem.AlbumColors]. Returns null
 * when no artwork/colors are available so callers fall back to the theme's own colors.
 *
 * Artwork goes through Coil's shared ImageLoader with the song's stable art URL (2.8.1 fix), so a
 * cover already shown in the list is served from Coil's memory/disk cache — never re-downloaded.
 * It is decoded at a small size (palette quality does not need more) and without hardware
 * bitmaps (Palette must read pixels).
 */
object AdaptivePalette {

    suspend fun seedFromSong(context: Context, song: Song): Int? = withContext(Dispatchers.Default) {
        val bitmap = loadArtwork(context, song) ?: return@withContext null
        val palette = runCatching { Palette.from(bitmap).clearFilters().generate() }.getOrNull()
            ?: return@withContext null
        (palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch)?.rgb
    }

    private suspend fun loadArtwork(context: Context, song: Song): Bitmap? {
        song.albumArtUri?.let { art ->
            val request = ImageRequest.Builder(context)
                .data(art)
                .size(160)
                .allowHardware(false)
                .build()
            val result = runCatching { context.imageLoader.execute(request) }.getOrNull()
            ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap?.let { return it }
        }
        // Local files without an album-art uri: MediaStore's embedded-art thumbnail (API 29+).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            song.uri.startsWith("content://")
        ) {
            return runCatching {
                context.contentResolver.loadThumbnail(Uri.parse(song.uri), Size(160, 160), null)
            }.getOrNull()
        }
        return null
    }
}
