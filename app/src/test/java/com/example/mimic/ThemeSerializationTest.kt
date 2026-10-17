package com.example.mimic

import com.example.mimic.data.ThemeSerialization
import com.example.mimic.ui.designsystem.ThemeColors
import com.example.mimic.ui.designsystem.ThemeDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSerializationTest {

    private val sample = ThemeDefinition(
        id = "custom_123",
        name = "My \"Cool\" Theme",
        builtIn = false,
        colors = ThemeColors(
            primary = 0xFF1DB954,
            secondary = 0xFF17A34A,
            background = 0xFF121212,
            surface = 0xFF181818,
            surfaceElevated = 0xFF242424,
            textPrimary = 0xFFFFFFFF,
            textSecondary = 0xFFB3B3B3,
            isDark = true,
        ),
    )

    @Test fun round_trip_preserves_values_including_quotes_in_name() {
        val json = ThemeSerialization.toJson(sample)
        val parsed = ThemeSerialization.parse(json).getOrThrow()
        assertEquals(sample.id, parsed.id)
        assertEquals(sample.name, parsed.name)
        assertEquals(sample.colors, parsed.colors)
    }

    @Test fun parseHex_accepts_six_and_eight_digits() {
        assertEquals(0xFF1DB954, ThemeSerialization.parseHex("#1DB954"))
        assertEquals(0xFF1DB954, ThemeSerialization.parseHex("1DB954"))
        assertEquals(0x801DB954, ThemeSerialization.parseHex("#801DB954"))
    }

    @Test fun parse_rejects_missing_field() {
        val bad = """{ "mimicThemeVersion": 1, "id": "x", "name": "n", "isDark": true }"""
        assertTrue(ThemeSerialization.parse(bad).isFailure)
    }

    @Test fun parse_rejects_bad_color() {
        val json = ThemeSerialization.toJson(sample).replace("#FF1DB954", "#ZZZZZZ")
        assertTrue(ThemeSerialization.parse(json).isFailure)
    }

    @Test fun parse_rejects_oversized_input() {
        val huge = "{" + "x".repeat(9000) + "}"
        assertTrue(ThemeSerialization.parse(huge).isFailure)
    }
}
