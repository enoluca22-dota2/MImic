package com.example.mimic.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.pow

/**
 * Pure ARGB color math for the 3D styles (MImic 4.8). Int ARGB in, Int ARGB out, so the palette
 * and its contrast guarantees are JVM unit-tested without Android.
 */
object DepthColors {
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val x = (a ushr shift) and 0xFF
            val y = (b ushr shift) and 0xFF
            return (x + (y - x) * k).toInt().coerceIn(0, 255) shl shift
        }
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    fun withAlpha(argb: Int, alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24) or (argb and 0x00FFFFFF)

    /** WCAG relative luminance of the opaque color. */
    fun luminance(argb: Int): Double {
        fun lin(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin((argb shr 16) and 0xFF) + 0.7152 * lin((argb shr 8) and 0xFF) + 0.0722 * lin(argb and 0xFF)
    }

    /** WCAG contrast ratio between two opaque colors (1 .. 21). */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Black or white, whichever reads better on [background]. */
    fun onColor(background: Int): Int =
        if (contrast(background, WHITE) >= contrast(background, BLACK)) WHITE else BLACK

    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
}

/**
 * Every color the 3D renderers need, derived once per theme from its tokens (see [depthPalette]).
 * Provided by MImicTheme as [LocalDepth]; FLAT themes get the inert default.
 */
@Immutable
data class DepthPalette(
    val style: DepthStyle = DepthStyle.FLAT,
    val dark: Boolean = true,
    val strength: Float = 1f,
    /** The page / card color. Neumorphic surfaces are this color, lit by the two shadows. */
    val base: Int = 0,
    /** Highlight side (top-left) of a raised neumorphic surface. */
    val lightShadow: Int = 0,
    /** Shade side (bottom-right) of a raised neumorphic surface. */
    val darkShadow: Int = 0,
    /** Physical (skeuomorphic) control body, top to bottom (matte black on the Black & Orange style). */
    val metalTop: Int = 0,
    val metalBottom: Int = 0,
    /** Bevel of panels: the lit top edge and the shaded bottom edge. */
    val rimLight: Int = 0,
    val rimDark: Int = 0,
    /** Bevel and cast shadow of physical controls (Hybrid puts dark controls on a light page). */
    val ctrlRimLight: Int = 0,
    val ctrlRimDark: Int = 0,
    /** Icons / text on a physical control body. */
    val onControl: Int = 0,
    /** Accent (current song, filled tracks). */
    val accent: Int = 0,
    val accentTop: Int = 0,
    val accentBottom: Int = 0,
    val onAccent: Int = 0,
    /** Lit indicators on physical controls: red-orange -> orange -> amber (LEDs, knob ring, glyphs). */
    val glowHot: Int = 0,
    val glow: Int = 0,
    val glowWarm: Int = 0,
    /** Recessed groove (slider tracks, switch tracks). */
    val groove: Int = 0,
) {
    val on: Boolean get() = style != DepthStyle.FLAT
    val matteAmber: Boolean get() = style == DepthStyle.MATTE_AMBER

    /** Pages, cards, sheets and the nav bar use soft neumorphic relief. */
    val softSurfaces: Boolean get() = style == DepthStyle.NEUMORPHIC || style == DepthStyle.HYBRID

    /** Buttons, knobs and switches are drawn as physical objects. */
    val physicalControls: Boolean get() = style == DepthStyle.SKEUOMORPHIC || style == DepthStyle.HYBRID || matteAmber

    /** Equipment look (the Black & Orange reference): bare lit transport glyphs, uppercase labels. */
    val equipment: Boolean get() = style == DepthStyle.SKEUOMORPHIC && dark

    fun c(argb: Int): Color = Color(argb)
}

val LocalDepth = staticCompositionLocalOf { DepthPalette() }

/**
 * Derives a [DepthPalette] from theme tokens. Pure (JVM-tested): the shadows keep the page's hue
 * (never a neutral grey that would look dirty on ice blue), and [DepthPalette.onAccent] always
 * reaches the better of black / white on the accent.
 */
