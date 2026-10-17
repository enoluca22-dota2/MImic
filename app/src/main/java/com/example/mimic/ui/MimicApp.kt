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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
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
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    var nowPlayingOpen by remember { mutableStateOf(false) }

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
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
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
            composable("home") { HomeScreen(songs, innerPadding, viewModel::play) }
            composable("search") { SearchScreen(songs, innerPadding, viewModel::play) }
            composable("library") { LibraryScreen(songs, innerPadding, viewModel::play) }
        }
    }

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
        )
    }
}
