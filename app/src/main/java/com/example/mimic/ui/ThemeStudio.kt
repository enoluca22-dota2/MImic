package com.example.mimic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mimic.data.ThemeSerialization
import com.example.mimic.player.PlayerViewModel
import com.example.mimic.ui.designsystem.ThemeColors
import com.example.mimic.ui.designsystem.ThemeDefinition

@Composable
private fun StudioHeader(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
fun ThemeStudioScreen(
    vm: PlayerViewModel,
    onEdit: (ThemeDefinition) -> Unit,
    onCreateNew: () -> Unit,
    onImport: () -> Unit,
    onExportFile: (ThemeDefinition) -> Unit,
    onShare: (ThemeDefinition) -> Unit,
    onBack: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val themes by vm.allThemes.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        StudioHeader("Theme Studio", onBack, actions = {
            IconButton(onClick = onImport) { Icon(Icons.Default.FileDownload, contentDescription = "Import theme") }
            IconButton(onClick = onCreateNew) { Icon(Icons.Default.Add, contentDescription = "New theme") }
        })
        Text(
            "Tap to apply. Use the menu to duplicate, edit, export or delete.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(themes, key = { it.id }) { theme ->
                ThemeRow(
                    theme = theme,
                    selected = theme.id == settings.themeId,
                    onApply = { vm.setThemeId(theme.id) },
                    onEdit = { onEdit(theme) },
                    onDuplicate = { onEdit(vm.duplicateTheme(theme)) },
                    onDelete = { vm.deleteCustomTheme(theme.id) },
                    onExportFile = { onExportFile(theme) },
                    onShare = { onShare(theme) },
                )
            }
        }
    }
}

