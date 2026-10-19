package com.example.mimic.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Tune
import com.example.mimic.data.GroupSort
import com.example.mimic.data.GroupSorting
import com.example.mimic.data.movedTab
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mimic.data.AlbumGroup
import com.example.mimic.data.ArtistGroup
import com.example.mimic.data.Song
import com.example.mimic.data.SongSort
import com.example.mimic.data.UnifiedLibrary
import com.example.mimic.data.db.FavoriteGroupEntity
import com.example.mimic.player.CatalogState
import com.example.mimic.player.PlayerViewModel
import com.example.mimic.ui.BrowseType
import com.example.mimic.ui.EmptyState
import com.example.mimic.ui.components.ArtImage
import com.example.mimic.ui.components.formatDuration
import com.example.mimic.ui.designsystem.LocalGlass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Library categories; kept as an alias so existing references keep compiling (3.2 moved it to data). */
typealias LibraryCategory = com.example.mimic.data.LibraryTab

/** Grouping / sorting runs off the main thread so a large library never stalls scrolling. */
@Composable
internal fun <T> rememberComputed(initial: T, vararg keys: Any?, compute: () -> T): T {
    val state by produceState(initial, *keys) { value = withContext(Dispatchers.Default) { compute() } }
    return state
}

@Composable
fun DetailTopBar(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

@Composable
fun LibraryScreen(vm: PlayerViewModel, contentPadding: PaddingValues, nav: AppNav) {
    val all by vm.songs.collectAsStateWithLifecycle()
    val filter by vm.sourceFilter.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.libraryConfig
    val lastPlayed by vm.lastPlayed.collectAsStateWithLifecycle()
    val catalog by vm.catalogState.collectAsStateWithLifecycle()
    val visibleTabs = config.visibleTabs()
    var categoryState by rememberSaveable { mutableStateOf(LibraryCategory.SONGS) }
    // A tab the user just hid falls back to the first visible list tab.
    val category = categoryState.takeIf { it in visibleTabs } ?: visibleTabs.firstOrNull { it.opensInPlace() } ?: LibraryCategory.SONGS
    val env = rememberRowEnv(vm, nav)
    var editTabs by remember { mutableStateOf(false) }

    val filtered = rememberComputed(emptyList(), all, filter) { UnifiedLibrary.filter(all, filter) }
    val sorted = rememberComputed(emptyList(), filtered, config.songSort, config.songsReversed, lastPlayed) {
        UnifiedLibrary.sort(filtered, config.songSort, lastPlayed).let { if (config.songsReversed) it.asReversed() else it }
    }
    val albums = rememberComputed(emptyList(), filtered, config.groupSort, config.groupsReversed) {
        GroupSorting.albums(UnifiedLibrary.albums(filtered), config.groupSort, config.groupsReversed)
    }
    val artists = rememberComputed(emptyList(), filtered, config.groupSort, config.groupsReversed) {
        GroupSorting.artists(UnifiedLibrary.artists(filtered), config.groupSort, config.groupsReversed)
    }
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val favData = rememberFavoritesData(vm)
    var favKind by rememberSaveable { mutableStateOf(FavKind.SONGS) }
    var dlGrouping by rememberSaveable { mutableStateOf(DownloadGrouping.ALL) }

    // Multi-select (Songs tab): long-press a song to start; the selection survives scrolling.
    var selectedIds by rememberSaveable { mutableStateOf<List<Long>?>(null) }
    val selection = SongSelection(selectedIds?.toSet()) { s ->
        val cur = selectedIds.orEmpty()
        selectedIds = if (s.id in cur) cur - s.id else cur + s.id
    }
    if (selectedIds != null) androidx.activity.compose.BackHandler { selectedIds = null }
    if (category != LibraryCategory.SONGS && selectedIds != null) selectedIds = null

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    com.example.mimic.ui.navigation.ScrollToTopOnReselect(com.example.mimic.ui.navigation.TopLevel.LIBRARY, listState)
    RefreshableLibrary(vm, contentPadding) {
    LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item(key = "title") {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)) {
                Text(
                    "Library",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = LocalGlass.current.headerFontWeight,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    libraryCountLine(all, catalog),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "filters") { SourceFilterRow(vm) }
        item(key = "categories") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                visibleTabs.forEach { c ->
                    FilterChip(
                        selected = c == category,
                        onClick = {
                            when (c) {
                                LibraryCategory.GENRES -> nav.openBrowse(BrowseType.GENRES)
                                LibraryCategory.FOLDERS -> nav.openBrowse(BrowseType.FOLDERS)
                                LibraryCategory.SMART -> nav.openSmart()
                                else -> categoryState = c
                            }
                        },
                        label = { Text(c.label) },
                        leadingIcon = when (c) {
                            LibraryCategory.GENRES -> ({ Icon(Icons.Default.Label, null, Modifier.size(18.dp)) })
                            LibraryCategory.FOLDERS -> ({ Icon(Icons.Default.Folder, null, Modifier.size(18.dp)) })
                            LibraryCategory.SMART -> ({ Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)) })
                            else -> null
                        },
                    )
                }
                IconButton(onClick = { editTabs = true }) {
                    Icon(Icons.Default.Tune, contentDescription = "Edit library tabs")
                }
            }
        }
        when (category) {
            LibraryCategory.SONGS -> {
                val sel = selectedIds
                if (sel != null) {
                    item(key = "selbar") {
                        val chosen = remember(sel, sorted) { sorted.filter { it.id in sel.toSet() } }
                        SelectionBar(
                            count = chosen.size,
                            allSelected = chosen.size == sorted.size && sorted.isNotEmpty(),
                            downloadable = chosen.count { it.isFromServer && (!it.isDownloaded || it.fileMissing) },
                            onSelectAll = { selectedIds = if (chosen.size == sorted.size) emptyList() else sorted.map { it.id } },
                            onPlay = { vm.play(chosen, 0); selectedIds = null },
                            onQueue = { vm.addAllToQueue(chosen); selectedIds = null },
                            onPlaylist = { nav.addToPlaylist(chosen); selectedIds = null },
                            onFavorite = { vm.favoriteAll(chosen); selectedIds = null },
                            onDownload = { vm.downloadAll(chosen); selectedIds = null },
                            onClose = { selectedIds = null },
                        )
                    }
                } else {
                    item(key = "sortbar") {
                        SortBar(
                            count = sorted.size,
                            label = config.songSort.label,
                            options = SongSort.entries.map { it.label },
                            reversed = config.songsReversed,
                            onSort = { i -> vm.setLibraryConfig(config.copy(songSort = SongSort.entries[i])) },
                            onReverse = { vm.setLibraryConfig(config.copy(songsReversed = !config.songsReversed)) },
                            trailing = {
                                TextButton(onClick = { selectedIds = emptyList() }, enabled = sorted.isNotEmpty()) { Text("Select") }
                            },
                        )
                    }
                }
                if (sorted.isEmpty()) item(key = "empty") { InlineEmpty(emptyText(filter.label)) }
                songItems(sorted, env, selection = selection)
            }
            LibraryCategory.ALBUMS -> {
                item(key = "albumbar") {
                    SortBar(
                        count = albums.size, noun = "album" to "albums",
                        label = config.groupSort.label,
                        options = GroupSort.entries.map { it.label },
                        reversed = config.groupsReversed,
                        onSort = { i -> vm.setLibraryConfig(config.copy(groupSort = GroupSort.entries[i])) },
                        onReverse = { vm.setLibraryConfig(config.copy(groupsReversed = !config.groupsReversed)) },
                        trailing = { ViewToggle(config.albumsGrid) { vm.setLibraryConfig(config.copy(albumsGrid = !config.albumsGrid)) } },
                    )
                }
                if (albums.isEmpty()) item(key = "empty") { InlineEmpty(emptyText(filter.label)) }
                if (config.albumsGrid) albumGrid(albums, nav)
                else items(albums, key = { "all" + it.key }) { a -> AlbumListRow(a) { nav.openAlbum(a.key) } }
            }
            LibraryCategory.ARTISTS -> {
                val artistSorts = GroupSort.entries.filter { it != GroupSort.ARTIST }
                item(key = "artistbar") {
                    SortBar(
                        count = artists.size, noun = "artist" to "artists",
                        label = (if (config.groupSort == GroupSort.ARTIST) GroupSort.NAME else config.groupSort).label,
                        options = artistSorts.map { it.label },
                        reversed = config.groupsReversed,
                        onSort = { i -> vm.setLibraryConfig(config.copy(groupSort = artistSorts[i])) },
                        onReverse = { vm.setLibraryConfig(config.copy(groupsReversed = !config.groupsReversed)) },
                        trailing = { ViewToggle(config.artistsGrid) { vm.setLibraryConfig(config.copy(artistsGrid = !config.artistsGrid)) } },
                    )
                }
                if (artists.isEmpty()) item(key = "empty") { InlineEmpty(emptyText(filter.label)) }
                if (config.artistsGrid) artistGrid(artists, nav)
                else items(artists, key = { "ar" + it.key }) { a -> ArtistRow(a) { nav.openArtist(a.key) } }
            }
            LibraryCategory.PLAYLISTS -> playlistItems(vm, nav)
            LibraryCategory.FAVORITES -> favoritesSection(favData, favKind, { favKind = it }, env)
            LibraryCategory.DOWNLOADS -> downloadsSection(downloads, dlGrouping, { dlGrouping = it }, env)
            else -> Unit
        }
    }
    }
    if (editTabs) {
        LibraryTabsDialog(config, onChange = vm::setLibraryConfig, onDismiss = { editTabs = false })
    }
}

