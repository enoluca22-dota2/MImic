package com.example.mimic.ui.dynamic

import com.example.mimic.ui.designsystem.surfaceShape
import com.example.mimic.ui.designsystem.depthPanel
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import com.example.mimic.ui.designsystem.mattePlayerBackground
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.example.mimic.data.DynamicPlayerConfig
import com.example.mimic.data.Song
import com.example.mimic.data.animatesBackground
import com.example.mimic.data.transitionMs
import com.example.mimic.player.LyricsState
import com.example.mimic.player.PlayerUiState
import com.example.mimic.player.PlayerViewModel
import com.example.mimic.ui.components.formatDuration
import com.example.mimic.ui.designsystem.LocalGlass
import com.example.mimic.ui.designsystem.blockTouchesBehind

/** Transport + navigation callbacks of the Dynamic player (all actions go to the one Media3 player). */
class DynamicActions(
    val onBack: () -> Unit,
    val onStop: () -> Unit,
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onShuffle: () -> Unit,
    val onRepeat: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onOpenQueue: () -> Unit,
    val onOpenSleep: () -> Unit,
    val onOpenSpeed: () -> Unit,
    val onShare: () -> Unit,
    val onOpenLockView: () -> Unit,
    val onOpenSettings: () -> Unit,
    /** 4.2 radio: restart the station after a stream failure. */
    val onReconnect: () -> Unit = {},
    /** 4.2 radio: the station's country flag. */
    val radioFlag: String? = null,
)

/** The current song's scheme, animated between songs (once per artwork, never per frame). */
@Composable
fun rememberDynamicScheme(song: Song?, config: DynamicPlayerConfig): AnimatedScheme {
    val context = LocalContext.current
    val reduced = LocalGlass.current.motionScale == 0f
    val art = song?.albumArtUri
    var swatches by remember { mutableStateOf(DynamicPalette.cached(art)) }
    LaunchedEffect(art) { swatches = DynamicPalette.cached(art) ?: DynamicPalette.swatches(context, art) }
    val scheme = remember(swatches, config.brightness, config.autoContrast) {
        swatches?.let { DynamicColorMath.derive(it, config.brightness, config.autoContrast) }
            ?: DynamicScheme.fallback
    }
    val ms = config.transitionMs(reduced)
    val spec = if (ms == 0) snap<Color>() else tween<Color>(ms)
    return AnimatedScheme(
        top = animateColorAsState(Color(scheme.top), spec, label = "dynTop").value,
        bottom = animateColorAsState(Color(scheme.bottom), spec, label = "dynBottom").value,
        glow = animateColorAsState(Color(scheme.glow), spec, label = "dynGlow").value,
        accent = animateColorAsState(Color(scheme.accent), spec, label = "dynAccent").value,
        onBackground = animateColorAsState(Color(scheme.onBackground), spec, label = "dynOn").value,
        muted = animateColorAsState(Color(scheme.onBackgroundMuted), spec, label = "dynMuted").value,
    )
}

data class AnimatedScheme(val top: Color, val bottom: Color, val glow: Color, val accent: Color, val onBackground: Color, val muted: Color)

/** Artwork gradient + optional blurred artwork + slow ambient glow (paused with reduced motion). */
@Composable
fun DynamicBackground(song: Song?, scheme: AnimatedScheme, config: DynamicPlayerConfig, modifier: Modifier = Modifier) {
    val reduced = LocalGlass.current.motionScale == 0f
    val animate = config.animatesBackground(reduced)
    val amplitude = when (config.intensity) {
        com.example.mimic.data.DynamicIntensity.SUBTLE -> 0.06f
        com.example.mimic.data.DynamicIntensity.BALANCED -> 0.12f
        com.example.mimic.data.DynamicIntensity.IMMERSIVE -> 0.2f
    }
    val phase = if (animate) {
        val t = rememberInfiniteTransition(label = "dynBg")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse), label = "dynPhase").value
    } else 0.5f
    Box(modifier.fillMaxSize().background(scheme.bottom)) {
        if (config.gradient) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(scheme.top, scheme.bottom))))
        }
        val art = song?.albumArtUri
        if (config.artworkBlur && art != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.32f,
                modifier = Modifier.fillMaxSize().blur(72.dp),
            )
        }
        // Ambient glow from the artwork's vibrant color, drifting slowly when animation is on.
        Box(
            Modifier.fillMaxSize().drawBehind {
                val cx = size.width * (0.5f + (phase - 0.5f) * amplitude * 2f)
                val cy = size.height * (0.28f + (0.5f - phase) * amplitude)
                drawCircle(
                    Brush.radialGradient(listOf(scheme.glow.copy(alpha = 0.55f), Color.Transparent), center = Offset(cx, cy), radius = size.maxDimension * 0.55f),
                    radius = size.maxDimension * 0.55f,
                    center = Offset(cx, cy),
                )
            },
        )
        // Keep the lower part readable whatever the artwork.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to scheme.bottom.copy(alpha = 0.85f))))
    }
}

