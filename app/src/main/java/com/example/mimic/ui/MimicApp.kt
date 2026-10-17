package com.example.mimic.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mimic.ui.designsystem.ThemeDefinition
import com.example.mimic.ui.designsystem.ThemeRegistry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.mimic.data.Song
import com.example.mimic.player.PlayerViewModel
import com.example.mimic.ui.components.MiniPlayer

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Default.Home),
    Tab("search", "Search", Icons.Default.Search),
    Tab("library", "Your Library", Icons.Default.LibraryMusic),
)

@Composable
fun MimicApp(viewModel: PlayerViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    var nowPlayingOpen by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    var menuSong by remember { mutableStateOf<Song?>(null) }
    var addSong by remember { mutableStateOf<Song?>(null) }
    var createDialog by remember { mutableStateOf(false) }
    var createForSong by remember { mutableStateOf<Song?>(null) }
    var editingTheme by remember { mutableStateOf<ThemeDefinition?>(null) }
    var importThemeOpen by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val onSongMenu: (Song) -> Unit = { menuSong = it }
    val shareTheme: (ThemeDefinition) -> Unit = { theme ->
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "MImic theme: ${theme.name}")
            putExtra(android.content.Intent.EXTRA_TEXT, viewModel.exportTheme(theme))
        }
        context.startActivity(android.content.Intent.createChooser(send, "Share theme"))
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Column {
                val current = uiState.currentSong
                if (current != null) {
                    MiniPlayer(
                        song = current,
                        isPlaying = uiState.isPlaying,
                        onPlayPause = viewModel::togglePlayPause,
                        onClick = { nowPlayingOpen = true },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                NavigationBar {
                    val backStack by navController.currentBackStackEntryAsState()
                    val currentDest = backStack?.destination
                    tabs.forEach { tab ->
                        val selected = currentDest?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                HomeScreen(viewModel, innerPadding, onOpenSettings = { navController.navigate("settings") }, onSongMenu)
            }
            composable("search") { SearchScreen(viewModel, innerPadding, onSongMenu) }
            composable("library") {
                LibraryScreen(
                    vm = viewModel,
                    contentPadding = innerPadding,
                    onOpenPlaylist = { navController.navigate("playlist/$it") },
                    onOpenFavorites = { navController.navigate("favorites") },
                    onCreatePlaylist = { createDialog = true },
                    onSongMenu = onSongMenu,
                )
            }
            composable("settings") {
                SettingsScreen(
                    viewModel,
                    onBack = { navController.popBackStack() },
                    onOpenThemeStudio = { navController.navigate("themestudio") },
                )
            }
            composable("themestudio") {
                ThemeStudioScreen(
                    vm = viewModel,
                    onEdit = { editingTheme = it },
                    onCreateNew = {
                        val base = ThemeRegistry.byId(ThemeRegistry.DEFAULT_ID)
                        editingTheme = base.copy(id = viewModel.newThemeId(), name = "My Theme", builtIn = false)
                    },
                    onImport = { importThemeOpen = true },
                    onExport = shareTheme,
                    onBack = { navController.popBackStack() },
                )
            }
            composable("favorites") {
                FavoritesScreen(viewModel, onBack = { navController.popBackStack() }, onSongMenu = onSongMenu)
            }
            composable("playlist/{id}") { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull() ?: return@composable
                val name = playlists.firstOrNull { it.id == id }?.name ?: "Playlist"
                PlaylistDetailScreen(viewModel, id, name, onBack = { navController.popBackStack() }, onSongMenu = onSongMenu)
            }
        }
    }

    // ---- Overlays, sheets and dialogs ----

    if (nowPlayingOpen && uiState.currentSong != null) {
        NowPlayingScreen(
            state = uiState,
            onBack = { nowPlayingOpen = false },
            onPlayPause = viewModel::togglePlayPause,
            onNext = { viewModel.next() },
            onPrevious = { viewModel.previous() },
            onSeek = { viewModel.seekTo(it) },
            onShuffle = { viewModel.toggleShuffle() },
            onRepeat = { viewModel.cycleRepeat() },
            onToggleFavorite = { uiState.currentSong?.let { viewModel.toggleFavorite(it) } },
            onOpenQueue = { queueOpen = true },
            onOpenSleep = { sleepOpen = true },
        )
    }

    if (queueOpen) QueueSheet(viewModel, onDismiss = { queueOpen = false })

    if (sleepOpen) {
        SleepTimerDialog(
            current = settings.sleepTimerMinutes,
            onDismiss = { sleepOpen = false },
            onSet = { viewModel.startSleepTimer(it) },
            onCancel = { viewModel.cancelSleepTimer() },
        )
    }

    menuSong?.let { song ->
        SongActionsSheet(
            song = song,
            isFavorite = viewModel.favoriteIds.collectAsStateWithLifecycle().value.contains(song.id),
            onDismiss = { menuSong = null },
            onPlayNext = { viewModel.playNext(song) },
            onAddToQueue = { viewModel.addToQueue(song) },
            onAddToPlaylist = { addSong = song; menuSong = null },
            onToggleFavorite = { viewModel.toggleFavorite(song) },
        )
    }

    addSong?.let { song ->
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { addSong = null },
            onPick = { viewModel.addSongToPlaylist(it, song.id) },
            onCreateNew = { createForSong = song; addSong = null },
        )
    }

    if (createDialog || createForSong != null) {
        val song = createForSong
        CreatePlaylistDialog(
            onDismiss = { createDialog = false; createForSong = null },
            onCreate = { viewModel.createPlaylist(it, song) },
        )
    }

    editingTheme?.let { theme ->
        ThemeEditorScreen(
            initial = theme,
            onSave = { edited, apply -> viewModel.saveCustomTheme(edited, apply) },
            onExport = shareTheme,
            onBack = { editingTheme = null },
        )
    }

    if (importThemeOpen) {
        ImportThemeDialog(
            onDismiss = { importThemeOpen = false },
            onImport = { json ->
                viewModel.importTheme(json).fold(
                    onSuccess = { null },
                    onFailure = { it.message ?: "Invalid theme file" },
                )
            },
        )
    }
}
