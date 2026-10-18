package com.example.mimic.ui.designsystem

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.mimic.data.GlassQuality
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

/**
 * Everything a composable needs to render the active theme's *style* (MImic 2.9): the sanitized
 * [ThemeStyle], the device [GlassQuality], and the resolved motion settings. Provided once by
 * MImicTheme; read anywhere via [LocalGlass].
 */
@Immutable
data class GlassEnv(
    val style: ThemeStyle = ThemeStyle(),
    val quality: GlassQuality = GlassQuality.OFF,
    val isDarkTheme: Boolean = true,
    /** System "remove animations" / animator scale 0. Forces motion off regardless of theme. */
    val reducedMotion: Boolean = false,
) {
    /** Glass surfaces are drawn only when the theme asks for them AND the device setting allows. */
    val enabled: Boolean get() = style.glass && quality.allowsGlass

    /** 0 when motion is off (theme toggle or system reduced-motion), else the theme's intensity. */
    val motionScale: Float get() = if (!style.motion || reducedMotion) 0f else style.animationScale

    val darkGlass: Boolean
        get() = when (style.glassTone) {
            GlassTone.LIGHT -> false
            GlassTone.DARK -> true
            GlassTone.AUTO -> isDarkTheme
        }

    val headerFontWeight: FontWeight get() = FontWeight(style.headerWeight)
    val titleFontWeight: FontWeight get() = FontWeight(style.titleWeight)
    val artistFontWeight: FontWeight get() = FontWeight(style.artistWeight)
}

val LocalGlass = staticCompositionLocalOf { GlassEnv() }

/**
 * The live content layer glass surfaces refract/blur. Provided by screens that record their
 * content with `Modifier.layerBackdrop`; null means "no live backdrop here" and glass falls back
 * to a denser translucent tint (never a blank or see-through panel).
 */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Corner shape for cards & panels, from the theme's corner radius (scaled by [factor]). */
fun ThemeStyle.surfaceShape(factor: Float = 1f): Shape =
    RoundedRectangle((cornerRadius * factor).dp)

/** Shape for buttons/chips from the theme's [ControlShape]. */
fun ThemeStyle.controlShapeValue(): Shape = when (controlShape) {
    ControlShape.CAPSULE -> Capsule()
    ControlShape.SQUIRCLE -> RoundedRectangle((cornerRadius * 0.9f).coerceAtLeast(12f).dp)
    ControlShape.ROUNDED -> RoundedCornerShape((cornerRadius * 0.6f).dp)
}

/** Round controls stay perfectly circular for CAPSULE/ROUNDED, and go continuous for SQUIRCLE. */
fun ThemeStyle.roundControlShape(): Shape =
    if (controlShape == ControlShape.SQUIRCLE) RoundedRectangle(22.dp) else CircleShape

/** The glass tint for the current tone: an explicit theme tint wins over the tone default. */
@Composable
fun glassTint(env: GlassEnv = LocalGlass.current): Color = when {
    env.style.glassTint != 0L -> Color(env.style.glassTint)
    env.style.glassTone == GlassTone.LIGHT -> Color(0xFFF7F9FC)
    env.style.glassTone == GlassTone.DARK -> Color(0xFF0D1118)
    else -> MaterialTheme.colorScheme.surface
}

/** What a glass surface is actually rendering right now — shown to the user, never hidden. */
enum class GlassRenderMode(val label: String) {
    LIVE_LENS("Live blur + lens refraction"),
    LIVE_BLUR("Live blur (lens needs Android 13+)"),
    TINTED("Tinted translucency, no blur"),
    OPAQUE("Off — solid surfaces"),
}

/** The mode [glassSurface] will use for [env] with or without a live backdrop. Pure. */
fun glassRenderMode(env: GlassEnv, hasBackdrop: Boolean, sdk: Int = Build.VERSION.SDK_INT): GlassRenderMode = when {
    !env.enabled -> GlassRenderMode.OPAQUE
    !hasBackdrop || env.quality.blurFactor == 0f || sdk < Build.VERSION_CODES.S -> GlassRenderMode.TINTED
    env.quality.allowsRefraction && env.style.refraction > 0f && sdk >= Build.VERSION_CODES.TIRAMISU -> GlassRenderMode.LIVE_LENS
    else -> GlassRenderMode.LIVE_BLUR
}

/**
 * Minimum tint alpha that keeps text on a glass panel legible, given how strongly the content
 * behind is blurred (dp). Clear glass needs a little blur *or* a little tint — not a near-opaque
 * slab. 2.9 used 0.92 -> 0.25 here, which is why its "clear" glass looked like solid dark cards.
 * Pure + JVM-tested.
 */
fun glassReadabilityFloor(effectiveBlurDp: Float): Float =
    (0.08f + 0.02f * (6f - effectiveBlurDp).coerceAtLeast(0f)).coerceIn(0.08f, 0.2f)

