package com.example.mimic.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mimic.data.HomeSection
import com.example.mimic.data.Song
import com.example.mimic.data.SongSource
import com.example.mimic.data.SourceFilter
import com.example.mimic.data.UnifiedLibrary
import com.example.mimic.data.db.FavoriteGroupEntity
import com.example.mimic.player.PlayerViewModel
import com.example.mimic.ui.components.AlbumArt
import com.example.mimic.ui.components.ArtImage
import com.example.mimic.ui.designsystem.LocalGlass
import com.example.mimic.ui.designsystem.glassSurface
import com.example.mimic.ui.designsystem.surfaceShape
import com.example.mimic.ui.library.AlbumCard
import com.example.mimic.ui.library.AppNav
import com.example.mimic.ui.library.ArtistCard
import com.example.mimic.ui.library.InlineEmpty
import com.example.mimic.ui.library.SectionTitle
import com.example.mimic.ui.library.SourceFilterRow
import com.example.mimic.ui.library.countLabel
import java.util.Calendar

/**
 * Home — the main music dashboard (MImic 3.0). Every section is real data from the unified
 * library (device + server + downloads) and Room; nothing is fabricated. Sections follow the
 * user's Home customization (order + hidden) and the shared source filter, and each one has an
 * honest empty state, so Home works fully offline with only phone / downloaded music.
 */
