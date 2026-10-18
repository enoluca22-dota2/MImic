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
import com.example.mimic.data.toDefinition
import com.example.mimic.data.toEntity
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NowPlaying(val song: Song?, val isPlaying: Boolean)

/** Loading state of the server song catalog. */
sealed interface CatalogState {
    data object Idle : CatalogState
    data object Loading : CatalogState
    data class Loaded(val count: Int) : CatalogState
    data class Error(val message: String) : CatalogState
}

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

    private val downloadRepo = container.downloadRepository
    private val favoritesRepo = container.favoritesRepository
    private val cloud = container.cloudLibrary

    /** On-device (MediaStore) songs only. */
    private val _localSongs = MutableStateFlow<List<Song>>(emptyList())
    val localSongs: StateFlow<List<Song>> = _localSongs.asStateFlow()

    /** The signed-in server's song catalog (metadata only, streamed on play). */
    private val _serverSongs = MutableStateFlow<List<Song>>(emptyList())
    private var remoteById: Map<String, RemoteSong> = emptyMap()

    private val _catalogState = MutableStateFlow<CatalogState>(CatalogState.Idle)
    val catalogState: StateFlow<CatalogState> = _catalogState.asStateFlow()

    /** Offline downloads as songs (reactive: a finished download appears here immediately). */
    val downloads: StateFlow<List<Song>> = downloadRepo.songs
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * THE library: on-device + server + downloaded songs merged into one list with one identity
     * per recording (MImic 3.0). Every screen — Home, Search, Library, album/artist pages, smart
     * collections — derives from this single source of truth.
     */
    val songs: StateFlow<List<Song>> =
        combine(_localSongs, _serverSongs, cloud.songs, downloads) { local, server, cloudSongs, dl ->
            // Cloud songs merge exactly like server ones: a download replaces its streamed entry.
            com.example.mimic.data.UnifiedLibrary.merge(local, server + cloudSongs, dl)
        }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    /**
     * Just the loaded song + play state, without the 500 ms position ticks. Lists and Home observe
     * this instead of [uiState] so they don't recompose twice a second during playback.
     */
    val nowPlaying: StateFlow<NowPlaying> = _uiState
        .map { NowPlaying(it.currentSong, it.isPlaying) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, NowPlaying(null, false))

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    // ---- Library browsing state (kept here so it survives navigation) ----

    val sourceFilter = MutableStateFlow(com.example.mimic.data.SourceFilter.ALL)
    val songSort = MutableStateFlow(com.example.mimic.data.SongSort.NAME)

    /** songId -> last played time, for "Recently played" sorting. */
    val lastPlayed: StateFlow<Map<Long, Long>> = historyDao.lastPlayedAll()
        .map { rows -> rows.associate { it.songId to it.playedAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // ---- Derived library views, backed by Room + the unified library ----

    val favoriteIds: StateFlow<Set<Long>> = favoritesRepo.favoriteIds
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val favoriteSongs: StateFlow<List<Song>> =
        combine(favoritesRepo.favoriteIds, songs) { ids, songs ->
            val byId = songs.associateBy { it.id }
            ids.mapNotNull { byId[it] }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Favorited albums / artists (MImic 3.0). */
    val favoriteGroups: StateFlow<List<com.example.mimic.data.db.FavoriteGroupEntity>> = favoritesRepo.groups
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Server favorite changes still waiting to be sent (offline). */
    val pendingFavoriteSyncs: StateFlow<Int> = favoritesRepo.pendingCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Continue Listening: the user's real play history, newest first, across every source. */
    val recentlyPlayed: StateFlow<List<Song>> =
        combine(historyDao.recentlyPlayedIds(20), songs) { ids, songs ->
            val byId = songs.associateBy { it.id }
            ids.mapNotNull { byId[it] }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mostPlayed: StateFlow<List<Song>> =
        combine(historyDao.mostPlayed(20), songs) { counts, songs ->
            val byId = songs.associateBy { it.id }
            counts.mapNotNull { byId[it.songId] }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentlyAdded: StateFlow<List<Song>> = songs
        .map { com.example.mimic.data.LibraryQueries.recentlyAdded(it, 20) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** "Suggested": songs sharing an artist with the user's most-played, excluding those. */
    val suggested: StateFlow<List<Song>> =
        combine(historyDao.mostPlayed(30), songs) { counts, songs ->
            com.example.mimic.data.LibraryQueries.suggested(songs, counts, 20)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- Smart collections (generated from local library + play history; no server) ----

    val neverPlayed: StateFlow<List<Song>> =
        combine(historyDao.playedSongIds(), songs) { playedIds, songs ->
            com.example.mimic.data.SmartCollections.neverPlayed(songs, playedIds.toSet(), 50)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val leastPlayed: StateFlow<List<Song>> =
        combine(historyDao.leastPlayed(50), songs) { counts, songs ->
            com.example.mimic.data.SmartCollections.leastPlayed(songs, counts, 50)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val forgottenFavorites: StateFlow<List<Song>> =
        combine(favoriteDao.favoriteIds(), historyDao.recentlyPlayedIds(30), songs) { favs, recent, songs ->
            com.example.mimic.data.SmartCollections.forgottenFavorites(songs, favs.toSet(), recent.toSet(), 50)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Reproducible for the calendar day so it doesn't reshuffle on every recomposition. */
    val randomDiscovery: StateFlow<List<Song>> = songs
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
            val current = _uiState.value.currentSong
            val cloudName = current?.remoteId?.let(com.example.mimic.data.cloud.CloudRef::parse)?.provider?.label
            val reason = if (cloudName != null) cloudPlaybackError(error.errorCode, cloudName) else when (error.errorCode) {
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

    private fun cloudPlaybackError(code: Int, provider: String): String = when (code) {
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Can't reach $provider. Check your internet connection — downloaded songs still play offline."
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            "$provider needs you to sign in again (Settings → Music Sources)."
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "This song is no longer in your $provider music folder."
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "$provider refused the request. Try again, or sync the library."
        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "This file's format can't be played on this phone."
        else -> "Couldn't play from $provider (${androidx.media3.common.PlaybackException.getErrorCodeName(code)})."
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
            _localSongs.value = repository.loadSongs()
            _loading.value = false
        }
    }

    // ---- Playback ----

    fun play(queue: List<Song>, index: Int) {
        val c = controller ?: return
        if (queue.isEmpty()) return
        // Prefer the offline copy of any server track that has a verified download.
        val resolved = queue.map(::preferOffline)
        songsById = songsById + resolved.associateBy { it.id }
        _uiState.update { it.copy(playbackError = null) }
        c.setMediaItems(resolved.map { it.toMediaItem() }, index.coerceIn(0, resolved.size - 1), 0L)
        c.prepare()
        c.play()
    }

    /** Shuffle-play a list (album / artist "Shuffle" buttons). */
    fun shufflePlay(queue: List<Song>) {
        if (queue.isEmpty()) return
        play(queue.shuffled(), 0)
        controller?.shuffleModeEnabled = false // already shuffled; keep Next/Previous predictable
    }

    private fun preferOffline(song: Song): Song {
        val lib = libraryById[song.id] ?: return song
        return if (lib.isDownloaded && !lib.fileMissing) lib else song
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

    fun addToQueue(song: Song) {
        val s = preferOffline(song)
        songsById = songsById + (s.id to s)
        controller?.addMediaItem(s.toMediaItem())
    }

    fun playNext(song: Song) {
        val c = controller ?: return
        val s = preferOffline(song)
        songsById = songsById + (s.id to s)
        val insertAt = (c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount)
        c.addMediaItem(insertAt, s.toMediaItem())
    }

    fun removeFromQueue(index: Int) = controller?.removeMediaItem(index)
    fun moveInQueue(from: Int, to: Int) = controller?.moveMediaItem(from, to)

    // ---- Favorites ----

    /** Heart a song. Room updates first (Home/Library react at once); server tracks also star. */
    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            val account = serverCredentials.value?.let { SubsonicClient.accountKeyOf(it) }
            favoritesRepo.toggleSong(song, serverClient, account)
        }
    }

    fun toggleFavoriteAlbum(album: com.example.mimic.data.AlbumGroup) {
        viewModelScope.launch {
            favoritesRepo.toggleGroup(
                com.example.mimic.data.db.FavoriteGroupEntity.KIND_ALBUM, album.key, album.title, album.artist,
            )
        }
    }

    fun toggleFavoriteArtist(artist: com.example.mimic.data.ArtistGroup) {
        viewModelScope.launch {
            favoritesRepo.toggleGroup(
                com.example.mimic.data.db.FavoriteGroupEntity.KIND_ARTIST, artist.key, artist.name,
                "${artist.songs.size} songs",
            )
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
        combine(playlistDao.songIdsIn(playlistId), songs) { ids, songs ->
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

    /** Server track ids currently downloading — every screen shows the same progress state. */
    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    val downloading: StateFlow<Set<String>> = _downloading.asStateFlow()

    /** One-shot user messages (download finished / failed). */
    private val _messages = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: kotlinx.coroutines.flow.SharedFlow<String> = _messages

    private var serverClient: SubsonicClient? = null
    private var libraryById: Map<Long, Song> = emptyMap()

    init {
        // Keep the player's id -> Song lookup in step with the unified library, so the current
        // song / queue resolve for downloaded and server tracks as well as on-device files.
        viewModelScope.launch {
            songs.collect { lib ->
                libraryById = lib.associateBy { it.id }
                songsById = songsById + libraryById
                controller?.let { syncFromPlayer(it); rebuildQueue(it) }
            }
        }
        // Cloud libraries: refresh on launch when stale (foreground refresh is the reliable path;
        // the background worker is best-effort).
        cloud.syncAllAsync(10 * 60_000L)
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
                    onServerSession()
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
                    onServerSession()
                },
                onFailure = { _serverStatus.value = ServerStatus.Error(it.message ?: "Connection failed") },
            )
        }
    }

    /**
     * A server session started (sign-in or restored on launch): load the song catalog in the
     * background and sync favorites. Never blocks the UI; failures (offline) leave the on-device
     * and downloaded music fully usable.
     */
    private fun onServerSession() {
        refreshServerCatalog()
        val client = serverClient ?: return
        viewModelScope.launch { favoritesRepo.syncWithServer(client) }
    }

    /** (Re)load the server's song metadata. Audio is never downloaded by this. */
    fun refreshServerCatalog() {
        val client = serverClient ?: return
        if (_catalogState.value == CatalogState.Loading) return
        viewModelScope.launch {
            _catalogState.value = CatalogState.Loading
            client.allSongs().fold(
                onSuccess = { remote ->
                    if (serverClient !== client) return@fold // signed out meanwhile
                    remoteById = remote.associateBy { it.id }
                    _serverSongs.value = remote.map { client.toPlayableSong(it) }
                    _catalogState.value = CatalogState.Loaded(remote.size)
                },
                onFailure = {
                    _catalogState.value = CatalogState.Error(it.message ?: "Could not load the server library")
                },
            )
        }
    }

    fun setStaySignedIn(value: Boolean) {
        viewModelScope.launch { serverCredsRepo.setStaySignedIn(value) }
    }

    private fun clearServerSession() {
        serverClient = null
        _remoteAlbums.value = emptyList()
        _serverSongs.value = emptyList()
        remoteById = emptyMap()
        _catalogState.value = CatalogState.Idle
    }

    /** Full sign-out: forget the account entirely. Local (on-device) playback and downloads keep working. */
    fun disconnectServer() {
        viewModelScope.launch {
            clearServerSession()
            serverCredsRepo.clear()
            _serverStatus.value = ServerStatus.Disconnected
        }
    }

    /** Switch account: sign out of the current user but keep the server address prefilled. */
    fun switchAccount() {
        viewModelScope.launch {
            clearServerSession()
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

    /** Stream a remote album (converts to playable Songs; downloaded tracks play from the file). */
    fun playRemote(songs: List<RemoteSong>, index: Int) {
        val client = serverClient ?: return
        play(songs.map { client.toPlayableSong(it) }, index)
    }

    /** Download a server track (from the server browser) for offline playback. */
    fun downloadRemote(song: RemoteSong) {
        remoteById = remoteById + (song.id to song)
        startDownload(song)
    }

    /** Download a library song that comes from the server or a cloud drive. No-op for on-device songs. */
    fun downloadSong(song: Song) {
        if (song.remoteId?.let(com.example.mimic.data.cloud.CloudRef::parse) != null) {
            startCloudDownload(song, announce = true)
            return
        }
        val remote = song.remoteId?.let { remoteById[it] }
        if (remote == null) {
            _messages.tryEmit("Connect to your server to download \"${song.title}\"")
            return
        }
        startDownload(remote)
    }

    /** User-initiated "Download album": downloads the listed server tracks one at a time. */
    fun downloadAll(list: List<Song>) {
        val wanted = list.filter { it.isFromServer && (!it.isDownloaded || it.fileMissing) }
        val (cloudSongs, serverSongs) = wanted.partition { s -> s.remoteId?.let(com.example.mimic.data.cloud.CloudRef::parse) != null }
        val todo = serverSongs.mapNotNull { s -> s.remoteId?.let { remoteById[it] } }
        if (todo.isEmpty() && cloudSongs.isEmpty()) {
            _messages.tryEmit("Nothing to download")
            return
        }
        _messages.tryEmit("Downloading ${todo.size + cloudSongs.size} songs…")
        viewModelScope.launch {
            for (r in todo) download(r, announce = false)
            // One at a time: bounded bandwidth, and Cancel stops exactly the current song.
            for (s in cloudSongs) cloudDownload(s, announce = false)
        }
    }

    /** remoteId -> fraction downloaded (0..1, or -1 while the size is unknown). */
    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()
    private val downloadJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    private fun startCloudDownload(song: Song, announce: Boolean) {
        viewModelScope.launch { cloudDownload(song, announce) }
    }

    private suspend fun cloudDownload(song: Song, announce: Boolean) {
        val id = song.remoteId ?: return
        if (id in _downloading.value) return
        cloud.downloadBlockedReason()?.let { _messages.tryEmit("$it — \"${song.title}\" wasn't downloaded"); return }
        _downloading.update { it + id }
        _downloadProgress.update { it + (id to -1f) }
        val job = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            cloud.download(song) { done, total ->
                val f = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f
                _downloadProgress.update { it + (id to f) }
            }.fold(
                onSuccess = { if (announce) _messages.tryEmit("Downloaded \"${song.title}\"") },
                onFailure = { _messages.tryEmit("Download failed: ${it.message ?: "unknown error"}. Tap Download to retry.") },
            )
        }
        downloadJobs[id] = job
        try {
            job.join()
            if (job.isCancelled) _messages.tryEmit("Download cancelled")
        } finally {
            downloadJobs.remove(id)
            _downloading.update { it - id }
            _downloadProgress.update { it - id }
        }
    }

    /** Cancel a running cloud download; the partial file is deleted, nothing is recorded. */
    fun cancelDownload(song: Song) {
        song.remoteId?.let { downloadJobs[it]?.cancel() }
    }

    private fun startDownload(remote: RemoteSong) {
        viewModelScope.launch { download(remote, announce = true) }
    }

    private suspend fun download(remote: RemoteSong, announce: Boolean) {
        val client = serverClient ?: run { _messages.tryEmit("Not connected to your server"); return }
        if (remote.id in _downloading.value) return
        _downloading.update { it + remote.id }
        try {
            downloadRepo.download(client, remote).fold(
                onSuccess = { if (announce) _messages.tryEmit("Downloaded \"${remote.title}\"") },
                onFailure = { _messages.tryEmit("Download failed: ${it.message ?: "unknown error"}") },
            )
        } finally {
            _downloading.update { it - remote.id }
        }
    }

    /** Delete the offline copy only; the song stays on the server / in the cloud. */
    fun removeDownload(song: Song) {
        val remoteId = song.remoteId ?: return
        viewModelScope.launch {
            downloadRepo.remove(remoteId)
            val where = com.example.mimic.data.cloud.CloudRef.parse(remoteId)?.provider?.label ?: "your server"
            _messages.tryEmit("Removed download (still in $where)")
        }
    }

    // ---- Cloud sources: Google Drive / OneDrive (MImic 3.1) ----

    val cloudAccounts: StateFlow<List<com.example.mimic.data.db.CloudAccountEntity>> = cloud.accounts
    val cloudSyncState: StateFlow<Map<String, com.example.mimic.data.cloud.CloudSyncState>> = cloud.syncState
    val cloudStats: StateFlow<Map<String, com.example.mimic.data.cloud.CloudAccountStats>> = cloud.stats
    val oneDriveConfigured: Boolean get() = cloud.oneDriveAuth.isConfigured

    /** Pull-to-refresh / "Sync now": Navidrome catalog + every cloud account. */
    fun refreshAllSources() {
        refreshServerCatalog()
        cloud.syncAllAsync(0)
    }

    /** Opening the library: refresh cloud accounts not synced in the last 10 minutes. */
    fun refreshIfStale() = cloud.syncAllAsync(10 * 60_000L)

    fun syncCloudAccount(accountKey: String) {
        viewModelScope.launch { cloud.sync(accountKey) }
    }

    private fun cloudError(e: Throwable): String = when (e) {
        is com.example.mimic.data.cloud.CloudException -> e.message ?: "Cloud error"
        else -> "Couldn't connect (${e.javaClass.simpleName})"
    }

    /** OneDrive sign-in (browser) -> returns the new account key for folder selection. */
    fun connectOneDrive(activity: android.app.Activity, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { cloud.connectOneDrive(activity) }.fold(
                onSuccess = { onDone(it.accountKey) },
                onFailure = { _messages.tryEmit(cloudError(it)); onDone(null) },
            )
        }
    }

    /** Google step 1: either already authorized, or hands back Google's consent UI to launch. */
    fun connectGoogle(launchConsent: (android.app.PendingIntent) -> Unit, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { cloud.beginGoogle() }.fold(
                onSuccess = { step ->
                    when (step) {
                        is com.example.mimic.data.cloud.google.GoogleDriveAuth.Step.NeedsConsent -> launchConsent(step.intent)
                        is com.example.mimic.data.cloud.google.GoogleDriveAuth.Step.Authorized -> finishGoogle(step.token, onDone)
                    }
                },
                onFailure = { _messages.tryEmit(cloudError(it)); onDone(null) },
            )
        }
    }

    /** Google step 2: the consent UI returned. */
    fun completeGoogleConsent(data: android.content.Intent?, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { cloud.googleTokenFromConsent(data) }.fold(
                onSuccess = { finishGoogle(it, onDone) },
                onFailure = { _messages.tryEmit(cloudError(it)); onDone(null) },
            )
        }
    }

    private suspend fun finishGoogle(token: String, onDone: (String?) -> Unit) {
        runCatching { cloud.finishGoogle(token) }.fold(
            onSuccess = { onDone(it.accountKey) },
            onFailure = { _messages.tryEmit(cloudError(it)); onDone(null) },
        )
    }

    /** Debug builds: connect a mock-API test account (local test server via adb reverse). */
    fun connectMockCloud(provider: com.example.mimic.data.cloud.CloudProviderType, apiBase: String, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            runCatching { cloud.connectMock(provider, apiBase) }.fold(
                onSuccess = { onDone(it.accountKey) },
                onFailure = { _messages.tryEmit(cloudError(it)); onDone(null) },
            )
        }
    }

    suspend fun listCloudFolders(accountKey: String, parentId: String?): Result<List<com.example.mimic.data.cloud.CloudFolder>> =
        runCatching { cloud.listFolders(accountKey, parentId) }

    fun chooseCloudFolder(accountKey: String, folder: com.example.mimic.data.cloud.CloudFolder) {
        viewModelScope.launch { cloud.chooseFolder(accountKey, folder) }
    }

    fun disconnectCloud(accountKey: String, removeDownloads: Boolean) {
        viewModelScope.launch {
            cloud.disconnect(accountKey, removeDownloads)
            _messages.tryEmit(if (removeDownloads) "Disconnected and removed its downloads" else "Disconnected — downloaded songs are kept")
        }
    }

    fun setCloudDataControls(wifiOnlyDownloads: Boolean, cellularTagReading: Boolean, backgroundSync: Boolean) {
        viewModelScope.launch {
            settingsRepo.setCloudDataControls(wifiOnlyDownloads, cellularTagReading, backgroundSync)
            com.example.mimic.data.sync.CloudLibrarySyncWorker.schedule(getApplication(), backgroundSync)
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
