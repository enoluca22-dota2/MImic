package com.example.mimic.ui

import androidx.compose.material.icons.filled.Share
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mimic.data.Song
import com.example.mimic.data.db.PlaylistEntity
import com.example.mimic.player.PlayerViewModel
import com.example.mimic.ui.components.SongRow
import com.example.mimic.ui.library.songItems
import androidx.compose.foundation.verticalScroll
import com.example.mimic.ui.designsystem.ThemeDefinition

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

@Composable
fun SettingsScreen(
    vm: PlayerViewModel,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    onOpenThemeStudio: () -> Unit,
    onOpenPlayerStudio: () -> Unit,
    onOpenHomeCustomize: () -> Unit,
    onOpenMusicServer: () -> Unit,
    onOpenMusicSources: () -> Unit = {},
    onOpenPlayback: () -> Unit = {},
    onOpenUpdates: () -> Unit = {},
    onOpenRadioCountries: () -> Unit = {},
    onOpenLibraryStorage: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onOpenAdvanced: () -> Unit = {},
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val current by vm.activeTheme.collectAsStateWithLifecycle()
    val status by vm.serverStatus.collectAsStateWithLifecycle()
    val creds by vm.serverCredentials.collectAsStateWithLifecycle()
    // Settings is a top-level tab (MImic 3.0): no Back arrow, scrolls under the glass nav.
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(contentPadding),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = com.example.mimic.ui.designsystem.LocalGlass.current.headerFontWeight,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
        )
        Text(
            "Appearance",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenThemeStudio).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThemeSwatch(current, size = 44)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Theme Studio", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "Current: ${current.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenPlayerStudio).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Player Layout", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "Layout: ${settings.playerLayout.layout.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenHomeCustomize).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Home, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Home & Navigation", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "Sections & nav style",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "Music",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenPlayback).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Playback & audio", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                val a = settings.audio
                Text(
                    listOfNotNull(
                        if (a.crossfadeSec > 0) "Crossfade ${a.crossfadeSec} s" else "Gapless",
                        "EQ".takeIf { a.eqEnabled },
                        "ReplayGain ${a.replayGain.label.lowercase()}".takeIf { a.replayGain != com.example.mimic.data.ReplayGainMode.OFF },
                        com.example.mimic.ui.player.formatSpeed(a.speed).takeIf { a.speed != 1f },
                    ).joinToString(" · ") + " · equalizer, sleep fade, headphones",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenRadioCountries).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Icon(androidx.compose.material.icons.Icons.Default.Radio, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Radio Countries", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                Text("Add, remove and reorder the countries shown on Radio", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenLibraryStorage).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Icon(androidx.compose.material.icons.Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Library & Storage", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                Text("Folders, minimum song length, refresh library, caches", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenBackup).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Icon(androidx.compose.material.icons.Icons.Default.SettingsBackupRestore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Backup & Restore", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                Text("Save or restore favorites, playlists, history, themes and settings", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenAdvanced).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Icon(androidx.compose.material.icons.Icons.Default.Build, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Advanced", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                Text("Troubleshooting: download diagnostics", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenUpdates).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("App Updates", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "MImic ${com.example.mimic.BuildConfig.VERSION_NAME} · check GitHub for a newer version",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenMusicSources).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Music Sources", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "Phone music, Navidrome, offline downloads",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenMusicServer).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Navidrome account", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    if (status == com.example.mimic.data.server.ServerStatus.Connected && creds != null) {
                        "Signed in as ${creds!!.username} · server library & downloads"
                    } else {
                        "Sign in & connect your music server"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "MImic — a personal music player for the audio on this phone, your Navidrome server " +
                "and your own music server. No telemetry; nothing is sent anywhere except to the services you connect.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/** A small rounded preview of a theme's core colors. */
@Composable
fun ThemeSwatch(theme: ThemeDefinition, size: Int) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(10.dp))
            .background(Color(theme.colors.background)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size((size * 0.45f).dp).clip(CircleShape)
                .background(Color(theme.colors.primary)),
        )
    }
}


