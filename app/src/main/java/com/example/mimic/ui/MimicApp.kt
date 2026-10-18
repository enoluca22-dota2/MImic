package com.example.mimic.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.mimic.data.NavStyle
import com.example.mimic.data.Song
import com.example.mimic.data.resolve
import com.example.mimic.player.PlayerViewModel
import com.example.mimic.ui.components.GlassNavBar
import com.example.mimic.ui.components.GlassNavItem
import com.example.mimic.ui.components.MiniPlayer
import com.example.mimic.ui.designsystem.GlassEnv
import com.example.mimic.ui.designsystem.LocalGlass
import com.example.mimic.ui.designsystem.LocalGlassBackdrop
import com.example.mimic.ui.designsystem.ThemeDefinition
import com.example.mimic.ui.designsystem.ThemeRegistry
import com.example.mimic.ui.designsystem.blockTouchesBehind
import com.example.mimic.ui.home.HomeScreen
import com.example.mimic.ui.library.AlbumDetailScreen
import com.example.mimic.ui.library.AppNav
import com.example.mimic.ui.library.ArtistDetailScreen
import com.example.mimic.ui.library.DownloadsScreen
import com.example.mimic.ui.library.FavoritesScreen
import com.example.mimic.ui.library.LibraryScreen
import com.example.mimic.ui.library.LocalBarPadding
import com.example.mimic.ui.library.rememberRowEnv
import com.example.mimic.ui.navigation.TopLevel
import com.example.mimic.ui.navigation.TopLevelNavigation
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