fun depthPalette(
    style: DepthStyle,
    strength: Float,
    dark: Boolean,
    surface: Int,
    primary: Int,
): DepthPalette {
    if (style == DepthStyle.FLAT) return DepthPalette()
    val k = strength.coerceIn(0.4f, 1.6f)
    val white = DepthColors.WHITE
    val black = DepthColors.BLACK
    fun m(a: Int, b: Int, t: Float) = DepthColors.mix(a, b, t)
    // Neumorphic shades lean toward deep navy, not black, so ice blue stays clean (reference 01).
    val lightShadow = if (dark) DepthColors.withAlpha(m(surface, white, 0.10f), (0.70f * k).coerceAtMost(1f))
    else DepthColors.withAlpha(white, (0.95f * k).coerceAtMost(1f))
    val darkShadow = if (dark) DepthColors.withAlpha(m(surface, black, 0.55f), (0.75f * k).coerceAtMost(1f))
    else DepthColors.withAlpha(m(surface, NAVY, 0.30f), (0.70f * k).coerceAtMost(1f))
    // Physical controls: matte black on dark styles; Hybrid always uses dark graphite controls
    // (the Black & Orange hardware) set into its soft page; Ivory uses light enamel.
    val controlBase = when {
        style == DepthStyle.HYBRID || style == DepthStyle.MATTE_AMBER -> GRAPHITE
        dark -> surface
        else -> surface
    }
    val darkControls = style == DepthStyle.HYBRID || dark
    // Black & Orange (equipment) bodies use the reference's measured blue-graphite dome colors
    // (#30303A lit top, #1A1B20 shaded bottom; sampled from reference 02, 4.8.0-beta.2).
    val equipment = style == DepthStyle.SKEUOMORPHIC && dark
    val metalTop = if (style == DepthStyle.MATTE_AMBER) 0xFF2D2D37.toInt() else if (equipment) EQ_DOME_TOP else if (darkControls) m(controlBase, white, 0.09f) else m(controlBase, white, 0.62f)
    val metalBottom = if (style == DepthStyle.MATTE_AMBER) 0xFF1A1A20.toInt() else if (equipment) EQ_DOME_BOTTOM else if (darkControls) m(controlBase, black, 0.38f) else m(controlBase, black, 0.10f)
    val orange = darkControls && style != DepthStyle.NEUMORPHIC
    val glow = if (style == DepthStyle.MATTE_AMBER) 0xFFFF8100.toInt() else if (orange) ORANGE else primary
    return DepthPalette(
        style = style,
        dark = dark,
        strength = k,
        base = surface,
        lightShadow = lightShadow,
        darkShadow = darkShadow,
        metalTop = metalTop,
        metalBottom = metalBottom,
        rimLight = DepthColors.withAlpha(white, if (dark) 0.10f else 0.75f),
        rimDark = DepthColors.withAlpha(black, if (dark) 0.65f else 0.18f),
        ctrlRimLight = DepthColors.withAlpha(white, if (darkControls) 0.13f else 0.75f),
        ctrlRimDark = DepthColors.withAlpha(black, if (darkControls) (if (dark) 0.70f else 0.42f) else 0.22f),
        onControl = if (darkControls) 0xFFD6D6DA.toInt() else DepthColors.onColor(metalBottom).let { if (it == white) white else 0xFF2A241D.toInt() },
        accent = primary,
        accentTop = m(primary, white, 0.22f),
        accentBottom = m(primary, black, 0.22f),
        onAccent = DepthColors.onColor(primary),
        glowHot = if (style == DepthStyle.MATTE_AMBER) 0xFFFF4A00.toInt() else if (equipment) EQ_HOT else if (orange) ORANGE_HOT else m(glow, black, 0.15f),
        glow = glow,
        glowWarm = if (style == DepthStyle.MATTE_AMBER) 0xFFFDFA15.toInt() else if (equipment) EQ_WARM else if (orange) AMBER else m(glow, white, 0.25f),
        groove = when {
            equipment -> EQ_WELL
            dark -> m(surface, black, 0.30f)
            else -> m(surface, NAVY, 0.06f)
        },
    )
}

private const val NAVY = 0xFF1F3868.toInt()
internal val MatteAmberRed = Color(0xFFFF4A00)
internal val MatteAmberOrange = Color(0xFFFEA30A)
internal val MatteAmberYellow = Color(0xFFFDFA15)
internal val MatteGraphiteTop = Color(0xFF2D2D37)
internal val MatteGraphiteBottom = Color(0xFF202126)

