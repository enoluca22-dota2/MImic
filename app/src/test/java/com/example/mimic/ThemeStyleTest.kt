package com.example.mimic

import com.example.mimic.data.ControlStyle
import com.example.mimic.data.GlassQuality
import com.example.mimic.data.NavStyle
import com.example.mimic.data.PlayerBackground
import com.example.mimic.data.PlayerLayout
import com.example.mimic.data.PlayerLayoutConfig
import com.example.mimic.data.ThemeSerialization
import com.example.mimic.data.resolve
import com.example.mimic.data.resolvedBackground
import com.example.mimic.data.resolvedControls
import com.example.mimic.ui.designsystem.AlbumColors
import com.example.mimic.ui.designsystem.ControlShape
import com.example.mimic.ui.designsystem.FontChoice
import com.example.mimic.ui.designsystem.GlassEnv
import com.example.mimic.ui.designsystem.GlassTone
import com.example.mimic.ui.designsystem.ThemeColors
import com.example.mimic.ui.designsystem.ThemeDefinition
import com.example.mimic.ui.designsystem.ThemeRegistry
import com.example.mimic.ui.designsystem.ThemeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MImic 2.9: theme style schema, migration, sanitizing, album colors and style resolution. */
class ThemeStyleTest {

    private val colors = ThemeColors(
        0xFF0A84FF, 0xFF64D2FF, 0xFF07090D, 0xFF10151C, 0xFF1A222D, 0xFFF2F6FB, 0xFFA9B6C6, true,
    )
    private val styled = ThemeDefinition(
        id = "custom_9", name = "Glassy", builtIn = false, colors = colors,
        style = ThemeStyle(
            glass = true, glassOpacity = 0.35f, blurRadius = 31, refraction = 0.8f, edgeHighlight = 0.25f,
            shadow = 0.9f, glassTint = 0x80FFFFFF, glassTone = GlassTone.LIGHT, accentColor = 0xFFFFE600,
            gradientBackground = true, gradientColor = 0xFF1A0533, albumAdaptive = true, cornerRadius = 30,
            navCornerRadius = 12, controlShape = ControlShape.SQUIRCLE, pillNav = true,
            fontFamily = FontChoice.SERIF, fontScale = 1.1f, headerWeight = 900, titleWeight = 500,
            artistWeight = 300, motion = false, animationScale = 1.4f, transitionSpeed = 1.5f,
        ),
    )

    @Test fun v2_round_trip_preserves_every_style_field() {
        val parsed = ThemeSerialization.parse(ThemeSerialization.toJson(styled)).getOrThrow()
        assertEquals(styled.colors, parsed.colors)
        assertEquals(styled.style, parsed.style)
    }

    @Test fun v1_file_migrates_with_default_flat_style() {
        val v1 = """
            { "mimicThemeVersion": 1, "id": "old_theme", "name": "Old", "isDark": true,
              "primary": "#FF1DB954", "secondary": "#FF17A34A", "background": "#FF121212",
              "surface": "#FF181818", "surfaceElevated": "#FF242424",
              "textPrimary": "#FFFFFFFF", "textSecondary": "#FFB3B3B3" }
        """.trimIndent()
        val parsed = ThemeSerialization.parse(v1).getOrThrow()
        assertEquals(ThemeStyle(), parsed.style)
        assertEquals(0xFF1DB954, parsed.colors.primary)
    }

    @Test fun file_without_version_is_treated_as_v1() {
        val noVersion = ThemeSerialization.toJson(styled).replace("\"mimicThemeVersion\": 2,", "")
        assertTrue(ThemeSerialization.parse(noVersion).isSuccess)
        assertEquals(ThemeStyle(), ThemeSerialization.parse(noVersion).getOrThrow().style)
    }

