package com.example.mimic.ui.online

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mimic.data.online.OnlineCollection
import com.example.mimic.data.online.OnlineCollectionKind
import com.example.mimic.data.online.OnlineCollectionPage
import com.example.mimic.data.online.OnlineSearchFilter
import com.example.mimic.data.online.OnlineSearchResults
import com.example.mimic.player.PlayerViewModel
import com.example.mimic.ui.components.ArtImage
import com.example.mimic.ui.library.AlbumCard
import com.example.mimic.ui.library.AppNav
import com.example.mimic.ui.library.ArtistCard
import com.example.mimic.ui.library.InlineEmpty
import com.example.mimic.ui.library.RowEnv
import com.example.mimic.ui.library.SectionTitle
import com.example.mimic.ui.library.songItems

/** What the online part of Search is showing. */
sealed interface OnlineSearchState {
    data object Idle : OnlineSearchState
    data object Loading : OnlineSearchState
    data class Done(val results: OnlineSearchResults) : OnlineSearchState
    data class Error(val message: String) : OnlineSearchState
}

/** Turns any search/page failure into one sentence a person can act on. */
fun onlineErrorText(e: Throwable?): String {
    val m = e?.message.orEmpty()
    return when {
        e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketTimeoutException ->
            "No connection to YouTube Music. Check the internet connection and try again."
        "429" in m -> "YouTube Music is limiting requests right now. Wait a minute and try again."
        "403" in m || "bot" in m.lowercase() -> "YouTube Music refused the request. Try again later, or turn off a VPN if one is on."
        m.isNotBlank() -> "Couldn't load results: ${m.take(140)}"
        else -> "Couldn't load results. Try again."
    }
}

/**
 * Search results from YouTube Music (full edition), drawn into Search's own list: filters,
 * suggestions, albums / artists / playlists as cards, songs as ordinary MImic rows (so play, queue,
 * favorite, add to playlist and download all work exactly like any other song).
 */
