package com.example.mimic.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.example.mimic.data.AudioPrefs
import com.example.mimic.data.VolumeMath
import com.example.mimic.data.audio.AudioTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every audio effect of MImic 3.2, applied in the service so it works with the UI closed.
 * One volume equation rules them all (see [VolumeMath]): player.volume = replayGain × crossfade ×
 * sleepFade. Crossfade is a fade-through on the single player (fade out the end, fade in the next
 * start) — there is never a second player, so a queue transition can't double-play.
 */
@OptIn(UnstableApi::class)
class PlaybackEffects(
    private val context: Context,
    private val player: ExoPlayer,
    private val scope: CoroutineScope,
    prefsFlow: Flow<AudioPrefs>,
) {
    private var prefs = AudioPrefs()
    private var rgFactor = 1f
    private var fadeFactor = 1f
    private var sleepFactor = 1f
    private var startedAt = SystemClock.elapsedRealtime()
    private var fadeJob: Job? = null
    private var sleepJob: Job? = null

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null

    /** ReplayGain tags per media id (small LRU; reading a file head costs a few ms). */
    private val gainCache = object : LinkedHashMap<String, AudioTags>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AudioTags>) = size > 64
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var ignoreInitialDevices = true
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo>) {
            // The callback first reports the devices already connected; only react to new ones.
            if (ignoreInitialDevices) { ignoreInitialDevices = false; return }
            if (!prefs.resumeOnConnect) return
            if (added.any { it.isSink && it.type in HEADPHONES } && !player.isPlaying && player.mediaItemCount > 0) player.play()
        }
    }

    init {
        scope.launch {
            prefsFlow.distinctUntilChanged().collect { p ->
                val old = prefs
                prefs = p
                val params = PlaybackParameters(p.speed, if (p.keepPitch) 1f else p.speed)
                if (player.playbackParameters != params) player.playbackParameters = params
                player.skipSilenceEnabled = p.skipSilence
                if (old.replayGain != p.replayGain) applyReplayGain(player.currentMediaItem)
                applyEq()
                restartFadeTicker()
            }
        }
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) = attachEq(audioSessionId)
            override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) = restartFadeTicker()
        })
        if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) attachEq(player.audioSessionId)
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
    }

    fun onMediaItemTransition(item: MediaItem?) {
        startedAt = SystemClock.elapsedRealtime()
        applyReplayGain(item)
        updateFade()
    }

    // ---- Volume ----

    private fun applyVolume() {
        player.volume = (rgFactor * fadeFactor * sleepFactor).coerceIn(0f, 1f)
    }

    private fun restartFadeTicker() {
        fadeJob?.cancel()
        if (prefs.crossfadeSec <= 0) {
            if (fadeFactor != 1f) { fadeFactor = 1f; applyVolume() }
            return
        }
        if (!player.isPlaying) return
        fadeJob = scope.launch {
            while (isActive) {
                updateFade()
                delay(100)
            }
        }
    }

    private fun updateFade() {
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val f = VolumeMath.crossfadeFactor(
            fadeMs = prefs.crossfadeSec * 1000L,
            positionMs = player.currentPosition,
            durationMs = duration,
            sinceStartMs = SystemClock.elapsedRealtime() - startedAt,
        )
        if (f != fadeFactor) { fadeFactor = f; applyVolume() }
    }

    private fun applyReplayGain(item: MediaItem?) {
        val id = item?.mediaId
        val uri = item?.localConfiguration?.uri
        if (prefs.replayGain == com.example.mimic.data.ReplayGainMode.OFF || id == null || uri == null) {
            rgFactor = 1f; applyVolume(); return
        }
        scope.launch {
            val tags = synchronized(gainCache) { gainCache[id] } ?: withContext(Dispatchers.IO) { readTags(uri) }
                .also { synchronized(gainCache) { gainCache[id] = it } }
            if (player.currentMediaItem?.mediaId != id) return@launch
            rgFactor = VolumeMath.replayGainFactor(VolumeMath.pickGain(prefs.replayGain, tags.trackGainDb, tags.albumGainDb))
            Log.i(TAG, "ReplayGain ${prefs.replayGain}: track=${tags.trackGainDb} album=${tags.albumGainDb} -> x$rgFactor")
            applyVolume()
        }
    }

    /** Tags of on-device / downloaded files only; streamed songs are never fetched just for this. */
    private fun readTags(uri: Uri): AudioTags = runCatching {
        if (uri.scheme != "content" && uri.scheme != "file") return AudioTags.NONE
        context.contentResolver.openInputStream(uri)?.use { AudioTags.parse(readHead(it, AudioTags.HEAD_BYTES)) } ?: AudioTags.NONE
    }.getOrDefault(AudioTags.NONE)

    // ---- Sleep timer (fades out over the last 20 s, then pauses) ----

    /** minutes > 0: timer; [SLEEP_END_OF_SONG]: pause when the current song ends; else cancel. */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepJob = null
        sleepFactor = 1f
        applyVolume()
        sleepEndsAt = 0L
        when {
            minutes == SLEEP_END_OF_SONG -> {
                sleepEndsAt = -1L
                sleepJob = scope.launch {
                    val id = player.currentMediaItem?.mediaId
                    while (isActive) {
                        val d = player.duration
                        if (player.currentMediaItem?.mediaId != id) break // auto-advanced: stop right at the boundary
                        if (d != C.TIME_UNSET && d - player.currentPosition <= 400) break
                        delay(200)
                    }
                    player.pause()
                    if (player.currentMediaItem?.mediaId != id) player.seekTo(0)
                    sleepEndsAt = 0L
                }
            }
            minutes > 0 -> {
                sleepEndsAt = System.currentTimeMillis() + minutes * 60_000L
                sleepJob = scope.launch {
                    delay((minutes * 60_000L - SLEEP_FADE_MS).coerceAtLeast(0))
                    val steps = 40
                    repeat(steps) { i ->
                        sleepFactor = 1f - (i + 1) / steps.toFloat()
                        applyVolume()
                        delay(SLEEP_FADE_MS / steps)
                    }
                    player.pause()
                    sleepFactor = 1f
                    applyVolume()
                    sleepEndsAt = 0L
                }
            }
        }
    }

    /** Wall-clock end of the sleep timer, -1 = end of song, 0 = off. */
    var sleepEndsAt: Long = 0L
        private set

    // ---- Equalizer (device effect; may be unavailable) ----

    private fun attachEq(sessionId: Int) {
        releaseEq()
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        try {
            equalizer = Equalizer(0, sessionId)
            bassBoost = runCatching { BassBoost(0, sessionId) }.getOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Equalizer unavailable on this device", e)
            releaseEq()
        }
        applyEq()
    }

    private fun applyEq() {
        try {
            equalizer?.let { eq ->
                eq.enabled = prefs.eqEnabled
                if (prefs.eqEnabled) {
                    val range = eq.bandLevelRange
                    for (band in 0 until eq.numberOfBands) {
                        val level = prefs.eqBands.getOrNull(band) ?: 0
                        eq.setBandLevel(band.toShort(), level.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
                    }
                }
            }
            bassBoost?.let {
                it.enabled = prefs.eqEnabled && prefs.bassBoost > 0
                if (it.strengthSupported) it.setStrength(prefs.bassBoost.coerceIn(0, 1000).toShort())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply equalizer", e)
        }
    }

    /** Band layout for the settings screen; available=false when the phone has no equalizer. */
    fun eqInfo(): Bundle = Bundle().apply {
        val eq = equalizer ?: probeEqualizer()
        putBoolean(KEY_AVAILABLE, eq != null)
        if (eq != null) {
            val n = eq.numberOfBands.toInt()
            putIntArray(KEY_FREQS, IntArray(n) { eq.getCenterFreq(it.toShort()) / 1000 }) // Hz
            putInt(KEY_MIN, eq.bandLevelRange[0].toInt())
            putInt(KEY_MAX, eq.bandLevelRange[1].toInt())
            putBoolean(KEY_BASS, bassBoost?.strengthSupported == true)
            if (eq !== equalizer) eq.release()
        }
    }

    /** Before the first song has an audio session, describe the device EQ via a throwaway instance. */
    private fun probeEqualizer(): Equalizer? = runCatching { Equalizer(0, player.audioSessionId.takeIf { it != C.AUDIO_SESSION_ID_UNSET } ?: 0) }.getOrNull()

    private fun releaseEq() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        equalizer = null
        bassBoost = null
    }

    fun release() {
        fadeJob?.cancel()
        sleepJob?.cancel()
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        releaseEq()
    }

    companion object {
        private const val TAG = "MImicEffects"
        const val SLEEP_END_OF_SONG = -2
        const val SLEEP_FADE_MS = 20_000L

        const val KEY_AVAILABLE = "available"
        const val KEY_FREQS = "freqs"
        const val KEY_MIN = "min"
        const val KEY_MAX = "max"
        const val KEY_BASS = "bass"

        private val HEADPHONES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )

        fun readHead(input: java.io.InputStream, max: Int): ByteArray {
            val buf = ByteArray(max)
            var n = 0
            while (n < max) {
                val r = input.read(buf, n, max - n)
                if (r < 0) break
                n += r
            }
            return if (n == max) buf else buf.copyOf(n)
        }
    }
}
