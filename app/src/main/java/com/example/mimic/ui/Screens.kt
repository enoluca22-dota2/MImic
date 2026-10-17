package com.example.mimic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mimic.data.Song
import com.example.mimic.ui.components.AlbumArt
import com.example.mimic.ui.components.SongRow

@Composable
private fun ScreenTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
fun HomeScreen(
    songs: List<Song>,
    contentPadding: PaddingValues,
    onPlay: (List<Song>, Int) -> Unit,
) {
    val recent = remember(songs) { songs.sortedByDescending { it.dateAddedSec }.take(10) }
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { ScreenTitle("Good evening") }
        if (recent.isNotEmpty()) {
            item {
                Text(
                    "Recently added",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp),
                )
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(recent, key = { it.id }) { song ->
                        val index = songs.indexOf(song)
                        Column(
                            modifier = Modifier
                                .width(140.dp)
                                .clickable { onPlay(songs, index) },
                        ) {
                            AlbumArt(song, modifier = Modifier.size(140.dp), corner = 8)
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
                }
            }
        }
        item { ScreenTitle("All songs") }
        itemsIndexed(songs) { index, song ->
            SongRow(song = song, onClick = { onPlay(songs, index) })
        }
    }
}

// Small helper so HomeScreen can use an indexed list inside LazyColumn.
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    songs: List<Song>,
    itemContent: @Composable (Int, Song) -> Unit,
) {
    items(count = songs.size, key = { songs[it].id }) { index ->
        itemContent(index, songs[index])
    }
}

@Composable
fun SearchScreen(
    songs: List<Song>,
    contentPadding: PaddingValues,
    onPlay: (List<Song>, Int) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val results = remember(query, songs) {
        if (query.isBlank()) emptyList()
        else songs.filter {
            it.title.contains(query, true) ||
                it.artist.contains(query, true) ||
                it.album.contains(query, true)
        }
    }
    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        ScreenTitle("Search")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Songs, artists, albums") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (query.isNotBlank() && results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No matches for \"$query\"",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn {
                items(results, key = { it.id }) { song ->
                    val index = songs.indexOf(song)
                    SongRow(song = song, onClick = { onPlay(songs, index) })
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(
    songs: List<Song>,
    contentPadding: PaddingValues,
    onPlay: (List<Song>, Int) -> Unit,
) {
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { ScreenTitle("Your Library") }
        item {
            Text(
                "${songs.size} songs on this device",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
        }
        items(count = songs.size, key = { songs[it].id }) { index ->
            SongRow(song = songs[index], onClick = { onPlay(songs, index) })
        }
    }
}
