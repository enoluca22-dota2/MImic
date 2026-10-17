package com.example.mimic.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mimic.data.ArtworkShape
import com.example.mimic.data.ArtworkSize
import com.example.mimic.data.PlayerLayout
import com.example.mimic.data.PlayerLayoutConfig
import com.example.mimic.data.cornerDp
import com.example.mimic.data.fraction
import com.example.mimic.player.PlayerViewModel

@Composable
fun PlayerLayoutStudioScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.playerLayout
    fun update(new: PlayerLayoutConfig) = vm.setPlayerLayout(new)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(
                "Player Layout",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            "Changes apply instantly and never interrupt playback.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        PlayerLayoutPreview(config, Modifier.padding(16.dp))

        SelectRow(
            "Layout",
            listOf("Classic", "Immersive", "Compact"),
            config.layout.ordinal,
        ) { update(config.copy(layout = PlayerLayout.entries[it])) }

        SelectRow(
            "Artwork shape",
            listOf("Rounded", "Circle", "Square"),
            config.artworkShape.ordinal,
        ) { update(config.copy(artworkShape = ArtworkShape.entries[it])) }

        SelectRow(
            "Artwork size",
            listOf("Small", "Medium", "Large"),
            config.artworkSize.ordinal,
        ) { update(config.copy(artworkSize = ArtworkSize.entries[it])) }

        ToggleRow("Show shuffle & repeat", config.showShuffleRepeat) {
            update(config.copy(showShuffleRepeat = it))
        }
        ToggleRow("Show queue & sleep buttons", config.showSecondaryActions) {
            update(config.copy(showSecondaryActions = it))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SelectRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { index, label ->
                val isSelected = index == selected
                Box(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .clickable { onSelect(index) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        label,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A miniature Now Playing that honors the chosen layout, shape, size and toggles. */
@Composable
fun PlayerLayoutPreview(config: PlayerLayoutConfig, modifier: Modifier = Modifier) {
    val bg = if (config.layout == PlayerLayout.IMMERSIVE) {
        Modifier.background(
            Brush.verticalGradient(
                listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), MaterialTheme.colorScheme.surface),
            ),
        )
    } else {
        Modifier.background(MaterialTheme.colorScheme.surface)
    }
    val artFraction = when (config.layout) {
        PlayerLayout.IMMERSIVE -> maxOf(config.artworkSize.fraction(), 0.9f)
        PlayerLayout.COMPACT -> minOf(config.artworkSize.fraction(), 0.6f)
        PlayerLayout.CLASSIC -> config.artworkSize.fraction()
    } * 0.55f // scale down for the preview card
    val shape = if (config.artworkShape == ArtworkShape.CIRCLE) CircleShape
    else RoundedCornerShape(config.artworkShape.cornerDp().dp)

    Column(
        modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(bg)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (config.layout == PlayerLayout.COMPACT) Arrangement.Top else Arrangement.Center,
    ) {
        Box(
            Modifier.fillMaxWidth(artFraction).aspectRatio(1f).clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Text("Sample Track", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, maxLines = 1)
        Text("Sample Artist", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(0.4f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary))
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (config.showShuffleRepeat) {
                Icon(Icons.Default.Shuffle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            Icon(Icons.Default.SkipPrevious, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
            Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
            }
            Icon(Icons.Default.SkipNext, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
            if (config.showShuffleRepeat) {
                Icon(Icons.Default.Repeat, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}