internal fun matteAmberSymbolBrush(top: Float, bottom: Float): Brush =
    Brush.linearGradient(
        0f to MatteAmberRed, .48f to MatteAmberOrange, 1f to MatteAmberYellow,
        start = Offset(0f, top), end = Offset(0f, bottom),
    )

private const val GRAPHITE = 0xFF2A2B2F.toInt()
private const val ORANGE = 0xFFFF7A1A.toInt()
private const val ORANGE_HOT = 0xFFFF3D0A.toInt()
private const val AMBER = 0xFFFFC23A.toInt()

// Black & Orange equipment colors, sampled from reference 02 (Desktop\MImic\UI-Reference).
private const val EQ_DOME_TOP = 0xFF30303A.toInt()
private const val EQ_DOME_BOTTOM = 0xFF1A1B20.toInt()
private const val EQ_HOT = 0xFFFF4A00.toInt()
private const val EQ_WARM = 0xFFFFD21A.toInt()
private const val EQ_WELL = 0xFF0D0E10.toInt()

/** Status LEDs on the equipment knob: cyan at the low end (reference 02's right-hand dial). */
const val EQ_CYAN = 0xFF3EE8F0.toInt()

/** The knob's indicator dot: a cream-hot centre (reference 02: #FFE07B). */
const val EQ_CREAM = 0xFFFFE07B.toInt()

/**
 * A 3D panel: cards, tiles, the mini-player, the nav bar, the player control deck. On FLAT themes
 * it is exactly `clip(shape).background(color)`, so callers can swap it in without changing the
 * old look. Shadows are Compose's cached [dropShadow] / [innerShadow]: the blurred shadow is
 * rendered once per size and reused while scrolling — a list of raised cards costs a bitmap draw
 * per card, not a blur per frame.
 *
 * @param inset recessed into the page instead of raised (selected tabs, wells).
 * @param elevation distance of the light source; small tiles use less.
 */
@Composable
fun Modifier.depthPanel(
    shape: Shape,
    color: Color? = null,
    inset: Boolean = false,
    elevation: Dp = 6.dp,
): Modifier {
    val d = LocalDepth.current
    if (!d.on) return this.clip(shape).then(if (color != null) Modifier.background(color) else Modifier)
    // Neumorphism: a card is the page itself pushed up, so the generic card color (surfaceVariant,
    // often at 50-60% alpha in older screens) becomes the page color. Deliberate colors (error,
    // primary containers) are kept, made opaque so the shadows never show through.
    val generic = MaterialTheme.colorScheme.surfaceVariant
    val fill = when {
        color == null -> d.c(d.base)
        d.softSurfaces && color.red == generic.red && color.green == generic.green && color.blue == generic.blue -> d.c(d.base)
        else -> color.copy(alpha = 1f)
    }
    val e = elevation * d.strength
    return if (d.softSurfaces) {
        if (inset) {
            this.clip(shape).background(fill)
                .innerShadow(shape, Shadow(radius = e, color = d.c(d.darkShadow), offset = DpOffset(e * 0.5f, e * 0.5f)))
                .innerShadow(shape, Shadow(radius = e, color = d.c(d.lightShadow), offset = DpOffset(-e * 0.5f, -e * 0.5f)))
        } else {
            this
                .dropShadow(shape, Shadow(radius = e * 2f, color = d.c(d.darkShadow), offset = DpOffset(e, e)))
                .dropShadow(shape, Shadow(radius = e * 2f, color = d.c(d.lightShadow), offset = DpOffset(-e, -e)))
                .clip(shape)
                .background(fill)
        }
    } else {
        // Skeuomorphic material: a soft cast shadow below, a faint top-lit body and a bevel rim.
        val argb = fill.toArgbInt()
        if (inset) {
            this.clip(shape)
                .background(Brush.verticalGradient(listOf(d.c(DepthColors.mix(argb, DepthColors.BLACK, 0.10f)), fill)))
                .innerShadow(shape, Shadow(radius = e * 0.8f, color = d.c(d.rimDark), offset = DpOffset(0.dp, e * 0.4f)))
                .border(1.dp, Brush.verticalGradient(listOf(Color.Transparent, d.c(d.rimLight).copy(alpha = 0.35f))), shape)
        } else {
            this
                .dropShadow(shape, Shadow(radius = e * 1.4f, color = d.c(d.rimDark), offset = DpOffset(0.dp, e * 0.6f)))
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            d.c(DepthColors.mix(argb, DepthColors.WHITE, if (d.dark) 0.06f else 0.35f)),
                            d.c(DepthColors.mix(argb, DepthColors.BLACK, if (d.dark) 0.08f else 0.03f)),
                        ),
                    ),
                )
                .border(
                    1.dp,
                    Brush.verticalGradient(listOf(d.c(d.rimLight), Color.Transparent, d.c(d.rimDark).copy(alpha = 0.4f))),
                    shape,
                )
        }
    }
}

