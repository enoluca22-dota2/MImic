/*
 * Part of MImic (GPL-3.0). Adapted from BitChord's shared/.../data/listentogether/PartyModels.kt
 * and ServerClock.kt (Copyright (C) BitChord contributors, GPL-3.0,
 * https://github.com/KabirSinghBhatia/BitChord). Changes: org.json instead of
 * kotlinx-serialization (MImic's standard edition has no serialization plugin), MImic track keys,
 * skip-vote fields.
 */
package com.example.mimic.party

import org.json.JSONArray
import org.json.JSONObject

/**
 * A song as the party knows it: enough to identify and show it, nothing more. How each device
 * gets the audio — its own phone file, its own Navidrome, its download, or its own online stream —
 * stays that device's business; no audio or stream URL is ever sent through the party.
 *
 * [videoId] is the party's key for the track (the server treats it as an opaque string):
 *  - an online (YouTube Music) track: its 11-character YouTube video id;
 *  - anything else: "m:" + a hash of the cleaned title / artist / length ([PartyTracks.metaKey]),
 *    which every device matches against its own library (or, in the full edition, online).
 */
data class PartyTrack(
    val videoId: String,
    val title: String = "",
    val artist: String = "",
    /** Only ever a public https artwork URL (online tracks) — never a server URL with a login token. */
    val thumbnailUrl: String? = null,
    val durationMs: Long? = null,
    val fromAutoplay: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("videoId", videoId).put("title", title).put("artist", artist)
        .apply { thumbnailUrl?.let { put("thumbnailUrl", it) }; durationMs?.let { put("durationMs", it) } }
        .put("fromAutoplay", fromAutoplay)

    companion object {
        fun from(o: JSONObject?): PartyTrack? {
            o ?: return null
            val id = o.optString("videoId").takeIf { it.isNotBlank() } ?: return null
            return PartyTrack(
                id, o.optString("title"), o.optString("artist"),
                o.optString("thumbnailUrl").takeIf { it.startsWith("https://") },
                o.optLong("durationMs", 0L).takeIf { it > 0 },
                o.optBoolean("fromAutoplay"),
            )
        }
    }
}

data class PartyMember(
    val memberId: String,
    val userId: String = "",
    val displayName: String = "",
    val avatarUrl: String? = null,
    val isHost: Boolean = false,
    /** Holding a socket right now — not whether they are still in the party. */
    val connected: Boolean = false,
    val joinedAtMs: Long = 0,
    val lastSeenMs: Long = 0,
) {
    companion object {
        fun from(o: JSONObject?): PartyMember? {
            o ?: return null
            val id = o.optString("memberId").takeIf { it.isNotBlank() } ?: return null
            return PartyMember(
                id, o.optString("userId"), o.optString("displayName"),
                o.optString("avatarUrl").takeIf { it.startsWith("https://") },
                o.optBoolean("isHost"), o.optBoolean("connected"), o.optLong("joinedAtMs"), o.optLong("lastSeenMs"),
            )
        }

        fun list(a: JSONArray?): List<PartyMember> = a?.let { (0 until it.length()).mapNotNull { i -> from(it.optJSONObject(i)) } }.orEmpty()
    }
}

/**
 * Where the party is, as of a server timestamp: [positionMs] was true at server time [anchorMs].
 * A device turns that into "where should I be now" with its measured clock offset
 * ([ListenTogether.partyPositionMs]) — so a frame delayed 300 ms still lands in the right place.
 */
data class PartyPlayback(
    val seq: Long = 0,
    val track: PartyTrack? = null,
    val queueSeq: Long = 0,
    val queueLength: Int = 0,
    val queueIndex: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val anchorMs: Long = 0,
    val effectivePositionMs: Long = 0,
    val updatedBy: String? = null,
    val startedBy: String? = null,
    val startedByName: String? = null,
    val updatedAtMs: Long = 0,
    /** MImic server: votes to skip the current track, and how many are needed (majority). */
    val skipVotes: Int = 0,
    val skipVotesNeeded: Int = 0,
    val skipVoters: List<String> = emptyList(),
) {
    companion object {
        fun from(o: JSONObject?): PartyPlayback {
            o ?: return PartyPlayback()
            val voters = o.optJSONArray("skipVoters")
            return PartyPlayback(
                seq = o.optLong("seq"),
                track = PartyTrack.from(o.optJSONObject("track")),
                queueSeq = o.optLong("queueSeq"),
                queueLength = o.optInt("queueLength"),
                queueIndex = o.optInt("queueIndex", -1),
                isPlaying = o.optBoolean("isPlaying"),
                positionMs = o.optLong("positionMs"),
                anchorMs = o.optLong("anchorMs"),
                effectivePositionMs = o.optLong("effectivePositionMs"),
                updatedBy = o.optString("updatedBy").takeIf { it.isNotBlank() && it != "null" },
                startedBy = o.optString("startedBy").takeIf { it.isNotBlank() && it != "null" },
                startedByName = o.optString("startedByName").takeIf { it.isNotBlank() && it != "null" },
                updatedAtMs = o.optLong("updatedAtMs"),
                skipVotes = o.optInt("skipVotes"),
                skipVotesNeeded = o.optInt("skipVotesNeeded"),
                skipVoters = voters?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
            )
        }
    }
}

