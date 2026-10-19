package com.example.mimic.cast

import android.content.Context
import android.util.Log
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

/**
 * Google Cast (Chromecast, Google TV, TVs with Chromecast built-in) — MImic 4.1.
 *
 * Discovery and the device picker are Google's (MediaRouteButton + Cast framework); playback on
 * the TV uses Google's Default Media Receiver through Media3's [CastPlayer]. The phone keeps the
 * one local Media3 player for everything else: when a cast session starts, the PlayerViewModel
 * hands the queue to the TV ([LanMediaServer] URLs) and pauses the phone; when it ends, the phone
 * takes over at the same song and position, paused.
 *
 * Requires Google Play services. TVs that only speak other protocols (DLNA/UPnP, AirPlay,
 * Miracast screen mirroring) are not Cast receivers and will not appear.
 */
@UnstableApi
class CastController(context: Context) {

    sealed interface Status {
        /** Cast can't run on this phone (e.g. no Google Play services). */
        data class Unsupported(val reason: String) : Status
        data object Idle : Status
        data class Connected(val deviceName: String) : Status
    }

    enum class Event { SESSION_STARTED, SESSION_ENDED }

    private val app = context.applicationContext
    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    val server = LanMediaServer(app, OkHttpClient())

    var castContext: CastContext? = null
        private set
    var player: CastPlayer? = null
        private set

    /** Must run on the main thread; safe to call repeatedly. */
    fun ensureInitialized(): Boolean {
        if (player != null) return true
        if (_status.value is Status.Unsupported) return false
        return try {
            @Suppress("DEPRECATION")
            val ctx = CastContext.getSharedInstance(app)
            castContext = ctx
            player = CastPlayer(ctx).also { p ->
                p.setSessionAvailabilityListener(object : SessionAvailabilityListener {
                    override fun onCastSessionAvailable() {
                        _status.value = Status.Connected(deviceName() ?: "TV")
                        _events.tryEmit(Event.SESSION_STARTED)
                    }

                    override fun onCastSessionUnavailable() {
                        _status.value = Status.Idle
                        _events.tryEmit(Event.SESSION_ENDED)
                    }
                })
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Cast unavailable: ${e.javaClass.simpleName}")
            _status.value = Status.Unsupported("Casting needs Google Play services, which isn't available on this phone.")
            false
        }
    }

    fun deviceName(): String? = castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName

    /** TV volume 0..1, or null when not connected / not reported. */
    fun volume(): Float? = castContext?.sessionManager?.currentCastSession?.let { runCatching { it.volume.toFloat() }.getOrNull() }

    fun setVolume(v: Float) {
        castContext?.sessionManager?.currentCastSession?.let { runCatching { it.volume = v.toDouble().coerceIn(0.0, 1.0) } }
    }

    /** Ends the session; playback returns to the phone (paused at the same position). */
    fun disconnect() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    companion object {
        private const val TAG = "MImicCast"
    }
}

/** Declared in the manifest: Google's Default Media Receiver plays standard audio from a URL. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