    @Test fun future_version_is_rejected_with_a_clear_message() {
        val future = ThemeSerialization.toJson(styled).replace("\"mimicThemeVersion\": 2", "\"mimicThemeVersion\": 9")
        val result = ThemeSerialization.parse(future)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("newer"))
    }

    @Test fun hostile_style_values_are_clamped_or_defaulted() {
        val json = ThemeSerialization.toJson(styled)
            .replace("\"glassOpacity\": 0.35", "\"glassOpacity\": 999.0")
            .replace("\"blurRadius\": 31", "\"blurRadius\": -500")
            .replace("\"fontScale\": 1.1", "\"fontScale\": 40.0")
            .replace("\"headerWeight\": 900", "\"headerWeight\": 123456")
            .replace("\"controlShape\": \"SQUIRCLE\"", "\"controlShape\": \"DROP_TABLE\"")
            .replace("\"accentColor\": \"#FFFFE600\"", "\"accentColor\": \"javascript:alert(1)\"")
        val st = ThemeSerialization.parse(json).getOrThrow().style
        assertEquals(1f, st.glassOpacity)
        assertEquals(0, st.blurRadius)
        assertEquals(1.25f, st.fontScale)
        assertEquals(900, st.headerWeight)
        assertEquals(ControlShape.ROUNDED, st.controlShape)
        assertEquals(0L, st.accentColor)
    }

    @Test fun style_column_round_trips_and_null_means_default() {
        assertEquals(styled.style, ThemeSerialization.parseStyle(ThemeSerialization.styleToJson(styled.style)))
        assertEquals(ThemeStyle(), ThemeSerialization.parseStyle(null))
        assertEquals(ThemeStyle(), ThemeSerialization.parseStyle("garbage {{{"))
    }

    @Test fun sanitize_handles_non_finite_numbers() {
        val st = ThemeStyle(glassOpacity = Float.NaN, refraction = Float.POSITIVE_INFINITY).sanitized()
        assertEquals(0.6f, st.glassOpacity)
        assertEquals(0f, st.refraction)
    }

    @Test fun album_colors_are_readable_for_any_artwork() {
        // Loud, dark, pale and grey covers: text must stay readable in both light and dark schemes.
        val seeds = listOf(0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00, 0xFF101010, 0xFFF5F5F5, 0xFF808080, 0xFFFF2E97)
        for (seed in seeds) for (dark in listOf(true, false)) {
            val c = AlbumColors.themeColors(seed.toInt(), dark)
            val reg = ThemeRegistryTest()
            assertTrue("seed ${seed.toString(16)} dark=$dark text", reg.contrastRatio(c.textPrimary, c.background) >= 7.0)
            assertTrue("seed ${seed.toString(16)} dark=$dark text2", reg.contrastRatio(c.textSecondary, c.background) >= 4.5)
            assertEquals(dark, c.isDark)
        }
    }

    @Test fun album_colors_keep_amoled_black() {
        assertEquals(0xFF000000L, AlbumColors.themeColors(0xFFB388FF.toInt(), isDark = true, amoled = true).background)
    }

    @Test fun nav_auto_follows_theme_and_explicit_choice_wins() {
        assertEquals(NavStyle.GLASS, NavStyle.AUTO.resolve(themeWantsGlassNav = true))
        assertEquals(NavStyle.STANDARD, NavStyle.AUTO.resolve(themeWantsGlassNav = false))
        assertEquals(NavStyle.COMPACT, NavStyle.COMPACT.resolve(themeWantsGlassNav = true))
        assertEquals(NavStyle.GLASS, NavStyle.FLOATING.resolve(themeWantsGlassNav = false, glassTheme = true))
        assertEquals(NavStyle.FLOATING, NavStyle.FLOATING.resolve(themeWantsGlassNav = false, glassTheme = false))
        assertEquals(NavStyle.STANDARD, NavStyle.STANDARD.resolve(themeWantsGlassNav = true))
    }

    @Test fun player_auto_styles_resolve_and_preserve_pre_2_9_behavior() {
        val classic = PlayerLayoutConfig()
        assertEquals(PlayerBackground.SOLID, classic.resolvedBackground(glassTheme = false))
        assertEquals(PlayerBackground.GRADIENT, classic.copy(layout = PlayerLayout.IMMERSIVE).resolvedBackground(false))
        assertEquals(PlayerBackground.BLURRED_ARTWORK, classic.resolvedBackground(glassTheme = true))
        assertEquals(ControlStyle.FILLED, classic.resolvedControls(glassTheme = false))
        assertEquals(ControlStyle.GLASS, classic.resolvedControls(glassTheme = true))
        assertEquals(ControlStyle.MINIMAL, classic.copy(controlStyle = ControlStyle.MINIMAL).resolvedControls(true))
    }

    @Test fun glass_env_honors_quality_and_reduced_motion() {
        val glassTheme = ThemeRegistry.byId("mimic_liquid_glass").style
        assertTrue(GlassEnv(glassTheme, GlassQuality.BALANCED).enabled)
        assertFalse(GlassEnv(glassTheme, GlassQuality.OFF).enabled)
        assertFalse(GlassEnv(ThemeStyle(), GlassQuality.HIGH).enabled)
        assertEquals(0f, GlassEnv(glassTheme, GlassQuality.HIGH, reducedMotion = true).motionScale)
        assertEquals(0f, GlassEnv(glassTheme.copy(motion = false), GlassQuality.HIGH).motionScale)
        // 3.0: BALANCED keeps the (cheap) lens so glass still reads as glass; PERFORMANCE drops all shaders.
        assertTrue(GlassQuality.HIGH.allowsRefraction && GlassQuality.BALANCED.allowsRefraction)
        assertFalse(GlassQuality.PERFORMANCE.allowsRefraction)
        assertEquals(0f, GlassQuality.PERFORMANCE.blurFactor)
    }
}
