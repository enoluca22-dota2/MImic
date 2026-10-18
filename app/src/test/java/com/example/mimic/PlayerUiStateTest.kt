package com.example.mimic

import androidx.media3.common.Player
import com.example.mimic.player.PlayerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression guard for MImic 2.8.4 "Stop & Close": stopPlayback() resets ui state to
 * PlayerUiState(), which must represent a fully stopped/cleared player so the app never
 * falsely reports music is still active (no current song, not playing, no buffering, no error).
 */
class PlayerUiStateTest {

    @Test
    fun `default state is fully cleared and idle`() {
        val s = PlayerUiState()
        assertNull("no current song after stop", s.currentSong)
        assertFalse("not playing after stop", s.isPlaying)
        assertFalse("not buffering after stop", s.isBuffering)
        assertEquals("position reset", 0L, s.positionMs)
        assertEquals("duration reset", 0L, s.durationMs)
        assertNull("no error after stop", s.playbackError)
        assertEquals("repeat reset to off", Player.REPEAT_MODE_OFF, s.repeatMode)
        assertFalse("shuffle reset", s.shuffle)
    }
}
