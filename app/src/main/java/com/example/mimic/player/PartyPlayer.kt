package com.example.mimic.player

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * The player the MediaSession exposes (MImic 4.5). Outside a party it is a plain pass-through.
 * In a party every *user* command — from MImic's UI, the notification, the lock screen, a
 * Bluetooth / headset button — goes through here, so [PartySync] can publish it; corrections the
 * party makes go straight to the ExoPlayer underneath and never come back as intents.
 *
 * In a host-only party a listener's play / pause move only their own device, and seek / skip /
 * queue changes are refused (the server would refuse them too).
 */
@OptIn(UnstableApi::class)
class PartyPlayer(player: Player, private val sync: PartySync) : ForwardingPlayer(player) {

    override fun play() {
        if (sync.onLockedTransport(true)) return
        if (sync.shouldDeferPlay()) { sync.onLocalIntent(); return }
        super.play()
        sync.onLocalIntent()
    }

    override fun pause() {
        if (sync.onLockedTransport(false)) return
        super.pause()
        sync.onLocalIntent()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) play() else pause()

    override fun seekTo(positionMs: Long) {
        if (sync.blocksTransport()) return
        super.seekTo(positionMs)
        sync.onLocalIntent()
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (sync.blocksTransport()) return
        super.seekTo(mediaItemIndex, positionMs)
        sync.onLocalIntent()
    }

    override fun seekToNext() { if (!sync.skip(forward = true)) super.seekToNext() }
    override fun seekToNextMediaItem() { if (!sync.skip(forward = true)) super.seekToNextMediaItem() }

    override fun seekToPrevious() {
        // "Previous" within the first seconds goes back a song; later it restarts the song — same in a party.
        if (sync.inParty && currentPosition > 3_000) { seekTo(0); return }
        if (!sync.skip(forward = false)) super.seekToPrevious()
    }

    override fun seekToPreviousMediaItem() { if (!sync.skip(forward = false)) super.seekToPreviousMediaItem() }

    // Playing a new list: it becomes the party's queue (when this device may drive the party).
    override fun setMediaItems(mediaItems: MutableList<MediaItem>) = replaced { super.setMediaItems(mediaItems) }
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) = replaced { super.setMediaItems(mediaItems, resetPosition) }
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) =
        replaced { super.setMediaItems(mediaItems, startIndex, startPositionMs) }
    override fun setMediaItem(mediaItem: MediaItem) = replaced { super.setMediaItem(mediaItem) }
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = replaced { super.setMediaItem(mediaItem, startPositionMs) }
    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = replaced { super.setMediaItem(mediaItem, resetPosition) }

    private inline fun replaced(block: () -> Unit) {
        if (sync.blocksTransport()) return
        block()
        sync.onLocalIntent(queueReplaced = true)
    }
}
