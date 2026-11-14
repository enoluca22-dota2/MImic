package com.example.mimic.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import com.example.mimic.ui.designsystem.matteGrain
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.example.mimic.data.VolumeControl
import com.example.mimic.player.PlayerUiState
import com.example.mimic.player.rememberMediaVolume
import com.example.mimic.ui.components.DepthIconButton
import com.example.mimic.ui.components.formatDuration
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val PanelShape = RoundedCornerShape(20.dp)

@Composable
internal fun ControlDeckPanel(
    state: PlayerUiState,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    showShuffleRepeat: Boolean,
    volumeMode: VolumeControl?,
    eqEnabled: Boolean,
    onEqEnabledChange: ((Boolean) -> Unit)?,
    hapticSeek: Boolean,
    liveContent: @Composable () -> Unit,
) {
    val matteAmber = com.example.mimic.ui.designsystem.LocalDepth.current.matteAmber
    val panelColors = if (matteAmber) listOf(Color(0xFF26262E), Color(0xFF18181E))
        else listOf(Color(0xFF30353C), Color(0xFF191D22))
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(panelColors), PanelShape)
            .border(1.dp, if (matteAmber) Color(0xFF34343D) else Color(0xFF484E56), PanelShape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state.isLive) liveContent() else ControlDeckSeek(state, onSeek, hapticSeek)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showShuffleRepeat) {
                DepthIconButton(Icons.Default.Shuffle, "Shuffle", onShuffle, size = 44.dp, iconSize = 18.dp,
                    selected = state.shuffle, stateDescription = if (state.shuffle) "On" else "Off")
            }
            HardwareButton("Previous", TransportIcon.Previous, onPrevious, Modifier.weight(1f))
            HardwareButton(
                if (state.isPlaying) "Pause" else "Play",
                if (state.isPlaying) TransportIcon.Pause else TransportIcon.Play,
                onPlayPause, Modifier.weight(1.25f), illuminated = true,
            )
            HardwareButton("Next", TransportIcon.Next, onNext, Modifier.weight(1f))
            if (showShuffleRepeat) {
                DepthIconButton(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    "Repeat", onRepeat, size = 44.dp, iconSize = 18.dp,
                    selected = state.repeatMode != Player.REPEAT_MODE_OFF,
                    stateDescription = when (state.repeatMode) {
                        Player.REPEAT_MODE_ONE -> "Repeat one"
                        Player.REPEAT_MODE_ALL -> "Repeat all"
                        else -> "Off"
                    },
                )
            }
        }
        if (!state.isLive && (volumeMode != null || onEqEnabledChange != null)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (volumeMode != null) {
                    val volume = rememberMediaVolume()
                    Column(Modifier.weight(if (matteAmber) 1.35f else 1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        val percent = "${(volume.fraction * 100).roundToInt()}%"
                        PanelLabel("Volume · $percent")
                        if (volumeMode == VolumeControl.SLIDER) {
                            HardwareSlider("Media volume", volume.fraction, volume::setFraction,
                                Modifier.fillMaxWidth().padding(horizontal = 8.dp), enabled = !volume.fixed,
                                valueDescription = percent)
                        } else {
                            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                val dialSize = if (matteAmber) minOf(maxWidth, 136.dp) else 88.dp
                                HardwareKnob("Media volume", volume.fraction, volume::setFraction, Modifier.size(dialSize),
                                    enabled = !volume.fixed, valueDescription = percent,
                                    keyboardStep = 1f / volume.max)
                            }
                        }
                    }
                }
                if (onEqEnabledChange != null) {
                    Column(Modifier.weight(if (matteAmber) 1.35f else 1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        PanelLabel("EQ bypass")
                        HardwareSwitch("EQ bypass", !eqEnabled, { onEqEnabledChange(!it) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlDeckSeek(state: PlayerUiState, onSeek: (Long) -> Unit, haptic: Boolean) {
    val duration = state.durationMs
    val songId = state.currentSong?.id
    var pending by remember(songId, duration) { mutableStateOf<Float?>(null) }
    val value = pending ?: if (duration > 0) (state.positionMs.toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f
    val elapsed = formatDuration((value.toDouble() * duration.coerceAtLeast(0)).roundToLong())
    val total = if (duration > 0) formatDuration(duration) else "—"
    val feedback = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth()) {
        androidx.compose.runtime.key(songId, duration) {
            HardwareSlider(
                "Seek", value, { pending = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = duration > 0,
                onValueChangeFinished = {
                    pending?.let { fraction ->
                        onSeek((fraction.toDouble() * duration).roundToLong().coerceIn(0, duration))
                        if (haptic) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    pending = null
                },
                valueDescription = "$elapsed of $total",
            )
        }
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.SpaceBetween) {
            PanelLabel(elapsed)
            PanelLabel(total)
        }
    }
}

@Composable
private fun PanelLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Color(0xFFB4BCC5),
        fontFamily = FontFamily.Monospace)
}
