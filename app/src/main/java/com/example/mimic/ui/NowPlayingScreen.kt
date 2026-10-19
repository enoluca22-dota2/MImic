package com.example.mimic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Speed
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.material.icons.filled.Stop
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
import com.example.mimic.ui.components.GlassIconButton
import com.example.mimic.data.ControlStyle
import com.example.mimic.data.PlayerBackground
import com.example.mimic.data.resolvedBackground
import com.example.mimic.data.resolvedControls
import com.example.mimic.ui.designsystem.LocalGlass
import com.example.mimic.ui.designsystem.LocalGlassBackdrop
import com.example.mimic.ui.designsystem.blockTouchesBehind
import com.example.mimic.ui.designsystem.glassSurface
import com.example.mimic.ui.designsystem.roundControlShape
import com.example.mimic.ui.designsystem.surfaceShape
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.RoundedRectangle
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.vector.ImageVector
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
    onStop: () -> Unit = {},
    onOpenLyrics: () -> Unit = {},
    onOpenSpeed: () -> Unit = {},
    /** Current playback speed (shown on the Speed action). */
    speed: Float = 1f,
    sleepActive: Boolean = false,
    hapticSeek: Boolean = true,
    /** 4.1: Cast + Share actions, top-right. */
    topEndActions: @Composable () -> Unit = {},
) {
    val song = state.currentSong
    // System back collapses (minimizes) the full-screen player — it never gets stuck open.
    BackHandler(onBack = onBack)
    val env = LocalGlass.current
    val glassTheme = env.enabled
    val background = config.resolvedBackground(glassTheme)
    val controls = config.resolvedControls(glassTheme)
    // The overlay must FULLY occlude whatever is behind it. Gradient / artwork backgrounds are
    // translucent, so they are always layered ON TOP of an opaque base; otherwise the screen
    // underneath bleeds through (the 2.8.4 "Now Playing over the settings page" bug).
    val gradientOverlay = if (background == PlayerBackground.GRADIENT) {
        Modifier.background(
            Brush.verticalGradient(
                listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    MaterialTheme.colorScheme.background,
                ),
            ),
        )
    } else {
        Modifier
    }
    // Glass controls sample the player's own background (never the screen behind the overlay).
    val liveGlass = glassTheme && env.quality.blurFactor > 0f &&
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    val playerBackdrop = rememberLayerBackdrop()

    val artworkFraction = when (config.layout) {
        PlayerLayout.IMMERSIVE -> maxOf(config.artworkSize.fraction(), 0.9f)
        PlayerLayout.COMPACT -> minOf(config.artworkSize.fraction(), 0.6f)
        PlayerLayout.CLASSIC, PlayerLayout.DYNAMIC -> config.artworkSize.fraction()
    }
    val corner = if (config.artworkShape == ArtworkShape.CIRCLE) 1000 else config.artworkShape.cornerDp()
    val artShape = if (config.artworkShape == ArtworkShape.SQUIRCLE) RoundedRectangle(corner.dp) else null

    // Opaque base fills edge-to-edge (behind the system bars for an immersive look); the content
    // Column is inset with systemBarsPadding so the header clears the status bar and the controls
    // clear the navigation bar.
    Box(
        Modifier.fillMaxSize()
            .blockTouchesBehind()
            .background(MaterialTheme.colorScheme.background),
    ) {
    Box(
        Modifier.matchParentSize()
            .then(if (liveGlass) Modifier.layerBackdrop(playerBackdrop) else Modifier)
            .then(gradientOverlay),
    ) {
        if (background == PlayerBackground.BLURRED_ARTWORK) BlurredArtwork(song)
    }
    CompositionLocalProvider(LocalGlassBackdrop provides if (liveGlass) playerBackdrop else null) {
    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top bar: Minimize (collapse, keeps playing) + Stop & Close + optional secondary actions.
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TopAction(controls, Icons.Default.KeyboardArrowDown, "Minimize", onBack)
            Spacer(Modifier.width(if (controls == ControlStyle.GLASS) 8.dp else 0.dp))
            TopAction(controls, Icons.Default.Stop, "Stop & close", onStop)
            Text(
                "Now Playing",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            // Same width as Minimize + Stop, so the title stays centered.
            Row(Modifier.width(if (controls == ControlStyle.GLASS) 104.dp else 96.dp), horizontalArrangement = Arrangement.End) { topEndActions() }
        }

        if (config.layout == PlayerLayout.COMPACT) Spacer(Modifier.height(12.dp)) else Spacer(Modifier.height(24.dp))

        // Swipe the artwork sideways to skip (left = next, right = previous); it follows the finger.
        var dragX by remember { mutableStateOf(0f) }
        val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
        // The artwork takes what's left after the text + controls, so on a small screen it shrinks
        // instead of pushing the controls and the action row off the bottom.
        Box(Modifier.weight(1f, fill = false), contentAlignment = Alignment.Center) {
        AlbumArt(
            song,
            modifier = Modifier
                .fillMaxWidth(artworkFraction)
                .aspectRatio(1f, matchHeightConstraintsFirst = true)
                .graphicsLayer {
                    translationX = dragX * 0.6f
                    alpha = 1f - (kotlin.math.abs(dragX) / (threshold * 4)).coerceAtMost(0.4f)
                }
                .pointerInput(onNext, onPrevious) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (dragX <= -threshold) onNext() else if (dragX >= threshold) onPrevious()
                            dragX = 0f
                        },
                        onDragCancel = { dragX = 0f },
                        onHorizontalDrag = { change, dx -> change.consume(); dragX += dx },
                    )
                }
                .semantics { contentDescription = "Album artwork. Swipe left for next song, right for previous." },
            corner = corner,
            shape = artShape,
        )
        }

        if (config.layout == PlayerLayout.IMMERSIVE) Spacer(Modifier.weight(0.01f)) else Spacer(Modifier.height(if (config.layout == PlayerLayout.COMPACT) 16.dp else 24.dp))

        // Title + favorite
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    song?.title ?: "Nothing playing",
                    style = if (config.layout == PlayerLayout.COMPACT) MaterialTheme.typography.titleLarge
                    else MaterialTheme.typography.headlineSmall,
                    fontWeight = env.headerFontWeight,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    // Long titles scroll slowly instead of being cut off (respecting reduced motion).
                    modifier = if (env.motionScale > 0f) Modifier.basicMarquee(initialDelayMillis = 1500) else Modifier,
                    overflow = if (env.motionScale > 0f) TextOverflow.Clip else TextOverflow.Ellipsis,
                )
                Text(
                    song?.artist ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Clear status: a failed song says why; buffering is shown, never a silent freeze.
                when {
                    state.playbackError != null -> Text(
                        state.playbackError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                    )
                    state.isBuffering -> Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Buffering…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
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
        if (controls == ControlStyle.GLASS) {
            // One frosted control deck holding seek + transport, like a floating glass panel.
            Column(
                Modifier
                    .fillMaxWidth()
                    .glassSurface(env.style.surfaceShape(1.1f), fallback = MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                SeekBar(state, onSeek, hapticSeek)
                Spacer(Modifier.height(4.dp))
                Controls(state, config, controls, onPlayPause, onNext, onPrevious, onShuffle, onRepeat)
            }
        } else {
            SeekBar(state, onSeek, hapticSeek)
            Spacer(Modifier.height(8.dp))
            Controls(state, config, controls, onPlayPause, onNext, onPrevious, onShuffle, onRepeat)
        }

        if (config.showSecondaryActions) {
            Spacer(Modifier.height(if (config.layout == PlayerLayout.COMPACT) 8.dp else 16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BottomAction(Icons.Default.Lyrics, "Lyrics", onOpenLyrics)
                BottomAction(Icons.Default.Speed, com.example.mimic.ui.player.formatSpeed(speed), onOpenSpeed, highlighted = speed != 1f, description = "Playback speed")
                BottomAction(Icons.Default.Bedtime, if (sleepActive) "Sleep on" else "Sleep", onOpenSleep, highlighted = sleepActive, description = "Sleep timer")
                BottomAction(Icons.AutoMirrored.Filled.QueueMusic, "Queue", onOpenQueue)
            }
        }

        if (config.layout == PlayerLayout.IMMERSIVE) Spacer(Modifier.height(24.dp))
    }
    }
    }
}

