package com.example.mimic.player

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.mimic.MimicApplication
import com.example.mimic.data.db.PlayHistoryEntity
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import android.os.Bundle
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Hosts the ExoPlayer instance and a MediaSession so playback survives in the background
 * and gets a system media notification with transport controls.
 *
 * MImic 3.2: the service is also where playback *facts* are recorded, because it outlives the UI —
 * the play queue + position (so the app resumes where it left off) and the play history (so a song
 * that auto-advances with the app closed still counts).
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionTicker: Job? = null
    private var effects: PlaybackEffects? = null
    /** Listen Together (4.5): binds the party to this player; idle outside a party. */
    private var partySync: PartySync? = null

    /** The item whose play hasn't been recorded yet (it was loaded but hasn't started playing). */
    private var unrecordedMediaId: String? = null
    private var consecutiveErrors = 0

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // One player for every source: phone files (content://), offline downloads (file://),
        // Navidrome streams (http/https) and, in the full edition, online tracks (mimic-yt://,
        // resolved to a fresh stream when opened — 4.4). Non-online URIs are untouched by the wrap.
        val dataSources = com.example.mimic.online.OnlineMusic.wrap(androidx.media3.datasource.DefaultDataSource.Factory(this))
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones unplugged
            // Keep the CPU + Wi-Fi awake while streaming with the screen off.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(playerListener(player))
        effects = PlaybackEffects(this, player, scope, container.settingsRepository.settings.map { it.audio })
        com.example.mimic.party.ListenTogether.init(this)
        val sync = PartySync(scope) { player }.also { it.start() }
        partySync = sync
        mediaSession = MediaSession.Builder(this, PartyPlayer(player, sync))
            .setCallback(sessionCallback())
            // Notification / lock-screen artwork: per-track art for phone songs (4.1), cached.
            .setBitmapLoader(
                androidx.media3.session.CacheBitmapLoader(
                    com.example.mimic.data.art.TrackArtBitmapLoader(this, androidx.media3.datasource.DataSourceBitmapLoader(this)),
                ),
            )
            .setSessionActivity(
                android.app.PendingIntent.getActivity(
                    this, 0, android.content.Intent(this, com.example.mimic.MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    /** MImic's own commands from the UI: sleep timer (lives here so it fires with the UI closed), EQ layout. */
    private fun sessionCallback() = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_SLEEP, Bundle.EMPTY))
                .add(SessionCommand(CMD_EQ_INFO, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val fx = effects ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_UNKNOWN))
            return when (customCommand.customAction) {
                CMD_SLEEP -> {
                    if (args.containsKey(ARG_MINUTES)) fx.setSleepTimer(args.getInt(ARG_MINUTES))
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply { putLong(ARG_ENDS_AT, fx.sleepEndsAt) }))
                }
                CMD_EQ_INFO -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, fx.eqInfo()))
                else -> Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
        }
    }

    private val container get() = (application as MimicApplication).container

    private fun playerListener(player: Player) = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) partySync?.onAutoAdvance()
            unrecordedMediaId = mediaItem?.mediaId
            effects?.onMediaItemTransition(mediaItem)
            // A queue replaced by the app (play / restore) is "loaded", not yet "played": record it
            // once it actually starts. Auto-advance and skips while playing count right away.
            if (player.isPlaying) recordPendingPlay()
            persistPosition(player)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            positionTicker?.cancel()
            if (isPlaying) {
                // Resuming a restored queue mid-song continues an old play; it isn't a new one.
                if (player.currentPosition > RESUME_NOT_A_PLAY_MS) unrecordedMediaId = null
                recordPendingPlay()
                positionTicker = scope.launch {
                    while (isActive) {
                        delay(10_000)
                        persistPosition(player)
                    }
                }
            } else {
                persistPosition(player)
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) persistQueue(player)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) consecutiveErrors = 0
            // The last song this device had finished, but the party may have more.
            if (playbackState == Player.STATE_ENDED) partySync?.onAutoAdvance()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            partySync?.onPlayWhenReadyChanged(playWhenReady, reason)
        }

        /**
         * One unplayable song (deleted, missing on the server, unsupported format)
         * must not end the whole queue: skip to the next song. A run of failures in a row means
         * the problem isn't the song (e.g. no connection), so stop there instead of racing through
         * the queue. The UI shows the error either way.
         */
        override fun onPlayerError(error: PlaybackException) {
            // Radio (negative ids): the app retries the same station a bounded number of times;
            // jumping to another station on its own would be surprising.
            if ((player.currentMediaItem?.mediaId?.toLongOrNull() ?: 0L) < 0) return
            // In a party, a song this device can't play is sat out; skipping would leave the party.
            if (partySync?.onPlaybackError() == true) return
            consecutiveErrors++
            if (consecutiveErrors <= MAX_SKIPS_ON_ERROR && player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    private fun recordPendingPlay() {
        val songId = unrecordedMediaId?.toLongOrNull() ?: return
        unrecordedMediaId = null
        if (songId < 0) return // live radio isn't a song play (stations keep their own Recently Played)
        scope.launch(Dispatchers.IO) {
            container.historyDao.record(PlayHistoryEntity(songId = songId, playedAt = System.currentTimeMillis()))
        }
    }

    private fun persistQueue(player: Player) {
        // A radio session must not overwrite the music queue that Continue Listening restores.
        if ((player.currentMediaItem?.mediaId?.toLongOrNull() ?: 0L) < 0) return
        // Nor a party's running order (the user's own queue comes back when they leave).
        if (partySync?.inParty == true) return
        val ids = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).mediaId.toLongOrNull() }
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        scope.launch(Dispatchers.IO) { container.playbackStateStore.saveQueue(ids, index, position) }
    }

    private fun persistPosition(player: Player, on: CoroutineScope = scope) {
        if (player.mediaItemCount == 0) return
        if ((player.currentMediaItem?.mediaId?.toLongOrNull() ?: 0L) < 0) return // radio: keep the music position
        if (partySync?.inParty == true) return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        on.launch(Dispatchers.IO) { container.playbackStateStore.savePosition(index, position) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        partySync?.stop()
        partySync = null
        effects?.release()
        effects = null
        mediaSession?.run {
            // The service scope dies with the service; this last write must outlive it.
            persistPosition(player, on = CoroutineScope(SupervisorJob()))
            player.release()
            release()
        }
        mediaSession = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CMD_SLEEP = "com.example.mimic.SLEEP_TIMER"
        const val CMD_EQ_INFO = "com.example.mimic.EQ_INFO"
        const val ARG_MINUTES = "minutes"
        const val ARG_ENDS_AT = "endsAt"
        private const val MAX_SKIPS_ON_ERROR = 3
        private const val RESUME_NOT_A_PLAY_MS = 5_000L
    }
}
