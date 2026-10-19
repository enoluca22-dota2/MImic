package com.example.mimic.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.mimic.downloader.Downloader

/** Settings → Advanced (MImic 4.3.1): troubleshooting tools. */
@Composable
fun AdvancedScreen(onBack: () -> Unit, onOpenDownloadDiagnostics: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("Advanced", onBack)
        if (Downloader.AVAILABLE) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpenDownloadDiagnostics).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.BugReport, contentDescription = null, tint = c.onSurfaceVariant) }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Download Diagnostics", style = MaterialTheme.typography.bodyLarge, color = c.onBackground)
                    Text("Record why a YouTube/TikTok link fails and share a report", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            }
        } else {
            Note("Nothing to adjust here in this edition.")
        }
    }
}
