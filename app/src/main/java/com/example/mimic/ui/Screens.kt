package com.example.mimic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mimic.data.Song
import com.example.mimic.player.PlayerViewModel
import com.example.mimic.ui.components.AlbumArt
import com.example.mimic.ui.components.SongRow

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(start = 16.dp, top = 20.dp, bottom = 10.dp),
    )
}

@Composable
private fun SongCard(song: Song, onClick: () -> Unit) {
    Column(modifier = Modifier.width(144.dp).clickable(onClick = onClick)) {
        AlbumArt(song, modifier = Modifier.size(144.dp), corner = 8)
        Spacer(Modifier.height(6.dp))
        Text(
            song.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            song.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A titled horizontal carousel; renders nothing when [songs] is empty. */
private fun androidx.compose.foundation.lazy.LazyListScope.carousel(
    title: String,
    songs: List<Song>,
    onPlay: (List<Song>, Int) -> Unit,
) {
    if (songs.isEmpty()) return
    item(key = "h_$title") { SectionHeader(title) }
    item(key = "r_$title") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(songs, key = { it.id }) { song ->
                SongCard(song, onClick = { onPlay(songs, songs.indexOf(song)) })
            }
        }
    }
}

@Composable
fun HomeScreen(
    vm: PlayerViewModel,
    contentPadding: PaddingValues,
    onOpenSettings: () -> Unit,
    onSongMenu: (Song) -> Unit,
) {
    val loading by vm.loading.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val recentlyPlayed by vm.recentlyPlayed.collectAsStateWithLifecycle()
    val recentlyAdded by vm.recentlyAdded.collectAsStateWithLifecycle()
    val mostPlayed by vm.mostPlayed.collectAsStateWithLifecycle()
    val favorites by vm.favoriteSongs.collectAsStateWithLifecycle()
    val suggested by vm.suggested.collectAsStateWithLifecycle()
    val homeConfig by vm.settings.collectAsStateWithLifecycle()

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (songs.isEmpty()) {
        EmptyState("No music found", "Add audio files to your device, then pull to rescan from Library.")
        return
    }

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Good evening",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        }
        homeConfig.homeConfig.visibleOrdered().forEach { section ->
            val (title, list) = when (section) {
                com.example.mimic.data.HomeSection.RECENTLY_PLAYED -> "Recently played" to recentlyPlayed
                com.example.mimic.data.HomeSection.MOST_PLAYED -> "Jump back in" to mostPlayed
                com.example.mimic.data.HomeSection.FAVORITES -> "Your favorites" to favorites
                com.example.mimic.data.HomeSection.RECENTLY_ADDED -> "Recently added" to recentlyAdded
                com.example.mimic.data.HomeSection.SUGGESTED -> "Suggested for you" to suggested
            }
            carousel(title, list, vm::play)
        }

        item { SectionHeader("All songs") }
        items(count = songs.size, key = { songs[it].id }) { index ->
            SongRow(
                song = songs[index],
                onClick = { vm.play(songs, index) },
                isFavorite = favorites.any { it.id == songs[index].id },
                onMenu = { onSongMenu(songs[index]) },
            )
        }
    }
}

@Composable
fun SearchScreen(
    vm: PlayerViewModel,
    contentPadding: PaddingValues,
    onSongMenu: (Song) -> Unit,
) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val results = remember(query, songs) {
        com.example.mimic.data.LibraryQueries.search(songs, query)
    }
    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        SectionHeader("Search")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Songs, artists, albums") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        when {
            query.isBlank() ->
                EmptyState("Find your music", "Search across every song, artist and album on your device.")
            results.isEmpty() ->
                EmptyState("No matches", "Nothing found for \"$query\". Try a different spelling.")
            else -> LazyColumn {
                items(results, key = { it.id }) { song ->
                    val index = songs.indexOf(song)
                    SongRow(song = song, onClick = { vm.play(songs, index) }, onMenu = { onSongMenu(song) })
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(
    vm: PlayerViewModel,
    contentPadding: PaddingValues,
    onOpenPlaylist: (Long) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenDownloads: () -> Unit,
    onCreatePlaylist: () -> Unit,
    onBrowse: (BrowseType) -> Unit,
    onSongMenu: (Song) -> Unit,
) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val favorites by vm.favoriteSongs.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { SectionHeader("Your Library") }
        item {
            LibraryEntry(
                leading = {
                    Box(
                        Modifier.size(52.dp).clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary) }
                },
                title = "Liked Songs",
                subtitle = "${favorites.size} songs",
                onClick = onOpenFavorites,
            )
        }
        item {
            LibraryEntry(
                leading = {
                    Box(
                        Modifier.size(52.dp).clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                },
                title = "Downloads",
                subtitle = "${downloads.size} offline",
                onClick = onOpenDownloads,
            )
        }
        item {
            val browse = listOf(
                BrowseType.ALBUMS to Icons.Default.Album,
                BrowseType.ARTISTS to Icons.Default.Person,
                BrowseType.FOLDERS to Icons.Default.Folder,
                BrowseType.GENRES to Icons.Default.Label,
            )
            Column {
                browse.forEach { (type, icon) ->
                    LibraryEntry(
                        leading = {
                            Box(
                                Modifier.size(52.dp).clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        },
                        title = type.name.lowercase().replaceFirstChar { it.uppercase() },
                        subtitle = "Browse by ${type.name.lowercase()}",
                        onClick = { onBrowse(type) },
                    )
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Playlists",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCreatePlaylist) {
                    Icon(
                        androidx.compose.material.icons.Icons.Default.Add,
                        contentDescription = "New playlist",
                    )
                }
            }
        }
        if (playlists.isEmpty()) {
            item {
                Text(
                    "No playlists yet. Tap + to create one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        } else {
            items(playlists, key = { it.id }) { pl ->
                LibraryEntry(
                    leading = {
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                androidx.compose.material.icons.Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    title = pl.name,
                    subtitle = "Playlist",
                    onClick = { onOpenPlaylist(pl.id) },
                )
            }
        }
        item { SectionHeader("Songs") }
        item {
            Text(
                "${songs.size} on this device",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
            )
        }
        items(count = songs.size, key = { songs[it].id }) { index ->
            SongRow(
                song = songs[index],
                onClick = { vm.play(songs, index) },
                isFavorite = favorites.any { it.id == songs[index].id },
                onMenu = { onSongMenu(songs[index]) },
            )
        }
    }
}

@Composable
fun LibraryEntry(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyState(title: String, body: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
