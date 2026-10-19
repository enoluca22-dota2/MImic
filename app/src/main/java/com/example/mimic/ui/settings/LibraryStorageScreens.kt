package com.example.mimic.ui.settings

import android.app.Application
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mimic.MimicApplication
import com.example.mimic.data.LibraryScanConfig
import com.example.mimic.data.Song
import com.example.mimic.data.StorageCaches
import com.example.mimic.data.backup.BackupData
import com.example.mimic.data.backup.BackupFormatException
import com.example.mimic.data.backup.BackupManager
import com.example.mimic.player.PlayerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

// ---------------------------------------------------------------------------------------------
// Backup & Restore (MImic 4.3)
// ---------------------------------------------------------------------------------------------

sealed interface BackupState {
    data object Idle : BackupState
    data class Working(val message: String) : BackupState
    /** A backup file was read and checked; nothing has been changed yet. */
    data class Preview(val data: BackupData) : BackupState
    data class Done(val message: String) : BackupState
    data class Failed(val message: String) : BackupState
}

class BackupViewModel(app: Application) : AndroidViewModel(app) {
    private val manager: BackupManager = (app as MimicApplication).container.backupManager
    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    fun export(target: Uri, library: List<Song>) {
        _state.value = BackupState.Working("Saving backup…")
        viewModelScope.launch {
            _state.value = runCatching { manager.export(library, target) }.fold(
                onSuccess = { r ->
                    val s = r.summary
                    BackupState.Done(
                        "Backup saved: ${n(s.favorites, "favorite song")}, ${n(s.playlists, "playlist")} (${n(s.playlistSongs, "song")}), " +
                            "${n(s.historyEvents, "play")}, ${n(s.themes, "custom theme")}, ${n(s.settings, "setting")}, ${n(s.radioStations, "radio station")}." +
                            (if (r.skippedMissingSongs > 0) " ${n(r.skippedMissingSongs, "entry", "entries")} left out: the song is no longer on this phone." else ""),
                    )
                },
                onFailure = { BackupState.Failed("Couldn't save the backup: ${it.message ?: "unknown error"}") },
            )
        }
    }

    fun open(source: Uri) {
        _state.value = BackupState.Working("Checking backup…")
        viewModelScope.launch {
            _state.value = runCatching { manager.read(source) }.fold(
                onSuccess = { BackupState.Preview(it) },
                onFailure = { BackupState.Failed((it as? BackupFormatException)?.message ?: "Couldn't read that file.") },
            )
        }
    }

    fun restore(data: BackupData, library: List<Song>, options: BackupManager.RestoreOptions) {
        _state.value = BackupState.Working("Restoring…")
        viewModelScope.launch {
            _state.value = runCatching { manager.restore(data, library, options) }.fold(
                onSuccess = { r ->
                    val parts = buildList {
                        if (options.music) {
                            add(n(r.favoritesAdded, "favorite"))
                            add("${n(r.playlistsCreated, "new playlist")} (${n(r.playlistSongsAdded, "song")} added)")
                            add(n(r.historyAdded, "play"))
                        }
                        if (options.settings) { add(n(r.settingsRestored, "setting")); add(n(r.themesAdded, "theme")) }
                        if (options.radio) { add(n(r.radioCountriesAdded, "radio country", "radio countries")); add(n(r.radioStations, "radio station")) }
                    }
                    BackupState.Done(
                        "Restored: " + parts.joinToString(", ") + "." +
                            (if (r.songsNotFound > 0) " ${n(r.songsNotFound, "song")} from the backup ${if (r.songsNotFound == 1) "isn't" else "aren't"} in this library right now, so ${if (r.songsNotFound == 1) "its entries were" else "their entries were"} skipped. Navidrome songs need Navidrome signed in; songs no longer on the phone or server can't be matched." else "") +
                            (if (r.serverAddressFilled) " Your Navidrome address is filled in — sign in with your password in Settings → Account." else ""),
                    )
                },
                onFailure = { BackupState.Failed("Restore stopped: ${it.message ?: "unknown error"}. Nothing was deleted.") },
            )
        }
    }

    fun dismiss() { _state.value = BackupState.Idle }
}

/** "1 song" / "3 songs". */
private fun n(count: Int, one: String, many: String = one + "s") = "$count ${if (count == 1) one else many}"


@Composable
fun BackupRestoreScreen(player: PlayerViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val vm: BackupViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(uri, player.fullLibrary())
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.open(uri) }
    val c = MaterialTheme.colorScheme

    LazyColumn(Modifier.fillMaxSize()) {
        item { Header("Backup & Restore", onBack) }
        item {
            Note("A backup is one file with your favorites, playlists, listening history, custom themes, settings and radio countries & stations. " +
                "It never contains music files, downloads or passwords. Keep it anywhere the file picker can reach — the phone, an SD card or a cloud app.")
        }
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { save.launch("MImic-backup-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(Date())}.json") }, enabled = state !is BackupState.Working, modifier = Modifier.fillMaxWidth()) {
                    Text("Create backup…")
                }
                OutlinedButton(onClick = { pick.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }, enabled = state !is BackupState.Working, modifier = Modifier.fillMaxWidth()) {
                    Text("Restore from backup…")
                }
            }
        }
        item {
            when (val s = state) {
                is BackupState.Working -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text(s.message, color = c.onBackground)
                }
                is BackupState.Done -> Card(s.message, c.primary) { vm.dismiss() }
                is BackupState.Failed -> Card(s.message, c.error) { vm.dismiss() }
                else -> Unit
            }
        }
        item {
            Note("Restoring only adds: favorites, playlists, history, themes and stations you already have stay as they are. " +
                "Songs are matched by title, artist and length (or Navidrome id), so a backup also works on a new phone. " +
                "Settings from the backup replace your current settings — you choose this before anything changes.")
        }
    }

    (state as? BackupState.Preview)?.let { p -> RestoreDialog(p.data, onCancel = vm::dismiss) { opts -> vm.restore(p.data, player.fullLibrary(), opts) } }
}

