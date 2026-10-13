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
    val scheme = remember(colors, style.accentColor) {
        val base = colors.toColorScheme()
        if (style.accentColor != 0L) base.copy(tertiary = Color(style.accentColor)) else base.copy(tertiary = base.secondary)
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
    CompositionLocalProvider(LocalGlass provides env) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            shapes = shapes,
            content = content,
        )
    }
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
