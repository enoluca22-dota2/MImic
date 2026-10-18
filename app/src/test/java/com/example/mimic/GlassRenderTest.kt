package com.example.mimic

import com.example.mimic.data.GlassQuality
import com.example.mimic.ui.designsystem.GlassEnv
import com.example.mimic.ui.designsystem.GlassRenderMode
import com.example.mimic.ui.designsystem.ThemeRegistry
import com.example.mimic.ui.designsystem.ThemeStyle
import com.example.mimic.ui.designsystem.glassReadabilityFloor
import com.example.mimic.ui.designsystem.glassRenderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** MImic 3.0 glass rules: real transparency, honest render-mode reporting. */
class GlassRenderTest {

    private val glass = ThemeStyle(glass = true, refraction = 0.8f)

    @Test fun render_mode_matches_quality_device_and_backdrop() {
        assertEquals(GlassRenderMode.LIVE_LENS, glassRenderMode(GlassEnv(glass, GlassQuality.HIGH), true, sdk = 33))
        assertEquals(GlassRenderMode.LIVE_LENS, glassRenderMode(GlassEnv(glass, GlassQuality.BALANCED), true, sdk = 35))
        assertEquals(GlassRenderMode.LIVE_BLUR, glassRenderMode(GlassEnv(glass, GlassQuality.HIGH), true, sdk = 31))
        assertEquals(GlassRenderMode.TINTED, glassRenderMode(GlassEnv(glass, GlassQuality.PERFORMANCE), true, sdk = 35))
        assertEquals(GlassRenderMode.TINTED, glassRenderMode(GlassEnv(glass, GlassQuality.HIGH), false, sdk = 35))
        assertEquals(GlassRenderMode.TINTED, glassRenderMode(GlassEnv(glass, GlassQuality.HIGH), true, sdk = 29))
        assertEquals(GlassRenderMode.OPAQUE, glassRenderMode(GlassEnv(glass, GlassQuality.OFF), true, sdk = 35))
        assertEquals(GlassRenderMode.OPAQUE, glassRenderMode(GlassEnv(ThemeStyle(), GlassQuality.HIGH), true, sdk = 35))
    }

    @Test fun readability_floor_never_forces_an_opaque_slab() {
        // 2.9 forced >= 0.25 even with strong blur and 0.92 with none; 3.0 caps the floor at 0.2.
        assertTrue(glassReadabilityFloor(0f) <= 0.2f)
        assertEquals(0.08f, glassReadabilityFloor(12f), 1e-4f)
        assertTrue(glassReadabilityFloor(2f) > glassReadabilityFloor(8f))
    }

    @Test fun glass_presets_are_actually_see_through() {
        for (id in listOf("ios_glass_clear", "frosted_glass", "ios_dark_glass", "mimic_liquid_glass")) {
            val st = ThemeRegistry.byId(id).style
            assertTrue("$id must be glass", st.glass)
            assertTrue("$id tint too dense: ${st.glassOpacity}", st.glassOpacity <= 0.4f)
        }
        val clear = ThemeRegistry.byId("ios_glass_clear").style
        val frosted = ThemeRegistry.byId("frosted_glass").style
        val dark = ThemeRegistry.byId("ios_dark_glass").style
        // Clear < Dark < Frosted in tint; Frosted blurs the most; Clear refracts the most.
        assertTrue(clear.glassOpacity < dark.glassOpacity && dark.glassOpacity <= frosted.glassOpacity + 0.1f)
        assertTrue(frosted.blurRadius > dark.blurRadius && dark.blurRadius > clear.blurRadius)
        assertTrue(clear.refraction >= dark.refraction && dark.refraction > frosted.refraction)
    }
}
