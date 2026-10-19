package com.example.mimic.ui.lock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.mimic.data.DynamicPlayerConfig
import com.example.mimic.data.LockStyle
import com.example.mimic.data.Song
import com.example.mimic.player.PlayerUiState
import com.example.mimic.ui.designsystem.blockTouchesBehind
import com.example.mimic.ui.dynamic.AnimatedScheme
import com.example.mimic.ui.dynamic.DynamicBackground
import com.example.mimic.ui.dynamic.rememberDynamicScheme
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * In-app "lock-screen style" player (MImic 4.1): a calm full-screen view with a large clock and
 * the current song, in four designs. It is an ordinary MImic screen — it does NOT replace or
 * bypass Android's secure lock screen (that panel, and the system media controls on it, are
 * drawn by Android from MImic's MediaSession). The screen stays on while this view is open.
 */
@Composable
fun LockStyleScreen(
    state: PlayerUiState,
    config: DynamicPlayerConfig,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCycleStyle: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    // Clock: recomposes once a minute, aligned to the minute boundary.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L)
        }
    }
    val song = state.currentSong
    val scheme = rememberDynamicScheme(song, config)
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now))
    val date = DateFormat.getDateInstance(DateFormat.FULL).format(Date(now))

    Box(Modifier.fillMaxSize().blockTouchesBehind()) {
        when (config.lockStyle) {
            LockStyle.CLASSIC -> Box(Modifier.fillMaxSize().background(Color(0xFF07070A)))
            LockStyle.DYNAMIC -> DynamicBackground(song, scheme, config)
            LockStyle.GLASS -> {
                Box(Modifier.fillMaxSize().background(scheme.bottom))
                if (song?.albumArtUri != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    AsyncImage(song.albumArtUri, null, contentScale = ContentScale.Crop, alpha = 0.7f, modifier = Modifier.fillMaxSize().blur(48.dp))
                }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            }
            LockStyle.MINIMAL -> Box(Modifier.fillMaxSize().background(Color.Black))
        }
        val fg = if (config.lockStyle == LockStyle.DYNAMIC) scheme.onBackground else Color.White
        val muted = if (config.lockStyle == LockStyle.DYNAMIC) scheme.muted else Color.White.copy(alpha = 0.7f)

        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth()) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close lock-screen view", tint = muted) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onCycleStyle) { Icon(Icons.Default.Style, "Change design (now ${config.lockStyle.label})", tint = muted) }
            }
            Text(time, color = fg, fontSize = if (config.lockStyle == LockStyle.MINIMAL) 64.sp else 84.sp, fontWeight = FontWeight.Light)
            Text(date, color = muted, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            when (config.lockStyle) {
                LockStyle.MINIMAL -> MinimalBody(song, fg, muted)
                else -> LockArtwork(song, if (config.lockStyle == LockStyle.CLASSIC) 0.82f else 0.74f, if (config.lockStyle == LockStyle.CLASSIC) 12 else 28)
            }
            Spacer(Modifier.weight(1f))
            if (config.lockStyle != LockStyle.MINIMAL) {
                Text(song?.title ?: "Nothing playing", color = fg, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(song?.artist.orEmpty(), color = muted, style = MaterialTheme.typography.titleMedium, maxLines = 1, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
            }
            val frac = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { frac },
                color = if (config.lockStyle == LockStyle.DYNAMIC) scheme.accent else fg,
                trackColor = fg.copy(alpha = 0.18f),
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.height(20.dp))
            LockControls(state.isPlaying, config.lockStyle, fg, scheme, onPrevious, onPlayPause, onNext)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun LockArtwork(song: Song?, fraction: Float, corner: Int) {
    Box(
        Modifier.fillMaxWidth(fraction).aspectRatio(1f).clip(RoundedCornerShape(corner.dp)).background(Color.White.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(64.dp))
        song?.albumArtUri?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun MinimalBody(song: Song?, fg: Color, muted: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f))) {
            song?.albumArtUri?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(song?.title ?: "Nothing playing", color = fg, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 34.sp)
            Text(song?.artist.orEmpty(), color = muted, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
}

@Composable
private fun LockControls(playing: Boolean, style: LockStyle, fg: Color, scheme: AnimatedScheme, onPrevious: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit) {
    val glass = style == LockStyle.GLASS
    Row(
        Modifier.then(
            if (glass) Modifier.clip(RoundedCornerShape(40.dp)).background(Color.White.copy(alpha = 0.14f)).border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(40.dp)).padding(horizontal = 16.dp, vertical = 6.dp)
            else Modifier,
        ),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "Previous", tint = fg, modifier = Modifier.size(34.dp)) }
        val playBg = when (style) { LockStyle.DYNAMIC -> scheme.onBackground; LockStyle.MINIMAL -> Color.Transparent; else -> fg }
        val playFg = when (style) { LockStyle.DYNAMIC -> scheme.bottom; LockStyle.MINIMAL -> fg; else -> Color.Black }
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(playBg)
                .then(if (style == LockStyle.MINIMAL) Modifier.border(2.dp, fg, CircleShape) else Modifier)
                .clickable(onClick = onPlayPause).semantics { contentDescription = if (playing) "Pause" else "Play" },
            contentAlignment = Alignment.Center,
        ) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = playFg, modifier = Modifier.size(38.dp)) }
        IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Next", tint = fg, modifier = Modifier.size(34.dp)) }
    }
}
