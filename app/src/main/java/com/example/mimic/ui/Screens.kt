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
import androidx.compose.material.icons.filled.AutoAwesome
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
import com.example.mimic.ui.designsystem.LocalGlass
import com.example.mimic.ui.library.songItems

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = LocalGlass.current.headerFontWeight,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(start = 16.dp, top = 20.dp, bottom = 10.dp),
    )
}

/**
 * Search across the unified library (device + server + downloads), respecting the shared source
 * filter. Results are grouped into songs, albums and artists; matching is pure and runs off the
 * main thread so typing stays smooth on large libraries.
 */
@Composable
fun SearchScreen(
    vm: PlayerViewModel,
    contentPadding: PaddingValues,
    nav: com.example.mimic.ui.library.AppNav,
) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val filter by vm.sourceFilter.collectAsStateWithLifecycle()
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val env = com.example.mimic.ui.library.rememberRowEnv(vm, nav)
    val results by androidx.compose.runtime.produceState(SearchResults(), query, songs, filter) {
        kotlinx.coroutines.delay(120) // debounce keystrokes
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val pool = com.example.mimic.data.UnifiedLibrary.filter(songs, filter)
            val hits = com.example.mimic.data.UnifiedLibrary.search(pool, query)
            val q = query.trim()
            SearchResults(
                songs = hits,
                albums = if (q.isEmpty()) emptyList() else com.example.mimic.data.UnifiedLibrary.albums(pool)
                    .filter { it.title.contains(q, true) || it.artist.contains(q, true) }.take(20),
                artists = if (q.isEmpty()) emptyList() else com.example.mimic.data.UnifiedLibrary.artists(pool)
                    .filter { it.name.contains(q, true) }.take(20),
            )
        }
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    com.example.mimic.ui.navigation.ScrollToTopOnReselect(com.example.mimic.ui.navigation.TopLevel.SEARCH, listState)
    LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item(key = "title") { SectionHeader("Search") }
        item(key = "field") {
            // Pill-shaped search field; flat (no live glass) so typing/scrolling costs nothing extra.
            val searchShape = if (LocalGlass.current.style.glass) com.kyant.shapes.Capsule() else RoundedCornerShape(12.dp)
            androidx.compose.material3.TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Songs, artists, albums") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = searchShape,
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item(key = "filters") { com.example.mimic.ui.library.SourceFilterRow(vm) }
        when {
            query.isBlank() -> item(key = "hint") {
                Box(Modifier.fillParentMaxHeight(0.5f)) {
                    EmptyState("Find your music", "Search every song, artist and album — on this phone, on your server and in your downloads.")
                }
            }
            results.isEmpty() -> item(key = "none") {
                Box(Modifier.fillParentMaxHeight(0.5f)) {
                    EmptyState("No matches", "Nothing found for \"$query\" with the ${filter.label} filter.")
                }
            }
            else -> {
                if (results.artists.isNotEmpty()) {
                    item(key = "ar_h") { com.example.mimic.ui.library.SectionTitle("Artists") }
                    item(key = "ar_r") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(results.artists, key = { it.key }) { a ->
                                com.example.mimic.ui.library.ArtistCard(
                                    a.name, com.example.mimic.ui.library.countLabel(a.songs.size),
                                    a.songs.firstNotNullOfOrNull { it.albumArtUri }, onClick = { nav.openArtist(a.key) }, size = 96.dp,
                                )
                            }
                        }
                    }
                }
                if (results.albums.isNotEmpty()) {
                    item(key = "al_h") { com.example.mimic.ui.library.SectionTitle("Albums") }
                    item(key = "al_r") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(results.albums, key = { it.key }) { a ->
                                com.example.mimic.ui.library.AlbumCard(a.title, a.artist, a.artUri, onClick = { nav.openAlbum(a.key) }, width = 128.dp)
                            }
                        }
                    }
                }
                if (results.songs.isNotEmpty()) {
                    item(key = "so_h") { com.example.mimic.ui.library.SectionTitle("Songs") }
                    songItems(results.songs, env)
                }
            }
        }
    }
}

private data class SearchResults(
    val songs: List<Song> = emptyList(),
    val albums: List<com.example.mimic.data.AlbumGroup> = emptyList(),
    val artists: List<com.example.mimic.data.ArtistGroup> = emptyList(),
) {
    fun isEmpty() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty()
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
