package com.example.mimic.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mimic.data.Song
import com.example.mimic.data.SourceFilter
import com.example.mimic.player.PlayerViewModel
import com.example.mimic.ui.BrowseType
import com.example.mimic.ui.components.ArtImage
import com.example.mimic.ui.components.SongRow
import com.example.mimic.ui.designsystem.LocalGlass

/**
 * Space taken by the floating mini-player + navigation at the bottom. Screens let their lists
 * scroll *under* those bars (so the Liquid Glass has real content to show through) and add this
 * as bottom contentPadding so the last row can still scroll clear of them.
 */
val LocalBarPadding = staticCompositionLocalOf { 0.dp }

/** Navigation callbacks shared by all library/home screens (one place, no per-screen plumbing). */
@Immutable
class AppNav(
    val openAlbum: (String) -> Unit,
    val openArtist: (String) -> Unit,
    val openPlaylist: (Long) -> Unit,
    val openDownloads: () -> Unit,
    val openFavorites: () -> Unit,
    val openSmart: () -> Unit,
    val openBrowse: (BrowseType) -> Unit,
    val openServerBrowse: () -> Unit,
    val createPlaylist: () -> Unit,
    val songMenu: (Song) -> Unit,
    val back: () -> Unit,
)

/** Per-screen snapshot of row state, collected once per screen instead of once per row. */
@Immutable
class RowEnv(
    val favoriteIds: Set<Long>,
    val downloading: Set<String>,
    val currentId: Long?,
    val vm: PlayerViewModel,
    val nav: AppNav,
)

@Composable
fun rememberRowEnv(vm: PlayerViewModel, nav: AppNav): RowEnv {
    val fav by vm.favoriteIds.collectAsStateWithLifecycle()
    val dl by vm.downloading.collectAsStateWithLifecycle()
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    val currentId = now.song?.id
    return remember(fav, dl, currentId, vm, nav) { RowEnv(fav, dl, currentId, vm, nav) }
}

@Composable
fun LibrarySongRow(song: Song, queue: List<Song>, index: Int, env: RowEnv, showAlbum: Boolean = true) {
    SongRow(
        song = song,
        onClick = { env.vm.play(queue, index) },
        isFavorite = song.id in env.favoriteIds,
        onToggleFavorite = { env.vm.toggleFavorite(song) },
        onMenu = { env.nav.songMenu(song) },
        isDownloading = song.remoteId != null && song.remoteId in env.downloading,
        isCurrent = song.id == env.currentId,
        showAlbum = showAlbum,
    )
}

/** Song rows for a LazyColumn; keys are the stable song ids (one row per logical song). */
fun LazyListScope.songItems(songs: List<Song>, env: RowEnv, showAlbum: Boolean = true, keyPrefix: String = "s") {
    items(count = songs.size, key = { "$keyPrefix${songs[it].id}" }, contentType = { "song" }) { i ->
        LibrarySongRow(songs[i], songs, i, env, showAlbum)
    }
}

/** ALL / ON DEVICE / SERVER / DOWNLOADED chips bound to the shared library filter. */
@Composable
fun SourceFilterRow(vm: PlayerViewModel, modifier: Modifier = Modifier) {
    val selected by vm.sourceFilter.collectAsStateWithLifecycle()
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SourceFilter.entries.forEach { f ->
            FilterChip(
                selected = f == selected,
                onClick = { vm.sourceFilter.value = f },
                label = { Text(f.label) },
            )
        }
    }
}

/**
 * Pull-to-refresh for the library tabs (MImic 3.1): re-checks the Navidrome catalog and every
 * cloud account (incremental — only changes are transferred). The spinner shows while any source
 * is still syncing, including syncs started elsewhere (Sync now, app launch).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RefreshableLibrary(vm: PlayerViewModel, contentPadding: PaddingValues, content: @Composable () -> Unit) {
    val catalog by vm.catalogState.collectAsStateWithLifecycle()
    val cloud by vm.cloudSyncState.collectAsStateWithLifecycle()
    val refreshing = catalog == com.example.mimic.player.CatalogState.Loading ||
        cloud.values.any { it is com.example.mimic.data.cloud.CloudSyncState.Syncing && it.phase.startsWith("Reading").not() }
    val state = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { vm.refreshAllSources() },
        state = state,
        modifier = Modifier.fillMaxSize(),
        indicator = {
            androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = contentPadding.calculateTopPadding()),
            )
        },
    ) { content() }
}

/** A section heading with an optional "See all". */
@Composable
fun SectionTitle(title: String, onSeeAll: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 22.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = LocalGlass.current.headerFontWeight,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (onSeeAll != null) TextButton(onClick = onSeeAll) { Text("See all") }
    }
}

/** Album / playlist card: artwork, title (2 lines), subtitle. */
@Composable
fun AlbumCard(title: String, subtitle: String, artUri: String?, onClick: () -> Unit, width: Dp = 148.dp) {
    val env = LocalGlass.current
    Column(Modifier.width(width).clickable(onClick = onClick)) {
        ArtImage(
            artUri,
            Modifier.size(width),
            androidx.compose.foundation.shape.RoundedCornerShape((env.style.cornerRadius * 0.45f).dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight(env.style.titleWeight.coerceAtLeast(500)),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Round artist tile. MImic has no artist photos, so it shows the artist's album artwork. */
@Composable
fun ArtistCard(name: String, subtitle: String, artUri: String?, onClick: () -> Unit, size: Dp = 112.dp) {
    Column(
        Modifier.width(size).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtImage(artUri, Modifier.size(size), CircleShape)
        Spacer(Modifier.height(6.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Inline (non-fullscreen) empty state for a section inside a scrolling page. */
@Composable
fun InlineEmpty(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null && onAction != null) TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) { Text(action) }
    }
}

fun countLabel(n: Int, one: String = "song", many: String = "songs") = if (n == 1) "1 $one" else "$n $many"