/** Tabs that switch the list in place (the others open their own screen). */
private fun LibraryCategory.opensInPlace() =
    this != LibraryCategory.GENRES && this != LibraryCategory.FOLDERS && this != LibraryCategory.SMART

private fun emptyText(filter: String) =
    if (filter == "All") "No music yet. Add audio files to your phone, or sign in to your Navidrome server in Settings → Music Sources."
    else "Nothing here with the \"$filter\" filter."

private fun libraryCountLine(all: List<Song>, catalog: CatalogState): String {
    val byProvider = all.groupingBy { it.provider }.eachCount()
    val dl = all.count { it.isDownloaded }
    val parts = buildList {
        add("${byProvider[com.example.mimic.data.MusicProvider.PHONE] ?: 0} on phone")
        byProvider[com.example.mimic.data.MusicProvider.NAVIDROME]?.let { add("$it Navidrome") }
        add("$dl downloaded")
    }
    val base = parts.joinToString(" · ")
    return when (catalog) {
        CatalogState.Loading -> "$base · loading server…"
        is CatalogState.Error -> "$base · server unavailable"
        else -> base
    }
}

/** Count · sort field menu · direction toggle · optional trailing action (Select, grid/list). */
@Composable
private fun SortBar(
    count: Int,
    label: String,
    options: List<String>,
    reversed: Boolean,
    onSort: (Int) -> Unit,
    onReverse: () -> Unit,
    noun: Pair<String, String> = "song" to "songs",
    trailing: @Composable () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(countLabel(count, noun.first, noun.second), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
        Box {
            TextButton(onClick = { open = true }) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, maxLines = 1)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, o ->
                    DropdownMenuItem(text = { Text(o) }, onClick = { onSort(i); open = false })
                }
            }
        }
        IconButton(onClick = onReverse) {
            Icon(
                if (reversed) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = if (reversed) "Reversed order" else "Normal order",
            )
        }
        trailing()
    }
}