/** The party's running order; travels separately from [PartyPlayback] (only its seq rides along). */
data class PartyQueue(val seq: Long = 0, val index: Int = -1, val items: List<PartyTrack> = emptyList()) {
    companion object {
        fun from(o: JSONObject?): PartyQueue {
            o ?: return PartyQueue()
            val a = o.optJSONArray("items")
            return PartyQueue(o.optLong("seq"), o.optInt("index", -1), a?.let { (0 until it.length()).mapNotNull { i -> PartyTrack.from(it.optJSONObject(i)) } }.orEmpty())
        }
    }
}

data class PartySnapshot(
    val code: String = "",
    val maxMembers: Int = 5,
    val hostOnlyControl: Boolean = false,
    val members: List<PartyMember> = emptyList(),
    val playback: PartyPlayback = PartyPlayback(),
    val queue: PartyQueue = PartyQueue(),
) {
    companion object {
        fun from(o: JSONObject?): PartySnapshot {
            o ?: return PartySnapshot()
            return PartySnapshot(
                o.optString("code"), o.optInt("maxMembers", 5), o.optBoolean("hostOnlyControl"),
                PartyMember.list(o.optJSONArray("members")), PartyPlayback.from(o.optJSONObject("playback")), PartyQueue.from(o.optJSONObject("queue")),
            )
        }
    }
}

data class PartyPreview(
    val code: String = "",
    val hostName: String = "",
    val memberCount: Int = 0,
    val maxMembers: Int = 5,
    val isFull: Boolean = false,
    val members: List<PartyMember> = emptyList(),
) {
    companion object {
        fun from(o: JSONObject): PartyPreview = PartyPreview(
            o.optString("code"), o.optString("hostName"), o.optInt("memberCount"), o.optInt("maxMembers", 5), o.optBoolean("isFull"),
            o.optJSONArray("members")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                    PartyMember("", "", it.optString("displayName"), it.optString("avatarUrl").takeIf { u -> u.startsWith("https://") }, it.optBoolean("isHost"))
                }
            }.orEmpty(),
        )
    }
}

/** The answer to a create / join: the code, and this device's key to it (never shown, never logged). */
data class PartyMembership(val code: String, val token: String, val you: PartyMember, val party: PartySnapshot) {
    companion object {
        fun from(o: JSONObject): PartyMembership = PartyMembership(
            o.getString("code"), o.getString("token"), PartyMember.from(o.optJSONObject("you")) ?: error("no member"), PartySnapshot.from(o.optJSONObject("party")),
        )
    }
}

/** One recent party action, kept for this app session only. */
data class PartyActivity(val action: String, val by: String, val atMs: Long, val detail: String = "")

/**
 * This device's offset from the party server's clock — NTP's method cut down to one WebSocket:
 * `offset = serverMs - (t0 + t1) / 2`, error at most half the round trip, so the sample with the
 * *smallest* round trip (within the last two minutes) wins. Local readings are monotonic
 * (elapsedRealtime), never the wall clock, so a time-zone or network time change can't move the
 * playhead.
 */
class ServerClock(private val localNowMs: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private data class Sample(val offsetMs: Long, val roundTripMs: Long, val takenAtMs: Long)
    private val samples = ArrayDeque<Sample>()

    @Volatile var offsetMs: Long? = null
        private set
    @Volatile var roundTripMs: Long = 0
        private set
    val synced: Boolean get() = offsetMs != null

    @Synchronized
    fun record(sentAtLocalMs: Long, serverMs: Long, receivedAtLocalMs: Long) {
        val roundTrip = (receivedAtLocalMs - sentAtLocalMs).coerceAtLeast(0)
        val midpoint = sentAtLocalMs + roundTrip / 2
        samples.addLast(Sample(serverMs - midpoint, roundTrip, receivedAtLocalMs))
        while (samples.size > WINDOW) samples.removeFirst()
        val cutoff = receivedAtLocalMs - SAMPLE_TTL_MS
        val usable = samples.filter { it.takenAtMs >= cutoff }.ifEmpty { samples.toList() }
        val best = usable.minBy { it.roundTripMs }
        offsetMs = best.offsetMs
        roundTripMs = best.roundTripMs
    }

    fun serverNowMs(): Long? = offsetMs?.let { localNowMs() + it }

    @Synchronized
    fun reset() {
        samples.clear()
        offsetMs = null
        roundTripMs = 0
    }

    fun nowMs(): Long = localNowMs()

    private companion object {
        const val WINDOW = 12
        const val SAMPLE_TTL_MS = 120_000L
    }
}

/** Index of [videoId] in [queue] nearest the party's own index (the queue may hold a track twice). */
fun partyQueueIndexOf(queue: PartyQueue, playback: PartyPlayback, videoId: String?): Int {
    if (videoId == null) return -1
    val items = queue.items
    val around = if (playback.queueIndex in items.indices) playback.queueIndex else queue.index
    if (around in items.indices && items[around].videoId == videoId) return around
    var best = -1
    var bestDistance = Int.MAX_VALUE
    items.forEachIndexed { i, t ->
        if (t.videoId == videoId) {
            val d = kotlin.math.abs(i - around)
            if (d < bestDistance) { best = i; bestDistance = d }
        }
    }
    return best
}
