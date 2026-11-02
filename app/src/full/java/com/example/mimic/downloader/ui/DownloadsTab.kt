package com.example.mimic.downloader.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.mimic.downloader.core.FileActions
import com.example.mimic.downloader.core.Formatting
import com.example.mimic.downloader.data.local.datastore.NetworkPolicy
import com.example.mimic.downloader.data.local.datastore.SpeedLimit
import com.example.mimic.downloader.data.local.db.DownloadEntity
import com.example.mimic.downloader.data.local.db.HistoryEntity
import com.example.mimic.downloader.data.model.AnalysisResult
import com.example.mimic.downloader.data.model.DownloadCategory
import com.example.mimic.downloader.data.model.DownloadStatus
import com.example.mimic.downloader.data.model.FormatKind
import com.example.mimic.downloader.data.model.MediaFormat
import com.example.mimic.downloader.data.model.MediaInfo
import com.example.mimic.downloader.data.model.MixPage
import com.example.mimic.downloader.data.model.MixPreview
import com.example.mimic.downloader.data.model.PlaylistEntry
import com.example.mimic.downloader.data.model.PlaylistInfo
import com.example.mimic.downloader.data.model.QuickFormat
import com.example.mimic.downloader.data.platform.YouTubeProvider
import com.example.mimic.downloader.download.ProgressDisplay
import com.example.mimic.ui.components.LocalCompactMiniPlayer
import java.util.Locale

/** What a completed download asks MImic to do when tapped. */
fun interface DownloadedMediaOpener {
    fun open(uri: String, title: String, artist: String?, isVideo: Boolean)
}

private enum class QueueFilter(val label: String) { ACTIVE("Active"), DONE("Completed"), FAILED("Failed"), HISTORY("History") }

/** Above this many items a download asks for confirmation first. */
private const val LARGE_BATCH = 50

/** Room kept below the playlist so its last songs can scroll above the selection bar. */
private val SELECTION_BAR_SPACE = 84.dp

