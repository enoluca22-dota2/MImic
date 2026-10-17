package com.example.mimic.data

import com.example.mimic.data.db.CustomThemeEntity
import com.example.mimic.ui.designsystem.ThemeColors
import com.example.mimic.ui.designsystem.ThemeDefinition

fun CustomThemeEntity.toDefinition(): ThemeDefinition = ThemeDefinition(
    id = id,
    name = name,
    builtIn = false,
    colors = ThemeColors(
        primary = primary,
        secondary = secondary,
        background = background,
        surface = surface,
        surfaceElevated = surfaceElevated,
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        isDark = isDark,
    ),
)

fun ThemeDefinition.toEntity(): CustomThemeEntity = CustomThemeEntity(
    id = id,
    name = name,
    isDark = colors.isDark,
    primary = colors.primary,
    secondary = colors.secondary,
    background = colors.background,
    surface = colors.surface,
    surfaceElevated = colors.surfaceElevated,
    textPrimary = colors.textPrimary,
    textSecondary = colors.textSecondary,
    createdAt = System.currentTimeMillis(),
)
