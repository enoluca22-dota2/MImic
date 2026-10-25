package com.example.mimic

import com.example.mimic.data.audio.LrcLib
import com.example.mimic.data.lyrics.LyricsMatcher
import com.example.mimic.data.lyrics.LyricsQuery
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in (MIMIC_LIVE_PROBE=1): the 4.5 lyrics pipeline against the live LRCLIB API, for the
 * metadata shapes that found nothing in 4.4. Prints one line per case; never part of a normal run.
 */
class LiveLyricsProbe {
    @Test fun probe() {
        assumeTrue(System.getenv("MIMIC_LIVE_PROBE") == "1")
        val cases = listOf(
            Triple("Justin Timberlake - Mirrors", "JustinTimberlake", 484_000L),
            Triple("Houdini (Lyrics)", "Dua Lipa", 186_000L),
            Triple("Dua Lipa - Houdini (Official Music Video)", "Dua Lipa", 188_000L),
            Triple("Aerials", "System Of A Down", 235_000L),
            Triple("Stay (with Justin Bieber)", "The Kid LAROI", 141_000L),
            Triple("Mirrors (Live at the iHeartRadio Music Festival)", "Justin Timberlake", 484_000L),
        )
        for ((title, artist, dur) in cases) {
            var result = "no match"
            loop@ for (q in LyricsQuery.variants(title, artist, durationMs = dur)) {
                for (r in listOf(LrcLib.get(q), LrcLib.search(q))) {
                    when (r) {
                        is LrcLib.Response.Failed -> { result = "FAILED ${r.reason} ${r.httpCode}"; break@loop }
                        is LrcLib.Response.Ok -> when (val m = LyricsMatcher.match(q, r.candidates)) {
                            is LyricsMatcher.Match.Found -> {
                                result = "FOUND ${if (m.candidate.synced != null) "synced" else "plain"}: ${m.candidate.title} / ${m.candidate.artist} (${m.candidate.durationMs / 1000}s) id=${m.candidate.providerId}"
                                break@loop
                            }
                            is LyricsMatcher.Match.Ambiguous -> { result = "AMBIGUOUS ${m.candidates.size}"; break@loop }
                            LyricsMatcher.Match.None -> Unit
                        }
                    }
                }
            }
            println("LIVE-LYRICS | $title | $artist | ${dur / 1000}s -> $result")
        }
    }
}