/**
 * The body of a 3D button / chip / knob cap, in each of its states:
 *  - neumorphic (reference 01): raised; pressed or [selected] sinks into the page with a faint
 *    accent tint. [accent] (play/pause) is a raised ring around a pressed-in tinted centre.
 *  - physical (reference 02): a matte, top-lit dome with a hairline bevel casting a shadow;
 *    pressed flips the light and the cast shadow collapses (pushed in). [accent] = a bigger dome.
 * [enabled] = false flattens it and drops the shadow so it never looks pressable.
 */
@Composable
fun Modifier.depthControl(
    shape: Shape,
    pressed: Boolean = false,
    selected: Boolean = false,
    accent: Boolean = false,
    enabled: Boolean = true,
    elevation: Dp = 5.dp,
    physical: Boolean = LocalDepth.current.physicalControls,
): Modifier {
    val d = LocalDepth.current
    if (!d.on) return this.clip(shape)
    val e = elevation * d.strength
    if (!enabled) {
        return this.clip(shape).background(d.c(if (physical) d.metalBottom else d.base)).border(1.dp, d.c(d.rimDark).copy(alpha = 0.12f), shape)
    }
    if (!physical) {
        val sunk = pressed || (selected && !accent)
        val tint = if (selected || accent) DepthColors.mix(d.base, d.accent, if (d.dark) 0.16f else 0.10f) else d.base
        val fill = Brush.linearGradient(
            listOf(
                d.c(DepthColors.mix(tint, DepthColors.WHITE, if (d.dark) 0.03f else 0.30f)),
                d.c(DepthColors.mix(tint, DepthColors.BLACK, if (d.dark) 0.06f else 0.03f)),
            ),
        )
        return if (sunk) {
            this.clip(shape).background(fill)
                .innerShadow(shape, Shadow(radius = e, color = d.c(d.darkShadow), offset = DpOffset(e * 0.5f, e * 0.5f)))
                .innerShadow(shape, Shadow(radius = e, color = d.c(d.lightShadow), offset = DpOffset(-e * 0.5f, -e * 0.5f)))
        } else {
            this
                .dropShadow(shape, Shadow(radius = e * 2.2f, color = d.c(d.darkShadow), offset = DpOffset(e, e)))
                .dropShadow(shape, Shadow(radius = e * 2.2f, color = d.c(d.lightShadow), offset = DpOffset(-e, -e)))
                .clip(shape)
                .background(Brush.linearGradient(listOf(d.c(DepthColors.mix(d.base, DepthColors.WHITE, if (d.dark) 0.03f else 0.30f)), d.c(d.base))))
                .then(
                    if (accent) {
                        // Raised ring, pressed-in centre (reference 01's play/pause).
                        Modifier.padding(e * 1.4f).clip(shape).background(fill)
                            .innerShadow(shape, Shadow(radius = e * 0.8f, color = d.c(d.darkShadow), offset = DpOffset(e * 0.4f, e * 0.4f)))
                            .innerShadow(shape, Shadow(radius = e * 0.8f, color = d.c(d.lightShadow), offset = DpOffset(-e * 0.4f, -e * 0.4f)))
                    } else {
                        Modifier
                    },
                )
        }
    }
    // Matte dome lit from the top-left; pressed: darker, light from below, almost no cast shadow.
    val top = d.c(d.metalTop)
    val bottom = d.c(d.metalBottom)
    val body = if (pressed) {
        Brush.verticalGradient(listOf(d.c(DepthColors.mix(d.metalBottom, DepthColors.BLACK, 0.15f)), d.c(DepthColors.mix(d.metalTop, DepthColors.BLACK, 0.10f))))
    } else {
        Brush.verticalGradient(listOf(top, bottom))
    }
    val cast = if (accent) 1.6f else 1.2f
    val shadowed = if (pressed) {
        this.dropShadow(shape, Shadow(radius = e * 0.5f, color = d.c(d.ctrlRimDark), offset = DpOffset(0.dp, e * 0.15f)))
    } else {
        this
            .dropShadow(shape, Shadow(radius = e * 1.6f * cast, color = d.c(d.ctrlRimDark), offset = DpOffset(e * 0.25f, e * 0.8f * cast)))
            .dropShadow(shape, Shadow(radius = e * 0.6f, color = d.c(d.ctrlRimLight).copy(alpha = 0.06f), offset = DpOffset(-e * 0.2f, -e * 0.3f)))
    }
    return shadowed
        .clip(shape)
        .background(body)
        .then(
            if (pressed) {
                Modifier.innerShadow(shape, Shadow(radius = e * 0.8f, color = Color.Black.copy(alpha = 0.55f), offset = DpOffset(0.dp, e * 0.35f)))
            } else {
                // Soft specular on the upper-left of the dome (matte: wide and faint, never glossy).
                Modifier.innerShadow(shape, Shadow(radius = e * 1.2f, color = d.c(d.ctrlRimLight).copy(alpha = 0.10f), offset = DpOffset(e * 0.3f, e * 0.4f)))
            },
        )
        .border(
            1.dp,
            Brush.verticalGradient(
                if (pressed) listOf(d.c(d.ctrlRimDark), d.c(d.ctrlRimLight).copy(alpha = 0.25f))
                else listOf(d.c(d.ctrlRimLight), Color.Transparent, d.c(d.ctrlRimDark)),
            ),
            shape,
        )
}