@Composable
private fun ViewToggle(grid: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
            contentDescription = if (grid) "Show as list" else "Show as grid",
        )
    }
}

/** Multi-select actions for the selected songs. */
@Composable
private fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    downloadable: Int,
    onSelectAll: () -> Unit,
    onPlay: () -> Unit,
    onQueue: () -> Unit,
    onPlaylist: () -> Unit,
    onFavorite: () -> Unit,
    onDownload: () -> Unit,
    onClose: () -> Unit,
) {
    val enabled = count > 0
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Cancel selection") }
            Text(
                if (count == 0) "Tap songs to select" else "$count selected",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSelectAll) { Text(if (allSelected) "Select none" else "Select all") }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        ) {
            SelAction(Icons.Default.PlayArrow, "Play", enabled, onPlay)
            SelAction(Icons.AutoMirrored.Filled.QueueMusic, "Queue", enabled, onQueue)
            SelAction(Icons.AutoMirrored.Filled.PlaylistAdd, "Playlist", enabled, onPlaylist)
            SelAction(Icons.Default.Favorite, "Like", enabled, onFavorite)
            SelAction(Icons.Default.Download, if (downloadable > 0) "Download $downloadable" else "Download", downloadable > 0, onDownload)
        }
    }
}

@Composable
private fun SelAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(label)
    }
}

