package com.example.mimic.data

/** ReplayGain volume leveling, from valid tags only (attenuation-only, never boosts). */
enum class ReplayGainMode(val label: String) { OFF("Off"), TRACK("Track"), ALBUM("Album") }

/**
 * Playback & audio settings (MImic 3.2). All are applied inside PlaybackService, so they keep
 * working with the app closed. Device-dependent ones (equalizer, bass boost) degrade to "not
 * available on this phone" rather than failing.
 */
data class AudioPrefs(
    val speed: Float = 1f,
    /** Keep the original pitch when changing speed (otherwise faster = higher, like tape). */
    val keepPitch: Boolean = true,
    /** Fade out the end / fade in the start of each song, 0 = off. One player: no overlap. */
    val crossfadeSec: Int = 0,
    val skipSilence: Boolean = false,
    val replayGain: ReplayGainMode = ReplayGainMode.OFF,
    val eqEnabled: Boolean = false,
    /** Band levels in millibels, one per device band (empty = flat). */
    val eqBands: List<Int> = emptyList(),
    /** 0..1000 (device permille). */
    val bassBoost: Int = 0,
    val hapticSeek: Boolean = true,
    /** Resume when headphones / Bluetooth audio connect (pausing on disconnect is always on). */
    val resumeOnConnect: Boolean = false,
) {
    companion object {
        val SPEEDS = listOf(0.5f, 0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)
        const val MAX_CROSSFADE_SEC = 12

        fun encodeBands(bands: List<Int>) = bands.joinToString(",")
        fun decodeBands(csv: String?) = csv?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
    }
}

/**
 * The single volume equation used by the service (pure, unit-tested):
 * volume = replayGain × crossfade × sleepFade, each factor in 0..1.
 */
object VolumeMath {
    /** ReplayGain dB -> linear factor; attenuation only (positive gains are clamped to 1). */
    fun replayGainFactor(gainDb: Float?): Float =
        if (gainDb == null) 1f else Math.pow(10.0, gainDb / 20.0).toFloat().coerceIn(0f, 1f)

    fun pickGain(mode: ReplayGainMode, trackDb: Float?, albumDb: Float?): Float? = when (mode) {
        ReplayGainMode.OFF -> null
        ReplayGainMode.TRACK -> trackDb ?: albumDb
        ReplayGainMode.ALBUM -> albumDb ?: trackDb
    }

    /**
     * Crossfade factor at [positionMs] of a [durationMs] song, [sinceStartMs] after it began.
     * Songs shorter than twice the fade don't fade (it would swallow them).
     */
    fun crossfadeFactor(fadeMs: Long, positionMs: Long, durationMs: Long, sinceStartMs: Long): Float {
        if (fadeMs <= 0) return 1f
        var f = 1f
        if (durationMs > fadeMs * 2) {
            val remaining = durationMs - positionMs
            if (remaining < fadeMs) f = (remaining.toFloat() / fadeMs).coerceIn(0f, 1f)
        }
        if (sinceStartMs in 0 until fadeMs) f = minOf(f, sinceStartMs.toFloat() / fadeMs)
        return f
    }
}
