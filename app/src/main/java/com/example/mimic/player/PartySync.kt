/*
 * Part of MImic (GPL-3.0). Adapted from BitChord's app/.../playback/PartySync.kt and
 * PartyPersonalQueueStash.kt (Copyright (C) BitChord contributors, GPL-3.0,
 * https://github.com/kushagrasinghx/BitChord). Changes for MImic: party tracks are resolved to
 * this device's own source (PartyTracks) asynchronously, songs are MImic songs (numeric media
 * ids), no queue tiers / crossfade player swap, a sync status for the UI, playback errors mark a
 * song unavailable on this device instead of skipping the party.
 */
package com.example.mimic.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.mimic.data.Song
import com.example.mimic.party.ListenTogether
import com.example.mimic.party.PartyLibrary
import com.example.mimic.party.PartyTrack
import com.example.mimic.party.PartyTracks
import com.example.mimic.party.partyQueueIndexOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Makes the player follow the party, and the party follow this player (MImic 4.5).
 *
 * Lives in [PlaybackService] so a party survives the screen going off. The two directions use
 * different doors so nothing echoes: **outbound** is [onLocalIntent], called only from the
 * session's forwarding player (every user action: app, notification, headset, Bluetooth, lock
 * screen) and from auto-advance; **inbound** is [reconcile], which writes straight to the
 * ExoPlayer underneath. A pause the user didn't ask for (another app took the audio, a call) is
 * not published — one person's phone call doesn't stop the music for everyone — and that device
 * stops following until its user presses play again.
 *
 * Timing: [reconcile] converges on [ListenTogether.partyPositionMs]. Resumes wait for the
 * server's scheduled start (so nobody is consistently early); a control is matched within
 * [ALIGN_TOLERANCE_MS]; between controls, drift is corrected by seeking only when it exceeds
 * [DRIFT_LIMIT_MS] on two readings in a row, then left alone for [DRIFT_COOLDOWN_MS] (bounded
 * correction — a seek is audible, and correcting a slow device every second never converges).
 */
class PartySync(private val scope: CoroutineScope, private val player: () -> Player?) {

    /** What this device is doing in the party, for the Listen Together screen. */
    data class Status(
        val resolving: Boolean = false,
        /** The party's song isn't playable here (no matching source): this device sits it out. */
        val unavailableTitle: String? = null,
        /** Where this song is coming from on this device ("Phone", "Navidrome", "YouTube Music"…). */
        val sourceLabel: String? = null,
        /** Last measured playhead difference from the party (ms, + = ahead), while playing. */
        val driftMs: Long? = null,
        val corrections: Int = 0,
        /** Another app took the audio: not following until the user presses play. */
        val detached: Boolean = false,
    )

    private sealed interface Resolution {
        data class Ready(val song: Song) : Resolution
        data object Unavailable : Resolution
    }

    private val jobs = mutableListOf<Job>()
    private var publishJob: Job? = null
    private var startJob: Job? = null
    private val resolved = ConcurrentHashMap<String, Resolution>()
    private val resolving = ConcurrentHashMap.newKeySet<String>()
    /** Lookups that failed for a transient reason and will be retried (not a "not available"). */
    private val retrying = ConcurrentHashMap.newKeySet<String>()
    /** Party key of every song this device loaded for the party, so publishing never changes a track's identity. */
    private val keyBySongId = ConcurrentHashMap<Long, String>()

    private var reconcileQuietUntilMs = 0L
    private var awaitPlaybackSeq = Long.MAX_VALUE
    private var awaitQueueSeq = Long.MAX_VALUE
    private var lastAnchorMs = 0L
    private var lastPositionMs = 0L
    private var lastTrackId: String? = null
    private var lastIsPlaying = false
    private var loadingKey: String? = null
    private var alignedSeq = -1L
    @Volatile private var focusLost = false
    @Volatile private var rejoining = false
    private var locallyPaused = false
    private var driftStrikes = 0
    private var driftCooldownUntilMs = 0L
    private var lastDriftLogMs = 0L
    private var lastPartyCode: String? = null
    private var stash: Stash? = null
    @Volatile private var lastSampledPosition: Long? = null

    private var deferredPlayPending: Boolean
        get() = ListenTogether.awaitingStart.value
        set(value) = ListenTogether.setAwaitingStart(value)