@Composable
fun SongListScreen(
    title: String,
    songs: List<Song>,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onSongMenu: (Song) -> Unit,
    favoriteIds: Set<Long> = emptySet(),
    rowEnv: com.example.mimic.ui.library.RowEnv? = null,
    subtitle: String? = null,
) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title, onBack)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (songs.isEmpty()) {
            EmptyState("Nothing here yet", "Play, like or add music and this fills in on its own.")
        } else {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { onPlay(songs, 0) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Play all")
                }
                androidx.compose.material3.FilledTonalButton(onClick = { onPlay(songs.shuffled(), 0) }) {
                    Icon(Icons.Default.Shuffle, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Shuffle")
                }
            }
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = com.example.mimic.ui.library.LocalBarPadding.current)) {
                if (rowEnv != null) {
                    songItems(songs, rowEnv)
                } else {
                    items(count = songs.size, key = { songs[it].id }) { index ->
                        SongRow(
                            song = songs[index],
                            onClick = { onPlay(songs, index) },
                            isFavorite = favoriteIds.contains(songs[index].id),
                            onMenu = { onSongMenu(songs[index]) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistDetailScreen(
    vm: PlayerViewModel,
    playlistId: Long,
    playlistName: String,
    onBack: () -> Unit,
    onSongMenu: (Song) -> Unit,
) {
    val songsFlow = remember(playlistId) { vm.songsInPlaylist(playlistId) }
    val songs by songsFlow.collectAsStateWithLifecycle(emptyList())
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(playlistName, onBack, trailing = {
            IconButton(onClick = { renaming = true }) {
                Icon(Icons.Default.Edit, contentDescription = "Rename playlist")
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete playlist")
            }
        })
        if (renaming) {
            var name by remember { mutableStateOf(playlistName) }
            AlertDialog(
                onDismissRequest = { renaming = false },
                title = { Text("Rename playlist") },
                text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
                confirmButton = { TextButton(onClick = { vm.renamePlaylist(playlistId, name.trim()); renaming = false }, enabled = name.isNotBlank()) { Text("Rename") } },
                dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
            )
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete \"$playlistName\"?") },
                text = { Text("The playlist is removed. Its songs stay in your library.") },
                confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deletePlaylist(playlistId); onBack() }) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            )
        }
        Text(
            com.example.mimic.ui.library.countLabel(songs.size) + if (songs.isNotEmpty()) " · " + com.example.mimic.ui.components.formatDuration(songs.sumOf { it.durationMs }) else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (songs.isEmpty()) {
            EmptyState("Empty playlist", "Use a song's menu → Add to playlist to fill it.")
        } else {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { vm.play(songs, 0) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Play all")
                }
                androidx.compose.material3.FilledTonalButton(onClick = { vm.shufflePlay(songs) }) {
                    Icon(Icons.Default.Shuffle, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Shuffle")
                }
            }
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = com.example.mimic.ui.library.LocalBarPadding.current)) {
                items(count = songs.size, key = { songs[it].id }) { index ->
                    val song = songs[index]
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            SongRow(song = song, onClick = { vm.play(songs, index) }, onMenu = { onSongMenu(song) })
                        }
                        // Manual ordering via up/down keeps reorder reliable without drag gestures.
                        IconButton(
                            onClick = {
                                if (index > 0) vm.reorderPlaylist(playlistId, songs.map { it.id }.swap(index, index - 1))
                            },
                            enabled = index > 0,
                        ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up") }
                        IconButton(
                            onClick = {
                                if (index < songs.size - 1) {
                                    vm.reorderPlaylist(playlistId, songs.map { it.id }.swap(index, index + 1))
                                }
                            },
                            enabled = index < songs.size - 1,
                        ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down") }
                        IconButton(onClick = { vm.removeSongFromPlaylist(playlistId, song.id) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove")
                        }
                    }
                }
            }
        }
    }
}

private fun List<Long>.swap(a: Int, b: Int): List<Long> {
    val copy = toMutableList()
    val tmp = copy[a]; copy[a] = copy[b]; copy[b] = tmp
    return copy
}

// ---- Dialogs & sheets ----

