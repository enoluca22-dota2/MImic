package com.example.mimic.ui.update

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mimic.BuildConfig
import com.example.mimic.MimicApplication
import com.example.mimic.ui.library.DetailTopBar
import com.example.mimic.ui.library.LocalBarPadding
import com.example.mimic.update.UpdateChannel
import com.example.mimic.update.UpdateConfig
import com.example.mimic.update.UpdateManager.State
import java.text.DateFormat
import java.util.Date

/**
 * Settings → App Updates (MImic 4.0): current/latest version, Check for Updates, what's new,
 * download progress, install through Android's own confirmation, channel and auto-check.
 */
@Composable
fun AppUpdatesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val manager = remember { (context.applicationContext as MimicApplication).container.updateManager }
    val state by manager.state.collectAsStateWithLifecycle()
    var auto by remember { mutableStateOf(manager.autoCheckEnabled) }
    var channel by remember { mutableStateOf(manager.channel) }

    // Back from "Install unknown apps": pick up the granted permission.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) manager.refreshInstallPermission() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = LocalBarPadding.current)) {
        DetailTopBar("App Updates", onBack)
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Installed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("MImic ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${BuildConfig.FLAVOR} edition · build ${BuildConfig.VERSION_CODE}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val latest = when (val s = state) {
                    is State.Available -> s.release.version.toString()
                    is State.UpToDate -> s.current
                    is State.Downloading -> s.release.version.toString()
                    is State.Verifying -> s.release.version.toString()
                    is State.ReadyToInstall -> s.release.version.toString()
                    else -> null
                }
                if (latest != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Latest", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(latest, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            val last = manager.lastCheckedAt
            Text(
                "Last checked: " + if (last == 0L) "never" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StateBody(state, manager)
        }

        Card {
            Text("Preferences", fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Automatically check for updates")
                    Text("At most once a day on any network. Only notifies — never downloads or installs by itself.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = auto, onCheckedChange = { auto = it; manager.autoCheckEnabled = it })
            }
            Text("Update channel")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in UpdateChannel.entries) {
                    FilterChip(selected = channel == c, onClick = { channel = c; manager.channel = c }, label = { Text(c.label) })
                }
            }
            if (channel == UpdateChannel.BETA) {
                Text("Beta releases may contain unfinished features. You also keep getting stable releases.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        TextButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(UpdateConfig.releasesPage))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text("View all releases on GitHub") }
        Text(
            "Updates come only from ${UpdateConfig.releasesPage}. Every APK is checked (SHA-256, package, newer version, CPU, " +
                "and MImic's own signing certificate) before Android's installer asks you to confirm. Your music, " +
                "downloads, playlists and settings stay — an update never needs an uninstall.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun StateBody(state: State, manager: com.example.mimic.update.UpdateManager) {
    when (state) {
        State.NotConfigured -> Text("This build has no update source configured.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        State.Idle -> Button(onClick = manager::checkNow) { Text("Check for Updates") }
        State.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(10.dp))
            Text("Checking GitHub…")
        }
        is State.UpToDate -> {
            Text("MImic is up to date.", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = manager::checkNow) { Text("Check again") }
        }
        is State.Available -> {
            Text("Update available: ${state.release.name}" + if (state.release.isPreRelease) " (beta)" else "", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Notes(state.release.notes)
            Text(state.release.apk.name + (state.release.apk.sizeBytes?.let { " · ${it / 1_048_576} MB" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = manager::download) { Text("Download Update") }
                TextButton(onClick = manager::dismiss) { Text("Not now") }
            }
        }
        is State.Downloading -> {
            Text("Downloading ${state.release.version}…")
            val f = state.fraction
            if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("${state.downloadedBytes / 1_048_576} / ${(state.totalBytes ?: 0) / 1_048_576} MB", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = manager::cancelDownload) { Text("Cancel") }
        }
        is State.Verifying -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(10.dp))
            Text("Verifying checksum and signature…")
        }
        is State.ReadyToInstall -> {
            Text("Verified. Ready to install ${state.release.version}.", fontWeight = FontWeight.SemiBold)
            if (state.needsPermission) {
                Text("Android needs your permission for MImic to install updates (one time).", style = MaterialTheme.typography.bodySmall)
                Button(onClick = manager::openInstallPermissionSettings) { Text("Allow installing updates") }
            } else {
                Button(onClick = manager::installReady) { Text("Install Update") }
            }
        }
        is State.Failed -> {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (state.release != null) manager.download() else manager.checkNow() }) { Text("Try again") }
                TextButton(onClick = manager::dismiss) { Text("Dismiss") }
            }
        }
    }
}

/** Release notes are untrusted text from GitHub: shown as plain text, never as links or HTML. */
@Composable
private fun Notes(notes: String) {
    if (notes.isBlank()) return
    Text("What's new", style = MaterialTheme.typography.labelLarge)
    Column(
        Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)).verticalScroll(rememberScrollState()).padding(12.dp),
    ) { Text(notes, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(c.surfaceVariant.copy(alpha = 0.72f), c.surfaceVariant.copy(alpha = 0.42f))))
            .border(1.dp, c.onSurface.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
    Spacer(Modifier.height(0.dp))
}
