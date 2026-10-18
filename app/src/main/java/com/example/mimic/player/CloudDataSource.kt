package com.example.mimic.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.example.mimic.data.cloud.CloudException
import com.example.mimic.data.cloud.CloudLibraryRepository
import com.example.mimic.data.cloud.CloudRef
import com.example.mimic.data.cloud.onedrive.OneDriveMusicSource
import okhttp3.OkHttpClient

/**
 * Lets the ONE Media3 player (PlaybackService) play `mimic-cloud://` songs: every other URI goes
 * to the normal DefaultDataSource unchanged.
 */
@OptIn(UnstableApi::class)
class MimicDataSourceFactory(
    private val context: Context,
    private val cloud: CloudLibraryRepository,
    private val okHttp: OkHttpClient,
) : DataSource.Factory {
    private val defaults = DefaultDataSource.Factory(context)
    override fun createDataSource(): DataSource =
        RoutingDataSource(defaults.createDataSource(), CloudDataSource(cloud, okHttp))
}

@OptIn(UnstableApi::class)
private class RoutingDataSource(private val normal: DataSource, private val cloud: DataSource) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        normal.addTransferListener(transferListener)
        cloud.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val ds = if (dataSpec.uri.scheme == CloudRef.SCHEME) cloud else normal
        active = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active!!.read(buffer, offset, length)
    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
    override fun close() {
        try { active?.close() } finally { active = null }
    }
}

/**
 * Streams a cloud file with HTTP Range requests (so seeking jumps straight to the right bytes and
 * nothing is downloaded up front). The `mimic-cloud://` URI is resolved at open time: Google Drive
 * gets a bearer header; OneDrive gets a fresh pre-authenticated URL. A 401/403/404/410 (expired
 * URL or token) is re-resolved once. [getUri] reports the `mimic-cloud://` URI and errors are
 * rebuilt without the real URL, so no token or temporary link reaches logs or the UI.
 */
@OptIn(UnstableApi::class)
private class CloudDataSource(
    private val cloud: CloudLibraryRepository,
    private val okHttp: OkHttpClient,
) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var upstream: HttpDataSource? = null
    private var openSpec: DataSpec? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val ref = CloudRef.fromPlayUri(dataSpec.uri.toString())
            ?: throw HttpDataSource.HttpDataSourceException("Bad cloud URI", dataSpec, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        val account = cloud.accountBlocking(ref.accountKey)
            ?: throw HttpDataSource.HttpDataSourceException("Cloud account disconnected", dataSpec, PlaybackException.ERROR_CODE_IO_NO_PERMISSION, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        val source = cloud.source(ref.provider)
        openSpec = dataSpec
        var force = false
        for (attempt in 0..1) {
            val target = try {
                source.resolveStream(account, ref.itemId, force)
            } catch (e: CloudException.AuthRequired) {
                throw HttpDataSource.HttpDataSourceException("Sign in again", dataSpec, PlaybackException.ERROR_CODE_IO_NO_PERMISSION, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
            } catch (e: CloudException.Offline) {
                throw HttpDataSource.HttpDataSourceException("Offline", dataSpec, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
            } catch (e: CloudException) {
                val code = (e as? CloudException.Http)?.code
                throw HttpDataSource.HttpDataSourceException(
                    "Cloud error", dataSpec,
                    if (code == 404) PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND else PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                )
            }
            val http = OkHttpDataSource.Factory(okHttp).setDefaultRequestProperties(target.headers).createDataSource()
            listeners.forEach(http::addTransferListener)
            try {
                val n = http.open(dataSpec.buildUpon().setUri(Uri.parse(target.url)).build())
                upstream = http
                return n
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                runCatching { http.close() }
                if (attempt == 0 && e.responseCode in setOf(401, 403, 404, 410)) {
                    force = true
                    (source as? OneDriveMusicSource)?.invalidate(account, ref.itemId)
                    continue
                }
                // Same error class, but described against the mimic-cloud:// spec (no real URL).
                throw HttpDataSource.InvalidResponseCodeException(e.responseCode, null, null, emptyMap(), dataSpec, ByteArray(0))
            } catch (e: HttpDataSource.HttpDataSourceException) {
                runCatching { http.close() }
                throw HttpDataSource.HttpDataSourceException("Cloud stream failed", dataSpec, e.reason, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
            }
        }
        throw HttpDataSource.HttpDataSourceException("Cloud stream failed", dataSpec, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val up = upstream ?: return C.RESULT_END_OF_INPUT
        return try {
            up.read(buffer, offset, length)
        } catch (e: HttpDataSource.HttpDataSourceException) {
            throw HttpDataSource.HttpDataSourceException("Cloud stream interrupted", openSpec ?: e.dataSpec, e.reason, HttpDataSource.HttpDataSourceException.TYPE_READ)
        }
    }

    override fun getUri(): Uri? = openSpec?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun close() {
        try { upstream?.close() } finally { upstream = null; openSpec = null }
    }
}
