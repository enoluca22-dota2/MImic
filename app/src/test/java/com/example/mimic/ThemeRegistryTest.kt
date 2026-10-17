package com.example.mimic

import com.example.mimic.ui.designsystem.ThemeRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ThemeRegistryTest {

    @Test fun has_all_fourteen_presets_with_unique_ids() {
        assertEquals(14, ThemeRegistry.presets.size)
        val ids = ThemeRegistry.presets.map { it.id }
        assertEquals("ids must be unique", ids.size, ids.toSet().size)
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

    private fun contrastRatio(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }
}
