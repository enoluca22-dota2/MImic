package com.example.mimic

import com.example.mimic.data.ControlStyle
import com.example.mimic.data.PlayerBackground
import com.example.mimic.data.PlayerLayoutConfig
import com.example.mimic.data.ThemeSerialization
import com.example.mimic.data.VolumeControl
import com.example.mimic.data.resolvedBackground
import com.example.mimic.data.resolvedControls
import com.example.mimic.data.resolvedVolume
import com.example.mimic.player.volumeStepFor
import com.example.mimic.ui.components.knobAngleFor
import com.example.mimic.ui.components.knobFractionAfterTurn
import com.example.mimic.ui.designsystem.DepthColors
import com.example.mimic.ui.designsystem.DepthStyle
import com.example.mimic.ui.designsystem.ThemeRegistry
import com.example.mimic.ui.designsystem.ThemeStyle
import com.example.mimic.ui.designsystem.depthPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MImic 4.8: the three 3D styles — tokens, presets, accessibility, resolution, volume knob math. */
class DepthStyleTest {

    // ---- Tokens & persistence ----

    @Test
    fun defaultStyleIsFlat_soEveryExistingThemeIsUnchanged() {
        assertEquals(DepthStyle.FLAT, ThemeStyle().depth)
        ThemeRegistry.presets.filter { it.id !in ThemeRegistry.DEPTH_IDS }.forEach {
            assertEquals(it.id, DepthStyle.FLAT, it.style.depth)
        }
    }

    @Test
    fun depthRoundTripsThroughExportAndRoom() {
        ThemeRegistry.DEPTH_IDS.forEach { id ->
            val theme = ThemeRegistry.byId(id)
            val parsed = ThemeSerialization.parse(ThemeSerialization.toJson(theme)).getOrThrow()
            assertEquals(id, theme.style.depth, parsed.style.depth)
            assertEquals(id, theme.style.depthStrength, parsed.style.depthStrength, 0.0001f)
            // Room stores the style through the same flat JSON.
            assertEquals(theme.style.sanitized(), ThemeSerialization.parseStyle(ThemeSerialization.styleToJson(theme.style)))
        }
    }

    @Test
    fun oldFilesWithoutDepthImportAsFlat_andBadValuesAreSafe() {
        assertEquals(DepthStyle.FLAT, ThemeSerialization.parseStyle("""{ "glass": true }""").depth)
        assertEquals(DepthStyle.FLAT, ThemeSerialization.parseStyle("""{ "depth": "HOLOGRAM" }""").depth)
        assertEquals(1.6f, ThemeSerialization.parseStyle("""{ "depth": "HYBRID", "depthStrength": 99 }""").depthStrength, 0f)
        assertEquals(1f, ThemeStyle(depthStrength = Float.NaN).sanitized().depthStrength, 0f)
    }

    // ---- Presets ----

    @Test
    fun eachStyleHasALightAndADarkPreset() {
        assertEquals(setOf(DepthStyle.NEUMORPHIC, DepthStyle.SKEUOMORPHIC, DepthStyle.HYBRID), ThemeRegistry.DEPTH_PRESETS.keys)
        ThemeRegistry.DEPTH_PRESETS.forEach { (style, ids) ->
            val light = ThemeRegistry.byId(ids.first)
            val dark = ThemeRegistry.byId(ids.second)
            assertEquals(ids.first, light.id)
            assertEquals(ids.second, dark.id)
            assertFalse(light.colors.isDark)
            assertTrue(dark.colors.isDark)
            assertEquals(style, light.style.depth)
            assertEquals(style, dark.style.depth)
            assertFalse("3D presets are not glass", light.style.glass || dark.style.glass)
            assertEquals(ids.first, ThemeRegistry.depthPresetId(style, dark = false))
            assertEquals(ids.second, ThemeRegistry.depthPresetId(style, dark = true))
        }
        assertNull(ThemeRegistry.depthPresetId(DepthStyle.FLAT, dark = false))
        assertEquals(6, ThemeRegistry.DEPTH_PRESETS.values.flatMap { listOf(it.first, it.second) }.toSet().size)
        assertTrue(ThemeRegistry.CONTROL_DECK_ID in ThemeRegistry.DEPTH_IDS)
        assertEquals("ids are unique", ThemeRegistry.presets.size, ThemeRegistry.presets.map { it.id }.toSet().size)
    }

