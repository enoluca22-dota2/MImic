package com.example.mimic.ui.sources

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mimic.data.MusicProvider
import com.example.mimic.player.PlayerViewModel
import com.example.mimic.ui.library.DetailTopBar
import com.example.mimic.ui.library.LocalBarPadding
import com.example.mimic.ui.library.countLabel

/**
 * Settings → Music Sources (MImic 4.1): the three sources MImic plays from — music on this phone,
 * the user's Navidrome server, and offline downloads. (Google Drive / OneDrive were removed in 4.1;
 * songs already downloaded from them stay as offline songs.)
 */
@Composable
fun MusicSourcesScreen(
    vm: PlayerViewModel,
    onBack: () -> Unit,
    onOpenNavidrome: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val songs by vm.songs.collectAsStateWithLifecycle()
    val serverStatus by vm.serverStatus.collectAsStateWithLifecycle()
    val creds by vm.serverCredentials.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = LocalBarPadding.current)) {
        DetailTopBar("Music Sources", onBack)
        SourceCard(
            icon = Icons.Default.PhoneAndroid,
            title = "Phone music",
            subtitle = countLabel(songs.count { it.provider == MusicProvider.PHONE && !it.isDownloaded }) +
                " on this phone · found automatically · always available offline",
            onClick = null,
        )
        SourceCard(
            icon = Icons.Default.Dns,
            title = "Navidrome",
            subtitle = if (serverStatus == com.example.mimic.data.server.ServerStatus.Connected && creds != null) {
                "Signed in as ${creds!!.username} · ${countLabel(songs.count { it.provider == MusicProvider.NAVIDROME })} · streams from your server (LAN or Tailscale)"
            } else {
                "Not signed in — tap to connect your own music server"
            },
            onClick = onOpenNavidrome,
        )
        SourceCard(
            icon = Icons.Default.DownloadDone,
            title = "Offline downloads",
            subtitle = countLabel(downloads.size) + " saved on this phone · play without internet or Tailscale",
            onClick = onOpenDownloads,
        )
    }
}

@Composable
private fun SourceCard(icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)?) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(c.surfaceVariant.copy(alpha = 0.72f), c.surfaceVariant.copy(alpha = 0.42f))))
            .border(1.dp, c.onSurface.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(c.primary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.primary)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
    }
}