/**
 * Downloads tab (MImic 4.0): paste/share a supported link → choose audio or video quality →
 * the persisted queue (progress, speed, ETA, pause/resume/cancel/retry) → completed files open in
 * MImic's own player. Ported from ENAGELYUCA's Home + Downloads screens onto MImic's design.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadsTab(
    contentPadding: PaddingValues,
    sharedText: String?,
    onSharedTextConsumed: () -> Unit,
    opener: DownloadedMediaOpener,
    showMessage: (String) -> Unit,
) {
    val vm: DownloaderViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val history by vm.historyEntries.collectAsStateWithLifecycle()
    val waitingForWifi by vm.waitingForWifi.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(QueueFilter.ACTIVE) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            vm.consumeSharedText(sharedText)
            onSharedTextConsumed()
        }
    }
    LaunchedEffect(Unit) { vm.messages.collect(showMessage) }

    val active = queue.filter { !it.status.isTerminal }
    val done = queue.filter { it.status == DownloadStatus.COMPLETED }
    val failed = queue.filter { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED }

    // While the user is choosing what to download, the mini player shrinks to a slim bar so it
    // doesn't cover the song list or the selection bar.
    val phase = state.phase
    val choosing = phase is DetectPhase.Detected || phase is DetectPhase.Batch || phase is DetectPhase.ChooseScope
    val setCompactPlayer = LocalCompactMiniPlayer.current
    DisposableEffect(choosing) {
        setCompactPlayer(choosing)
        onDispose { setCompactPlayer(false) }
    }
    val picker = (phase as? DetectPhase.Detected)?.takeIf { it.playlist != null }
    var confirmLarge by remember { mutableStateOf<Int?>(null) }
    val barPadding = contentPadding.calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = barPadding + if (picker != null) SELECTION_BAR_SPACE else 0.dp,
        ),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 0.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Music or video from a link", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "Done" else "Settings") }
            }
        }
        if (showSettings) item { DownloaderSettingsCard(vm) }
        item { LinkCard(vm, state) }
        // A playlist's songs are list items of their own (not one huge card), so long playlists
        // scroll smoothly and the selection is kept while scrolling.
        if (picker != null) playlistPicker(vm, picker) else item { PhaseContent(vm, state) }
        item {
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (f in QueueFilter.entries) {
                    val n = when (f) {
                        QueueFilter.ACTIVE -> active.size
                        QueueFilter.DONE -> done.size
                        QueueFilter.FAILED -> failed.size
                        QueueFilter.HISTORY -> history.size
                    }
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(if (n > 0) "${f.label} · $n" else f.label) })
                }
            }
        }
        if (waitingForWifi && active.isNotEmpty()) {
            item { Notice("Waiting for Wi-Fi — downloads are set to Wi-Fi only (Settings above).") }
        }
        when (filter) {
            QueueFilter.ACTIVE -> {
                if (active.isEmpty()) item { Empty("Nothing downloading. Paste a YouTube or TikTok link above, or share one to MImic.") }
                else {
                    item {
                        Row(Modifier.padding(horizontal = 12.dp)) {
                            TextButton(onClick = { vm.pauseAll() }) { Text("Pause all") }
                            TextButton(onClick = { vm.resumeAll() }) { Text("Resume all") }
                        }
                    }
                    item { BatchProgress(queue) }
                    items(active, key = { it.id }) { JobRow(it, vm, opener) }
                }
            }
            QueueFilter.DONE -> {
                if (done.isEmpty()) item { Empty("Finished downloads appear here. Music also lands in your Library (Phone).") }
                else {
                    item { TextButton(onClick = { vm.clearFinished() }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Clear list (files stay)") } }
                    items(done, key = { it.id }) { JobRow(it, vm, opener) }
                }
            }
            QueueFilter.FAILED -> {
                if (failed.isEmpty()) item { Empty("No failed downloads.") }
                else {
                    item { TextButton(onClick = { vm.retryAllFailed() }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Retry all") } }
                    items(failed, key = { it.id }) { JobRow(it, vm, opener) }
                }
            }
            QueueFilter.HISTORY -> {
                if (history.isEmpty()) item { Empty("Your download history is empty.") }
                else items(history, key = { "h${it.id}" }) { HistoryRow(it, vm, opener) }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (picker != null) {
        SelectionBar(
            picker,
            onDownload = { n -> if (n > LARGE_BATCH) confirmLarge = n else vm.downloadSelected() },
            onCancel = { vm.reset(clearUrl = false) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = barPadding),
        )
    }
    }
    confirmLarge?.let { n ->
        ConfirmLargeBatch(
            n,
            onConfirm = {
                confirmLarge = null
                vm.downloadSelected()
            },
            onDismiss = { confirmLarge = null },
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(c.surfaceVariant.copy(alpha = 0.72f), c.surfaceVariant.copy(alpha = 0.42f))))
            .border(1.dp, c.onSurface.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun LinkCard(vm: DownloaderViewModel, state: DownloaderUiState) {
    val context = LocalContext.current
    Card {
        OutlinedTextField(
            value = state.urlText,
            onValueChange = vm::onUrlChanged,
            label = { Text("YouTube or TikTok link") },
            placeholder = { Text("https://…") },
            supportingText = state.urlHint?.let { { Text(it) } },
            isError = state.urlHint != null,
            maxLines = 4,
            trailingIcon = {
                IconButton(onClick = {
                    val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (!text.isNullOrBlank()) vm.consumeSharedText(text)
                }) { Icon(Icons.Default.ContentPaste, contentDescription = "Paste link") }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::analyze, enabled = state.canAnalyze) { Text("Find formats") }
            if (state.urlText.isNotEmpty()) OutlinedButton(onClick = { vm.reset(clearUrl = true) }) { Text("Clear") }
        }
        Text(
            "Download only media you own or are allowed to download. MImic doesn't bypass DRM, paywalls or logins.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PhaseContent(vm: DownloaderViewModel, state: DownloaderUiState) {
    when (val p = state.phase) {
        DetectPhase.Idle -> Unit
        is DetectPhase.Analyzing -> Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    if (p.detected.isMixPreview) {
                        Text("Reading Mix preview…")
                        Text(
                            if (p.found > 0) "${p.found} of up to ${MixPreview.PAGE} found" else "Only the first ${MixPreview.PAGE} recommendations are read.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else if (p.detected.isCollection) {
                        Text("Reading ${p.detected.listType?.label?.lowercase(Locale.ROOT) ?: "playlist"}…")
                        Text(
                            if (p.found > 0) "${p.found} found so far" else "Only the items of this exact list are read.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text("Reading ${p.detected.displayName} formats…")
                    }
                }
                TextButton(onClick = vm::cancelAnalyze) { Text("Cancel") }
            }
        }
        is DetectPhase.ChooseScope -> Card {
            val mix = p.detected.playlistContext?.isMix == true
            Text(if (mix) "This video was opened from a YouTube Mix." else "This link is a video inside a playlist.", fontWeight = FontWeight.SemiBold)
            if (mix) {
                Text(
                    "A Mix is an endless list of recommendations, not a fixed playlist. The preview shows ${MixPreview.PAGE} songs at a time and you tick the ones you want.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::chooseCurrentVideo) { Text("This video") }
                OutlinedButton(onClick = vm::choosePlaylist) { Text(if (mix) "Preview Mix" else "Whole playlist") }
            }
        }
        is DetectPhase.Error -> Card {
            Text(p.info.title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
            Text(p.info.message, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::analyze) { Text("Try again") }
                // A Mix that couldn't be listed: the song it was started from can still be downloaded.
                if (p.detected?.isMixPreview == true && p.detected.listId?.let { YouTubeProvider.mixSeed(it) } != null) {
                    OutlinedButton(onClick = vm::openMixSong) { Text("Song only") }
                }
                TextButton(onClick = { vm.reset(clearUrl = false) }) { Text("Dismiss") }
            }
        }
        is DetectPhase.Detected -> when (val r = p.result) {
            is AnalysisResult.Single -> SingleResult(vm, r.media, p.existing, p.note)
            // Shown by playlistPicker as list items of their own.
            is AnalysisResult.Collection -> Unit
        }
        is DetectPhase.Batch -> BatchResult(vm, p)
    }
}

@Composable
private fun MediaHeader(title: String, subtitle: String?, thumbnail: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = 96.dp, height = 56.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun SingleResult(vm: DownloaderViewModel, media: MediaInfo, existing: HistoryEntity?, note: String?) {
    var kind by rememberSaveable(media.webpageUrl) { mutableStateOf(FormatKind.AUDIO) }
    val formats = if (kind == FormatKind.AUDIO) media.audioFormats else media.videoFormats
    var selectedKey by rememberSaveable(media.webpageUrl, kind) { mutableStateOf(formats.firstOrNull()?.key) }
    var name by rememberSaveable(media.webpageUrl) { mutableStateOf(media.title) }
    val selected: MediaFormat? = formats.firstOrNull { it.key == selectedKey } ?: formats.firstOrNull()
    Card {
        MediaHeader(media.title, listOfNotNull(media.uploader, media.durationSeconds?.let { Formatting.duration(it) }).joinToString(" · ").ifBlank { null }, media.thumbnailUrl)
        if (note != null) Notice(note)
        if (existing != null) Notice("Already downloaded ${Formatting.relativeTime(existing.completedAt)} (${existing.location ?: "on this phone"}). You can download it again.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = kind == FormatKind.AUDIO, onClick = { kind = FormatKind.AUDIO }, label = { Text("Audio (${media.audioFormats.size})") }, leadingIcon = { Icon(Icons.Default.MusicNote, null, Modifier.size(18.dp)) })
            FilterChip(selected = kind == FormatKind.VIDEO, onClick = { kind = FormatKind.VIDEO }, label = { Text("Video (${media.videoFormats.size})") }, leadingIcon = { Icon(Icons.Default.Movie, null, Modifier.size(18.dp)) })
        }
        if (formats.isEmpty()) Text("No ${kind.name.lowercase()} formats offered for this item.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            for (f in formats) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { selectedKey = f.key }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = f.key == selected?.key, onClick = { selectedKey = f.key })
                    Column(Modifier.weight(1f)) {
                        Text(f.displayLabel + (f.qualityName?.let { " · $it" } ?: ""))
                        val details = listOfNotNull(
                            // MP3 rows are converted from the source stream; say so instead of "M4A".
                            f.container?.uppercase()?.let { if (f.kind == FormatKind.AUDIO && !f.nativeAudio) "from $it" else it },
                            f.fps?.takeIf { it > 30 }?.let { "${it}fps" },
                            if (f.isHdr) "HDR" else null,
                            sizeLabel(f),
                        ).joinToString(" · ")
                        if (details.isNotBlank()) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("File name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { selected?.let { vm.download(media, it, name, startNow = true) } }, enabled = selected != null) { Text("Download") }
            OutlinedButton(onClick = { selected?.let { vm.download(media, it, name, startNow = false) } }, enabled = selected != null) { Text("Later") }
            TextButton(onClick = { vm.reset(clearUrl = false) }) { Text("Cancel") }
        }
    }
}

private fun sizeLabel(f: MediaFormat): String? = when (val s = f.fileSizeBytes) {
    is com.example.mimic.downloader.data.model.Metric.Known -> Formatting.bytes(s.value)
    is com.example.mimic.downloader.data.model.Metric.Estimated -> "~" + Formatting.bytes(s.value)
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickChoice(selected: QuickFormat, onSelect: (QuickFormat) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (q in QuickFormat.choices) FilterChip(selected = q == selected, onClick = { onSelect(q) }, label = { Text(q.label) })
    }
}

/**
 * The playlist picker: a header card (title, counts, format) followed by one row per song with a
 * checkbox. The selection and format live in [DetectPhase.Detected], so they survive scrolling
 * and format changes; "Download selected" is in the [SelectionBar] pinned at the bottom.
 */
