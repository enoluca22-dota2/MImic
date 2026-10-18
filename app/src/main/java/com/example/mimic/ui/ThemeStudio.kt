package com.example.mimic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mimic.data.GlassQuality
import com.example.mimic.data.Song
import com.example.mimic.data.ThemeSerialization
import com.example.mimic.player.PlayerViewModel
import com.example.mimic.ui.components.GlassNavBar
import com.example.mimic.ui.components.GlassNavItem
import com.example.mimic.ui.components.MiniPlayer
import com.example.mimic.ui.designsystem.ControlShape
import com.example.mimic.ui.designsystem.FontChoice
import com.example.mimic.ui.designsystem.GlassTone
import com.example.mimic.ui.designsystem.LocalGlass
import com.example.mimic.ui.designsystem.LocalGlassBackdrop
import com.example.mimic.ui.designsystem.NavIndicator
import com.example.mimic.ui.designsystem.ThemeDefinition
import com.example.mimic.ui.designsystem.ThemeRegistry
import com.example.mimic.ui.designsystem.ThemeStyle
import com.example.mimic.ui.designsystem.controlShapeValue
import com.example.mimic.ui.theme.MImicTheme
import com.github.skydoves.colorpicker.compose.BrightnessSlider
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.roundToInt

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
            fontWeight = LocalGlass.current.headerFontWeight,
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
    var confirmDelete by remember { mutableStateOf<ThemeDefinition?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }

    val canonical = ThemeRegistry.CANONICAL_IDS.mapNotNull { id -> themes.firstOrNull { it.id == id } }
    val flagship = themes.firstOrNull { it.id == "mimic_liquid_glass" }
    val legacy = themes.filter { it.builtIn && it.id != "mimic_liquid_glass" && it.id !in ThemeRegistry.CANONICAL_IDS }
    val custom = themes.filter { !it.builtIn }

    Column(Modifier.fillMaxSize()) {
        StudioHeader("Theme Studio", onBack, actions = {
            IconButton(onClick = onImport) { Icon(Icons.Default.FileDownload, contentDescription = "Import theme") }
            IconButton(onClick = onCreateNew) { Icon(Icons.Default.Add, contentDescription = "New theme") }
            Box {
                IconButton(onClick = { overflow = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                    DropdownMenuItem(text = { Text("Restore defaults") }, onClick = { overflow = false; confirmRestore = true })
                }
            }
        })
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "quality") { GlassQualityPicker(settings.glassQuality, vm::setGlassQuality) }
            flagship?.let { theme ->
                item(key = "flagship") {
                    FlagshipCard(
                        theme = theme,
                        quality = settings.glassQuality,
                        selected = settings.themeId == theme.id,
                        onApply = { vm.setThemeId(theme.id) },
                        onCustomize = { onEdit(vm.duplicateTheme(theme)) },
                    )
                }
            }
            themeSection("Presets", canonical, settings.themeId, vm, onEdit, onExportFile, onShare) { confirmDelete = it }
            if (custom.isNotEmpty()) {
                themeSection("Your themes", custom, settings.themeId, vm, onEdit, onExportFile, onShare) { confirmDelete = it }
            }
            themeSection("More presets", legacy, settings.themeId, vm, onEdit, onExportFile, onShare) { confirmDelete = it }
            item(key = "footer") { Spacer(Modifier.height(24.dp)) }
        }
    }

    confirmDelete?.let { theme ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \"${theme.name}\"?") },
            text = { Text("This custom theme will be removed. Built-in presets are not affected. This can't be undone — export it first if you want a copy.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteCustomTheme(theme.id); confirmDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore defaults?") },
            text = { Text("Switches back to the default theme, resets glass quality and sets navigation and player styles to follow the theme. Your custom themes are kept.") },
            confirmButton = { TextButton(onClick = { vm.restoreThemeDefaults(); confirmRestore = false }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } },
        )
    }
}