/**
 * Fine matte grain (reference 02's powder-coated finish): one 96×96 noise tile generated once per
 * process and tiled by the GPU — no per-frame work. Only the Black & Orange style uses it.
 */
private val matteTile by lazy { makeMatteTile(6, 10) }
private val graphiteTile by lazy { makeMatteTile(8, 24) }

private fun makeMatteTile(minAlpha: Int, alphaRange: Int): androidx.compose.ui.graphics.ImageBitmap {
    val size = 96
    val rnd = java.util.Random(4_808L)
    val px = IntArray(size * size) {
        val v = if (minAlpha == 6) rnd.nextInt(256) else if (rnd.nextBoolean()) 255 else 0
        val a = minAlpha + rnd.nextInt(alphaRange)
        (a shl 24) or (v shl 16) or (v shl 8) or v
    }
    val bmp = android.graphics.Bitmap.createBitmap(px, size, size, android.graphics.Bitmap.Config.ARGB_8888)
    return bmp.asImageBitmap()
}

@Composable
fun Modifier.matteGrain(shape: Shape = RectangleShape): Modifier {
    val d = LocalDepth.current
    if (!d.equipment && !d.matteAmber) return this
    val brush = androidx.compose.runtime.remember(d.matteAmber) {
        androidx.compose.ui.graphics.ShaderBrush(
            androidx.compose.ui.graphics.ImageShader(if (d.matteAmber) graphiteTile else matteTile, androidx.compose.ui.graphics.TileMode.Repeated, androidx.compose.ui.graphics.TileMode.Repeated),
        )
    }
    return if (shape == RectangleShape) this.drawBehind { drawRect(brush) }
        else this.clip(shape).drawBehind { drawRect(brush) }
}

@Composable
internal fun Modifier.mattePlayerBackground(): Modifier = background(
    Brush.verticalGradient(listOf(Color(0xFF29292E), Color(0xFF19191D), Color(0xFF111114))),
).matteGrain()

/** Color -> packed ARGB Int (sRGB), for the pure [DepthColors] math. */
fun Color.toArgbInt(): Int {
    fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return (ch(alpha) shl 24) or (ch(red) shl 16) or (ch(green) shl 8) or ch(blue)
}
