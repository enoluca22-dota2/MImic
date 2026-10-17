package com.example.mimic.data

/** How the Now Playing screen is arranged. Independent of theme colors. */
enum class PlayerLayout { CLASSIC, IMMERSIVE, COMPACT }

enum class ArtworkShape { ROUNDED, CIRCLE, SQUARE }

enum class ArtworkSize { SMALL, MEDIUM, LARGE }

data class PlayerLayoutConfig(
    val layout: PlayerLayout = PlayerLayout.CLASSIC,
    val artworkShape: ArtworkShape = ArtworkShape.ROUNDED,
    val artworkSize: ArtworkSize = ArtworkSize.LARGE,
    val showShuffleRepeat: Boolean = true,
    val showSecondaryActions: Boolean = true,
)

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
}