    @Test
    fun neumorphicPagesAreTheCardColor() {
        // The raised illusion needs card == page; Soft Ice Blue is a light icy blue.
        listOf("neu_ice", "neu_ice_night", "hybrid_soft", "hybrid_soft_night").forEach {
            val c = ThemeRegistry.byId(it).colors
            assertEquals(it, c.background, c.surface)
        }
        val ice = ThemeRegistry.byId("neu_ice").colors.background.toInt()
        assertTrue("ice blue: blue channel leads", (ice and 0xFF) > ((ice shr 16) and 0xFF))
        assertTrue("light", DepthColors.luminance(ice) > 0.7)
    }

    @Test
    fun presetTextMeetsWcagContrast() {
        ThemeRegistry.DEPTH_IDS.forEach { id ->
            val c = ThemeRegistry.byId(id).colors
            for (page in listOf(c.background.toInt(), c.surface.toInt())) {
                assertTrue("$id text", DepthColors.contrast(c.textPrimary.toInt(), page) >= 7.0)
                assertTrue("$id secondary text", DepthColors.contrast(c.textSecondary.toInt(), page) >= 4.5)
                // Accent is used for icons, active controls and the current song title.
                val accent = DepthColors.contrast(c.primary.toInt(), page)
                assertTrue("$id accent $accent", accent >= 4.5)
            }
            val p = depthPalette(ThemeRegistry.byId(id).style.depth, 1f, c.isDark, c.surface.toInt(), c.primary.toInt())
            assertTrue("$id play icon on accent", DepthColors.contrast(p.onAccent, p.accent) >= 4.5)
        }
    }

    // ---- Palette ----

    @Test
    fun flatPaletteIsInert() {
        val p = depthPalette(DepthStyle.FLAT, 1f, true, 0xFF101010.toInt(), 0xFF1DB954.toInt())
        assertFalse(p.on)
        assertFalse(p.softSurfaces)
        assertFalse(p.physicalControls)
    }

    @Test
    fun styleFlagsMatchTheBrief() {
        fun p(s: DepthStyle) = depthPalette(s, 1f, false, 0xFFE3EAF3.toInt(), 0xFF285F9E.toInt())
        assertTrue(p(DepthStyle.NEUMORPHIC).softSurfaces)
        assertFalse(p(DepthStyle.NEUMORPHIC).physicalControls)
        assertFalse(p(DepthStyle.SKEUOMORPHIC).softSurfaces)
        assertTrue(p(DepthStyle.SKEUOMORPHIC).physicalControls)
        assertTrue(p(DepthStyle.HYBRID).softSurfaces)
        assertTrue(p(DepthStyle.HYBRID).physicalControls)
    }

    @Test
    fun neumorphicShadowsKeepTheHueAndFrameThePage() {
        val page = 0xFFE3EAF3.toInt()
        val p = depthPalette(DepthStyle.NEUMORPHIC, 1f, false, page, 0xFF285F9E.toInt())
        val shade = p.darkShadow or 0xFF000000.toInt()
        assertTrue("shade darker than page", DepthColors.luminance(shade) < DepthColors.luminance(page))
        assertTrue("shade stays blue-tinted", (shade and 0xFF) > ((shade shr 16) and 0xFF))
        assertTrue("highlight lighter than page", DepthColors.luminance(p.lightShadow or 0xFF000000.toInt()) > DepthColors.luminance(page))
        // Stronger depth = more opaque shadows; clamped.
        val strong = depthPalette(DepthStyle.NEUMORPHIC, 5f, false, page, 0)
        assertEquals(1.6f, strong.strength, 0f)
        assertTrue((strong.darkShadow ushr 24) > (p.darkShadow ushr 24))
    }

    @Test
    fun colorMath() {
        assertEquals(DepthColors.WHITE, DepthColors.mix(DepthColors.BLACK, DepthColors.WHITE, 1f))
        assertEquals(DepthColors.BLACK, DepthColors.mix(DepthColors.BLACK, DepthColors.WHITE, 0f))
        assertEquals(21.0, DepthColors.contrast(DepthColors.BLACK, DepthColors.WHITE), 0.01)
        assertEquals(0x80123456.toInt(), DepthColors.withAlpha(0xFF123456.toInt(), 128 / 255f))
        assertEquals(DepthColors.BLACK, DepthColors.onColor(0xFFFFB04A.toInt()))
        assertEquals(DepthColors.WHITE, DepthColors.onColor(0xFF285F9E.toInt()))
    }

