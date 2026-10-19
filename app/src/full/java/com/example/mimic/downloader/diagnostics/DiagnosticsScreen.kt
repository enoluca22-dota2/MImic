package com.example.mimic.downloader.diagnostics

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mimic.downloader.DownloaderContainer
import com.example.mimic.ui.settings.Header
import com.example.mimic.ui.settings.Note
import com.example.mimic.ui.settings.Section
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = DownloaderContainer.get(app)
    private val diag = c.diagnostics
    val enabled: StateFlow<Boolean> = diag.enabled
    val attempts: StateFlow<List<DiagAttempt>> = diag.attempts

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()
    private var testJob: Job? = null

    fun setEnabled(on: Boolean) = diag.setEnabled(on)
    fun clear() = diag.clear()

    /** Analyzes a public test video through the normal analyzer, so it is recorded like any link. */
    fun runTest() {
        if (_testing.value) return
        _testing.value = true
        testJob = viewModelScope.launch {
            try {
                c.analyzer.analyze(DownloadDiagnostics.TEST_URL)
            } finally {
                _testing.value = false
            }
        }
    }

    fun cancelTest() {
        testJob?.cancel()
    }

    suspend fun reportText(): String = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val updated = YoutubeDL.version(app)
        val used = attempts.value.firstNotNullOfOrNull { it.facts.version }
        val version = when {
            updated != null -> "$updated (updated by MImic)"
            used != null -> "$used (bundled with MImic)"
            else -> null
        }
        diag.report(version, c.ytDlpUpdater.lastCheckMillis, c.engineState())
    }

    suspend fun reportFile() = withContext(Dispatchers.IO) { diag.writeReportFile(reportText()) }
}

/** Settings → Advanced → Download Diagnostics. */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val vm: DiagnosticsViewModel = viewModel()
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val attempts by vm.attempts.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val c = MaterialTheme.colorScheme

    LazyColumn(Modifier.fillMaxSize()) {
        item { Header("Download Diagnostics", onBack) }
        item {
            Note(
                "Records what happens when MImic reads a YouTube or TikTok link: device, network type, yt-dlp version, each step " +
                    "with its timing, and the exact error. It stays on this phone until you share it. Links are cut down to the " +
                    "video id; passwords, cookies, tokens and IP addresses are never recorded.",
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Record link analysis", style = MaterialTheme.typography.bodyLarge, color = c.onBackground)
                    Text("Keeps the last ${DownloadDiagnostics.MAX_ATTEMPTS} attempts", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = vm::setEnabled)
            }
        }
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            runCatching { shareReport(context, vm.reportFile()) }.onFailure {
                                android.widget.Toast.makeText(context, "Couldn't share the report. Use Copy report instead.", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Share Diagnostic Report") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val text = vm.reportText()
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("MImic download diagnostics", text))
                            android.widget.Toast.makeText(context, "Report copied", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Copy report") }
                if (testing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Testing with a public YouTube video…", color = c.onBackground, modifier = Modifier.weight(1f))
                        TextButton(onClick = vm::cancelTest) { Text("Cancel") }
                    }
                } else {
                    OutlinedButton(onClick = vm::runTest, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Text("Run a test (public YouTube video)")
                    }
                }
            }
        }
        item {
            Note(
                "To report a problem: analyze the link that fails in Downloads (or tap Run a test), then come back here and tap " +
                    "Share Diagnostic Report.",
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Section("Recent attempts") }
                if (attempts.isNotEmpty()) TextButton(onClick = vm::clear) { Text("Clear") }
            }
        }
        if (attempts.isEmpty()) {
            item { Note("Nothing recorded yet.") }
        }
        items(attempts, key = { "${it.startedAt}-${it.kind}-${it.link}" }) { a -> AttemptRow(a) }
    }
}

@Composable
private fun AttemptRow(a: DiagAttempt) {
    val c = MaterialTheme.colorScheme
    val ok = a.category == DiagCategory.OK
    val time = remember(a.startedAt) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(a.startedAt)) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
            .background((if (ok) c.primary else if (a.category.isFailure) c.error else c.outline).copy(alpha = 0.10f))
            .padding(12.dp),
    ) {
        Text(
            "${a.kind}: ${if (ok) "OK" else a.category.label}",
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            color = if (ok) c.primary else if (a.category.isFailure) c.error else c.onBackground,
        )
        Text("$time · ${"%.1f".format(Locale.US, a.durationMs / 1000.0)} s · ${a.network.substringBefore(" · internet")}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        Text(a.link, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        (a.formats ?: a.facts.errors.firstOrNull())?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = c.onBackground, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Android's share sheet with the TXT report (through MImic's FileProvider; read-only grant). */
private fun shareReport(context: Context, file: java.io.File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "MImic download diagnostics")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share Diagnostic Report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