@Composable
private fun ThemeRow(
    theme: ThemeDefinition,
    selected: Boolean,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onExportFile: () -> Unit,
    onShare: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onApply).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemeSwatch(theme, size = 52)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(theme.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            Text(
                (if (theme.colors.isDark) "Dark" else "Light") + if (theme.builtIn) " · Built-in" else " · Custom",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary)
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Options")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Apply") }, onClick = { menu = false; onApply() })
                DropdownMenuItem(text = { Text("Duplicate & edit") }, onClick = { menu = false; onDuplicate() })
                if (!theme.builtIn) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                }
                DropdownMenuItem(text = { Text("Export to file") }, onClick = { menu = false; onExportFile() })
                DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; onShare() })
                if (!theme.builtIn) {
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
fun ThemeEditorScreen(
    initial: ThemeDefinition,
    onSave: (ThemeDefinition, Boolean) -> Unit,
    onExport: (ThemeDefinition) -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var picking by remember { mutableStateOf<String?>(null) } // token key being edited

    fun setColor(key: String, value: Long) {
        val c = draft.colors
        draft = draft.copy(
            colors = when (key) {
                "primary" -> c.copy(primary = value)
                "secondary" -> c.copy(secondary = value)
                "background" -> c.copy(background = value)
                "surface" -> c.copy(surface = value)
                "surfaceElevated" -> c.copy(surfaceElevated = value)
                "textPrimary" -> c.copy(textPrimary = value)
                else -> c.copy(textSecondary = value)
            },
        )
    }

    fun colorOf(key: String): Long = with(draft.colors) {
        when (key) {
            "primary" -> primary; "secondary" -> secondary; "background" -> background
            "surface" -> surface; "surfaceElevated" -> surfaceElevated
            "textPrimary" -> textPrimary; else -> textSecondary
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StudioHeader("Edit theme", onBack, actions = {
            IconButton(onClick = { onExport(draft) }) { Icon(Icons.Default.FileDownload, contentDescription = "Export") }
        })
        ThemePreview(draft, Modifier.padding(16.dp))
        OutlinedTextField(
            value = draft.name,
            onValueChange = { draft = draft.copy(name = it.take(60)) },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Dark theme", Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
            Switch(
                checked = draft.colors.isDark,
                onCheckedChange = { draft = draft.copy(colors = draft.colors.copy(isDark = it)) },
            )
        }
        val tokens = listOf(
            "primary" to "Primary accent", "secondary" to "Secondary accent",
            "background" to "Background", "surface" to "Surface",
            "surfaceElevated" to "Elevated surface", "textPrimary" to "Text primary",
            "textSecondary" to "Text secondary",
        )
        tokens.forEach { (key, label) ->
            Row(
                Modifier.fillMaxWidth().clickable { picking = key }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(Color(colorOf(key))))
                Spacer(Modifier.width(16.dp))
                Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
                Text(ThemeSerialization.toHex(colorOf(key)), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = { onSave(draft, false); onBack() }, modifier = Modifier.weight(1f)) {
                Text("Save")
            }
            Button(onClick = { onSave(draft, true); onBack() }, modifier = Modifier.weight(1f)) {
                Text("Save & apply")
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    picking?.let { key ->
        ColorPickerDialog(
            initial = colorOf(key),
            onDismiss = { picking = null },
            onPick = { setColor(key, it); picking = null },
        )
    }
}

/** A representative preview card rendered with the draft theme's own colors. */
@Composable
fun ThemePreview(theme: ThemeDefinition, modifier: Modifier = Modifier) {
    val c = theme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(c.background))
            .padding(16.dp),
    ) {
        Text("Now Playing", color = Color(c.textSecondary), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(c.surface)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(Color(c.surfaceElevated)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Sample Track", color = Color(c.textPrimary), fontWeight = FontWeight.Bold, maxLines = 1)
                Text("Sample Artist", color = Color(c.textSecondary), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            Box(Modifier.size(36.dp).clip(CircleShape).background(Color(c.primary)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = if (c.isDark) Color.Black else Color.White)
            }
        }
        Spacer(Modifier.height(12.dp))
        // Progress bar sample
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(c.surfaceElevated))) {
            Box(Modifier.fillMaxWidth(0.4f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(c.primary)))
        }
    }
}

@Composable
fun ColorPickerDialog(initial: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val alpha = (initial shr 24) and 0xFF
    var r by remember { mutableStateOf(((initial shr 16) and 0xFF).toInt()) }
    var g by remember { mutableStateOf(((initial shr 8) and 0xFF).toInt()) }
    var b by remember { mutableStateOf((initial and 0xFF).toInt()) }
    fun argb(): Long = (alpha shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    var hex by remember { mutableStateOf(ThemeSerialization.toHex(initial)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick color") },
        text = {
            Column {
                Box(
                    Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(8.dp)).background(Color(argb())),
                )
                Spacer(Modifier.height(12.dp))
                ChannelSlider("R", r) { r = it; hex = ThemeSerialization.toHex(argb()) }
                ChannelSlider("G", g) { g = it; hex = ThemeSerialization.toHex(argb()) }
                ChannelSlider("B", b) { b = it; hex = ThemeSerialization.toHex(argb()) }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { text ->
                        hex = text
                        runCatching { ThemeSerialization.parseHex(text) }.onSuccess { parsed ->
                            r = ((parsed shr 16) and 0xFF).toInt()
                            g = ((parsed shr 8) and 0xFF).toInt()
                            b = (parsed and 0xFF).toInt()
                        }
                    },
                    label = { Text("Hex") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(argb()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChannelSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(20.dp), color = MaterialTheme.colorScheme.onSurface)
        androidx.compose.material3.Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f),
        )
        Text(value.toString(), Modifier.width(36.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ImportThemeDialog(onDismiss: () -> Unit, onImport: (String) -> String?) {
    var json by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import theme") },
        text = {
            Column {
                Text(
                    "Paste a MImic theme (JSON) exported from this app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = json,
                    onValueChange = { json = it; error = null },
                    label = { Text("Theme JSON") },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val err = onImport(json)
                if (err == null) onDismiss() else error = err
            }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