private fun LazyListScope.themeSection(
    title: String,
    list: List<ThemeDefinition>,
    selectedId: String,
    vm: PlayerViewModel,
    onEdit: (ThemeDefinition) -> Unit,
    onExportFile: (ThemeDefinition) -> Unit,
    onShare: (ThemeDefinition) -> Unit,
    onDelete: (ThemeDefinition) -> Unit,
) {
    if (list.isEmpty()) return
    item(key = "h_$title") {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    items(list, key = { it.id }) { theme ->
        ThemeRow(
            theme = theme,
            selected = theme.id == selectedId,
            onApply = { vm.setThemeId(theme.id) },
            onEdit = { onEdit(theme) },
            onDuplicate = { onEdit(vm.duplicateTheme(theme)) },
            onDelete = { onDelete(theme) },
            onExportFile = { onExportFile(theme) },
            onShare = { onShare(theme) },
        )
    }
}

/** Device-wide glass quality. Independent of the theme; changes apply instantly. */
@Composable
private fun GlassQualityPicker(current: GlassQuality, onPick: (GlassQuality) -> Unit) {
    Column {
        SelectRow("Glass quality", listOf("High", "Balanced", "Performance", "Off"), current.ordinal) {
            onPick(GlassQuality.entries[it])
        }
        Text(
            when (current) {
                GlassQuality.HIGH -> "Full live blur + lens refraction (refraction needs Android 13+, blur Android 12+)."
                GlassQuality.BALANCED -> "Live blur + lens refraction, slightly lighter. Recommended."
                GlassQuality.PERFORMANCE -> "Tinted see-through panels without live blur or shaders. Lightest on battery."
                GlassQuality.OFF -> "Glass themes use plain opaque surfaces."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        // Honest status: exactly what the navigation bar / mini-player draw on THIS device now.
        val env = com.example.mimic.ui.designsystem.LocalGlass.current
        val mode = if (!env.style.glass) null else com.example.mimic.ui.designsystem.glassRenderMode(env, hasBackdrop = true)
        Text(
            if (mode == null) "Rendering now: the active theme is not a glass theme." else "Rendering now: ${mode.label}.",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }
}

/** Big live card for the MImic Liquid Glass flagship, rendered in its own theme. */
@Composable
private fun FlagshipCard(
    theme: ThemeDefinition,
    quality: GlassQuality,
    selected: Boolean,
    onApply: () -> Unit,
    onCustomize: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        MImicTheme(theme = theme, quality = quality) {
            ThemeLivePreview(Modifier.height(240.dp).clickable(onClick = onApply))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(theme.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "Liquid glass that re-tints itself from the playing album",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary)
            } else {
                TextButton(onClick = onApply) { Text("Apply") }
            }
            TextButton(onClick = onCustomize) { Text("Customize") }
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
            val tags = buildList {
                add(if (theme.colors.isDark) "Dark" else "Light")
                if (theme.style.glass) add("Glass")
                if (theme.style.albumAdaptive) add("Adaptive")
                add(if (theme.builtIn) "Built-in" else "Custom")
            }
            Text(
                tags.joinToString(" · "),
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
                DropdownMenuItem(text = { Text(if (theme.builtIn) "Customize (copy)" else "Duplicate & edit") }, onClick = { menu = false; onDuplicate() })
                if (!theme.builtIn) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                }
                DropdownMenuItem(text = { Text("Export to file") }, onClick = { menu = false; onExportFile() })
                DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; onShare() })
                if (!theme.builtIn) {
                    DropdownMenuItem(text = { Text("Delete…") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

private enum class EditorSection(val label: String) {
    COLORS("Colors"), GLASS("Glass"), SHAPES("Shapes"), TEXT("Text"), MOTION("Motion"), NAV("Navigation")
}

@Composable
fun ThemeEditorScreen(
    initial: ThemeDefinition,
    onSave: (ThemeDefinition, Boolean) -> Unit,
    onExport: (ThemeDefinition) -> Unit,
    onBack: () -> Unit,
    quality: GlassQuality = GlassQuality.BALANCED,
) {
    var draft by remember { mutableStateOf(initial) }
    var picking by remember { mutableStateOf<String?>(null) } // token key being edited
    var section by remember { mutableStateOf(EditorSection.COLORS) }

    fun style(block: ThemeStyle.() -> ThemeStyle) {
        draft = draft.copy(style = draft.style.block())
    }

    fun setColor(key: String, value: Long) {
        val c = draft.colors
        when (key) {
            "accent" -> style { copy(accentColor = value) }
            "gradient" -> style { copy(gradientColor = value) }
            "tint" -> style { copy(glassTint = value) }
            else -> draft = draft.copy(
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
    }

    fun colorOf(key: String): Long = with(draft.colors) {
        when (key) {
            "primary" -> primary; "secondary" -> secondary; "background" -> background
            "surface" -> surface; "surfaceElevated" -> surfaceElevated
            "textPrimary" -> textPrimary; "accent" -> draft.style.accentColor
            "gradient" -> draft.style.gradientColor; "tint" -> draft.style.glassTint
            else -> textSecondary
        }
    }

    Column(Modifier.fillMaxSize()) {
        StudioHeader("Edit theme", onBack, actions = {
            IconButton(onClick = { onExport(draft) }) { Icon(Icons.Default.FileDownload, contentDescription = "Export") }
        })
        // Live preview: the draft rendered through the real theme pipeline and real components.
        // Shorter on small phones so the controls below keep most of the screen.
        val previewHeight = if (LocalConfiguration.current.screenHeightDp < 720) 160.dp else 210.dp
        MImicTheme(theme = draft, quality = quality) {
            ThemeLivePreview(Modifier.padding(horizontal = 16.dp).height(previewHeight))
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EditorSection.entries.forEach { s ->
                val on = s == section
                Box(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { section = s }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        s.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val st = draft.style
            when (section) {
                EditorSection.COLORS -> {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it.take(60)) },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                    ToggleRow("Dark theme", draft.colors.isDark) { draft = draft.copy(colors = draft.colors.copy(isDark = it)) }
                    ToggleRow("Album-adaptive colors", st.albumAdaptive) { v -> style { copy(albumAdaptive = v) } }
                    Hint("Re-tints the theme from the playing song's artwork, keeping text readable.")
                    listOf(
                        "primary" to "Primary", "secondary" to "Secondary", "background" to "Background",
                        "surface" to "Surface", "surfaceElevated" to "Elevated surface",
                        "textPrimary" to "Text", "textSecondary" to "Secondary text",
                    ).forEach { (key, label) -> ColorTokenRow(label, colorOf(key)) { picking = key } }
                    ColorTokenRow("Accent", colorOf("accent"), auto = st.accentColor == 0L, onReset = { style { copy(accentColor = 0L) } }) { picking = "accent" }
                    ToggleRow("Gradient background", st.gradientBackground) { v -> style { copy(gradientBackground = v) } }
                    if (st.gradientBackground) {
                        ColorTokenRow("Gradient color", colorOf("gradient"), auto = st.gradientColor == 0L, onReset = { style { copy(gradientColor = 0L) } }) { picking = "gradient" }
                    }
                }
                EditorSection.GLASS -> {
                    ToggleRow("Liquid Glass surfaces", st.glass) { v -> style { copy(glass = v) } }
                    Hint("Applies to the navigation bar, mini-player and player controls only — lists stay fast.")
                    LabeledSlider("Transparency", 1f - st.glassOpacity, 0f..1f, percent = true) { v -> style { copy(glassOpacity = 1f - v) } }
                    LabeledSlider("Blur intensity", st.blurRadius.toFloat(), 0f..48f, suffix = "dp") { v -> style { copy(blurRadius = v.roundToInt()) } }
                    Hint("Low blur keeps a minimum frost so text on glass always stays readable.")
                    LabeledSlider("Refraction", st.refraction, 0f..1f, percent = true) { v -> style { copy(refraction = v) } }
                    Hint("Refraction needs Android 13+ and Glass quality \"High\". Blur needs Android 12+.")
                    LabeledSlider("Edge highlight", st.edgeHighlight, 0f..1f, percent = true) { v -> style { copy(edgeHighlight = v) } }
                    LabeledSlider("Shadow", st.shadow, 0f..1f, percent = true) { v -> style { copy(shadow = v) } }
                    SelectRow("Glass tone", listOf("Auto", "Light glass", "Dark glass"), st.glassTone.ordinal) { i -> style { copy(glassTone = GlassTone.entries[i]) } }
                    ColorTokenRow("Glass tint", colorOf("tint"), auto = st.glassTint == 0L, onReset = { style { copy(glassTint = 0L) } }) { picking = "tint" }
                }
                EditorSection.SHAPES -> {
                    LabeledSlider("Card corner radius", st.cornerRadius.toFloat(), 0f..40f, suffix = "dp") { v -> style { copy(cornerRadius = v.roundToInt()) } }
                    LabeledSlider("Navigation corner radius", st.navCornerRadius.toFloat(), 0f..40f, suffix = "dp") { v -> style { copy(navCornerRadius = v.roundToInt()) } }
                    SelectRow("Buttons", listOf("Rounded", "Capsule", "Squircle"), st.controlShape.ordinal) { i -> style { copy(controlShape = ControlShape.entries[i]) } }
                    ShapeSample(st)
                    Hint("Artwork shape and size are in Settings → Player Layout.")
                }
                EditorSection.TEXT -> {
                    SelectRow("Font", listOf("System", "Sans", "Serif", "Mono", "Cursive"), st.fontFamily.ordinal) { i -> style { copy(fontFamily = FontChoice.entries[i]) } }
                    LabeledSlider("Font size", st.fontScale, 0.85f..1.25f, percent = true) { v -> style { copy(fontScale = v) } }
                    WeightRow("Headers", st.headerWeight) { w -> style { copy(headerWeight = w) } }
                    WeightRow("Song titles", st.titleWeight) { w -> style { copy(titleWeight = w) } }
                    WeightRow("Artist labels", st.artistWeight) { w -> style { copy(artistWeight = w) } }
                    Hint("Uses the fonts built into Android — nothing is downloaded.")
                }
                EditorSection.MOTION -> {
                    ToggleRow("Motion", st.motion) { v -> style { copy(motion = v) } }
                    if (LocalGlass.current.reducedMotion) Hint("Android's \"Remove animations\" is on, so motion stays off everywhere.")
                    LabeledSlider("Animation intensity", st.animationScale, 0f..1.5f, percent = true) { v -> style { copy(animationScale = v) } }
                    LabeledSlider("Transition speed", st.transitionSpeed, 0.5f..2f, suffix = "×") { v -> style { copy(transitionSpeed = v) } }
                    Hint("Tap the tabs in the preview to see the selection animation.")
                }
                EditorSection.NAV -> {
                    ToggleRow("Floating glass navigation", st.pillNav) { v -> style { copy(pillNav = v) } }
                    Hint("Used when Settings → Home & Navigation is set to \"Auto (theme)\".")
                    SelectRow("Tab indicator", listOf("Pill", "Dot"), st.navIndicator.ordinal) { i -> style { copy(navIndicator = NavIndicator.entries[i]) } }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val clean = { draft.copy(name = draft.name.trim().ifEmpty { "My Theme" }, style = draft.style.sanitized()) }
            OutlinedButton(onClick = { onSave(clean(), false); onBack() }, modifier = Modifier.weight(1f)) {
                Text("Save")
            }
            Button(onClick = { onSave(clean(), true); onBack() }, modifier = Modifier.weight(1f)) {
                Text("Save & apply")
            }
        }
    }

    picking?.let { key ->
        val current = colorOf(key).takeIf { it != 0L } ?: draft.colors.primary
        ColorPickerDialog(
            initial = current,
            onDismiss = { picking = null },
            onPick = { setColor(key, it); picking = null },
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

@Composable
private fun ColorTokenRow(
    label: String,
    argb: Long,
    auto: Boolean = false,
    onReset: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape)
                .background(if (auto) MaterialTheme.colorScheme.surfaceVariant else Color(argb))
                .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f), CircleShape),
        )
        Spacer(Modifier.width(16.dp))
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
        Text(if (auto) "Auto" else ThemeSerialization.toHex(argb), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onReset != null && !auto) {
            TextButton(onClick = onReset) { Text("Auto") }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    percent: Boolean = false,
    suffix: String = "",
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
        Row {
            Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodyMedium)
            Text(
                when {
                    percent -> "${(value * 100).roundToInt()}%"
                    suffix == "×" -> "%.1f×".format(value)
                    else -> "${value.roundToInt()}$suffix"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

private val WEIGHTS = listOf(300 to "Light", 400 to "Regular", 500 to "Medium", 700 to "Bold", 900 to "Black")

@Composable
private fun WeightRow(label: String, weight: Int, onPick: (Int) -> Unit) {
    val idx = WEIGHTS.indexOfFirst { it.first >= weight }.let { if (it < 0) WEIGHTS.lastIndex else it }
    SelectRow(label, WEIGHTS.map { it.second }, idx) { onPick(WEIGHTS[it].first) }
}

@Composable
private fun ShapeSample(style: ThemeStyle) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.clip(style.controlShapeValue()).background(MaterialTheme.colorScheme.primary).padding(horizontal = 20.dp, vertical = 10.dp),
        ) { Text("Button", color = MaterialTheme.colorScheme.onPrimary) }
        Box(
            Modifier.size(width = 96.dp, height = 44.dp)
                .clip(RoundedCornerShape((style.cornerRadius * 0.67f).dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Text("Card", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private val previewSong = Song(
    id = -1L, title = "Sample Track", artist = "Sample Artist", album = "", durationMs = 200_000L,
    uri = "", albumArtUri = null, dateAddedSec = 0L,
)

private val previewTabs = listOf(
    GlassNavItem("Home", Icons.Default.Home),
    GlassNavItem("Search", Icons.Default.Search),
    GlassNavItem("Library", Icons.Default.LibraryMusic),
)

/**
 * A miniature app screen in the *current* MaterialTheme / LocalGlass: colorful "album" blocks and
 * list rows as the backdrop, with the REAL MiniPlayer and GlassNavBar floating over it, so glass
 * opacity, blur, refraction, shapes, fonts and motion are all previewed exactly as they render.
 */
@Composable
fun ThemeLivePreview(modifier: Modifier = Modifier) {
    val env = LocalGlass.current
    val scheme = MaterialTheme.colorScheme
    val backdrop = rememberLayerBackdrop()
    val live = env.enabled && env.quality.blurFactor > 0f && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    var tab by remember { mutableIntStateOf(0) }
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
        // Backdrop content (what the glass frosts over).
        Box(
            Modifier.fillMaxSize()
                .then(if (live) Modifier.layerBackdrop(backdrop) else Modifier)
                .background(
                    if (env.style.gradientBackground) {
                        val top = if (env.style.gradientColor != 0L) Color(env.style.gradientColor) else androidx.compose.ui.graphics.lerp(scheme.background, scheme.primary, 0.22f)
                        Brush.verticalGradient(listOf(top, scheme.background))
                    } else {
                        Brush.verticalGradient(listOf(scheme.background, scheme.background))
                    },
                ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Good evening", style = MaterialTheme.typography.titleLarge, fontWeight = env.headerFontWeight, color = scheme.onBackground)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.primary.copy(alpha = 0.6f)).forEach { c ->
                        Box(
                            Modifier.size(64.dp)
                                .clip(RoundedCornerShape((env.style.cornerRadius * 0.45f).dp))
                                .background(Brush.linearGradient(listOf(c, c.copy(alpha = 0.35f)))),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                listOf("Midnight Drive", "Glass Hearts").forEach { t ->
                    Text(t, style = MaterialTheme.typography.bodyLarge, fontWeight = env.titleFontWeight, color = scheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Sample Artist", style = MaterialTheme.typography.bodySmall, fontWeight = env.artistFontWeight, color = scheme.onSurfaceVariant)
                }
            }
        }
        // Floating glass chrome, sampling the backdrop above.
        CompositionLocalProvider(LocalGlassBackdrop provides if (live) backdrop else null) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                MiniPlayer(
                    song = previewSong,
                    isPlaying = true,
                    onPlayPause = {},
                    onClick = {},
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                GlassNavBar(previewTabs, tab, { tab = it }, showLabels = true)
            }
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
    val controller = rememberColorPickerController()
    // Push a typed/slider color into the wheel without it echoing back as a user change.
    fun syncWheel() = controller.selectByColor(Color(argb() or 0xFF000000L), fromUser = false)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick color") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Box(
                    Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp)).background(Color(argb())),
                )
                Spacer(Modifier.height(12.dp))
                HsvColorPicker(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    controller = controller,
                    initialColor = Color(initial or 0xFF000000L),
                    onColorChanged = { env ->
                        if (env.fromUser) {
                            val c = env.color
                            r = (c.red * 255).roundToInt(); g = (c.green * 255).roundToInt(); b = (c.blue * 255).roundToInt()
                            hex = ThemeSerialization.toHex(argb())
                        }
                    },
                )
                Spacer(Modifier.height(10.dp))
                BrightnessSlider(modifier = Modifier.fillMaxWidth().height(28.dp), controller = controller)
                Spacer(Modifier.height(10.dp))
                ChannelSlider("R", r) { r = it; hex = ThemeSerialization.toHex(argb()); syncWheel() }
                ChannelSlider("G", g) { g = it; hex = ThemeSerialization.toHex(argb()); syncWheel() }
                ChannelSlider("B", b) { b = it; hex = ThemeSerialization.toHex(argb()); syncWheel() }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { text ->
                        hex = text
                        runCatching { ThemeSerialization.parseHex(text) }.onSuccess { parsed ->
                            r = ((parsed shr 16) and 0xFF).toInt()
                            g = ((parsed shr 8) and 0xFF).toInt()
                            b = (parsed and 0xFF).toInt()
                            syncWheel()
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
        Slider(
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

