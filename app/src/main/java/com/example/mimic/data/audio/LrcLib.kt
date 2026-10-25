package com.example.mimic.data.audio

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Lyrics from LRCLIB (https://lrclib.net), a free community lyrics database with an open API and
 * no key (MImic 4.4). Used only when a song has no lyrics of its own (imported .lrc / embedded).
 *
 * Matching is strict on purpose — a wrong song's lyrics are worse than none: the candidate's
 * title and artist must match and its duration must be within [TOLERANCE_S] seconds of the
 * track. Synced (LRC) lyrics are preferred; plain lyrics are returned as plain, never fake-synced.
 * The request carries only title / artist / album / duration — nothing about the user.
 */
object LrcLib {
    private const val TOLERANCE_S = 4
    private const val UA = "MImic (https://github.com/enoluca22-dota2/MImic)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .build()

    data class Candidate(val track: String, val artist: String, val durationS: Double, val synced: String?, val plain: String?)

    /** Best LRC text (synced preferred, else plain) for the song, or null when nothing matches. */
    fun find(title: String, artist: String, durationMs: Long): String? {
        if (title.isBlank()) return null
        val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .apply { if (artist.isNotBlank() && !artist.startsWith("Unknown", true)) addQueryParameter("artist_name", artist) }
            .build()
        val body = client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            r.body?.string() ?: return null
        }
        return pick(parse(body), title, artist, durationMs)
    }

    fun parse(json: String): List<Candidate> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optBoolean("instrumental")) return@mapNotNull null
            Candidate(
                o.optString("trackName"), o.optString("artistName"), o.optDouble("duration", 0.0),
                o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" },
                o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }

    /** Pure: the matching rule, unit-tested. */
    fun pick(candidates: List<Candidate>, title: String, artist: String, durationMs: Long): String? {
        val t = norm(title)
        val a = norm(artist)
        val ok = candidates.filter { c ->
            val ct = norm(c.track)
            val titleOk = ct == t || ct.startsWith(t) || t.startsWith(ct)
            val artistOk = a.isEmpty() || a.startsWith("unknown") || norm(c.artist).let { it.contains(a) || a.contains(it) }
            val durOk = durationMs <= 0 || abs(c.durationS - durationMs / 1000.0) <= TOLERANCE_S
            titleOk && artistOk && durOk && (c.synced != null || c.plain != null)
        }
        val best = ok.firstOrNull { it.synced != null } ?: ok.firstOrNull() ?: return null
        return best.synced ?: best.plain
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
}
