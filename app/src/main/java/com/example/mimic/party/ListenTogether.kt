/*
 * Part of MImic (GPL-3.0). Adapted from BitChord's app/.../data/listentogether/ListenTogether.kt
 * (Copyright (C) BitChord contributors, GPL-3.0, https://github.com/KabirSinghBhatia/BitChord).
 * Changes for MImic: OkHttp WebSocket instead of Ktor (both editions), one user-chosen server
 * (no built-in default / fallback), identity from the optional MImic account or a nickname
 * (no YouTube sign-in required), membership kept across a process restart, end party, skip votes.
 */
package com.example.mimic.party

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Listen Together (MImic 4.5): one party, shared by up to ten devices, each playing the music
 * from its own authorized source. This object is the client half — membership, the socket, the
 * clock and the controls. It does not touch the player: [com.example.mimic.player.PartySync]
 * (inside the playback service, so a party survives the screen going off) binds it to Media3.
 *
 * How it stays in time: the server holds "position P was true at server time A". This device
 * measures its offset from the server's clock ([ServerClock]) and computes where it should be
 * right now ([partyPositionMs]); a frame that arrives late carries an older anchor and still lands
 * in the right place. Every state carries a sequence number; anything not newer than what was
 * applied is dropped, so two people pressing pause together settle instead of fighting. The
 * server re-states the truth every 5 s, and a dropped socket reconnects with jittered backoff —
 * the server keeps the slot for 45 s, so a tunnel or a screen-off is invisible to the others.
 *
 * Security: the server mints a secret token on join; it is required for every call afterwards
 * (Authorization header, never in a URL), stored in app-private prefs, and never shown or logged.
 * A device that doesn't hold the token for a party can't act in it.
 */
object ListenTogether {

    enum class Connection { OFFLINE, CONNECTING, LIVE }

    data class State(
        val code: String? = null,
        val serverUrl: String? = null,
        val you: PartyMember? = null,
        val members: List<PartyMember> = emptyList(),
        val maxMembers: Int = 5,
        val hostOnlyControl: Boolean = false,
        val playback: PartyPlayback = PartyPlayback(),
        val queue: PartyQueue = PartyQueue(),
        val connection: Connection = Connection.OFFLINE,
        /** False until the first round trip — the playhead is a guess until then. */
        val clockSynced: Boolean = false,
        val roundTripMs: Long = 0,
        val reconnects: Int = 0,
        /** The last thing that went wrong, for the screen to show. */
        val error: String? = null,
        /** Why the last party ended for this device ("ended" by the host, "kicked", "left"). */
        val endedReason: String? = null,
    ) {
        val inParty: Boolean get() = code != null
        val isHost: Boolean get() = you?.isHost == true
        /** A listener in a party whose host took control. The server enforces the same rule. */
        val controlsLocked: Boolean get() = inParty && hostOnlyControl && !isHost
        val youVotedSkip: Boolean get() = you != null && you.memberId in playback.skipVoters
    }

    class PartyException(val code: String, message: String, val statusCode: Int? = null) : Exception(message)

    private const val TAG = "MImicParty"
    const val CODE_LENGTH = 6
    private const val PREFS = "mimic_listen_together"
    private const val KEY_SERVER = "server_url"
    private const val KEY_CODE = "party_code"
    private const val KEY_TOKEN = "party_token"
    private const val KEY_PARTY_SERVER = "party_server"
    private const val KEY_DEVICE = "device_id"
    private const val KEY_LOCAL_USER = "local_user_id"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_KICKED = "kicked_until"
    private const val KICK_BLOCK_MS = 24L * 60 * 60 * 1000
    private const val PING_INTERVAL_MS = 15_000L
    private const val REPORT_INTERVAL_MS = 10_000L
    private const val UNREACHABLE = "Couldn't reach the party server."
    private val JSON = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        // A party can sit paused for minutes; the socket isn't in trouble. OkHttp's own pings and
        // the server's heartbeat say whether it is.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val shortHttp = http.newBuilder().readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clock = ServerClock(SystemClock::elapsedRealtime)
    private val switchMutex = Mutex()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _activity = MutableStateFlow<List<PartyActivity>>(emptyList())
    val activity: StateFlow<List<PartyActivity>> = _activity.asStateFlow()