@Composable
private fun RestoreDialog(data: BackupData, onCancel: () -> Unit, onRestore: (BackupManager.RestoreOptions) -> Unit) {
    val s = data.summary()
    var music by remember { mutableStateOf(true) }
    var settings by remember { mutableStateOf(true) }
    var radio by remember { mutableStateOf(true) }
    val created = remember(data.createdAt) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(data.createdAt)) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Restore this backup?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Made $created" + (data.appVersion.takeIf { it.isNotBlank() }?.let { " by MImic $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                Option(music, { music = it }, "Music data (added)", "${n(s.favorites, "favorite song")}, ${n(s.favoriteGroups, "favorite album/artist", "favorite albums/artists")}, ${n(s.playlists, "playlist")}, ${n(s.historyEvents, "play")}")
                Option(settings, { settings = it }, "Settings & themes (replaced)", "${n(s.settings, "setting")}, ${n(s.themes, "custom theme")}" + (s.server?.let { ", Navidrome address ($it)" } ?: ""))
                Option(radio, { radio = it }, "Radio (added)", "${n(s.radioCountries, "country", "countries")}, ${n(s.radioStations, "saved station")}")
                Text("Music files and downloads are never changed.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onRestore(BackupManager.RestoreOptions(music, settings, radio)) }, enabled = music || settings || radio) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun Option(checked: Boolean, onChange: (Boolean) -> Unit, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Library & Storage (MImic 4.3): which phone folders are shown, minimum length, caches
// ---------------------------------------------------------------------------------------------

@Composable
fun LibraryStorageScreen(player: PlayerViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = androidx.compose.ui.platform.LocalContext.current
    val config by player.scanConfig.collectAsStateWithLifecycle()
    val folders by player.scanFolders.collectAsStateWithLifecycle()
    val local by player.localSongs.collectAsStateWithLifecycle()
    val loading by player.loading.collectAsStateWithLifecycle()
    val caches = remember { StorageCaches(context.applicationContext) }
    var usage by remember { mutableStateOf<StorageCaches.Usage?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { usage = caches.usage() }
    val c = MaterialTheme.colorScheme

    LazyColumn(Modifier.fillMaxSize()) {
        item { Header("Library & Storage", onBack) }
        item { Section("Phone music") }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${n(local.size, "song")} shown" + folders.sumOf { it.songCount }.let { all -> if (all > local.size) " · ${all - local.size} hidden" else "" },
                    style = MaterialTheme.typography.bodyLarge, color = c.onBackground, modifier = Modifier.weight(1f),
                )
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else TextButton(onClick = { player.loadLibrary() }) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Refresh") }
            }
        }
        item {
            Text("Minimum song length", style = MaterialTheme.typography.bodyMedium, color = c.onBackground, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryScanConfig.MIN_CHOICES.forEach { sec ->
                    FilterChip(
                        selected = config.minDurationSec == sec,
                        onClick = { player.setScanConfig(config.copy(minDurationSec = sec)) },
                        label = { Text(if (sec == 0) "Off" else "$sec s") },
                    )
                }
            }
            Note("Hides voice notes and short clips. Nothing is deleted — change it back at any time.")
        }
        item { Section("Folders") }
        if (folders.isEmpty()) item { Note("No phone music found yet.") }
        items(folders, key = { it.path }) { f ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp).clip(RoundedCornerShape(16.dp))
                    .background(c.surfaceVariant.copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (f.excluded) Icons.Default.FolderOff else Icons.Default.Folder, null, tint = if (f.excluded) c.onSurfaceVariant else c.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.bodyLarge, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${f.path} · ${f.songCount} songs", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Switch(
                    checked = !f.excluded,
                    onCheckedChange = { show ->
                        val set = if (show) config.excludedFolders.filterNot { com.example.mimic.data.LibraryScan.normalize(it).equals(f.path, true) }.toSet()
                        else config.excludedFolders + f.path
                        player.setScanConfig(config.copy(excludedFolders = set))
                    },
                )
            }
        }
        item { Note("Hidden folders keep their favorites, playlists and history; turn a folder back on to see its songs again. Folders with a .nomedia file are already skipped by Android.") }
        item { Section("Storage") }
        item {
            val u = usage
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (u == null) Text("Measuring…", color = c.onSurfaceVariant)
                else {
                    Text("Artwork cache: ${StorageCaches.format(u.artworkBytes)}", color = c.onBackground)
                    Text("Covers & logos cache: ${StorageCaches.format(u.imageBytes)}", color = c.onBackground)
                    Text("Offline downloads: ${StorageCaches.format(u.downloadsBytes)} (kept)", color = c.onSurfaceVariant)
                }
                Spacer(Modifier.size(6.dp))
                OutlinedButton(onClick = { confirmClear = true }, enabled = (u?.clearableBytes ?: 0L) > 0L) { Text("Clear temporary caches") }
            }
            Note("Clearing only removes pictures MImic can load again. Downloads, music files, playlists and settings are not touched.")
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear temporary caches?") },
            text = { Text("Artwork and logos will be loaded again when they're next shown. Downloads and music files stay.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { caches.clear(); usage = caches.usage(); player.showMessage("Temporary caches cleared") }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}

@Composable
private fun Card(text: String, accent: androidx.compose.ui.graphics.Color, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(16.dp)).background(accent.copy(alpha = 0.12f)).padding(14.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("OK") }
    }
}
