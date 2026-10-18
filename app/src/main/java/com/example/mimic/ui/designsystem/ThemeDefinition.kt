package com.example.mimic.ui.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Token-based color definition for a theme. Colors are stored as ARGB [Long]s so a
 * theme is a plain data value — serializable to JSON for import/export, and
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

/** System font families only — always available, nothing to license or download. */
enum class FontChoice { DEFAULT, SANS, SERIF, MONO, CURSIVE }

/** Silhouette of buttons and chips. SQUIRCLE uses continuous-curvature (G2) corners. */
enum class ControlShape { ROUNDED, CAPSULE, SQUIRCLE }

/** How the selected tab is marked in the glass navigation bar. */
enum class NavIndicator { PILL, DOT }

/** Whether glass panels frost toward white, toward black, or follow the theme's darkness. */
enum class GlassTone { AUTO, LIGHT, DARK }

/**
 * Visual *style* of a theme beyond its colors (MImic 2.9). All fields default to the
 * pre-2.9 flat look, so every existing preset, custom theme and v1 JSON import keeps
 * rendering exactly as before. Glass themes opt in by setting [glass] = true.
 *
 * Values are intentionally plain scalars so the editor can bind sliders/switches to them
 * and serialization stays trivial. [sanitized] clamps everything to safe ranges — never
 * trust an imported value.
 */
data class ThemeStyle(
    // ---- Glass ----
    val glass: Boolean = false,
    val glassOpacity: Float = 0.6f,      // frosted-surface tint alpha, 0 (clear) .. 1 (solid)
    val blurRadius: Int = 24,            // backdrop blur (dp) at HIGH quality, API 31+
    val refraction: Float = 0f,          // lens refraction strength 0..1, API 33+ at HIGH quality
    val edgeHighlight: Float = 0.6f,     // rim-light intensity on glass edges, 0 .. 1
    val shadow: Float = 0.5f,            // drop-shadow intensity under glass, 0 .. 1
    val glassTint: Long = 0L,            // 0 => derive from the surface color; else ARGB tint
    val glassTone: GlassTone = GlassTone.AUTO,
    // ---- Colors beyond the core tokens ----
    val accentColor: Long = 0L,          // 0 => secondary; maps to Material tertiary
    val gradientBackground: Boolean = false,
    val gradientColor: Long = 0L,        // 0 => derive from surface; else 2nd gradient stop (ARGB)
    val albumAdaptive: Boolean = false,  // re-tint the scheme from the playing song's artwork
    // ---- Shapes ----
    val cornerRadius: Int = 18,          // cards & surfaces (dp); 18 reproduces the pre-2.9 radii
    val navCornerRadius: Int = 28,       // floating / glass navigation container (dp)
    val controlShape: ControlShape = ControlShape.ROUNDED,
    // ---- Navigation ----
    val pillNav: Boolean = false,        // theme asks for the floating glass pill navigation
    val navIndicator: NavIndicator = NavIndicator.PILL,
    // ---- Typography ----
    val fontFamily: FontChoice = FontChoice.DEFAULT,
    val fontScale: Float = 1f,
    val headerWeight: Int = 700,         // screen/section headers
    val titleWeight: Int = 400,          // song titles in lists, cards and the mini-player
    val artistWeight: Int = 400,         // artist / secondary labels
    // ---- Motion ----
    val motion: Boolean = true,
    val animationScale: Float = 1f,      // press/selection intensity multiplier, 0 .. 1.5
    val transitionSpeed: Float = 1f,     // >1 faster, <1 slower; 0.5 .. 2
) {
    fun sanitized(): ThemeStyle = copy(
        glassOpacity = glassOpacity.finiteOr(0.6f).coerceIn(0f, 1f),
        blurRadius = blurRadius.coerceIn(0, 48),
        refraction = refraction.finiteOr(0f).coerceIn(0f, 1f),
        edgeHighlight = edgeHighlight.finiteOr(0.6f).coerceIn(0f, 1f),
        shadow = shadow.finiteOr(0.5f).coerceIn(0f, 1f),
        cornerRadius = cornerRadius.coerceIn(0, 40),
        navCornerRadius = navCornerRadius.coerceIn(0, 40),
        fontScale = fontScale.finiteOr(1f).coerceIn(0.85f, 1.25f),
        headerWeight = headerWeight.coerceIn(100, 900) / 100 * 100,
        titleWeight = titleWeight.coerceIn(100, 900) / 100 * 100,
        artistWeight = artistWeight.coerceIn(100, 900) / 100 * 100,
        animationScale = animationScale.finiteOr(1f).coerceIn(0f, 1.5f),
        transitionSpeed = transitionSpeed.finiteOr(1f).coerceIn(0.5f, 2f),
    )
}

private fun Float.finiteOr(fallback: Float) = if (isFinite()) this else fallback

