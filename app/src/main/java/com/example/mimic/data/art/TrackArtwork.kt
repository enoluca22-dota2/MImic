package com.example.mimic.data.art

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/**
 * Per-track artwork for songs on the phone (MImic 4.1).
 *
 * THE BUG IT FIXES: phone songs used MediaStore's *album* art (`content://media/…/albumart/<ALBUM_ID>`).
 * MediaStore gives every file WITHOUT an album tag in the same folder the same ALBUM_ID (the album
 * becomes the folder name) and extracts that album's art from one of the files — so all untagged
 * downloads in e.g. Music/MImic showed one song's cover, in lists, the player, the notification and
 * on the lock screen.
 *
 * Now every phone song has its own art key `mimic-art://track/<mediaId>?v=<dateModified>[&album=<id>]`:
 *  1. the file's OWN embedded picture (read once, cached per track + version in app storage);
 *  2. otherwise MediaStore album art — only when the song has a real album tag ([album] present);
 *  3. otherwise nothing → the UI shows a neutral placeholder.
 * The version is the file's DATE_MODIFIED, so a re-tagged file gets fresh artwork, and the key is
 * per track id, so two files can never share a cache entry. Navidrome and downloaded songs keep
 * their server cover URLs / saved cover files (already per album / track id).
 */
object TrackArtwork {
    const val SCHEME = "mimic-art"
    private const val MAX_PX = 720
    private val extractLimiter = Semaphore(3)

    data class Key(val mediaId: Long, val version: Long, val albumId: Long?)

    fun uri(mediaId: Long, dateModified: Long, albumId: Long?): String =
        "$SCHEME://track/$mediaId?v=$dateModified" + (albumId?.let { "&album=$it" } ?: "")

    /** Pure parse of [uri]; null for anything that isn't a track-art key. */
    fun parse(uri: String): Key? {
        if (!uri.startsWith("$SCHEME://track/")) return null
        val rest = uri.removePrefix("$SCHEME://track/")
        val id = rest.substringBefore('?').toLongOrNull() ?: return null
        val params = rest.substringAfter('?', "").split('&').mapNotNull { p ->
            val k = p.substringBefore('=', ""); val v = p.substringAfter('=', "")
            if (k.isEmpty()) null else k to v
        }.toMap()
        return Key(id, params["v"]?.toLongOrNull() ?: 0L, params["album"]?.toLongOrNull())
    }

    /**
     * Whether MediaStore's album for this file is a real, tagged album (its art may be shown for
     * the track) — not the folder-name album MediaStore invents for untagged files.
     */
    fun isRealAlbum(albumTag: String?, folderName: String?): Boolean {
        val a = albumTag?.trim().orEmpty()
        if (a.isEmpty() || a.equals("<unknown>", ignoreCase = true) || a.equals("unknown", ignoreCase = true)) return false
        if (folderName != null && a.equals(folderName.trim(), ignoreCase = true)) return false
        return true
    }

    /** File name of the cached artwork for [key] (pure). */
    fun cacheName(key: Key): String = "t${key.mediaId}_${key.version}.jpg"
    fun noneMarkerName(key: Key): String = "t${key.mediaId}_${key.version}.none"

    private fun cacheDir(context: Context) = File(context.cacheDir, "trackart").apply { mkdirs() }

    /**
     * Bitmap for a `mimic-art://` key, at most [targetPx] on its long side. Blocking IO: call off
     * the main thread. Null = no artwork for this track (show the placeholder).
     */
    suspend fun load(context: Context, uri: String, targetPx: Int = MAX_PX): Bitmap? {
        val key = parse(uri) ?: return null
        val dir = cacheDir(context)
        val cached = File(dir, cacheName(key))
        if (cached.isFile) return decodeFile(cached, targetPx)
        if (!File(dir, noneMarkerName(key)).exists()) {
            val embedded = extractLimiter.withPermit { extractEmbedded(context, key.mediaId) }
            if (embedded != null) {
                val bmp = decodeBytes(embedded, MAX_PX)
                if (bmp != null) {
                    runCatching {
                        val tmp = File(dir, cacheName(key) + ".part")
                        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                        tmp.renameTo(cached)
                        // Older versions of this track's art are stale now.
                        dir.listFiles { f -> f.name.startsWith("t${key.mediaId}_") && f.name != cached.name }?.forEach { it.delete() }
                    }
                    return if (targetPx >= MAX_PX) bmp else scaleDown(bmp, targetPx)
                }
            }
            runCatching { File(dir, noneMarkerName(key)).createNewFile() }
        }
        // No picture inside the file: a real album's art is still the right picture for it.
        val albumId = key.albumId ?: return null
        return runCatching {
            val albumUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
            context.contentResolver.openInputStream(albumUri)?.use { decodeBytes(it.readBytes(), targetPx) }
        }.getOrNull()
    }

    private fun extractEmbedded(context: Context, mediaId: Long): ByteArray? {
        val audio = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, audio)
            r.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun decodeFile(f: File, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx) }
        return BitmapFactory.decodeFile(f.path, opts)
    }

    private fun decodeBytes(b: ByteArray, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx) }
        return BitmapFactory.decodeByteArray(b, 0, b.size, opts)
    }

    private fun scaleDown(b: Bitmap, targetPx: Int): Bitmap {
        val long = maxOf(b.width, b.height)
        if (long <= targetPx) return b
        val f = targetPx.toFloat() / long
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    /** Largest power-of-two subsampling that keeps the long side ≥ [targetPx] (pure). */
    fun sampleSize(w: Int, h: Int, targetPx: Int): Int {
        var s = 1
        val long = maxOf(w, h)
        while (long / (s * 2) >= targetPx) s *= 2
        return s
    }
}
