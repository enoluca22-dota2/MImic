package com.example.mimic.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * The phone's media volume (STREAM_MUSIC — the one the volume keys change while music plays),
 * as Compose state (4.8). Reads and writes go straight to [AudioManager], so a knob or slider bound
 * to it changes the real output volume, and [level] follows the hardware keys and other apps.
 * MImic's playback itself (Media3 player volume, ReplayGain, crossfade) is untouched.
 */
@Stable
class MediaVolumeState internal constructor(private val audio: AudioManager?) {
    val max: Int = (audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(1)

    /** True when the device has fixed volume (some TVs / car units): the control is shown disabled. */
    val fixed: Boolean = audio?.isVolumeFixed ?: true

    var level by mutableIntStateOf(read())
        private set

    val fraction: Float get() = level.toFloat() / max

    private fun read(): Int = runCatching { audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0 }.getOrDefault(0)

    internal fun refresh() {
        level = read()
    }

    /** Sets the media volume to [newLevel] steps (clamped). No system volume panel is shown. */
    fun set(newLevel: Int) {
        val v = newLevel.coerceIn(0, max)
        if (fixed || v == level) return
        // Never crash the player over volume: some OEMs throw while Do Not Disturb is changing.
        runCatching { audio?.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
        level = read()
    }

    fun setFraction(f: Float) = set(volumeStepFor(f, max))
}

/** Nearest volume step for a 0..1 control position. Pure (unit-tested). */
fun volumeStepFor(fraction: Float, max: Int): Int =
    if (!fraction.isFinite() || max <= 0) 0 else Math.round(fraction.coerceIn(0f, 1f) * max).coerceIn(0, max)

private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"

@Composable
fun rememberMediaVolume(): MediaVolumeState {
    val context = LocalContext.current
    val state = remember {
        MediaVolumeState(context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
    }
    // Follow the volume keys / other apps while the control is on screen; unregistered on leave.
    DisposableEffect(state) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) = state.refresh()
        }
        val registered = runCatching {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(VOLUME_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        }.isSuccess
        state.refresh()
        onDispose { if (registered) runCatching { context.unregisterReceiver(receiver) } }
    }
    return state
}
