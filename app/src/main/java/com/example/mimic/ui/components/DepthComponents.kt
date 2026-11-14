package com.example.mimic.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.mimic.player.MediaVolumeState
import com.example.mimic.player.volumeStepFor
import com.example.mimic.ui.designsystem.LocalDepth
import com.example.mimic.ui.designsystem.LocalGlass
import com.example.mimic.ui.designsystem.depthControl
import com.example.mimic.ui.designsystem.depthPanel
import com.example.mimic.ui.designsystem.pressScale
import com.kyant.shapes.Capsule
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * MImic 4.8 — the 3D style component kit (Neumorphism / Skeuomorphism / Hybrid Soft 3D).
 * Every component reads the active theme's [LocalDepth]; on FLAT themes the Mimic* wrappers
 * render the exact Material component they replace, so existing themes look the same.
 */

/**
 * A round 3D button with pressed, released, [selected] and disabled states (≥48dp target).
 * [accent] = the main action (play/pause). On physical styles a selected button also lights a
 * small LED, so "on" never depends on color alone.
 */
@Composable
fun DepthIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    accent: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
    tint: Color? = null,
    /** Spoken state for toggles (shuffle "On"/"Off"); null for plain buttons. */
    stateDescription: String? = null,
    shape: Shape = CircleShape,
    /** Equipment transport (reference 02): no button body, just a lit glyph that dims when pressed. */
    bare: Boolean = false,
) {
    val d = LocalDepth.current
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .size(size)
            .pressScale(interaction, maxShrink = if (bare) 0.10f else 0.05f)
            .then(
                if (bare && d.on) {
                    Modifier
                } else {
                    Modifier.depthControl(
                        shape,
                        pressed = pressed && enabled,
                        selected = selected,
                        accent = accent,
                        enabled = enabled,
                        elevation = if (size >= 64.dp) 7.dp else if (size >= 52.dp) 5.dp else 4.dp,
                    )
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                if (stateDescription != null) {
                    this.stateDescription = stateDescription
                    this.selected = selected
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val lit = d.physicalControls && enabled && (accent || selected || bare)
        if (lit && tint == null) {
            GlowIcon(icon, contentDescription, iconSize, dim = bare && pressed)
        } else {
            val iconTint = when {
                !enabled -> (if (d.physicalControls) d.c(d.onControl) else scheme.onSurface).copy(alpha = 0.38f)
                tint != null -> tint
                !d.on && accent -> scheme.onPrimary
                d.physicalControls -> d.c(d.onControl)
                // Reference 01: near-black glyphs; state shows as the pressed-in, tinted body.
                d.on -> scheme.onSurface
                selected -> scheme.primary
                else -> scheme.onSurface
            }
            Icon(icon, contentDescription = contentDescription, tint = iconTint, modifier = Modifier.size(iconSize))
        }
        if (selected && enabled && d.physicalControls && !accent) {
            // LED: a lit dot under the icon with a soft glow.
            val led = d.c(d.glow)
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = (size * 0.07f).coerceAtLeast(3.dp)).size(4.dp)
                    .drawBehind {
                        drawCircle(led.copy(alpha = 0.35f), radius = this.size.minDimension * 1.3f)
                        drawCircle(led)
                    },
            )
        }
    }
}

/**
 * A glyph lit from inside (reference 02): a red-orange -> amber gradient through the icon's shape
 * plus a faint halo. One offscreen layer per icon; nothing animates while idle.
 */
@Composable
fun GlowIcon(icon: ImageVector, contentDescription: String?, size: Dp, dim: Boolean = false) {
    val d = LocalDepth.current
    val hot = d.c(d.glowHot)
    val mid = d.c(d.glow)
    val warm = d.c(d.glowWarm)
    Box(
        Modifier.size(size).drawBehind {
            drawCircle(Brush.radialGradient(listOf(mid.copy(alpha = if (dim) 0.08f else 0.22f), Color.Transparent)), radius = this.size.minDimension * 0.75f)
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(size)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; alpha = if (dim) 0.55f else 1f }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.linearGradient(listOf(hot, mid, warm), start = Offset(0f, this.size.height), end = Offset(this.size.width, 0f)),
                        blendMode = BlendMode.SrcIn,
                    )
                },
        )
    }
}