    // ---- Player layout resolution ----

    @Test
    fun autoControlsAndBackgroundFollowA3dTheme() {
        val auto = PlayerLayoutConfig()
        assertEquals(ControlStyle.TACTILE, auto.resolvedControls(glassTheme = false, depthTheme = true))
        assertEquals(ControlStyle.FILLED, auto.resolvedControls(glassTheme = false, depthTheme = false))
        assertEquals(ControlStyle.GLASS, auto.resolvedControls(glassTheme = true, depthTheme = true))
        // An explicit choice always wins.
        assertEquals(ControlStyle.MINIMAL, auto.copy(controlStyle = ControlStyle.MINIMAL).resolvedControls(false, depthTheme = true))
        assertEquals(PlayerBackground.SOLID, auto.resolvedBackground(glassTheme = false, depthTheme = true))
        assertEquals(PlayerBackground.GRADIENT, auto.copy(background = PlayerBackground.GRADIENT).resolvedBackground(false, true))
    }

    @Test
    fun volumeControlAppearsOnlyWhenWantedSoOldPlayersStayTheSame() {
        val auto = PlayerLayoutConfig()
        assertEquals(VolumeControl.DIAL, auto.resolvedVolume(ControlStyle.TACTILE))
        assertNull(auto.resolvedVolume(ControlStyle.FILLED))
        assertNull(auto.resolvedVolume(ControlStyle.GLASS))
        assertEquals(VolumeControl.SLIDER, auto.copy(volumeControl = VolumeControl.SLIDER).resolvedVolume(ControlStyle.FILLED))
        assertNull(auto.copy(volumeControl = VolumeControl.OFF).resolvedVolume(ControlStyle.TACTILE))
    }

    @Test
    fun enumsAreAppendOnly() {
        // Persisted by name and shown by ordinal in Settings — new values only at the end.
        assertEquals(listOf("AUTO", "FILLED", "GLASS", "MINIMAL", "TACTILE"), ControlStyle.entries.map { it.name })
        assertEquals(listOf("FLAT", "NEUMORPHIC", "SKEUOMORPHIC", "HYBRID"), DepthStyle.entries.map { it.name })
    }

    // ---- Volume knob ----

    @Test
    fun volumeStepsAreClampedAndRounded() {
        assertEquals(0, volumeStepFor(0f, 15))
        assertEquals(15, volumeStepFor(1f, 15))
        assertEquals(8, volumeStepFor(0.5f, 15))
        assertEquals(15, volumeStepFor(3f, 15))
        assertEquals(0, volumeStepFor(-1f, 15))
        assertEquals(0, volumeStepFor(Float.NaN, 15))
        assertEquals(0, volumeStepFor(0.5f, 0))
    }

    @Test
    fun knobSweepsFrom7To5OClock() {
        assertEquals(-135f, knobAngleFor(0f), 0f)
        assertEquals(0f, knobAngleFor(0.5f), 0f)
        assertEquals(135f, knobAngleFor(1f), 0f)
        assertEquals(135f, knobAngleFor(2f), 0f)
    }

    @Test
    fun knobFollowsTheFingersRotation() {
        // Clockwise 27° = +10 %.
        assertEquals(0.6f, knobFractionAfterTurn(0.5f, 0f, 27f), 0.0001f)
        // Counter-clockwise.
        assertEquals(0.4f, knobFractionAfterTurn(0.5f, 10f, -17f), 0.0001f)
        // Crossing 6 o'clock (180 -> -180) is a small step, not a full-scale jump.
        assertEquals(0.5f + 20f / 270f, knobFractionAfterTurn(0.5f, 170f, -170f), 0.0001f)
        // Clamped at the end stops.
        assertEquals(1f, knobFractionAfterTurn(0.95f, 0f, 90f), 0f)
        assertEquals(0f, knobFractionAfterTurn(0.05f, 0f, -90f), 0f)
    }
}