@Composable
fun CreatePlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Playlist name") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) { onCreate(name.trim()); onDismiss() } },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AddToPlaylistDialog(
    playlists: List<PlaylistEntity>,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
    onCreateNew: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable { onCreateNew() }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text("New playlist")
                }
                playlists.forEach { pl ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(pl.id); onDismiss() }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text(pl.name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionsSheet(
    song: Song,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownload: (() -> Unit)? = null,
    onRemoveDownload: (() -> Unit)? = null,
    /** Non-null while this song is downloading: fraction 0..1, or < 0 while the size is unknown. */
    downloadProgress: Float? = null,
    onCancelDownload: (() -> Unit)? = null,
    onGoToAlbum: (() -> Unit)? = null,
    onGoToArtist: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                song.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Text(
                "${song.artist} · ${song.album}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            ActionRow(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                if (isFavorite) "Remove from Liked Songs" else "Add to Liked Songs") { onToggleFavorite(); onDismiss() }
            ActionRow(Icons.Default.PlayArrow, "Play next") { onPlayNext(); onDismiss() }
            ActionRow(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue") { onAddToQueue(); onDismiss() }
            ActionRow(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist") { onAddToPlaylist() }
            if (downloadProgress != null && onCancelDownload != null) {
                val pct = if (downloadProgress >= 0f) " ${(downloadProgress * 100).toInt()}%" else "…"
                ActionRow(Icons.Default.Close, "Downloading$pct — tap to cancel") { onCancelDownload(); onDismiss() }
            } else if (onDownload != null) {
                ActionRow(androidx.compose.material.icons.Icons.Default.Download, if (song.fileMissing) "Download again (file missing)" else "Download for offline") { onDownload(); onDismiss() }
            }
            if (onRemoveDownload != null) ActionRow(Icons.Default.Delete, if (song.provider == com.example.mimic.data.MusicProvider.NAVIDROME) "Remove download (stays on your server)" else "Remove download") { onRemoveDownload(); onDismiss() }
            if (onShare != null) ActionRow(androidx.compose.material.icons.Icons.Default.Share, "Share audio file") { onShare(); onDismiss() }
            if (onGoToAlbum != null) ActionRow(androidx.compose.material.icons.Icons.Default.Album, "Go to album") { onGoToAlbum(); onDismiss() }
            if (onGoToArtist != null) ActionRow(androidx.compose.material.icons.Icons.Default.Person, "Go to artist") { onGoToArtist(); onDismiss() }
        }
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Smart Mixes (MImic 3.2; "Smart Collections" before) — generated views over the user's own
 * library + play history. Every mix is a plain, deterministic rule (no AI, nothing leaves the
 * phone), and each says in one line why its songs were picked. They never alter manual playlists.
 */
enum class SmartMix(val title: String, val explanation: String) {
    RECENTLY_PLAYED("Recently Played", "The songs you played last, newest first"),
    MOST_PLAYED("Most Played", "Your most-played songs, by play count"),
    FAVORITE_ARTISTS("Favorite Artists Mix", "Songs by artists you ♡ or whose songs you liked, mixed so no artist dominates"),
    NEVER_PLAYED("Never Played", "Music in your library you haven't heard yet, newest additions first"),
    FORGOTTEN_FAVORITES("Forgotten Favorites", "Liked songs you haven't played recently"),
    SHUFFLE_DISCOVERY("Shuffle Discovery", "A daily shuffle that favors songs you rarely play, max 3 per artist"),
    LEAST_PLAYED("Least Played", "Songs you've played only a few times"),
}

/** Live contents of every mix (each is a VM StateFlow; nothing is computed in composition). */
@Composable
fun rememberSmartMixes(vm: PlayerViewModel): Map<SmartMix, List<Song>> {
    val recent by vm.recentlyPlayed.collectAsStateWithLifecycle()
    val most by vm.mostPlayed.collectAsStateWithLifecycle()
    val favArtists by vm.favoriteArtistsMix.collectAsStateWithLifecycle()
    val never by vm.neverPlayed.collectAsStateWithLifecycle()
    val forgotten by vm.forgottenFavorites.collectAsStateWithLifecycle()
    val discovery by vm.shuffleDiscovery.collectAsStateWithLifecycle()
    val least by vm.leastPlayed.collectAsStateWithLifecycle()
    return mapOf(
        SmartMix.RECENTLY_PLAYED to recent, SmartMix.MOST_PLAYED to most, SmartMix.FAVORITE_ARTISTS to favArtists,
        SmartMix.NEVER_PLAYED to never, SmartMix.FORGOTTEN_FAVORITES to forgotten,
        SmartMix.SHUFFLE_DISCOVERY to discovery, SmartMix.LEAST_PLAYED to least,
    )
}

@Composable
fun SmartCollectionsScreen(
    vm: PlayerViewModel,
    onBack: () -> Unit,
    onSongMenu: (Song) -> Unit,
    rowEnv: com.example.mimic.ui.library.RowEnv? = null,
    /** Open straight into one mix (from Home); Back then returns to where the user came from. */
    initialMix: SmartMix? = null,
    onOpenStats: (() -> Unit)? = null,
) {
    val mixes = rememberSmartMixes(vm)
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()

    var selected by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialMix) }
    val sel = selected
    if (sel == null) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Smart Mixes", onBack)
            Text(
                "Built on this phone from your own library and play history — simple rules, no AI, nothing is sent anywhere.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = com.example.mimic.ui.library.LocalBarPadding.current),
            ) {
                if (onOpenStats != null) item(key = "stats") {
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onOpenStats).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary) }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Listening stats", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                            Text("Plays, listening time, top artists and songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(SmartMix.entries.size, key = { SmartMix.entries[it].name }) { i ->
                    val mix = SmartMix.entries[i]
                    val songs = mixes[mix].orEmpty()
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = mix }.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(mix.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                            Text(mix.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("${songs.size}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = { vm.play(songs, 0) }, enabled = songs.isNotEmpty()) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play ${mix.title}")
                        }
                    }
                }
            }
        }
    } else {
        val leave = { if (initialMix != null) onBack() else selected = null }
        SongListScreen(
            title = sel.title,
            songs = mixes[sel].orEmpty(),
            onBack = leave,
            onPlay = vm::play,
            onSongMenu = onSongMenu,
            favoriteIds = favoriteIds,
            rowEnv = rowEnv,
            subtitle = sel.explanation,
        )
        androidx.activity.compose.BackHandler { leave() }
    }
}
