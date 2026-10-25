package com.example.mimic.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mimic.data.lyrics.LyricsStatus
import com.example.mimic.player.PlayerViewModel

/**
 * Library → Lyrics (MImic 4.5): Find Missing Lyrics for music already on the phone / server /
 * downloaded, without downloading the audio again or touching the files. Runs in the background
 * (leaving the screen doesn't stop it), at a pace LRCLIB is fine with.
 */
@Composable
fun MissingLyricsScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    val scan by vm.lyricsScan.collectAsStateWithLifecycle()
    val counts by vm.lyricsCounts.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Lyrics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Saved lyrics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    val synced = counts[LyricsStatus.SYNCED] ?: 0
                    val plain = counts[LyricsStatus.PLAIN] ?: 0
                    Text("$synced synced · $plain plain · ${songs.size} songs in the library", style = MaterialTheme.typography.bodyMedium)
                    val none = counts[LyricsStatus.UNAVAILABLE] ?: 0
                    val failed = counts[LyricsStatus.FAILED] ?: 0
                    val choose = counts[LyricsStatus.NEEDS_CHOICE] ?: 0
                    val pending = counts[LyricsStatus.PENDING] ?: 0
                    Text(
                        "$none without lyrics · $failed lookups failed · $choose need you to choose · $pending waiting",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Saved lyrics work offline. Songs that need a choice show a list in the lyrics screen when played.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Find Missing Lyrics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Checks every song without saved lyrics: the file's own tags and .lrc, Navidrome, then LRCLIB. " +
                            "Songs with lyrics are skipped; failed lookups are retried. Your music files are never changed.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    val s = scan
                    if (s != null && s.running) {
                        val fraction = if (s.total > 0) s.done.toFloat() / s.total else 0f
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                        Text("${s.done} of ${s.total} checked · ${s.found} found · ${s.unavailable} none · ${s.failed} failed", style = MaterialTheme.typography.bodyMedium)
                        if (s.total == 0) Text("Waiting for a connection…", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = vm::cancelLyricsScan, modifier = Modifier.padding(top = 8.dp)) { Text("Cancel") }
                    } else {
                        if (s != null && s.finished) {
                            Text(
                                if (s.cancelled) "Cancelled." else "Done: ${s.found} found, ${s.unavailable} without lyrics, ${s.failed} failed, " +
                                    "${s.choose} need a choice, ${s.skipped} skipped (lyrics saved or checked recently).",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        Button(onClick = vm::findMissingLyrics, enabled = songs.isNotEmpty()) {
                            Text(if (s?.finished == true && s.failed > 0) "Retry failed lookups" else "Find Missing Lyrics")
                        }
                    }
                }
            }
            Text(
                "Lyrics come from LRCLIB (lrclib.net), a free community database. Only title, artist, album and length are sent.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}
