package com.example.mimic.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import androidx.palette.graphics.Palette
import com.example.mimic.ui.designsystem.ThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Derives a dark [ThemeColors] from a song's album art, off the main thread. Returns null
 * when no artwork/colors are available so callers can fall back to a static theme.
 */
object AdaptivePalette {

    suspend fun fromSong(context: Context, song: Song): ThemeColors? = withContext(Dispatchers.Default) {
        val bitmap = loadArtwork(context, song) ?: return@withContext null
        val palette = runCatching { Palette.from(bitmap).clearFilters().generate() }.getOrNull()
            ?: return@withContext null

        val primaryRgb = (palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.dominantSwatch)
            ?.rgb ?: return@withContext null
        val secondaryRgb = (palette.lightMutedSwatch ?: palette.mutedSwatch ?: palette.dominantSwatch)
            ?.rgb ?: primaryRgb
        val baseRgb = (palette.darkMutedSwatch ?: palette.dominantSwatch)?.rgb ?: 0xFF202020.toInt()

        val background = darken(baseRgb, 0.78f)
        ThemeColors(
            primary = toArgbLong(primaryRgb),
            secondary = toArgbLong(secondaryRgb),
            background = toArgbLong(background),
            surface = toArgbLong(lighten(background, 0.06f)),
            surfaceElevated = toArgbLong(lighten(background, 0.12f)),
            textPrimary = 0xFFFFFFFF,
            textSecondary = 0xFFC4C4C4,
            isDark = true,
        )
    }

    private fun loadArtwork(context: Context, song: Song): Bitmap? {
        // Prefer the modern thumbnail API (API 29+); fall back to the album-art uri.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            runCatching {
                return context.contentResolver.loadThumbnail(Uri.parse(song.uri), Size(320, 320), null)
            }
        }
        val art = song.albumArtUri ?: return null
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(art))?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }

    // ---- color math on 0xAARRGGBB Ints ----

    private fun toArgbLong(rgb: Int): Long = (0xFF000000L or (rgb.toLong() and 0xFFFFFF))

    private fun darken(rgb: Int, factor: Float): Int = scale(rgb, 1f - factor.coerceIn(0f, 1f))

    private fun lighten(rgb: Int, amount: Float): Int {
        val r = channel(rgb, 16); val g = channel(rgb, 8); val b = channel(rgb, 0)
        val a = (amount.coerceIn(0f, 1f) * 255).toInt()
        return pack((r + a).coerceAtMost(255), (g + a).coerceAtMost(255), (b + a).coerceAtMost(255))
    }

    private fun scale(rgb: Int, f: Float): Int =
        pack((channel(rgb, 16) * f).toInt(), (channel(rgb, 8) * f).toInt(), (channel(rgb, 0) * f).toInt())

    private fun channel(rgb: Int, shift: Int) = (rgb shr shift) and 0xFF
    private fun pack(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
