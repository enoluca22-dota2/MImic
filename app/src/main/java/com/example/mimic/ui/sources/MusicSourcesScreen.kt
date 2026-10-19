package com.example.mimic.ui.sources

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.example.mimic.data.cloud.CloudConnectState
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
    val connect by vm.cloudConnect.collectAsStateWithLifecycle()
    val activity = LocalContext.current as Activity

    /** A fresh account goes straight to folder selection; a reconnect keeps its folder. */
    fun afterConnect(key: String) {
        if (vm.cloudAccounts.value.firstOrNull { it.accountKey == key }?.folderId == null) onChooseFolder(key)
    }

    // Google's chooser/consent result always goes to the VM: a RESULT_CANCELED still carries the
    // failure status (3.3 — dropping it is why picking an account used to do nothing).
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.completeGoogleConsent(result.resultCode, result.data) { key -> key?.let(::afterConnect) }
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
                connect = connect[provider],
                configured = provider != CloudProviderType.ONEDRIVE || vm.oneDriveSetupProblem == null || account?.apiBase != null,
                setupHelp = if (provider == CloudProviderType.ONEDRIVE) vm.oneDriveSetupProblem else null,
                onConnect = {
                    when (provider) {
                        CloudProviderType.ONEDRIVE -> vm.connectOneDrive(activity) { key -> key?.let(::afterConnect) }
                        CloudProviderType.GOOGLE_DRIVE -> vm.connectGoogle(
                            email = account?.email,
                            launchConsent = { pi -> consent.launch(IntentSenderRequest.Builder(pi.intentSender).build()) },
                            onDone = { key -> key?.let(::afterConnect) },
                        )
                    }
                },
                onDismissError = { vm.clearCloudConnectError(provider) },
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

/** The connection status a card shows; never "Connected" before sign-in AND a folder succeeded. */
internal enum class SourceStatus(val label: String) {
    SETUP_REQUIRED("Setup required"),
    DISCONNECTED("Not connected"),
    CONNECTING("Connecting…"),
    AUTH_REQUIRED("Authorization required"),
    EXPIRED("Connection expired"),
    NEEDS_FOLDER("Choose a folder"),
    SYNCING("Syncing…"),
    ERROR("Problem"),
    CONNECTED("Connected"),
    ;

    companion object {
        fun of(configured: Boolean, account: CloudAccountEntity?, connect: CloudConnectState?, sync: CloudSyncState?): SourceStatus = when {
            connect is CloudConnectState.Connecting -> CONNECTING
            account == null && !configured -> SETUP_REQUIRED
            account == null && connect is CloudConnectState.Failed && connect.kind != CloudConnectState.Kind.CANCELLED -> AUTH_REQUIRED
            account == null -> DISCONNECTED
            sync is CloudSyncState.Failed && sync.needsSignIn -> EXPIRED
            sync is CloudSyncState.Syncing -> SYNCING
            account.folderId == null -> NEEDS_FOLDER
            sync is CloudSyncState.Failed -> ERROR
            sync == null && account.lastError != null -> ERROR
            else -> CONNECTED
        }
    }
}

