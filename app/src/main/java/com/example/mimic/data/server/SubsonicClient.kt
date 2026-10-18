package com.example.mimic.data.server

import com.example.mimic.data.MetadataFallback
import com.example.mimic.data.Song
import com.example.mimic.data.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
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

    /** "user@server" — scopes downloads and pending favorite syncs to the account that made them. */
    val accountKey: String = accountKeyOf(creds)

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
                        name = MetadataFallback.album(a.optString("name")),
                        artist = MetadataFallback.artist(a.optString("artist")),
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
        buildList { if (arr != null) for (i in 0 until arr.length()) add(parseSong(arr.getJSONObject(i))) }
    }

    /**
     * The whole song catalog's *metadata* (no audio), paged through search3 with an empty query —
     * the documented OpenSubsonic way to enumerate songs, supported by Navidrome. Used for the
     * Songs / Albums / Artists views; audio is only fetched when the user plays or downloads.
     */
    suspend fun allSongs(pageSize: Int = 500, maxSongs: Int = 20_000): Result<List<RemoteSong>> = io {
        val out = ArrayList<RemoteSong>()
        var offset = 0
        while (offset < maxSongs) {
            val arr = call(
                "search3",
                mapOf(
                    "query" to "", "songCount" to "$pageSize", "songOffset" to "$offset",
                    "artistCount" to "0", "albumCount" to "0",
                ),
            ).optJSONObject("searchResult3")?.optJSONArray("song")
            val n = arr?.length() ?: 0
            for (i in 0 until n) out += parseSong(arr!!.getJSONObject(i))
            if (n < pageSize) break
            offset += pageSize
        }
        out.distinctBy { it.id }
    }

    /** Server-side favorites (Subsonic "starred") — song ids only. */
    suspend fun starredSongIds(): Result<List<String>> = io {
        val arr = call("getStarred2").optJSONObject("starred2")?.optJSONArray("song")
        buildList { if (arr != null) for (i in 0 until arr.length()) add(arr.getJSONObject(i).getString("id")) }
    }

    suspend fun star(songId: String): Result<Unit> = io { call("star", mapOf("id" to songId)); Unit }
    suspend fun unstar(songId: String): Result<Unit> = io { call("unstar", mapOf("id" to songId)); Unit }

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
        dateAddedSec = remote.createdSec,
        folder = "Server",
        genre = remote.genre,
        source = SongSource.SERVER,
        remoteId = remote.id,
        albumKey = serverAlbumKey(remote.albumId, remote.album),
        artistKey = MetadataFallback.artistKey(remote.artist),
    )

    /**
     * Downloads a track's original file to [target] atomically: bytes go to a ".part" file, the
     * size is checked against Content-Length when the server sends one, and only a complete,
     * non-empty, readable file is renamed into place. A failed or interrupted download never
     * leaves a half-written file where a finished one is expected.
     */
    suspend fun download(remote: RemoteSong, target: File): Result<Unit> = io {
        val part = File(target.parentFile, target.name + ".part")
        try {
            val resp = http.newCall(Request.Builder().url(downloadUrl(remote.id)).build()).execute()
            resp.use {
                if (!it.isSuccessful) error("HTTP ${it.code}")
                val body = it.body ?: error("Empty body")
                // Navidrome reports a missing file as 200 + a JSON error document, not audio.
                val type = body.contentType()?.toString().orEmpty()
                if (type.contains("json") || type.contains("xml")) error("Server returned an error instead of audio")
                target.parentFile?.mkdirs()
                part.outputStream().use { out ->
                    body.byteStream().copyTo(out)
                    out.fd.sync()
                }
                val expected = body.contentLength()
                if (expected > 0 && part.length() != expected) {
                    throw IOException("Incomplete download: ${part.length()} of $expected bytes")
                }
            }
            if (part.length() == 0L || !part.canRead()) throw IOException("Downloaded file is empty or unreadable")
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("Could not finalize download")
        } finally {
            part.delete()
        }
        Unit
    }

    /** Saves the cover image locally so downloaded songs keep their artwork offline. */
    suspend fun downloadCover(coverArtId: String, target: File): Result<Unit> = io {
        val url = coverArtUrl(coverArtId) ?: error("No cover")
        http.newCall(Request.Builder().url(url).build()).execute().use {
            if (!it.isSuccessful) error("HTTP ${it.code}")
            val body = it.body ?: error("Empty body")
            if (body.contentType()?.type != "image") error("Not an image")
            target.parentFile?.mkdirs()
            target.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        Unit
    }

    private fun parseSong(s: JSONObject): RemoteSong = RemoteSong(
        id = s.getString("id"),
        title = MetadataFallback.title(s.optString("title"), s.optString("path").ifBlank { null }),
        artist = MetadataFallback.artist(s.optString("artist")),
        album = MetadataFallback.album(s.optString("album")),
        durationMs = s.optLong("duration", 0L) * 1000L,
        coverArtId = s.optString("coverArt").ifBlank { null },
        suffix = s.optString("suffix").ifBlank { null },
        albumId = s.optString("albumId").ifBlank { null },
        artistId = s.optString("artistId").ifBlank { null },
        createdSec = parseIsoSeconds(s.optString("created")),
        genre = s.optString("genre").ifBlank { null },
    )

    private suspend inline fun <T> io(crossinline block: () -> T): Result<T> =
        withContext(Dispatchers.IO) { runCatching { block() } }

    companion object {
        fun accountKeyOf(creds: ServerCredentials): String =
            creds.username.trim() + "@" + SubsonicAuth.normalizeBaseUrl(creds.baseUrl).lowercase()

        /** Server albums are grouped by the server's album id (never by title alone). */
        fun serverAlbumKey(albumId: String?, albumName: String): String =
            if (albumId != null) "server:album:$albumId" else "server:album-name:" + MetadataFallback.album(albumName).lowercase()

        /** "2024-05-01T12:00:00.123Z" -> epoch seconds; 0 when absent or unparseable. */
        fun parseIsoSeconds(value: String?): Long {
            if (value.isNullOrBlank()) return 0L
            return runCatching {
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                fmt.parse(value.take(19))!!.time / 1000L
            }.getOrDefault(0L)
        }
    }
}
