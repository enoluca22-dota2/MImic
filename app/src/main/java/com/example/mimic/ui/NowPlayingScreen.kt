package com.example.mimic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.example.mimic.data.ArtworkShape
import com.example.mimic.data.PlayerLayout
import com.example.mimic.data.PlayerLayoutConfig
import com.example.mimic.data.cornerDp
import com.example.mimic.data.fraction
import com.example.mimic.player.PlayerUiState
import com.example.mimic.ui.components.AlbumArt
import com.example.mimic.ui.components.formatDuration

@Composable
fun NowPlayingScreen(
    state: PlayerUiState,
    config: PlayerLayoutConfig,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSleep: () -> Unit,
) {
    val song = state.currentSong
    // System back collapses the full-screen player (previously it could get stuck open).
    BackHandler(onBack = onBack)
    // Immersive uses a subtle top-down gradient from the accent into the background.
    val background = if (config.layout == PlayerLayout.IMMERSIVE) {
        Modifier.background(
            Brush.verticalGradient(
                listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    MaterialTheme.colorScheme.background,
                ),
            ),
        )
    } else {
        Modifier.background(MaterialTheme.colorScheme.background)
    }

    val artworkFraction = when (config.layout) {
        PlayerLayout.IMMERSIVE -> maxOf(config.artworkSize.fraction(), 0.9f)
        PlayerLayout.COMPACT -> minOf(config.artworkSize.fraction(), 0.6f)
        PlayerLayout.CLASSIC -> config.artworkSize.fraction()
    }
    val corner = if (config.artworkShape == ArtworkShape.CIRCLE) 1000 else config.artworkShape.cornerDp()

    Column(
        // statusBarsPadding keeps the collapse chevron below the status-bar clock so it stays tappable.
        modifier = Modifier.fillMaxSize().then(background).statusBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top bar: collapse + optional secondary actions.
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Collapse")
            }
            Text(
                "Now Playing",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            if (config.showSecondaryActions) {
                IconButton(onClick = onOpenSleep) { Icon(Icons.Default.Bedtime, contentDescription = "Sleep timer") }
                IconButton(onClick = onOpenQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Queue") }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }

        if (config.layout == PlayerLayout.COMPACT) Spacer(Modifier.height(12.dp)) else Spacer(Modifier.height(24.dp))

        AlbumArt(
            song,
            modifier = Modifier.fillMaxWidth(artworkFraction).aspectRatio(1f),
            corner = corner,
        )

        if (config.layout == PlayerLayout.IMMERSIVE) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(28.dp))

        // Title + favorite
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    song?.title ?: "Nothing playing",
                    style = if (config.layout == PlayerLayout.COMPACT) MaterialTheme.typography.titleLarge
                    else MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song?.artist ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onToggleFavorite, enabled = song != null) {
                Icon(
                    if (state.isCurrentFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Like",
                    tint = if (state.isCurrentFavorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(if (config.layout == PlayerLayout.COMPACT) 8.dp else 16.dp))
        SeekBar(state, onSeek)

        Spacer(Modifier.height(8.dp))
        Controls(state, config, onPlayPause, onNext, onPrevious, onShuffle, onRepeat)

        if (config.layout == PlayerLayout.IMMERSIVE) Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Controls(
    state: PlayerUiState,
    config: PlayerLayoutConfig,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (config.showShuffleRepeat) {
            IconButton(onClick = onShuffle) {
                Icon(
                    Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (state.shuffle) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onPrevious) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(40.dp))
        }
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(40.dp),
            )
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(40.dp))
        }
        if (config.showShuffleRepeat) {
            IconButton(onClick = onRepeat) {
                Icon(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne
                    else Icons.Default.Repeat,
                    contentDescription = "Repeat",
                    tint = if (state.repeatMode == Player.REPEAT_MODE_OFF)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SeekBar(state: PlayerUiState, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val duration = state.durationMs.coerceAtLeast(1L).toFloat()
    val position = if (dragging) dragValue else state.positionMs.toFloat().coerceIn(0f, duration)

    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = position,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { onSeek(dragValue.toLong()); dragging = false },
            valueRange = 0f..duration,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.onBackground,
                activeTrackColor = MaterialTheme.colorScheme.onBackground,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                formatDuration(position.toLong()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatDuration(state.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
