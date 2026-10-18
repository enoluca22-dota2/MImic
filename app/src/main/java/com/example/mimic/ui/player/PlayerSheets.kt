package com.example.mimic.ui.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mimic.data.AudioPrefs
import com.example.mimic.data.ReplayGainMode
import com.example.mimic.player.LyricsState
import com.example.mimic.player.PlaybackEffects
import com.example.mimic.player.PlayerViewModel
import com.example.mimic.ui.components.SongRow
import kotlin.math.roundToInt

private fun speedLabel(speed: Float): String =
    if (speed == speed.roundToInt().toFloat()) "${speed.roundToInt()}×" else "${"%.2f".format(speed).trimEnd('0')}×"

/** "1×" / "1.25×" for the Now Playing action row. */
fun formatSpeed(speed: Float) = speedLabel(speed)

// ---------------------------------------------------------------------------------------------
// Lyrics
// ---------------------------------------------------------------------------------------------

/**
 * Lyrics for the current song: synced lines follow playback (tap one to jump there); plain
 * lyrics just scroll. Sources are the user's own files only — an added .lrc, or lyrics embedded
 * in an on-device / downloaded file. Nothing is looked up online.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSheet(vm: PlayerViewModel, onDismiss: () -> Unit) {
    val state by vm.lyrics.collectAsStateWithLifecycle()
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importLyrics) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Lyrics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(ui.currentSong?.title.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Add .lrc") }
            }
            when (val s = state) {
                LyricsState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is LyricsState.None -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(s.reason, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is LyricsState.Found -> {
                    val lyrics = s.lyrics
                    val active = lyrics.activeIndex(ui.positionMs)
                    val list = rememberLazyListState()
                    LaunchedEffect(active) { if (active >= 0) list.animateScrollToItem((active - 2).coerceAtLeast(0)) }
                    Text(
                        (if (lyrics.synced) "Synced · " else "") + lyrics.source,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                    LazyColumn(state = list, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 48.dp), modifier = Modifier.weight(1f)) {
                        itemsIndexed(lyrics.lines) { i, line ->
                            val isActive = i == active
                            Text(
                                line.text.ifBlank { "♪" },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                color = when {
                                    !lyrics.synced -> MaterialTheme.colorScheme.onSurface
                                    isActive -> MaterialTheme.colorScheme.primary
                                    i < active -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                },
                                modifier = Modifier.fillMaxWidth()
                                    .then(if (line.timeMs != null) Modifier.clickable { vm.seekTo(line.timeMs) } else Modifier)
                                    .padding(vertical = 8.dp),
                            )
                        }
                        if (lyrics.source == "Your .lrc file") {
                            item { TextButton(onClick = vm::removeImportedLyrics) { Text("Remove the added .lrc file") } }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Speed
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SpeedSheet(vm: PlayerViewModel, onDismiss: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val audio = settings.audio
    var slider by remember(audio.speed) { mutableFloatStateOf(audio.speed) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            Text("Playback speed", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(speedLabel(slider), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
            Slider(
                value = slider,
                onValueChange = { slider = (it * 20).roundToInt() / 20f },
                onValueChangeFinished = { vm.setSpeed(slider) },
                valueRange = 0.5f..2f,
                steps = 29,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AudioPrefs.SPEEDS.forEach { sp ->
                    FilterChip(selected = audio.speed == sp, onClick = { vm.setSpeed(sp) }, label = { Text(speedLabel(sp)) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep original pitch")
                    Text("Off = faster sounds higher, like speeding up a tape", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = audio.keepPitch, onCheckedChange = { vm.setAudio(audio.copy(keepPitch = it)) })
            }
            Text("Applies to every song until you change it back.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sleep timer
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(vm: PlayerViewModel, onDismiss: () -> Unit) {
    val endsAt by vm.sleepEndsAt.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(15_000) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("Sleep timer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp))
            val status = when {
                endsAt == -1L -> "Stops when this song ends"
                endsAt > now -> {
                    val mins = ((endsAt - now) / 60_000L).coerceAtLeast(1)
                    val at = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(endsAt))
                    "Stops at $at (in about $mins min) · fades out over the last 20 s"
                }
                else -> "Off — music fades out gently, then pauses"
            }
            Text(status, style = MaterialTheme.typography.bodyMedium, color = if (endsAt != 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
            listOf(5, 15, 30, 45, 60, 90).forEach { m ->
                SheetRow("$m minutes") { vm.startSleepTimer(m); onDismiss() }
            }
            SheetRow("End of this song") { vm.startSleepTimer(PlaybackEffects.SLEEP_END_OF_SONG); onDismiss() }
            if (endsAt != 0L) SheetRow("Turn off", MaterialTheme.colorScheme.error) { vm.cancelSleepTimer(); onDismiss() }
        }
    }
}

@Composable
private fun SheetRow(label: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
    )
}

// ---------------------------------------------------------------------------------------------
// Queue
// ---------------------------------------------------------------------------------------------

/**
 * The play queue: the current song, then Up Next. Drag a row's handle to reorder (one move is
 * sent to the player when the finger lifts, so playback never stutters mid-drag), ✕ removes,
 * Clear empties Up Next after a confirmation, and the whole queue can be saved as a playlist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(vm: PlayerViewModel, onDismiss: () -> Unit) {
    val queue by vm.queue.collectAsStateWithLifecycle()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val currentIndex by vm.currentIndex.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var saveAs by remember { mutableStateOf(false) }

    // Local display order while dragging: positions -> original queue indices.
    var order by remember(queue) { mutableStateOf(queue.indices.toList()) }
    var dragPos by remember { mutableStateOf<Int?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowPx by remember { mutableFloatStateOf(0f) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Queue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { saveAs = true }, enabled = queue.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Save queue as playlist")
                }
                IconButton(onClick = { confirmClear = true }, enabled = currentIndex < queue.size - 1) {
                    Icon(Icons.Default.ClearAll, contentDescription = "Clear up next")
                }
            }
            if (queue.isEmpty()) {
                Text("Nothing queued. Use a song's ⋮ menu → Add to queue or Play next.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(order, key = { pos, _ -> "q$pos" }) { pos, original ->
                    val song = queue.getOrNull(original) ?: return@itemsIndexed
                    val isCurrent = original == currentIndex
                    if (isCurrent) QueueHeader("Now playing")
                    if (pos == currentIndex + 1) QueueHeader("Up next · ${queue.size - currentIndex - 1}")
                    val dragging = dragPos == pos
                    Row(
                        Modifier.fillMaxWidth()
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = if (dragging) dragOffset else 0f; shadowElevation = if (dragging) 12f else 0f }
                            .background(
                                when {
                                    dragging -> MaterialTheme.colorScheme.surfaceVariant
                                    isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else -> androidx.compose.ui.graphics.Color.Transparent
                                },
                            )
                            .onSizeChanged { if (rowPx == 0f) rowPx = it.height.toFloat() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            SongRow(song = song, onClick = { vm.playQueueItem(original) }, isCurrent = isCurrent, showAlbum = false)
                        }
                        IconButton(onClick = { vm.removeFromQueue(original) }, enabled = !isCurrent) {
                            Icon(Icons.Default.Close, contentDescription = "Remove ${song.title} from queue")
                        }
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = "Drag to reorder ${song.title}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(32.dp)
                                .pointerInput(queue) {
                                    detectVerticalDragGestures(
                                        onDragStart = { dragPos = pos; dragStartIndex = order[pos]; dragOffset = 0f },
                                        onVerticalDrag = { change, dy ->
                                            change.consume()
                                            val cur = dragPos ?: return@detectVerticalDragGestures
                                            dragOffset += dy
                                            val step = rowPx.takeIf { it > 0f } ?: return@detectVerticalDragGestures
                                            val target = when {
                                                dragOffset > step / 2 && cur < order.size - 1 -> cur + 1
                                                dragOffset < -step / 2 && cur > 0 -> cur - 1
                                                else -> cur
                                            }
                                            if (target != cur) {
                                                order = order.toMutableList().apply { add(target, removeAt(cur)) }
                                                dragOffset -= step * (target - cur)
                                                dragPos = target
                                            }
                                        },
                                        onDragEnd = {
                                            val to = dragPos
                                            val from = dragStartIndex
                                            dragPos = null
                                            dragOffset = 0f
                                            if (to != null && from >= 0 && from != to) vm.moveInQueue(from, to)
                                        },
                                        onDragCancel = { dragPos = null; dragOffset = 0f; order = queue.indices.toList() },
                                    )
                                },
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear Up Next?") },
            text = { Text("Removes the ${queue.size - currentIndex - 1} songs after the one playing now. The current song keeps playing.") },
            confirmButton = { TextButton(onClick = { vm.clearUpcoming(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
    if (saveAs) {
        var name by remember { mutableStateOf("Queue " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date())) }
        AlertDialog(
            onDismissRequest = { saveAs = false },
            title = { Text("Save queue as playlist") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Playlist name") }) },
            confirmButton = {
                TextButton(onClick = { vm.saveQueueAsPlaylist(name.trim()); saveAs = false }, enabled = name.isNotBlank()) { Text("Save ${queue.size} songs") }
            },
            dismissButton = { TextButton(onClick = { saveAs = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun QueueHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
    )
}

// ---------------------------------------------------------------------------------------------
// Settings → Playback & audio
// ---------------------------------------------------------------------------------------------

@Composable
fun PlaybackSettingsScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val a = settings.audio
    val eq by produceState<com.example.mimic.player.EqualizerInfo?>(null, settings.audio.eqEnabled) { value = vm.equalizerInfo() }
    var eqChecked by remember { mutableStateOf(false) }
    LaunchedEffect(eq) { eqChecked = true }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        com.example.mimic.ui.library.DetailTopBar("Playback & audio", onBack)

        Section("Transitions")
        SettingSwitch("Gapless playback", "Always on: albums mixed without gaps play seamlessly when the file carries gapless info (MP3 LAME/AAC). Streams behave like files.", true, enabled = false) {}
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(if (a.crossfadeSec == 0) "Crossfade: off" else "Crossfade: ${a.crossfadeSec} s", style = MaterialTheme.typography.bodyLarge)
            Text("Fades each song out at the end and the next one in. One player, so songs never double up.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var cf by remember(a.crossfadeSec) { mutableFloatStateOf(a.crossfadeSec.toFloat()) }
            Slider(value = cf, onValueChange = { cf = it }, onValueChangeFinished = { vm.setAudio(a.copy(crossfadeSec = cf.roundToInt())) }, valueRange = 0f..AudioPrefs.MAX_CROSSFADE_SEC.toFloat(), steps = AudioPrefs.MAX_CROSSFADE_SEC - 1)
        }
        SettingSwitch("Skip silence", "Shortens long silent gaps inside tracks (audiobooks, live recordings).", a.skipSilence) { vm.setAudio(a.copy(skipSilence = it)) }

        Section("Volume leveling (ReplayGain)")
        Text(
            "Uses ReplayGain tags already in your files to even out loudness. Only turns loud songs down, never up. Works for songs on this phone and downloads; streamed songs play unchanged.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReplayGainMode.entries.forEach { m -> FilterChip(selected = a.replayGain == m, onClick = { vm.setAudio(a.copy(replayGain = m)) }, label = { Text(m.label) }) }
        }

        Section("Equalizer")
        val info = eq
        if (info == null) {
            Text(
                if (eqChecked) "This phone doesn't offer an equalizer to apps (or nothing has played yet — play a song and reopen)." else "Checking…",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            SettingSwitch("Use equalizer", "The phone's own audio effect; sound varies by device.", a.eqEnabled) { vm.setAudio(a.copy(eqEnabled = it)) }
            if (a.eqEnabled) {
                info.frequenciesHz.forEachIndexed { band, hz ->
                    val level = a.eqBands.getOrNull(band) ?: 0
                    var v by remember(level) { mutableFloatStateOf(level.toFloat()) }
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz", modifier = Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = v, onValueChange = { v = it }, modifier = Modifier.weight(1f),
                            valueRange = info.minLevel.toFloat()..info.maxLevel.toFloat(),
                            onValueChangeFinished = {
                                val bands = List(info.frequenciesHz.size) { i -> if (i == band) v.roundToInt() else a.eqBands.getOrNull(i) ?: 0 }
                                vm.setAudio(a.copy(eqBands = bands))
                            },
                        )
                        Text("%+.0f dB".format(v / 100f), modifier = Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
                    }
                }
                if (info.bassBoost) {
                    var bb by remember(a.bassBoost) { mutableFloatStateOf(a.bassBoost.toFloat()) }
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Bass boost", modifier = Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall)
                        Slider(value = bb, onValueChange = { bb = it }, onValueChangeFinished = { vm.setAudio(a.copy(bassBoost = bb.roundToInt())) }, valueRange = 0f..1000f, modifier = Modifier.weight(1f))
                    }
                }
                TextButton(onClick = { vm.setAudio(a.copy(eqBands = emptyList(), bassBoost = 0)) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Reset to flat") }
            }
        }

        Section("Headphones & controls")
        SettingSwitch("Pause when headphones disconnect", "Always on (Android's \"becoming noisy\" signal).", true, enabled = false) {}
        SettingSwitch("Resume when headphones connect", "Wired, USB or Bluetooth audio. Needs no Bluetooth permission.", a.resumeOnConnect) { vm.setAudio(a.copy(resumeOnConnect = it)) }
        SettingSwitch("Haptic feedback when seeking", "A light tick when you let go of the seek bar.", a.hapticSeek) { vm.setAudio(a.copy(hapticSeek = it)) }
        Text(
            "Bluetooth and lock-screen buttons control MImic through Android's media session; other apps taking audio focus (calls, navigation) pause or duck it automatically.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun Section(title: String) {
    Row(Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
