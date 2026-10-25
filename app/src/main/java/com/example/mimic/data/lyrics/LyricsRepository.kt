package com.example.mimic.data.lyrics

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.example.mimic.data.Song
import com.example.mimic.data.audio.AudioTags
import com.example.mimic.data.audio.LrcLib
import com.example.mimic.data.audio.Lyrics
import com.example.mimic.data.online.OnlineIds
import com.example.mimic.data.radio.RadioIds
import com.example.mimic.data.server.SubsonicClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** What the lyrics lookup needs to know about a song — built from a [Song] or a finished download. */
data class LyricsTarget(
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: String,
    val remoteId: String?,
) {
    val isRadio: Boolean get() = RadioIds.isRadio(songId)
    /** A Navidrome track (streamed or downloaded) — the server may hold lyrics for it. */
    val navidromeId: String? get() = remoteId?.takeIf { !OnlineIds.isOnline(it) && !com.example.mimic.data.LegacyCloudIds.isCloud(it) }

    companion object {
        fun of(song: Song) = LyricsTarget(song.id, song.title, song.artist, song.album, song.durationMs, song.uri, song.remoteId)
    }
}

sealed interface LyricsOutcome {
    data class Found(val lyrics: Lyrics) : LyricsOutcome
    /** Several plausible matches: the user picks (nothing is shown until they do). */
    data class Choose(val candidates: List<LyricsCandidate>) : LyricsOutcome
    /** Every source was asked; none has lyrics for this recording. */
    data class Unavailable(val reason: String) : LyricsOutcome
    /** The lookup couldn't finish (offline, provider down) — worth retrying. */
    data class Failed(val reason: String) : LyricsOutcome
}

/**
 * MImic's lyrics, from every place they can come from, saved for offline use (MImic 4.5).
 *
 * Order — the first that has lyrics wins:
 *  1. a .lrc the user attached ("Your .lrc file") or lyrics they chose / typed (saved, userChosen);
 *  2. lyrics saved by an earlier lookup (the `lyrics` table) — works offline;
 *  3. lyrics embedded in the audio file (ID3 USLT, Vorbis LYRICS) for phone files and downloads;
 *  4. a sidecar `<same name>.lrc` next to the file (downloads; phone files where Android lets
 *     MImic read it);
 *  5. Navidrome (`getLyricsBySongId`, then `getLyrics`) for server tracks;
 *  6. LRCLIB, matched by [LyricsMatcher] on cleaned title / artist / version / duration.
 * Found lyrics (3–6) are saved in the database; misses are recorded as UNAVAILABLE (re-checked
 * after [UNAVAILABLE_RECHECK_MS]) and failures as FAILED (re-tried on the next play). Audio files
 * are never modified.
 */