@Composable
fun DynamicNowPlayingScreen(
    vm: PlayerViewModel,
    state: PlayerUiState,
    config: DynamicPlayerConfig,
    speed: Float,
    sleepActive: Boolean,
    actions: DynamicActions,
    castButton: @Composable (tint: Color) -> Unit,
    controlDeck: Boolean = false,
    eqEnabled: Boolean = false,
    onEqEnabledChange: ((Boolean) -> Unit)? = null,
    volumeMode: com.example.mimic.data.VolumeControl? = null,
    hapticSeek: Boolean = true,
) {
    val song = state.currentSong
    var lyricsOpen by remember { mutableStateOf(false) }
    BackHandler { if (lyricsOpen) lyricsOpen = false else actions.onBack() }
    val scheme = rememberDynamicScheme(song, config)
    val matteAmber = com.example.mimic.ui.designsystem.LocalDepth.current.matteAmber
    val reduced = LocalGlass.current.motionScale == 0f
    val ms = config.transitionMs(reduced)

    Box(Modifier.fillMaxSize().blockTouchesBehind()) {
        if (matteAmber) Box(Modifier.fillMaxSize().mattePlayerBackground())
        else DynamicBackground(song, scheme, config)
        if (lyricsOpen) {
            DynamicLyrics(vm, state, scheme, onClose = { lyricsOpen = false })
            return@Box
        }
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Top bar: Minimize · Stop & Close · title · Lock view
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundAction(Icons.Default.KeyboardArrowDown, "Minimize", scheme, config.glassControls, actions.onBack)
                Spacer(Modifier.width(8.dp))
                RoundAction(Icons.Default.Stop, "Stop & close", scheme, config.glassControls, actions.onStop)
                val equipmentLabel = com.example.mimic.ui.designsystem.LocalDepth.current.equipment
                Text(
                    if (equipmentLabel) "NOW PLAYING" else "Now Playing",
                    style = if (equipmentLabel) MaterialTheme.typography.labelMedium.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(2.5f, androidx.compose.ui.unit.TextUnitType.Sp))
                    else MaterialTheme.typography.titleSmall,
                    color = scheme.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(48.dp))
                RoundAction(Icons.Default.Lock, "Lock-screen style view", scheme, config.glassControls, actions.onOpenLockView)
            }
            Spacer(Modifier.height(20.dp))

            // Artwork — crossfades between songs; swipe sideways to skip.
            var dragX by remember { mutableFloatStateOf(0f) }
            val threshold = with(LocalDensity.current) { 72.dp.toPx() }
            Box(Modifier.weight(1f, fill = false), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = song,
                    contentKey = { it?.id },
                    transitionSpec = {
                        if (ms == 0) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                        else (fadeIn(tween(ms)) + scaleIn(tween(ms), initialScale = 0.94f)) togetherWith fadeOut(tween(ms / 2))
                    },
                    label = "dynArt",
                ) { s ->
                    DynamicArtwork(
                        s, config,
                        Modifier.fillMaxWidth(config.artSize.fraction)
                            .aspectRatio(1f, matchHeightConstraintsFirst = true)
                            .graphicsLayer { translationX = dragX * 0.6f }
                            .pointerInput(actions) {
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        if (dragX <= -threshold) actions.onNext() else if (dragX >= threshold) actions.onPrevious()
                                        dragX = 0f
                                    },
                                    onDragCancel = { dragX = 0f },
                                    onHorizontalDrag = { change, dx -> change.consume(); dragX += dx },
                                )
                            }
                            .semantics { contentDescription = "Album artwork. Swipe left for next song, right for previous." },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))

            // Title, artist, favorite
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        song?.title ?: "Nothing playing",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onBackground,
                        maxLines = 1,
                        modifier = if (!reduced) Modifier.basicMarquee(initialDelayMillis = 1500) else Modifier,
                        overflow = if (!reduced) TextOverflow.Clip else TextOverflow.Ellipsis,
                    )
                    Text(song?.artist.orEmpty(), style = MaterialTheme.typography.titleMedium, color = scheme.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    when {
                        state.playbackError != null -> Text(state.playbackError, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFB4AB), maxLines = 3)
                        state.isBuffering -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = scheme.accent)
                            Spacer(Modifier.width(6.dp))
                            Text("Buffering…", style = MaterialTheme.typography.bodySmall, color = scheme.muted)
                        }
                    }
                }
                if (com.example.mimic.ui.designsystem.LocalDepth.current.let { it.equipment || it.matteAmber }) {
                    com.example.mimic.ui.components.DepthIconButton(
                        if (state.isCurrentFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Like",
                        onClick = actions.onToggleFavorite,
                        enabled = song != null,
                        selected = state.isCurrentFavorite,
                        stateDescription = if (state.isCurrentFavorite) "Liked" else "Not liked",
                    )
                } else
                IconButton(onClick = actions.onToggleFavorite, enabled = song != null) {
                    Icon(
                        if (state.isCurrentFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (state.isCurrentFavorite) "Remove from favorites" else "Add to favorites",
                        tint = if (state.isCurrentFavorite) scheme.accent else scheme.onBackground,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (controlDeck) {
                com.example.mimic.ui.player.ControlDeckPanel(
                    state, actions.onSeek, actions.onPrevious, actions.onPlayPause,
                    actions.onNext, actions.onShuffle, actions.onRepeat,
                    showShuffleRepeat = true, volumeMode = volumeMode, eqEnabled = eqEnabled,
                    onEqEnabledChange = onEqEnabledChange, hapticSeek = hapticSeek,
                ) {
                    com.example.mimic.ui.radio.LiveRow(state, actions.radioFlag, scheme.onBackground, scheme.muted, scheme.accent, actions.onReconnect)
                }
            } else {
            // Black & Orange (4.8.0-beta.2): the artwork backdrop stays (that is the Dynamic
            // player), but seek + transport sit on the theme's matte equipment console.
            val equipment = com.example.mimic.ui.designsystem.LocalDepth.current.equipment
            Column(
                if (equipment) {
                    Modifier.fillMaxWidth()
                        .equipmentConsole()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                } else {
                    Modifier.fillMaxWidth()
                },
            ) {
            if (state.isLive) com.example.mimic.ui.radio.LiveRow(state, actions.radioFlag, scheme.onBackground, scheme.muted, scheme.accent, actions.onReconnect)
            else PremiumSeekBar(state, scheme, glow = config.intensity != com.example.mimic.data.DynamicIntensity.SUBTLE, onSeek = actions.onSeek)
            Spacer(Modifier.height(8.dp))

            // Transport
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (equipment) {
                    com.example.mimic.ui.components.DepthIconButton(
                        Icons.Default.Shuffle, "Shuffle", actions.onShuffle, size = 44.dp, iconSize = 20.dp,
                        selected = state.shuffle, stateDescription = if (state.shuffle) "On" else "Off",
                    )
                    com.example.mimic.ui.components.DepthIconButton(Icons.Default.SkipPrevious, "Previous", actions.onPrevious, size = 52.dp, iconSize = 34.dp, bare = true)
                    com.example.mimic.ui.components.DepthIconButton(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (state.isPlaying) "Pause" else "Play", actions.onPlayPause, size = 76.dp, iconSize = 38.dp, accent = true,
                    )
                    com.example.mimic.ui.components.DepthIconButton(Icons.Default.SkipNext, "Next", actions.onNext, size = 52.dp, iconSize = 34.dp, bare = true)
                    com.example.mimic.ui.components.DepthIconButton(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        "Repeat", actions.onRepeat, size = 44.dp, iconSize = 20.dp,
                        selected = state.repeatMode != Player.REPEAT_MODE_OFF,
                        stateDescription = when (state.repeatMode) { Player.REPEAT_MODE_ONE -> "Repeat one"; Player.REPEAT_MODE_ALL -> "Repeat all"; else -> "Off" },
                    )
                } else {
                IconButton(onClick = actions.onShuffle) {
                    Icon(Icons.Default.Shuffle, "Shuffle ${if (state.shuffle) "on" else "off"}", tint = if (state.shuffle) scheme.accent else scheme.muted)
                }
                RoundAction(Icons.Default.SkipPrevious, "Previous", scheme, config.glassControls, actions.onPrevious, size = 56)
                PlayButton(state.isPlaying, scheme, actions.onPlayPause)
                RoundAction(Icons.Default.SkipNext, "Next", scheme, config.glassControls, actions.onNext, size = 56)
                IconButton(onClick = actions.onRepeat) {
                    Icon(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        contentDescription = when (state.repeatMode) { Player.REPEAT_MODE_ONE -> "Repeat one"; Player.REPEAT_MODE_ALL -> "Repeat all"; else -> "Repeat off" },
                        tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) scheme.accent else scheme.muted,
                    )
                }
                }
            }
            }
            }
            Spacer(Modifier.height(16.dp))

            // Actions: Lyrics · Queue · Cast · Share · More
            var menu by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .then(if (config.glassControls && !matteAmber) Modifier.background(scheme.onBackground.copy(alpha = 0.08f)).border(1.dp, scheme.onBackground.copy(alpha = 0.12f), RoundedCornerShape(28.dp)) else Modifier)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (matteAmber) RoundAction(Icons.Default.Lyrics, "Lyrics", scheme, false, { lyricsOpen = true }) else IconButton(onClick = { lyricsOpen = true }) { Icon(Icons.Default.Lyrics, "Lyrics", tint = scheme.onBackground) }
                if (matteAmber) RoundAction(Icons.AutoMirrored.Filled.QueueMusic, "Queue", scheme, false, actions.onOpenQueue) else IconButton(onClick = actions.onOpenQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue", tint = scheme.onBackground) }
                castButton(scheme.onBackground)
                if (matteAmber) RoundAction(Icons.Default.Share, "Share song", scheme, false, actions.onShare)
                else IconButton(onClick = actions.onShare, enabled = song != null) { Icon(Icons.Default.Share, "Share song", tint = scheme.onBackground) }
                Box {
                    if (matteAmber) RoundAction(Icons.Default.MoreVert, "More options", scheme, false, { menu = true }) else IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options", tint = scheme.onBackground) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (sleepActive) "Sleep timer (on)" else "Sleep timer") },
                            leadingIcon = { Icon(Icons.Default.Bedtime, null) },
                            onClick = { menu = false; actions.onOpenSleep() },
                        )
                        DropdownMenuItem(
                            text = { Text("Playback speed (${com.example.mimic.ui.player.formatSpeed(speed)})") },
                            leadingIcon = { Icon(Icons.Default.Speed, null) },
                            onClick = { menu = false; actions.onOpenSpeed() },
                        )
                        DropdownMenuItem(text = { Text("Lock-screen style view") }, leadingIcon = { Icon(Icons.Default.Lock, null) }, onClick = { menu = false; actions.onOpenLockView() })
                        DropdownMenuItem(text = { Text("Dynamic player settings") }, leadingIcon = { Icon(Icons.Default.Tune, null) }, onClick = { menu = false; actions.onOpenSettings() })
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun DynamicArtwork(song: Song?, config: DynamicPlayerConfig, modifier: Modifier) {
    val shape = RoundedCornerShape(config.artShape.cornerDp.dp)
    Box(
        modifier.graphicsLayer { shadowElevation = 28f; this.shape = shape; clip = true }
            .background(Color.White.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(72.dp))
        if (song?.albumArtUri != null) {
            AsyncImage(model = song.albumArtUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun RoundAction(icon: ImageVector, label: String, scheme: AnimatedScheme, glass: Boolean, onClick: () -> Unit, size: Int = 44) {
    if (com.example.mimic.ui.designsystem.LocalDepth.current.let { it.equipment || it.matteAmber }) {
        com.example.mimic.ui.components.DepthIconButton(icon, label, onClick, size = maxOf(size, 44).dp, iconSize = (size * 0.5f).dp)
        return
    }
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .then(if (glass) Modifier.background(scheme.onBackground.copy(alpha = 0.12f)).border(1.dp, scheme.onBackground.copy(alpha = 0.16f), CircleShape) else Modifier)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = scheme.onBackground, modifier = Modifier.size((size * 0.5f).dp)) }
}

@Composable
private fun PlayButton(playing: Boolean, scheme: AnimatedScheme, onClick: () -> Unit) {
    Box(
        Modifier.size(76.dp).clip(CircleShape).background(scheme.onBackground).clickable(onClick = onClick)
            .semantics { contentDescription = if (playing) "Pause" else "Play" },
        contentAlignment = Alignment.Center,
    ) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = scheme.bottom, modifier = Modifier.size(40.dp)) }
}

/**
 * Premium timeline: rounded track, buffered range, artwork-colored progress with optional glow,
 * draggable thumb. Progress is Media3's real position (updated twice a second), animated linearly
 * between samples only while playing — never invented. Seeking happens on release; unknown
 * durations (live/unknown) show "--:--" and can't be dragged.
 */
@Composable
fun PremiumSeekBar(state: PlayerUiState, scheme: AnimatedScheme, glow: Boolean, onSeek: (Long) -> Unit) {
    val duration = state.durationMs
    val known = duration > 0
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var lastSongId by remember { mutableLongStateOf(-1L) }
    val songId = state.currentSong?.id ?: -1L
    val real = if (known) (state.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    // Snap on song change / pause; glide between the twice-a-second samples while playing.
    val snapNow = songId != lastSongId || !state.isPlaying
    val shown by animateFloatAsState(real, if (snapNow) snap() else tween(500, easing = LinearEasing), label = "seek")
    LaunchedEffect(songId) { lastSongId = songId }
    val fraction = dragFraction ?: shown
    val buffered = if (known) (state.bufferedPositionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val eq = com.example.mimic.ui.designsystem.LocalDepth.current

    Column(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().height(28.dp)
                .pointerInput(known, duration) {
                    if (!known) return@pointerInput
                    detectTapGestures { o -> onSeek((o.x / size.width * duration).toLong().coerceIn(0, duration)) }
                }
                .pointerInput(known, duration) {
                    if (!known) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { dragFraction?.let { onSeek((it * duration).toLong()) }; dragFraction = null },
                        onDragCancel = { dragFraction = null },
                        onHorizontalDrag = { change, dx -> change.consume(); dragFraction = ((dragFraction ?: shown) + dx / size.width).coerceIn(0f, 1f) },
                    )
                }
                .semantics { contentDescription = "Seek bar, ${formatDuration(state.positionMs)} of ${if (known) formatDuration(duration) else "unknown"}" },
        ) {
            if (eq.equipment) {
                drawEquipmentChannel(fraction, buffered, known, dragFraction != null, eq)
                return@Canvas
            }
            val h = if (dragFraction != null) 8.dp.toPx() else 5.dp.toPx()
            val y = size.height / 2 - h / 2
            val r = CornerRadius(h / 2, h / 2)
            drawRoundRect(scheme.onBackground.copy(alpha = 0.18f), Offset(0f, y), Size(size.width, h), r)
            if (buffered > 0f) drawRoundRect(scheme.onBackground.copy(alpha = 0.28f), Offset(0f, y), Size(size.width * buffered, h), r)
            val w = size.width * fraction
            if (glow && w > 0f) drawRoundRect(scheme.accent.copy(alpha = 0.35f), Offset(0f, y - h), Size(w, h * 3), CornerRadius(h * 1.5f, h * 1.5f))
            drawRoundRect(scheme.accent, Offset(0f, y), Size(w, h), r)
            val thumb = if (dragFraction != null) 11.dp.toPx() else 8.dp.toPx()
            if (known) drawCircle(scheme.onBackground, thumb, Offset(w.coerceIn(thumb, size.width - thumb), size.height / 2))
        }
        Row(Modifier.fillMaxWidth()) {
            val pos = dragFraction?.let { (it * duration).toLong() } ?: state.positionMs
            Text(formatDuration(pos), style = MaterialTheme.typography.labelMedium, color = scheme.muted)
            Spacer(Modifier.weight(1f))
            if (state.isBuffering) Text("Buffering  ", style = MaterialTheme.typography.labelMedium, color = scheme.muted)
            Text(if (known) formatDuration(duration) else "--:--", style = MaterialTheme.typography.labelMedium, color = scheme.muted)
        }
    }
}

/**
 * Full-screen lyrics on the song's own colors — the same lyrics, states and actions as the lyrics
 * screen (4.5: saved → device → Navidrome → LRCLIB, Retry / Add / Choose), drawn over the Dynamic
 * background. Synced lines follow playback; tapping one seeks there.
 */
@Composable
fun DynamicLyrics(vm: PlayerViewModel, ui: PlayerUiState, scheme: AnimatedScheme, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        com.example.mimic.ui.player.LyricsContent(
            vm, ui,
            com.example.mimic.ui.player.LyricsColors(background = scheme.bottom, onBackground = scheme.onBackground, muted = scheme.muted, accent = scheme.accent),
            onClose = onClose,
            modifier = Modifier.weight(1f),
        )
    }
}


/** The Black & Orange console under Dynamic's seek + transport: a raised matte panel (4.8.0-beta.2). */
@Composable
private fun Modifier.equipmentConsole(): Modifier =
    this.depthPanel(LocalGlass.current.style.surfaceShape(1.1f), elevation = 8.dp)

/**
 * Black & Orange seek channel for the Dynamic player: a recessed near-black groove with a lit lower
 * lip, the played part glowing red-orange to orange, and a small dark dome thumb with an orange LED.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEquipmentChannel(
    fraction: Float,
    buffered: Float,
    known: Boolean,
    dragging: Boolean,
    d: com.example.mimic.ui.designsystem.DepthPalette,
) {
    val h = (if (dragging) 7.dp else 5.dp).toPx()
    val y = size.height / 2 - h / 2
    val r = CornerRadius(h / 2, h / 2)
    val hot = d.c(d.glowHot)
    val mid = d.c(d.glow)
    val w = size.width * fraction
    // Glow spilling over the groove (behind it).
    if (w > 0f) {
        for ((grow, a) in listOf(3.2f to 0.10f, 1.9f to 0.20f)) {
            val gh = h * grow
            drawRoundRect(Brush.horizontalGradient(listOf(hot.copy(alpha = a), mid.copy(alpha = a)), endX = w), Offset(-(gh - h) / 2f, size.height / 2 - gh / 2), Size(w + (gh - h), gh), CornerRadius(gh / 2, gh / 2))
        }
    }
    // Groove + lit lower lip + shaded top edge.
    drawRoundRect(d.c(d.groove), Offset(0f, y), Size(size.width, h), r)
    drawRoundRect(Color.Black.copy(alpha = 0.6f), Offset(0f, y), Size(size.width, h * 0.45f), r)
    drawLine(d.c(d.rimLight).copy(alpha = 0.12f), Offset(h / 2, y + h + 0.8.dp.toPx()), Offset(size.width - h / 2, y + h + 0.8.dp.toPx()), strokeWidth = 1.dp.toPx())
    if (buffered > 0f) drawRoundRect(Color.White.copy(alpha = 0.06f), Offset(0f, y), Size(size.width * buffered, h), r)
    if (w > 0f) drawRoundRect(Brush.horizontalGradient(listOf(hot, mid), endX = w.coerceAtLeast(h)), Offset(0f, y), Size(w.coerceAtLeast(h), h), r)
    if (known) {
        val tr = (if (dragging) 12.dp else 10.dp).toPx()
        val cx = w.coerceIn(tr, size.width - tr)
        val c = Offset(cx, size.height / 2)
        drawCircle(Color.Black.copy(alpha = 0.55f), tr, c + Offset(0.5.dp.toPx(), 2.dp.toPx()))
        drawCircle(Brush.linearGradient(listOf(d.c(d.metalTop), d.c(d.metalBottom)), start = c - Offset(tr, tr), end = c + Offset(tr, tr)), tr, c)
        drawCircle(d.c(d.ctrlRimLight), tr, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        drawCircle(mid.copy(alpha = 0.35f), tr * 0.55f, c)
        drawCircle(mid, tr * 0.3f, c)
    }
}
