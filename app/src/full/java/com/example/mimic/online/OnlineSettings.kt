package com.example.mimic.online

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Settings → Online music (full edition): stream quality per network, download format, Wi-Fi only. */
@Composable
fun OnlineSettingsSection() {
    var wifi by remember { mutableStateOf(OnlineQuality.wifi) }
    var mobile by remember { mutableStateOf(OnlineQuality.mobile) }
    var format by remember { mutableStateOf(OnlineQuality.downloadFormat) }
    var wifiOnly by remember { mutableStateOf(OnlineQuality.wifiOnlyDownloads) }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Title("Streaming on Wi-Fi")
        OnlineQuality.Stream.entries.forEach { q -> Choice(q.label, q.detail, q == wifi) { OnlineQuality.wifi = q; wifi = q } }
        Title("Streaming on mobile data")
        OnlineQuality.Stream.entries.forEach { q -> Choice(q.label, q.detail, q == mobile) { OnlineQuality.mobile = q; mobile = q } }
        Title("Download format")
        OnlineQuality.DownloadFormat.entries.forEach { f -> Choice(f.label, f.detail, f == format) { OnlineQuality.downloadFormat = f; format = f } }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Download only on Wi-Fi")
                Text("Downloads wait for Wi-Fi instead of using mobile data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = wifiOnly, onCheckedChange = { OnlineQuality.wifiOnlyDownloads = it; wifiOnly = it })
        }
        Text(
            "YouTube audio is lossy (Opus or AAC). MImic saves it in its real format and never labels it lossless. " +
                "Downloads run in the background with a notification, wait for a connection, and are never saved twice.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun Title(t: String) = Text(t, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))

@Composable
private fun Choice(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(label)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