@Composable
private fun BottomBar(navController: NavHostController, navStyle: NavStyle, onReselect: (TopLevel) -> Unit) {
    // Recompose on every back-stack change; the owning tab is derived from the whole stack, so a
    // pushed screen (Album, Account, ...) keeps its tab highlighted.
    val entry by navController.currentBackStackEntryAsState()
    val routes = remember(entry) { navController.currentBackStack.value.map { it.destination.route } }
    val owner = TopLevelNavigation.owningTab(routes)
    val goTo: (TopLevel) -> Unit = { tab -> TopLevelNavigation.navigate(navController, tab)?.let(onReselect) }
    val compact = navStyle == NavStyle.COMPACT
    if (navStyle == NavStyle.GLASS) {
        GlassNavBar(
            items = glassTabs,
            selectedIndex = TopLevel.entries.indexOf(owner),
            onSelect = { goTo(TopLevel.entries[it]) },
            modifier = Modifier.navigationBarsPadding(),
        )
        return
    }
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        TopLevel.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == owner,
                onClick = { goTo(tab) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = if (compact) null else ({ Text(tab.label) }),
                alwaysShowLabel = !compact,
            )
        }
    }
    if (navStyle == NavStyle.FLOATING) {
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

private val glassTabs = TopLevel.entries.map { GlassNavItem(it.label, it.icon) }

/**
 * Hosts a pushed sub-screen. The top is inset below the status bar; the bottom is NOT padded:
 * lists scroll on under the floating mini-player / navigation (so glass has real content behind
 * it) and add [LocalBarPadding] as their own bottom contentPadding. [padBottom] keeps the old
 * fully-inset behavior for editor-style screens that aren't simple lists.
 */
@Composable
private fun InsetScreen(padding: PaddingValues, padBottom: Boolean = false, content: @Composable () -> Unit) {
    val bottom = padding.calculateBottomPadding()
    Box(
        Modifier.fillMaxSize().padding(
            top = padding.calculateTopPadding(),
            bottom = if (padBottom) bottom else 0.dp,
        ),
    ) {
        CompositionLocalProvider(LocalBarPadding provides if (padBottom) 0.dp else bottom) { content() }
    }
}

private fun enc(s: String): String = Uri.encode(s)

@Composable
fun MimicApp(viewModel: PlayerViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val tabReselect = remember { kotlinx.coroutines.flow.MutableSharedFlow<TopLevel>(extraBufferCapacity = 1) }
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
    var lyricsOpen by remember { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    val sleepEndsAt by viewModel.sleepEndsAt.collectAsStateWithLifecycle()
    var menuSong by remember { mutableStateOf<Song?>(null) }
    var addSongs by remember { mutableStateOf<List<Song>?>(null) }
    var createDialog by remember { mutableStateOf(false) }
    var createForSongs by remember { mutableStateOf<List<Song>?>(null) }
    var editingTheme by remember { mutableStateOf<ThemeDefinition?>(null) }
    var pendingExportTheme by remember { mutableStateOf<ThemeDefinition?>(null) }

    val context = LocalContext.current
    val onSongMenu: (Song) -> Unit = { menuSong = it }
    val back: () -> Unit = { navController.popBackStack() }
    val nav = remember(navController) {
        AppNav(
            openAlbum = { navController.navigate("album/${enc(it)}") },
            openArtist = { navController.navigate("artist/${enc(it)}") },
            openPlaylist = { navController.navigate("playlist/$it") },
            openDownloads = { navController.navigate("downloads") },
            openFavorites = { navController.navigate("favorites") },
            openSmart = { navController.navigate("smartcollections") },
            openStats = { navController.navigate("stats") },
            openMix = { navController.navigate("smartmix/${it.name}") },
            openBrowse = { navController.navigate("browse/${it.name}") },
            openServerBrowse = { navController.navigate("serverbrowse") },
            createPlaylist = { createDialog = true },
            songMenu = { menuSong = it },
            addToPlaylist = { addSongs = it },
            back = { navController.popBackStack() },
        )
    }

    // Download results etc. — short, non-blocking confirmations.
    LaunchedEffect(Unit) {
        viewModel.messages.collect { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() }
    }

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

    CompositionLocalProvider(
        LocalGlassBackdrop provides if (liveGlass) backdrop else null,
        com.example.mimic.ui.navigation.LocalTabReselect provides tabReselect,
    ) {
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
                            horizontal = if (navStyle == NavStyle.GLASS) 14.dp else 8.dp,
                            vertical = 4.dp,
                        ),
                        isBuffering = uiState.isBuffering,
                        error = uiState.playbackError,
                    )
                }
                BottomBar(navController, navStyle, onReselect = { tabReselect.tryEmit(it) })
            }
        },
    ) { innerPadding ->
        // The content (and the theme backdrop gradient) is recorded into the backdrop layer; the
        // nav bar / mini-player sample it. Lists scroll edge-to-edge behind them.
        Box(
            Modifier
                .fillMaxSize()
                .then(if (liveGlass) Modifier.layerBackdrop(backdrop) else Modifier)
                .themeBackground(),
        ) {
        val rowEnv = rememberRowEnv(viewModel, nav)
        NavHost(
            navController = navController,
            startDestination = TopLevel.HOME.route,
            enterTransition = { navMotion.first },
            exitTransition = { navMotion.second },
            popEnterTransition = { navMotion.first },
            popExitTransition = { navMotion.second },
        ) {
            // ---- The four top-level tabs ----
            composable(TopLevel.HOME.route) { HomeScreen(viewModel, innerPadding, nav) }
            composable(TopLevel.SEARCH.route) { SearchScreen(viewModel, innerPadding, nav) }
            composable(TopLevel.LIBRARY.route) { LibraryScreen(viewModel, innerPadding, nav) }
            composable(TopLevel.SETTINGS.route) {
                SettingsScreen(
                    viewModel,
                    contentPadding = innerPadding,
                    onOpenThemeStudio = { navController.navigate("themestudio") },
                    onOpenPlayerStudio = { navController.navigate("playerstudio") },
                    onOpenHomeCustomize = { navController.navigate("homecustomize") },
                    onOpenMusicServer = { navController.navigate("musicserver") },
                    onOpenMusicSources = { navController.navigate("sources") },
                    onOpenPlayback = { navController.navigate("playback") },
                )
            }

            // ---- Pushed screens (Back pops them; the owning tab stays highlighted) ----
            composable("album/{key}") { entry ->
                val key = entry.arguments?.getString("key")?.let(Uri::decode) ?: return@composable
                InsetScreen(innerPadding) { AlbumDetailScreen(viewModel, key, nav) }
            }
            composable("artist/{key}") { entry ->
                val key = entry.arguments?.getString("key")?.let(Uri::decode) ?: return@composable
                InsetScreen(innerPadding) { ArtistDetailScreen(viewModel, key, nav) }
            }
            composable("downloads") { InsetScreen(innerPadding) { DownloadsScreen(viewModel, nav) } }
            composable("favorites") { InsetScreen(innerPadding) { FavoritesScreen(viewModel, nav) } }
            composable("playerstudio") { InsetScreen(innerPadding, padBottom = true) { PlayerLayoutStudioScreen(viewModel, onBack = back) } }
            composable("homecustomize") { InsetScreen(innerPadding, padBottom = true) { HomeCustomizeScreen(viewModel, onBack = back) } }
            composable("musicserver") {
                InsetScreen(innerPadding) {
                    MusicServerScreen(
                        viewModel,
                        onBack = back,
                        onOpenBrowse = { navController.navigate("serverbrowse") },
                    )
                }
            }
            composable("sources") {
                InsetScreen(innerPadding) {
                    com.example.mimic.ui.sources.MusicSourcesScreen(
                        viewModel,
                        onBack = back,
                        onOpenNavidrome = { navController.navigate("musicserver") },
                        onChooseFolder = { navController.navigate("cloudfolder/${enc(it)}") },
                    )
                }
            }
            composable("cloudfolder/{key}") { entry ->
                val key = entry.arguments?.getString("key")?.let(Uri::decode) ?: return@composable
                InsetScreen(innerPadding) { com.example.mimic.ui.sources.CloudFolderPickerScreen(viewModel, key, onDone = back) }
            }
            composable("serverbrowse") {
                InsetScreen(innerPadding) {
                    ServerBrowseScreen(viewModel, onBack = back, onOpenAlbum = { navController.navigate("serveralbum/${enc(it)}") })
                }
            }
            composable("serveralbum/{id}") { entry ->
                val id = entry.arguments?.getString("id")?.let(Uri::decode) ?: return@composable
                InsetScreen(innerPadding) { ServerAlbumScreen(viewModel, id, onBack = back) }
            }
            composable("themestudio") {
                InsetScreen(innerPadding, padBottom = true) {
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
                        onBack = back,
                    )
                }
            }
            composable("stats") { InsetScreen(innerPadding) { StatsScreen(viewModel, onBack = back) } }
            composable("playback") {
                InsetScreen(innerPadding, padBottom = true) { com.example.mimic.ui.player.PlaybackSettingsScreen(viewModel, onBack = back) }
            }
            composable("smartcollections") {
                InsetScreen(innerPadding) { SmartCollectionsScreen(viewModel, onBack = back, onSongMenu = onSongMenu, rowEnv = rowEnv, onOpenStats = { navController.navigate("stats") }) }
            }
            composable("smartmix/{mix}") { entry ->
                val mix = entry.arguments?.getString("mix")?.let { runCatching { SmartMix.valueOf(it) }.getOrNull() }
                InsetScreen(innerPadding) { SmartCollectionsScreen(viewModel, onBack = back, onSongMenu = onSongMenu, rowEnv = rowEnv, initialMix = mix) }
            }
            composable("browse/{type}") { entry ->
                val type = entry.arguments?.getString("type")
                    ?.let { runCatching { BrowseType.valueOf(it) }.getOrNull() }
                    ?: BrowseType.ALBUMS
                InsetScreen(innerPadding) { BrowseScreen(viewModel, type, onBack = back, onSongMenu = onSongMenu, rowEnv = rowEnv) }
            }
            composable("playlist/{id}") { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull() ?: return@composable
                val name = playlists.firstOrNull { it.id == id }?.name ?: "Playlist"
                InsetScreen(innerPadding) { PlaylistDetailScreen(viewModel, id, name, onBack = back, onSongMenu = onSongMenu) }
            }
        }
        // Status-bar scrim (3.2): content scrolls edge-to-edge, so without this, titles scrolled
        // under the clock / battery icons collide with them.
        val bg = MaterialTheme.colorScheme.background
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(androidx.compose.foundation.layout.WindowInsets.Companion.statusBars)
                .background(Brush.verticalGradient(listOf(bg.copy(alpha = 0.92f), bg.copy(alpha = 0.7f)))),
        )
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
            onOpenLyrics = { lyricsOpen = true },
            onOpenSpeed = { speedOpen = true },
            speed = settings.audio.speed,
            sleepActive = sleepEndsAt != 0L,
            hapticSeek = settings.audio.hapticSeek,
        )
    }

    if (queueOpen) com.example.mimic.ui.player.QueueSheet(viewModel, onDismiss = { queueOpen = false })
    if (sleepOpen) com.example.mimic.ui.player.SleepTimerSheet(viewModel, onDismiss = { sleepOpen = false })
    if (lyricsOpen) com.example.mimic.ui.player.LyricsSheet(viewModel, onDismiss = { lyricsOpen = false })
    if (speedOpen) com.example.mimic.ui.player.SpeedSheet(viewModel, onDismiss = { speedOpen = false })

    menuSong?.let { song ->
        val canDownload = song.isFromServer && (!song.isDownloaded || song.fileMissing)
        val progress = viewModel.downloadProgress.collectAsStateWithLifecycle().value
        SongActionsSheet(
            song = song,
            isFavorite = viewModel.favoriteIds.collectAsStateWithLifecycle().value.contains(song.id),
            onDismiss = { menuSong = null },
            onPlayNext = { viewModel.playNext(song) },
            onAddToQueue = { viewModel.addToQueue(song) },
            onAddToPlaylist = { addSongs = listOf(song); menuSong = null },
            onToggleFavorite = { viewModel.toggleFavorite(song) },
            onDownload = if (canDownload) ({ viewModel.downloadSong(song) }) else null,
            onRemoveDownload = if (song.isDownloaded) ({ viewModel.removeDownload(song) }) else null,
            downloadProgress = song.remoteId?.let { progress[it] },
            onCancelDownload = { viewModel.cancelDownload(song) },
            onGoToAlbum = { nowPlayingOpen = false; nav.openAlbum(song.albumKey) },
            onGoToArtist = { nowPlayingOpen = false; nav.openArtist(song.artistKey) },
        )
    }

    addSongs?.let { songs ->
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { addSongs = null },
            onPick = { viewModel.addSongsToPlaylist(it, songs) },
            onCreateNew = { createForSongs = songs; addSongs = null },
        )
    }

    if (createDialog || createForSongs != null) {
        val songs = createForSongs.orEmpty()
        CreatePlaylistDialog(
            onDismiss = { createDialog = false; createForSongs = null },
            onCreate = { viewModel.createPlaylist(it, songs) },
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