private fun LazyListScope.playlistPicker(vm: DownloaderViewModel, d: DetectPhase.Detected) {
    val r = d.result as AnalysisResult.Collection
    val pl = r.playlist
    val focused = r.focusedEntry
    val digits = (pl.entries.maxOfOrNull { it.index } ?: 0).toString().length.coerceAtLeast(2)
    item(key = "pl-header") { PlaylistHeader(vm, d, pl, focused?.let { pl.entries[it].index }) }
    itemsIndexed(pl.entries, key = { i, _ -> "pl-$i" }) { i, e ->
        PlaylistRow(
            e,
            checked = e.isSelectable && e.key in d.selection,
            focused = i == focused,
            digits = digits,
            onToggle = { vm.toggleEntry(e.key) },
        )
    }
    pl.mix?.let { page -> item(key = "pl-more") { MixMore(page, d.loadingMore, vm::loadMoreMix) } }
}

/** Under a Mix preview: "Load 25 more", or why nothing more is offered. */
@Composable
private fun MixMore(page: MixPage, loading: Boolean, onLoad: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Loading more songs…", style = MaterialTheme.typography.bodySmall)
            }
            page.hasMore -> OutlinedButton(onClick = onLoad) { Text("Load ${MixPreview.PAGE} more") }
            else -> Text(
                if (page.fetchedThrough >= MixPreview.MAX_POSITION) "Preview limit reached — a Mix never ends, so MImic stops here." else "No more songs in this Mix preview.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlaylistHeader(vm: DownloaderViewModel, d: DetectPhase.Detected, pl: PlaylistInfo, focusedIndex: Int?) {
    val c = MaterialTheme.colorScheme
    val selectable = pl.selectable.size
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = pl.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 112.dp, height = 63.dp).clip(RoundedCornerShape(10.dp)).background(c.surface),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(pl.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(if (pl.mix != null) "Mix preview" else pl.uploader, "${pl.entries.size} track" + if (pl.entries.size == 1) "" else "s").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("Selected: ${d.selection.size} of $selectable", style = MaterialTheme.typography.bodySmall, color = c.primary, fontWeight = FontWeight.Medium)
            }
        }
        if (pl.mix != null) {
            Notice("Recommendations generated by YouTube, not a fixed playlist. Shown in YouTube's order; only the songs you tick are downloaded.")
        }
        val notes = listOfNotNull(
            pl.unavailableCount.takeIf { it > 0 }?.let { "$it unavailable (private, deleted or needs sign-in) — shown but can't be downloaded." },
            pl.hiddenCount.takeIf { it > 0 }?.let { "YouTube counts ${pl.reportedCount} videos but lists ${pl.entries.size}; it hides $it unavailable ${if (it == 1) "one" else "ones"}." },
            pl.duplicateCount.takeIf { it > 0 }?.let { "$it ${if (it == 1) "video is" else "videos are"} listed twice; each is downloaded once." },
            focusedIndex?.let { "The video from your link is #$it (highlighted)." },
        )
        for (n in notes) Text(n, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        Text("Format for the selected items", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        FormatGroup("Audio", Icons.Default.MusicNote, QuickFormat.choices.filterIsInstance<QuickFormat.AudioMp3>(), d.quickFormat, vm::setQuickFormat)
        FormatGroup("Video", Icons.Default.Movie, QuickFormat.choices.filterIsInstance<QuickFormat.BestVideo>(), d.quickFormat, vm::setQuickFormat)
        Text(
            when (d.quickFormat) {
                is QuickFormat.AudioMp3 -> "Each item's best audio stream, converted to MP3 after download. An item that can't be converted fails with the reason; nothing else is saved in its place."
                is QuickFormat.BestVideo -> "Each item gets its own best video with audio within this limit (never upscaled). The quality actually delivered is shown on every download."
            },
            style = MaterialTheme.typography.bodySmall,
            color = c.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::selectAll, enabled = d.selection.size < selectable) { Text("Select all") }
            OutlinedButton(onClick = vm::deselectAll, enabled = d.selection.isNotEmpty()) { Text("Deselect all") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatGroup(label: String, icon: ImageVector, choices: List<QuickFormat>, selected: QuickFormat, onSelect: (QuickFormat) -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp).size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (q in choices) FilterChip(selected = q == selected, onClick = { onSelect(q) }, label = { Text(q.label) })
            }
        }
    }
}