data class ThemeDefinition(
    val id: String,
    val name: String,
    val builtIn: Boolean,
    val colors: ThemeColors,
    val style: ThemeStyle = ThemeStyle(),
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
 * Registry of the built-in theme presets: the MImic Liquid Glass flagship, then the 14 canonical
 * MImic 2.9 presets ([CANONICAL_IDS]), then earlier presets kept so no one's previously-applied
 * theme ever disappears.
 * IDs are stable — renaming a preset's display label is safe, changing its id is not.
 */
object ThemeRegistry {

    // MImic 3.0 glass tuning: light tint + light blur + strong lens, so the content behind the
    // nav / mini-player stays visible. (2.9 used 42-64% tint over 24-30dp blur => solid-looking.)

    /** Clear: barely tinted, barely blurred — the content behind is plainly visible, bent at the rim. */
    private val GLASS_CLEAR = ThemeStyle(
        glass = true, glassOpacity = 0.12f, blurRadius = 4, refraction = 1f, edgeHighlight = 0.9f,
        shadow = 0.35f, cornerRadius = 26, controlShape = ControlShape.CAPSULE, gradientBackground = true,
        gradientColor = 0xFFCFE0F7, pillNav = true, animationScale = 1.15f, headerWeight = 800,
        glassTone = GlassTone.LIGHT, titleWeight = 500,
    )

    /** Frosted: noticeably blurred content behind, medium translucency. */
    private val GLASS_FROSTED = ThemeStyle(
        glass = true, glassOpacity = 0.3f, blurRadius = 20, refraction = 0.45f, edgeHighlight = 0.6f,
        cornerRadius = 24, controlShape = ControlShape.SQUIRCLE, gradientBackground = true,
        gradientColor = 0xFF1C2A3A, pillNav = true, animationScale = 1.1f, titleWeight = 500,
    )

    /** Dark: a smoky dark tint that still lets the colors behind glow through. */
    private val GLASS_DARK = ThemeStyle(
        glass = true, glassOpacity = 0.36f, blurRadius = 8, refraction = 0.75f, edgeHighlight = 0.7f,
        cornerRadius = 24, controlShape = ControlShape.CAPSULE, gradientBackground = true,
        gradientColor = 0xFF0E1E33, pillNav = true, animationScale = 1.1f, glassTone = GlassTone.DARK,
        titleWeight = 500,
    )

    /** The MImic flagship: dark liquid glass that re-tints itself from the playing album. */
    private val GLASS_MIMIC = ThemeStyle(
        glass = true, glassOpacity = 0.2f, blurRadius = 10, refraction = 0.85f, edgeHighlight = 0.8f,
        shadow = 0.55f, cornerRadius = 26, navCornerRadius = 32, controlShape = ControlShape.SQUIRCLE,
        gradientBackground = true, albumAdaptive = true, pillNav = true, animationScale = 1.15f,
        headerWeight = 800, titleWeight = 500,
    )

    private fun dark(
        id: String, name: String, primary: Long, secondary: Long,
        background: Long, surface: Long, elevated: Long,
        text: Long = 0xFFFFFFFF, text2: Long = 0xFFB3B3B3, style: ThemeStyle = ThemeStyle(),
    ) = ThemeDefinition(id, name, true, ThemeColors(primary, secondary, background, surface, elevated, text, text2, true), style)

    private fun light(
        id: String, name: String, primary: Long, secondary: Long,
        background: Long, surface: Long, elevated: Long,
        text: Long = 0xFF101418, text2: Long = 0xFF55657A, style: ThemeStyle = ThemeStyle(),
    ) = ThemeDefinition(id, name, true, ThemeColors(primary, secondary, background, surface, elevated, text, text2, false), style)

    val presets: List<ThemeDefinition> = listOf(
        // ---- MImic Liquid Glass flagship, then the 14 canonical MImic 2.9 presets ----
        dark("mimic_liquid_glass", "MImic Liquid Glass", 0xFF8AB4FF, 0xFFB79CFF, 0xFF080B12, 0xFF121826, 0xFF1C2436, text = 0xFFF3F6FC, text2 = 0xFFAFB9CC, style = GLASS_MIMIC),
        light("ios_glass_clear", "Liquid Glass Clear", 0xFF0A84FF, 0xFF64D2FF, 0xFFEEF3FB, 0xFFFFFFFF, 0xFFE3ECF7, text = 0xFF0B1725, text2 = 0xFF5A6B82, style = GLASS_CLEAR),
        dark("frosted_glass", "Liquid Glass Frosted", 0xFF7FD1FF, 0xFF5AA9E6, 0xFF0E1116, 0xFF161B22, 0xFF222A35, text2 = 0xFFAEBBC9, style = GLASS_FROSTED),
        dark("ios_dark_glass", "Liquid Glass Dark", 0xFF0A84FF, 0xFF64D2FF, 0xFF07090D, 0xFF10151C, 0xFF1A222D, text = 0xFFF2F6FB, text2 = 0xFFA9B6C6, style = GLASS_DARK),
        dark("amoled_black", "AMOLED Pure Black", 0xFF1DB954, 0xFF1DB954, 0xFF000000, 0xFF0A0A0A, 0xFF161616),
        dark("amoled_purple", "AMOLED Purple", 0xFFB388FF, 0xFF7C4DFF, 0xFF000000, 0xFF0B0710, 0xFF160F1F, text2 = 0xFFB9A8D6, style = ThemeStyle(controlShape = ControlShape.CAPSULE)),
        dark("emerald", "Classic Music Dark", 0xFF1DB954, 0xFF17A34A, 0xFF121212, 0xFF181818, 0xFF242424),
        light("arctic_light", "Classic Music Light", 0xFF1DB954, 0xFF16A34A, 0xFFFAFCFF, 0xFFFFFFFF, 0xFFECF2F8),
        dark("album_adaptive", "Album Adaptive", 0xFF1DB954, 0xFF17A34A, 0xFF121212, 0xFF181818, 0xFF242424, style = ThemeStyle(albumAdaptive = true, gradientBackground = true)),
        dark("neon_cyberpunk", "Neon Cyberpunk", 0xFFFF2E97, 0xFF00E5FF, 0xFF07020C, 0xFF130320, 0xFF20073A, text = 0xFFF6E9FF, text2 = 0xFFC08AD6, style = ThemeStyle(accentColor = 0xFFFFE600, gradientBackground = true, gradientColor = 0xFF1A0533, cornerRadius = 14, fontFamily = FontChoice.MONO, controlShape = ControlShape.SQUIRCLE)),
        dark("midnight_blue", "Midnight Blue", 0xFF4F8BFF, 0xFF6FA0FF, 0xFF0A0F1F, 0xFF111830, 0xFF1A2542, text2 = 0xFF9FB0D0),
        dark("sunset", "Sunset Gradient", 0xFFFF7043, 0xFFFFB300, 0xFF1A0F0A, 0xFF2A160E, 0xFF3A1F14, text = 0xFFFFF3EC, text2 = 0xFFD7A08A, style = ThemeStyle(gradientBackground = true, gradientColor = 0xFF3A1F14, cornerRadius = 20)),
        dark("minimal_mono", "Minimal Monochrome", 0xFFFFFFFF, 0xFFB3B3B3, 0xFF101010, 0xFF1A1A1A, 0xFF262626, text2 = 0xFF9A9A9A, style = ThemeStyle(cornerRadius = 6, headerWeight = 500, animationScale = 0.6f)),
        dark("premium_gold", "Premium Gold", 0xFFE8C06A, 0xFFC9A24B, 0xFF0C0A06, 0xFF171308, 0xFF241C0C, text = 0xFFF8F0DC, text2 = 0xFFCBB789, style = ThemeStyle(gradientBackground = true, gradientColor = 0xFF241C0C, cornerRadius = 20, fontFamily = FontChoice.SERIF, accentColor = 0xFFF5DEA0, controlShape = ControlShape.CAPSULE)),
        dark("material_you", "Material 3 Expressive", 0xFF9FCAFF, 0xFFB9C7DB, 0xFF1A1C1E, 0xFF202325, 0xFF2B2F33, text = 0xFFE3E2E6, text2 = 0xFFA8ABB0, style = ThemeStyle(cornerRadius = 28, controlShape = ControlShape.CAPSULE, accentColor = 0xFFFFB4AB, headerWeight = 800, animationScale = 1.3f)),
        // ---- Earlier presets retained so previously-applied themes never vanish ----
        dark("ocean_neon", "Ocean Neon", 0xFF00E5FF, 0xFF2E77D0, 0xFF07131A, 0xFF0E1E29, 0xFF143041, text2 = 0xFF9FB3C8),
        dark("rose_gold", "Rose Gold", 0xFFB76E79, 0xFFE8A2A2, 0xFF171013, 0xFF22171B, 0xFF301F26, text = 0xFFF6E7EC, text2 = 0xFFC79FAC),
        dark("purple_galaxy", "Purple Galaxy", 0xFF8E44EF, 0xFFC06BFF, 0xFF0E0A1A, 0xFF171026, 0xFF221634, text2 = 0xFFBCA9E0),
        dark("retro_vinyl", "Retro Vinyl", 0xFFD9A441, 0xFF8C5A2B, 0xFF17120B, 0xFF241B10, 0xFF332717, text = 0xFFF4E8D0, text2 = 0xFFC0A878),
        dark("high_contrast", "High Contrast", 0xFFFFEB3B, 0xFFFFFFFF, 0xFF000000, 0xFF000000, 0xFF141414, text = 0xFFFFFFFF, text2 = 0xFFFFFFFF),
    )

    private val byId = presets.associateBy { it.id }
    const val DEFAULT_ID = "emerald"

    /** The 14 presets from the 2.9 brief, in display order. */
    val CANONICAL_IDS = listOf(
        "ios_glass_clear", "frosted_glass", "ios_dark_glass", "amoled_black", "amoled_purple",
        "emerald", "arctic_light", "album_adaptive", "neon_cyberpunk", "midnight_blue",
        "sunset", "minimal_mono", "premium_gold", "material_you",
    )

    fun byId(id: String?): ThemeDefinition = byId[id] ?: byId.getValue(DEFAULT_ID)
}
