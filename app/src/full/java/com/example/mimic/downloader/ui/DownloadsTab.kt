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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.example.mimic.downloader.data.model.QuickFormat
import com.example.mimic.downloader.download.ProgressDisplay

/** What a completed download asks MImic to do when tapped. */
fun interface DownloadedMediaOpener {
    fun open(uri: String, title: String, artist: String?, isVideo: Boolean)
}

private enum class QueueFilter(val label: String) { ACTIVE("Active"), DONE("Completed"), FAILED("Failed"), HISTORY("History") }

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

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Downloads", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "Done" else "Settings") }
            }
        }
        if (showSettings) item { DownloaderSettingsCard(vm) }
        item { LinkCard(vm, state) }
        item { PhaseContent(vm, state) }
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
                Text("Reading ${p.detected.displayName} formats…", modifier = Modifier.weight(1f))
                TextButton(onClick = vm::cancelAnalyze) { Text("Cancel") }
            }
        }
        is DetectPhase.ChooseScope -> Card {
            Text("This link is a video inside a playlist.", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::chooseCurrentVideo) { Text("This video") }
                OutlinedButton(onClick = vm::choosePlaylist) { Text("Whole playlist") }
            }
        }
        is DetectPhase.Error -> Card {
            Text(p.info.title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
            Text(p.info.message, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::analyze) { Text("Try again") }
                TextButton(onClick = { vm.reset(clearUrl = false) }) { Text("Dismiss") }
            }
        }
        is DetectPhase.Detected -> when (val r = p.result) {
            is AnalysisResult.Single -> SingleResult(vm, r.media, p.existing)
            is AnalysisResult.Collection -> PlaylistResult(vm, r)
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
private fun SingleResult(vm: DownloaderViewModel, media: MediaInfo, existing: HistoryEntity?) {
    var kind by rememberSaveable(media.webpageUrl) { mutableStateOf(FormatKind.AUDIO) }
    val formats = if (kind == FormatKind.AUDIO) media.audioFormats else media.videoFormats
    var selectedKey by rememberSaveable(media.webpageUrl, kind) { mutableStateOf(formats.firstOrNull()?.key) }
    var name by rememberSaveable(media.webpageUrl) { mutableStateOf(media.title) }
    val selected: MediaFormat? = formats.firstOrNull { it.key == selectedKey } ?: formats.firstOrNull()
    Card {
        MediaHeader(media.title, listOfNotNull(media.uploader, media.durationSeconds?.let { Formatting.duration(it) }).joinToString(" · ").ifBlank { null }, media.thumbnailUrl)
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

@Composable
private fun PlaylistResult(vm: DownloaderViewModel, r: AnalysisResult.Collection) {
    val pl = r.playlist
    val available = pl.entries.filter { it.isAvailable }
    var picked by remember(pl.sourceUrl) {
        mutableStateOf(if (r.focusedEntry != null) setOf(pl.entries[r.focusedEntry!!].index) else available.map { it.index }.toSet())
    }
    var choice by remember(pl.sourceUrl) { mutableStateOf<QuickFormat>(QuickFormat.AudioMp3()) }
    Card {
        MediaHeader(pl.title, listOfNotNull(pl.uploader, "${pl.entries.size} items").joinToString(" · "), pl.thumbnailUrl)
        QuickChoice(choice) { choice = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = picked.size == available.size, onCheckedChange = { all -> picked = if (all) available.map { it.index }.toSet() else emptySet() })
            Text("Select all (${available.size})")
        }
        Column {
            for (e in pl.entries) {
                Row(Modifier.fillMaxWidth().clickable(enabled = e.isAvailable) { picked = if (e.index in picked) picked - e.index else picked + e.index }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = e.index in picked, onCheckedChange = null, enabled = e.isAvailable)
                    Column(Modifier.weight(1f)) {
                        Text("${e.index}. ${e.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(e.unavailableReason ?: listOfNotNull(e.uploader, e.durationSeconds?.let { Formatting.duration(it) }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = if (e.isAvailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.downloadPlaylist(pl, pl.entries.filter { it.index in picked }, choice) }, enabled = picked.isNotEmpty()) { Text("Download ${picked.size}") }
            TextButton(onClick = { vm.reset(clearUrl = false) }) { Text("Cancel") }
        }
    }
}

@Composable
private fun BatchResult(vm: DownloaderViewModel, p: DetectPhase.Batch) {
    var choice by remember { mutableStateOf<QuickFormat>(QuickFormat.AudioMp3()) }
    var includeExisting by remember { mutableStateOf(false) }
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
            Button(onClick = { vm.downloadAllReady(choice, includeExisting) }, enabled = p.items.any { it.status == BatchStatus.READY }) { Text("Download all ready") }
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
