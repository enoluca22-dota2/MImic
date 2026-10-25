/*
 * Part of MImic (GPL-3.0). The credit / packaging patterns in [CREDITS] are adapted from BitChord's
 * data/lyrics/LyricsQuery.kt (Copyright (C) BitChord contributors, GPL-3.0,
 * https://github.com/KabirSinghBhatia/BitChord); the rest is MImic's own.
 */
package com.example.mimic.data.lyrics

/**
 * What a lyrics database would call a song, worked out from the title / artist MImic has.
 *
 * MImic's metadata comes from very different places: tagged phone files ("Mirrors" / "Justin
 * Timberlake"), Navidrome, YouTube Music's catalogue, and — in Albania, where the catalogue answers
 * "No results" — plain YouTube uploads ("Justin Timberlake - Mirrors (Official Video)" by
 * "JustinTimberlakeVEVO"). LRCLIB indexes the first form only, so asking it with the last one
 * found nothing at all. That was the main reason lyrics were missing in 4.4 (measured against the
 * live API: 0 results for the YouTube form, a synced match for the cleaned one).
 *
 * Only credits and upload packaging are removed. Anything naming a different *recording* — live,
 * remix, acoustic, sped up… — is kept and reported in [versions], so the matcher can refuse
 * lyrics of another version instead of showing confidently wrong words.
 */