fun LazyListScope.onlineResults(
    query: String,
    filter: OnlineSearchFilter,
    onFilter: (OnlineSearchFilter) -> Unit,
    state: OnlineSearchState,
    suggestions: List<String>,
    onSuggestion: (String) -> Unit,
    onRetry: () -> Unit,
    env: RowEnv,
    nav: AppNav,
    onOpened: () -> Unit,
    recent: List<String> = emptyList(),
    onRecent: (String) -> Unit = {},
) {
    item(key = "on_filters") {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OnlineSearchFilter.entries.forEach { f -> FilterChip(selected = f == filter, onClick = { onFilter(f) }, label = { Text(f.label) }) }
        }
    }
    if (suggestions.isNotEmpty() && query.isNotBlank()) item(key = "on_sugg") {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            suggestions.take(6).forEach { s -> AssistChip(onClick = { onSuggestion(s) }, label = { Text(s, maxLines = 1) }) }
        }
    }
    when (state) {
        OnlineSearchState.Idle -> if (query.isBlank() && recent.isNotEmpty()) {
            item(key = "on_recent_h") { SectionTitle("Recent searches") }
            items(recent, key = { "on_rs_$it" }) { q ->
                TextButton(onClick = { onRecent(q) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(q, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        } else item(key = "on_idle") {
            com.example.mimic.ui.EmptyState("Search YouTube Music", "Songs, albums, artists and playlists. They play in MImic's own player and can be downloaded for offline listening.")
        }
        OnlineSearchState.Loading -> item(key = "on_loading") {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        is OnlineSearchState.Error -> item(key = "on_error") {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                TextButton(onClick = onRetry) { Text("Try again") }
            }
        }
        is OnlineSearchState.Done -> {
            val r = state.results
            if (r.tracks.isEmpty() && r.collections.isEmpty()) {
                item(key = "on_none") { InlineEmpty("Nothing on YouTube Music matches \"$query\".") }
                return
            }
            collectionRows(r.collections, nav, onOpened)
            if (r.tracks.isNotEmpty()) {
                item(key = "on_songs_h") { SectionTitle("Songs") }
                songItems(r.tracks.map { it.toSong() }, env, keyPrefix = "on")
            }
        }
    }
}

private fun LazyListScope.collectionRows(all: List<OnlineCollection>, nav: AppNav, onOpened: () -> Unit) {
    val groups = listOf(
        "Artists" to all.filter { it.kind == OnlineCollectionKind.ARTIST },
        "Albums" to all.filter { it.kind == OnlineCollectionKind.ALBUM },
        "Playlists" to all.filter { it.kind == OnlineCollectionKind.PLAYLIST },
    )
    for ((title, list) in groups) {
        if (list.isEmpty()) continue
        item(key = "on_${title}_h") { SectionTitle(title) }
        item(key = "on_${title}_r") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(list, key = { it.browseId }) { c ->
                    val open = { onOpened(); nav.openOnline(c) }
                    if (c.kind == OnlineCollectionKind.ARTIST) ArtistCard(c.title, c.subtitle, c.artworkUrl, onClick = open, size = 96.dp)
                    else AlbumCard(c.title, c.subtitle, c.artworkUrl, onClick = open, width = 128.dp)
                }
            }
        }
    }
}

/** An online album, playlist or artist: header, Play / Shuffle / Download, tracks, and (artists) releases. */
@Composable
fun OnlineCollectionScreen(vm: PlayerViewModel, browseId: String, nav: AppNav, onBack: () -> Unit) {
    val collection = remember(browseId) { vm.collectionFor(browseId) }
    var page by remember(browseId) { mutableStateOf<OnlineCollectionPage?>(null) }
    var error by remember(browseId) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(browseId, attempt) {
        val c = collection ?: run { error = "This page is no longer open. Search for it again."; return@LaunchedEffect }
        error = null
        vm.onlinePage(c).onSuccess { page = it }.onFailure { error = onlineErrorText(it) }
    }
    val env = com.example.mimic.ui.library.rememberRowEnv(vm, nav)
    val songs = remember(page) { page?.tracks?.map { it.toSong() }.orEmpty() }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "top") {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Text("YouTube Music", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "head") {
            val p = page
            val title = p?.title ?: collection?.title.orEmpty()
            val art = p?.artworkUrl ?: collection?.artworkUrl
            val isArtist = (p?.kind ?: collection?.kind) == OnlineCollectionKind.ARTIST
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ArtImage(art, Modifier.size(200.dp), if (isArtist) CircleShape else RoundedCornerShape(12.dp))
                Spacer(Modifier.height(12.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = p?.subtitle ?: collection?.subtitle.orEmpty()
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2)
                Spacer(Modifier.height(12.dp))
                if (songs.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.play(songs, 0) }) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Play") }
                    OutlinedButton(onClick = { vm.shufflePlay(songs) }) { Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(4.dp)); Text("Shuffle") }
                    OutlinedButton(onClick = { vm.downloadAll(songs) }) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(4.dp)); Text("Download") }
                }
            }
        }
        when {
            error != null -> item(key = "err") {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    TextButton(onClick = { attempt++ }) { Text("Try again") }
                }
            }
            page == null -> item(key = "load") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            else -> {
                if (songs.isEmpty()) item(key = "empty") { InlineEmpty("No playable songs here.") }
                else {
                    item(key = "songs_h") { SectionTitle(if (page!!.kind == OnlineCollectionKind.ARTIST) "Top songs" else com.example.mimic.ui.library.countLabel(songs.size)) }
                    songItems(songs, env, showAlbum = page!!.kind != OnlineCollectionKind.ALBUM, keyPrefix = "oc")
                }
                collectionRows(page!!.related, nav) {}
            }
        }
        item(key = "pad") { Spacer(Modifier.height(140.dp)) }
    }
}
