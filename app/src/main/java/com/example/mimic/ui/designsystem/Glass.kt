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

/**
 * A Liquid Glass panel. Picks the best renderer the device and the quality mode allow:
 *
 *  - HIGH/BALANCED on API 31+ with a live [backdrop]: real backdrop blur + vibrancy, plus lens
 *    refraction on API 33+ at HIGH, rim highlight and soft shadow (Kyant Backdrop).
 *  - PERFORMANCE, older devices, or no live backdrop: a denser translucent tint with a gradient
 *    rim — no shaders, no blur, no per-frame work.
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
    val blurPx = with(LocalDensity.current) { (style.blurRadius * env.quality.blurFactor).dp.toPx() }
    val canBlur = backdrop != null && blurPx > 0.5f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    if (canBlur) {
        // Readability floor: a clear panel is only legible when the content behind it is blurred
        // enough. Strong blur (>=16dp effective) may go down to 25% tint; no blur keeps 92%.
        val effectiveBlurDp = style.blurRadius * env.quality.blurFactor
        val floor = 0.92f - 0.67f * (effectiveBlurDp / 16f).coerceIn(0f, 1f)
        val readableOpacity = opacity.coerceAtLeast(floor)
        val lensOn = env.quality.allowsRefraction && style.refraction > 0f &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val refraction = style.refraction
        val edge = style.edgeHighlight
        val shadowAlpha = style.shadow
        return this.drawBackdrop(
            backdrop = backdrop!!,
            shape = { shape },
            effects = {
                vibrancy()
                blur(blurPx)
                if (lensOn) {
                    lens(
                        refractionHeight = 14.dp.toPx() * refraction,
                        refractionAmount = 28.dp.toPx() * refraction,
                        depthEffect = refraction > 0.5f,
                    )
                }
            },
            highlight = if (edge > 0f) ({ Highlight(alpha = edge) }) else null,
            shadow = if (shadowAlpha > 0f) ({ Shadow(alpha = shadowAlpha) }) else null,
            onDrawSurface = { drawRect(baseTint.copy(alpha = readableOpacity)) },
        ).clip(shape)
    }

    // Translucent fallback: no live blur, so content behind would show through sharply — keep the
    // tint dense enough that text on the panel always stays readable.
    val fallbackAlpha = opacity.coerceAtLeast(0.92f)
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
