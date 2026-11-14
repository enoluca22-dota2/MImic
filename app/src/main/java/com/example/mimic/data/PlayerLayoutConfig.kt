package com.example.mimic.data

/** How the Now Playing screen is arranged. Independent of theme colors. */
/** DYNAMIC (4.1): the artwork-colored "Dynamic Now Playing" player — see [DynamicPlayerConfig]. */
enum class PlayerLayout { CLASSIC, IMMERSIVE, COMPACT, DYNAMIC }

enum class ArtworkShape { ROUNDED, CIRCLE, SQUARE, SQUIRCLE }

enum class ArtworkSize { SMALL, MEDIUM, LARGE }

/** What is painted behind the Now Playing content. Always on top of an opaque base (2.8.4 fix). */
enum class PlayerBackground { AUTO, SOLID, GRADIENT, BLURRED_ARTWORK }

/**
 * Transport control styling. AUTO = glass on glass themes, 3D on 3D themes, filled otherwise.
 * Append-only (persisted by name, shown by ordinal). TACTILE (4.8) = the 3D buttons, knob and
 * groove slider of the active 3D style.
 */
enum class ControlStyle { AUTO, FILLED, GLASS, MINIMAL, TACTILE }

/** Now Playing volume control (4.8). AUTO = a dial with 3D controls, none otherwise. */
enum class VolumeControl { AUTO, OFF, SLIDER, DIAL }

data class PlayerLayoutConfig(
    val layout: PlayerLayout = PlayerLayout.CLASSIC,
    val artworkShape: ArtworkShape = ArtworkShape.ROUNDED,
    val artworkSize: ArtworkSize = ArtworkSize.LARGE,
    val showShuffleRepeat: Boolean = true,
    val showSecondaryActions: Boolean = true,
    val background: PlayerBackground = PlayerBackground.AUTO,
    val controlStyle: ControlStyle = ControlStyle.AUTO,
    val volumeControl: VolumeControl = VolumeControl.AUTO,
)

/** AUTO resolution: glass themes get the blurred-artwork backdrop, Immersive the accent gradient. */
fun PlayerLayoutConfig.resolvedBackground(glassTheme: Boolean, depthTheme: Boolean = false): PlayerBackground = when {
    background != PlayerBackground.AUTO -> background
    glassTheme -> PlayerBackground.BLURRED_ARTWORK
    // 3D surfaces are lit against the page color; a busy backdrop would break the illusion.
    depthTheme -> PlayerBackground.SOLID
    layout == PlayerLayout.IMMERSIVE -> PlayerBackground.GRADIENT
    else -> PlayerBackground.SOLID
}

fun PlayerLayoutConfig.resolvedControls(glassTheme: Boolean, depthTheme: Boolean = false): ControlStyle = when {
    controlStyle != ControlStyle.AUTO -> controlStyle
    glassTheme -> ControlStyle.GLASS
    depthTheme -> ControlStyle.TACTILE
    else -> ControlStyle.FILLED
}

/**
 * Which volume control Now Playing shows: null = none. AUTO gives a dial only with 3D controls,
 * so existing themes keep their player exactly as it was.
 */
fun PlayerLayoutConfig.resolvedVolume(controls: ControlStyle): VolumeControl? = when (volumeControl) {
    VolumeControl.OFF -> null
    VolumeControl.SLIDER -> VolumeControl.SLIDER
    VolumeControl.DIAL -> VolumeControl.DIAL
    VolumeControl.AUTO -> if (controls == ControlStyle.TACTILE) VolumeControl.DIAL else null
}

/** Fraction of available width the artwork should occupy. Pure (unit-testable). */
fun ArtworkSize.fraction(): Float = when (this) {
    ArtworkSize.SMALL -> 0.55f
    ArtworkSize.MEDIUM -> 0.72f
    ArtworkSize.LARGE -> 0.90f
}

/** Corner radius in dp for ROUNDED/SQUARE. CIRCLE is handled with CircleShape in the UI. */
fun ArtworkShape.cornerDp(): Int = when (this) {
    ArtworkShape.ROUNDED -> 16
    ArtworkShape.SQUARE -> 0
    ArtworkShape.CIRCLE -> 0
    ArtworkShape.SQUIRCLE -> 28
}

// ---- Dynamic Now Playing (MImic 4.1) -----------------------------------------------------------

/** How strongly the Dynamic player animates. Transition times stay within 500–900 ms. */
enum class DynamicIntensity(val label: String) { SUBTLE("Subtle"), BALANCED("Balanced"), IMMERSIVE("Immersive") }

enum class DynamicArtShape(val label: String, val cornerDp: Int) { SQUARE("Square", 4), ROUNDED("Rounded", 18), EXTRA_ROUNDED("Extra rounded", 36) }

enum class DynamicArtSize(val label: String, val fraction: Float) { COMPACT("Compact", 0.64f), NORMAL("Normal", 0.8f), LARGE("Large", 0.92f) }

/** In-app "lock-screen style" full-screen views (not Android's secure lock screen). */
enum class LockStyle(val label: String) { CLASSIC("Classic"), DYNAMIC("Dynamic"), GLASS("Glass"), MINIMAL("Minimal") }

/**
 * Settings of the Dynamic Now Playing player only — changing them never touches the Classic,
 * Immersive or Compact layouts or any theme. Persisted in settings.
 */
data class DynamicPlayerConfig(
    val gradient: Boolean = true,
    val artworkBlur: Boolean = true,
    val animatedBackground: Boolean = true,
    val intensity: DynamicIntensity = DynamicIntensity.BALANCED,
    val artShape: DynamicArtShape = DynamicArtShape.EXTRA_ROUNDED,
    val glassControls: Boolean = true,
    val artSize: DynamicArtSize = DynamicArtSize.NORMAL,
    /** -0.4 (darker) … +0.4 (brighter) applied to the artwork-derived background. */
    val brightness: Float = 0f,
    /** Text/controls pick black or white for contrast; a minimum contrast is enforced either way. */
    val autoContrast: Boolean = true,
    /** Reduced motion: no animated background, instant color changes (also follows the system setting). */
    val reduceMotion: Boolean = false,
    val lockStyle: LockStyle = LockStyle.DYNAMIC,
)

/** Color transition length; 0 with reduced motion (pure, unit-tested). */
fun DynamicPlayerConfig.transitionMs(systemReducedMotion: Boolean): Int =
    if (reduceMotion || systemReducedMotion) 0 else when (intensity) {
        DynamicIntensity.SUBTLE -> 900
        DynamicIntensity.BALANCED -> 700
        DynamicIntensity.IMMERSIVE -> 500
    }

/** Whether the slow background motion runs (pure, unit-tested). */
fun DynamicPlayerConfig.animatesBackground(systemReducedMotion: Boolean): Boolean =
    animatedBackground && !reduceMotion && !systemReducedMotion
