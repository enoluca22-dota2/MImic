package com.example.mimic.ui.sources

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mimic.data.MusicProvider
import com.example.mimic.data.cloud.CloudFolder
import com.example.mimic.data.cloud.CloudProviderType
import com.example.mimic.data.cloud.CloudSyncState
import com.example.mimic.data.db.CloudAccountEntity
import com.example.mimic.player.PlayerViewModel
import com.example.mimic.ui.library.DetailTopBar
import com.example.mimic.ui.library.LocalBarPadding
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Settings → Music Sources (MImic 3.1): Google Drive, OneDrive, Navidrome and phone music in one
 * place. Cloud accounts connect through the providers' own sign-in UIs; MImic never asks for a
 * Google or Microsoft password.
 */
@Composable
fun MusicSourcesScreen(
    vm: PlayerViewModel,
    onBack: () -> Unit,
    onOpenNavidrome: () -> Unit,
    onChooseFolder: (String) -> Unit,
) {
    BackHandler(onBack = onBack)
    val accounts by vm.cloudAccounts.collectAsStateWithLifecycle()
    val sync by vm.cloudSyncState.collectAsStateWithLifecycle()
    val stats by vm.cloudStats.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val serverStatus by vm.serverStatus.collectAsStateWithLifecycle()
    val creds by vm.serverCredentials.collectAsStateWithLifecycle()
    val activity = LocalContext.current as Activity
    var busy by remember { mutableStateOf<CloudProviderType?>(null) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vm.completeGoogleConsent(result.data) { key -> busy = null; key?.let(onChooseFolder) }
        } else {
            busy = null
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = LocalBarPadding.current)) {
        DetailTopBar("Music Sources", onBack)
        Text(
            "Stream and download from your own cloud storage — no computer needs to be on. MImic only reads your " +
                "music folder; it never changes, renames or deletes cloud files.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        for (provider in listOf(CloudProviderType.GOOGLE_DRIVE, CloudProviderType.ONEDRIVE)) {
            val account = accounts.firstOrNull { it.provider == provider.id }
            CloudSourceCard(
                provider = provider,
                account = account,
                state = account?.let { sync[it.accountKey] },
                stats = account?.let { stats[it.accountKey] },
                busy = busy == provider,
                configured = provider != CloudProviderType.ONEDRIVE || vm.oneDriveConfigured || account?.apiBase != null,
                onConnect = {
                    busy = provider
                    when (provider) {
                        CloudProviderType.ONEDRIVE -> vm.connectOneDrive(activity) { key -> busy = null; key?.let(onChooseFolder) }
                        CloudProviderType.GOOGLE_DRIVE -> vm.connectGoogle(
                            launchConsent = { pi -> consent.launch(IntentSenderRequest.Builder(pi.intentSender).build()) },
                            onDone = { key -> busy = null; key?.let(onChooseFolder) },
                        )
                    }
                },
                onChooseFolder = { account?.let { onChooseFolder(it.accountKey) } },
                onSync = { account?.let { vm.syncCloudAccount(it.accountKey) } },
                onDisconnect = { removeDownloads -> account?.let { vm.disconnectCloud(it.accountKey, removeDownloads) } },
            )
        }

        SourceRow(
            icon = { Icon(Icons.Default.Dns, null) },
            title = "Navidrome (optional)",
            subtitle = if (serverStatus == com.example.mimic.data.server.ServerStatus.Connected && creds != null) {
                "Signed in as ${creds!!.username} · ${songs.count { it.provider == MusicProvider.NAVIDROME }} songs · needs your server running"
            } else {
                "Your own music server (laptop / Docker). Not needed for cloud music."
            },
            onClick = onOpenNavidrome,
        )
        SourceRow(
            icon = { Icon(Icons.Default.PhoneAndroid, null) },
            title = "Phone music",
            subtitle = com.example.mimic.ui.library.countLabel(songs.count { it.provider == MusicProvider.PHONE }) + " on this phone · always available offline",
            onClick = null,
        )

        Text(
            "Data use",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
        )
        ToggleRow(
            "Download over Wi-Fi only",
            "Downloads wait for Wi-Fi instead of using mobile data",
            settings.wifiOnlyDownloads,
        ) { vm.setCloudDataControls(it, settings.cellularTagReading, settings.backgroundCloudSync) }
        ToggleRow(
            "Read song details on mobile data",
            "Reads tags & artwork from new cloud files (~100–500 KB each). Off: only on Wi-Fi",
            settings.cellularTagReading,
        ) { vm.setCloudDataControls(settings.wifiOnlyDownloads, it, settings.backgroundCloudSync) }
        ToggleRow(
            "Background library refresh",
            "Checks for new uploads every few hours (metadata only). Android may delay it",
            settings.backgroundCloudSync,
        ) { vm.setCloudDataControls(settings.wifiOnlyDownloads, settings.cellularTagReading, it) }
        Text(
            "Streaming uses data each time a song plays (about 1 MB per minute at 128 kbps). Google Drive and " +
                "OneDrive are storage services, not music services: very heavy use can be rate-limited by the provider.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun CloudSourceCard(
    provider: CloudProviderType,
    account: CloudAccountEntity?,
    state: CloudSyncState?,
    stats: com.example.mimic.data.cloud.CloudAccountStats?,
    busy: Boolean,
    configured: Boolean,
    onConnect: () -> Unit,
    onChooseFolder: () -> Unit,
    onSync: () -> Unit,
    onDisconnect: (removeDownloads: Boolean) -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(provider.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (busy || state is CloudSyncState.Syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            if (account == null) {
                Text(
                    if (configured) "Not connected" else "Not available in this build — needs an app registration (docs/CLOUD_SETUP.md)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (provider == CloudProviderType.GOOGLE_DRIVE) {
                    Text(
                        "MImic asks for read-only access to your Drive so it can find music you upload later. It only lists the one folder you choose.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onConnect, enabled = configured && !busy) { Text("Connect account") }
                return@Column
            }
            Info("Account", account.displayName + (account.email?.takeIf { it != account.displayName }?.let { " · $it" } ?: ""))
            Info("Music folder", account.folderName ?: "Not chosen yet")
            Info(
                "Status",
                when {
                    state is CloudSyncState.Syncing -> state.phase
                    state is CloudSyncState.Failed -> state.message
                    account.lastError != null -> account.lastError
                    account.folderId == null -> "Choose a music folder"
                    else -> "Connected"
                },
                error = state is CloudSyncState.Failed || (state !is CloudSyncState.Syncing && account.lastError != null),
            )
            if (stats != null && account.folderId != null) {
                val extra = buildList {
                    if (stats.tagsPending > 0) add("${stats.tagsPending} waiting for details")
                    if (stats.unsupported > 0) add("${stats.unsupported} unsupported files skipped")
                    if (stats.duplicates > 0) add("${stats.duplicates} duplicates hidden")
                }
                Info("Library", "${stats.songs} songs" + extra.joinToString("") { " · $it" })
            }
            Info(
                "Last synced",
                if (account.lastSyncAt == 0L) "Never" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(account.lastSyncAt)),
            )
            if (state is CloudSyncState.Done) {
                Info("Last sync", "${state.added} new · ${state.changed} changed · ${state.removed} removed (files & folders)")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if ((state as? CloudSyncState.Failed)?.needsSignIn == true) {
                    Button(onClick = onConnect) { Text("Sign in again") }
                } else {
                    Button(onClick = onSync, enabled = account.folderId != null && state !is CloudSyncState.Syncing) { Text("Sync now") }
                }
                OutlinedButton(onClick = onChooseFolder) { Text(if (account.folderId == null) "Choose folder" else "Change folder") }
            }
            TextButton(onClick = { confirm = true }, contentPadding = PaddingValues(0.dp)) { Text("Disconnect account") }
        }
    }
    if (confirm) {
        var alsoDownloads by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Disconnect ${provider.label}?") },
            text = {
                Column {
                    Text("MImic forgets this account's song list and signs out. Nothing in your ${provider.label} is changed. Favorites and history stay on this phone.")
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { alsoDownloads = !alsoDownloads }) {
                        Checkbox(alsoDownloads, onCheckedChange = { alsoDownloads = it })
                        Text("Also delete this account's downloaded songs from the phone")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { confirm = false; onDisconnect(alsoDownloads) }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Info(label: String, value: String, error: Boolean = false) {
    Row {
        Text("$label  ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SourceRow(icon: @Composable () -> Unit, title: String, subtitle: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = onChange)
    }
}

/** Browse the account's folders and pick the one music folder MImic should read. */
@Composable
fun CloudFolderPickerScreen(vm: PlayerViewModel, accountKey: String, onDone: () -> Unit) {
    val accounts by vm.cloudAccounts.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.accountKey == accountKey }
    val provider = account?.let { CloudProviderType.of(it.provider) }
    // Breadcrumb stack: null id = drive root.
    var stack by remember { mutableStateOf(listOf<CloudFolder?>(null)) }
    var folders by remember { mutableStateOf<List<CloudFolder>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val current = stack.last()

    BackHandler { if (stack.size > 1) stack = stack.dropLast(1) else onDone() }
    LaunchedEffect(current?.id) {
        folders = null
        error = null
        vm.listCloudFolders(accountKey, current?.id).fold(
            onSuccess = { folders = it },
            onFailure = { error = it.message ?: "Couldn't list folders" },
        )
    }

    Column(Modifier.fillMaxSize()) {
        DetailTopBar("Choose music folder", onBack = { if (stack.size > 1) stack = stack.dropLast(1) else onDone() })
        Text(
            (listOf(provider?.label ?: "Cloud") + stack.drop(1).map { it!!.name }).joinToString(" / "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (current != null) {
            Button(
                onClick = {
                    scope.launch {
                        vm.chooseCloudFolder(accountKey, current)
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text("Use \"${current.name}\" as my music folder") }
        } else {
            Text(
                "Open the folder you upload music to (for example \"MImic Music\"). MImic includes everything inside it, " +
                    "including sub-folders like Artist/Album.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        // Local snapshots: the lazy list's item provider may run after `folders` was reset to null
        // for the next folder's load, so it must never re-read the state with `!!`.
        val list = folders
        val err = error
        when {
            err != null -> Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            list == null -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Text("No sub-folders here.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(contentPadding = PaddingValues(bottom = LocalBarPadding.current + 16.dp)) {
                items(list, key = { it.id }) { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable { stack = stack + f }.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Text(f.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        f.childCount?.let { Text(com.example.mimic.ui.library.countLabel(it, "item", "items"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
