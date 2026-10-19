package com.example.mimic.ui

import androidx.compose.foundation.horizontalScroll
import com.example.mimic.data.ControlStyle
import com.example.mimic.data.PlayerBackground

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
            listOf("Classic", "Immersive", "Compact", "Dynamic Now Playing"),
            config.layout.ordinal,
        ) { update(config.copy(layout = PlayerLayout.entries[it])) }

        DynamicPlayerSettings(vm, settings.dynamicPlayer, active = config.layout == PlayerLayout.DYNAMIC) {
            update(config.copy(layout = PlayerLayout.DYNAMIC))
        }

        Text(
            "Classic · Immersive · Compact",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
        )

        SelectRow(
            "Artwork shape",
            listOf("Rounded", "Circle", "Square", "Squircle"),
            config.artworkShape.ordinal,
        ) { update(config.copy(artworkShape = ArtworkShape.entries[it])) }

        SelectRow(
            "Artwork size",
            listOf("Small", "Medium", "Large"),
            config.artworkSize.ordinal,
        ) { update(config.copy(artworkSize = ArtworkSize.entries[it])) }

        SelectRow(
            "Background",
            listOf("Auto", "Solid", "Gradient", "Blurred artwork"),
            config.background.ordinal,
        ) { update(config.copy(background = PlayerBackground.entries[it])) }

        SelectRow(
            "Controls",
            listOf("Auto", "Filled", "Liquid Glass", "Minimal"),
            config.controlStyle.ordinal,
        ) { update(config.copy(controlStyle = ControlStyle.entries[it])) }
        Text(
            "Auto follows the theme: Liquid Glass themes get glass controls over the blurred cover.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

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
fun SelectRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        // Scrolls horizontally so long option sets never clip on narrow phones.
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
        PlayerLayout.DYNAMIC -> 0.8f
    } * 0.55f // scale down for the preview card
    val shape = when (config.artworkShape) {
        ArtworkShape.CIRCLE -> CircleShape
        ArtworkShape.SQUIRCLE -> com.kyant.shapes.RoundedRectangle(config.artworkShape.cornerDp().dp)
        else -> RoundedCornerShape(config.artworkShape.cornerDp().dp)
    }

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

/**
 * Settings for the Dynamic Now Playing player (4.1). Stored separately from the other layouts, so
 * tuning them never changes Classic, Immersive, Compact or any theme.
 */
@Composable
private fun DynamicPlayerSettings(
    vm: PlayerViewModel,
    c: com.example.mimic.data.DynamicPlayerConfig,
    active: Boolean,
    onUse: () -> Unit,
) {
    fun set(n: com.example.mimic.data.DynamicPlayerConfig) = vm.setDynamicPlayer(n)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Dynamic Now Playing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Colors, gradient and lyrics follow the artwork of the song that's playing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!active) androidx.compose.material3.FilledTonalButton(onClick = onUse) { Text("Use") }
            else Text("In use", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        ToggleRow("Dynamic gradient", c.gradient) { set(c.copy(gradient = it)) }
        ToggleRow("Artwork blur", c.artworkBlur) { set(c.copy(artworkBlur = it)) }
        ToggleRow("Animated background", c.animatedBackground) { set(c.copy(animatedBackground = it)) }
        SelectRow("Animation intensity", com.example.mimic.data.DynamicIntensity.entries.map { it.label }, c.intensity.ordinal) {
            set(c.copy(intensity = com.example.mimic.data.DynamicIntensity.entries[it]))
        }
        SelectRow("Artwork shape", com.example.mimic.data.DynamicArtShape.entries.map { it.label }, c.artShape.ordinal) {
            set(c.copy(artShape = com.example.mimic.data.DynamicArtShape.entries[it]))
        }
        SelectRow("Artwork size", com.example.mimic.data.DynamicArtSize.entries.map { it.label }, c.artSize.ordinal) {
            set(c.copy(artSize = com.example.mimic.data.DynamicArtSize.entries[it]))
        }
        ToggleRow("Glass controls", c.glassControls) { set(c.copy(glassControls = it)) }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text("Background brightness", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            androidx.compose.material3.Slider(
                value = c.brightness,
                onValueChange = { set(c.copy(brightness = it)) },
                valueRange = -0.4f..0.4f,
                steps = 7,
            )
        }
        ToggleRow("Automatic contrast", c.autoContrast) { set(c.copy(autoContrast = it)) }
        Text(
            "Text always keeps at least 4.5:1 contrast with the background, whatever the artwork or these settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        ToggleRow("Reduce motion", c.reduceMotion) { set(c.copy(reduceMotion = it)) }
        SelectRow("Lock-screen style view", com.example.mimic.data.LockStyle.entries.map { it.label }, c.lockStyle.ordinal) {
            set(c.copy(lockStyle = com.example.mimic.data.LockStyle.entries[it]))
        }
    }
}
