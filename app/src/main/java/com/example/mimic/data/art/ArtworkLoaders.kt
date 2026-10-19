package com.example.mimic.data.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.pxOrElse
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Coil (every artwork in the UI): `mimic-art://` keys go through [TrackArtwork]. Coil's memory
 * cache keys on the full URI, which is unique per track + file version, so a cover is never
 * reused for another song.
 */
class TrackArtFetcher(private val uri: Uri, private val options: Options, private val context: Context) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val target = maxOf(options.size.width.pxOrElse { 512 }, options.size.height.pxOrElse { 512 }).coerceIn(64, 1024)
        val bmp = TrackArtwork.load(context, uri.toString(), target)
            ?: throw IOException("no artwork for this track") // → Coil error → the placeholder stays
        return DrawableResult(BitmapDrawable(context.resources, bmp), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.scheme == TrackArtwork.SCHEME) TrackArtFetcher(data, options, context.applicationContext) else null
    }
}

/**
 * Media3 (notification, lock screen, Bluetooth/Android Auto metadata): resolves `mimic-art://`
 * the same way; every other artwork URI (Navidrome covers, saved cover files) goes to Media3's
 * own loader unchanged.
 */
@UnstableApi
class TrackArtBitmapLoader(private val context: Context, private val fallback: BitmapLoader) : BitmapLoader {
    private val executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    override fun supportsMimeType(mimeType: String): Boolean = fallback.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = fallback.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        if (uri.scheme != TrackArtwork.SCHEME) fallback.loadBitmap(uri)
        else executor.submit<Bitmap> {
            runBlocking { TrackArtwork.load(context, uri.toString(), 512) } ?: throw IOException("no artwork for this track")
        }
}
