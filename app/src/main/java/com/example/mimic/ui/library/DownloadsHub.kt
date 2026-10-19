package com.example.mimic.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mimic.player.PlayerViewModel

/**
 * The Downloads tab (MImic 4.1): "Offline music" — every song that plays without internet or
 * Tailscale, with storage use and live download progress — and, in the full edition, "From a
 * link" (the media downloader). One tab, one place for everything downloaded.
 */
@Composable
fun DownloadsHubScreen(
    vm: PlayerViewModel,
    nav: AppNav,
    contentPadding: PaddingValues,
    sharedLinkPending: Boolean,
    linkDownloader: (@Composable (PaddingValues) -> Unit)?,
) {
    var segment by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(sharedLinkPending) { if (sharedLinkPending && linkDownloader != null) segment = 1 }
    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        Text(
            "Downloads",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
        )
        if (linkDownloader != null) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                listOf("Offline music", "From a link").forEachIndexed { i, label ->
                    SegmentedButton(selected = segment == i, onClick = { segment = i }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                }
            }
        }
        val inner = PaddingValues(bottom = contentPadding.calculateBottomPadding())
        if (segment == 1 && linkDownloader != null) linkDownloader(inner) else OfflineMusic(vm, nav, inner)
    }
}

@Composable
private fun OfflineMusic(vm: PlayerViewModel, nav: AppNav, contentPadding: PaddingValues) {
    val env = rememberRowEnv(vm, nav)
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val storage by vm.offlineStorage.collectAsStateWithLifecycle()
    val downloading by vm.downloading.collectAsStateWithLifecycle()
    var grouping by rememberSaveable { mutableStateOf(DownloadGrouping.ALL) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "storage") {
            val c = MaterialTheme.colorScheme
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(c.surfaceVariant.copy(alpha = 0.6f))
                    .border(1.dp, c.onSurface.copy(alpha = 0.08f), RoundedCornerShape(22.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudDone, null, tint = c.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("${countLabel(downloads.size)} available offline", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.onSurface)
                }
                storage?.let { s ->
                    val total = (s.usedBytes + s.freeBytes).coerceAtLeast(1)
                    LinearProgressIndicator(progress = { s.usedBytes.toFloat() / total }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                    Text("${formatBytes(s.usedBytes)} used by MImic downloads · ${formatBytes(s.freeBytes)} free", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
                Text(
                    "These play without internet or Tailscale. Phone music is always offline too. Remove a download from its ⋮ menu — the original stays on your server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSurfaceVariant,
                )
                if (downloading.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Downloading ${countLabel(downloading.size)}…", style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        downloadsSection(downloads, grouping, { grouping = it }, env)
    }
}

/** "12.4 MB" (pure, unit-tested). */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return if (v >= 100) "%.0f %s".format(java.util.Locale.US, v, units[i]) else "%.1f %s".format(java.util.Locale.US, v, units[i])
}
