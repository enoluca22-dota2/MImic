package com.example.mimic.data

import android.os.Build

/**
 * Global quality knob for the Liquid Glass visual effects (MImic 2.9). This is a *device*
 * setting, independent of the chosen theme: a glass theme still renders, but heavier effects
 * (backdrop blur, lens refraction) are scaled down or disabled for weaker devices.
 *
 * The ordering matters for the performance mandate — glass must never cost playback or scroll
 * smoothness, so [PERFORMANCE] keeps the translucent look with *no* blur, and [OFF] drops glass
 * surfaces entirely in favour of plain opaque surfaces (the pre-2.9 look).
 */
enum class GlassQuality {
    HIGH,        // full blur + full lens refraction (refraction needs API 33+, blur API 31+)
    BALANCED,    // slightly lighter blur + lens refraction (default on capable devices)
    PERFORMANCE, // translucent tinted surfaces only: no blur, no shaders
    OFF;         // no glass — plain opaque surfaces

    /** Fraction of a theme's configured blur radius to actually apply. */
    val blurFactor: Float
        get() = when (this) {
            HIGH -> 1f
            BALANCED -> 0.75f
            PERFORMANCE -> 0f
            OFF -> 0f
        }

    /** Whether glass surfaces are drawn at all (translucent tint + edge highlight). */
    val allowsGlass: Boolean get() = this != OFF

    /**
     * Whether the lens refraction shader may run (still also gated on API 33 at draw time). The
     * lens is what makes glass read as *glass* (the edges bend what is behind them), and it is a
     * single cheap pass over a small surface, so BALANCED keeps it too (MImic 3.0).
     */
    val allowsRefraction: Boolean get() = this == HIGH || this == BALANCED

    /** Lens strength multiplier. */
    val refractionFactor: Float
        get() = when (this) {
            HIGH -> 1f
            BALANCED -> 0.8f
            else -> 0f
        }

    companion object {
        /**
         * A conservative default chosen from the device capability. Backdrop blur relies on
         * RenderEffect (API 31+); below that we never attempt blur, so PERFORMANCE is the floor.
         */
        fun safeDefault(): GlassQuality =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BALANCED else PERFORMANCE
    }
}
