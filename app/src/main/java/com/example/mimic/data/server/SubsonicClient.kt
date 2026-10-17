package com.example.mimic.data.server

import com.example.mimic.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Minimal Subsonic/OpenSubsonic client over OkHttp. All calls are suspend + IO-dispatched.
 * Network results are untrusted input — callers handle Result failures.
 */
class SubsonicClient(private val creds: ServerCredentials) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // One salt per client instance (i.e. per session), not per call. Salted-token auth stays
    // secure either way (the password is never derivable from salt+token), but a STABLE salt
    // makes stream/cover URLs deterministic. Previously a fresh random salt per call produced a
    // different cover-art URL on every recomposition, defeating Coil's cache and re-downloading
    // every image on each scroll/tick — invisible on localhost, a network storm over Tailscale
    // that janked scrolling and starved the audio stream. See MImic 2.8.1.
    private val salt: String = SubsonicAuth.randomSalt()

    private fun url(endpoint: String, extra: Map<String, String> = emptyMap()): String =
        SubsonicAuth.buildUrl(
            creds.baseUrl,
            endpoint,
            SubsonicAuth.authParams(creds.username, creds.password, salt),
            extra,
        )

    private fun call(endpoint: String, extra: Map<String, String> = emptyMap()): JSONObject {
        val resp = http.newCall(Request.Builder().url(url(endpoint, extra)).build()).execute()
        resp.use {
            val body = it.body?.string() ?: error("Empty response")
            if (!it.isSuccessful) error("HTTP ${it.code}")
            val root = JSONObject(body).getJSONObject("subsonic-response")
            if (root.optString("status") != "ok") {
                val msg = root.optJSONObject("error")?.optString("message") ?: "Server error"
                error(msg)
            }
            return root
        }
    }

    /** Verifies the server is reachable and credentials are valid. */
    suspend fun ping(): Result<Unit> = io { call("ping"); Unit }

    suspend fun albums(size: Int = 200, offset: Int = 0): Result<List<RemoteAlbum>> = io {
        val arr = call(
            "getAlbumList2",
            mapOf("type" to "alphabeticalByName", "size" to "$size", "offset" to "$offset"),
        ).optJSONObject("albumList2")?.optJSONArray("album")
        buildList {
            if (arr != null) for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                add(
                    RemoteAlbum(
                        id = a.getString("id"),
                        name = a.optString("name", "Unknown album"),
                        artist = a.optString("artist", "Unknown artist"),
                        coverArtId = a.optString("coverArt").ifBlank { null },
                        songCount = a.optInt("songCount", 0),
                    ),
                )
            }
        }
    }

    suspend fun albumSongs(albumId: String): Result<List<RemoteSong>> = io {
        val arr = call("getAlbum", mapOf("id" to albumId))
            .optJSONObject("album")?.optJSONArray("song")
        buildList {
            if (arr != null) for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                add(
                    RemoteSong(
                        id = s.getString("id"),
                        title = s.optString("title", "Unknown title"),
                        artist = s.optString("artist", "Unknown artist"),
                        album = s.optString("album", "Unknown album"),
                        durationMs = s.optLong("duration", 0L) * 1000L,
                        coverArtId = s.optString("coverArt").ifBlank { null },
                        suffix = s.optString("suffix").ifBlank { null },
                    ),
                )
            }
        }
    }

    fun streamUrl(songId: String): String = url("stream", mapOf("id" to songId))
    fun downloadUrl(songId: String): String = url("download", mapOf("id" to songId))
    fun coverArtUrl(coverArtId: String?): String? =
        coverArtId?.let { url("getCoverArt", mapOf("id" to it, "size" to "512")) }

    /** A remote track as a playable [Song] whose uri streams from the server. */
    fun toPlayableSong(remote: RemoteSong): Song = Song(
        id = remoteStableId(remote.id),
        title = remote.title,
        artist = remote.artist,
        album = remote.album,
        durationMs = remote.durationMs,
        uri = streamUrl(remote.id),
        albumArtUri = coverArtUrl(remote.coverArtId),
        dateAddedSec = 0L,
        folder = "Server",
        genre = null,
    )

    /** Downloads a track's original file to [target]. */
    suspend fun download(remote: RemoteSong, target: File): Result<Unit> = io {
        val resp = http.newCall(Request.Builder().url(downloadUrl(remote.id)).build()).execute()
        resp.use {
            if (!it.isSuccessful) error("HTTP ${it.code}")
            val sink = it.body ?: error("Empty body")
            target.parentFile?.mkdirs()
            target.outputStream().use { out -> sink.byteStream().copyTo(out) }
        }
        Unit
    }

    private suspend inline fun <T> io(crossinline block: () -> T): Result<T> =
        withContext(Dispatchers.IO) { runCatching { block() } }
}
