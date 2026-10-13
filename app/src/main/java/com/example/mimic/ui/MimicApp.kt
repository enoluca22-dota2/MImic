package com.example.mimic.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import com.example.mimic.ui.components.GlassNavBar
import com.example.mimic.ui.components.GlassNavItem
import com.example.mimic.ui.components.MiniPlayer
import com.example.mimic.data.resolve
import com.example.mimic.ui.designsystem.GlassEnv
import com.example.mimic.ui.designsystem.LocalGlass
import com.example.mimic.ui.designsystem.LocalGlassBackdrop
import com.example.mimic.ui.designsystem.blockTouchesBehind
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

@androidx.compose.runtime.Composable
private fun BottomBar(
    navController: androidx.navigation.NavHostController,
    navStyle: com.example.mimic.data.NavStyle,
) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentDest = backStack?.destination
    val compact = navStyle == com.example.mimic.data.NavStyle.COMPACT
    val goTo: (Tab) -> Unit = { tab ->
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    if (navStyle == com.example.mimic.data.NavStyle.GLASS) {
        // -1 on pushed sub-screens (Settings, Theme Studio, ...) so no tab looks selected there.
        val selectedIndex = tabs.indexOfFirst { tab -> currentDest?.hierarchy?.any { it.route == tab.route } == true }
        GlassNavBar(
            items = glassTabs,
            selectedIndex = selectedIndex,
            onSelect = { goTo(tabs[it]) },
            modifier = Modifier.navigationBarsPadding(),
        )
        return
    }
    val content: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        tabs.forEach { tab ->
            val selected = currentDest?.hierarchy?.any { it.route == tab.route } == true
            NavigationBarItem(
                selected = selected,
                onClick = { goTo(tab) },
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

private val glassTabs = tabs.map { GlassNavItem(it.label, it.icon) }

/**
 * Insets full-screen sub-screens (Settings, Player Layout, Account, etc.) by the Scaffold's
 * [padding]: the top keeps their header below the status-bar clock, the bottom keeps their last
 * rows above the mini-player + navigation bar. The three tab screens consume innerPadding
 * themselves (as LazyColumn contentPadding), so they are not wrapped here.
 */
@Composable
private fun InsetScreen(padding: PaddingValues, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(padding)) { content() }
}

@Composable
fun MimicApp(viewModel: PlayerViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val glassEnv = LocalGlass.current
    val navStyle = settings.homeConfig.navStyle.resolve(
        themeWantsGlassNav = glassEnv.style.glass && glassEnv.style.pillNav,
        glassTheme = glassEnv.style.glass,
    )
    // Live content layer for backdrop blur/refraction. Only recorded when a glass theme can
    // actually blur on this device + quality; otherwise nothing extra is drawn at all.
    val liveGlass = glassEnv.enabled && glassEnv.quality.blurFactor > 0f &&
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    val backdrop = rememberLayerBackdrop()
    val navMotion = navTransitions(glassEnv)

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

    CompositionLocalProvider(LocalGlassBackdrop provides if (liveGlass) backdrop else null) {
    // While a full-screen overlay is up, the screen beneath is hidden from TalkBack too.
    val overlayOpen = (nowPlayingOpen && uiState.currentSong != null) || editingTheme != null
    Scaffold(
        modifier = Modifier.fillMaxSize().then(if (overlayOpen) Modifier.clearAndSetSemantics {} else Modifier),
        containerColor = if (glassEnv.style.gradientBackground) Color.Transparent else MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
                val current = uiState.currentSong
                if (current != null) {
                    MiniPlayer(
                        song = current,
                        isPlaying = uiState.isPlaying,
                        onPlayPause = viewModel::togglePlayPause,
                        onClick = { nowPlayingOpen = true },
                        modifier = Modifier.padding(
                            horizontal = if (navStyle == com.example.mimic.data.NavStyle.GLASS) 14.dp else 8.dp,
                            vertical = 4.dp,
                        ),
                        isBuffering = uiState.isBuffering,
                        error = uiState.playbackError,
                    )
                }
                BottomBar(navController, navStyle)
            }
        },
    ) { innerPadding ->
        // The content (and the theme backdrop gradient) is recorded into the backdrop layer; the
        // nav bar / mini-player sample it. Lists still scroll edge-to-edge behind them.
        Box(
            Modifier
                .fillMaxSize()
                .then(if (liveGlass) Modifier.layerBackdrop(backdrop) else Modifier)
                .themeBackground(),
        ) {
        NavHost(
            navController = navController,
            startDestination = "home",
            enterTransition = { navMotion.first },
            exitTransition = { navMotion.second },
            popEnterTransition = { navMotion.first },
            popExitTransition = { navMotion.second },
        ) {
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
                InsetScreen(innerPadding) {
                    SettingsScreen(
                        viewModel,
                        onBack = { navController.popBackStack() },
                        onOpenThemeStudio = { navController.navigate("themestudio") },
                        onOpenPlayerStudio = { navController.navigate("playerstudio") },
                        onOpenHomeCustomize = { navController.navigate("homecustomize") },
                        onOpenMusicServer = { navController.navigate("musicserver") },
                    )
                }
            }
            composable("playerstudio") { InsetScreen(innerPadding) { PlayerLayoutStudioScreen(viewModel, onBack = { navController.popBackStack() }) } }
            composable("homecustomize") { InsetScreen(innerPadding) { HomeCustomizeScreen(viewModel, onBack = { navController.popBackStack() }) } }
            composable("musicserver") {
                InsetScreen(innerPadding) {
                    MusicServerScreen(
                        viewModel,
                        onBack = { navController.popBackStack() },
                        onOpenBrowse = { navController.navigate("serverbrowse") },
                    )
                }
            }
            composable("serverbrowse") { InsetScreen(innerPadding) { ServerBrowseScreen(viewModel, onBack = { navController.popBackStack() }) } }
            composable("downloads") {
                val downloads by viewModel.downloads.collectAsStateWithLifecycle()
                InsetScreen(innerPadding) {
                    SongListScreen(
                        title = "Downloads",
                        songs = downloads,
                        onBack = { navController.popBackStack() },
                        onPlay = viewModel::play,
                        onSongMenu = onSongMenu,
                    )
                }
            }
            composable("themestudio") {
                InsetScreen(innerPadding) {
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
            }
            composable("favorites") {
                InsetScreen(innerPadding) { FavoritesScreen(viewModel, onBack = { navController.popBackStack() }, onSongMenu = onSongMenu) }
            }
            composable("smartcollections") {
                InsetScreen(innerPadding) { SmartCollectionsScreen(viewModel, onBack = { navController.popBackStack() }, onSongMenu = onSongMenu) }
            }
            composable("browse/{type}") { entry ->
                val type = entry.arguments?.getString("type")
                    ?.let { runCatching { BrowseType.valueOf(it) }.getOrNull() }
                    ?: BrowseType.ALBUMS
                InsetScreen(innerPadding) { BrowseScreen(viewModel, type, onBack = { navController.popBackStack() }, onSongMenu = onSongMenu) }
            }
            composable("playlist/{id}") { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull() ?: return@composable
                val name = playlists.firstOrNull { it.id == id }?.name ?: "Playlist"
                InsetScreen(innerPadding) { PlaylistDetailScreen(viewModel, id, name, onBack = { navController.popBackStack() }, onSongMenu = onSongMenu) }
            }
        }
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
            onStop = { viewModel.stopPlayback(); nowPlayingOpen = false },
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
        // Full-screen overlay: opaque, inset from the system bars, and Back closes it (same rules
        // as the 2.8.4 Now Playing fix: nothing underneath may bleed through or receive touches).
        androidx.activity.compose.BackHandler { editingTheme = null }
        Box(
            Modifier
                .fillMaxSize()
                .blockTouchesBehind()
                .background(MaterialTheme.colorScheme.background)
                .systemBarsPadding(),
        ) {
            ThemeEditorScreen(
                initial = theme,
                quality = settings.glassQuality,
                onSave = { edited, apply -> viewModel.saveCustomTheme(edited, apply) },
                onExport = shareTheme,
                onBack = { editingTheme = null },
            )
        }
    }

}

/**
 * Screen transitions honoring the theme's motion settings: a short cross-fade whose duration
 * follows the transition speed, or no animation at all when motion / system animations are off.
 */
private fun navTransitions(env: GlassEnv): Pair<EnterTransition, ExitTransition> {
    if (env.motionScale == 0f) return EnterTransition.None to ExitTransition.None
    val ms = (220 / env.style.transitionSpeed).toInt()
    return fadeIn(tween(ms)) to fadeOut(tween(ms))
}

/**
 * The theme backdrop: a static vertical gradient (plus a soft accent glow on glass themes) painted
 * behind all content. Static => no per-frame work; it is what glass surfaces frost over.
 */
@Composable
private fun Modifier.themeBackground(): Modifier {
    val env = LocalGlass.current
    if (!env.style.gradientBackground) return this
    val scheme = MaterialTheme.colorScheme
    val top = if (env.style.gradientColor != 0L) Color(env.style.gradientColor) else lerp(scheme.background, scheme.primary, 0.22f)
    val glow = scheme.tertiary.copy(alpha = 0.22f)
    return this
        .background(Brush.verticalGradient(0f to top, 0.55f to scheme.background, 1f to scheme.background))
        .then(
            if (env.style.glass) {
                Modifier.background(
                    Brush.radialGradient(listOf(glow, Color.Transparent), center = Offset(900f, 260f), radius = 900f),
                )
            } else {
                Modifier
            },
        )
}
