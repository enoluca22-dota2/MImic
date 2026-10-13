package com.example.mimic

import com.example.mimic.ui.designsystem.ThemeRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ThemeRegistryTest {

    @Test fun has_all_fourteen_canonical_presets_with_unique_ids() {
        assertEquals(14, ThemeRegistry.CANONICAL_IDS.size)
        val ids = ThemeRegistry.presets.map { it.id }
        assertEquals("ids must be unique", ids.size, ids.toSet().size)
        ThemeRegistry.CANONICAL_IDS.forEach { assertTrue("missing canonical preset $it", it in ids) }
        assertTrue("flagship present", "mimic_liquid_glass" in ids)
    }

    @Test fun pre_2_9_preset_ids_are_all_still_resolvable() {
        // Users who applied any of these before 2.9 must keep their theme after upgrading.
        listOf(
            "amoled_black", "emerald", "ocean_neon", "sunset", "rose_gold", "arctic_light",
            "purple_galaxy", "midnight_blue", "retro_vinyl", "minimal_mono", "material_you",
            "album_adaptive", "frosted_glass", "high_contrast",
        ).forEach { id -> assertEquals(id, ThemeRegistry.byId(id).id) }
    }

    @Test fun liquid_glass_presets_opt_into_glass_and_flat_presets_do_not() {
        listOf("mimic_liquid_glass", "ios_glass_clear", "frosted_glass", "ios_dark_glass").forEach {
            assertTrue("$it should be glass", ThemeRegistry.byId(it).style.glass)
        }
        listOf("amoled_black", "emerald", "high_contrast").forEach {
            assertTrue("$it should stay flat", !ThemeRegistry.byId(it).style.glass)
        }
        assertTrue(ThemeRegistry.byId("album_adaptive").style.albumAdaptive)
    }

    @Test fun every_preset_has_readable_secondary_text() {
        ThemeRegistry.presets.forEach { theme ->
            val ratio = contrastRatio(theme.colors.textSecondary, theme.colors.background)
            assertTrue("${theme.name} secondary text contrast too low: $ratio", ratio >= 3.0)
        }
    }

    @Test fun byId_falls_back_to_default_for_unknown_or_null() {
        val default = ThemeRegistry.byId(ThemeRegistry.DEFAULT_ID)
        assertSame(default, ThemeRegistry.byId("does_not_exist"))
        assertSame(default, ThemeRegistry.byId(null))
    }

    @Test fun every_preset_has_readable_title_contrast() {
        // WCAG AA for large text is 3.0:1; we require >= 4.0 for every built-in theme.
        ThemeRegistry.presets.forEach { theme ->
            val ratio = contrastRatio(theme.colors.textPrimary, theme.colors.background)
            assertTrue(
                "${theme.name} title contrast too low: $ratio",
                ratio >= 4.0,
            )
        }
    }

    private fun channel(c: Double): Double =
        if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    fun contrastRatio(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }
}
