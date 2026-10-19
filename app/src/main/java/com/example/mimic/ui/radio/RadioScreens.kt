package com.example.mimic.ui.radio

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.mimic.data.radio.RadioCountries
import com.example.mimic.data.radio.RadioFilter
import com.example.mimic.data.radio.RadioSearch
import com.example.mimic.data.radio.RadioSort
import com.example.mimic.data.radio.RadioStation
import com.example.mimic.player.PlayerViewModel

/**
 * Radio (MImic 4.2): the user's countries — only Albania until they add more with + — each with
 * its stations by category, favorites and recently played. Edit shows − to remove a country.
 */
@Composable
fun RadioScreen(player: PlayerViewModel, contentPadding: PaddingValues, onAddCountry: () -> Unit) {
    val vm: RadioViewModel = viewModel()
    val countries by vm.countries.collectAsStateWithLifecycle()
    val stationsMap by vm.stations.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val favIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val ui by player.uiState.collectAsStateWithLifecycle()
    val playingStation = player.radioStationFor(ui.currentSong)

    var selected by rememberSaveable { mutableStateOf(RadioCountries.DEFAULT) }
    if (selected !in countries) selected = countries.first()
    var editing by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf(RadioFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(RadioSort.POPULAR) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    LaunchedEffect(selected) { vm.load(selected) }

    val countryStations = (stationsMap[selected] as? StationsState.Loaded)?.stations.orEmpty()
    // Saved stations of this country also count (a favorite may no longer be in the directory).
    val known = RadioSearch.merge(countryStations, favorites.filter { it.countryCode == selected }, recent.filter { it.countryCode == selected })
    val visible = searchResults ?: RadioSearch.view(known, filter, sort, favIds, recent.map { it.id }, query)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "head") {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Radio", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                IconButton(onClick = { showSearch = !showSearch; if (!showSearch) { query = ""; vm.clearSearch() } }) { Icon(Icons.Default.Search, "Search stations") }
                IconButton(onClick = { editing = !editing }) { Icon(if (editing) Icons.Default.Check else Icons.Default.Edit, if (editing) "Done editing countries" else "Edit countries") }
            }
        }
        item(key = "countries") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(countries, key = { it }) { code ->
                    CountryChip(code, selected = code == selected, editing = editing, onClick = { selected = code; vm.clearSearch() }, onRemove = { confirmRemove = code })
                }
                item(key = "+") {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = onAddCountry)
                            .semantics { contentDescription = "Add country" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onPrimary) }
                }
            }
            if (RadioCountries.DEFAULT !in countries) {
                TextButton(onClick = { vm.restoreDefault() }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Restore default (🇦🇱 Albania)") }
            }
        }
        item(key = "search") {
            AnimatedVisibility(showSearch) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it; if (it.isBlank()) vm.clearSearch() },
                        singleLine = true, placeholder = { Text("Station, city or genre") }, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.searchDirectory(query, selected) }, enabled = query.isNotBlank()) { Text("Search all") }
                }
            }
        }
        item(key = "filters") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LazyRow(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(RadioFilter.entries.toList()) { f -> FilterChip(selected = filter == f, onClick = { filter = f; vm.clearSearch() }, label = { Text(f.label) }) }
                }
                var sortMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.Sort, "Sort stations (${sort.label})") }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        RadioSort.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label + if (s == sort) " ✓" else "") }, onClick = { sort = s; sortMenu = false }) }
                    }
                }
                IconButton(onClick = { vm.load(selected, refresh = true) }) { Icon(Icons.Default.Refresh, "Refresh stations") }
            }
        }
        val state = stationsMap[selected]
        when {
            searching -> item(key = "searching") { Center { CircularProgressIndicator() } }
            searchResults != null -> {
                item(key = "sr") { Note("${searchResults!!.size} stations found in the directory for \"$query\"") }
            }
            state is StationsState.Loading || state == null -> item(key = "loading") { Center { CircularProgressIndicator() } }
            state is StationsState.Error -> item(key = "err") {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { vm.load(selected, refresh = true) }) { Text("Try again") }
                }
            }
            state is StationsState.Loaded && state.note != null -> item(key = "note") { Note(state.note) }
        }
        if (!searching && (state is StationsState.Loaded || searchResults != null)) {
            if (visible.isEmpty()) item(key = "empty") {
                Note(
                    when (filter) {
                        RadioFilter.FAVORITES -> "No favorite stations in ${RadioCountries.displayName(selected)} yet. Tap ♡ on a station."
                        RadioFilter.RECENT -> "Stations you play appear here."
                        else -> "No stations here. Try another filter, or Refresh."
                    },
                )
            }
            itemsIndexed(visible, key = { _, s -> "st-" + s.id }) { i, st ->
                StationRow(
                    st,
                    playing = playingStation?.id == st.id,
                    favorite = st.id in favIds,
                    onPlay = { player.playRadio(visible, i) },
                    onFavorite = { player.toggleRadioFavorite(st) },
                )
            }
        }
    }

    confirmRemove?.let { code ->
        val name = RadioCountries.displayName(code)
        val playingThere = playingStation?.countryCode == code
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${RadioCountries.flagOf(code)} $name?") },
            text = {
                Text(
                    "Its stations are hidden from Radio. Your favorite and recently played stations stay saved, and your music, downloads and Navidrome aren't affected." +
                        (if (playingThere) " The station playing now keeps playing." else "") +
                        (if (code == RadioCountries.DEFAULT) " You can bring Albania back any time with Restore default." else ""),
                )
            },
            confirmButton = { TextButton(onClick = { vm.removeCountry(code); confirmRemove = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CountryChip(code: String, selected: Boolean, editing: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(22.dp))
                .background(if (selected) c.primary.copy(alpha = 0.22f) else c.surfaceVariant.copy(alpha = 0.7f))
                .border(1.dp, if (selected) c.primary else c.onSurface.copy(alpha = 0.08f), RoundedCornerShape(22.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(RadioCountries.flagOf(code), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(6.dp))
            Text(RadioCountries.displayName(code), style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = c.onSurface)
            if (editing) Spacer(Modifier.width(26.dp))
        }
        if (editing) {
            Box(
                Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).size(24.dp).clip(CircleShape).background(c.error).clickable(onClick = onRemove)
                    .semantics { contentDescription = "Remove ${RadioCountries.displayName(code)}" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Remove, null, tint = c.onError, modifier = Modifier.size(16.dp)) }
        }
    }
}

@Composable
fun StationRow(st: RadioStation, playing: Boolean, favorite: Boolean, onPlay: () -> Unit, onFavorite: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).clip(RoundedCornerShape(18.dp))
            .background(if (playing) c.primary.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onPlay).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StationLogo(st, 52)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(st.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = if (playing) c.primary else c.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(st.subtitle, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            st.qualityLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant.copy(alpha = 0.8f)) }
        }
        if (playing) LiveBadge()
        IconButton(onClick = onFavorite) {
            Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (favorite) "Remove ${st.name} from favorites" else "Add ${st.name} to favorites", tint = if (favorite) c.primary else c.onSurfaceVariant)
        }
    }
}

