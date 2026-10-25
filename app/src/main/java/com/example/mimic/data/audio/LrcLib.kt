package com.example.mimic.data.audio

import com.example.mimic.data.lyrics.LyricsCandidate
import com.example.mimic.data.lyrics.LyricsQuery
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Lyrics from LRCLIB (https://lrclib.net), a free community lyrics database with an open API and
 * no key. Requests carry only title / artist / album / duration and MImic's User-Agent (LRCLIB asks
 * clients to identify themselves) — nothing about the user.
 *
 * 4.5 rewrite. Measured on the live API on 2026-10-25, 4.4 lost lyrics in two ways:
 *  - it searched with the raw title/artist; YouTube-upload metadata ("Justin Timberlake - Mirrors"
 *    by "JustinTimberlake") returns zero results — the caller now passes cleaned [LyricsQuery]s;
 *  - LRCLIB answered 503 to a large share of requests, and 4.4 turned every non-200 into "no
 *    lyrics found", with no retry. Now 5xx / 429 / I/O errors are retried a bounded number of
 *    times and, if they persist, reported as [Response.Failed] so the app says "couldn't reach"
 *    and tries again later instead of caching a false "not found".
 *
 * Matching is not done here; [com.example.mimic.data.lyrics.LyricsMatcher] decides.
 */
object LrcLib {
    const val NAME = "LRCLIB"
    private const val UA = "MImic (https://github.com/enoluca22-dota2/MImic)"

    /** Overridable for tests (MockWebServer). */
    @Volatile var baseUrl: String = "https://lrclib.net"
    /** Waits before the 2nd and 3rd attempt; overridable for tests. */
    @Volatile var backoffMs: LongArray = longArrayOf(1_200, 3_000)
    /** Minimum spacing between requests — LRCLIB is a volunteer service; batch scans respect it. */
    @Volatile var minIntervalMs: Long = 350

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .build()

    enum class Reason { NETWORK, UNAVAILABLE, RATE_LIMITED }

    sealed interface Response {
        data class Ok(val candidates: List<LyricsCandidate>) : Response
        data class Failed(val reason: Reason, val httpCode: Int? = null) : Response
    }

    /** Exact lookup by signature (LRCLIB's recommended call). 404 = no such record = empty Ok. */
    fun get(q: LyricsQuery): Response {
        if (q.title.isBlank() || !q.artistKnown || q.durationMs <= 0) return Response.Ok(emptyList())
        val url = "$baseUrl/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", q.title)
            .addQueryParameter("artist_name", q.artist)
            .apply { if (q.album.isNotBlank()) addQueryParameter("album_name", q.album) }
            .addQueryParameter("duration", (q.durationMs / 1000).toString())
            .build().toString()
        return when (val r = call(url)) {
            is Raw.Body -> Response.Ok(listOfNotNull(runCatching { candidate(JSONObject(r.text)) }.getOrNull()))
            Raw.NotFound -> Response.Ok(emptyList())
            is Raw.Fail -> Response.Failed(r.reason, r.code)
        }
    }

    /** Field search on title (+ artist when known). */
    fun search(q: LyricsQuery): Response {
        if (q.title.isBlank()) return Response.Ok(emptyList())
        val url = "$baseUrl/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", q.title)
            .apply { if (q.artistKnown) addQueryParameter("artist_name", q.artist) }
            .build().toString()
        return searchCall(url)
    }

    /** Free-text search ("artist title"), for spellings the field search misses. */
    fun searchText(text: String): Response {
        if (text.isBlank()) return Response.Ok(emptyList())
        val url = "$baseUrl/api/search".toHttpUrl().newBuilder().addQueryParameter("q", text).build().toString()
        return searchCall(url)
    }

    private fun searchCall(url: String): Response = when (val r = call(url)) {
        is Raw.Body -> Response.Ok(parse(r.text))
        Raw.NotFound -> Response.Ok(emptyList())
        is Raw.Fail -> Response.Failed(r.reason, r.code)
    }

    fun parse(json: String): List<LyricsCandidate> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { runCatching { candidate(it) }.getOrNull() } }
    }

    private fun candidate(o: JSONObject): LyricsCandidate = LyricsCandidate(
        provider = NAME,
        providerId = o.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" },
        title = o.optString("trackName"),
        artist = o.optString("artistName"),
        album = o.optString("albumName").takeIf { it != "null" }.orEmpty(),
        durationMs = (o.optDouble("duration", 0.0).takeIf { !it.isNaN() } ?: 0.0).times(1000).toLong(),
        synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" },
        plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" },
        instrumental = o.optBoolean("instrumental"),
    )

    private sealed interface Raw {
        data class Body(val text: String) : Raw
        data object NotFound : Raw
        data class Fail(val reason: Reason, val code: Int?) : Raw
    }

    private val gate = Any()
    private var lastRequestAt = 0L

    private fun call(url: String): Raw {
        var last: Raw.Fail = Raw.Fail(Reason.NETWORK, null)
        for (attempt in 0..backoffMs.size) {
            if (attempt > 0) sleep(backoffMs[attempt - 1])
            pace()
            val outcome: Raw = try {
                client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
                    when {
                        r.isSuccessful -> Raw.Body(r.body?.string().orEmpty())
                        r.code == 404 -> Raw.NotFound
                        r.code == 429 -> {
                            // Honour a short Retry-After; a long one means "not now" — give up for now.
                            val wait = r.header("Retry-After")?.toLongOrNull()
                            if (wait != null && wait in 1..10) sleep(wait * 1000)
                            Raw.Fail(Reason.RATE_LIMITED, 429)
                        }
                        r.code >= 500 -> Raw.Fail(Reason.UNAVAILABLE, r.code)
                        // Other 4xx won't get better by retrying.
                        else -> return Raw.Fail(Reason.UNAVAILABLE, r.code)
                    }
                }
            } catch (e: IOException) {
                Raw.Fail(Reason.NETWORK, null)
            }
            if (outcome !is Raw.Fail) return outcome
            last = outcome
        }
        return last
    }

    private fun pace() {
        synchronized(gate) {
            val wait = lastRequestAt + minIntervalMs - System.currentTimeMillis()
            if (wait > 0) sleep(wait)
            lastRequestAt = System.currentTimeMillis()
        }
    }

    private fun sleep(ms: Long) {
        if (ms > 0) try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }
}
