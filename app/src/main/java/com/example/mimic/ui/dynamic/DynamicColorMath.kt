package com.example.mimic.ui.dynamic

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Colors of the Dynamic Now Playing player for one song, as ARGB ints (pure Kotlin — unit-tested
 * without Android). Everything is derived from the artwork's own palette; [fallback] is used only
 * when a song has no artwork.
 */
data class DynamicScheme(
    val top: Int,
    val bottom: Int,
    val glow: Int,
    val accent: Int,
    val onBackground: Int,
    val onBackgroundMuted: Int,
) {
    companion object {
        /** Neutral midnight scheme for songs without artwork. */
        val fallback = DynamicColorMath.derive(
            DynamicColorMath.Swatches(dominant = 0xFF26305A.toInt(), vibrant = 0xFF6D7CFF.toInt(), darkMuted = 0xFF0B0F24.toInt()),
            brightness = 0f, autoContrast = true,
        )
    }
}

object DynamicColorMath {
    /** The artwork colors the scheme is built from (AndroidX Palette swatches, or a fallback). */
    data class Swatches(val dominant: Int, val vibrant: Int, val darkMuted: Int)

    const val MIN_TEXT_CONTRAST = 4.5 // WCAG AA for normal text
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val INK = 0xFF0E0E12.toInt()

    fun r(c: Int) = (c shr 16) and 0xFF
    fun g(c: Int) = (c shr 8) and 0xFF
    fun b(c: Int) = c and 0xFF
    fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** WCAG relative luminance (0 black … 1 white). */
    fun luminance(c: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(r(c)) + 0.7152 * ch(g(c)) + 0.0722 * ch(b(c))
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Linear mix: t = 0 → [a], 1 → [b]. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return argb(
            (r(a) + (r(b) - r(a)) * k).toInt(),
            (g(a) + (g(b) - g(a)) * k).toInt(),
            (b(a) + (b(b) - b(a)) * k).toInt(),
        )
    }

    /** Brightness shift: positive towards white, negative towards black. */
    fun shift(c: Int, amount: Float): Int = if (amount >= 0) mix(c, WHITE, amount) else mix(c, 0xFF000000.toInt(), -amount)

    /**
     * Darkens (or lightens, for dark text) [bg] step by step until [text] reaches [min] contrast
     * against it. Guarantees the accessibility floor whatever the artwork looks like.
     */
    fun ensureContrast(bg: Int, text: Int, min: Double = MIN_TEXT_CONTRAST): Int {
        var c = bg
        val towards = if (luminance(text) > 0.5) 0xFF000000.toInt() else WHITE
        var i = 0
        while (contrast(c, text) < min && i < 20) { c = mix(c, towards, 0.12f); i++ }
        return c
    }

    /**
     * Builds the scheme: a vertical gradient from the dominant color (top) into its deep muted
     * shade (bottom), a glow and an accent from the vibrant color. With [autoContrast] the text is
     * whichever of white / ink reads better; either way the background is adjusted until text
     * contrast is at least [MIN_TEXT_CONTRAST].
     */
    fun derive(s: Swatches, brightness: Float, autoContrast: Boolean): DynamicScheme {
        val shiftBy = brightness.coerceIn(-0.4f, 0.4f)
        var top = shift(mix(s.dominant, 0xFF000000.toInt(), 0.30f), shiftBy)
        var bottom = shift(mix(s.darkMuted, 0xFF000000.toInt(), 0.62f), shiftBy * 0.6f)
        val mid = mix(top, bottom, 0.5f)
        val text = if (autoContrast && contrast(mid, INK) > contrast(mid, WHITE) + 1.0) INK else WHITE
        top = ensureContrast(top, text)
        bottom = ensureContrast(bottom, text)
        // Accent must stay visible on the lower half (progress bar, buttons): ≥ 3:1 (WCAG UI).
        var accent = s.vibrant
        var i = 0
        val towardsText = text
        while (contrast(accent, bottom) < 3.0 && i < 20) { accent = mix(accent, towardsText, 0.1f); i++ }
        val glow = mix(s.vibrant, top, 0.35f)
        val muted = mix(text, mix(top, bottom, 0.5f), 0.32f)
        return DynamicScheme(top, bottom, glow, accent, text, ensureMuted(muted, top, bottom, text))
    }

    /** Secondary text keeps at least 3:1 on both ends of the gradient. */
    private fun ensureMuted(muted: Int, top: Int, bottom: Int, text: Int): Int {
        var m = muted
        var i = 0
        while ((contrast(m, top) < 3.0 || contrast(m, bottom) < 3.0) && i < 20) { m = mix(m, text, 0.15f); i++ }
        return m
    }
}
