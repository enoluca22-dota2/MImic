package com.example.mimic.data.lyrics

import kotlin.math.abs

/** One set of lyrics a provider offered for a search. */
data class LyricsCandidate(
    val provider: String,
    /** The provider's own id for the record (LRCLIB id), so a user's choice can be remembered. */
    val providerId: String?,
    val title: String,
    val artist: String,
    val album: String,
    /** 0 when the provider didn't say. */
    val durationMs: Long,
    val synced: String?,
    val plain: String?,
    val instrumental: Boolean = false,
) {
    val hasText: Boolean get() = !synced.isNullOrBlank() || !plain.isNullOrBlank()
    /** Synced text when there is some, else plain — never fake-synced. */
    val bestText: String? get() = synced?.takeIf { it.isNotBlank() } ?: plain?.takeIf { it.isNotBlank() }
}

/**
 * Decides which (if any) candidate belongs to the recording being played — MImic 4.5.
 *
 * A wrong song's lyrics are worse than none, so every rule only ever *refuses*:
 *  - the cleaned titles must be equal (4.4 accepted any prefix, so "Mirrors (Live …)" passed for
 *    "Mirrors");
 *  - version markers must agree (live / remix / acoustic / instrumental / cover / sped up / slowed /
 *    demo) — a remix's words or timing are not the original's;
 *  - an artist we know must be credited on the candidate (main or featured, spaces ignored);
 *  - a duration we know must be within [DURATION_TOLERANCE_MS] (a radio edit or an 8-minute video is
 *    another recording). With an unknown artist the tolerance is tighter, because then the duration
 *    is the only thing standing between the song and a same-named one by somebody else.
 *
 * What survives is ranked synced-first, then by duration distance. The result is [Match.Ambiguous]
 * when the survivors disagree about who the song is by (unknown artist) or nothing pins the
 * recording (no duration, several different texts): the user chooses instead of MImic guessing.
 */
object LyricsMatcher {
    const val DURATION_TOLERANCE_MS = 3_500L
    private const val DURATION_TOLERANCE_UNKNOWN_ARTIST_MS = 2_000L

    sealed interface Match {
        data class Found(val candidate: LyricsCandidate) : Match
        data class Ambiguous(val candidates: List<LyricsCandidate>) : Match
        data object None : Match
    }

    fun accepts(q: LyricsQuery, c: LyricsCandidate): Boolean {
        if (!c.hasText || c.instrumental) return false
        val cq = LyricsQuery.of(c.title, c.artist, c.album, c.durationMs)
        if (LyricsQuery.compact(cq.title) != LyricsQuery.compact(q.title)) return false
        if (cq.versions != q.versions) return false
        if (q.artistKnown) {
            val theirs = cq.artists.map { it.replace(" ", "") } + LyricsQuery.compact(c.artist)
            val ours = q.artists.map { it.replace(" ", "") }
            // Containment only for names long enough not to match by accident ("X" is in everything).
            val credited = ours.any { mine ->
                theirs.any { it == mine || (mine.length >= 4 && it.length >= 4 && (it.contains(mine) || mine.contains(it))) }
            }
            if (!credited) return false
        }
        if (q.durationMs > 0 && c.durationMs > 0) {
            val tol = if (q.artistKnown) DURATION_TOLERANCE_MS else DURATION_TOLERANCE_UNKNOWN_ARTIST_MS
            if (abs(q.durationMs - c.durationMs) > tol) return false
        } else if (!q.artistKnown) {
            // Neither an artist nor a duration to check: a title alone identifies nothing.
            return false
        }
        return true
    }

    fun match(q: LyricsQuery, candidates: List<LyricsCandidate>): Match {
        val ok = candidates.filter { accepts(q, it) }
            .sortedWith(
                compareBy<LyricsCandidate>({ it.synced.isNullOrBlank() }, { distance(q, it) }),
            )
        if (ok.isEmpty()) return Match.None
        if (!q.artistKnown) {
            val by = ok.map { LyricsQuery.compact(it.artist) }.toSet()
            if (by.size > 1) return Match.Ambiguous(distinctTexts(ok))
        }
        if (q.durationMs <= 0) {
            val texts = distinctTexts(ok)
            if (texts.size > 1 && texts.count { !it.synced.isNullOrBlank() } != 1) return Match.Ambiguous(texts)
        }
        return Match.Found(ok.first())
    }

    private fun distance(q: LyricsQuery, c: LyricsCandidate): Long =
        if (q.durationMs > 0 && c.durationMs > 0) abs(q.durationMs - c.durationMs) else Long.MAX_VALUE / 2

    /** One candidate per distinct text (LRCLIB often holds the same lyrics several times). */
    fun distinctTexts(list: List<LyricsCandidate>): List<LyricsCandidate> =
        list.distinctBy { (it.bestText ?: "").lowercase().replace(Regex("""\s+"""), " ").take(400) }.take(8)
}