@Composable
private fun StatusChip(status: SourceStatus) {
    val c = MaterialTheme.colorScheme
    val (bg, fg) = when (status) {
        SourceStatus.CONNECTED -> c.primary.copy(alpha = 0.18f) to c.primary
        SourceStatus.CONNECTING, SourceStatus.SYNCING -> c.tertiary.copy(alpha = 0.18f) to c.tertiary
        SourceStatus.EXPIRED, SourceStatus.AUTH_REQUIRED, SourceStatus.ERROR -> c.error.copy(alpha = 0.16f) to c.error
        else -> c.onSurface.copy(alpha = 0.08f) to c.onSurfaceVariant
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { contentDescription = "Status: ${status.label}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status == SourceStatus.CONNECTING || status == SourceStatus.SYNCING) {
            CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = fg)
        } else {
            Box(Modifier.size(7.dp).clip(CircleShape).background(fg))
        }
        Spacer(Modifier.width(6.dp))
        Text(status.label, style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CloudSourceCard(
    provider: CloudProviderType,
    account: CloudAccountEntity?,
    state: CloudSyncState?,
    stats: com.example.mimic.data.cloud.CloudAccountStats?,
    connect: CloudConnectState?,
    configured: Boolean,
    setupHelp: String?,
    onConnect: () -> Unit,
    onDismissError: () -> Unit,
    onChooseFolder: () -> Unit,
    onSync: () -> Unit,
    onDisconnect: (removeDownloads: Boolean) -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    val status = SourceStatus.of(configured, account, connect, state)
    val busy = status == SourceStatus.CONNECTING
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(c.surfaceVariant.copy(alpha = 0.72f), c.surfaceVariant.copy(alpha = 0.42f))))
            .border(1.dp, c.onSurface.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Cloud, null, tint = c.primary) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(provider.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.onSurface)
                if (account != null) {
                    Text(account.email ?: account.displayName, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1)
                }
            }
            StatusChip(status)
        }

        when (connect) {
            is CloudConnectState.Connecting -> Text(connect.step, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            is CloudConnectState.Failed -> MessageBox(connect.message, error = connect.kind != CloudConnectState.Kind.CANCELLED, onDismiss = onDismissError)
            else -> Unit
        }

        if (account == null) {
            if (!configured) {
                MessageBox(setupHelp ?: "This build has no app registration for ${provider.label}.", error = false, onDismiss = null)
            } else {
                Text(
                    if (provider == CloudProviderType.GOOGLE_DRIVE) {
                        "MImic asks for read-only access to your Drive so it can find music you upload later. It only lists the one folder you choose."
                    } else {
                        "Sign in with your Microsoft account in the browser. MImic gets read-only access to your files."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSurfaceVariant,
                )
            }
            Button(onClick = onConnect, enabled = configured && !busy) { Text(if (connect is CloudConnectState.Failed) "Try again" else "Connect account") }
            return@Column
        }

        if (account.email != null && account.displayName != account.email) Info("Account", account.displayName)
        Info("Music folder", account.folderName ?: "Not chosen yet")
        val detail = when {
            state is CloudSyncState.Syncing -> state.phase
            state is CloudSyncState.Failed -> state.message
            state == null && account.lastError != null -> account.lastError
            else -> null
        }
        if (detail != null) Info("Status", detail, error = state is CloudSyncState.Failed || state == null)
        if (stats != null && account.folderId != null) {
            val extra = buildList {
                if (stats.tagsPending > 0) add("${stats.tagsPending} waiting for details")
                if (stats.unsupported > 0) add("${stats.unsupported} unsupported files skipped")
                if (stats.duplicates > 0) add("${stats.duplicates} duplicates hidden")
            }
            Info(
                "Library",
                if (stats.songs == 0 && state !is CloudSyncState.Syncing) "No music found in this folder yet"
                else "${stats.songs} songs" + extra.joinToString("") { " · $it" },
            )
        }
        Info(
            "Last synced",
            if (account.lastSyncAt == 0L) "Never" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(account.lastSyncAt)),
        )
        if (state is CloudSyncState.Done) {
            Info("Last sync", "${state.added} new · ${state.changed} changed · ${state.removed} removed (files & folders)")
        }
        val needsAuth = status == SourceStatus.EXPIRED || status == SourceStatus.AUTH_REQUIRED
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                needsAuth -> Button(onClick = onConnect, enabled = !busy) { Text("Reconnect") }
                status == SourceStatus.NEEDS_FOLDER -> Button(onClick = onChooseFolder, enabled = !busy) { Text("Choose folder") }
                else -> Button(onClick = onSync, enabled = account.folderId != null && state !is CloudSyncState.Syncing && !busy) { Text("Sync now") }
            }
            if (status != SourceStatus.NEEDS_FOLDER) OutlinedButton(onClick = onChooseFolder, enabled = !busy) { Text("Change folder") }
            if (!needsAuth) OutlinedButton(onClick = onConnect, enabled = configured && !busy) { Text("Reconnect") }
        }
        TextButton(onClick = { confirm = true }, contentPadding = PaddingValues(0.dp)) { Text("Disconnect account", color = c.error) }
    }
    if (confirm) {
        var alsoDownloads by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Disconnect ${provider.label}?") },
            text = {
                Column {
                    Text(
                        "MImic signs out, removes its access and forgets this account's song list. Nothing in your " +
                            "${provider.label} is changed. Downloaded songs, favorites and history stay on this phone.",
                    )
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

/** Errors and setup instructions; selectable so fingerprints can be copied into a console. */
@Composable
private fun MessageBox(text: String, error: Boolean, onDismiss: (() -> Unit)?) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (error) c.error.copy(alpha = 0.12f) else c.onSurface.copy(alpha = 0.06f))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SelectionContainer(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = if (error) c.error else c.onSurfaceVariant)
        }
        if (onDismiss != null) {
            TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("OK") }
        }
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
