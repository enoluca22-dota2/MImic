package com.example.mimic.ui.radio

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mimic.player.PlayerUiState

/**
 * Live radio in any player layout: instead of a timeline (a live stream can't be seeked) a LIVE
 * badge, what's on air when the station says so, and Reconnect after a failure.
 */
@Composable
fun LiveRow(state: PlayerUiState, flag: String?, textColor: Color, mutedColor: Color, accent: Color, onReconnect: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveBadge(accent)
            if (flag != null) {
                Spacer(Modifier.width(8.dp))
                Text(flag, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                state.liveTitle?.let { "On air: $it" } ?: if (state.isBuffering) "Connecting…" else "Live radio",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (state.liveTitle != null) textColor else mutedColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state.playbackError != null) {
                if (com.example.mimic.ui.designsystem.LocalDepth.current.matteAmber) {
                    com.example.mimic.ui.components.DepthIconButton(Icons.Default.Refresh, "Reconnect", onReconnect)
                } else TextButton(onClick = onReconnect) {
                    Icon(Icons.Default.Refresh, null, tint = textColor, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reconnect", color = textColor)
                }
            }
        }
        VolumeSlider(textColor, accent)
    }
}

/** The phone's media volume (the same one the volume keys change). */
@Composable
fun VolumeSlider(iconColor: Color, accent: Color) {
    // 4.8: shared media-volume state — also follows the hardware volume keys now.
    val volume = com.example.mimic.player.rememberMediaVolume()
    if (com.example.mimic.ui.designsystem.LocalDepth.current.matteAmber) {
        com.example.mimic.ui.player.HardwareSlider("Media volume", volume.fraction, volume::setFraction, enabled = !volume.fixed)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.VolumeDown, "Volume", tint = iconColor)
        com.example.mimic.ui.components.MimicSlider(
            value = volume.fraction,
            onValueChange = { v -> volume.setFraction(v) },
            enabled = !volume.fixed,
            colors = SliderDefaults.colors(thumbColor = iconColor, activeTrackColor = accent),
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = iconColor)
    }
}
