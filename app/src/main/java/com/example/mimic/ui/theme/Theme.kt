package com.example.mimic.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = AccentGreen,
    onPrimary = AppBlack,
    primaryContainer = AccentGreenDark,
    secondary = AccentGreen,
    background = AppBlack,
    onBackground = TextPrimary,
    surface = AppSurface,
    onSurface = TextPrimary,
    surfaceVariant = AppSurfaceElevated,
    onSurfaceVariant = TextSecondary,
)

@Composable
fun MImicTheme(
    content: @Composable () -> Unit
) {
    // The app is dark-only by design, like most music players.
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