/**
 * Drop-in for Material's [Slider]: on 3D themes the track is a recessed groove with a lit fill and
 * the thumb a raised (or metal) knob; on FLAT themes it IS the Material slider with [colors].
 * Dragging, steps, keyboard and TalkBack behave exactly like the Material slider in both cases.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MimicSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors? = null,
) {
    val d = LocalDepth.current
    if (!d.on) {
        Slider(
            value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled,
            valueRange = valueRange, steps = steps, onValueChangeFinished = onValueChangeFinished,
            colors = colors ?: SliderDefaults.colors(),
        )
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val dragged by interaction.collectIsDraggedAsState()
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interaction,
        thumb = { DepthThumb(pressed || dragged, enabled) },
        track = { state -> DepthTrack(state.fraction(), enabled) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
private fun SliderState.fraction(): Float {
    val span = valueRange.endInclusive - valueRange.start
    return if (span <= 0f) 0f else ((value - valueRange.start) / span).coerceIn(0f, 1f)
}

@Composable
private fun DepthThumb(pressed: Boolean, enabled: Boolean) {
    val d = LocalDepth.current
    val accent = MaterialTheme.colorScheme.primary
    val led = if (d.physicalControls) d.c(d.glow) else accent
    Box(
        Modifier.size(if (d.physicalControls) 22.dp else 24.dp).depthControl(CircleShape, pressed = pressed, enabled = enabled, elevation = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Grip: a glowing LED on the dark knob (ref 02), a soft accent dot on the white thumb (ref 01).
        Box(
            Modifier.size(if (d.physicalControls) 6.dp else 7.dp).drawBehind {
                if (d.physicalControls && enabled) drawCircle(led.copy(alpha = 0.35f), radius = this.size.minDimension * 1.4f)
                drawCircle(if (enabled) led else led.copy(alpha = 0.3f))
            },
        )
    }
}

@Composable
private fun DepthTrack(fraction: Float, enabled: Boolean) {
    val d = LocalDepth.current
    val physical = d.physicalControls
    val start = if (physical) d.c(d.glowHot) else MaterialTheme.colorScheme.primary
    val end = if (physical) d.c(d.glow) else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    Box(
        Modifier.fillMaxWidth().height(if (d.equipment) 6.dp else 7.dp)
            .depthPanel(Capsule(), color = d.c(d.groove), inset = true, elevation = 2.dp)
            .drawBehind {
                if (fraction <= 0f) return@drawBehind
                val h = size.height
                val w = (size.width * fraction).coerceAtLeast(h)
                val a = if (enabled) 1f else 0.35f
                drawRoundRect(
                    Brush.horizontalGradient(listOf(start.copy(alpha = a), end.copy(alpha = a)), endX = w),
                    size = androidx.compose.ui.geometry.Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2f),
                )
            },
    )
}

/**
 * Drop-in for Material's [Switch]. 3D themes: a recessed track and a raised knob (metal with an
 * LED on physical styles) that slides over; FLAT themes: the Material switch.
 */
@Composable
fun MimicSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val d = LocalDepth.current
    if (!d.on) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
        return
    }
    val env = LocalGlass.current
    val accent = MaterialTheme.colorScheme.primary
    val knobX by animateDpAsState(
        if (checked) 25.dp else 3.dp,
        if (env.motionScale == 0f) snap() else spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow * env.style.transitionSpeed),
        label = "depthSwitch",
    )
    val toggle = if (onCheckedChange != null) {
        Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
    } else {
        Modifier
    }
    Box(modifier.minimumInteractiveComponentSize().then(toggle), contentAlignment = Alignment.Center) {
        val groove = d.c(d.groove)
        Box(
            Modifier.size(width = 52.dp, height = 30.dp)
                .depthPanel(
                    Capsule(),
                    color = if (checked && enabled) blend(accent, groove, if (d.dark) 0.45f else 0.32f) else groove,
                    inset = true,
                    elevation = 3.dp,
                ),
        ) {
            Box(
                Modifier.offset(x = knobX, y = 3.dp).size(24.dp)
                    .depthControl(CircleShape, enabled = enabled, elevation = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (d.physicalControls) {
                    val on = checked && enabled
                    val led = if (on) d.c(d.glow) else d.c(d.onControl).copy(alpha = 0.25f)
                    Box(
                        Modifier.size(6.dp).drawBehind {
                            if (on) drawCircle(led.copy(alpha = 0.35f), radius = this.size.minDimension * 1.2f)
                            drawCircle(led)
                        },
                    )
                }
            }
        }
    }
}