/**
 * A Liquid Glass panel (MImic 3.0 rework). Picks the best renderer the device and quality allow:
 *
 *  - HIGH / BALANCED on API 31+ with a live [backdrop]: the content *behind* the panel (the
 *    scrolling list, artwork, the theme gradient) is captured and drawn through it with vibrancy,
 *    blur, and on API 33+ lens refraction at the edges, plus a rim highlight and soft shadow
 *    (Kyant Backdrop, the engine behind AndroidLiquidGlass). Only a light tint goes on top, so the
 *    content stays visible — that is what makes it glass rather than a dark rounded card.
 *  - PERFORMANCE, older devices, or no live backdrop: a translucent tint with a gradient rim — no
 *    shaders, no blur, no per-frame work. Content shows through, unblurred.
 *  - Glass disabled (OFF or a flat theme): the plain opaque [fallback] surface (pre-2.9 look).
 *
 * The panel never intercepts touches by itself and draws nothing outside [shape], so it cannot
 * become an invisible touch-blocking overlay. Apply it only to a few large surfaces (nav bar,
 * mini-player, player controls) — never to list rows.
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape,
    backdrop: Backdrop? = LocalGlassBackdrop.current,
    fallback: Color = MaterialTheme.colorScheme.surfaceVariant,
    tint: Color? = null,
    opacityBoost: Float = 0f,
): Modifier {
    val env = LocalGlass.current
    if (!env.enabled) return this.clip(shape).background(fallback)

    val style = env.style
    val baseTint = tint ?: glassTint(env)
    val opacity = (style.glassOpacity + opacityBoost).coerceIn(0f, 1f)
    val rim = Color.White
    val effectiveBlurDp = style.blurRadius * env.quality.blurFactor
    val blurPx = with(LocalDensity.current) { effectiveBlurDp.dp.toPx() }
    val mode = glassRenderMode(env, backdrop != null)

    if (mode == GlassRenderMode.LIVE_LENS || mode == GlassRenderMode.LIVE_BLUR) {
        val readableOpacity = opacity.coerceAtLeast(glassReadabilityFloor(effectiveBlurDp))
        val lensOn = mode == GlassRenderMode.LIVE_LENS
        val refraction = style.refraction * env.quality.refractionFactor
        val edge = style.edgeHighlight
        val shadowAlpha = style.shadow
        return this.drawBackdrop(
            backdrop = backdrop!!,
            shape = { shape },
            effects = {
                vibrancy()
                if (blurPx > 0.5f) blur(blurPx)
                if (lensOn) {
                    lens(
                        refractionHeight = 16.dp.toPx() * refraction,
                        refractionAmount = 32.dp.toPx() * refraction,
                        depthEffect = refraction > 0.5f,
                    )
                }
            },
            highlight = if (edge > 0f) ({ Highlight(alpha = edge) }) else null,
            shadow = if (shadowAlpha > 0f) ({ Shadow(alpha = shadowAlpha) }) else null,
            onDrawSurface = { drawRect(baseTint.copy(alpha = readableOpacity)) },
        ).clip(shape)
    }

    // Tinted translucency: no live blur, so what's behind shows through *sharply* and would fight
    // the labels on the panel (verified on-device: at 62% list text bled through the nav labels).
    // 84% keeps the labels clean while colors and shapes behind still tint the panel.
    val fallbackAlpha = opacity.coerceIn(0.84f, 0.9f)
    val shadowDp = (10f * style.shadow).dp
    return this
        .then(if (style.shadow > 0f) Modifier.shadow(shadowDp, shape, clip = false) else Modifier)
        .clip(shape)
        .background(baseTint.copy(alpha = fallbackAlpha))
        .then(
            if (style.edgeHighlight > 0f) {
                Modifier.border(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(
                            rim.copy(alpha = 0.45f * style.edgeHighlight),
                            rim.copy(alpha = 0.06f * style.edgeHighlight),
                        ),
                    ),
                    shape,
                )
            } else {
                Modifier
            },
        )
}

/**
 * Press feedback: a short spring scale-down while pressed, scaled by the theme's motion
 * intensity. Idle cost is zero — the animation only runs while a press is in progress.
 */
@Composable
fun Modifier.pressScale(interaction: InteractionSource, maxShrink: Float = 0.06f): Modifier {
    val env = LocalGlass.current
    val pressed by interaction.collectIsPressedAsState()
    val target = if (pressed) 1f - maxShrink * env.motionScale else 1f
    val scale by animateFloatAsState(
        target,
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium * env.style.transitionSpeed),
        label = "pressScale",
    )
    return if (env.motionScale == 0f) this else this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * Makes a full-screen overlay (Now Playing, theme editor) the hit target for every touch inside
 * it. A background-only Box is NOT a pointer target in Compose, so taps on its empty areas used to
 * fall through to the screen underneath (e.g. starting another song from the list behind the
 * player). This observes events without consuming them, so the overlay's own controls still work.
 */
fun Modifier.blockTouchesBehind(): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}
