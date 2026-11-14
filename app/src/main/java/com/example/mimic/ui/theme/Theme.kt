package com.example.mimic.ui.theme

import android.provider.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.example.mimic.data.GlassQuality
import com.example.mimic.ui.designsystem.AlbumColors
import com.example.mimic.ui.designsystem.DepthStyle
import com.example.mimic.ui.designsystem.LocalDepth
import com.example.mimic.ui.designsystem.depthPalette
import com.example.mimic.ui.designsystem.FontChoice
import com.example.mimic.ui.designsystem.GlassEnv
import com.example.mimic.ui.designsystem.LocalGlass
import com.example.mimic.ui.designsystem.ThemeDefinition
import com.example.mimic.ui.designsystem.ThemeStyle
import com.example.mimic.ui.designsystem.toColorScheme

/**
 * Applies a [ThemeDefinition]: colors (optionally re-tinted from the playing album via
 * [adaptiveSeed]), shapes and typography from its [ThemeStyle], and the [GlassEnv] that glass
 * components read. Every derived value is remembered on its true inputs, so a playback tick or a
 * list scroll never rebuilds the scheme.
 */
@Composable
fun MImicTheme(
    theme: ThemeDefinition,
    quality: GlassQuality = GlassQuality.OFF,
    adaptiveSeed: Int? = null,
    content: @Composable () -> Unit,
) {
    val style = remember(theme.style) { theme.style.sanitized() }
    val seed = adaptiveSeed.takeIf { style.albumAdaptive }
    val colors = remember(theme.colors, seed) {
        if (seed == null) {
            theme.colors
        } else {
            AlbumColors.themeColors(seed, theme.colors.isDark, amoled = theme.colors.background == 0xFF000000L)
        }
    }
    val scheme = remember(colors, style.accentColor, style.depth) {
        val base = colors.toColorScheme()
        val accented = if (style.accentColor != 0L) base.copy(tertiary = Color(style.accentColor)) else base.copy(tertiary = base.secondary)
        if (style.depth == DepthStyle.FLAT) accented else depthSurfaces(accented, colors.isDark, style.depth)
    }
    val depth = remember(style.depth, style.depthStrength, colors) {
        depthPalette(style.depth, style.depthStrength, colors.isDark, colors.surface.toInt(), colors.primary.toInt())
    }
    val typography = remember(style.fontFamily, style.fontScale) { typographyFor(style) }
    val shapes = remember(style.cornerRadius) { shapesFor(style) }
    val context = LocalContext.current
    val reducedMotion = remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val env = remember(style, quality, colors.isDark, reducedMotion) {
        GlassEnv(style = style, quality = quality, isDarkTheme = colors.isDark, reducedMotion = reducedMotion)
    }
    CompositionLocalProvider(LocalGlass provides env, LocalDepth provides depth) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            shapes = shapes,
            content = content,
        )
    }
}

/**
 * 3D themes (4.8): dialogs, menus, bottom sheets and Material containers take the page's own
 * material instead of Material's default tinted containers, so a sheet over a neumorphic page is
 * the same ice-blue (or graphite / ivory) with depth, not a purple-grey slab. FLAT themes never
 * reach this, so their colors are unchanged.
 */
private fun depthSurfaces(scheme: androidx.compose.material3.ColorScheme, dark: Boolean, depth: DepthStyle): androidx.compose.material3.ColorScheme {
    val s = scheme.surface
    fun shade(t: Float) = androidx.compose.ui.graphics.lerp(s, if (dark) Color.White else Color.Black, t)
    return scheme.copy(
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = s,
        surfaceContainerLow = s,
        surfaceContainer = shade(0.02f),
        surfaceContainerHigh = shade(0.035f),
        surfaceContainerHighest = shade(0.05f),
        surfaceBright = shade(0.04f),
        surfaceDim = androidx.compose.ui.graphics.lerp(s, Color.Black, 0.05f),
        // Selected chips/tabs: equipment style = a dark part lit orange (reference 02).
        secondaryContainer = if (depth == DepthStyle.SKEUOMORPHIC && dark) androidx.compose.ui.graphics.lerp(s, scheme.primary, 0.12f)
        else scheme.primary.copy(alpha = if (dark) 0.28f else 0.16f),
        onSecondaryContainer = if (depth == DepthStyle.SKEUOMORPHIC && dark) scheme.primary else scheme.onSurface,
        primaryContainer = if (dark) androidx.compose.ui.graphics.lerp(s, scheme.primary, 0.10f) else androidx.compose.ui.graphics.lerp(s, scheme.primary, 0.08f),
        outlineVariant = scheme.onSurfaceVariant.copy(alpha = 0.25f),
    )
}

private fun FontChoice.family(): FontFamily = when (this) {
    FontChoice.DEFAULT -> FontFamily.Default
    FontChoice.SANS -> FontFamily.SansSerif
    FontChoice.SERIF -> FontFamily.Serif
    FontChoice.MONO -> FontFamily.Monospace
    FontChoice.CURSIVE -> FontFamily.Cursive
}

/** The app [Typography] with the theme's font family and size scale applied to every style. */
private fun typographyFor(style: ThemeStyle): androidx.compose.material3.Typography {
    if (style.fontFamily == FontChoice.DEFAULT && style.fontScale == 1f) return Typography
    val family = style.fontFamily.family()
    val k = style.fontScale
    fun TextStyle.adapt(): TextStyle = copy(
        fontFamily = family,
        fontSize = fontSize.scaled(k),
        lineHeight = lineHeight.scaled(k),
    )
    val t = Typography
    return androidx.compose.material3.Typography(
        displayLarge = t.displayLarge.adapt(), displayMedium = t.displayMedium.adapt(), displaySmall = t.displaySmall.adapt(),
        headlineLarge = t.headlineLarge.adapt(), headlineMedium = t.headlineMedium.adapt(), headlineSmall = t.headlineSmall.adapt(),
        titleLarge = t.titleLarge.adapt(), titleMedium = t.titleMedium.adapt(), titleSmall = t.titleSmall.adapt(),
        bodyLarge = t.bodyLarge.adapt(), bodyMedium = t.bodyMedium.adapt(), bodySmall = t.bodySmall.adapt(),
        labelLarge = t.labelLarge.adapt(), labelMedium = t.labelMedium.adapt(), labelSmall = t.labelSmall.adapt(),
    )
}

private fun TextUnit.scaled(k: Float): TextUnit = if (isSpecified) this * k else this

/** Material shapes from the theme corner radius; the default 18 reproduces Material's 8/12/16/28. */
private fun shapesFor(style: ThemeStyle): Shapes {
    val r = style.cornerRadius.toFloat()
    return Shapes(
        extraSmall = RoundedCornerShape((r * 0.22f).dp),
        small = RoundedCornerShape((r * 0.45f).dp),
        medium = RoundedCornerShape((r * 0.67f).dp),
        large = RoundedCornerShape((r * 0.89f).dp),
        extraLarge = RoundedCornerShape((r * 1.56f).dp),
    )
}
