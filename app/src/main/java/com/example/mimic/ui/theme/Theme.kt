package com.example.mimic.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.example.mimic.ui.designsystem.ThemeDefinition
import com.example.mimic.ui.designsystem.toColorScheme

@Composable
fun MImicTheme(
    theme: ThemeDefinition,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = theme.colors.toColorScheme(),
        typography = Typography,
        content = content,
    )
}
