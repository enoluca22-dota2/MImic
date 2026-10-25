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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
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
import com.example.mimic.ui.online.onlineResults
import kotlinx.coroutines.launch

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

/** Which result types Search shows (3.2 filter chips). */
private enum class ResultKind(val label: String) { ALL("All"), SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists") }

/**
 * Search across the unified library (device + server + cloud + downloads), respecting the shared
 * source filter. Results are grouped into artists, albums and songs (best matches first,
 * accent-insensitive); matching is pure and runs off the main thread so typing stays smooth.
 * With an empty field it shows the user's recent searches (kept on the phone only).
 */
@Composable
fun SearchScreen(
    vm: PlayerViewModel,
    contentPadding: PaddingValues,
    nav: com.example.mimic.ui.library.AppNav,
) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val filter by vm.sourceFilter.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var kind by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(ResultKind.ALL) }
    // 4.4: search the user's own library, or YouTube Music (full edition).
    var online by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var onlineFilter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(com.example.mimic.data.online.OnlineSearchFilter.ALL) }
    var onlineAttempt by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val onlineState by androidx.compose.runtime.produceState<com.example.mimic.ui.online.OnlineSearchState>(
        com.example.mimic.ui.online.OnlineSearchState.Idle, online, query, onlineFilter, onlineAttempt,
    ) {
        if (!online || query.isBlank()) { value = com.example.mimic.ui.online.OnlineSearchState.Idle; return@produceState }
        kotlinx.coroutines.delay(450) // debounce: a new keystroke cancels this search
        value = com.example.mimic.ui.online.OnlineSearchState.Loading
        value = vm.onlineSearch(query.trim(), onlineFilter).fold(
            onSuccess = { com.example.mimic.ui.online.OnlineSearchState.Done(it) },
            onFailure = { com.example.mimic.ui.online.OnlineSearchState.Error(com.example.mimic.ui.online.onlineErrorText(it)) },
        )
    }
    // "Load more" pages for the current online search (reset whenever the search changes).
    var moreTracks by remember(query, onlineFilter, onlineState) { mutableStateOf(emptyList<com.example.mimic.data.online.OnlineTrack>()) }
    var moreToken by remember(query, onlineFilter, onlineState) {
        mutableStateOf((onlineState as? com.example.mimic.ui.online.OnlineSearchState.Done)?.results?.nextToken)
    }
    var loadingMore by remember(query, onlineFilter, onlineState) { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val loadMore: () -> Unit = {
        val token = moreToken
        if (token != null && !loadingMore) {
            loadingMore = true
            scope.launch {
                vm.onlineSearchMore(token).onSuccess { moreTracks = moreTracks + it.tracks; moreToken = it.nextToken }
                    .onFailure { moreToken = null }
                loadingMore = false
            }
        }
    }
    val onlineSuggestions by androidx.compose.runtime.produceState(emptyList<String>(), online, query) {
        if (!online || query.isBlank()) { value = emptyList(); return@produceState }
        kotlinx.coroutines.delay(250)
        value = runCatching { vm.onlineSuggestions(query.trim()) }.getOrDefault(emptyList()).filter { !it.equals(query.trim(), true) }
    }
    val env = com.example.mimic.ui.library.rememberRowEnv(vm, nav)
    val results by androidx.compose.runtime.produceState(SearchResults(), query, songs, filter) {
        kotlinx.coroutines.delay(120) // debounce keystrokes
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val pool = com.example.mimic.data.UnifiedLibrary.filter(songs, filter)
            val hits = com.example.mimic.data.UnifiedLibrary.search(pool, query)
            val q = com.example.mimic.data.LibraryQueries.normalize(query)
            fun has(text: String) = com.example.mimic.data.LibraryQueries.normalize(text).contains(q)
            SearchResults(
                songs = hits,
                albums = if (q.isEmpty()) emptyList() else com.example.mimic.data.UnifiedLibrary.albums(pool)
                    .filter { has(it.title) || has(it.artist) }.take(20),
                artists = if (q.isEmpty()) emptyList() else com.example.mimic.data.UnifiedLibrary.artists(pool)
                    .filter { has(it.name) }.take(20),
            )
        }
    }
    // A search the user acted on (opened a result, pressed Search, or left with results showing)
    // is remembered for next time; half-typed queries are not.
    val saveQuery = {
        val found = if (online) onlineState is com.example.mimic.ui.online.OnlineSearchState.Done else !results.isEmpty()
        if (query.isNotBlank() && found) vm.recordSearch(query)
    }
    val latestRemember by androidx.compose.runtime.rememberUpdatedState(saveQuery)
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { latestRemember() } }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current

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
                placeholder = { Text(if (online) "Search YouTube Music" else "Songs, artists, albums") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) ({
                    androidx.compose.material3.IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                }) else null,
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { saveQuery(); focus.clearFocus() }),
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
        if (vm.onlineAvailable) item(key = "mode") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(selected = !online, onClick = { online = false }, label = { Text("My library") })
                androidx.compose.material3.FilterChip(selected = online, onClick = { online = true }, label = { Text("YouTube Music") })
            }
        }
        if (online) {
            run {
                onlineResults(
                    query = query, filter = onlineFilter, onFilter = { onlineFilter = it }, state = onlineState,
                    suggestions = onlineSuggestions, onSuggestion = { query = it; focus.clearFocus() },
                    onRetry = { onlineAttempt++ }, env = env, nav = nav, onOpened = { saveQuery() },
                    recent = settings.recentSearches, onRecent = { query = it },
                    moreTracks = moreTracks, canLoadMore = moreToken != null, loadingMore = loadingMore, onLoadMore = loadMore,
                )
            }
            return@LazyColumn
        }
        item(key = "filters") { com.example.mimic.ui.library.SourceFilterRow(vm) }
        if (query.isNotBlank()) {
            item(key = "kinds") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ResultKind.entries.forEach { k ->
                        androidx.compose.material3.FilterChip(
                            selected = k == kind,
                            onClick = { kind = k },
                            label = { Text(k.label) },
                        )
                    }
                }
            }
        }
        when {
            query.isBlank() && settings.recentSearches.isNotEmpty() -> {
                item(key = "recent_h") {
                    Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { com.example.mimic.ui.library.SectionTitle("Recent searches") }
                        androidx.compose.material3.TextButton(onClick = vm::clearRecentSearches, modifier = Modifier.padding(top = 16.dp)) { Text("Clear") }
                    }
                }
                items(settings.recentSearches, key = { "rs_$it" }) { q ->
                    Row(
                        Modifier.fillMaxWidth().clickable { query = q }.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        Text(q, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        androidx.compose.material3.IconButton(onClick = { vm.removeRecentSearch(q) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove $q from recent searches", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            query.isBlank() -> item(key = "hint") {
                Box(Modifier.fillParentMaxHeight(0.5f)) {
                    EmptyState("Find your music", "Search every song, artist and album — on this phone, on your server, in your cloud folders and in your downloads.")
                }
            }
            results.isEmpty() -> item(key = "none") {
                Box(Modifier.fillParentMaxHeight(0.5f)) {
                    EmptyState("No matches", "Nothing found for \"$query\" with the ${filter.label} filter.")
                }
            }
            else -> {
                val showArtists = kind == ResultKind.ALL || kind == ResultKind.ARTISTS
                val showAlbums = kind == ResultKind.ALL || kind == ResultKind.ALBUMS
                val showSongs = kind == ResultKind.ALL || kind == ResultKind.SONGS
                if (showArtists && results.artists.isNotEmpty()) {
                    item(key = "ar_h") { com.example.mimic.ui.library.SectionTitle("Artists") }
                    item(key = "ar_r") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(results.artists, key = { it.key }) { a ->
                                com.example.mimic.ui.library.ArtistCard(
                                    a.name, com.example.mimic.ui.library.countLabel(a.songs.size),
                                    a.songs.firstNotNullOfOrNull { it.albumArtUri }, onClick = { saveQuery(); nav.openArtist(a.key) }, size = 96.dp,
                                )
                            }
                        }
                    }
                }
                if (showAlbums && results.albums.isNotEmpty()) {
                    item(key = "al_h") { com.example.mimic.ui.library.SectionTitle("Albums") }
                    item(key = "al_r") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(results.albums, key = { it.key }) { a ->
                                com.example.mimic.ui.library.AlbumCard(a.title, a.artist, a.artUri, onClick = { saveQuery(); nav.openAlbum(a.key) }, width = 128.dp)
                            }
                        }
                    }
                }
                if (showSongs && results.songs.isNotEmpty()) {
                    item(key = "so_h") { com.example.mimic.ui.library.SectionTitle("Songs") }
                    songItems(results.songs, env)
                }
                val nothingOfKind = (kind == ResultKind.SONGS && results.songs.isEmpty()) ||
                    (kind == ResultKind.ALBUMS && results.albums.isEmpty()) ||
                    (kind == ResultKind.ARTISTS && results.artists.isEmpty())
                if (nothingOfKind) item(key = "none_kind") {
                    com.example.mimic.ui.library.InlineEmpty("No ${kind.label.lowercase()} match \"$query\".")
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