    private val _serverUrl = MutableStateFlow("")
    /** The party server this install uses ("" = not set yet). */
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _awaitingStart = MutableStateFlow(false)
    /** A resume accepted but waiting for the party's scheduled start (see PartySync). */
    val awaitingStart: StateFlow<Boolean> = _awaitingStart.asStateFlow()
    fun setAwaitingStart(value: Boolean) { _awaitingStart.value = value }

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    @Volatile private var token: String? = null
    @Volatile private var partyBase: String? = null
    @Volatile private var socket: WebSocket? = null
    private var socketJob: Job? = null

    fun init(context: Context) {
        if (prefs != null) return
        appContext = context.applicationContext
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _serverUrl.value = p.getString(KEY_SERVER, null).orEmpty()
        // A party outlives a process restart (Android killed the app, an update installed): if
        // this device still holds a membership, ask the server whether it's still good and rejoin.
        val code = p.getString(KEY_CODE, null)
        val held = p.getString(KEY_TOKEN, null)
        val base = p.getString(KEY_PARTY_SERVER, null)
        if (!code.isNullOrBlank() && !held.isNullOrBlank() && !base.isNullOrBlank()) scope.launch { restore(base, code, held) }
    }

    private suspend fun restore(base: String, code: String, held: String) = switchMutex.withLock {
        val snapshot = runCatching {
            shortHttp.newCall(Request.Builder().url("$base/api/parties/$code").header("Authorization", "Bearer $held").build()).execute().use { r ->
                if (r.code == 401 || r.code == 404) null else if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}") else JSONObject(r.body?.string().orEmpty())
            }
        }
        if (snapshot.isSuccess && snapshot.getOrNull() == null) {
            // The party ended or let this device go while it was away.
            clearMembership()
            return@withLock
        }
        val party = snapshot.getOrNull()?.let { PartySnapshot.from(it.optJSONObject("party") ?: it) }
        token = held
        partyBase = base
        clock.reset()
        _state.value = State(
            code = code, serverUrl = base,
            you = party?.members?.firstOrNull { it.userId == identityUserId() },
            members = party?.members.orEmpty(), maxMembers = party?.maxMembers ?: 5,
            hostOnlyControl = party?.hostOnlyControl ?: false,
            playback = party?.playback ?: PartyPlayback(), queue = party?.queue ?: PartyQueue(),
            connection = Connection.CONNECTING,
        )
        connect()
    }

    // ------------------------------------------------------------------ server address --

    /**
     * Normalises what the user typed: no scheme → http:// (a Tailscale / LAN address); a path,
     * query or fragment is refused. Null when it isn't an address.
     */
    fun normalizeServerUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val candidate = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        if (!uri.rawQuery.isNullOrEmpty() || !uri.rawFragment.isNullOrEmpty()) return null
        if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return null
        if (uri.port != -1 && uri.port !in 1..65535) return null
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host.lowercase()
        return "$scheme://$h" + (if (uri.port != -1) ":${uri.port}" else "")
    }

    fun setServerUrl(normalized: String) {
        prefs?.edit()?.putString(KEY_SERVER, normalized)?.apply()
        _serverUrl.value = normalized
    }

    /** Round-trip time to [base]'s health check, or the failure. */
    suspend fun checkServer(base: String): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val start = SystemClock.elapsedRealtime()
            shortHttp.newCall(Request.Builder().url("$base/healthz").build()).execute().use { r ->
                if (!r.isSuccessful || !r.body?.string().orEmpty().contains("\"ok\":true")) throw PartyException("not_party_server", "That address isn't a MImic party server.")
            }
            SystemClock.elapsedRealtime() - start
        }.recoverCatching { throw if (it is PartyException) it else PartyException("unreachable", UNREACHABLE) }
    }

    // ------------------------------------------------------------------------ identity --

    fun nickname(): String = prefs?.getString(KEY_NICKNAME, null).orEmpty()
    fun setNickname(value: String) { prefs?.edit()?.putString(KEY_NICKNAME, value.trim().take(40))?.apply() }

    data class Identity(val userId: String, val deviceId: String, val name: String, val avatar: String?, val fromAccount: Boolean)

    /**
     * Who this device is in a party, or null when it has no name yet. Uses the MImic account
     * (Google) when signed in — name and photo — otherwise the nickname typed here; no separate
     * account is needed. [Identity.userId] is a hash, never the account id itself. The server can't
     * verify a name (it holds no credentials); what it guarantees is that only token holders act.
     */
    fun identity(): Identity? {
        val account = runCatching {
            (appContext as? com.example.mimic.MimicApplication)?.container?.mimicAccount?.state?.value
                as? com.example.mimic.data.account.MimicAccount.State.SignedIn
        }.getOrNull()
        val name = nickname().ifBlank { account?.name.orEmpty() }.trim().take(40)
        if (name.isBlank()) return null
        return Identity(identityUserId(account?.uid), deviceId(), name, account?.photoUrl?.takeIf { it.startsWith("https://") }, account != null)
    }

    private fun identityUserId(accountUid: String? = runCatching {
        ((appContext as? com.example.mimic.MimicApplication)?.container?.mimicAccount?.state?.value
            as? com.example.mimic.data.account.MimicAccount.State.SignedIn)?.uid
    }.getOrNull()): String {
        val base = accountUid?.let { "account:$it" } ?: ("local:" + (prefs?.getString(KEY_LOCAL_USER, null) ?: UUID.randomUUID().toString().also { prefs?.edit()?.putString(KEY_LOCAL_USER, it)?.apply() }))
        return sha256(base).take(32)
    }

    private fun deviceId(): String =
        prefs?.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also { prefs?.edit()?.putString(KEY_DEVICE, it)?.apply() }

    // ------------------------------------------------------------------------- joining --

    suspend fun createParty(maxMembers: Int = 10): Result<String> = switchMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val base = _serverUrl.value.ifBlank { throw PartyException("no_server", "Set the party server address first.") }
                val who = identity() ?: throw PartyException("no_name", "Choose the name your friends will see first.")
                if (_state.value.inParty) leaveLocked()
                val m = post("$base/api/parties", joinBody(who).put("maxMembers", maxMembers))
                commit(base, m)
                m.code
            }.onFailure { fail(it) }
        }
    }

    /** Joins [code] on [server] (an invite names its server; a typed code uses this device's). */
    suspend fun joinParty(code: String, server: String? = null): Result<String> = switchMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val base = (server?.let(::normalizeServerUrl) ?: _serverUrl.value).ifBlank { throw PartyException("no_server", "Set the party server address first.") }
                val who = identity() ?: throw PartyException("no_name", "Choose the name your friends will see first.")
                val cleaned = cleanCode(code) ?: throw PartyException("bad_code", "A party code is six letters or digits.")
                if (recentKicks().containsKey(cleaned)) throw PartyException("recently_kicked", "The host removed you from this party.")
                val st = _state.value
                if (st.inParty && st.code == cleaned && partyBase == base) return@runCatching cleaned
                // Join the new party first; only then let go of the old one.
                val m = post("$base/api/parties/$cleaned/join", joinBody(who))
                if (st.inParty) leaveLocked()
                if (server != null && _serverUrl.value.isBlank()) setServerUrl(base)
                commit(base, m)
                m.code
            }.onFailure { fail(it) }
        }
    }

    suspend fun preview(code: String, server: String? = null): Result<PartyPreview> = withContext(Dispatchers.IO) {
        runCatching {
            val base = (server?.let(::normalizeServerUrl) ?: _serverUrl.value).ifBlank { throw PartyException("no_server", "Set the party server address first.") }
            val cleaned = cleanCode(code) ?: throw PartyException("bad_code", "A party code is six letters or digits.")
            shortHttp.newCall(Request.Builder().url("$base/api/parties/$cleaned/preview").build()).execute().use { r ->
                if (!r.isSuccessful) throw problem(r)
                PartyPreview.from(JSONObject(r.body?.string().orEmpty()))
            }
        }.recoverCatching { throw if (it is PartyException) it else PartyException("unreachable", UNREACHABLE) }
    }

    /** Give up this device's slot; the party carries on without it. */
    suspend fun leaveParty() = switchMutex.withLock { withContext(Dispatchers.IO) { leaveLocked(); _state.update { it.copy(endedReason = "left") } } }

    /** Host only: ends the party for everyone (the server answers with "bye: ended"). */
    fun endParty() = control("endParty") {}

    private fun leaveLocked() {
        val code = _state.value.code
        val held = token
        val base = partyBase
        stopSocket()
        clearMembership()
        if (code != null && held != null && base != null) {
            scope.launch {
                runCatching {
                    shortHttp.newCall(Request.Builder().url("$base/api/parties/$code/leave").header("Authorization", "Bearer $held").post(ByteArray(0).toRequestBody()).build()).execute().close()
                }
            }
        }
    }

    private fun clearMembership() {
        token = null
        partyBase = null
        clock.reset()
        prefs?.edit()?.remove(KEY_CODE)?.remove(KEY_TOKEN)?.remove(KEY_PARTY_SERVER)?.apply()
        _awaitingStart.value = false
        _state.value = State()
    }

    private fun commit(base: String, m: PartyMembership) {
        partyBase = base
        token = m.token
        prefs?.edit()?.putString(KEY_CODE, m.code)?.putString(KEY_TOKEN, m.token)?.putString(KEY_PARTY_SERVER, base)?.apply()
        clock.reset()
        _state.value = State(
            code = m.code, serverUrl = base, you = m.you, members = m.party.members, maxMembers = m.party.maxMembers,
            hostOnlyControl = m.party.hostOnlyControl, playback = m.party.playback, queue = m.party.queue,
            connection = Connection.CONNECTING,
        )
        connect()
    }

    private fun fail(t: Throwable) {
        val msg = (t as? PartyException)?.message ?: UNREACHABLE
        Log.w(TAG, "party request failed: ${(t as? PartyException)?.code ?: t.javaClass.simpleName}")
        _state.update { it.copy(error = msg) }
    }

    fun clearError() = _state.update { it.copy(error = null, endedReason = null) }

    /** Re-open the socket for a membership that has none (process restart, the service restarted). */
    fun ensureConnected() {
        if (_state.value.code == null || token == null) return
        if (socketJob?.isActive == true) return
        connect()
    }

    // ------------------------------------------------------------------------ controls --

    fun play(positionMs: Long? = null) = control("play") { positionMs?.let { put("positionMs", it) } }
    fun pause(positionMs: Long? = null) = control("pause") { positionMs?.let { put("positionMs", it) } }
    fun seek(positionMs: Long) = control("seek") { put("positionMs", positionMs) }
    fun next() = control("next") {}
    fun previous() = control("previous") {}
    fun voteSkip() = control("voteSkip") {}
    fun setTrack(track: PartyTrack, positionMs: Long = 0, isPlaying: Boolean = true) =
        control("setTrack") { put("track", track.toJson()); put("positionMs", positionMs); put("isPlaying", isPlaying) }
    fun setQueue(queue: List<PartyTrack>, index: Int) =
        control("setQueue") { put("queue", JSONArray(queue.map { it.toJson() })); put("queueIndex", index) }
    fun queueAdd(tracks: List<PartyTrack>, playNext: Boolean = false) =
        control("queueAdd") { put("tracks", JSONArray(tracks.map { it.toJson() })); put("playNext", playNext) }
    fun queueRemove(videoId: String) = control("queueRemove") { put("videoId", videoId) }
    fun queueMove(fromIndex: Int, toIndex: Int, videoId: String?) =
        control("queueMove") { put("fromIndex", fromIndex); put("toIndex", toIndex); videoId?.let { put("videoId", it) } }
    fun kick(memberId: String) = control("kick") { put("memberId", memberId) }
    fun setHostOnlyControl(enabled: Boolean) = control("setHostOnlyControl") { put("enabled", enabled) }

    private fun control(action: String, body: JSONObject.() -> Unit) {
        send(JSONObject().put("type", "control").put("action", action).apply(body))
    }

    private fun send(frame: JSONObject): Boolean = socket?.send(frame.toString()) == true

    // ----------------------------------------------------------------------- playhead --

    /** Where this device should be in the current track right now, or null outside a party. */
    fun partyPositionMs(): Long? {
        val playback = _state.value.playback
        val track = playback.track ?: return null
        if (!playback.isPlaying) return playback.positionMs
        val serverNow = clock.serverNowMs() ?: return playback.effectivePositionMs
        val position = playback.positionMs + (serverNow - playback.anchorMs).coerceAtLeast(0)
        val duration = track.durationMs
        return if (duration != null) minOf(position, duration) else position
    }

    /** How far in the future the party's next resume is scheduled (0 if already under way). */
    fun msUntilStart(): Long {
        val playback = _state.value.playback
        if (!playback.isPlaying) return 0
        val serverNow = clock.serverNowMs() ?: return 0
        return (playback.anchorMs - serverNow).coerceAtLeast(0)
    }

    // -------------------------------------------------------------------------- socket --

    private fun connect() {
        socketJob?.cancel()
        socketJob = scope.launch { socketLoop() }
    }

    private fun stopSocket() {
        socketJob?.cancel()
        socketJob = null
        socket?.close(1000, "left")
        socket = null
    }

    /**
     * Connect, pump frames until the connection goes away, back off (jittered, up to 30 s), go
     * round again. A backgrounded phone loses its socket many times in a listening session;
     * every one of those has to heal on its own.
     */
    private suspend fun socketLoop() {
        var backoffMs = 1_000L
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val code = _state.value.code ?: return
            val held = token ?: return
            val base = partyBase ?: return
            _state.update { it.copy(connection = Connection.CONNECTING) }
            val closed = CompletableDeferred<Unit>()
            val wsUrl = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws/parties/$code"
            var pumps: List<Job> = emptyList()
            val ws = http.newWebSocket(
                Request.Builder().url(wsUrl).header("Authorization", "Bearer $held").build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        socket = webSocket
                        backoffMs = 1_000L
                        _state.update { it.copy(connection = Connection.LIVE, error = null) }
                        pumps = listOf(scope.launch { pingLoop(webSocket) }, scope.launch { reportLoop(webSocket) })
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) = onFrame(text)
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        // 401 / 404 at the handshake: this membership is gone for good.
                        if (response?.code == 401 || response?.code == 404) {
                            scope.launch { switchMutex.withLock { clearMembership(); _state.update { it.copy(endedReason = "gone", error = "That party has ended.") } } }
                        }
                        Log.w(TAG, "party socket dropped (${t.javaClass.simpleName}${response?.let { ", HTTP ${it.code}" } ?: ""})")
                        closed.complete(Unit)
                    }
                },
            )
            try {
                closed.await()
            } finally {
                pumps.forEach(Job::cancel)
                if (socket === ws) socket = null
                ws.cancel()
            }
            if (_state.value.code == null) return
            // The clock isn't trusted across a gap: re-measured after reconnecting.
            clock.reset()
            _state.update { it.copy(connection = Connection.CONNECTING, clockSynced = false, reconnects = it.reconnects + 1) }
            delay(backoffMs / 2 + Random.nextLong(backoffMs + 1))
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    /** A burst of pings on arrival (the best of several round trips is the offset), then a trickle. */
    private suspend fun pingLoop(ws: WebSocket) {
        repeat(5) { ping(ws); delay(300) }
        while (true) { delay(PING_INTERVAL_MS); ping(ws) }
    }

    private fun ping(ws: WebSocket) {
        ws.send(JSONObject().put("type", "ping").put("clientMs", clock.nowMs()).toString())
    }

    /** Tells the server where this device actually is (logged there as drift; never decides anything). */
    private suspend fun reportLoop(ws: WebSocket) {
        while (true) {
            delay(REPORT_INTERVAL_MS)
            val pos = reportedPosition?.invoke() ?: continue
            ws.send(JSONObject().put("type", "report").put("positionMs", pos).put("isPlaying", _state.value.playback.isPlaying).toString())
        }
    }

    /** Set by PartySync: this device's real playhead in the party's current track. */
    @Volatile var reportedPosition: (() -> Long?)? = null

    private fun onFrame(text: String) {
        val received = clock.nowMs()
        val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (frame.optString("type")) {
            "welcome" -> {
                val party = PartySnapshot.from(frame.optJSONObject("party"))
                val you = PartyMember.from(frame.optJSONObject("you"))
                _state.update {
                    it.copy(
                        code = party.code.ifBlank { it.code }, you = you ?: it.you, members = party.members, maxMembers = party.maxMembers,
                        hostOnlyControl = party.hostOnlyControl, playback = party.playback, queue = party.queue,
                        connection = Connection.LIVE, error = null,
                    )
                }
            }
            "pong" -> {
                val sentAt = frame.optLong("clientMs", -1).takeIf { it >= 0 } ?: return
                val serverMs = frame.optLong("serverMs", -1).takeIf { it >= 0 } ?: return
                clock.record(sentAt, serverMs, received)
                _state.update { it.copy(clockSynced = clock.synced, roundTripMs = clock.roundTripMs) }
            }
            "state" -> {
                val playback = PartyPlayback.from(frame.optJSONObject("playback"))
                // Older than what's applied: says nothing (keeps simultaneous controls from oscillating).
                if (playback.seq < _state.value.playback.seq) return
                _state.update { it.copy(playback = playback) }
                // A missed queue frame is noticed on the next heartbeat and healed.
                if (playback.queueSeq != _state.value.queue.seq) send(JSONObject().put("type", "syncQueue"))
            }
            "queue" -> {
                val queue = PartyQueue.from(frame.optJSONObject("queue"))
                if (queue.seq < _state.value.queue.seq) return
                _state.update { it.copy(queue = queue) }
            }
            "members" -> {
                val members = PartyMember.list(frame.optJSONArray("members"))
                _state.update { cur ->
                    cur.copy(
                        members = members,
                        maxMembers = frame.optInt("maxMembers", cur.maxMembers),
                        hostOnlyControl = if (frame.has("hostOnlyControl")) frame.optBoolean("hostOnlyControl") else cur.hostOnlyControl,
                        // The only place a promotion to host is announced.
                        you = cur.you?.let { mine -> members.firstOrNull { it.memberId == mine.memberId } } ?: cur.you,
                    )
                }
            }
            "activity" -> {
                val action = frame.optString("action").takeIf { it.isNotBlank() } ?: return
                val by = frame.optString("by").takeIf { it.isNotBlank() } ?: return
                _activity.value = (listOf(PartyActivity(action, by, frame.optLong("atMs", System.currentTimeMillis()), frame.optString("detail"))) + _activity.value).take(50)
            }
            "error" -> {
                val reason = frame.optString("error")
                Log.w(TAG, "party server refused a frame: $reason")
                _state.update { it.copy(error = frame.optString("message").ifBlank { null }) }
                if (reason == "bad_token" || reason == "no_such_party") scope.launch { switchMutex.withLock { leaveLocked() } }
            }
            "bye" -> {
                val reason = frame.optString("reason")
                if (reason == "kicked") _state.value.code?.let(::recordKick)
                scope.launch {
                    switchMutex.withLock {
                        stopSocket()
                        clearMembership()
                        _state.update {
                            it.copy(
                                endedReason = reason,
                                error = when (reason) {
                                    "ended" -> "The host ended the party."
                                    "kicked" -> "The host removed you from the party."
                                    else -> null
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------ plumbing --

    private fun joinBody(who: Identity) = JSONObject().put("userId", who.userId).put("deviceId", who.deviceId).put("displayName", who.name)
        .apply { who.avatar?.let { put("avatarUrl", it) } }

    private fun post(url: String, body: JSONObject): PartyMembership =
        try {
            shortHttp.newCall(Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).build()).execute().use { r ->
                if (!r.isSuccessful) throw problem(r)
                PartyMembership.from(JSONObject(r.body?.string().orEmpty()))
            }
        } catch (e: PartyException) {
            throw e
        } catch (e: Exception) {
            throw PartyException("unreachable", UNREACHABLE)
        }

    private fun problem(r: Response): PartyException {
        val o = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull()
        val code = o?.optString("error").orEmpty().ifBlank { "http_${r.code}" }
        val msg = o?.optString("message")?.takeIf { it.isNotBlank() } ?: when (r.code) {
            404 -> "There's no party with that code."
            409 -> "That party is full."
            429 -> "Too many parties created from this network. Try again in a minute."
            else -> "The party server said ${r.code}."
        }
        return PartyException(code, msg, r.code)
    }

    fun cleanCode(raw: String): String? =
        raw.filter { it.isLetterOrDigit() }.uppercase().takeIf { it.length == CODE_LENGTH }

    private fun recentKicks(): Map<String, Long> {
        val raw = prefs?.getString(KEY_KICKED, null) ?: return emptyMap()
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val now = System.currentTimeMillis()
        return o.keys().asSequence().associateWith { o.optLong(it) }.filterValues { it > now }
    }

    private fun recordKick(code: String) {
        val all = recentKicks() + (code to System.currentTimeMillis() + KICK_BLOCK_MS)
        prefs?.edit()?.putString(KEY_KICKED, JSONObject(all as Map<*, *>).toString())?.apply()
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
