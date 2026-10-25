package com.example.mimic

import com.example.mimic.data.audio.LrcLib
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 4.4: LRCLIB matching never shows another recording's lyrics. */
class LrcLibTest {
    private val json = """[
      {"trackName":"Mirrors","artistName":"Justin Timberlake","duration":484.0,"instrumental":false,"plainLyrics":"plain","syncedLyrics":"[00:01.00]Synced line"},
      {"trackName":"Mirrors (Radio Edit)","artistName":"Justin Timberlake","duration":321.0,"instrumental":false,"plainLyrics":"edit","syncedLyrics":null},
      {"trackName":"Mirrors","artistName":"Someone Else","duration":484.0,"instrumental":false,"plainLyrics":"cover","syncedLyrics":null}
    ]"""

    @Test fun synced_match_with_same_duration_wins() {
        assertEquals("[00:01.00]Synced line", LrcLib.pick(LrcLib.parse(json), "Mirrors", "Justin Timberlake", 485_000))
    }

    @Test fun different_length_recording_is_rejected() {
        // The 8-minute official video is not the album recording: no lyrics rather than wrong ones.
        assertNull(LrcLib.pick(LrcLib.parse(json), "Mirrors", "Justin Timberlake", 501_000))
    }

    @Test fun other_artists_lyrics_are_never_used() {
        assertNull(LrcLib.pick(LrcLib.parse(json).drop(0).filter { it.artist == "Someone Else" }, "Mirrors", "Justin Timberlake", 484_000))
    }

    @Test fun bad_json_is_no_match() {
        assertEquals(emptyList<LrcLib.Candidate>(), LrcLib.parse("not json"))
    }
}