/** [top] at [amount] opacity over the opaque [bottom]. */
private fun blend(top: Color, bottom: Color, amount: Float): Color = Color(
    red = top.red * amount + bottom.red * (1 - amount),
    green = top.green * amount + bottom.green * (1 - amount),
    blue = top.blue * amount + bottom.blue * (1 - amount),
    alpha = 1f,
)

/**
 * Drop-in for a Material [Card]: a raised 3D panel on 3D themes, the Material card otherwise.
 * [containerColor] = null keeps the card's default color.
 */
@Composable
fun MimicCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    containerColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val d = LocalDepth.current
    if (!d.on) {
        Card(
            modifier = modifier,
            shape = shape,
            colors = if (containerColor != null) CardDefaults.cardColors(containerColor = containerColor) else CardDefaults.cardColors(),
            content = content,
        )
        return
    }
    val fill = containerColor ?: d.c(d.base)
    val onFill = MaterialTheme.colorScheme.contentColorFor(fill).takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSurface
    CompositionLocalProvider(LocalContentColor provides onFill) {
        Column(modifier.depthPanel(shape, color = fill), content = content)
    }
}

// ---------------------------------------------------------------------------------------------
// Volume: a rotary knob and a slider, both bound to the phone's real media volume.
// ---------------------------------------------------------------------------------------------

private const val KNOB_SWEEP = 270f
private const val KNOB_START = -135f

/** Knob angle (degrees, 0 = 12 o'clock, clockwise) for a 0..1 position. Pure (unit-tested). */
fun knobAngleFor(fraction: Float): Float = KNOB_START + KNOB_SWEEP * fraction.coerceIn(0f, 1f)

/**
 * Turns a finger's angular movement into a new knob position: the knob follows the finger's
 * rotation (relative, so touching it never makes it jump), wrapping correctly across 6 o'clock.
 * Pure (unit-tested).
 */
fun knobFractionAfterTurn(fraction: Float, fromDeg: Float, toDeg: Float): Float {
    var delta = toDeg - fromDeg
    while (delta > 180f) delta -= 360f
    while (delta < -180f) delta += 360f
    return (fraction + delta / KNOB_SWEEP).coerceIn(0f, 1f)
}

/**
 * A rotary volume dial: turn it with a circular drag to change the phone's media volume (the same
 * volume the hardware keys change — and it follows them). Lit ticks show the level; a haptic tick
 * marks each volume step. TalkBack: adjustable ("Volume, 40 percent") with volume up / down
 * actions. Disabled (and says so) on fixed-volume devices.
 */
