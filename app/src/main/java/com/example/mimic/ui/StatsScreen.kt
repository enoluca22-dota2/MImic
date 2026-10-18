package com.example.mimic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mimic.data.ListeningStats
import com.example.mimic.player.PlayerViewModel
import com.example.mimic.ui.library.DetailTopBar
import com.example.mimic.ui.library.LocalBarPadding
import com.example.mimic.ui.library.SectionTitle

/** Listening stats (MImic 3.2): from this phone's play history only; nothing leaves the device. */
@Composable
fun StatsScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    val stats by vm.listeningStats.collectAsStateWithLifecycle()
    val days by vm.statsDays.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBarPadding.current + 16.dp)) {
        item { DetailTopBar("Listening stats", onBack) }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7 to "7 days", 30 to "30 days", 365 to "Year").forEach { (d, label) ->
                    FilterChip(selected = days == d, onClick = { vm.statsDays.value = d }, label = { Text(label) })
                }
            }
        }
        val s = stats
        if (s == null) {
            item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            return@LazyColumn
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Plays", "${s.plays}", Modifier.weight(1f))
                StatTile("Listened", formatMinutes(s.estimatedMinutes), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Different songs", "${s.distinctSongs}", Modifier.weight(1f))
                StatTile("Day streak", "${s.streakDays}", Modifier.weight(1f))
            }
        }
        item {
            SectionTitle(if (days == 365) "Plays per day · last year" else "Plays per day · last $days days")
            PlaysChart(s.playsPerDay)
        }
        if (s.topArtists.isNotEmpty()) {
            item { SectionTitle("Top artists") }
            s.topArtists.forEachIndexed { i, (artist, n) ->
                item(key = "ta$i") { RankRow(i + 1, artist, "$n plays", null) }
            }
        }
        if (s.topSongs.isNotEmpty()) {
            item { SectionTitle("Top songs") }
            s.topSongs.forEachIndexed { i, (song, n) ->
                item(key = "ts$i") { RankRow(i + 1, song.title, "${song.artist} · $n plays") { vm.play(s.topSongs.map { it.first }, i) } }
            }
        }
        if (s.plays == 0) {
            item {
                Text("No plays in this period yet. Stats fill in as you listen.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
        }
        item {
            Text(
                "A play counts when a song starts. Listening time adds up the length of each played song, so it's an estimate (skipped songs count fully). Calculated on this phone only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

private fun formatMinutes(m: Long): String = if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)).padding(14.dp),
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Single-series bar chart: thin bars in the theme's primary color with 4 dp rounded tops on a
 * shared baseline, 2 dp gaps, a recessive baseline, and no per-bar numbers. Tap a bar to read its
 * value; TalkBack gets a text summary instead of the picture.
 */
@Composable
private fun PlaysChart(perDay: List<Int>) {
    val max = (perDay.maxOrNull() ?: 0).coerceAtLeast(1)
    var selected by remember(perDay) { mutableStateOf<Int?>(null) }
    val bar = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val busiest = perDay.indices.maxByOrNull { perDay[it] }
    val summary = "Plays per day over ${perDay.size} days: total ${perDay.sum()}, most ${perDay.maxOrNull() ?: 0} in one day."
    Column(Modifier.padding(horizontal = 16.dp)) {
        val sel = selected
        Text(
            when {
                sel != null -> "${daysAgoLabel(perDay.size - 1 - sel)}: ${perDay[sel]} plays"
                busiest != null && perDay[busiest] > 0 -> "Busiest: ${daysAgoLabel(perDay.size - 1 - busiest)} (${perDay[busiest]} plays) · tap a bar"
                else -> "Tap a bar to see that day"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier.fillMaxWidth().height(120.dp)
                .semantics { contentDescription = summary }
                .pointerInput(perDay) {
                    detectTapGestures { o ->
                        val i = (o.x / (size.width / perDay.size.toFloat())).toInt().coerceIn(0, perDay.size - 1)
                        selected = if (selected == i) null else i
                    }
                },
        ) {
            val n = perDay.size
            val slot = size.width / n
            val gap = if (slot > 6.dp.toPx()) 2.dp.toPx() else 0.5.dp.toPx()
            val w = (slot - gap).coerceAtLeast(1f)
            val r = minOf(4.dp.toPx(), w / 2)
            perDay.forEachIndexed { i, v ->
                if (v == 0) return@forEachIndexed
                val h = (v.toFloat() / max) * size.height
                val x = i * slot + gap / 2
                val alpha = if (selected == null || selected == i) 1f else 0.4f
                // Rounded top, square base anchored on the baseline.
                val path = Path().apply {
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            left = x, top = size.height - h, right = x + w, bottom = size.height,
                            topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r),
                        ),
                    )
                }
                drawPath(path, bar.copy(alpha = alpha))
            }
            drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        }
        Row(Modifier.fillMaxWidth()) {
            Text(daysAgoLabel(perDay.size - 1), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("Today", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun daysAgoLabel(d: Int) = when (d) { 0 -> "Today"; 1 -> "Yesterday"; else -> "$d days ago" }

@Composable
private fun RankRow(rank: Int, title: String, subtitle: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$rank", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