    val inParty: Boolean get() = ListenTogether.state.value.inParty

    fun start() {
        // Read by the socket's report loop on a background thread: never touch the player there
        // (ExoPlayer is main-thread only) — hand it the value the tick last sampled.
        ListenTogether.reportedPosition = { lastSampledPosition }
        jobs += scope.launch {
            ListenTogether.state
                .map { Key(it.playback.seq, it.queue.seq, it.code, it.clockSynced) }
                .distinctUntilChanged()
                .collect { key ->
                    if (key.code != lastPartyCode) {
                        val was = lastPartyCode != null
                        lastPartyCode = key.code
                        if (!was && key.code != null) onEnteredParty() else if (was && key.code == null) onLeftParty()
                    }
                    if (key.seq >= awaitPlaybackSeq && key.queueSeq >= awaitQueueSeq) reconcileQuietUntilMs = 0L
                    reconcile()
                }
        }
        jobs += scope.launch {
            while (true) {
                delay(TICK_MS)
                lastSampledPosition = player()?.takeIf { it.isPlaying }?.currentPosition
                if (ListenTogether.state.value.inParty) ListenTogether.ensureConnected()
                reconcile()
            }
        }
    }

    fun stop() {
        jobs.forEach(Job::cancel)
        jobs.clear()
        publishJob?.cancel()
        startJob?.cancel()
        ListenTogether.reportedPosition = null
    }

    // ------------------------------------------------------------------ outbound --

    /** Set when the user replaced this player's queue (played a new list): publish it as the party's. */
    private var pendingQueueChange = false

    /**
     * The user did something to playback here — or the queue moved on by itself. Debounced.
     * [queueReplaced]: the user started a new list; only then is this player's queue published
     * (a listener's local queue holds only the songs resolved on this device, so diffing it at any
     * other time would delete friends' songs from the party).
     */
    fun onLocalIntent(queueReplaced: Boolean = false) {
        val party = ListenTogether.state.value
        if (!party.inParty || party.controlsLocked) return
        if (focusLost) {
            focusLost = false
            rejoining = true
            updateStatus { it.copy(detached = false) }
        }
        if (queueReplaced) pendingQueueChange = true
        reconcileQuietUntilMs = SystemClock.elapsedRealtime() + INTENT_QUIET_MS
        awaitPlaybackSeq = Long.MAX_VALUE
        awaitQueueSeq = Long.MAX_VALUE
        publishJob?.cancel()
        publishJob = scope.launch {
            delay(PUBLISH_DEBOUNCE_MS)
            publish()
        }
    }

    /**
     * The party's song finished on this player. Proposes the party's *next* song (not this
     * player's next item, which may skip songs not resolved here). Every device that reaches the
     * end sends the same setTrack, so two at once settle on the same song.
     */
    fun onAutoAdvance() {
        val party = ListenTogether.state.value
        if (!party.inParty || party.controlsLocked) return
        val current = party.playback.track ?: return
        val idx = partyQueueIndexOf(party.queue, party.playback, current.videoId)
        val next = party.queue.items.getOrNull(idx + 1)
        reconcileQuietUntilMs = SystemClock.elapsedRealtime() + INTENT_QUIET_MS
        awaitPlaybackSeq = party.playback.seq + 1
        awaitQueueSeq = party.queue.seq
        if (next != null) ListenTogether.setTrack(next, 0, true) else ListenTogether.pause(current.durationMs)
    }

    /** Next / previous in a party move the party (the server steps its queue). */
    fun skip(forward: Boolean): Boolean {
        val party = ListenTogether.state.value
        if (!party.inParty) return false
        if (party.controlsLocked) return true
        if (forward) ListenTogether.next() else ListenTogether.previous()
        return true
    }

    /** Whether a transport command must be refused (a listener in a host-only party). */
    fun blocksTransport(): Boolean = ListenTogether.state.value.controlsLocked

    fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) {
            if (focusLost) updateStatus { it.copy(detached = false) }
            focusLost = false
            return
        }
        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS && inParty) {
            Log.i(TAG, "another app took the audio; not following the party until asked back")
            focusLost = true
            deferredPlayPending = false
            startJob?.cancel()
            updateStatus { it.copy(detached = true) }
        }
    }

    /** Play / pause for a listener in a host-only party: moves this device only. True = handled. */
    fun onLockedTransport(playing: Boolean): Boolean {
        val party = ListenTogether.state.value
        if (!party.controlsLocked) return false
        val exo = player() ?: return false
        if (playing) {
            locallyPaused = false
            if (!party.playback.isPlaying) return true
            ListenTogether.partyPositionMs()?.takeIf { party.clockSynced }?.let(exo::seekTo)
            exo.play()
        } else {
            locallyPaused = true
            exo.pause()
        }
        return true
    }

    /** Hold a local play() back for the party's scheduled start (true = the caller must not start). */
    fun shouldDeferPlay(): Boolean {
        val party = ListenTogether.state.value
        if (!party.inParty || party.connection != ListenTogether.Connection.LIVE || !party.clockSynced) return false
        if (player()?.playWhenReady == true) return false
        deferredPlayPending = true
        startJob?.cancel()
        startJob = scope.launch {
            delay(DEFERRED_PLAY_TIMEOUT_MS)
            if (!deferredPlayPending) return@launch
            deferredPlayPending = false
            val exo = player() ?: return@launch
            if (!exo.playWhenReady) {
                Log.w(TAG, "party never acknowledged the resume; starting locally")
                exo.play()
            }
        }
        return true
    }

    /** A playback error while in a party: this song can't be played here — sit it out. */
    fun onPlaybackError(): Boolean {
        val party = ListenTogether.state.value
        if (!party.inParty) return false
        val key = party.playback.track?.videoId ?: return true
        Log.w(TAG, "playback error on the party song; sitting it out on this device")
        resolved[key] = Resolution.Unavailable
        updateStatus { it.copy(unavailableTitle = party.playback.track.title.ifBlank { "This song" }, sourceLabel = null) }
        return true
    }

    private fun publish() {
        val party = ListenTogether.state.value
        if (!party.inParty) return
        if (rejoining) {
            rejoining = false
            reconcileQuietUntilMs = 0L
            return
        }
        val exo = player() ?: return
        val currentId = exo.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val track = trackFor(currentId, exo.duration) ?: return
        val position = exo.currentPosition.coerceAtLeast(0L)
        val wantsPlaying = deferredPlayPending || exo.playWhenReady
        val basePlayback = party.playback.seq
        val baseQueue = party.queue.seq
        var playbackControls = 0
        var queueControls = 0

        val queueReplaced = pendingQueueChange || party.queue.items.isEmpty()
        pendingQueueChange = false
        if (queueReplaced) {
            // The new list, from the current song on (the party keeps 1 + 25 songs).
            val current = exo.currentMediaItemIndex.coerceAtLeast(0)
            val tracks = listOf(track) + (current + 1 until exo.mediaItemCount).asSequence()
                .mapNotNull { i -> exo.getMediaItemAt(i).mediaId.toLongOrNull()?.let { trackFor(it, 0L) } }
                .take(MAX_UPCOMING).toList()
            if (tracks.map(PartyTrack::videoId) != party.queue.items.map(PartyTrack::videoId)) {
                ListenTogether.setQueue(tracks, 0)
                queueControls++
            }
        }

        when {
            party.playback.track?.videoId != track.videoId -> { ListenTogether.setTrack(track, position, wantsPlaying); playbackControls++ }
            party.playback.isPlaying != wantsPlaying -> {
                if (wantsPlaying) ListenTogether.play(position) else ListenTogether.pause(position)
                playbackControls++
            }
            else -> {
                val partyPosition = ListenTogether.partyPositionMs()
                if (partyPosition == null || abs(position - partyPosition) > SEEK_REPORT_FLOOR_MS) {
                    ListenTogether.seek(position)
                    playbackControls++
                }
            }
        }
        awaitPlaybackSeq = basePlayback + playbackControls
        awaitQueueSeq = baseQueue + queueControls
        if (playbackControls == 0 && queueControls == 0) reconcileQuietUntilMs = 0L
    }

    /** The party track for a song on this player: the key it was loaded under, else its own identity. */
    private fun trackFor(songId: Long, playerDurationMs: Long): PartyTrack? {
        val party = ListenTogether.state.value
        keyBySongId[songId]?.let { key ->
            (party.queue.items + listOfNotNull(party.playback.track)).firstOrNull { it.videoId == key }?.let { return it }
        }
        val song = PartyLibrary.song(songId) ?: return null
        return PartyTracks.fromSong(song, playerDurationMs)?.also {
            keyBySongId[songId] = it.videoId
            // This device already plays this song from its own copy: keep that copy for the party.
            resolved.putIfAbsent(it.videoId, Resolution.Ready(song))
        }
    }

    /** A new party with nothing playing: the host hands it what this device is already playing. */
    private fun seedEmptyParty(party: ListenTogether.State) {
        if (party.connection != ListenTogether.Connection.LIVE || !party.isHost) return
        val exo = player() ?: return
        if (exo.currentMediaItem == null) return
        publish()
    }

    // ------------------------------------------------------------------- inbound --

    private fun reconcile() {
        val party = ListenTogether.state.value
        if (!party.inParty) {
            loadingKey = null
            focusLost = false
            rejoining = false
            deferredPlayPending = false
            locallyPaused = false
            lastAnchorMs = 0L; lastPositionMs = 0L; lastTrackId = null; lastIsPlaying = false
            return
        }
        if (locallyPaused && !party.controlsLocked) locallyPaused = false
        if (SystemClock.elapsedRealtime() < reconcileQuietUntilMs) return
        if (focusLost) return
        val target = party.playback
        val track = target.track ?: run { seedEmptyParty(party); return }
        val exo = player() ?: return
        if (exo.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) return

        val song = when (val r = resolved[track.videoId]) {
            is Resolution.Ready -> r.song
            Resolution.Unavailable -> {
                // Not playable here: stay quiet for this song instead of playing something else.
                if (exo.playWhenReady) exo.pause()
                deferredPlayPending = false
                val waiting = track.videoId in retrying
                updateStatus { it.copy(unavailableTitle = if (waiting) null else track.title.ifBlank { "This song" }, resolving = waiting, sourceLabel = null, driftMs = null) }
                return
            }
            null -> { resolveAsync(track); return }
        }
        if (exo.currentMediaItem?.mediaId != song.id.toString()) {
            load(party, track, song)
            return
        }
        loadingKey = null
        updateStatus { it.copy(unavailableTitle = null, resolving = false, sourceLabel = sourceOf(song)) }
        reconcileQueue(party, exo)
        if (locallyPaused) { deferredPlayPending = false; return }

        if (!target.isPlaying) {
            deferredPlayPending = false
            if (exo.playWhenReady) exo.pause()
            if (abs(exo.currentPosition - target.positionMs) > PAUSED_TOLERANCE_MS) exo.seekTo(target.positionMs)
            updateStatus { it.copy(driftMs = null) }
            return
        }
        if (!party.clockSynced) return
        val want = ListenTogether.partyPositionMs()
        if (!exo.playWhenReady) {
            val wait = ListenTogether.msUntilStart()
            if (wait > 0) {
                startJob?.cancel()
                startJob = scope.launch { delay(wait); reconcile() }
                return
            }
            if (want != null) exo.seekTo(want)
            deferredPlayPending = false
            startJob?.cancel()
            exo.play()
            alignedSeq = target.seq
            return
        }
        deferredPlayPending = false
        if (want == null) return
        if (exo.playbackState != Player.STATE_READY) { driftStrikes = 0; return }

        val drift = exo.currentPosition - want
        updateStatus { it.copy(driftMs = drift) }
        val now = SystemClock.elapsedRealtime()
        if (now - lastDriftLogMs > DRIFT_LOG_EVERY_MS) {
            lastDriftLogMs = now
            Log.i(TAG, "drift ${drift}ms rtt ${party.roundTripMs}ms seq ${target.seq}")
        }
        if (target.seq != alignedSeq) {
            val anchorChanged = lastAnchorMs != target.anchorMs || lastPositionMs != target.positionMs ||
                lastTrackId != track.videoId || lastIsPlaying != target.isPlaying
            alignedSeq = target.seq
            lastAnchorMs = target.anchorMs; lastPositionMs = target.positionMs; lastTrackId = track.videoId; lastIsPlaying = target.isPlaying
            if (anchorChanged && abs(drift) > ALIGN_TOLERANCE_MS) {
                Log.i(TAG, "aligning ${drift}ms onto party control ${target.seq}")
                exo.seekTo(want)
                return
            }
        }
        if (abs(drift) <= DRIFT_LIMIT_MS) { driftStrikes = 0; return }
        if (now < driftCooldownUntilMs) return
        if (++driftStrikes < DRIFT_STRIKES) return
        Log.i(TAG, "correcting ${drift}ms of drift against the party")
        driftStrikes = 0
        driftCooldownUntilMs = now + DRIFT_COOLDOWN_MS
        exo.seekTo(want)
        updateStatus { it.copy(corrections = it.corrections + 1) }
    }

    private fun sourceOf(song: Song): String = when {
        song.isDownloaded && !song.fileMissing -> "Downloaded on this phone"
        song.source == com.example.mimic.data.SongSource.LOCAL -> "On this phone"
        song.isOnline -> "Streaming from YouTube Music"
        song.isFromServer -> "Streaming from your Navidrome"
        else -> song.provider.label
    }

    private fun resolveAsync(track: PartyTrack) {
        if (!resolving.add(track.videoId)) return
        updateStatus { it.copy(resolving = true) }
        scope.launch {
            val outcome = runCatching { PartyTracks.resolve(track) }
            val song = outcome.getOrNull()
            if (outcome.isFailure) {
                // Couldn't look it up (offline, search failed): not a verdict — try again shortly.
                Log.i(TAG, "party song lookup failed; retrying")
                retrying += track.videoId
                resolved[track.videoId] = Resolution.Unavailable
                resolving.remove(track.videoId)
                reconcile()
                delay(RETRY_RESOLVE_MS)
                retrying -= track.videoId
                if (resolved[track.videoId] == Resolution.Unavailable) resolved.remove(track.videoId)
                reconcile()
                return@launch
            }
            if (song != null) {
                PartyLibrary.remember(song)
                keyBySongId[song.id] = track.videoId
                resolved[track.videoId] = Resolution.Ready(song)
            } else {
                Log.i(TAG, "party song not available on this device")
                resolved[track.videoId] = Resolution.Unavailable
            }
            resolving.remove(track.videoId)
            reconcile()
        }
    }

    /** The party's song (and the resolved part of its running order) onto this player. */
    private fun load(party: ListenTogether.State, track: PartyTrack, song: Song) {
        if (loadingKey == track.videoId) return
        loadingKey = track.videoId
        Log.i(TAG, "loading the party song from ${sourceOf(song)}")
        val exo = player() ?: run { loadingKey = null; return }
        val index = partyQueueIndexOf(party.queue, party.playback, track.videoId)
        val upcoming = if (index >= 0) party.queue.items.drop(index + 1).take(MAX_UPCOMING) else emptyList()
        val items = listOf(PartyTracks.mediaItem(song)) + upcoming.mapNotNull { readySong(it)?.let(PartyTracks::mediaItem) }
        val startAt = ListenTogether.partyPositionMs() ?: party.playback.positionMs
        exo.setMediaItems(items, 0, startAt)
        exo.prepare()
        if (party.playback.isPlaying && party.clockSynced && ListenTogether.msUntilStart() <= 0L && !locallyPaused) exo.play()
        updateStatus { it.copy(unavailableTitle = null, resolving = false, sourceLabel = sourceOf(song)) }
    }

    private fun readySong(t: PartyTrack): Song? = when (val r = resolved[t.videoId]) {
        is Resolution.Ready -> r.song
        Resolution.Unavailable -> null
        null -> { resolveAsync(t); null }
    }

    /** Keep the upcoming items equal to the party's (resolved ones only), without touching the current one. */
    private fun reconcileQueue(party: ListenTogether.State, exo: Player) {
        val items = party.queue.items
        if (items.isEmpty()) return
        val currentId = exo.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val key = keyBySongId[currentId] ?: return
        val partyIndex = partyQueueIndexOf(party.queue, party.playback, key)
        if (partyIndex < 0) return
        val desired = items.drop(partyIndex + 1).take(MAX_UPCOMING).mapNotNull(::readySong)
        val desiredIds = desired.map { it.id.toString() }
        val currentIndex = exo.currentMediaItemIndex
        val localIds = (currentIndex + 1 until exo.mediaItemCount).map { exo.getMediaItemAt(it).mediaId }
        if (localIds == desiredIds) return
        when {
            desiredIds.size > localIds.size && desiredIds.subList(0, localIds.size) == localIds ->
                exo.addMediaItems(desired.drop(localIds.size).map(PartyTracks::mediaItem))
            localIds.size > desiredIds.size && localIds.subList(0, desiredIds.size) == desiredIds ->
                exo.removeMediaItems(currentIndex + 1 + desiredIds.size, exo.mediaItemCount)
            else -> {
                val move = if (localIds.size == desiredIds.size) detectSingleMove(localIds, desiredIds) else null
                if (move != null) exo.moveMediaItem(currentIndex + 1 + move.fromIndex, currentIndex + 1 + move.toIndex)
                else exo.replaceMediaItems(currentIndex + 1, exo.mediaItemCount, desired.map(PartyTracks::mediaItem))
            }
        }
    }

    // ---------------------------------------------------------- party boundaries --

    private data class Stash(val items: List<MediaItem>, val index: Int, val positionMs: Long)

    private fun onEnteredParty() {
        val exo = player() ?: return
        stash = if (exo.mediaItemCount > 0) Stash((0 until exo.mediaItemCount).map(exo::getMediaItemAt), exo.currentMediaItemIndex, exo.currentPosition) else null
        resolved.clear()
        keyBySongId.clear()
        _status.value = Status()
        Log.i(TAG, "entered a party")
    }

    /** Back to the user's own queue (paused) — the party's running order was the party's. */
    private fun onLeftParty() {
        Log.i(TAG, "left the party")
        loadingKey = null
        focusLost = false
        rejoining = false
        _status.value = Status()
        val s = stash ?: return
        stash = null
        val exo = player() ?: return
        exo.pause()
        exo.setMediaItems(s.items, s.index.coerceIn(0, (s.items.size - 1).coerceAtLeast(0)), s.positionMs)
        exo.prepare()
    }

    private fun updateStatus(f: (Status) -> Status) {
        val next = f(_status.value)
        if (next != _status.value) _status.value = next
    }

    private data class Key(val seq: Long, val queueSeq: Long, val code: String?, val clockSynced: Boolean)

    companion object {
        private const val TAG = "MImicParty"
        private val _status = MutableStateFlow(Status())
        /** One playback service per process, so one status. */
        val status: StateFlow<Status> = _status.asStateFlow()

        const val TICK_MS = 700L
        /** Above two independent decoders' jitter, at the level of a real desync. */
        const val DRIFT_LIMIT_MS = 1_200L
        const val DRIFT_STRIKES = 2
        const val DRIFT_COOLDOWN_MS = 6_000L
        const val PAUSED_TOLERANCE_MS = 400L
        const val ALIGN_TOLERANCE_MS = 120L
        const val SEEK_REPORT_FLOOR_MS = 1_000L
        const val DEFERRED_PLAY_TIMEOUT_MS = 1_800L
        const val PUBLISH_DEBOUNCE_MS = 120L
        const val INTENT_QUIET_MS = 4_500L
        const val MAX_UPCOMING = 25
        private const val DRIFT_LOG_EVERY_MS = 5_000L
        private const val RETRY_RESOLVE_MS = 6_000L
    }
}

internal data class QueueMoveDelta(val fromIndex: Int, val toIndex: Int, val videoId: String)

/** If [newList] is [oldList] with exactly one item moved, that move; otherwise null. */
internal fun detectSingleMove(oldList: List<String>, newList: List<String>): QueueMoveDelta? {
    if (oldList.size != newList.size || oldList == newList || oldList.isEmpty()) return null
    if (oldList.groupingBy { it }.eachCount() != newList.groupingBy { it }.eachCount()) return null
    for (from in oldList.indices) {
        val item = oldList[from]
        val without = oldList.toMutableList().apply { removeAt(from) }
        for (to in oldList.indices) {
            if (from == to) continue
            if (without.toMutableList().apply { add(to, item) } == newList) return QueueMoveDelta(from, to, item)
        }
    }
    return null
}
