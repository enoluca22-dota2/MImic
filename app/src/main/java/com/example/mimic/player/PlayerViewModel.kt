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
import com.example.mimic.data.online.toSong
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NowPlaying(val song: Song?, val isPlaying: Boolean)

/** The phone's equalizer: band centers (Hz) and level range (millibels). */
data class EqualizerInfo(val frequenciesHz: List<Int>, val minLevel: Int, val maxLevel: Int, val bassBoost: Boolean)

sealed interface LyricsState {
    data object Loading : LyricsState
    data class Found(val lyrics: com.example.mimic.data.audio.Lyrics) : LyricsState
    data class None(val reason: String) : LyricsState
}

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
    /** How far the player has loaded ahead (Media3 bufferedPosition), for the seek bar. */
    val bufferedPositionMs: Long = 0L,
    /** 4.2: the current item is a live radio station (no seeking; LIVE instead of a timeline). */
    val isLive: Boolean = false,
    /** 4.2: what the station says is on air now (ICY "StreamTitle"), when it sends one. */
    val liveTitle: String? = null,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val isCurrentFavorite: Boolean = false,
    /** Non-null when the last play attempt failed; cleared when a new item starts loading. */
    val playbackError: String? = null,
)

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class]) // CastPlayer (4.1)
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val container: AppContainer = (app as MimicApplication).container
    private val repository = container.musicRepository
    private val favoriteDao = container.favoriteDao
    private val historyDao = container.historyDao
    private val playlistDao = container.playlistDao
    private val settingsRepo = container.settingsRepository

    private val downloadRepo = container.downloadRepository
    private val favoritesRepo = container.favoritesRepository

    /** On-device (MediaStore) songs only. */
    private val _localSongs = MutableStateFlow<List<Song>>(emptyList())
    val localSongs: StateFlow<List<Song>> = _localSongs.asStateFlow()

    /** The signed-in server's song catalog (metadata only, streamed on play). */
    private val _serverSongs = MutableStateFlow<List<Song>>(emptyList())
    private var remoteById: Map<String, RemoteSong> = emptyMap()

    private val _catalogState = MutableStateFlow<CatalogState>(CatalogState.Idle)
    val catalogState: StateFlow<CatalogState> = _catalogState.asStateFlow()

    /** Storage used by offline downloads (recomputed whenever the downloads change). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val offlineStorage: StateFlow<com.example.mimic.data.DownloadRepository.Storage?> = downloadRepo.songs
        .mapLatest { downloadRepo.storage() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Offline downloads as songs (reactive: a finished download appears here immediately). */
    val downloads: StateFlow<List<Song>> = downloadRepo.songs
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * THE library: on-device + server + downloaded songs merged into one list with one identity
     * per recording (MImic 3.0). Every screen — Home, Search, Library, album/artist pages, smart
     * collections — derives from this single source of truth.
     */
    // ---- Online music (4.4, full edition) ----

    private val onlineStore = container.onlineTracks

    /** Every online track MImic remembers (played, favorited, in a playlist or downloaded). */
    private val onlineRows: StateFlow<List<com.example.mimic.data.online.OnlineTrackEntity>> = onlineStore.all
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Online tracks the user put in the library (favorite / playlist / download) — shown in Library. */
    private val onlineLibrary = onlineRows.map { rows -> rows.filter { it.inLibrary }.map { it.toSong() } }

    val songs: StateFlow<List<Song>> =
        combine(_localSongs, _serverSongs, downloads, onlineLibrary) { local, server, dl, online ->
            com.example.mimic.data.UnifiedLibrary.merge(local, server, dl, online)
        }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * [songs] plus online tracks that were only played (not added to the library), so play
     * history, Continue Listening and the restored queue still show them with their own metadata.
     */
    val knownSongs: StateFlow<List<Song>> =
        combine(songs, onlineRows) { lib, rows ->
            val ids = lib.mapTo(HashSet(lib.size)) { it.id }
            lib + rows.filter { !it.inLibrary }.map { it.toSong() }.filter { it.id !in ids }
        }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- Library scanning (4.3): phone songs before folder / minimum-length filtering ----

    private val _rawLocal = MutableStateFlow<List<Song>>(emptyList())

    val scanConfig: StateFlow<com.example.mimic.data.LibraryScanConfig> = settingsRepo.scanConfig
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.example.mimic.data.LibraryScanConfig())

    /** Every folder with phone music (shown or hidden), for Settings → Library & Storage. */
    val scanFolders: StateFlow<List<com.example.mimic.data.ScanFolder>> =
        combine(_rawLocal, settingsRepo.scanConfig) { raw, c -> com.example.mimic.data.LibraryScan.folders(raw, c) }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            combine(_rawLocal, settingsRepo.scanConfig) { raw, c -> com.example.mimic.data.LibraryScan.filter(raw, c) }
                .flowOn(kotlinx.coroutines.Dispatchers.Default)
                .collect { _localSongs.value = it }
        }
    }

    fun setScanConfig(c: com.example.mimic.data.LibraryScanConfig) {
        viewModelScope.launch { settingsRepo.setScanConfig(c) }
    }

    /** Every song MImic knows, including hidden folders — for backups and restore matching. */
    fun fullLibrary(): List<Song> =
        com.example.mimic.data.UnifiedLibrary.merge(_rawLocal.value, _serverSongs.value, downloads.value)

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /** Index of the playing item in [queue] (the queue may hold the same song twice). */
    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

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
        combine(historyDao.recentlyPlayedIds(20), knownSongs) { ids, songs ->
            val byId = songs.associateBy { it.id }
            ids.mapNotNull { byId[it] }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mostPlayed: StateFlow<List<Song>> =
        combine(historyDao.mostPlayed(20), knownSongs) { counts, songs ->
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

    /** Listening stats window in days (7 / 30 / 365). */
    val statsDays = MutableStateFlow(30)

    val listeningStats: StateFlow<com.example.mimic.data.ListeningStats?> =
        combine(historyDao.historySince(System.currentTimeMillis() - 366 * com.example.mimic.data.ListeningStats.DAY_MS), songs, statsDays) { h, s, d ->
            val now = System.currentTimeMillis()
            com.example.mimic.data.ListeningStats.compute(h, s, now, d, java.util.TimeZone.getDefault().getOffset(now).toLong())
        }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Albums played recently, newest first — Continue Listening and Quick Access (MImic 3.2). */
    val recentAlbums: StateFlow<List<com.example.mimic.data.AlbumGroup>> =
        combine(historyDao.recentlyPlayedIds(150), knownSongs) { ids, songs ->
            com.example.mimic.data.LibraryQueries.recentAlbums(ids, songs, 12)
        }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- Smart Mixes (MImic 3.2): deterministic rules over the user's own library + history ----

    val favoriteArtistsMix: StateFlow<List<Song>> =
        combine(favoritesRepo.groups, favoritesRepo.favoriteIds, historyDao.recentlyPlayedIds(15), songs) { groups, favs, recent, songs ->
            val artistKeys = groups.filter { it.kind == com.example.mimic.data.db.FavoriteGroupEntity.KIND_ARTIST }.map { it.groupKey }.toSet()
            com.example.mimic.data.SmartCollections.favoriteArtistsMix(songs, artistKeys, favs.toSet(), recent, daySeed(), 50)
        }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val shuffleDiscovery: StateFlow<List<Song>> =
        combine(historyDao.mostPlayed(Int.MAX_VALUE), songs) { counts, songs ->
            com.example.mimic.data.SmartCollections.shuffleDiscovery(songs, counts.associate { it.songId to it.count }, daySeed(), 50)
        }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists: StateFlow<List<PlaylistEntity>> = playlistDao.playlists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var songsById: Map<Long, Song> = emptyMap()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // A new item started loading — clear any stale error from the previous track.
            // (Plays are recorded by PlaybackService, which also sees plays with the UI closed.)
            _uiState.update { it.copy(playbackError = null) }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // Classify without leaking the stream URL (which carries the auth token).
            val current = _uiState.value.currentSong
            if (current != null && com.example.mimic.data.radio.RadioIds.isRadio(current.id)) {
                onRadioError(current, error)
                return
            }
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
            // The service skips an unplayable song when more follow; say which one and why, since
            // the mini-player error clears as soon as the next song loads.
            if (controller?.hasNextMediaItem() == true && current != null) {
                _messages.tryEmit("Skipped \"${current.title}\": $reason")
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            syncFromPlayer(player)
            rebuildQueue(player)
            if (player.playbackState == Player.STATE_READY) radioRetries = 0
        }
    }

    // ---- Internet radio (4.2) ----

    private val radioRepo = container.radioRepository

    /** Stations known to the player by their (negative) player id. */
    private val radioById = java.util.concurrent.ConcurrentHashMap<Long, com.example.mimic.data.radio.RadioStation>()
    private var radioRetries = 0
    private var radioRetryJob: Job? = null

    val radioFavoriteIds: StateFlow<Set<String>> = radioRepo.favorites.map { l -> l.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun radioStationFor(song: Song?): com.example.mimic.data.radio.RadioStation? = song?.let { radioById[it.id] }

    private fun radioSong(st: com.example.mimic.data.radio.RadioStation, url: String = st.streamUrl) = Song(
        id = st.playerId,
        title = st.name,
        artist = st.subtitle,
        album = "${st.flag} ${st.countryName} · Radio",
        durationMs = 0,
        uri = url,
        albumArtUri = st.logoUrl,
        dateAddedSec = 0,
        folder = "Radio",
        genre = st.genre,
        source = com.example.mimic.data.SongSource.SERVER,
        remoteId = com.example.mimic.data.radio.RadioIds.remoteId(st.id),
    )

    private fun radioMediaItem(st: com.example.mimic.data.radio.RadioStation, url: String): MediaItem = MediaItem.Builder()
        .setMediaId(st.playerId.toString())
        .setUri(url)
        .apply { if (st.isHls) setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8) }
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(st.name)
                .setArtist(st.subtitle)
                .setStation(st.name)
                .setAlbumTitle("${st.countryName} · Radio")
                .setArtworkUri(st.logoUrl?.let(android.net.Uri::parse))
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .setIsPlayable(true)
                .build(),
        )
        .build()

    /**
     * Play [stations][index]; the rest become the queue, so Next / Previous switch stations. The
     * directory is asked for the station's current stream first (stations move their streams).
     */
    fun playRadio(stations: List<com.example.mimic.data.radio.RadioStation>, index: Int) {
        val chosen = stations.getOrNull(index) ?: return
        radioRetryJob?.cancel()
        radioRetries = 0
        viewModelScope.launch {
            val url = radioRepo.resolveStreamUrl(chosen)
            val list = stations.map { if (it.id == chosen.id) it.copy(streamUrl = url) else it }
            list.forEach { radioById[it.playerId] = it }
            songsById = songsById + list.associate { it.playerId to radioSong(it) }
            _uiState.update { it.copy(playbackError = null) }
            val c = controller ?: return@launch
            val items = list.map { radioMediaItem(it, it.streamUrl) }
            val tv = castPlayerIfCasting()
            if (tv != null) {
                c.setMediaItems(items, index, 0L)
                c.prepare()
                tv.setMediaItems(list.mapNotNull { toCastItem(radioSong(it)) }, index, 0L)
                tv.prepare(); tv.play()
            } else {
                c.setMediaItems(items, index, 0L)
                c.prepare()
                c.play()
            }
            radioRepo.recordPlayed(chosen)
        }
    }

    /** "Reconnect": fetch the stream address again and restart the station (resets the retry budget). */
    fun reconnectRadio() {
        val song = _uiState.value.currentSong ?: return
        val st = radioById[song.id] ?: return
        radioRetries = 0
        restartStation(st)
    }

    private fun restartStation(st: com.example.mimic.data.radio.RadioStation) {
        val c = controller ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(playbackError = null, isBuffering = true) }
            val url = radioRepo.resolveStreamUrl(st)
            val index = c.currentMediaItemIndex
            if (index < 0 || c.currentMediaItem?.mediaId != st.playerId.toString()) return@launch
            c.replaceMediaItem(index, radioMediaItem(st, url))
            c.prepare()
            c.play()
        }
    }

    /**
     * A dead or interrupted stream: retry twice (after 5 s and 15 s, with a fresh address), then
     * stop and offer Reconnect — never an endless retry loop, never a silent jump to another station.
     */
    private fun onRadioError(song: Song, error: androidx.media3.common.PlaybackException) {
        val st = radioById[song.id]
        val offline = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        android.util.Log.w("MImicRadio", "Station error ${error.errorCodeName}")
        if (st != null && radioRetries < 2) {
            radioRetries++
            val wait = if (radioRetries == 1) 5_000L else 15_000L
            _uiState.update { it.copy(isBuffering = true, playbackError = null) }
            radioRetryJob?.cancel()
            radioRetryJob = viewModelScope.launch { delay(wait); restartStation(st) }
            return
        }
        _uiState.update {
            it.copy(
                isBuffering = false,
                playbackError = if (offline) "No connection to the station. Check your internet, then tap Reconnect."
                else "This station isn't broadcasting right now (${error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')}). Try Reconnect or another station.",
            )
        }
    }

    fun toggleRadioFavorite(st: com.example.mimic.data.radio.RadioStation) {
        viewModelScope.launch { radioRepo.setFavorite(st, st.id !in radioFavoriteIds.value) }
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
            restoreQueueIfIdle()
            refreshSleepState()
        }, MoreExecutors.directExecutor())
    }

    init {
        // New downloads are ordinary phone songs: rescan (debounced) so they appear right away.
        viewModelScope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            com.example.mimic.downloader.Downloader.completions(getApplication())
                .debounce(1500)
                .collect { loadLibrary() }
        }
    }

    fun loadLibrary() {
        viewModelScope.launch {
            _loading.value = true
            _rawLocal.value = repository.loadSongs()
            _loading.value = false
        }
    }

    // ---- Casting (Google Cast, 4.1) ----

    private val castController = container.castController
    @Volatile private var casting = false
    private var lastCastIndex = 0
    private var lastCastPositionMs = 0L

    /** "Casting to <TV>" for the UI, or null. */
    private val _castingTo = MutableStateFlow<String?>(null)
    val castingTo: StateFlow<String?> = _castingTo.asStateFlow()

    /** The player the controls act on: the TV while casting, otherwise the phone. */
    private val activePlayer: Player? get() = if (casting) castController.player ?: controller else controller

    private fun castPlayerIfCasting(): Player? = if (casting) castController.player else null

    private val castListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (!casting) return
            lastCastIndex = player.currentMediaItemIndex
            lastCastPositionMs = player.currentPosition
            syncFromPlayer(player)
            rebuildQueue(player)
        }
    }

    init {
        viewModelScope.launch {
            castController.events.collect { e ->
                when (e) {
                    com.example.mimic.cast.CastController.Event.SESSION_STARTED -> startCasting()
                    com.example.mimic.cast.CastController.Event.SESSION_ENDED -> stopCasting()
                }
            }
        }
    }

    /** A cast session began: hand the phone's queue + position to the TV, pause the phone. */
    private fun startCasting() {
        val tv = castController.player ?: return
        if (castController.server.start() == null) {
            _messages.tryEmit("To cast, connect this phone to the same Wi-Fi network as your TV.")
            castController.disconnect()
            return
        }
        casting = true
        _castingTo.value = castController.deviceName() ?: "TV"
        tv.addListener(castListener)
        val c = controller
        val songs = c?.let { (0 until it.mediaItemCount).mapNotNull { i -> it.getMediaItemAt(i).mediaId.toLongOrNull()?.let { id -> songsById[id] } } }.orEmpty()
        if (c != null && songs.isNotEmpty()) {
            val items = songs.mapNotNull(::toCastItem)
            if (items.size != songs.size) _messages.tryEmit("Some songs can't be cast and were left out")
            tv.setMediaItems(items, c.currentMediaItemIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)), c.currentPosition)
            tv.repeatMode = c.repeatMode
            tv.prepare()
            tv.play()
            c.pause()
        }
        _messages.tryEmit("Casting to ${_castingTo.value}")
    }

    /** The cast session ended: the phone takes over at the same song and position, paused. */
    private fun stopCasting() {
        if (!casting) return
        casting = false
        castController.player?.removeListener(castListener)
        castController.server.stop()
        val name = _castingTo.value
        _castingTo.value = null
        controller?.let { c ->
            if (lastCastIndex in 0 until c.mediaItemCount) c.seekTo(lastCastIndex, lastCastPositionMs)
            c.pause()
            syncFromPlayer(c)
            rebuildQueue(c)
        }
        _messages.tryEmit("Stopped casting${name?.let { " to $it" } ?: ""} — playback is back on the phone")
    }

    /**
     * The TV-side item for [song]: its audio and artwork served by the phone's LAN server under
     * random tokens. Navidrome songs are relayed (transcoded to MP3, which every Cast device
     * plays) — the stream URL with its login token never leaves the phone.
     */
    private fun toCastItem(song: Song): androidx.media3.common.MediaItem? {
        val server = castController.server
        val uri = android.net.Uri.parse(song.uri)
        val (source, mime) = when (uri.scheme) {
            "content" -> com.example.mimic.cast.LanMediaServer.Source.ContentUri(uri) to
                (runCatching { getApplication<Application>().contentResolver.getType(uri) }.getOrNull() ?: "audio/mpeg")
            "file" -> com.example.mimic.cast.LanMediaServer.Source.LocalFile(java.io.File(uri.path ?: return null)) to
                com.example.mimic.share.SongShare.mimeFor(com.example.mimic.share.SongShare.extensionOf(song.uri))
            "http", "https" -> if (com.example.mimic.data.radio.RadioIds.isRadio(song.id)) {
                val st = radioById[song.id]
                val mime = if (st?.isHls == true) "application/x-mpegURL" else "audio/mpeg"
                // A public radio stream carries no credentials: HTTPS goes straight to the TV.
                if (song.uri.startsWith("https://")) {
                    return androidx.media3.common.MediaItem.Builder().setMediaId(song.id.toString()).setUri(song.uri).setMimeType(mime)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setArtworkUri(song.albumArtUri?.let(android.net.Uri::parse)).build())
                        .build()
                }
                com.example.mimic.cast.LanMediaServer.Source.Relay(song.uri) to mime
            } else com.example.mimic.cast.LanMediaServer.Source.Relay(song.uri + "&format=mp3&maxBitRate=320") to "audio/mpeg"
            else -> return null
        }
        val url = server.publish(source, mime) ?: return null
        val art = song.albumArtUri?.let { server.publish(com.example.mimic.cast.LanMediaServer.Source.Artwork(it), "image/jpeg") }
        return androidx.media3.common.MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(url)
            .setMimeType(mime)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album)
                    .setArtworkUri(art?.let(android.net.Uri::parse)).build(),
            )
            .build()
    }

    fun disconnectCast() = castController.disconnect()

    // ---- Online music (4.4): search, pages, downloads ----

    val onlineAvailable: Boolean get() = com.example.mimic.online.OnlineMusic.AVAILABLE

    /** Tracks seen in online results this session, by videoId, so a download keeps album ids / art. */
    private val onlineSeen = java.util.concurrent.ConcurrentHashMap<String, com.example.mimic.data.online.OnlineTrack>()

    /** Collections opened from search (the page route carries only the browse id). */
    private val onlineCollections = java.util.concurrent.ConcurrentHashMap<String, com.example.mimic.data.online.OnlineCollection>()

    suspend fun onlineSearch(query: String, filter: com.example.mimic.data.online.OnlineSearchFilter) =
        com.example.mimic.online.OnlineMusic.search(query, filter).onSuccess { r -> r.tracks.forEach { onlineSeen[it.videoId] = it } }

    suspend fun onlineSuggestions(input: String): List<String> = com.example.mimic.online.OnlineMusic.suggestions(input)

    fun rememberCollection(c: com.example.mimic.data.online.OnlineCollection) { onlineCollections[c.browseId] = c }

    fun collectionFor(browseId: String): com.example.mimic.data.online.OnlineCollection? = onlineCollections[browseId]

    suspend fun onlinePage(c: com.example.mimic.data.online.OnlineCollection) =
        com.example.mimic.online.OnlineMusic.collection(c).onSuccess { p -> p.tracks.forEach { onlineSeen[it.videoId] = it } }

    /** The online track behind [song]: as seen in results, as remembered, or rebuilt from the song. */
    private fun onlineTrackOf(song: Song): com.example.mimic.data.online.OnlineTrack? {
        val id = com.example.mimic.data.online.OnlineIds.videoIdOf(song.remoteId) ?: return null
        return onlineSeen[id]
            ?: onlineRows.value.firstOrNull { it.videoId == id }?.toTrack()
            ?: com.example.mimic.data.online.OnlineTrack(
                videoId = id, title = song.title, artist = song.artist, album = song.album,
                durationMs = song.durationMs, artworkUrl = song.albumArtUri?.takeIf { it.startsWith("http") },
            )
    }

    private fun rememberOnline(list: List<Song>, inLibrary: Boolean = false) {
        val tracks = list.filter { it.isOnline }.mapNotNull(::onlineTrackOf)
        if (tracks.isEmpty()) return
        viewModelScope.launch { onlineStore.remember(tracks, inLibrary) }
    }

    private fun downloadOnline(list: List<Song>) {
        val tracks = list.mapNotNull(::onlineTrackOf)
        if (tracks.isEmpty()) return
        val app = getApplication<Application>()
        tracks.forEach { com.example.mimic.online.OnlineMusic.download(app, it) }
        viewModelScope.launch { onlineStore.remember(tracks, inLibrary = true) }
        _messages.tryEmit(if (tracks.size == 1) "Downloading \"${tracks[0].title}\"…" else "Downloading ${tracks.size} songs…")
    }

    // ---- Playback ----

    fun play(queue: List<Song>, index: Int) {
        val c = controller ?: return
        if (queue.isEmpty()) return
        // Prefer the offline copy of any server track that has a verified download.
        val resolved = queue.map(::preferOffline)
        songsById = songsById + resolved.associateBy { it.id }
        rememberOnline(resolved)
        _uiState.update { it.copy(playbackError = null) }
        val start = index.coerceIn(0, resolved.size - 1)
        val tv = castPlayerIfCasting()
        if (tv != null) {
            // Casting: the TV plays it; the phone keeps the same queue (paused) for when casting ends.
            c.setMediaItems(resolved.map { it.toMediaItem() }, start, 0L)
            c.prepare()
            tv.setMediaItems(resolved.mapNotNull(::toCastItem), start, 0L)
            tv.prepare()
            tv.play()
            return
        }
        c.setMediaItems(resolved.map { it.toMediaItem() }, start, 0L)
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
        val p = activePlayer ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun next() = activePlayer?.seekToNextMediaItem()
    fun previous() = activePlayer?.seekToPreviousMediaItem()

    fun pause() {
        activePlayer?.pause()
    }

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
    fun seekTo(positionMs: Long) = activePlayer?.seekTo(positionMs)
    fun playQueueItem(index: Int) = activePlayer?.seekToDefaultPosition(index)

    fun toggleShuffle() {
        val c = activePlayer ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val c = activePlayer ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // ---- Queue editing ----

    fun addToQueue(song: Song) {
        rememberOnline(listOf(song))
        val s = preferOffline(song)
        songsById = songsById + (s.id to s)
        controller?.addMediaItem(s.toMediaItem())
        castPlayerIfCasting()?.let { tv -> toCastItem(s)?.let(tv::addMediaItem) }
    }

    fun addAllToQueue(list: List<Song>) {
        val c = controller ?: return
        if (list.isEmpty()) return
        if (c.mediaItemCount == 0) { play(list, 0); return }
        val resolved = list.map(::preferOffline)
        songsById = songsById + resolved.associateBy { it.id }
        c.addMediaItems(resolved.map { it.toMediaItem() })
        castPlayerIfCasting()?.addMediaItems(resolved.mapNotNull(::toCastItem))
        _messages.tryEmit("Added ${list.size} songs to the queue")
    }

    fun playNext(song: Song) {
        rememberOnline(listOf(song))
        val c = controller ?: return
        val s = preferOffline(song)
        songsById = songsById + (s.id to s)
        val insertAt = (c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount)
        c.addMediaItem(insertAt, s.toMediaItem())
        castPlayerIfCasting()?.let { tv -> toCastItem(s)?.let { tv.addMediaItem((tv.currentMediaItemIndex + 1).coerceAtMost(tv.mediaItemCount), it) } }
    }

    fun removeFromQueue(index: Int) {
        castPlayerIfCasting()?.removeMediaItem(index)
        controller?.removeMediaItem(index)
    }

    fun moveInQueue(from: Int, to: Int) {
        castPlayerIfCasting()?.moveMediaItem(from, to)
        controller?.moveMediaItem(from, to)
    }

    /** Clear Up Next: everything after the current song (the song itself keeps playing). */
    fun clearUpcoming() {
        castPlayerIfCasting()?.let { tv ->
            val from = tv.currentMediaItemIndex + 1
            if (from < tv.mediaItemCount) tv.removeMediaItems(from, tv.mediaItemCount)
        }
        val c = controller ?: return
        val from = c.currentMediaItemIndex + 1
        if (from < c.mediaItemCount) c.removeMediaItems(from, c.mediaItemCount)
    }

    /** Save the whole queue, in order, as a new playlist. */
    fun saveQueueAsPlaylist(name: String) {
        val ids = _queue.value.map { it.id }.distinct()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val id = playlistDao.create(PlaylistEntity(name = name, createdAt = System.currentTimeMillis()))
            ids.forEachIndexed { pos, songId -> playlistDao.insertMember(PlaylistSongEntity(id, songId, pos)) }
            _messages.tryEmit("Saved ${ids.size} songs to \"$name\"")
        }
    }

    // ---- Favorites ----

    /** Heart a song. Room updates first (Home/Library react at once); server tracks also star. */
    fun toggleFavorite(song: Song) {
        radioById[song.id]?.let { toggleRadioFavorite(it); return }
        rememberOnline(listOf(song), inLibrary = true)
        viewModelScope.launch {
            val account = serverCredentials.value?.let { SubsonicClient.accountKeyOf(it) }
            favoritesRepo.toggleSong(song, serverClient, account)
        }
    }

    /** Multi-select ♡: likes every selected song that isn't liked yet (never un-likes). */
    fun favoriteAll(list: List<Song>) {
        val favs = favoriteIds.value
        val todo = list.filter { it.id !in favs }
        todo.forEach(::toggleFavorite)
        _messages.tryEmit(if (todo.isEmpty()) "Already in Liked Songs" else "Added ${todo.size} to Liked Songs")
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

    fun createPlaylist(name: String, firstSong: Song? = null) = createPlaylist(name, listOfNotNull(firstSong))

    fun createPlaylist(name: String, songs: List<Song>) {
        rememberOnline(songs, inLibrary = true)
        viewModelScope.launch {
            val id = playlistDao.create(PlaylistEntity(name = name, createdAt = System.currentTimeMillis()))
            songs.forEach { addToPlaylistInternal(id, it.id) }
        }
    }

    /** Multi-select "Add to playlist": appends in the given order, skipping songs already in it. */
    fun addSongsToPlaylist(playlistId: Long, songs: List<Song>) {
        rememberOnline(songs, inLibrary = true)
        viewModelScope.launch {
            val existing = playlistDao.songIdsIn(playlistId).first().toSet()
            val added = songs.filter { it.id !in existing }
            added.forEach { addToPlaylistInternal(playlistId, it.id) }
            if (songs.size > 1) _messages.tryEmit("Added ${added.size} songs" + if (added.size < songs.size) " (${songs.size - added.size} already there)" else "")
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
        combine(playlistDao.songIdsIn(playlistId), knownSongs) { ids, songs ->
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

    /** Dynamic Now Playing settings only — other layouts and themes are untouched. */
    fun setDynamicPlayer(config: com.example.mimic.data.DynamicPlayerConfig) {
        viewModelScope.launch { settingsRepo.setDynamicPlayer(config) }
    }

    fun cycleLockStyle() {
        val cur = settings.value.dynamicPlayer
        val styles = com.example.mimic.data.LockStyle.entries
        setDynamicPlayer(cur.copy(lockStyle = styles[(cur.lockStyle.ordinal + 1) % styles.size]))
    }

    fun recordSearch(query: String) {
        val next = com.example.mimic.data.LibraryQueries.pushRecent(settings.value.recentSearches, query)
        if (next != settings.value.recentSearches) viewModelScope.launch { settingsRepo.setRecentSearches(next) }
    }

    fun removeRecentSearch(query: String) {
        viewModelScope.launch { settingsRepo.setRecentSearches(settings.value.recentSearches - query) }
    }

    fun clearRecentSearches() {
        viewModelScope.launch { settingsRepo.setRecentSearches(emptyList()) }
    }

    fun setLibraryConfig(config: com.example.mimic.data.LibraryConfig) {
        viewModelScope.launch { settingsRepo.setLibraryConfig(config) }
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

    /** Online download states by videoId (WorkManager-backed; full edition). */
    val onlineDownloads: StateFlow<Map<String, com.example.mimic.data.online.OnlineDownloadState>> =
        com.example.mimic.online.OnlineMusic.downloadStates(getApplication())
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Remote ids being downloaded: Navidrome ids, plus yt:<videoId> for queued / running online ones. */
    val downloading: StateFlow<Set<String>> = combine(_downloading, onlineDownloads) { server, online ->
        server + online.filterValues { it !is com.example.mimic.data.online.OnlineDownloadState.Failed }
            .keys.map(com.example.mimic.data.online.OnlineIds::remoteId)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** One-shot user messages (download finished / failed). */
    private val _messages = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: kotlinx.coroutines.flow.SharedFlow<String> = _messages

    private var serverClient: SubsonicClient? = null
    private var libraryById: Map<Long, Song> = emptyMap()

    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            viewModelScope.launch { if (_catalogState.value is CatalogState.Error) refreshServerCatalog() }
        }
    }

    init {
        // Keep the player's id -> Song lookup in step with the unified library, so the current
        // song / queue resolve for downloaded and server tracks as well as on-device files.
        viewModelScope.launch {
            knownSongs.collect { lib ->
                libraryById = lib.associateBy { it.id }
                songsById = songsById + libraryById
                controller?.let { syncFromPlayer(it); rebuildQueue(it) }
            }
        }
        // Back online after a failed load (airplane mode, tunnel, Wi-Fi switch): reload the server
        // catalog by itself, so server songs and their favorites reappear without a manual refresh.
        runCatching {
            val cm = getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(networkCallback)
        }
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

    /** Download a library song that comes from the server. No-op for on-device songs. */
    fun downloadSong(song: Song) {
        if (song.isOnline) {
            downloadOnline(listOf(song))
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
        val online = list.filter { it.isOnline && (!it.isDownloaded || it.fileMissing) }
        if (online.isNotEmpty()) downloadOnline(online)
        if (list.none { it.isFromServer }) return
        val wanted = list.filter { it.isFromServer && (!it.isDownloaded || it.fileMissing) }
        val todo = wanted.mapNotNull { s -> s.remoteId?.let { remoteById[it] } }
        if (todo.isEmpty()) {
            _messages.tryEmit("Nothing to download")
            return
        }
        _messages.tryEmit("Downloading ${todo.size} songs…")
        viewModelScope.launch {
            for (r in todo) download(r, announce = false)
        }
    }

    /** remoteId -> fraction downloaded (0..1, or -1 while the size is unknown). */
    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Navidrome downloads plus running online ones (keyed yt:<videoId>). */
    val downloadProgress: StateFlow<Map<String, Float>> = combine(_downloadProgress, onlineDownloads) { server, online ->
        server + online.mapNotNull { (id, st) ->
            (st as? com.example.mimic.data.online.OnlineDownloadState.Running)?.let { com.example.mimic.data.online.OnlineIds.remoteId(id) to it.fraction }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    private val downloadJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** Cancel a running download; the partial file is deleted, nothing is recorded. */
    fun cancelDownload(song: Song) {
        com.example.mimic.data.online.OnlineIds.videoIdOf(song.remoteId)?.let {
            com.example.mimic.online.OnlineMusic.cancelDownload(getApplication(), it)
        }
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

    /** Delete the offline copy only; the song stays on the server. */
    fun removeDownload(song: Song) {
        val remoteId = song.remoteId ?: return
        viewModelScope.launch {
            downloadRepo.remove(remoteId)
            _messages.tryEmit(if (com.example.mimic.data.LegacyCloudIds.isCloud(remoteId)) "Removed download" else "Removed download (still on your server)")
        }
    }

    /** Pull-to-refresh / "Sync now": the Navidrome catalog (phone music is rescanned on load). */
    fun refreshAllSources() {
        refreshServerCatalog()
        loadLibrary()
    }

    fun showMessage(text: String) {
        _messages.tryEmit(text)
    }

    // ---- Media downloader (full edition, MImic 4.0) ----

    private val _sharedLink = MutableStateFlow<String?>(null)

    /** A link shared/opened into MImic, waiting for the Downloads tab to analyze it. */
    val sharedLink: StateFlow<String?> = _sharedLink.asStateFlow()

    fun offerSharedLink(text: String) {
        _sharedLink.value = text
    }

    fun consumeSharedLink() {
        _sharedLink.value = null
    }

    private val _openUpdates = MutableStateFlow(false)
    val openUpdatesRequest: StateFlow<Boolean> = _openUpdates.asStateFlow()
    fun requestOpenUpdates() { _openUpdates.value = true }
    fun consumeOpenUpdates() { _openUpdates.value = false }

    /**
     * Play a file the downloader just saved. Music is published to MediaStore (Music/MImic), so
     * it is an ordinary phone song: the library's own entry (same MediaStore id → same song id,
     * favorites and history) is used when the scan has it; otherwise an equivalent entry is built.
     */
    fun playDownloadedFile(uri: String, title: String, artist: String?) {
        val mediaId = android.net.Uri.parse(uri).lastPathSegment?.toLongOrNull()
        val known = _localSongs.value.firstOrNull { it.uri == uri || (mediaId != null && it.id == mediaId && uri.contains("/audio/")) }
        val song = known ?: Song(
            id = mediaId?.takeIf { uri.contains("/audio/") } ?: (uri.hashCode().toLong() and 0x7FFFFFFF) + (1L shl 40),
            title = title,
            artist = artist ?: "Unknown artist",
            album = "MImic downloads",
            durationMs = 0,
            uri = uri,
            albumArtUri = null,
            dateAddedSec = System.currentTimeMillis() / 1000,
            folder = "MImic",
        )
        play(listOf(song), 0)
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

    // ---- Sleep timer (runs in PlaybackService, so it fires with the app closed) ----

    /** Wall-clock end of the sleep timer; -1 = end of the current song; 0 = off. */
    private val _sleepEndsAt = MutableStateFlow(0L)
    val sleepEndsAt: StateFlow<Long> = _sleepEndsAt.asStateFlow()

    private fun sleepCommand(args: android.os.Bundle) {
        val c = controller ?: return
        val future = c.sendCustomCommand(androidx.media3.session.SessionCommand(PlaybackService.CMD_SLEEP, android.os.Bundle.EMPTY), args)
        future.addListener({
            runCatching { future.get() }.getOrNull()?.extras?.let { _sleepEndsAt.value = it.getLong(PlaybackService.ARG_ENDS_AT) }
        }, MoreExecutors.directExecutor())
    }

    /** minutes > 0, or [PlaybackEffects.SLEEP_END_OF_SONG]. The last 20 s fade out. */
    fun startSleepTimer(minutes: Int) = sleepCommand(android.os.Bundle().apply { putInt(PlaybackService.ARG_MINUTES, minutes) })

    fun cancelSleepTimer() = sleepCommand(android.os.Bundle().apply { putInt(PlaybackService.ARG_MINUTES, 0) })

    /** Ask the service for the timer state (after reconnecting, e.g. app reopened). */
    private fun refreshSleepState() = sleepCommand(android.os.Bundle.EMPTY)

    // ---- Playback & audio settings (applied by PlaybackService) ----

    fun setAudio(prefs: com.example.mimic.data.AudioPrefs) {
        viewModelScope.launch { settingsRepo.setAudio(prefs) }
    }

    fun setSpeed(speed: Float) = setAudio(settings.value.audio.copy(speed = speed.coerceIn(0.5f, 2f)))

    /** The phone's equalizer layout (bands in Hz, level range in millibels), or null if it has none. */
    suspend fun equalizerInfo(): EqualizerInfo? {
        val c = controller ?: return null
        val future = c.sendCustomCommand(androidx.media3.session.SessionCommand(PlaybackService.CMD_EQ_INFO, android.os.Bundle.EMPTY), android.os.Bundle.EMPTY)
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { future.get(3, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull() }
        val b = result?.extras ?: return null
        if (!b.getBoolean(PlaybackEffects.KEY_AVAILABLE)) return null
        return EqualizerInfo(
            frequenciesHz = b.getIntArray(PlaybackEffects.KEY_FREQS)?.toList().orEmpty(),
            minLevel = b.getInt(PlaybackEffects.KEY_MIN),
            maxLevel = b.getInt(PlaybackEffects.KEY_MAX),
            bassBoost = b.getBoolean(PlaybackEffects.KEY_BASS),
        )
    }

    // ---- Lyrics (owned / local audio only; nothing is fetched from the internet) ----

    private val lyricsDir get() = java.io.File(getApplication<Application>().filesDir, "lyrics")
    private val lyricsReload = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val lyrics: StateFlow<LyricsState> =
        combine(uiState.map { it.currentSong }.distinctUntilChanged { a, b -> a?.id == b?.id && a?.uri == b?.uri }, lyricsReload) { s, _ -> s }
            .mapLatest { song -> if (song == null) LyricsState.None("Nothing playing") else loadLyrics(song) }
            .flowOn(kotlinx.coroutines.Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LyricsState.Loading)

    private fun loadLyrics(song: Song): LyricsState {
        // 1. A .lrc file the user added for this song.
        val imported = java.io.File(lyricsDir, "${song.id}.lrc")
        if (imported.isFile) {
            com.example.mimic.data.audio.Lyrics.parse(imported.readText(), "Your .lrc file")?.let { return LyricsState.Found(it) }
        }
        // 2. Lyrics embedded in the audio file (on-device or downloaded songs).
        val uri = android.net.Uri.parse(song.uri)
        if (uri.scheme == "content" || uri.scheme == "file") {
            val tags = runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                    com.example.mimic.data.audio.AudioTags.parse(PlaybackEffects.readHead(it, com.example.mimic.data.audio.AudioTags.HEAD_BYTES))
                }
            }.getOrNull()
            tags?.lyrics?.let { text -> com.example.mimic.data.audio.Lyrics.parse(text, "Embedded in the file")?.let { return LyricsState.Found(it) } }
            return LyricsState.None("No lyrics in this file. Add a .lrc file to show them.")
        }
        return LyricsState.None("Lyrics are read from files on this phone. Download the song, or add a .lrc file.")
    }

    /** Attach a user-picked .lrc / .txt file to the current song (copied into app storage). */
    fun importLyrics(uri: android.net.Uri) {
        val song = _uiState.value.currentSong ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val text = runCatching { getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            if (text == null || com.example.mimic.data.audio.Lyrics.parse(text, "") == null) {
                _messages.tryEmit("That file has no readable lyrics")
                return@launch
            }
            lyricsDir.mkdirs()
            java.io.File(lyricsDir, "${song.id}.lrc").writeText(text)
            lyricsReload.update { it + 1 }
            _messages.tryEmit("Lyrics added to \"${song.title}\"")
        }
    }

    fun removeImportedLyrics() {
        val song = _uiState.value.currentSong ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            java.io.File(lyricsDir, "${song.id}.lrc").delete()
            lyricsReload.update { it + 1 }
        }
    }

    // ---- Internals ----

    /**
     * Cold start with an idle player: bring back the last queue, paused at the saved song and
     * position (MImic 3.2). Waits briefly for the library to contain the saved song — server and
     * cloud songs arrive a moment after on-device ones — then restores whatever resolves; songs
     * that are gone (or unreachable and not downloaded) are skipped. Never interrupts playback the
     * user already started.
     */
    private fun restoreQueueIfIdle() {
        val c = controller ?: return
        if (c.mediaItemCount > 0) return
        viewModelScope.launch {
            val snap = container.playbackStateStore.read() ?: return@launch
            val lib = kotlinx.coroutines.withTimeoutOrNull(8_000) {
                knownSongs.first { lib -> !_loading.value && lib.any { it.id == snap.currentId } }
            } ?: knownSongs.value
            val byId = lib.associateBy { it.id }
            val items = snap.ids.mapNotNull { byId[it] }.map(::preferOffline)
            val (index, position) = snap.resumePoint(items.map { it.id }) ?: return@launch
            val player = controller ?: return@launch
            if (player.mediaItemCount > 0) return@launch // the user started something meanwhile
            songsById = songsById + items.associateBy { it.id }
            player.setMediaItems(items.map { it.toMediaItem() }, index, position)
            player.prepare() // paused: the mini-player and Continue Listening show it, ready to resume
        }
    }

    private fun syncFromPlayer(player: Player) {
        val current = player.currentMediaItem?.mediaId?.toLongOrNull()?.let { songsById[it] }
        val fav = current?.let { favoriteIds.value.contains(it.id) } ?: false
        val buffering = player.playbackState == Player.STATE_BUFFERING
        // A sleep timer that just fired paused the player: pick up its new (off) state.
        if (!player.isPlaying && _uiState.value.isPlaying && _sleepEndsAt.value != 0L) refreshSleepState()
        _currentIndex.value = player.currentMediaItemIndex
        val station = current?.let { radioById[it.id] }
        // ICY "StreamTitle" replaces the static title in the player's metadata while on air.
        val onAir = station?.let { st -> player.mediaMetadata.title?.toString()?.takeIf { t -> t.isNotBlank() && t != st.name } }
        _uiState.update {
            it.copy(
                isLive = station != null,
                liveTitle = onAir,
                currentSong = current,
                isPlaying = player.isPlaying,
                isBuffering = buffering,
                durationMs = player.duration.coerceAtLeast(0L),
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                isCurrentFavorite = if (station != null) station.id in radioFavoriteIds.value else fav,
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

    /** True while MImic is on screen; position polling pauses otherwise (battery, 4.1). */
    private val uiVisible = MutableStateFlow(true)
    fun setUiVisible(visible: Boolean) { uiVisible.value = visible }

    private fun startPositionUpdates() {
        viewModelScope.launch {
            while (true) {
                // Nothing on screen needs the position: wait (no polling) until MImic is visible again.
                if (!uiVisible.value) uiVisible.first { it }
                activePlayer?.let { c ->
                    if (casting) { lastCastIndex = c.currentMediaItemIndex; lastCastPositionMs = c.currentPosition }
                    val curId = c.currentMediaItem?.mediaId?.toLongOrNull()
                    val fav = curId?.let { id -> radioById[id]?.let { it.id in radioFavoriteIds.value } ?: favoriteIds.value.contains(id) } ?: false
                    _uiState.update {
                        it.copy(positionMs = c.currentPosition.coerceAtLeast(0L), bufferedPositionMs = c.bufferedPosition.coerceAtLeast(0L), isCurrentFavorite = fav)
                    }
                }
                delay(500L)
            }
        }
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
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