/** Top-bar action: a glass circle on glass control style, else the original plain icon button. */
@Composable
private fun TopAction(style: ControlStyle, icon: ImageVector, label: String, onClick: () -> Unit) {
    if (style == ControlStyle.GLASS) {
        GlassIconButton(icon, label, onClick, size = 48.dp, iconSize = 22.dp)
    } else {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label) }
    }
}

/**
 * The current cover, heavily blurred and dimmed, as the player backdrop. Static (no animation);
 * Coil serves the already-cached cover by its stable URL at a small decode size. Below API 31
 * [blur] is a no-op, so the scrim is denser there to keep text readable.
 */
@Composable
private fun BlurredArtwork(song: com.example.mimic.data.Song?) {
    val art = song?.albumArtUri ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    val request = remember(art) {
        coil.request.ImageRequest.Builder(context).data(art).size(256).crossfade(true).build()
    }
    val canBlur = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    val bg = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize()) {
        coil.compose.AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            alpha = if (canBlur) 0.85f else 0.35f,
            modifier = Modifier.fillMaxSize().then(if (canBlur) Modifier.blur(60.dp) else Modifier),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(bg.copy(alpha = 0.25f), bg.copy(alpha = 0.55f), bg.copy(alpha = 0.88f)),
                ),
            ),
        )
    }
}

@Composable
private fun Controls(
    state: PlayerUiState,
    config: PlayerLayoutConfig,
    style: ControlStyle,
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
        val playIcon = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow
        val playLabel = if (state.isPlaying) "Pause" else "Play"
        when (style) {
            ControlStyle.GLASS -> {
                GlassIconButton(Icons.Default.SkipPrevious, "Previous", onPrevious, size = 56.dp, iconSize = 32.dp)
                GlassIconButton(playIcon, playLabel, onPlayPause, size = 76.dp, iconSize = 40.dp, accent = true)
                GlassIconButton(Icons.Default.SkipNext, "Next", onNext, size = 56.dp, iconSize = 32.dp)
            }
            ControlStyle.MINIMAL -> {
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = onPlayPause, modifier = Modifier.size(72.dp)) {
                    Icon(playIcon, contentDescription = playLabel, modifier = Modifier.size(56.dp))
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
                }
            }
            else -> {
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(40.dp))
                }
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(LocalGlass.current.style.roundControlShape())
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onPlayPause),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        playIcon,
                        contentDescription = playLabel,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(40.dp),
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(40.dp))
                }
            }
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

/** Labeled icon action under the controls. */
@Composable
private fun BottomAction(icon: ImageVector, label: String, onClick: () -> Unit, highlighted: Boolean = false, description: String = label) {
    val tint = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@Composable
private fun SeekBar(state: PlayerUiState, onSeek: (Long) -> Unit, haptic: Boolean = true) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val duration = state.durationMs.coerceAtLeast(1L).toFloat()
    val position = if (dragging) dragValue else state.positionMs.toFloat().coerceIn(0f, duration)

    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = position,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                onSeek(dragValue.toLong())
                dragging = false
                if (haptic) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
            },
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
