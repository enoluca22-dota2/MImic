package com.example.mimic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.mimic.data.server.RemoteAlbum
import com.example.mimic.data.server.RemoteSong
import com.example.mimic.data.server.ServerStatus
import com.example.mimic.player.PlayerViewModel
import com.example.mimic.ui.components.formatDuration

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
fun MusicServerScreen(vm: PlayerViewModel, onBack: () -> Unit, onOpenBrowse: () -> Unit) {
    val status by vm.serverStatus.collectAsStateWithLifecycle()
    val creds by vm.serverCredentials.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Header("Account", onBack)
        val current = creds
        if (status == ServerStatus.Connected && current != null) {
            AccountProfile(
                username = current.username,
                serverUrl = current.baseUrl,
                onBrowse = onOpenBrowse,
                onSignOut = { vm.disconnectServer() },
                onSwitchAccount = { vm.switchAccount() },
            )
        } else {
            SignInForm(vm = vm, status = status, onContinueOffline = onBack)
        }
    }
}

@Composable
private fun SignInForm(vm: PlayerViewModel, status: ServerStatus, onContinueOffline: () -> Unit) {
    val creds by vm.serverCredentials.collectAsStateWithLifecycle()
    val lastUrl by vm.lastServerUrl.collectAsStateWithLifecycle()
    val stayPref by vm.staySignedIn.collectAsStateWithLifecycle()

    var url by remember(creds, lastUrl) { mutableStateOf(creds?.baseUrl ?: lastUrl ?: "http://") }
    var user by remember(creds) { mutableStateOf(creds?.username ?: "") }
    var pass by remember(creds) { mutableStateOf("") }
    var stay by remember(stayPref) { mutableStateOf(stayPref) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Text(
            "Sign in",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Text(
            "Connect to your own music server (Navidrome / Subsonic). Works on your Wi-Fi, or " +
                "from anywhere via a Tailscale address or an HTTPS URL — just enter that address below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        OutlinedTextField(
            value = url, onValueChange = { url = it; testResult = null }, label = { Text("Server address") },
            placeholder = { Text("http://192.168.0.178:4533") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )
        OutlinedTextField(
            value = user, onValueChange = { user = it; testResult = null }, label = { Text("Username") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )
        OutlinedTextField(
            value = pass, onValueChange = { pass = it; testResult = null }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )

        Row(
            Modifier.fillMaxWidth().clickable { stay = !stay }.padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = stay, onCheckedChange = { stay = it })
            Text("Stay signed in", color = MaterialTheme.colorScheme.onBackground)
        }

        StatusLine(status)
        val msg = testResult
        if (msg != null) {
            Text(
                msg, style = MaterialTheme.typography.bodyMedium,
                color = if (msg.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { vm.connectServer(url, user, pass, stay) },
                enabled = status != ServerStatus.Connecting && url.isNotBlank() && user.isNotBlank(),
            ) { Text("Sign In") }
            OutlinedButton(
                onClick = {
                    testing = true; testResult = null
                    vm.testServerConnection(url, user, pass) { result ->
                        testing = false
                        testResult = result.fold(
                            onSuccess = { "✓ Connection OK" },
                            onFailure = { "Could not connect: ${it.message ?: "unknown error"}" },
                        )
                    }
                },
                enabled = !testing && url.isNotBlank() && user.isNotBlank(),
            ) { Text(if (testing) "Testing…" else "Test Connection") }
        }

        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Text(
            "You don't need an account to use MImic — music stored on this device always plays offline.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        OutlinedButton(onClick = onContinueOffline, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("Continue offline (Guest)")
        }
    }
}

@Composable
private fun AccountProfile(
    username: String,
    serverUrl: String,
    onBrowse: () -> Unit,
    onSignOut: () -> Unit,
    onSwitchAccount: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.AccountCircle, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(username, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Text(serverUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Signed in · Connected", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }

        Button(onClick = onBrowse, modifier = Modifier.padding(16.dp)) {
            Icon(Icons.Default.Album, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Browse server library")
        }

        HorizontalDivider(Modifier.padding(horizontal = 16.dp))

        Row(
            Modifier.fillMaxWidth().clickable(onClick = onSwitchAccount).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.width(16.dp))
            Text("Switch account", color = MaterialTheme.colorScheme.onBackground)
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onSignOut).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(16.dp))
            Text("Sign out", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun StatusLine(status: ServerStatus) {
    val (text, color) = when (status) {
        ServerStatus.Disconnected -> "Not connected" to MaterialTheme.colorScheme.onSurfaceVariant
        ServerStatus.Connecting -> "Connecting…" to MaterialTheme.colorScheme.onSurfaceVariant
        ServerStatus.Connected -> "Connected" to MaterialTheme.colorScheme.primary
        is ServerStatus.Error -> "Error: ${status.message}" to MaterialTheme.colorScheme.error
    }
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
fun ServerBrowseScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    val albums by vm.remoteAlbums.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<RemoteAlbum?>(null) }

    LaunchedEffect(Unit) { if (albums.isEmpty()) vm.loadRemoteAlbums() }

    val current = selected
    if (current == null) {
        Column(Modifier.fillMaxSize()) {
            Header("Server Library", onBack)
            if (albums.isEmpty()) {
                EmptyState("No albums", "Connect to the server, or check that it has scanned your music.")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(albums, key = { it.id }) { album ->
                        Row(
                            Modifier.fillMaxWidth().clickable { selected = album }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RemoteArt(vm.remoteCoverUrl(album.coverArtId), Modifier.size(52.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(album.name, color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
                                Text("${album.artist} · ${album.songCount} songs", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    } else {
        AlbumSongsScreen(vm, current, onBack = { selected = null })
    }
}

@Composable
private fun AlbumSongsScreen(vm: PlayerViewModel, album: RemoteAlbum, onBack: () -> Unit) {
    var songs by remember { mutableStateOf<List<RemoteSong>?>(null) }
    LaunchedEffect(album.id) { songs = vm.remoteAlbumSongs(album.id) }

    Column(Modifier.fillMaxSize()) {
        Header(album.name, onBack)
        val list = songs
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyState("No songs", "This album returned no tracks.")
            else -> {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Button(onClick = { vm.playRemote(list, 0) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Play all")
                    }
                }
                LazyColumn {
                    items(list, key = { it.id }) { song ->
                        RemoteSongRow(
                            vm = vm,
                            song = song,
                            onPlay = { vm.playRemote(list, list.indexOf(song)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteSongRow(vm: PlayerViewModel, song: RemoteSong, onPlay: () -> Unit) {
    var downloaded by remember(song.id) { mutableStateOf(false) }
    var downloading by remember(song.id) { mutableStateOf(false) }
    LaunchedEffect(song.id) { downloaded = vm.isDownloaded(song.id) }

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteArt(vm.remoteCoverUrl(song.coverArtId), Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
            Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        Text(formatDuration(song.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        when {
            downloaded -> Icon(Icons.Default.CheckCircle, contentDescription = "Downloaded", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp).size(24.dp))
            downloading -> CircularProgressIndicator(modifier = Modifier.padding(start = 8.dp).size(24.dp), strokeWidth = 2.dp)
            else -> IconButton(onClick = {
                downloading = true
                vm.downloadRemote(song) { ok -> downloading = false; downloaded = ok }
            }) { Icon(Icons.Default.Download, contentDescription = "Download") }
        }
    }
}

@Composable
private fun RemoteArt(url: String?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)))
        } else {
            Icon(Icons.Default.Album, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
