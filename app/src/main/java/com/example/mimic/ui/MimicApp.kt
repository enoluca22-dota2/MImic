package com.example.mimic.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.draw.clip
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

@androidx.compose.runtime.Composable
private fun BottomBar(
    navController: androidx.navigation.NavHostController,
    navStyle: com.example.mimic.data.NavStyle,
) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentDest = backStack?.destination
    val compact = navStyle == com.example.mimic.data.NavStyle.COMPACT
    val content: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
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
                label = if (compact) null else ({ Text(tab.label) }),
                alwaysShowLabel = !compact,
            )
        }
    }
    if (navStyle == com.example.mimic.data.NavStyle.FLOATING) {
        NavigationBar(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp)),
            content = content,
        )
    } else {
        NavigationBar(content = content)
    }
}

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
    var pendingExportTheme by remember { mutableStateOf<ThemeDefinition?>(null) }

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

    // SAF: write a theme JSON to a file the user picks.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val theme = pendingExportTheme
        if (uri != null && theme != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(viewModel.exportTheme(theme).toByteArray())
                }
            }
            android.widget.Toast.makeText(context, "Theme exported", android.widget.Toast.LENGTH_SHORT).show()
        }
        pendingExportTheme = null
    }

    // SAF: read a theme JSON file the user picks and import it.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            val msg = if (text == null) {
                "Could not read file"
            } else {
                viewModel.importTheme(text).fold(
                    onSuccess = { "Imported \"${it.name}\"" },
                    onFailure = { it.message ?: "Invalid theme file" },
                )
            }
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
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
                        isBuffering = uiState.isBuffering,
                        error = uiState.playbackError,
                    )
                }
                BottomBar(navController, settings.homeConfig.navStyle)
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
                    onOpenDownloads = { navController.navigate("downloads") },
                    onOpenSmart = { navController.navigate("smartcollections") },
                    onCreatePlaylist = { createDialog = true },
                    onBrowse = { navController.navigate("browse/${it.name}") },
                    onSongMenu = onSongMenu,
                )
            }
            composable("settings") {
                SettingsScreen(
                    viewModel,
                    onBack = { navController.popBackStack() },
                    onOpenThemeStudio = { navController.navigate("themestudio") },
                    onOpenPlayerStudio = { navController.navigate("playerstudio") },
                    onOpenHomeCustomize = { navController.navigate("homecustomize") },
                    onOpenMusicServer = { navController.navigate("musicserver") },
                )
            }
            composable("playerstudio") { PlayerLayoutStudioScreen(viewModel, onBack = { navController.popBackStack() }) }
            composable("homecustomize") { HomeCustomizeScreen(viewModel, onBack = { navController.popBackStack() }) }
            composable("musicserver") {
                MusicServerScreen(
                    viewModel,
                    onBack = { navController.popBackStack() },
                    onOpenBrowse = { navController.navigate("serverbrowse") },
                )
            }
            composable("serverbrowse") { ServerBrowseScreen(viewModel, onBack = { navController.popBackStack() }) }
            composable("downloads") {
                val downloads by viewModel.downloads.collectAsStateWithLifecycle()
                SongListScreen(
                    title = "Downloads",
                    songs = downloads,
                    onBack = { navController.popBackStack() },
                    onPlay = viewModel::play,
                    onSongMenu = onSongMenu,
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
                    onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "text/*")) },
                    onExportFile = { pendingExportTheme = it; exportLauncher.launch("mimic-theme-${it.name}.json") },
                    onShare = shareTheme,
                    onBack = { navController.popBackStack() },
                )
            }
            composable("favorites") {
                FavoritesScreen(viewModel, onBack = { navController.popBackStack() }, onSongMenu = onSongMenu)
            }
            composable("smartcollections") {
                SmartCollectionsScreen(viewModel, onBack = { navController.popBackStack() }, onSongMenu = onSongMenu)
            }
            composable("browse/{type}") { entry ->
                val type = entry.arguments?.getString("type")
                    ?.let { runCatching { BrowseType.valueOf(it) }.getOrNull() }
                    ?: BrowseType.ALBUMS
                BrowseScreen(viewModel, type, onBack = { navController.popBackStack() }, onSongMenu = onSongMenu)
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
            config = settings.playerLayout,
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

}