/** Reorder / hide Library tabs. Changes apply (and persist) immediately. */
@Composable
private fun LibraryTabsDialog(config: com.example.mimic.data.LibraryConfig, onChange: (com.example.mimic.data.LibraryConfig) -> Unit, onDismiss: () -> Unit) {
    val order = config.completeOrder()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Library tabs") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Choose which tabs show and their order.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                order.forEachIndexed { i, tab ->
                    val shown = tab !in config.hiddenTabs
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = shown,
                            onCheckedChange = {
                                val hidden = if (shown) config.hiddenTabs + tab else config.hiddenTabs - tab
                                // At least one tab always stays visible.
                                if (hidden.size < LibraryCategory.entries.size) onChange(config.copy(tabOrder = order, hiddenTabs = hidden))
                            },
                        )
                        Text(tab.label, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onChange(config.copy(tabOrder = order.movedTab(tab, -1))) }, enabled = i > 0) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move ${tab.label} up")
                        }
                        IconButton(onClick = { onChange(config.copy(tabOrder = order.movedTab(tab, 1))) }, enabled = i < order.size - 1) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move ${tab.label} down")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            TextButton(onClick = { onChange(config.copy(tabOrder = LibraryCategory.entries, hiddenTabs = emptySet())) }) { Text("Reset") }
        },
    )
}

