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

/**
 * Hosts the ExoPlayer instance and a MediaSession so playback survives in the background
 * and gets a system media notification with transport controls.
 *
 * MImic 3.2: the service is also where playback *facts* are recorded, because it outlives the UI —
 * the play queue + position (so the app resumes where it left off) and the play history (so a song
 * that auto-advances with the app closed still counts).
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionTicker: Job? = null

    /** The item whose play hasn't been recorded yet (it was loaded but hasn't started playing). */
    private var unrecordedMediaId: String? = null
    private var consecutiveErrors = 0

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val cloud = (application as MimicApplication).container.cloudLibrary
        // One player for every source: files/content/http as before, plus mimic-cloud:// songs
        // (Google Drive / OneDrive) resolved and range-streamed by CloudDataSource.
        val dataSources = MimicDataSourceFactory(this, cloud, cloud.httpClient)
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
        mediaSession = MediaSession.Builder(this, player).build()
    }

    private val container get() = (application as MimicApplication).container

    private fun playerListener(player: Player) = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            unrecordedMediaId = mediaItem?.mediaId
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
        }

        /**
         * One unplayable song (deleted from the cloud, missing on the server, unsupported format)
         * must not end the whole queue: skip to the next song. A run of failures in a row means
         * the problem isn't the song (e.g. no connection), so stop there instead of racing through
         * the queue. The UI shows the error either way.
         */
        override fun onPlayerError(error: PlaybackException) {
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
        scope.launch(Dispatchers.IO) {
            container.historyDao.record(PlayHistoryEntity(songId = songId, playedAt = System.currentTimeMillis()))
        }
    }

    private fun persistQueue(player: Player) {
        val ids = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).mediaId.toLongOrNull() }
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        scope.launch(Dispatchers.IO) { container.playbackStateStore.saveQueue(ids, index, position) }
    }

    private fun persistPosition(player: Player, on: CoroutineScope = scope) {
        if (player.mediaItemCount == 0) return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        on.launch(Dispatchers.IO) { container.playbackStateStore.savePosition(index, position) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
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

    private companion object {
        const val MAX_SKIPS_ON_ERROR = 3
        const val RESUME_NOT_A_PLAY_MS = 5_000L
    }
}
