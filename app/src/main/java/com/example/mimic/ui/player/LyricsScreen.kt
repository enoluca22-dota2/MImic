package com.example.mimic.ui.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mimic.data.audio.LrcLib
import com.example.mimic.data.lyrics.LyricsCandidate
import com.example.mimic.data.lyrics.LyricsRepository
import com.example.mimic.player.LyricsCandidatesState
import com.example.mimic.player.LyricsState
import com.example.mimic.player.PlayerUiState
import com.example.mimic.player.PlayerViewModel

/** Colours the lyrics are drawn in — the song's own (artwork-adaptive) or the Dynamic player's. */
data class LyricsColors(val background: Color, val onBackground: Color, val muted: Color, val accent: Color)

/**
 * Full-screen lyrics (MImic 4.5; replaces the 4.4 bottom sheet). Colours follow the artwork,
 * darkened on a dark theme and lightened on a light one, so text always stays readable; the
 * transport and the position bar sit at the bottom.
 */
@Composable
fun LyricsSheet(vm: PlayerViewModel, onDismiss: () -> Unit) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val dark = isSystemInDarkTheme() || MaterialTheme.colorScheme.background.luminance() < 0.4f
    val context = LocalContext.current
    val song = ui.currentSong
    val seed by produceState<Int?>(null, song?.id) {
        value = song?.let { runCatching { com.example.mimic.data.AdaptivePalette.seedFromSong(context, it) }.getOrNull() }
    }
    val base = MaterialTheme.colorScheme
    val tint = seed?.let { Color(it) } ?: base.primary
    val bg by animateColorAsState(if (dark) lerp(Color.Black, tint, 0.32f) else lerp(Color.White, tint, 0.16f), tween(600), label = "lyricsBg")
    val colors = LyricsColors(
        background = bg,
        onBackground = if (dark) Color.White else Color(0xFF121212),
        muted = if (dark) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.55f),
        accent = if (dark) lerp(tint, Color.White, 0.45f) else lerp(tint, Color.Black, 0.35f),
    )
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(colors.background)) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                LyricsContent(vm, ui, colors, onClose = onDismiss, modifier = Modifier.weight(1f))
                LyricsTransport(vm, ui, colors)
            }
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** Header, every lyrics state, and the lyrics menu. Shared by the full screen and the Dynamic player. */
@Composable
fun LyricsContent(vm: PlayerViewModel, ui: PlayerUiState, colors: LyricsColors, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by vm.lyrics.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importLyrics) }
    var menu by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close lyrics", tint = colors.onBackground) }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(ui.currentSong?.title.orEmpty(), color = colors.onBackground, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ui.currentSong?.artist.orEmpty(), color = colors.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Lyrics options", tint = colors.onBackground) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Choose lyrics…") }, onClick = { menu = false; choosing = true; vm.searchLyricsCandidates() })
                    DropdownMenuItem(text = { Text("Add lyrics from a file (.lrc / .txt)") }, onClick = { menu = false; picker.launch(arrayOf("*/*")) })
                    DropdownMenuItem(text = { Text("Type or paste lyrics") }, onClick = { menu = false; typing = true })
                    DropdownMenuItem(text = { Text("Search again") }, onClick = { menu = false; vm.forgetLyrics() })
                    val found = state as? LyricsState.Found
                    if (found?.lyrics?.source == LyricsRepository.SOURCE_USER_FILE) {
                        DropdownMenuItem(text = { Text("Remove the added .lrc file") }, onClick = { menu = false; vm.removeImportedLyrics() })
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val s = state) {
                LyricsState.Loading -> Busy(colors, null)
                LyricsState.Searching -> Busy(colors, "Searching for lyrics…")
                is LyricsState.Found -> FoundLyrics(vm, ui, s, colors)
                is LyricsState.Choose -> CandidateList(
                    title = "Which lyrics belong to this song?",
                    subtitle = "Several songs have this name. Pick the one that matches — MImic remembers it.",
                    candidates = s.candidates, colors = colors, onPick = vm::chooseLyrics,
                )
                is LyricsState.None -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(s.reason, color = colors.muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                    if (ui.currentSong != null && s.reason != "Nothing playing" && ui.isLive.not()) {
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = vm::retryLyrics, colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.background)) { Text("Retry") }
                            OutlinedButton(onClick = { typing = true }) { Text("Add lyrics", color = colors.onBackground) }
                        }
                        TextButton(onClick = { choosing = true; vm.searchLyricsCandidates() }) { Text("Search with another title…", color = colors.onBackground) }
                    }
                }
            }
        }
    }
    if (typing) TypeLyricsDialog(onDismiss = { typing = false }, onSave = { vm.saveTypedLyrics(it); typing = false })
    if (choosing) ChooseLyricsDialog(vm, ui, colors, onDismiss = { choosing = false; vm.clearLyricsCandidates() })
}

