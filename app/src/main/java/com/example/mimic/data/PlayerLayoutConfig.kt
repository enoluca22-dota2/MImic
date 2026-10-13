package com.example.mimic.data

/** How the Now Playing screen is arranged. Independent of theme colors. */
enum class PlayerLayout { CLASSIC, IMMERSIVE, COMPACT }

enum class ArtworkShape { ROUNDED, CIRCLE, SQUARE, SQUIRCLE }

enum class ArtworkSize { SMALL, MEDIUM, LARGE }

/** What is painted behind the Now Playing content. Always on top of an opaque base (2.8.4 fix). */
enum class PlayerBackground { AUTO, SOLID, GRADIENT, BLURRED_ARTWORK }

/** Transport control styling. AUTO = glass on glass themes, filled otherwise. */
enum class ControlStyle { AUTO, FILLED, GLASS, MINIMAL }

data class PlayerLayoutConfig(
    val layout: PlayerLayout = PlayerLayout.CLASSIC,
    val artworkShape: ArtworkShape = ArtworkShape.ROUNDED,
    val artworkSize: ArtworkSize = ArtworkSize.LARGE,
    val showShuffleRepeat: Boolean = true,
    val showSecondaryActions: Boolean = true,
    val background: PlayerBackground = PlayerBackground.AUTO,
    val controlStyle: ControlStyle = ControlStyle.AUTO,
)

/** AUTO resolution: glass themes get the blurred-artwork backdrop, Immersive the accent gradient. */
fun PlayerLayoutConfig.resolvedBackground(glassTheme: Boolean): PlayerBackground = when {
    background != PlayerBackground.AUTO -> background
    glassTheme -> PlayerBackground.BLURRED_ARTWORK
    layout == PlayerLayout.IMMERSIVE -> PlayerBackground.GRADIENT
    else -> PlayerBackground.SOLID
}

fun PlayerLayoutConfig.resolvedControls(glassTheme: Boolean): ControlStyle = when {
    controlStyle != ControlStyle.AUTO -> controlStyle
    glassTheme -> ControlStyle.GLASS
    else -> ControlStyle.FILLED
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
