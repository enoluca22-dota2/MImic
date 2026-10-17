package com.example.mimic.ui

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
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
import com.example.mimic.data.AccentColor
import com.example.mimic.data.Song
import com.example.mimic.data.ThemeMode
import com.example.mimic.data.db.PlaylistEntity
import com.example.mimic.player.PlayerViewModel
import com.example.mimic.ui.components.SongRow
import com.example.mimic.ui.theme.accentToColor

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
fun SettingsScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Settings", onBack)
        Text(
            "Theme",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        ThemeMode.entries.forEach { mode ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.setThemeMode(mode) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = settings.themeMode == mode, onClick = { vm.setThemeMode(mode) })
                Spacer(Modifier.width(8.dp))
                Text(
                    mode.label(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        Text(
            "Accent color",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AccentColor.entries.forEach { accent ->
                val selected = settings.accent == accent
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(accentToColor(accent))
                        .border(
                            width = if (selected) 3.dp else 0.dp,
                            color = MaterialTheme.colorScheme.onBackground,
                            shape = CircleShape,
                        )
                        .clickable { vm.setAccent(accent) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "MImic — a personal, offline music player. Plays audio stored on this device. " +
                "No account, no telemetry, nothing leaves your phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

private fun ThemeMode.label() = when (this) {
    ThemeMode.SYSTEM -> "Follow system"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
    ThemeMode.AMOLED -> "AMOLED black"
    ThemeMode.GLASS -> "Glass"
}

@Composable
fun SongListScreen(
    title: String,
    songs: List<Song>,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onSongMenu: (Song) -> Unit,
    favoriteIds: Set<Long> = emptySet(),
) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title, onBack)
        if (songs.isEmpty()) {
            EmptyState("Nothing here yet", "Songs you add will show up here.")
        } else {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Button(onClick = { onPlay(songs, 0) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Play all")
                }
            }
            LazyColumn {
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

@Composable
fun FavoritesScreen(vm: PlayerViewModel, onBack: () -> Unit, onSongMenu: (Song) -> Unit) {
    val favorites by vm.favoriteSongs.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    SongListScreen("Liked Songs", favorites, onBack, vm::play, onSongMenu, favoriteIds)
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
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(playlistName, onBack, trailing = {
            IconButton(onClick = { vm.deletePlaylist(playlistId); onBack() }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete playlist")
            }
        })
        if (songs.isEmpty()) {
            EmptyState("Empty playlist", "Use a song's menu → Add to playlist to fill it.")
        } else {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Button(onClick = { vm.play(songs, 0) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Play all")
                }
            }
            LazyColumn {
                items(count = songs.size, key = { songs[it].id }) { index ->
                    val song = songs[index]
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            SongRow(song = song, onClick = { vm.play(songs, index) })
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

@Composable
fun SleepTimerDialog(current: Int, onDismiss: () -> Unit, onSet: (Int) -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                if (current > 0) {
                    Text(
                        "Active: pausing in about $current min",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                listOf(15, 30, 45, 60).forEach { mins ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSet(mins); onDismiss() }.padding(vertical = 12.dp),
                    ) { Text("$mins minutes") }
                }
            }
        },
        confirmButton = {
            if (current > 0) TextButton(onClick = { onCancel(); onDismiss() }) { Text("Turn off") }
            else TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
            ActionRow(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                if (isFavorite) "Remove from Liked Songs" else "Add to Liked Songs") { onToggleFavorite(); onDismiss() }
            ActionRow(Icons.Default.PlayArrow, "Play next") { onPlayNext(); onDismiss() }
            ActionRow(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue") { onAddToQueue(); onDismiss() }
            ActionRow(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist") { onAddToPlaylist() }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(vm: PlayerViewModel, onDismiss: () -> Unit) {
    val queue by vm.queue.collectAsStateWithLifecycle()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            "Queue",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            items(count = queue.size, key = { queue[it].id.toString() + "_" + it }) { index ->
                val song = queue[index]
                val isCurrent = song.id == state.currentSong?.id
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.weight(1f).then(
                            if (isCurrent) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            else Modifier,
                        ),
                    ) {
                        SongRow(song = song, onClick = { vm.playQueueItem(index) })
                    }
                    IconButton(onClick = { vm.removeFromQueue(index) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove from queue")
                    }
                }
            }
        }
    }
}