@Composable
fun HomeScreen(vm: PlayerViewModel, contentPadding: PaddingValues, nav: AppNav) {
    val loading by vm.loading.collectAsStateWithLifecycle()
    val all by vm.songs.collectAsStateWithLifecycle()
    val filter by vm.sourceFilter.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val recentlyPlayed by vm.recentlyPlayed.collectAsStateWithLifecycle()
    val recentlyAdded by vm.recentlyAdded.collectAsStateWithLifecycle()
    val mostPlayed by vm.mostPlayed.collectAsStateWithLifecycle()
    val favorites by vm.favoriteSongs.collectAsStateWithLifecycle()
    val suggested by vm.suggested.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val groups by vm.favoriteGroups.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val never by vm.neverPlayed.collectAsStateWithLifecycle()
    val least by vm.leastPlayed.collectAsStateWithLifecycle()
    val forgotten by vm.forgottenFavorites.collectAsStateWithLifecycle()
    val random by vm.randomDiscovery.collectAsStateWithLifecycle()
    val player by vm.nowPlaying.collectAsStateWithLifecycle()

    fun List<Song>.f() = if (filter == SourceFilter.ALL) this else filter { filter.matches(it) }
    val newestAlbums = com.example.mimic.ui.library.rememberComputed(emptyList<com.example.mimic.data.AlbumGroup>(), all, filter) {
        UnifiedLibrary.albums(UnifiedLibrary.filter(all, filter))
            .sortedByDescending { a -> a.songs.maxOf { it.dateAddedSec } }
            .take(20)
    }

    com.example.mimic.ui.library.RefreshableLibrary(vm, contentPadding) {
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item(key = "greeting") { Greeting(all) }
        item(key = "filters") { SourceFilterRow(vm, Modifier.padding(top = 4.dp)) }
        if (loading && all.isEmpty()) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
        settings.homeConfig.visibleOrdered().forEach { section ->
            when (section) {
                HomeSection.RECENTLY_PLAYED -> {
                    item(key = "cl_h") { SectionTitle(section.label) }
                    val current = player.song
                    if (current != null) {
                        item(key = "cl_now") {
                            NowPlayingCard(current, player.isPlaying, onToggle = vm::togglePlayPause, onMenu = { nav.songMenu(current) })
                        }
                    }
                    val history = recentlyPlayed.f().filter { it.id != current?.id }
                    if (history.isEmpty() && current == null) {
                        item(key = "cl_e") { InlineEmpty("Songs you play will show up here so you can jump right back in.") }
                    } else if (history.isNotEmpty()) {
                        songCarousel("cl", history, vm, nav)
                    }
                }
                HomeSection.DOWNLOADS -> {
                    item(key = "dl_h") { SectionTitle(section.label, onSeeAll = nav.openDownloads.takeIf { downloads.isNotEmpty() }) }
                    if (downloads.isEmpty()) {
                        item(key = "dl_e") {
                            InlineEmpty(
                                "Songs you download from your cloud folder or server appear here automatically and play offline.",
                            )
                        }
                    } else {
                        val list = downloads.f()
                        if (list.isEmpty()) item(key = "dl_f") { InlineEmpty("No downloads from this source yet.") }
                        else songCarousel("dl", list, vm, nav)
                    }
                }
                HomeSection.RECENTLY_ADDED -> {
                    val list = recentlyAdded.f()
                    item(key = "ra_h") { SectionTitle(section.label) }
                    if (list.isEmpty()) item(key = "ra_e") { InlineEmpty("New music on your phone, cloud folder or server will show up here.") }
                    else songCarousel("ra", list, vm, nav, showSource = true)
                }
                HomeSection.FAVORITES -> {
                    val list = favorites.f()
                    item(key = "fs_h") { SectionTitle(section.label, onSeeAll = nav.openFavorites.takeIf { list.isNotEmpty() }) }
                    if (list.isEmpty()) item(key = "fs_e") { InlineEmpty("Tap ♡ on any song and it will be saved here.") }
                    else songCarousel("fs", list, vm, nav)
                }
                HomeSection.FAVORITE_ARTISTS -> {
                    val favArtists = groups.filter { it.kind == FavoriteGroupEntity.KIND_ARTIST }
                    item(key = "fa_h") { SectionTitle(section.label, onSeeAll = nav.openFavorites.takeIf { favArtists.isNotEmpty() }) }
                    if (favArtists.isEmpty()) {
                        item(key = "fa_e") { InlineEmpty("Open an artist in Library and tap ♡ to pin them here.") }
                    } else {
                        item(key = "fa_r") {
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                items(favArtists, key = { it.groupKey }) { g ->
                                    val art = remember(all, g.groupKey) { all.firstOrNull { it.artistKey == g.groupKey }?.albumArtUri }
                                    ArtistCard(g.name, g.subtitle, art, onClick = { nav.openArtist(g.groupKey) })
                                }
                            }
                        }
                    }
                }
                HomeSection.ALBUMS -> {
                    item(key = "al_h") { SectionTitle(section.label) }
                    if (newestAlbums.isEmpty()) {
                        item(key = "al_e") { InlineEmpty("Albums from your phone, cloud folder and server appear here.") }
                    } else {
                        item(key = "al_r") {
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(newestAlbums, key = { it.key }) { a ->
                                    com.example.mimic.ui.library.AlbumCard(a.title, a.artist, a.artUri, onClick = { nav.openAlbum(a.key) })
                                }
                            }
                        }
                    }
                }
                HomeSection.PLAYLISTS -> {
                    item(key = "pl_h") { SectionTitle(section.label) }
                    item(key = "pl_r") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            item(key = "new") { TileCard("New playlist", "Create", Icons.Default.Add, accent = true, onClick = nav.createPlaylist) }
                            items(playlists, key = { it.id }) { pl ->
                                TileCard(pl.name, "Playlist", Icons.AutoMirrored.Filled.QueueMusic, onClick = { nav.openPlaylist(pl.id) })
                            }
                        }
                    }
                }
                HomeSection.SMART_COLLECTIONS -> {
                    item(key = "sc_h") { SectionTitle(section.label, onSeeAll = nav.openSmart) }
                    item(key = "sc_r") {
                        val tiles = listOf(
                            "Never Played" to never.size, "Least Played" to least.size,
                            "Forgotten Favorites" to forgotten.size, "Random Discovery" to random.size,
                        )
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(tiles, key = { it.first }) { (name, n) ->
                                TileCard(name, countLabel(n), Icons.Default.AutoAwesome, onClick = nav.openSmart)
                            }
                        }
                    }
                }
                HomeSection.MOST_PLAYED -> {
                    val list = mostPlayed.f()
                    if (list.isNotEmpty()) {
                        item(key = "mp_h") { SectionTitle(section.label) }
                        songCarousel("mp", list, vm, nav)
                    }
                }
                HomeSection.SUGGESTED -> {
                    val list = suggested.f()
                    if (list.isNotEmpty()) {
                        item(key = "sg_h") { SectionTitle(section.label) }
                        songCarousel("sg", list, vm, nav)
                    }
                }
            }
        }
        item(key = "albums_hint") {
            val albums = remember(all) { UnifiedLibrary.albums(all).size }
            if (all.isNotEmpty()) {
                Text(
                    "${countLabel(all.size)} · ${countLabel(albums, "album", "albums")} — browse everything in Library",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
    }
}

@Composable
private fun Greeting(all: List<Song>) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)) {
        Text(
            greeting,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = LocalGlass.current.headerFontWeight,
            color = MaterialTheme.colorScheme.onBackground,
        )
        val dl = all.count { it.isDownloaded }
        val device = all.count { it.source == SongSource.LOCAL }
        val cloud = all.count { it.provider == com.example.mimic.data.MusicProvider.GOOGLE_DRIVE || it.provider == com.example.mimic.data.MusicProvider.ONEDRIVE }
        Text(
            "$device on this phone · " + (if (cloud > 0) "$cloud in the cloud · " else "") + "$dl downloaded",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The song that's loaded in the player, with one-tap resume / pause. */
@Composable
private fun NowPlayingCard(song: Song, isPlaying: Boolean, onToggle: () -> Unit, onMenu: () -> Unit) {
    val env = LocalGlass.current
    val shape = env.style.surfaceShape(0.7f)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (env.style.glass) 0.55f else 1f))
            .clickable(onClick = onToggle)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(song, Modifier.size(64.dp), corner = 10)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (isPlaying) "Now playing" else "Paused — tap to resume", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(song.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onToggle) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Resume",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun LazyListScope.songCarousel(
    key: String,
    songs: List<Song>,
    vm: PlayerViewModel,
    nav: AppNav,
    showSource: Boolean = false,
) {
    item(key = key + "_r") {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(songs.size, key = { songs[it].id }) { i ->
                SongCard(songs[i], showSource, onClick = { vm.play(songs, i) }, onLongClick = { nav.songMenu(songs[i]) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongCard(song: Song, showSource: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val env = LocalGlass.current
    Column(Modifier.width(144.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box {
            AlbumArt(song, modifier = Modifier.size(144.dp), corner = (env.style.cornerRadius * 0.45f).toInt())
            if (song.isDownloaded) {
                Icon(
                    Icons.Default.DownloadDone,
                    contentDescription = "Downloaded",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape).padding(3.dp).size(14.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            song.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight(env.style.titleWeight.coerceAtLeast(500)),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val sub = if (showSource) {
            song.artist + " · " + when {
                song.isDownloaded -> "Downloaded"
                song.source == SongSource.SERVER -> "Server"
                song.source == SongSource.CLOUD -> song.provider.label
                else -> "On device"
            }
        } else song.artist
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Square tile for playlists / smart collections. */
@Composable
private fun TileCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Boolean = false, onClick: () -> Unit) {
    val env = LocalGlass.current
    Column(Modifier.width(132.dp).clickable(onClick = onClick)) {
        Box(
            Modifier.size(132.dp).clip(RoundedCornerShape((env.style.cornerRadius * 0.45f).dp))
                .background(if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (accent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
