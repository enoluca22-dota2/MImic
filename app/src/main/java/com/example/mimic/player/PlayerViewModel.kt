package com.example.mimic.player

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.mimic.AppContainer
import com.example.mimic.MimicApplication
import com.example.mimic.data.AppSettings
import com.example.mimic.data.Song
import com.example.mimic.data.server.RemoteAlbum
import com.example.mimic.data.server.RemoteSong
import com.example.mimic.data.server.ServerCredentials
import com.example.mimic.data.server.ServerStatus
import com.example.mimic.data.server.SubsonicClient
import com.example.mimic.data.server.remoteStableId
import com.example.mimic.data.toDefinition
import com.example.mimic.data.toEntity
import com.example.mimic.data.db.DownloadEntity
import java.io.File
import com.example.mimic.data.db.FavoriteEntity
import com.example.mimic.data.db.PlaylistEntity
import com.example.mimic.data.db.PlaylistSongEntity
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayerUiState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val isCurrentFavorite: Boolean = false,
    /** Non-null when the last play attempt failed; cleared when a new item starts loading. */
    val playbackError: String? = null,
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val container: AppContainer = (app as MimicApplication).container
    private val repository = container.musicRepository
    private val favoriteDao = container.favoriteDao
    private val historyDao = container.historyDao
    private val playlistDao = container.playlistDao
    private val settingsRepo = container.settingsRepository

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    // ---- Derived library views, backed by Room + the scanned song list ----

    val favoriteIds: StateFlow<Set<Long>> = favoriteDao.favoriteIds()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val favoriteSongs: StateFlow<List<Song>> =
        combine(favoriteDao.favoriteIds(), _songs) { ids, songs ->
            val byId = songs.associateBy { it.id }
            ids.mapNotNull { byId[it] }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentlyPlayed: StateFlow<List<Song>> =
        combine(historyDao.recentlyPlayedIds(20), _songs) { ids, songs ->
            val byId = songs.associateBy { it.id }
            ids.mapNotNull { byId[it] }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mostPlayed: StateFlow<List<Song>> =
        combine(historyDao.mostPlayed(20), _songs) { counts, songs ->
            val byId = songs.associateBy { it.id }
            counts.mapNotNull { byId[it.songId] }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentlyAdded: StateFlow<List<Song>> = _songs
        .map { com.example.mimic.data.LibraryQueries.recentlyAdded(it, 20) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** "Suggested": songs sharing an artist with the user's most-played, excluding those. */
    val suggested: StateFlow<List<Song>> =
        combine(historyDao.mostPlayed(30), _songs) { counts, songs ->
            com.example.mimic.data.LibraryQueries.suggested(songs, counts, 20)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- Smart collections (generated from local library + play history; no server) ----

    val neverPlayed: StateFlow<List<Song>> =
        combine(historyDao.playedSongIds(), _songs) { playedIds, songs ->
            com.example.mimic.data.SmartCollections.neverPlayed(songs, playedIds.toSet(), 50)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val leastPlayed: StateFlow<List<Song>> =
        combine(historyDao.leastPlayed(50), _songs) { counts, songs ->
            com.example.mimic.data.SmartCollections.leastPlayed(songs, counts, 50)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val forgottenFavorites: StateFlow<List<Song>> =
        combine(favoriteDao.favoriteIds(), historyDao.recentlyPlayedIds(30), _songs) { favs, recent, songs ->
            com.example.mimic.data.SmartCollections.forgottenFavorites(songs, favs.toSet(), recent.toSet(), 50)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Reproducible for the calendar day so it doesn't reshuffle on every recomposition. */
    val randomDiscovery: StateFlow<List<Song>> = _songs
        .map { com.example.mimic.data.SmartCollections.randomDiscovery(it, daySeed(), 50) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun daySeed(): Long = System.currentTimeMillis() / 86_400_000L

    val playlists: StateFlow<List<PlaylistEntity>> = playlistDao.playlists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var songsById: Map<Long, Song> = emptyMap()
    private var sleepJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // A new item started loading — clear any stale error from the previous track.
            _uiState.update { it.copy(playbackError = null) }
            mediaItem?.mediaId?.toLongOrNull()?.let { recordPlay(it) }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // Classify without leaking the stream URL (which carries the auth token).
            val reason = when (error.errorCode) {
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                    "Can't reach the server. Check your connection (or Tailscale) and try again."
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                    "Server rejected the request. Try signing in again."
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                    "Track not found on the server."
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                    // The server returned something that isn't playable audio — almost always a
                    // missing/renamed file on the server (Navidrome sends a 200 + JSON error body).
                    "This track isn't playable — it may be missing on the server. Try another, or rescan your library."
                else -> "Playback error (${error.errorCodeName})."
            }
            android.util.Log.w("MImic", "Playback error ${error.errorCodeName}", error)
            _uiState.update { it.copy(isBuffering = false, playbackError = reason) }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            syncFromPlayer(player)
            rebuildQueue(player)
        }
    }

    fun initController() {
        if (controller != null || controllerFuture != null) return
        val token = SessionToken(
            getApplication(),
            ComponentName(getApplication(), PlaybackService::class.java),
        )
        val future = MediaController.Builder(getApplication(), token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = future.get().also { it.addListener(listener) }
            syncFromPlayer(controller!!)
            rebuildQueue(controller!!)
            startPositionUpdates()
        }, MoreExecutors.directExecutor())
    }

    fun loadLibrary() {
        viewModelScope.launch {
            _loading.value = true
            val list = repository.loadSongs()
            _songs.value = list
            songsById = list.associateBy { it.id }
            _loading.value = false
        }
    }

    // ---- Playback ----

    fun play(queue: List<Song>, index: Int) {
        val c = controller ?: return
        // Remote/downloaded songs aren't in the scanned library, so register them for
        // current-song/queue resolution.
        songsById = songsById + queue.associateBy { it.id }
        _uiState.update { it.copy(playbackError = null) }
        c.setMediaItems(queue.map { it.toMediaItem() }, index, 0L)
        c.prepare()
        c.play()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPreviousMediaItem()

    /**
     * Stop & close: halt playback, clear the queue, and reset UI state. This empties the
     * MediaSession so Android removes the media notification / system media controls (the OS may
     * still keep a "recently used" card briefly — that surface is owned by the system, not MImic).
     * Clearing currentSong also dismisses the full-screen player and hides the mini-player.
     */
    fun stopPlayback() {
        val c = controller ?: return
        c.stop()
        c.clearMediaItems()
        _queue.value = emptyList()
        _uiState.value = PlayerUiState()
    }
    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs)
    fun playQueueItem(index: Int) = controller?.seekToDefaultPosition(index)

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // ---- Queue editing ----

    fun addToQueue(song: Song) = controller?.addMediaItem(song.toMediaItem())

    fun playNext(song: Song) {
        val c = controller ?: return
        val insertAt = (c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount)
        c.addMediaItem(insertAt, song.toMediaItem())
    }

    fun removeFromQueue(index: Int) = controller?.removeMediaItem(index)
    fun moveInQueue(from: Int, to: Int) = controller?.moveMediaItem(from, to)

    // ---- Favorites ----

    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            if (favoriteDao.isFavorite(song.id)) {
                favoriteDao.remove(song.id)
            } else {
                favoriteDao.add(FavoriteEntity(song.id, System.currentTimeMillis()))
            }
        }
    }

    // ---- Playlists ----

    fun createPlaylist(name: String, firstSong: Song? = null) {
        viewModelScope.launch {
            val id = playlistDao.create(PlaylistEntity(name = name, createdAt = System.currentTimeMillis()))
            if (firstSong != null) addToPlaylistInternal(id, firstSong.id)
        }
    }

    fun addSongToPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch { addToPlaylistInternal(playlistId, songId) }
    }

    private suspend fun addToPlaylistInternal(playlistId: Long, songId: Long) {
        val pos = playlistDao.nextPosition(playlistId)
        playlistDao.insertMember(PlaylistSongEntity(playlistId, songId, pos))
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch { playlistDao.removeMember(playlistId, songId) }
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        viewModelScope.launch { playlistDao.rename(playlistId, name) }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            playlistDao.clearMembers(playlistId)
            playlistDao.deletePlaylist(playlistId)
        }
    }

    fun reorderPlaylist(playlistId: Long, orderedSongIds: List<Long>) {
        viewModelScope.launch { playlistDao.reorder(playlistId, orderedSongIds) }
    }

    /** Songs in a playlist, in saved order, as a live Flow mapped to full [Song]s. */
    fun songsInPlaylist(playlistId: Long): Flow<List<Song>> =
        combine(playlistDao.songIdsIn(playlistId), _songs) { ids, songs ->
            val byId = songs.associateBy { it.id }
            ids.mapNotNull { byId[it] }
        }

    // ---- Settings ----

    fun setThemeId(id: String) {
        viewModelScope.launch { settingsRepo.setThemeId(id) }
    }

    fun setPlayerLayout(config: com.example.mimic.data.PlayerLayoutConfig) {
        viewModelScope.launch { settingsRepo.setPlayerLayout(config) }
    }

    fun setHomeConfig(config: com.example.mimic.data.HomeConfig) {
        viewModelScope.launch { settingsRepo.setHomeConfig(config) }
    }

    // ---- Music server (Navidrome / Subsonic) ----

    private val serverCredsRepo = container.serverCredentialsRepository
    private val downloadDao = container.downloadDao

    private val _serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.Disconnected)
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    private val _remoteAlbums = MutableStateFlow<List<RemoteAlbum>>(emptyList())
    val remoteAlbums: StateFlow<List<RemoteAlbum>> = _remoteAlbums.asStateFlow()

    val serverCredentials: StateFlow<ServerCredentials?> = serverCredsRepo.credentials
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val staySignedIn: StateFlow<Boolean> = serverCredsRepo.staySignedIn
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Remembered server address (kept across "Switch account") for prefilling the sign-in form. */
    val lastServerUrl: StateFlow<String?> = serverCredsRepo.lastServerUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Downloaded tracks as playable (offline, file-backed) [Song]s. */
    val downloads: StateFlow<List<Song>> = downloadDao.all()
        .map { list ->
            list.map {
                Song(
                    id = remoteStableId(it.remoteId),
                    title = it.title, artist = it.artist, album = it.album,
                    durationMs = it.durationMs, uri = it.fileUri, albumArtUri = it.coverArtUri,
                    dateAddedSec = it.downloadedAt / 1000, folder = "Downloads",
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var serverClient: SubsonicClient? = null

    init {
        // Encrypt any legacy plaintext password left by an older install (safe no-op otherwise).
        viewModelScope.launch { serverCredsRepo.migratePlaintextIfNeeded() }
        // Rebuild the client from saved credentials so the server survives app restarts —
        // but only when the user chose "Stay signed in" (otherwise they land as an offline guest).
        viewModelScope.launch {
            serverCredsRepo.credentials.collect { creds ->
                val stay = serverCredsRepo.staySignedIn.first()
                if (creds != null && stay && serverClient == null) {
                    serverClient = SubsonicClient(creds)
                    _serverStatus.value = ServerStatus.Connected
                }
            }
        }
    }

    /** Ping the server without persisting anything — the "Test Connection" button. */
    fun testServerConnection(baseUrl: String, username: String, password: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val creds = ServerCredentials(baseUrl.trim(), username.trim(), password)
            val result = SubsonicClient(creds).ping().map { }
            onResult(result)
        }
    }

    /** Validate credentials with a ping, then persist and connect on success (the "Sign In" action). */
    fun connectServer(baseUrl: String, username: String, password: String, staySignedIn: Boolean = true) {
        viewModelScope.launch {
            _serverStatus.value = ServerStatus.Connecting
            val creds = ServerCredentials(baseUrl.trim(), username.trim(), password)
            val client = SubsonicClient(creds)
            client.ping().fold(
                onSuccess = {
                    serverClient = client
                    serverCredsRepo.save(creds, staySignedIn)
                    _serverStatus.value = ServerStatus.Connected
                    loadRemoteAlbums()
                },
                onFailure = { _serverStatus.value = ServerStatus.Error(it.message ?: "Connection failed") },
            )
        }
    }

    fun setStaySignedIn(value: Boolean) {
        viewModelScope.launch { serverCredsRepo.setStaySignedIn(value) }
    }

    /** Full sign-out: forget the account entirely. Local (on-device) playback keeps working. */
    fun disconnectServer() {
        viewModelScope.launch {
            serverClient = null
            _remoteAlbums.value = emptyList()
            serverCredsRepo.clear()
            _serverStatus.value = ServerStatus.Disconnected
        }
    }

    /** Switch account: sign out of the current user but keep the server address prefilled. */
    fun switchAccount() {
        viewModelScope.launch {
            serverClient = null
            _remoteAlbums.value = emptyList()
            serverCredsRepo.signOutKeepServer()
            _serverStatus.value = ServerStatus.Disconnected
        }
    }

    fun loadRemoteAlbums() {
        val client = serverClient ?: return
        viewModelScope.launch {
            client.albums().onSuccess { _remoteAlbums.value = it }
                .onFailure { _serverStatus.value = ServerStatus.Error(it.message ?: "Failed to load albums") }
        }
    }

    suspend fun remoteAlbumSongs(albumId: String): List<RemoteSong> =
        serverClient?.albumSongs(albumId)?.getOrNull() ?: emptyList()

    fun remoteCoverUrl(coverArtId: String?): String? = serverClient?.coverArtUrl(coverArtId)

    /** Stream a remote album (converts to playable Songs whose uri streams from the server). */
    fun playRemote(songs: List<RemoteSong>, index: Int) {
        val client = serverClient ?: return
        play(songs.map { client.toPlayableSong(it) }, index)
    }

    suspend fun isDownloaded(remoteId: String): Boolean = downloadDao.isDownloaded(remoteId)

    /** Download a remote track to app storage for offline playback. */
    fun downloadRemote(song: RemoteSong, onResult: (Boolean) -> Unit) {
        val client = serverClient ?: return onResult(false)
        viewModelScope.launch {
            val dir = File(getApplication<Application>().filesDir, "downloads")
            val ext = song.suffix?.takeIf { it.isNotBlank() } ?: "mp3"
            val target = File(dir, "${song.id}.$ext")
            client.download(song, target).fold(
                onSuccess = {
                    downloadDao.upsert(
                        DownloadEntity(
                            remoteId = song.id, title = song.title, artist = song.artist,
                            album = song.album, durationMs = song.durationMs,
                            fileUri = android.net.Uri.fromFile(target).toString(),
                            coverArtUri = client.coverArtUrl(song.coverArtId),
                            downloadedAt = System.currentTimeMillis(),
                        ),
                    )
                    onResult(true)
                },
                onFailure = { onResult(false) },
            )
        }
    }

    // ---- Custom themes (Theme Studio) ----

    private val customThemeDao = container.customThemeDao

    val customThemes: StateFlow<List<com.example.mimic.ui.designsystem.ThemeDefinition>> =
        customThemeDao.all()
            .map { list -> list.map { it.toDefinition() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Built-in presets plus the user's custom themes, for the gallery and resolution. */
    val allThemes: StateFlow<List<com.example.mimic.ui.designsystem.ThemeDefinition>> =
        customThemes
            .map { custom -> com.example.mimic.ui.designsystem.ThemeRegistry.presets + custom }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                com.example.mimic.ui.designsystem.ThemeRegistry.presets,
            )

    /** The selected theme (preset or custom), resolved once here instead of in every screen. */
    val activeTheme: StateFlow<com.example.mimic.ui.designsystem.ThemeDefinition> =
        combine(settings.map { it.themeId }.distinctUntilChanged(), allThemes) { id, themes ->
            themes.firstOrNull { it.id == id } ?: com.example.mimic.ui.designsystem.ThemeRegistry.byId(id)
        }
            .distinctUntilChanged()
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                com.example.mimic.ui.designsystem.ThemeRegistry.byId(com.example.mimic.ui.designsystem.ThemeRegistry.DEFAULT_ID),
            )

    /**
     * Seed color from the current song's artwork, computed only while the active theme has
     * album-adaptive coloring on (any theme can, not just the "Album Adaptive" preset). Runs off
     * the main thread and only when the *song* or the adaptive flag changes — never per frame or
     * per playback tick. Null => use the theme's own colors.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val adaptiveSeed: StateFlow<Int?> =
        combine(
            uiState.map { it.currentSong }.distinctUntilChanged { a, b -> a?.id == b?.id && a?.albumArtUri == b?.albumArtUri },
            activeTheme.map { it.style.albumAdaptive }.distinctUntilChanged(),
        ) { song, adaptive -> song to adaptive }
            .mapLatest { (song, adaptive) ->
                if (!adaptive || song == null) null
                else com.example.mimic.data.AdaptivePalette.seedFromSong(getApplication(), song)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setGlassQuality(quality: com.example.mimic.data.GlassQuality) {
        viewModelScope.launch { settingsRepo.setGlassQuality(quality) }
    }

    /**
     * "Restore defaults" in Theme Studio: back to the default preset and device-appropriate glass
     * quality, nav/player styles back to follow-the-theme. Custom themes are NOT touched.
     */
    fun restoreThemeDefaults() {
        viewModelScope.launch {
            val s = settings.value
            settingsRepo.setThemeId(com.example.mimic.ui.designsystem.ThemeRegistry.DEFAULT_ID)
            settingsRepo.setGlassQuality(com.example.mimic.data.GlassQuality.safeDefault())
            settingsRepo.setHomeConfig(s.homeConfig.copy(navStyle = com.example.mimic.data.NavStyle.AUTO))
            settingsRepo.setPlayerLayout(
                s.playerLayout.copy(
                    background = com.example.mimic.data.PlayerBackground.AUTO,
                    controlStyle = com.example.mimic.data.ControlStyle.AUTO,
                ),
            )
        }
    }

    fun saveCustomTheme(theme: com.example.mimic.ui.designsystem.ThemeDefinition, apply: Boolean) {
        viewModelScope.launch {
            customThemeDao.upsert(theme.toEntity())
            if (apply) settingsRepo.setThemeId(theme.id)
        }
    }

    fun deleteCustomTheme(id: String) {
        viewModelScope.launch {
            customThemeDao.delete(id)
            if (settings.value.themeId == id) {
                settingsRepo.setThemeId(com.example.mimic.ui.designsystem.ThemeRegistry.DEFAULT_ID)
            }
        }
    }

    /** Clone any theme (preset or custom) into a new editable custom theme. */
    fun duplicateTheme(source: com.example.mimic.ui.designsystem.ThemeDefinition): com.example.mimic.ui.designsystem.ThemeDefinition {
        val copy = source.copy(id = newThemeId(), name = source.name + " copy", builtIn = false)
        saveCustomTheme(copy, apply = false)
        return copy
    }

    fun newThemeId(): String = "custom_" + System.currentTimeMillis()

    fun exportTheme(theme: com.example.mimic.ui.designsystem.ThemeDefinition): String =
        com.example.mimic.data.ThemeSerialization.toJson(theme)

    /** Parse + validate pasted JSON, saving it as a custom theme on success. */
    fun importTheme(json: String): Result<com.example.mimic.ui.designsystem.ThemeDefinition> {
        return com.example.mimic.data.ThemeSerialization.parse(json).map { parsed ->
            // Always store imports under a fresh custom id so they can't clobber a built-in.
            val theme = parsed.copy(id = newThemeId(), builtIn = false)
            saveCustomTheme(theme, apply = false)
            theme
        }
    }

    // ---- Sleep timer ----

    fun startSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        viewModelScope.launch { settingsRepo.setSleepTimerMinutes(minutes) }
        if (minutes <= 0) return
        sleepJob = viewModelScope.launch {
            delay(minutes * 60_000L)
            controller?.pause()
            settingsRepo.setSleepTimerMinutes(0)
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        viewModelScope.launch { settingsRepo.setSleepTimerMinutes(0) }
    }

    // ---- Internals ----

    private fun recordPlay(songId: Long) {
        viewModelScope.launch {
            historyDao.record(
                com.example.mimic.data.db.PlayHistoryEntity(
                    songId = songId,
                    playedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun syncFromPlayer(player: Player) {
        val current = player.currentMediaItem?.mediaId?.toLongOrNull()?.let { songsById[it] }
        val fav = current?.let { favoriteIds.value.contains(it.id) } ?: false
        val buffering = player.playbackState == Player.STATE_BUFFERING
        _uiState.update {
            it.copy(
                currentSong = current,
                isPlaying = player.isPlaying,
                isBuffering = buffering,
                durationMs = player.duration.coerceAtLeast(0L),
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                isCurrentFavorite = fav,
                // A successful play (or ready/ended state) clears a prior error.
                playbackError = if (player.playbackState == Player.STATE_READY) null else it.playbackError,
            )
        }
    }

    private fun rebuildQueue(player: Player) {
        val items = (0 until player.mediaItemCount).mapNotNull { i ->
            player.getMediaItemAt(i).mediaId.toLongOrNull()?.let { songsById[it] }
        }
        _queue.value = items
    }

    private fun startPositionUpdates() {
        viewModelScope.launch {
            while (true) {
                controller?.let { c ->
                    val fav = c.currentMediaItem?.mediaId?.toLongOrNull()
                        ?.let { favoriteIds.value.contains(it) } ?: false
                    _uiState.update {
                        it.copy(positionMs = c.currentPosition.coerceAtLeast(0L), isCurrentFavorite = fav)
                    }
                }
                delay(500L)
            }
        }
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        super.onCleared()
    }
}

private fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(albumArtUri?.let { android.net.Uri.parse(it) })
            .build(),
    )
    .build()
