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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayerUiState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val isCurrentFavorite: Boolean = false,
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
            mediaItem?.mediaId?.toLongOrNull()?.let { recordPlay(it) }
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
        _uiState.update {
            it.copy(
                currentSong = current,
                isPlaying = player.isPlaying,
                durationMs = player.duration.coerceAtLeast(0L),
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                isCurrentFavorite = fav,
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
