package com.example.mimic.ui.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Token-based color definition for a theme. Colors are stored as ARGB [Long]s so a
 * theme is a plain data value — serializable to JSON for import/export later, and
 * unit-testable without Android. [toColorScheme] maps the tokens to a Material 3 scheme.
 */
data class ThemeColors(
    val primary: Long,
    val secondary: Long,
    val background: Long,
    val surface: Long,
    val surfaceElevated: Long,
    val textPrimary: Long,
    val textSecondary: Long,
    val isDark: Boolean,
)

data class ThemeDefinition(
    val id: String,
    val name: String,
    val builtIn: Boolean,
    val colors: ThemeColors,
)

fun ThemeColors.toColorScheme(): ColorScheme {
    val primary = Color(primary)
    val secondary = Color(secondary)
    val background = Color(background)
    val surface = Color(surface)
    val surfaceElevated = Color(surfaceElevated)
    val textPrimary = Color(textPrimary)
    val textSecondary = Color(textSecondary)
    val onPrimary = if (isDark) Color.Black else Color.White
    return if (isDark) {
        darkColorScheme(
            primary = primary, onPrimary = onPrimary, primaryContainer = primary.copy(alpha = 0.3f),
            secondary = secondary, background = background, onBackground = textPrimary,
            surface = surface, onSurface = textPrimary, surfaceVariant = surfaceElevated,
            onSurfaceVariant = textSecondary,
        )
    } else {
        lightColorScheme(
            primary = primary, onPrimary = onPrimary, primaryContainer = primary.copy(alpha = 0.18f),
            secondary = secondary, background = background, onBackground = textPrimary,
            surface = surface, onSurface = textPrimary, surfaceVariant = surfaceElevated,
            onSurfaceVariant = textSecondary,
        )
    }
}

/**
 * Registry of the built-in theme presets. Custom user themes (editor, import/export)
 * are a later milestone; this registry is the stable foundation they will extend.
 */
object ThemeRegistry {

    private fun dark(
        id: String, name: String, primary: Long, secondary: Long,
        background: Long, surface: Long, elevated: Long,
        text: Long = 0xFFFFFFFF, text2: Long = 0xFFB3B3B3,
    ) = ThemeDefinition(id, name, true, ThemeColors(primary, secondary, background, surface, elevated, text, text2, true))

    private fun light(
        id: String, name: String, primary: Long, secondary: Long,
        background: Long, surface: Long, elevated: Long,
        text: Long = 0xFF101418, text2: Long = 0xFF55657A,
    ) = ThemeDefinition(id, name, true, ThemeColors(primary, secondary, background, surface, elevated, text, text2, false))

    val presets: List<ThemeDefinition> = listOf(
        dark("amoled_black", "AMOLED Black", 0xFF1DB954, 0xFF1DB954, 0xFF000000, 0xFF0A0A0A, 0xFF161616),
        dark("emerald", "Emerald", 0xFF1DB954, 0xFF17A34A, 0xFF121212, 0xFF181818, 0xFF242424),
        dark("ocean_neon", "Ocean Neon", 0xFF00E5FF, 0xFF2E77D0, 0xFF07131A, 0xFF0E1E29, 0xFF143041, text2 = 0xFF9FB3C8),
        dark("sunset", "Sunset", 0xFFFF7043, 0xFFFFB300, 0xFF1A0F0A, 0xFF2A160E, 0xFF3A1F14, text = 0xFFFFF3EC, text2 = 0xFFD7A08A),
        dark("rose_gold", "Rose Gold", 0xFFB76E79, 0xFFE8A2A2, 0xFF171013, 0xFF22171B, 0xFF301F26, text = 0xFFF6E7EC, text2 = 0xFFC79FAC),
        light("arctic_light", "Arctic Light", 0xFF2E77D0, 0xFF4F8BFF, 0xFFFAFCFF, 0xFFFFFFFF, 0xFFECF2F8),
        dark("purple_galaxy", "Purple Galaxy", 0xFF8E44EF, 0xFFC06BFF, 0xFF0E0A1A, 0xFF171026, 0xFF221634, text2 = 0xFFBCA9E0),
        dark("midnight_blue", "Midnight Blue", 0xFF4F8BFF, 0xFF6FA0FF, 0xFF0A0F1F, 0xFF111830, 0xFF1A2542, text2 = 0xFF9FB0D0),
        dark("retro_vinyl", "Retro Vinyl", 0xFFD9A441, 0xFF8C5A2B, 0xFF17120B, 0xFF241B10, 0xFF332717, text = 0xFFF4E8D0, text2 = 0xFFC0A878),
        dark("minimal_mono", "Minimal Monochrome", 0xFFFFFFFF, 0xFFB3B3B3, 0xFF101010, 0xFF1A1A1A, 0xFF262626, text2 = 0xFF9A9A9A),
        dark("material_you", "Material You", 0xFF9FCAFF, 0xFFB9C7DB, 0xFF1A1C1E, 0xFF202325, 0xFF2B2F33, text = 0xFFE3E2E6, text2 = 0xFFA8ABB0),
        dark("album_adaptive", "Album Adaptive", 0xFF1DB954, 0xFF17A34A, 0xFF121212, 0xFF181818, 0xFF242424),
        dark("frosted_glass", "Frosted Glass", 0xFF7FD1FF, 0xFF5AA9E6, 0xFF0E1116, 0xFF161B22, 0xFF222A35, text2 = 0xFFAEBBC9),
        dark("high_contrast", "High Contrast", 0xFFFFEB3B, 0xFFFFFFFF, 0xFF000000, 0xFF000000, 0xFF141414, text = 0xFFFFFFFF, text2 = 0xFFFFFFFF),
    )

    private val byId = presets.associateBy { it.id }
    const val DEFAULT_ID = "emerald"

    fun byId(id: String?): ThemeDefinition = byId[id] ?: byId.getValue(DEFAULT_ID)
}