class LyricsRepository(
    private val context: Context,
    private val dao: LyricsDao,
    /** The signed-in Navidrome session, if any. */
    private val navidrome: suspend () -> SubsonicClient?,
) {
    private val dir get() = File(context.filesDir, "lyrics")
    private val locks = ConcurrentHashMap<Long, Mutex>()

    fun observe(songId: Long) = dao.observe(songId)
    fun statusCounts() = dao.statusCounts()
    suspend fun savedRows(): List<LyricsEntity> = dao.allWithoutText()

    /** The user's own .lrc for a song (Add lyrics → file), if any. */
    fun userFile(songId: Long) = File(dir, "$songId.lrc")

    suspend fun resolve(t: LyricsTarget, allowNetwork: Boolean, force: Boolean = false): LyricsOutcome =
        locks.getOrPut(t.songId) { Mutex() }.withLock { withContext(Dispatchers.IO) { resolveLocked(t, allowNetwork, force) } }

    private suspend fun resolveLocked(t0: LyricsTarget, allowNetwork: Boolean, force: Boolean): LyricsOutcome {
        if (t0.isRadio) return LyricsOutcome.Unavailable("No lyrics for live radio.")
        // The duration is what tells one recording from another; read it from the file when the
        // caller didn't know it (a fresh link download).
        val t = if (t0.durationMs <= 0) t0.copy(durationMs = probeDuration(t0.uri)) else t0
        userFile(t.songId).takeIf { it.isFile }?.let { f ->
            Lyrics.parse(runCatching { f.readText() }.getOrDefault(""), SOURCE_USER_FILE)?.let { return LyricsOutcome.Found(it) }
        }
        val key = trackKey(t)
        val row = dao.get(t.songId)?.takeIf { it.trackKey == key }
        if (row != null && row.hasLyrics) {
            Lyrics.parse(row.text!!, row.source ?: LrcLib.NAME)?.let { return LyricsOutcome.Found(it) }
        }
        if (row?.userChosen == true && !force) {
            // The user said "these lyrics" (or typed them) — don't second-guess with a lookup.
            row.text?.let { Lyrics.parse(it, row.source ?: SOURCE_USER) }?.let { return LyricsOutcome.Found(it) }
        }

        // Offline sources: never need the network, always checked (they're cheap).
        embedded(t)?.let { text -> return save(t, key, text, SOURCE_EMBEDDED) }
        sidecar(t)?.let { text -> return save(t, key, text, SOURCE_SIDECAR) }
        // 4.4 cached LRCLIB answers as files; adopt them into the table.
        File(dir, "lrclib/${t.songId}.lrc").takeIf { it.isFile }?.let { f ->
            runCatching { f.readText() }.getOrNull()?.takeIf { Lyrics.parse(it, LrcLib.NAME) != null }?.let { text ->
                return save(t, key, text, LrcLib.NAME)
            }
        }

        if (!force && row != null) {
            val age = System.currentTimeMillis() - row.updatedAt
            when (row.lyricsStatus) {
                LyricsStatus.UNAVAILABLE -> if (age < UNAVAILABLE_RECHECK_MS) return LyricsOutcome.Unavailable(NOT_FOUND)
                LyricsStatus.FAILED -> if (age < FAILED_RETRY_MS) return LyricsOutcome.Failed(UNREACHABLE)
                else -> Unit
            }
        }
        if (!allowNetwork) {
            return if (row?.lyricsStatus == LyricsStatus.UNAVAILABLE) LyricsOutcome.Unavailable(NOT_FOUND)
            else LyricsOutcome.Failed("You're offline. Lyrics will be searched when you're connected.")
        }

        // Navidrome holds lyrics from its own files' tags / .lrc sidecars on the server.
        var networkFailed = false
        t.navidromeId?.let { id ->
            val client = runCatching { navidrome() }.getOrNull()
            if (client != null) {
                val r = client.lyrics(id, t.artist, t.title)
                r.getOrNull()?.takeIf { Lyrics.parse(it, SOURCE_NAVIDROME) != null }?.let { return save(t, key, it, SOURCE_NAVIDROME) }
                if (r.isFailure) networkFailed = true
            }
        }

        return when (val m = searchLrcLib(t)) {
            is Searched.Matched -> save(t, key, m.candidate.bestText!!, LrcLib.NAME, m.candidate)
            is Searched.Ambiguous -> {
                record(t, key, LyricsStatus.NEEDS_CHOICE, row)
                LyricsOutcome.Choose(m.candidates)
            }
            Searched.NoMatch -> if (networkFailed) {
                record(t, key, LyricsStatus.FAILED, row); LyricsOutcome.Failed(UNREACHABLE)
            } else {
                record(t, key, LyricsStatus.UNAVAILABLE, row); LyricsOutcome.Unavailable(NOT_FOUND)
            }
            is Searched.Failed -> {
                record(t, key, LyricsStatus.FAILED, row)
                LyricsOutcome.Failed(if (m.reason == LrcLib.Reason.NETWORK) UNREACHABLE else "The lyrics service is busy right now. Try again in a moment.")
            }
        }
    }

    private sealed interface Searched {
        data class Matched(val candidate: LyricsCandidate) : Searched
        data class Ambiguous(val candidates: List<LyricsCandidate>) : Searched
        data object NoMatch : Searched
        data class Failed(val reason: LrcLib.Reason) : Searched
    }

    /** Exact `get`, then field search, then free-text search — over every reading of the title. */
    private fun searchLrcLib(t: LyricsTarget): Searched {
        val queries = LyricsQuery.variants(t.title, t.artist, t.album, t.durationMs)
        for (q in queries) {
            val steps = listOf({ LrcLib.get(q) }, { LrcLib.search(q) }, { LrcLib.searchText(listOf(q.artist, q.title).filter { it.isNotBlank() }.joinToString(" ")) })
            for (step in steps) {
                when (val r = step()) {
                    // Already retried inside LrcLib: the service is unreachable / busy right now, and
                    // more requests would only add to that. Report it, so it's retried later.
                    is LrcLib.Response.Failed -> return Searched.Failed(r.reason)
                    is LrcLib.Response.Ok -> when (val m = LyricsMatcher.match(q, r.candidates)) {
                        is LyricsMatcher.Match.Found -> return Searched.Matched(m.candidate)
                        is LyricsMatcher.Match.Ambiguous -> return Searched.Ambiguous(m.candidates)
                        LyricsMatcher.Match.None -> Unit
                    }
                }
            }
        }
        return Searched.NoMatch
    }

    /**
     * Lyrics the user can pick from (the "Choose lyrics" list): everything LRCLIB returns for the
     * song — or for [title] / [artist] the user typed — with text. Accepted matches come first.
     */
    suspend fun candidates(t: LyricsTarget, title: String? = null, artist: String? = null): Result<List<LyricsCandidate>> = withContext(Dispatchers.IO) {
        val q = LyricsQuery.of(title ?: t.title, artist ?: t.artist, t.album, t.durationMs)
        val all = ArrayList<LyricsCandidate>()
        var failed: LrcLib.Response.Failed? = null
        for (r in listOf(LrcLib.search(q), LrcLib.searchText(listOf(q.artist, q.title).filter { it.isNotBlank() }.joinToString(" ")))) {
            when (r) {
                is LrcLib.Response.Ok -> all += r.candidates
                is LrcLib.Response.Failed -> failed = r
            }
        }
        if (all.isEmpty() && failed != null) return@withContext Result.failure(java.io.IOException(UNREACHABLE))
        val unique = all.filter { it.hasText && !it.instrumental }.distinctBy { it.providerId ?: "${it.title}|${it.artist}|${it.durationMs}" }
        Result.success(
            unique.sortedWith(
                compareBy<LyricsCandidate>(
                    { !LyricsMatcher.accepts(q, it) },
                    { it.synced.isNullOrBlank() },
                    { if (t.durationMs > 0 && it.durationMs > 0) kotlin.math.abs(t.durationMs - it.durationMs) else Long.MAX_VALUE },
                ),
            ).take(25),
        )
    }

    /** The user picked [c] in the lyrics screen. */
    suspend fun choose(t: LyricsTarget, c: LyricsCandidate): LyricsOutcome = withContext(Dispatchers.IO) {
        save(t, trackKey(t), c.bestText ?: return@withContext LyricsOutcome.Failed("Those lyrics are empty."), c.provider, c, userChosen = true)
    }

    /** Lyrics the user typed or pasted ("Add lyrics"). LRC timestamps are honoured if present. */
    suspend fun saveUserText(t: LyricsTarget, text: String): LyricsOutcome = withContext(Dispatchers.IO) {
        if (Lyrics.parse(text, SOURCE_USER) == null) return@withContext LyricsOutcome.Failed("There's no text to save.")
        save(t, trackKey(t), text.trim(), SOURCE_USER, null, userChosen = true)
    }

    /** Forget saved lyrics and the lookup status (Search again). The user's .lrc file is kept. */
    suspend fun forget(songId: Long) = withContext(Dispatchers.IO) { dao.delete(songId) }

    /** Mark songs as queued for a background lookup, unless they already have lyrics. */
    suspend fun markPending(t: LyricsTarget) = withContext(Dispatchers.IO) {
        val key = trackKey(t)
        val row = dao.get(t.songId)?.takeIf { it.trackKey == key }
        if (row?.hasLyrics == true || row?.userChosen == true) return@withContext
        dao.upsert(LyricsEntity(t.songId, LyricsStatus.PENDING.name, key, attempts = row?.attempts ?: 0, updatedAt = System.currentTimeMillis()))
    }

    private suspend fun save(
        t: LyricsTarget, key: String, text: String, source: String, c: LyricsCandidate? = null, userChosen: Boolean = false,
    ): LyricsOutcome {
        val parsed = Lyrics.parse(text, source) ?: return LyricsOutcome.Unavailable(NOT_FOUND)
        dao.upsert(
            LyricsEntity(
                songId = t.songId,
                status = (if (parsed.synced) LyricsStatus.SYNCED else LyricsStatus.PLAIN).name,
                trackKey = key,
                text = text,
                source = source,
                providerId = c?.providerId,
                matchedTitle = c?.title,
                matchedArtist = c?.artist,
                matchedDurationMs = c?.durationMs ?: 0,
                userChosen = userChosen,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return LyricsOutcome.Found(parsed)
    }

    private suspend fun record(t: LyricsTarget, key: String, status: LyricsStatus, old: LyricsEntity?) {
        dao.upsert(LyricsEntity(t.songId, status.name, key, attempts = (old?.attempts ?: 0) + 1, updatedAt = System.currentTimeMillis()))
    }

    private fun embedded(t: LyricsTarget): String? {
        val uri = Uri.parse(t.uri)
        if (uri.scheme != "content" && uri.scheme != "file") return null
        val tags = runCatching {
            context.contentResolver.openInputStream(uri)?.use { AudioTags.parse(com.example.mimic.player.PlaybackEffects.readHead(it, AudioTags.HEAD_BYTES)) }
        }.getOrNull()
        return tags?.lyrics?.takeIf { Lyrics.parse(it, SOURCE_EMBEDDED) != null }
    }

    private fun probeDuration(uri: String): Long {
        val u = Uri.parse(uri)
        if (u.scheme != "content" && u.scheme != "file") return 0L
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(context, u)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { r.release() }
        }
    }

    /** `song.lrc` next to `song.mp3`. Readable for downloads; for phone files only where Android allows. */
    private fun sidecar(t: LyricsTarget): String? {
        val uri = Uri.parse(t.uri)
        val path = when (uri.scheme) {
            "file" -> uri.path
            "content" -> runCatching {
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
            else -> null
        } ?: return null
        val audio = File(path)
        val lrc = File(audio.parentFile ?: return null, audio.nameWithoutExtension + ".lrc")
        return runCatching { lrc.takeIf { it.isFile && it.length() in 1..512_000 }?.readText() }.getOrNull()
            ?.takeIf { Lyrics.parse(it, SOURCE_SIDECAR) != null }
    }

    companion object {
        const val SOURCE_USER_FILE = "Your .lrc file"
        const val SOURCE_USER = "Your lyrics"
        const val SOURCE_EMBEDDED = "Embedded in the file"
        const val SOURCE_SIDECAR = ".lrc file next to the song"
        const val SOURCE_NAVIDROME = "Navidrome"
        const val NOT_FOUND = "No lyrics found for this recording."
        const val UNREACHABLE = "Couldn't reach the lyrics service. Check the connection and try again."
        const val UNAVAILABLE_RECHECK_MS = 14L * 24 * 60 * 60 * 1000
        const val FAILED_RETRY_MS = 2L * 60 * 1000

        /** Identifies the recording the lyrics were matched for (title + artist, cleaned). */
        fun trackKey(t: LyricsTarget): String {
            val q = LyricsQuery.of(t.title, t.artist)
            return LyricsQuery.compact(q.title) + "|" + LyricsQuery.compact(q.artist)
        }
    }
}
