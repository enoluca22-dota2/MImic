package com.example.mimic.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.mimic.data.AccentColor
import com.example.mimic.data.ThemeMode

fun accentToColor(accent: AccentColor): Color = when (accent) {
    AccentColor.GREEN -> Color(0xFF1DB954)
    AccentColor.BLUE -> Color(0xFF2E77D0)
    AccentColor.PURPLE -> Color(0xFF8E44EF)
    AccentColor.ORANGE -> Color(0xFFE87E2A)
    AccentColor.PINK -> Color(0xFFE93D82)
    AccentColor.RED -> Color(0xFFE02A2A)
}

private fun darkScheme(accent: Color, background: Color, surface: Color, surfaceElevated: Color) =
    darkColorScheme(
        primary = accent,
        onPrimary = Color.Black,
        primaryContainer = accent.copy(alpha = 0.3f),
        secondary = accent,
        background = background,
        onBackground = Color.White,
        surface = surface,
        onSurface = Color.White,
        surfaceVariant = surfaceElevated,
        onSurfaceVariant = Color(0xFFB3B3B3),
    )

private fun lightScheme(accent: Color) = lightColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = accent.copy(alpha = 0.18f),
    secondary = accent,
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF121212),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF121212),
    surfaceVariant = Color(0xFFECECEC),
    onSurfaceVariant = Color(0xFF5A5A5A),
)

@Composable
fun MImicTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accent: AccentColor = AccentColor.GREEN,
    content: @Composable () -> Unit,
) {
    val accentColor = accentToColor(accent)
    val systemDark = isSystemInDarkTheme()
    val colorScheme = when (themeMode) {
        ThemeMode.LIGHT -> lightScheme(accentColor)
        ThemeMode.DARK -> darkScheme(accentColor, Color(0xFF121212), Color(0xFF181818), Color(0xFF242424))
        ThemeMode.AMOLED -> darkScheme(accentColor, Color(0xFF000000), Color(0xFF0A0A0A), Color(0xFF161616))
        ThemeMode.GLASS -> darkScheme(accentColor, Color(0xFF0E1116), Color(0xFF161B22), Color(0xFF222A35))
        ThemeMode.SYSTEM ->
            if (systemDark) darkScheme(accentColor, Color(0xFF121212), Color(0xFF181818), Color(0xFF242424))
            else lightScheme(accentColor)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
