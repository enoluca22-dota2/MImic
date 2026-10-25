package com.example.mimic.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mimic.data.account.AccountSync
import com.example.mimic.data.account.MimicAccount
import com.example.mimic.player.PlayerViewModel
import com.example.mimic.ui.components.ArtImage

/**
 * Settings → Accounts (4.4). Two independent, optional accounts:
 *  - MImic account (Google): identity for syncing playlists / favorites / history between phones;
 *  - YouTube Music (full edition): lets online requests act as your YouTube Music account.
 * Signing in to one never signs in to the other. Guest mode (no account) is the default.
 */
@Composable
fun AccountsScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    val state by vm.accountState.collectAsStateWithLifecycle()
    val sync by vm.syncStatus.collectAsStateWithLifecycle()
    val activity = androidx.activity.compose.LocalActivity.current
    var confirmRestore by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var syncOn by remember { mutableStateOf(vm.syncEnabled) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Accounts & online music", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("MImic account", style = MaterialTheme.typography.titleMedium)
            Text(
                "Optional. Keeps your playlists, favorites, history and radio countries the same on every phone you sign in on. " +
                    "Your music files, Navidrome password and YouTube Music session are never uploaded.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            when (val s = state) {
                MimicAccount.State.NotConfigured -> Text(
                    "Not set up in this build yet: it needs a Firebase project (see docs/ACCOUNT_SETUP.md). You're using MImic as a guest — everything works on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                MimicAccount.State.SignedOut -> {
                    Button(onClick = { activity?.let(vm::signInMimic) }) { Text("Continue with Google") }
                    Text("Or keep using MImic as a guest.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
                MimicAccount.State.Working -> Text("Signing in…")
                is MimicAccount.State.Error -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { activity?.let(vm::signInMimic) }, modifier = Modifier.padding(top = 8.dp)) { Text("Try again") }
                }
                is MimicAccount.State.SignedIn -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ArtImage(s.photoUrl, Modifier.size(48.dp), CircleShape)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(s.name ?: "Signed in", style = MaterialTheme.typography.bodyLarge)
                            s.email?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Sync this phone")
                            Text(
                                when (val st = sync) {
                                    AccountSync.Status.Off -> "Off"
                                    AccountSync.Status.Idle -> "On"
                                    AccountSync.Status.Syncing -> "Syncing…"
                                    is AccountSync.Status.Done -> "Last sync: ${st.added}"
                                    is AccountSync.Status.Failed -> st.message
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = syncOn, onCheckedChange = { syncOn = it; vm.setSyncEnabled(it) })
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::syncNow, enabled = syncOn) { Text("Sync now") }
                        OutlinedButton(onClick = { confirmRestore = true }) { Text("Restore settings") }
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::signOutMimic) { Text("Sign out") }
                        TextButton(onClick = { confirmDelete = true }) { Text("Delete cloud copy") }
                    }
                    Text(
                        "Signing out keeps everything on this phone. Switching accounts: sign out, then continue with another Google account.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        com.example.mimic.online.YouTubeMusicAccountSection()
        if (vm.onlineAvailable) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Online music", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            com.example.mimic.online.OnlineSettingsSection()
        }
        Spacer(Modifier.height(140.dp))
    }

    if (confirmRestore) AlertDialog(
        onDismissRequest = { confirmRestore = false },
        title = { Text("Restore settings?") },
        text = { Text("Your theme, player layout and other settings will be replaced with the ones from your most recently synced other phone. Music, playlists and downloads are not affected.") },
        confirmButton = { TextButton(onClick = { confirmRestore = false; vm.restoreSyncedSettings() }) { Text("Restore") } },
        dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete the cloud copy?") },
        text = { Text("This removes the synced data of every phone from your MImic account. Nothing on this phone is deleted.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteCloudData() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}