data class LyricsQuery(
    /** Title to search with: credits ("feat. X") and packaging ("Official Video") removed. */
    val title: String,
    /** Main artist to search with ("" when unknown — then the duration has to carry the match). */
    val artist: String,
    /** Every artist credited (main + featured), normalised, for matching. */
    val artists: Set<String>,
    val album: String,
    val durationMs: Long,
    /** Version markers of the recording ("live", "remix", …) — must agree with a candidate's. */
    val versions: Set<String>,
) {
    val artistKnown: Boolean get() = artist.isNotBlank()

    companion object {
        fun of(rawTitle: String, rawArtist: String, album: String = "", durationMs: Long = 0L): LyricsQuery {
            var artist = cleanArtist(rawArtist)
            var title = rawTitle.trim()
            // "Artist - Song" uploads. The left side is the artist when it names the channel's artist
            // (compared without spaces, so "JustinTimberlake" == "Justin Timberlake") or when the
            // channel is no artist at all; the left side is then the better-spelled artist name.
            val dash = DASH.find(title)
            if (dash != null) {
                val left = title.substring(0, dash.range.first).trim()
                val right = title.substring(dash.range.last + 1).trim()
                if (left.isNotEmpty() && right.isNotEmpty()) {
                    val sameArtist = compact(left) == compact(artist) ||
                        (artist.isNotEmpty() && compact(left).contains(compact(artist))) ||
                        (artist.isNotEmpty() && compact(artist).contains(compact(left)))
                    if (sameArtist || artist.isEmpty()) {
                        artist = left
                        title = right
                    }
                }
            }
            val featured = FEATURED.findAll(title).map { it.groupValues[2].ifEmpty { it.groupValues[4] } }.toList()
            val versions = versionsOf(title)
            title = stripCredits(title)
            val credited = (splitArtists(artist) + featured.flatMap(::splitArtists)).map(::norm).filter { it.isNotEmpty() }.toSet()
            val main = splitArtists(artist).firstOrNull().orEmpty()
            return LyricsQuery(
                title = title,
                artist = main,
                artists = credited,
                album = cleanAlbum(album),
                durationMs = durationMs.coerceAtLeast(0L),
                versions = versions,
            )
        }

        /**
         * [of], plus — for an "A - B" title whose channel isn't A (a lyrics or fan channel) — the
         * reading "artist A, song B". Tried in order; the matcher still checks every answer.
         */
        fun variants(rawTitle: String, rawArtist: String, album: String = "", durationMs: Long = 0L): List<LyricsQuery> {
            val primary = of(rawTitle, rawArtist, album, durationMs)
            val dash = DASH.find(rawTitle.trim()) ?: return listOf(primary)
            val left = rawTitle.trim().substring(0, dash.range.first).trim()
            val right = rawTitle.trim().substring(dash.range.last + 1).trim()
            if (left.isEmpty() || right.isEmpty()) return listOf(primary)
            val swapped = of(right, left, album, durationMs)
            return if (swapped.title == primary.title && swapped.artist == primary.artist) listOf(primary) else listOf(primary, swapped)
        }

        /** Channel names → artist: "X - Topic", "XVEVO", "X Official"; unknown placeholders → "". */
        fun cleanArtist(raw: String): String {
            var a = raw.trim().removeSuffix(" - Topic").trim()
            if (a.endsWith("VEVO") && a.length > 4) a = a.dropLast(4).trim()
            a = a.replace(OFFICIAL_SUFFIX, "").trim()
            if (a.isEmpty() || UNKNOWN.matches(a)) return ""
            // "JustinTimberlake" (a VEVO channel) → "Justin Timberlake".
            if (!a.contains(' ') && a.length > 3 && CAMEL.containsMatchIn(a)) a = a.replace(CAMEL, "$1 $2")
            return a
        }

        private fun cleanAlbum(raw: String): String =
            raw.trim().takeUnless { it.isEmpty() || UNKNOWN.matches(it) || it.equals("YouTube Music", true) }.orEmpty()

        /** Credits and packaging out; whatever remains is the recording's name. */
        fun stripCredits(raw: String): String {
            var name = raw
            CREDITS.forEach { name = it.replace(name, " ") }
            name = name.substringBefore(" | ").substringBefore(" ｜ ")
            return name.replace(WHITESPACE, " ").trim().trimEnd(',', '-', '–', '—').trim().ifBlank { raw.trim() }
        }

        /** Version markers present in a title (bracketed or after a dash). */
        fun versionsOf(title: String): Set<String> {
            val lower = title.lowercase()
            return VERSION_WORDS.filterTo(LinkedHashSet()) { (_, re) -> re.containsMatchIn(lower) }.map { it.first }.toSet()
        }

        fun splitArtists(raw: String): List<String> =
            raw.split(ARTIST_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

        /** Lowercase letters/digits with single spaces (accents kept: LRCLIB stores them). */
        fun norm(s: String): String = s.lowercase().replace(NON_WORD, " ").trim()

        /** [norm] without spaces: "Justin Timberlake" and "JustinTimberlake" compare equal. */
        fun compact(s: String): String = norm(s).replace(" ", "")

        private val WHITESPACE = Regex("""\s+""")
        private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
        private val DASH = Regex("""\s+[-–—]\s+""")
        private val CAMEL = Regex("""(\p{Ll})(\p{Lu})""")
        private val OFFICIAL_SUFFIX = Regex("""\s+(official|music)$""", RegexOption.IGNORE_CASE)
        private val UNKNOWN = Regex("""^(unknown( artist| album)?|<unknown>|various artists|youtube)$""", RegexOption.IGNORE_CASE)
        private val ARTIST_SPLIT = Regex("""\s*(,|&|\bx\b|\bfeat\.?|\bft\.?|\bfeaturing\b|\bwith\b|\band\b)\s*""", RegexOption.IGNORE_CASE)
        private val FEATURED = Regex("""[(\[]\s*(feat|ft|featuring|with)\.?\s+([^)\]]+)[)\]]|\s(feat|ft|featuring)\.?\s+(.+)$""", RegexOption.IGNORE_CASE)

        private val CREDITS = listOf(
            // Bracketed credits: (feat. X), [ft. X], (with X).
            Regex("""\s*[(\[]\s*(feat|ft|featuring|with)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
            // The same, unbracketed and running to the end of the title.
            Regex("""\s+(feat|ft|featuring)\.?\s+.*$""", RegexOption.IGNORE_CASE),
            // How the upload was labelled, not what was recorded.
            Regex(
                """\s*[(\[]\s*(official\s*)?(music\s*)?""" +
                    """(video|audio|visuali[sz]er|lyrics?\s*video|lyrics?|m/?v|hd|hq|4k|full\s*song|explicit|clean)""" +
                    """\s*[)\]]""",
                RegexOption.IGNORE_CASE,
            ),
            Regex("""\s*[(\[]\s*official\s*[)\]]""", RegexOption.IGNORE_CASE),
            Regex("""\s*【[^】]*】"""),
        )

        /** Markers of a different recording. "Remastered" is deliberately absent (same words/timing). */
        private val VERSION_WORDS: List<Pair<String, Regex>> = listOf(
            "live" to Regex("""[(\[\-–]\s*[^)\]]*\blive\b|\blive (at|in|from|version|session)\b"""),
            "remix" to Regex("""\bremix\b|\brmx\b|\bbootleg\b|\bflip\b"""),
            "acoustic" to Regex("""\bacoustic\b|\bunplugged\b"""),
            "instrumental" to Regex("""\binstrumental\b|\bkaraoke\b|\boff ?vocal\b"""),
            "cover" to Regex("""\bcover\b"""),
            "sped" to Regex("""\bsped up\b|\bspeed up\b|\bnightcore\b"""),
            "slowed" to Regex("""\bslowed\b|\breverb\b"""),
            "demo" to Regex("""[(\[\-–]\s*demo\b"""),
        )
    }
}
