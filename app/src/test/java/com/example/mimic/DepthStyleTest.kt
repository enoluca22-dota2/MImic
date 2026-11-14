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
    fun retiredPresetSelectionsUseTheDefault() {
        listOf("neu_ice", "neu_ice_night", "skeuo_graphite", "skeuo_ivory", "hybrid_soft", "hybrid_soft_night").forEach {
            assertFalse(ThemeRegistry.presets.any { preset -> preset.id == it })
            assertEquals(ThemeRegistry.DEFAULT_ID, ThemeRegistry.resolveSelection(it))
        }
        assertEquals("custom-theme", ThemeRegistry.resolveSelection("custom-theme"))
        assertEquals(ThemeRegistry.CONTROL_DECK_ID, ThemeRegistry.resolveSelection("matte_amber_3d"))
        assertTrue(ThemeRegistry.usesHardwareControls(ThemeRegistry.CONTROL_DECK_ID))
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
        // 4.8.0-beta.2: a 3D theme with glass switched on stays 3D (glass used to win here).
        assertEquals(ControlStyle.TACTILE, auto.resolvedControls(glassTheme = true, depthTheme = true))
        assertEquals(ControlStyle.GLASS, auto.resolvedControls(glassTheme = true, depthTheme = false))
        assertEquals(PlayerBackground.SOLID, auto.resolvedBackground(glassTheme = true, depthTheme = true))
        assertEquals(PlayerBackground.BLURRED_ARTWORK, auto.resolvedBackground(glassTheme = true, depthTheme = false))
        // Explicit choices on a glass + 3D theme are respected, whatever they are.
        for (explicit in listOf(ControlStyle.FILLED, ControlStyle.GLASS, ControlStyle.MINIMAL, ControlStyle.TACTILE)) {
            assertEquals(explicit, auto.copy(controlStyle = explicit).resolvedControls(glassTheme = true, depthTheme = true))
            assertEquals(explicit, auto.copy(controlStyle = explicit).resolvedControls(glassTheme = false, depthTheme = false))
        }
        for (explicit in PlayerBackground.entries.filter { it != PlayerBackground.AUTO }) {
            assertEquals(explicit, auto.copy(background = explicit).resolvedBackground(glassTheme = true, depthTheme = true))
        }
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
        assertEquals(listOf("FLAT", "NEUMORPHIC", "SKEUOMORPHIC", "HYBRID", "MATTE_AMBER"), DepthStyle.entries.map { it.name })
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

    @Test
    fun equipmentKnobLightsTicksUpToTheLevelOnly() {
        assertEquals(0, com.example.mimic.ui.components.equipmentLitTicks(0f))
        assertEquals(0, com.example.mimic.ui.components.equipmentLitTicks(Float.NaN))
        assertEquals(0, com.example.mimic.ui.components.equipmentLitTicks(-1f))
        assertEquals(1, com.example.mimic.ui.components.equipmentLitTicks(0.01f))
        assertEquals(9, com.example.mimic.ui.components.equipmentLitTicks(0.5f))
        assertEquals(com.example.mimic.ui.components.EQ_TICKS, com.example.mimic.ui.components.equipmentLitTicks(1f))
        assertEquals(com.example.mimic.ui.components.EQ_TICKS, com.example.mimic.ui.components.equipmentLitTicks(3f))
        // Monotonic: turning up never turns a light off.
        var last = 0
        for (i in 0..100) {
            val n = com.example.mimic.ui.components.equipmentLitTicks(i / 100f)
            assertTrue(n >= last)
            last = n
        }
    }

    @Test
    fun equipmentTicksAreDiscreteCyanAmberOrangeBands() {
        val cyan = com.example.mimic.ui.designsystem.EQ_CYAN
        val warm = 0xFFFFD21A.toInt()
        val hot = 0xFFFF4A00.toInt()
        assertEquals(cyan, com.example.mimic.ui.components.equipmentTickColor(0f, cyan, warm, hot))
        assertEquals(cyan, com.example.mimic.ui.components.equipmentTickColor(0.39f, cyan, warm, hot))
        assertEquals(warm, com.example.mimic.ui.components.equipmentTickColor(0.5f, cyan, warm, hot))
        assertEquals(hot, com.example.mimic.ui.components.equipmentTickColor(0.7f, cyan, warm, hot))
        assertEquals(hot, com.example.mimic.ui.components.equipmentTickColor(1f, cyan, warm, hot))
        assertEquals(cyan, com.example.mimic.ui.components.equipmentTickColor(Float.NaN, cyan, warm, hot))
    }

    @Test
    fun blackAndOrangePaletteUsesTheSampledReferenceColors() {
        val graphite = ThemeRegistry.byId(ThemeRegistry.CONTROL_DECK_ID)
        val d = depthPalette(DepthStyle.SKEUOMORPHIC, graphite.style.depthStrength, true, graphite.colors.surface.toInt(), graphite.colors.primary.toInt())
        assertTrue(d.equipment)
        assertEquals(0xFF30303A.toInt(), d.metalTop)
        assertEquals(0xFF1A1B20.toInt(), d.metalBottom)
        assertEquals(0xFF0D0E10.toInt(), d.groove)
        // Hybrid keeps its own graphite controls (unchanged by the Black & Orange refinement).
        val hybrid = depthPalette(DepthStyle.HYBRID, 1f, true, graphite.colors.surface.toInt(), graphite.colors.primary.toInt())
        assertTrue(hybrid.metalTop != d.metalTop)
    }
}
