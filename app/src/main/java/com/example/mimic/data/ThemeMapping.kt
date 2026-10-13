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
    // Pre-2.9 rows have no style => parseStyle(null) gives the default flat style.
    style = ThemeSerialization.parseStyle(styleJson),
)

fun ThemeDefinition.toEntity(createdAt: Long = System.currentTimeMillis()): CustomThemeEntity = CustomThemeEntity(
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
    createdAt = createdAt,
    styleJson = ThemeSerialization.styleToJson(style),
)
