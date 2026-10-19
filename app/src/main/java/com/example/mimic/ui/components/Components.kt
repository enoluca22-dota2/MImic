package com.example.mimic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.SkipNext
import coil.compose.AsyncImage
import com.example.mimic.data.MetadataFallback
import com.example.mimic.data.Song
import com.example.mimic.data.SongSource
import com.example.mimic.ui.designsystem.LocalGlass
import com.example.mimic.ui.designsystem.glassSurface
import com.example.mimic.ui.designsystem.surfaceShape
import java.util.Locale

/**
 * Square album artwork with a placeholder note icon when none is available. [shape] overrides
 * [corner] (used for squircle artwork). The image model is the song's stable art URI, so Coil's
 * cache key never changes between recompositions (2.8.1 fix).
 */
@Composable
fun AlbumArt(song: Song?, modifier: Modifier = Modifier, corner: Int = 6, shape: Shape? = null) =
    ArtImage(song?.albumArtUri, modifier, shape ?: RoundedCornerShape(corner.dp))

/** Artwork from a stable URI string (album/artist cards). */
@Composable
fun ArtImage(uri: String?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(6.dp)) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        // The note is always drawn underneath: when the artwork is missing or fails to load
        // (e.g. a MediaStore album with no embedded art) the tile still shows a placeholder
        // instead of an empty square.
        Icon(
            Icons.Default.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().clip(shape),
            )
        }
    }
}

/**
 * The one song row used by every list (MImic 3.0): 52dp artwork, a prominent title that wraps to
 * two lines instead of being cut off, "artist · album" underneath with a source indicator
 * (downloaded / streamed from server / file missing), duration, a tappable heart and the overflow
 * menu. Nothing overlaps the title; all targets are >= 48dp; text follows the system font scale.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isFavorite: Boolean = false,
    onMenu: (() -> Unit)? = null,
    onToggleFavorite: (() -> Unit)? = null,
    isDownloading: Boolean = false,
    isCurrent: Boolean = false,
    showAlbum: Boolean = true,
    /** Long-press (3.2: starts multi-select in Library). */
    onLongClick: (() -> Unit)? = null,
    /** Non-null while multi-selecting: whether this row is selected. */
    selected: Boolean? = null,
) {
    // Rows stay flat (no glass) by design: glass is reserved for a few large surfaces.
    val env = LocalGlass.current
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .then(if (selected == true) Modifier.background(scheme.primary.copy(alpha = 0.16f)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AlbumArt(song, modifier = Modifier.size(52.dp), corner = (env.style.cornerRadius / 3).coerceAtLeast(4))
            if (selected != null) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape((env.style.cornerRadius / 3).coerceAtLeast(4).dp))
                        .background(if (selected) scheme.primary.copy(alpha = 0.75f) else scheme.scrim.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = if (selected) "Selected" else "Not selected",
                        tint = if (selected) scheme.onPrimary else androidx.compose.ui.graphics.Color.White,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge,
                // Titles are never lighter than Medium, whatever the theme asks, for legibility.
                fontWeight = FontWeight(env.style.titleWeight.coerceAtLeast(500)),
                color = if (isCurrent) scheme.primary else scheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceIndicator(song, isDownloading)
                Text(
                    if (showAlbum && song.album != MetadataFallback.UNKNOWN_ALBUM) "${song.artist} · ${song.album}" else song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = env.artistFontWeight,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            formatDuration(song.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        if (onToggleFavorite != null) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (isFavorite) scheme.primary else scheme.onSurfaceVariant,
                )
            }
        } else if (isFavorite) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Favorite, contentDescription = "Favorite", tint = scheme.primary, modifier = Modifier.size(18.dp))
        }
        if (onMenu != null) {
            IconButton(onClick = onMenu) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = scheme.onSurfaceVariant)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

/** Small leading icon on a row's subtitle: downloaded, downloading, streamed, or missing file. */
@Composable
fun SourceIndicator(song: Song, isDownloading: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val mod = Modifier.padding(end = 4.dp).size(15.dp)
    when {
        isDownloading -> androidx.compose.material3.CircularProgressIndicator(modifier = mod, strokeWidth = 1.5.dp)
        song.fileMissing -> Icon(Icons.Default.ErrorOutline, contentDescription = "Download missing", tint = scheme.error, modifier = mod)
        song.isDownloaded -> Icon(Icons.Default.DownloadDone, contentDescription = "Downloaded", tint = scheme.primary, modifier = mod)
        song.source == SongSource.SERVER -> Icon(Icons.Default.CloudQueue, contentDescription = "On server", tint = scheme.onSurfaceVariant, modifier = mod)
        song.source == SongSource.CLOUD -> Icon(Icons.Default.Cloud, contentDescription = "In ${song.provider.label}", tint = scheme.onSurfaceVariant, modifier = mod)
        else -> Unit
    }
}

/**
 * The persistent now-playing bar shown above the bottom navigation. On glass themes it becomes a
 * floating frosted capsule (backdrop blur of the list scrolling beneath it); otherwise the
 * original flat bar. Same API and the same click targets either way.
 */
@Composable
fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isBuffering: Boolean = false,
    error: String? = null,
    /** 4.1: skip to the next song (shown when there's room). */
    onNext: (() -> Unit)? = null,
    /** 4.1: real playback progress 0..1 (Media3 position / duration), or null when unknown. */
    progress: Float? = null,
    /** 4.1: "Casting to …" while a Cast session is active. */
    castingTo: String? = null,
    /** 4.2: "LIVE · on air now" for radio. */
    liveTitle: String? = null,
    /** 4.1: the Cast button. */
    castButton: (@Composable () -> Unit)? = null,
) {
    val env = LocalGlass.current
    val glass = env.enabled
    val shape = if (glass) env.style.surfaceShape(0.9f) else RoundedCornerShape((env.style.cornerRadius * 0.45f).dp)
    androidx.compose.foundation.layout.Box(modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (glass) 64.dp else 60.dp)
            .glassSurface(shape, fallback = MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = if (glass) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(
            song,
            modifier = Modifier.size(44.dp),
            shape = if (glass) env.style.surfaceShape(0.45f) else null,
        )
        Spacer(Modifier.width(12.dp))
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = env.titleFontWeight,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Error takes priority, then a live "Buffering…" hint, else the artist.
            val (subtitle, subtitleColor) = when {
                error != null -> error to MaterialTheme.colorScheme.error
                castingTo != null -> "Casting to $castingTo" to MaterialTheme.colorScheme.primary
                liveTitle != null && !isBuffering -> liveTitle to MaterialTheme.colorScheme.error
                isBuffering -> "Buffering…" to MaterialTheme.colorScheme.onSurfaceVariant
                else -> song.artist to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isBuffering && error == null) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(24.dp).padding(end = 4.dp),
                strokeWidth = 2.dp,
            )
        }
        castButton?.invoke()
        IconButton(onClick = onPlayPause) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        if (onNext != null) {
            IconButton(onClick = onNext) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next song", tint = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
    if (progress != null) {
        // A 2 dp line along the bottom edge; it only moves with real playback.
        androidx.compose.material3.LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 18.dp).height(2.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.Transparent,
            drawStopIndicator = {},
        )
    }
    }
}

fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format(Locale.US, "%d:%02d", m, s)
}