@Composable
private fun Busy(colors: LyricsColors, label: String?) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = colors.accent)
        if (label != null) {
            Spacer(Modifier.height(12.dp))
            Text(label, color = colors.muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun FoundLyrics(vm: PlayerViewModel, ui: PlayerUiState, s: LyricsState.Found, colors: LyricsColors) {
    val lyrics = s.lyrics
    if (!lyrics.synced) {
        // Plain lyrics: readable text, never highlighted as if timed.
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 16.dp)) {
                lyrics.lines.forEach { line ->
                    Text(line.text, color = colors.onBackground, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 6.dp))
                }
            }
            Attribution(colors, "Plain lyrics (not synced) · ${sourceLabel(lyrics.source)}")
        }
        return
    }
    val active = lyrics.activeIndex(ui.positionMs)
    val list = rememberLazyListState()
    var userScrolledAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(list.isScrollInProgress) { if (list.isScrollInProgress) userScrolledAt = System.currentTimeMillis() }
    LaunchedEffect(active) {
        // Follow the song, keeping the current line in the upper third — unless the user scrolled
        // in the last 4 seconds (they're reading ahead or back).
        if (active >= 0 && System.currentTimeMillis() - userScrolledAt > 4000) list.animateScrollToItem((active - 2).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(state = list, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 24.dp), modifier = Modifier.weight(1f)) {
            itemsIndexed(lyrics.lines) { i, line ->
                val isActive = i == active
                val alpha by animateFloatAsState(
                    when {
                        isActive -> 1f
                        i < active -> 0.35f
                        else -> 0.55f
                    },
                    tween(250), label = "lyricAlpha",
                )
                Text(
                    line.text.ifBlank { "♪" },
                    style = if (isActive) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
                    fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold,
                    color = (if (isActive) colors.onBackground else colors.onBackground).copy(alpha = alpha),
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .then(if (line.timeMs != null) Modifier.clickable { vm.seekTo(line.timeMs) } else Modifier)
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                )
            }
        }
        Attribution(colors, "Synced · tap a line to jump · ${sourceLabel(lyrics.source)}")
    }
}

private fun sourceLabel(source: String): String =
    if (source == LrcLib.NAME) "Lyrics from LRCLIB (lrclib.net)" else source

@Composable
private fun Attribution(colors: LyricsColors, text: String) {
    Text(text, color = colors.muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 28.dp, vertical = 10.dp))
}

@Composable
private fun CandidateList(title: String, subtitle: String, candidates: List<LyricsCandidate>, colors: LyricsColors, onPick: (LyricsCandidate) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
        item {
            Text(title, color = colors.onBackground, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, color = colors.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        }
        items(candidates) { c -> CandidateRow(c, colors) { onPick(c) } }
    }
}

@Composable
private fun CandidateRow(c: LyricsCandidate, colors: LyricsColors, onPick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp))
            .background(colors.onBackground.copy(alpha = 0.08f)).clickable(onClick = onPick).padding(14.dp),
    ) {
        Text(c.title, color = colors.onBackground, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val details = listOfNotNull(
            c.artist.takeIf { it.isNotBlank() },
            c.album.takeIf { it.isNotBlank() },
            c.durationMs.takeIf { it > 0 }?.let { "%d:%02d".format(it / 60_000, (it / 1000) % 60) },
            if (!c.synced.isNullOrBlank()) "synced" else "plain",
        ).joinToString(" · ")
        Text(details, color = colors.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val preview = (c.plain ?: c.synced?.replace(Regex("""\[[^]]*]"""), ""))?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(2)?.joinToString(" / ")
        if (!preview.isNullOrBlank()) Text("“$preview”", color = colors.muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ChooseLyricsDialog(vm: PlayerViewModel, ui: PlayerUiState, colors: LyricsColors, onDismiss: () -> Unit) {
    val state by vm.lyricsCandidates.collectAsStateWithLifecycle()
    var title by remember { mutableStateOf(ui.currentSong?.title.orEmpty()) }
    var artist by remember { mutableStateOf(ui.currentSong?.artist.orEmpty()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(colors.background).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close", tint = colors.onBackground) }
                Text("Choose lyrics", color = colors.onBackground, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(artist, { artist = it }, label = { Text("Artist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.searchLyricsCandidates(title, artist) }, modifier = Modifier.padding(vertical = 8.dp)) { Text("Search") }
            }
            Box(Modifier.weight(1f)) {
                when (val s = state) {
                    LyricsCandidatesState.Idle, LyricsCandidatesState.Loading -> Busy(colors, "Searching LRCLIB…")
                    is LyricsCandidatesState.Error -> Text(s.message, color = colors.muted, modifier = Modifier.padding(24.dp))
                    is LyricsCandidatesState.Results -> if (s.list.isEmpty()) {
                        Text("Nothing found. Try another spelling of the title or artist.", color = colors.muted, modifier = Modifier.padding(24.dp))
                    } else {
                        CandidateList(
                            title = "${s.list.size} results",
                            subtitle = "Best matches for this recording first. Check the length and artist.",
                            candidates = s.list, colors = colors, onPick = { vm.chooseLyrics(it); onDismiss() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeLyricsDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add lyrics") },
        text = {
            Column {
                Text("Paste or type the lyrics. Lines with [mm:ss.xx] timestamps are synced; anything else is saved as plain lyrics.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().height(260.dp).padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LyricsTransport(vm: PlayerViewModel, ui: PlayerUiState, colors: LyricsColors) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        val fraction = if (ui.durationMs > 0) (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(
            progress = { fraction }, color = colors.accent, trackColor = colors.onBackground.copy(alpha = 0.15f),
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::previous) { Icon(Icons.Default.SkipPrevious, "Previous", tint = colors.onBackground) }
            Spacer(Modifier.width(16.dp))
            IconButton(onClick = vm::togglePlayPause, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(28.dp)).background(colors.onBackground)) {
                Icon(if (ui.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (ui.isPlaying) "Pause" else "Play", tint = colors.background)
            }
            Spacer(Modifier.width(16.dp))
            IconButton(onClick = vm::next) { Icon(Icons.Default.SkipNext, "Next", tint = colors.onBackground) }
        }
    }
}
