package com.example.mimic.ui.dynamic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts the artwork palette for the Dynamic player — once per artwork, never per frame. The
 * artwork is loaded small (160 px, software bitmap) through the same image loader as the UI, so
 * a phone song uses ITS OWN artwork (per-track key) and a Navidrome song its server cover.
 */
object DynamicPalette {
    private val cache = LruCache<String, DynamicColorMath.Swatches>(64)

    fun cached(artUri: String?): DynamicColorMath.Swatches? = artUri?.let { cache.get(it) }

    suspend fun swatches(context: Context, artUri: String?): DynamicColorMath.Swatches? {
        if (artUri == null) return null
        cache.get(artUri)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context).data(artUri).size(160).allowHardware(false).build()
            ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
        } ?: return null
        val s = withContext(Dispatchers.Default) { fromBitmap(bmp) } ?: return null
        cache.put(artUri, s)
        return s
    }

    private fun fromBitmap(bmp: Bitmap): DynamicColorMath.Swatches? {
        val p = Palette.from(bmp).maximumColorCount(16).generate()
        val dominant = p.dominantSwatch?.rgb ?: return null
        val vibrant = (p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.mutedSwatch)?.rgb ?: dominant
        val darkMuted = (p.darkMutedSwatch ?: p.darkVibrantSwatch)?.rgb ?: DynamicColorMath.mix(dominant, 0xFF000000.toInt(), 0.6f)
        return DynamicColorMath.Swatches(dominant, vibrant, darkMuted)
    }
}