@Composable
fun StationLogo(st: RadioStation, size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Radio, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        st.logoUrl?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/** "● LIVE" — shown instead of a timeline for live radio. */
@Composable
fun LiveBadge(color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.error) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp)
            .semantics { contentDescription = "Live" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text("LIVE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { content() }
}

/** "+" — searchable list of every country; ones already added are marked and can't be added twice. */
@Composable
fun AddRadioCountryScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val vm: RadioViewModel = viewModel()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val countries by vm.countries.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadCatalogCounts() }
    val shown = catalog.filter { RadioCountries.matches(it, query) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Add a country", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear") } },
            placeholder = { Text("Search countries (e.g. Italy, UK, Kosovo)") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(shown, key = { it.code }) { c ->
                val added = c.code in countries
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !added) { vm.addCountry(c.code); onBack() }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c.flag, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                        c.stationCount?.let { Text(if (it > 0) "$it stations in the directory" else "No stations listed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (added) Text("Added ✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Settings → Radio Countries: add (+), remove (−), reorder, restore default. */
@Composable
fun RadioCountriesSettingsScreen(onBack: () -> Unit, onAddCountry: () -> Unit) {
    BackHandler(onBack = onBack)
    val vm: RadioViewModel = viewModel()
    val countries by vm.countries.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Radio Countries", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Note("These countries appear on Radio, in this order. Albania is the default.")
        }
        itemsIndexed(countries, key = { _, c -> c }) { i, code ->
            val c = MaterialTheme.colorScheme
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(20.dp))
                    .background(c.surfaceVariant.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(RadioCountries.flagOf(code), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(RadioCountries.displayName(code), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = c.onSurface)
                    if (code == RadioCountries.DEFAULT) Text("Default", style = MaterialTheme.typography.labelSmall, color = c.primary)
                }
                IconButton(onClick = { vm.moveCountry(code, up = true) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move ${RadioCountries.displayName(code)} up") }
                IconButton(onClick = { vm.moveCountry(code, up = false) }, enabled = i < countries.size - 1) { Icon(Icons.Default.KeyboardArrowDown, "Move ${RadioCountries.displayName(code)} down") }
                IconButton(onClick = { confirmRemove = code }, enabled = countries.size > 1 || code != RadioCountries.DEFAULT) {
                    Icon(Icons.Default.Remove, "Remove ${RadioCountries.displayName(code)}", tint = c.error)
                }
            }
        }
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAddCountry) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add another country") }
                OutlinedButton(onClick = { vm.restoreDefault() }, enabled = countries.firstOrNull() != RadioCountries.DEFAULT) { Text("Restore default (Albania first)") }
            }
        }
    }
    confirmRemove?.let { code ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${RadioCountries.flagOf(code)} ${RadioCountries.displayName(code)}?") },
            text = { Text("Its stations are hidden from Radio. Favorite and recently played stations stay saved; music, downloads and Navidrome aren't affected.") },
            confirmButton = { TextButton(onClick = { vm.removeCountry(code); confirmRemove = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }
}