/** Album as a list row (list view): cover, title, artist · song count. */
@Composable
private fun AlbumListRow(a: AlbumGroup, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(a.artUri, Modifier.size(56.dp), RoundedCornerShape(8.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${a.artist} · ${countLabel(a.songs.size)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Three-column artist grid (round photos) inside a LazyColumn. */
private fun androidx.compose.foundation.lazy.LazyListScope.artistGrid(artists: List<ArtistGroup>, nav: AppNav) {
    val rows = artists.chunked(3)
    items(rows.size, key = { "arg" + rows[it].first().key }) { r ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            rows[r].forEach { a ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                    androidx.compose.foundation.layout.BoxWithConstraints {
                        ArtistCard(a.name, countLabel(a.songs.size), a.songs.firstNotNullOfOrNull { it.albumArtUri }, onClick = { nav.openArtist(a.key) }, size = maxWidth)
                    }
                }
            }
            repeat(3 - rows[r].size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** Two-column album grid inside a LazyColumn (rows of cards). */
private fun androidx.compose.foundation.lazy.LazyListScope.albumGrid(albums: List<AlbumGroup>, nav: AppNav) {
    val rows = albums.chunked(2)
    items(rows.size, key = { "alr" + rows[it].first().key }) { r ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            rows[r].forEach { a ->
                Box(Modifier.weight(1f)) {
                    androidx.compose.foundation.layout.BoxWithConstraints {
                        AlbumCard(a.title, "${a.artist} · ${countLabel(a.songs.size)}", a.artUri, onClick = { nav.openAlbum(a.key) }, width = maxWidth)
                    }
                }
            }
            if (rows[r].size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ArtistRow(a: ArtistGroup, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(a.songs.firstNotNullOfOrNull { it.albumArtUri }, Modifier.size(56.dp), CircleShape)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${countLabel(a.songs.size)} · ${countLabel(a.albums.size, "album", "albums")}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.playlistItems(vm: PlayerViewModel, nav: AppNav) {
    item(key = "pl_new") {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = nav.createPlaylist).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(14.dp))
            Text("New playlist", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
        }
    }
    item(key = "pl_list") {
        val playlists by vm.playlists.collectAsStateWithLifecycle()
        Column {
            if (playlists.isEmpty()) InlineEmpty("No playlists yet. Create one, or use a song's ⋮ menu → Add to playlist.")
            playlists.forEach { pl -> PlaylistRow(pl.name) { nav.openPlaylist(pl.id) } }
        }
    }
}

@Composable
fun PlaylistRow(name: String, subtitle: String = "Playlist", onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

enum class FavKind(val label: String) { SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists") }

/** Everything the favorites section shows, collected by the hosting screen. */
class FavoritesData(
    val songs: List<Song>,
    val groups: List<FavoriteGroupEntity>,
    val pending: Int,
    val library: List<Song>,
)

@Composable
fun rememberFavoritesData(vm: PlayerViewModel): FavoritesData {
    val favSongs by vm.favoriteSongs.collectAsStateWithLifecycle()
    val groups by vm.favoriteGroups.collectAsStateWithLifecycle()
    val pending by vm.pendingFavoriteSyncs.collectAsStateWithLifecycle()
    val filter by vm.sourceFilter.collectAsStateWithLifecycle()
    val all by vm.songs.collectAsStateWithLifecycle()
    return FavoritesData(favSongs.filter(filter::matches), groups, pending, all)
}

/** Favorites: songs / albums / artists, as lazy rows. */
fun androidx.compose.foundation.lazy.LazyListScope.favoritesSection(
    data: FavoritesData,
    kind: FavKind,
    onKind: (FavKind) -> Unit,
    env: RowEnv,
) {
    item(key = "fav_kinds") {
        Column {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FavKind.entries.forEach { k -> FilterChip(selected = k == kind, onClick = { onKind(k) }, label = { Text(k.label) }) }
            }
            if (data.pending > 0) {
                Text(
                    "${data.pending} favorite change${if (data.pending == 1) "" else "s"} will sync to your server when it's reachable.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
    when (kind) {
        FavKind.SONGS -> {
            if (data.songs.isEmpty()) item(key = "fav_empty") { InlineEmpty("Tap ♡ on any song to save it here.") }
            songItems(data.songs, env, keyPrefix = "fav")
        }
        FavKind.ALBUMS -> {
            val favAlbums = data.groups.filter { it.kind == FavoriteGroupEntity.KIND_ALBUM }
            if (favAlbums.isEmpty()) item(key = "fav_empty") { InlineEmpty("Open an album and tap ♡ to save it here.") }
            items(favAlbums, key = { "fa" + it.groupKey }) { g ->
                val art = data.library.firstOrNull { it.albumKey == g.groupKey }?.albumArtUri
                GroupRow(g.name, g.subtitle, art, RoundedCornerShape(8.dp)) { env.nav.openAlbum(g.groupKey) }
            }
        }
        FavKind.ARTISTS -> {
            val favArtists = data.groups.filter { it.kind == FavoriteGroupEntity.KIND_ARTIST }
            if (favArtists.isEmpty()) item(key = "fav_empty") { InlineEmpty("Open an artist and tap ♡ to save them here.") }
            items(favArtists, key = { "fr" + it.groupKey }) { g ->
                val art = data.library.firstOrNull { it.artistKey == g.groupKey }?.albumArtUri
                GroupRow(g.name, g.subtitle, art, CircleShape) { env.nav.openArtist(g.groupKey) }
            }
        }
    }
}

@Composable
private fun GroupRow(title: String, subtitle: String, art: String?, shape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(art, Modifier.size(56.dp), shape)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

enum class DownloadGrouping(val label: String) { ALL("All downloads"), ALBUM("By album"), ARTIST("By artist") }

/** Downloads: everything that plays offline, optionally grouped by album or artist (lazy rows). */
fun androidx.compose.foundation.lazy.LazyListScope.downloadsSection(
    downloads: List<Song>,
    grouping: DownloadGrouping,
    onGrouping: (DownloadGrouping) -> Unit,
    env: RowEnv,
) {
    item(key = "dl_head") {
        Column {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DownloadGrouping.entries.forEach { g -> FilterChip(selected = g == grouping, onClick = { onGrouping(g) }, label = { Text(g.label) }) }
            }
            val missing = downloads.count { it.fileMissing }
            Text(
                "${countLabel(downloads.size)} · playable offline" + if (missing > 0) " · $missing missing (tap ⋮ to re-download)" else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (downloads.isEmpty()) {
                InlineEmpty(
                    "Downloaded songs appear here and on Home. Download from Settings → Account → Server Library, or from any server song's ⋮ menu.",
                    "Open server library", env.nav.openServerBrowse,
                )
            }
        }
    }
    when (grouping) {
        DownloadGrouping.ALL -> songItems(downloads, env, keyPrefix = "dl")
        DownloadGrouping.ALBUM -> UnifiedLibrary.albums(downloads).forEach { a ->
            item(key = "dlh" + a.key) { SubHeader(a.title, a.artist) }
            songItems(a.songs, env, showAlbum = false, keyPrefix = "dl" + a.key)
        }
        DownloadGrouping.ARTIST -> UnifiedLibrary.artists(downloads).forEach { a ->
            item(key = "dlh" + a.key) { SubHeader(a.name, countLabel(a.songs.size)) }
            songItems(a.songs, env, keyPrefix = "dl" + a.key)
        }
    }
}

@Composable
private fun SubHeader(title: String, subtitle: String) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- Standalone pages (pushed from Home "See all") ----

@Composable
fun DownloadsScreen(vm: PlayerViewModel, nav: AppNav) {
    val env = rememberRowEnv(vm, nav)
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    var grouping by rememberSaveable { mutableStateOf(DownloadGrouping.ALL) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBarPadding.current)) {
        item(key = "top") { DetailTopBar("Downloads", nav.back) }
        downloadsSection(downloads, grouping, { grouping = it }, env)
    }
}

@Composable
fun FavoritesScreen(vm: PlayerViewModel, nav: AppNav) {
    val env = rememberRowEnv(vm, nav)
    val data = rememberFavoritesData(vm)
    var kind by rememberSaveable { mutableStateOf(FavKind.SONGS) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBarPadding.current)) {
        item(key = "top") { DetailTopBar("Favorites", nav.back) }
        favoritesSection(data, kind, { kind = it }, env)
    }
}

// ---- Album & artist pages ----

@Composable
fun AlbumDetailScreen(vm: PlayerViewModel, albumKey: String, nav: AppNav) {
    val all by vm.songs.collectAsStateWithLifecycle()
    val groups by vm.favoriteGroups.collectAsStateWithLifecycle()
    val album = rememberComputed<AlbumGroup?>(null, all, albumKey) { UnifiedLibrary.album(all, albumKey) }
    val env = rememberRowEnv(vm, nav)
    if (album == null) {
        Column(Modifier.fillMaxSize()) {
            DetailTopBar("Album", nav.back)
            EmptyState("Album not available", "Its songs aren't on this device, downloaded, or reachable on your server right now.")
        }
        return
    }
    val isFav = groups.any { it.kind == FavoriteGroupEntity.KIND_ALBUM && it.groupKey == album.key }
    val total = album.songs.sumOf { it.durationMs }
    val downloadable = album.songs.count { it.isFromServer && (!it.isDownloaded || it.fileMissing) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBarPadding.current)) {
        item(key = "top") { DetailTopBar("", nav.back) }
        item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ArtImage(album.artUri, Modifier.size(220.dp), RoundedCornerShape((LocalGlass.current.style.cornerRadius * 0.6f).dp))
                Spacer(Modifier.height(16.dp))
                Text(album.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    album.artist,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { nav.openArtist(album.songs.first().artistKey) }.padding(4.dp),
                )
                Text(
                    "${countLabel(album.songs.size)} · ${formatDuration(total)}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "actions") {
            ActionBar(
                onPlay = { vm.play(album.songs, 0) },
                onShuffle = { vm.shufflePlay(album.songs) },
                isFavorite = isFav,
                onFavorite = { vm.toggleFavoriteAlbum(album) },
                downloadable = downloadable,
                allDownloaded = album.songs.all { it.isDownloaded && !it.fileMissing },
                onDownload = { vm.downloadAll(album.songs) },
            )
        }
        songItems(album.songs, env, showAlbum = false)
    }
}

@Composable
fun ArtistDetailScreen(vm: PlayerViewModel, artistKey: String, nav: AppNav) {
    val all by vm.songs.collectAsStateWithLifecycle()
    val groups by vm.favoriteGroups.collectAsStateWithLifecycle()
    val artist = rememberComputed<ArtistGroup?>(null, all, artistKey) { UnifiedLibrary.artist(all, artistKey) }
    val env = rememberRowEnv(vm, nav)
    if (artist == null) {
        Column(Modifier.fillMaxSize()) {
            DetailTopBar("Artist", nav.back)
            EmptyState("Artist not available", "Their songs aren't on this device, downloaded, or reachable on your server right now.")
        }
        return
    }
    val isFav = groups.any { it.kind == FavoriteGroupEntity.KIND_ARTIST && it.groupKey == artist.key }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBarPadding.current)) {
        item(key = "top") { DetailTopBar("", nav.back) }
        item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ArtImage(artist.songs.firstNotNullOfOrNull { it.albumArtUri }, Modifier.size(180.dp), CircleShape)
                Spacer(Modifier.height(16.dp))
                Text(artist.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    "${countLabel(artist.songs.size)} · ${countLabel(artist.albums.size, "album", "albums")}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "actions") {
            ActionBar(
                onPlay = { vm.play(artist.songs, 0) },
                onShuffle = { vm.shufflePlay(artist.songs) },
                isFavorite = isFav,
                onFavorite = { vm.toggleFavoriteArtist(artist) },
            )
        }
        item(key = "albums_h") { SectionTitle("Albums") }
        item(key = "albums") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(artist.albums, key = { it.key }) { a ->
                    AlbumCard(a.title, countLabel(a.songs.size), a.artUri, onClick = { nav.openAlbum(a.key) })
                }
            }
        }
        item(key = "songs_h") { SectionTitle("All songs") }
        songItems(artist.songs, env)
    }
}

/** Play All (primary), Shuffle (secondary), favorite and optional download for a collection. */
@Composable
private fun ActionBar(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    isFavorite: Boolean,
    onFavorite: () -> Unit,
    downloadable: Int = 0,
    allDownloaded: Boolean = false,
    onDownload: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Play all")
        }
        FilledTonalButton(onClick = onShuffle, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Shuffle, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Shuffle")
        }
        IconButton(onClick = onFavorite) {
            Icon(
                if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onDownload != null && (downloadable > 0 || allDownloaded)) {
            IconButton(onClick = onDownload, enabled = downloadable > 0) {
                Icon(
                    if (allDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                    contentDescription = if (allDownloaded) "All downloaded" else "Download album ($downloadable songs)",
                    tint = if (allDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
