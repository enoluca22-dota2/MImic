package com.example.mimic.ui.designsystem

import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeContent

/**
 * Album-adaptive coloring (MImic 2.9). Turns one seed color extracted from the artwork into a
 * full, contrast-safe token set using MaterialKolor's port of Google's material-color-utilities
 * ([SchemeContent] keeps the artwork's own hue and chroma, which suits album art best).
 *
 * Pure Kotlin and deterministic, so readability against any artwork is unit-tested on the JVM.
 * The tone-based scheme guarantees text/background contrast no matter how loud the cover is —
 * the "text must remain readable regardless of album artwork" requirement.
 */
object AlbumColors {

    fun themeColors(seedArgb: Int, isDark: Boolean, amoled: Boolean = false): ThemeColors {
        val scheme = SchemeContent(Hct.fromInt(seedArgb), isDark, 0.0)
        val background = if (amoled && isDark) 0xFF000000L else scheme.background.toArgbLong()
        return ThemeColors(
            primary = scheme.primary.toArgbLong(),
            secondary = scheme.tertiary.toArgbLong(),
            background = background,
            surface = scheme.surfaceContainer.toArgbLong(),
            surfaceElevated = scheme.surfaceContainerHighest.toArgbLong(),
            textPrimary = scheme.onSurface.toArgbLong(),
            textSecondary = scheme.onSurfaceVariant.toArgbLong(),
            isDark = isDark,
        )
    }

    private fun Int.toArgbLong(): Long = toLong() and 0xFFFFFFFFL
}