@Composable
private fun PlaylistRow(e: PlaylistEntry, checked: Boolean, focused: Boolean, digits: Int, onToggle: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).clip(RoundedCornerShape(14.dp))
            .background(if (focused) c.primary.copy(alpha = 0.1f) else Color.Transparent)
            .toggleable(value = checked, enabled = e.isSelectable, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(top = 4.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = e.isSelectable, modifier = Modifier.padding(horizontal = 12.dp))
        Text(e.index.toString().padStart(digits, '0') + ".", modifier = Modifier.width((digits * 10 + 12).dp), color = c.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        AsyncImage(
            model = e.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = 64.dp, height = 36.dp).clip(RoundedCornerShape(6.dp)).background(c.surfaceVariant),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.title,
                color = if (e.isSelectable) c.onSurface else c.onSurface.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val reason = when {
                !e.isAvailable -> e.unavailableReason
                e.duplicateOf != null -> "Also #${e.duplicateOf} — downloaded once"
                else -> null
            }
            val details = listOfNotNull(e.uploader, e.durationSeconds?.let { Formatting.duration(it) }).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, color = c.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (reason != null) Text(reason, color = if (e.isAvailable) c.tertiary else c.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Pinned above the mini player while a playlist is open: the count, the format and the action. */
@Composable
private fun SelectionBar(d: DetectPhase.Detected, onDownload: (Int) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val n = d.selectedEntries.size
    val total = d.playlist?.selectable?.size ?: 0
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp))
            .background(c.surfaceVariant)
            .border(1.dp, c.onSurface.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
            .padding(start = 4.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Close playlist") }
        Column(Modifier.weight(1f)) {
            Text("Selected: $n of $total", color = c.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(d.quickFormat.label, color = c.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Button(onClick = { onDownload(n) }, enabled = n > 0) { Text("Download selected") }
    }
}

@Composable
private fun ConfirmLargeBatch(n: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = onConfirm) { Text("Download $n") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Download $n items?") },
        text = { Text("This adds $n separate downloads to the queue. Check that this is the list you meant before continuing.") },
    )
}

/** One line per playlist that is still downloading: "N of M done", with a bar. */
@Composable
private fun BatchProgress(queue: List<DownloadEntity>) {
    val batches = queue.filter { it.batchId != null }.groupBy { it.batchId }.values.filter { jobs -> jobs.any { !it.status.isTerminal } }
    for (jobs in batches) {
        val total = jobs.size
        val finished = jobs.count { it.status == DownloadStatus.COMPLETED }
        val failed = jobs.count { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
            val title = jobs.first().batchTitle ?: "Playlist"
            Text(
                "\"$title\": $finished of $total done" + if (failed > 0) " · $failed failed" else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(progress = { finished.toFloat() / total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

@Composable
private fun BatchResult(vm: DownloaderViewModel, p: DetectPhase.Batch) {
    var choice by remember { mutableStateOf<QuickFormat>(QuickFormat.AudioMp3()) }
    var includeExisting by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Int?>(null) }
    val toQueue = p.items.filter { it.status == BatchStatus.READY && (includeExisting || !it.alreadyDownloaded) }.sumOf {
        when (val r = it.result) {
            is AnalysisResult.Single -> 1
            is AnalysisResult.Collection -> r.playlist.selectable.size
            null -> 0
        }
    }
    confirm?.let { n ->
        ConfirmLargeBatch(
            n,
            onConfirm = {
                confirm = null
                vm.downloadAllReady(choice, includeExisting)
            },
            onDismiss = { confirm = null },
        )
    }
    Card {
        Text("${p.items.size} links", fontWeight = FontWeight.SemiBold)
        for (item in p.items) {
            Column {
                Text(item.title ?: item.url, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when (item.status) {
                    BatchStatus.ANALYZING -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    BatchStatus.FAILED -> Text(item.error ?: "Failed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    BatchStatus.NEEDS_CHOICE -> Row {
                        TextButton(onClick = { vm.chooseBatchScope(item.url, false) }) { Text("This video") }
                        TextButton(onClick = { vm.chooseBatchScope(item.url, true) }) { Text("Whole playlist") }
                    }
                    BatchStatus.READY -> if (item.alreadyDownloaded) Text("Already downloaded", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
        QuickChoice(choice) { choice = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = includeExisting, onCheckedChange = { includeExisting = it })
            Text("Also re-download ones I already have")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { if (toQueue > LARGE_BATCH) confirm = toQueue else vm.downloadAllReady(choice, includeExisting) },
                enabled = p.items.any { it.status == BatchStatus.READY },
            ) { Text("Download all ready ($toQueue)") }
            TextButton(onClick = { vm.reset(clearUrl = true) }) { Text("Cancel") }
        }
    }
}

@Composable
private fun JobRow(job: DownloadEntity, vm: DownloaderViewModel, opener: DownloadedMediaOpener) {
    val context = LocalContext.current
    val canOpen = job.status == DownloadStatus.COMPLETED && job.fileUri != null
    Card {
        Row(
            Modifier.then(if (canOpen) Modifier.clickable { opener.open(job.fileUri!!, job.title, job.uploader, job.category == DownloadCategory.VIDEO) } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = job.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 72.dp, height = 44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(job.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                val batch = if (job.batchIndex != null && job.batchSize != null) "Playlist ${job.batchIndex}/${job.batchSize} · " else ""
                Text(
                    batch + (job.errorMessage?.takeIf { job.status == DownloadStatus.FAILED } ?: ProgressDisplay.text(job)),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (job.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            when {
                job.status.canPause -> IconButton(onClick = { vm.pause(job.id) }) { Icon(Icons.Default.Pause, "Pause") }
                job.status == DownloadStatus.PAUSED -> IconButton(onClick = { vm.resume(job.id) }) { Icon(Icons.Default.PlayArrow, "Resume") }
                job.status.canRetry -> IconButton(onClick = { vm.retry(job.id) }) { Icon(Icons.Default.Refresh, "Retry") }
                canOpen -> IconButton(onClick = { opener.open(job.fileUri!!, job.title, job.uploader, job.category == DownloadCategory.VIDEO) }) { Icon(Icons.Default.PlayArrow, "Play") }
            }
            when {
                job.status.canCancel -> IconButton(onClick = { vm.cancel(job.id) }) { Icon(Icons.Default.Close, "Cancel") }
                else -> IconButton(onClick = { vm.remove(job.id) }) { Icon(Icons.Default.Delete, "Remove from list (file stays)") }
            }
        }
        val fraction = ProgressDisplay.fraction(job)
        if (!job.status.isTerminal && job.status != DownloadStatus.PAUSED) {
            if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (canOpen && job.category == DownloadCategory.VIDEO) {
            TextButton(onClick = { FileActions.share(context, job.fileUri!!, job.fileBaseName, job.title) }) { Text("Share") }
        }
    }
}

@Composable
private fun HistoryRow(h: HistoryEntity, vm: DownloaderViewModel, opener: DownloadedMediaOpener) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (h.fileUri != null && h.status == DownloadStatus.COMPLETED) Modifier.clickable { opener.open(h.fileUri, h.title, null, h.category == DownloadCategory.VIDEO) } else Modifier)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (h.category == DownloadCategory.VIDEO) Icons.Default.Movie else Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(h.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(h.formatLabel, Formatting.bytes(h.fileSizeBytes).takeIf { h.fileSizeBytes != null }, Formatting.relativeTime(h.completedAt), h.errorMessage).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (h.status == DownloadStatus.COMPLETED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                maxLines = 2,
            )
        }
        IconButton(onClick = { vm.deleteHistoryEntry(h.id) }) { Icon(Icons.Default.Delete, "Remove from history (file stays)") }
    }
}

@Composable
private fun DownloaderSettingsCard(vm: DownloaderViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val updating by vm.ytDlpUpdating.collectAsStateWithLifecycle()
    Card {
        Text("Downloader settings", fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Wi-Fi only")
                Text("Downloads wait for Wi-Fi", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = s.networkPolicy == NetworkPolicy.WIFI_ONLY, onCheckedChange = vm::setWifiOnly)
        }
        Text("Downloads at the same time: ${s.concurrentDownloads}")
        Slider(
            value = s.concurrentDownloads.toFloat(),
            onValueChange = { vm.setConcurrent(it.toInt()) },
            valueRange = 1f..5f,
            steps = 3,
        )
        Text("Speed limit")
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (l in SpeedLimit.entries) FilterChip(selected = s.speedLimit == l, onClick = { vm.setSpeedLimit(l) }, label = { Text(l.label) })
        }
        Text(
            "Files are saved to Music/MImic (audio, also shown in your Library) and Movies/MImic (video).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = vm::updateYtDlp, enabled = !updating) { Text(if (updating) "Updating yt-dlp…" else "Update yt-dlp (fixes most YouTube errors)") }
    }
}

@Composable
private fun Notice(text: String) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)).padding(12.dp),
    ) { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface) }
}

@Composable
private fun Empty(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
}