@Composable
fun VolumeKnob(
    volume: MediaVolumeState,
    modifier: Modifier = Modifier,
    size: Dp = 104.dp,
    haptics: Boolean = true,
) {
    val d = LocalDepth.current
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val enabled = !volume.fixed
    // The knob follows the finger continuously; the real volume snaps to the device's steps.
    var dragFraction by remember { mutableFloatStateOf(-1f) }
    val shown = if (dragFraction >= 0f) dragFraction else volume.fraction
    val percent = (volume.fraction * 100).roundToInt()
    val accent = scheme.primary
    val tickOff = scheme.onSurfaceVariant.copy(alpha = 0.3f)
    val physical = d.physicalControls
    Box(
        modifier
            .size(size)
            .semantics {
                contentDescription = "Volume"
                stateDescription = if (enabled) "$percent percent" else "Fixed by this device"
                if (!enabled) disabled()
                progressBarRangeInfo = ProgressBarRangeInfo(volume.level.toFloat(), 0f..volume.max.toFloat(), steps = (volume.max - 1).coerceAtLeast(0))
                if (enabled) {
                    setProgress { target -> volume.set(target.roundToInt()); true }
                    customActions = listOf(
                        CustomAccessibilityAction("Volume up") { volume.set(volume.level + 1); true },
                        CustomAccessibilityAction("Volume down") { volume.set(volume.level - 1); true },
                    )
                }
            }
            .then(
                if (!enabled) {
                    Modifier
                } else {
                    Modifier.pointerInput(volume) {
                        var lastAngle = 0f
                        var frac = 0f
                        fun angleOf(p: Offset): Float {
                            val cx = this.size.width / 2f
                            val cy = this.size.height / 2f
                            return Math.toDegrees(atan2((p.x - cx).toDouble(), (cy - p.y).toDouble())).toFloat()
                        }
                        fun nearCenter(p: Offset): Boolean {
                            val dx = p.x - this.size.width / 2f
                            val dy = p.y - this.size.height / 2f
                            return sqrt(dx * dx + dy * dy) < this.size.width * 0.12f
                        }
                        detectDragGestures(
                            onDragStart = { p -> frac = volume.fraction; lastAngle = angleOf(p); dragFraction = frac },
                            onDragEnd = { dragFraction = -1f },
                            onDragCancel = { dragFraction = -1f },
                            onDrag = { change, _ ->
                                change.consume()
                                val p = change.position
                                val a = angleOf(p)
                                // The angle is meaningless right at the hub; just re-anchor there.
                                if (!nearCenter(p)) frac = knobFractionAfterTurn(frac, lastAngle, a)
                                lastAngle = a
                                dragFraction = frac
                                val step = volumeStepFor(frac, volume.max)
                                if (step != volume.level) {
                                    volume.set(step)
                                    if (haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            },
                        )
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val angle = knobAngleFor(shown)
        if (physical) {
            // Reference 02: a recessed outer arc with a glowing indicator dot, and a glowing
            // red -> orange -> amber ring (the level) around a matte domed cap.
            val hot = d.c(d.glowHot)
            val mid = d.c(d.glow)
            val warm = d.c(d.glowWarm)
            val groove = d.c(d.groove)
            val rimLight = d.c(d.ctrlRimLight)
            Canvas(Modifier.fillMaxSize()) {
                val r = this.size.minDimension / 2f
                val c0 = Offset(this.size.width / 2f, this.size.height / 2f)
                val outerR = r - 5.dp.toPx()
                val ringR = r * 0.64f
                val ringW = 6.dp.toPx()
                fun arcAt(radius: Float) = androidx.compose.ui.geometry.Rect(c0.x - radius, c0.y - radius, c0.x + radius, c0.y + radius)
                // Outer recessed arc track (270°) with a hairline highlight under it.
                val o = arcAt(outerR)
                drawArc(groove, 135f, 270f, false, o.topLeft, o.size, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                // Level ring: dark groove all round, lit arc up to the level, with a soft halo.
                val ring = arcAt(ringR)
                drawCircle(groove, radius = ringR, center = c0, style = androidx.compose.ui.graphics.drawscope.Stroke(ringW + 2.dp.toPx()))
                if (enabled && shown > 0f) {
                    rotate(135f, c0) {
                        val sweep = Brush.sweepGradient(0f to hot, 0.40f to mid, 0.75f to warm, 1f to warm, center = c0)
                        drawArc(sweep, 0f, 270f * shown, false, ring.topLeft, ring.size, alpha = 0.28f, style = androidx.compose.ui.graphics.drawscope.Stroke(ringW * 2.6f, cap = StrokeCap.Round))
                        drawArc(sweep, 0f, 270f * shown, false, ring.topLeft, ring.size, style = androidx.compose.ui.graphics.drawscope.Stroke(ringW, cap = StrokeCap.Round))
                    }
                }
                // Indicator dot riding the outer arc.
                val a = Math.toRadians(angle.toDouble())
                val dot = c0 + Offset(sin(a).toFloat(), -cos(a).toFloat()) * outerR
                val dotColor = if (enabled) warm else tickOff
                if (enabled) drawCircle(Brush.radialGradient(listOf(mid.copy(alpha = 0.55f), Color.Transparent), center = dot, radius = 12.dp.toPx()), radius = 12.dp.toPx(), center = dot)
                drawCircle(dotColor, radius = 5.dp.toPx(), center = dot)
                drawCircle(rimLight, radius = 5.dp.toPx(), center = dot, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }
            val capSize = size * 0.48f
            Box(
                Modifier.size(capSize).depthControl(CircleShape, enabled = enabled, accent = true, elevation = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val top = d.c(d.metalTop)
                val bottom = d.c(d.metalBottom)
                val rimDark = d.c(d.ctrlRimDark)
                Canvas(Modifier.fillMaxSize()) {
                    val r = this.size.minDimension / 2f
                    val c0 = Offset(this.size.width / 2f, this.size.height / 2f)
                    // Raised inner dome (ref 02's cap-within-a-cap), lit from the top-left.
                    drawCircle(Brush.linearGradient(listOf(top, bottom), start = Offset(0f, 0f), end = Offset(this.size.width, this.size.height)), radius = r * 0.70f, center = c0)
                    drawCircle(rimDark.copy(alpha = 0.6f), radius = r * 0.70f, center = c0, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                    // Finger dimple: turns with the knob.
                    rotate(angle, c0) {
                        val dimple = Offset(c0.x, c0.y - r * 0.48f)
                        drawCircle(Brush.linearGradient(listOf(Color.Black.copy(alpha = 0.55f), top), start = dimple - Offset(6f, 6f), end = dimple + Offset(6f, 6f)), radius = r * 0.13f, center = dimple)
                    }
                }
            }
        } else {
            // Neumorphic dial: tick ring lit to the level, raised dish with an accent dot.
            Canvas(Modifier.fillMaxSize()) {
                val ticks = 21
                val r = this.size.minDimension / 2f
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                for (i in 0 until ticks) {
                    val f = i / (ticks - 1f)
                    val lit = enabled && shown > 0f && f <= shown + 0.001f
                    val ang = Math.toRadians(knobAngleFor(f).toDouble())
                    val dir = Offset(sin(ang).toFloat(), -cos(ang).toFloat())
                    val outer = center + dir * (r - 1.dp.toPx())
                    val inner = center + dir * (r - (if (i % 5 == 0) 9.dp else 6.dp).toPx())
                    drawLine(if (lit) accent else tickOff, inner, outer, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            Box(
                Modifier.size(size * 0.66f).depthControl(CircleShape, enabled = enabled, elevation = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                val rimLight = d.c(d.rimLight)
                val rimDark = d.c(d.rimDark)
                Canvas(Modifier.fillMaxSize()) {
                    val r = this.size.minDimension / 2f
                    val c0 = Offset(this.size.width / 2f, this.size.height / 2f)
                    drawCircle(
                        Brush.linearGradient(listOf(rimDark.copy(alpha = 0.10f), rimLight.copy(alpha = 0.6f)), start = Offset.Zero, end = Offset(this.size.width, this.size.height)),
                        radius = r * 0.78f,
                    )
                    rotate(angle, c0) {
                        drawCircle(if (enabled) accent else accent.copy(alpha = 0.3f), radius = 5.dp.toPx(), center = Offset(c0.x, c0.y - r * 0.62f))
                    }
                }
            }
        }
    }
}

/**
 * The Now Playing volume control: a [VolumeKnob] with the level beside it, or a 3D slider row when
 * [dial] is false. Both drive the real media volume.
 */
@Composable
fun PlayerVolumeControl(volume: MediaVolumeState, dial: Boolean, modifier: Modifier = Modifier, haptics: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val percent = (volume.fraction * 100).roundToInt()
    if (dial) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = null, tint = scheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(16.dp))
            VolumeKnob(volume, haptics = haptics)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (volume.fixed) "Fixed" else "$percent%",
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                Text("Volume", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
            }
        }
    } else {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = null, tint = scheme.onSurfaceVariant)
            MimicSlider(
                value = volume.fraction,
                onValueChange = { volume.setFraction(it) },
                enabled = !volume.fixed,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "Volume" },
            )
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = scheme.onSurfaceVariant)
        }
    }
}

/** A 3D option chip (every SelectRow in Settings): raised, or pressed-in / lit when selected. */
@Composable
fun DepthChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val d = LocalDepth.current
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .padding(vertical = 4.dp)
            .depthControl(Capsule(), pressed = pressed || (selected && d.physicalControls), selected = selected, elevation = 3.dp)
            .clickable(interactionSource = interaction, indication = ripple(), role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            color = when {
                selected && d.physicalControls -> d.c(d.glow)
                d.physicalControls -> d.c(d.onControl)
                selected -> scheme.primary
                else -> scheme.onSurface
            },
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
