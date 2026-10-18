package com.example.mimic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mimic.data.LibraryGrouping
import com.example.mimic.data.Song
import com.example.mimic.data.SongGroup
import com.example.mimic.player.PlayerViewModel

enum class BrowseType { ALBUMS, ARTISTS, FOLDERS, GENRES }

private fun BrowseType.title() = when (this) {
    BrowseType.ALBUMS -> "Albums"
    BrowseType.ARTISTS -> "Artists"
    BrowseType.FOLDERS -> "Folders"
    BrowseType.GENRES -> "Genres"
}

private fun BrowseType.icon(): ImageVector = when (this) {
    BrowseType.ALBUMS -> Icons.Default.Album
    BrowseType.ARTISTS -> Icons.Default.Person
    BrowseType.FOLDERS -> Icons.Default.Folder
    BrowseType.GENRES -> Icons.Default.Label
}

@Composable
fun BrowseScreen(
    vm: PlayerViewModel,
    type: BrowseType,
    onBack: () -> Unit,
    onSongMenu: (Song) -> Unit,
    rowEnv: com.example.mimic.ui.library.RowEnv? = null,
) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val groups = remember(songs, type) {
        when (type) {
            BrowseType.ALBUMS -> LibraryGrouping.albums(songs)
            BrowseType.ARTISTS -> LibraryGrouping.artists(songs)
            BrowseType.FOLDERS -> LibraryGrouping.folders(songs)
            BrowseType.GENRES -> LibraryGrouping.genres(songs)
        }
    }
    var selected by remember { mutableStateOf<SongGroup?>(null) }

    val current = selected
    if (current == null) {
        Column(Modifier.fillMaxSize()) {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Text(
                    type.title(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            if (groups.isEmpty()) {
                EmptyState("Nothing to show", "No ${type.title().lowercase()} found in your library.")
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = com.example.mimic.ui.library.LocalBarPadding.current)) {
                    items(groups, key = { it.name }) { group ->
                        LibraryEntry(
                            leading = {
                                Box(
                                    Modifier.size(52.dp).clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(type.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            },
                            title = group.name,
                            subtitle = group.subtitle,
                            onClick = { selected = group },
                        )
                    }
                }
            }
        }
    } else {
        SongListScreen(
            title = current.name,
            songs = current.songs,
            onBack = { selected = null },
            onPlay = vm::play,
            onSongMenu = onSongMenu,
            rowEnv = rowEnv,
        )
        // System Back returns to the folder/genre list, not out of the whole browser.
        androidx.activity.compose.BackHandler { selected = null }
    }
}
