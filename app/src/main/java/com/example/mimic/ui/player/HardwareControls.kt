package com.example.mimic.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import com.example.mimic.ui.designsystem.MatteAmberOrange
import com.example.mimic.ui.designsystem.MatteAmberRed
import com.example.mimic.ui.designsystem.MatteAmberYellow
import com.example.mimic.ui.designsystem.MatteGraphiteTop
import com.example.mimic.ui.designsystem.MatteGraphiteBottom
import com.example.mimic.ui.designsystem.matteAmberSymbolBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

val HardwareAmber = Color(0xFFFFB45F)
private val Well = Color(0xFF0C0E11)
private val Rim = Color(0xFF454C55)
private val Ink = Color(0xFFF6F2EA)
private val MetalTop = Color(0xFF686F78)
private val MetalBottom = Color(0xFF24282F)

enum class TransportIcon { Previous, Play, Pause, Next }

/** A momentary button. Pass the current Play or Pause action as [label]. */
@Composable
fun HardwareButton(
    label: String,
    icon: TransportIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    illuminated: Boolean = false,
    accent: Color = HardwareAmber,
) {
    val matteAmber = com.example.mimic.ui.designsystem.LocalDepth.current.matteAmber
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val travel by animateFloatAsState(if (pressed) 1f else 0f, tween(100), label = "Key travel")
    Canvas(
        modifier.defaultMinSize(minWidth = 48.dp).height(56.dp)
            .alpha(if (enabled) 1f else .48f)
            .semantics { contentDescription = label }
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
    ) {
        val unit = size.minDimension
        val iconCenter = if (matteAmber && illuminated) {
            val capCenter = center + Offset(0f, unit * (-.04f + travel * .05f))
            val radius = unit * .44f
            drawCircle(Well, radius, capCenter + Offset(0f, unit * (.07f - travel * .04f)))
            drawCircle(
                Brush.verticalGradient(listOf(MatteGraphiteTop, MatteGraphiteBottom), capCenter.y - radius, capCenter.y + radius),
                radius, capCenter,
            )
            drawCircle(Color.White.copy(alpha = if (focused) .55f else .14f), radius, capCenter, style = Stroke(1.dp.toPx()))
            capCenter
        } else drawHardwareKey(travel, illuminated, focused, accent, matteAmber)
        val radius = unit * if (matteAmber && illuminated) .21f else .175f
        val symbol = if (matteAmber) matteAmberSymbolBrush(iconCenter.y - radius * .77f, iconCenter.y + radius * .77f)
            else SolidColor(if (illuminated) Well else Ink)
        transportIcon(icon, iconCenter, radius, symbol)
    }
}

internal fun DrawScope.drawHardwareKey(
    travel: Float,
    illuminated: Boolean,
    focused: Boolean,
    accent: Color = HardwareAmber,
    matteAmber: Boolean = false,
): Offset {
    if (size.minDimension <= 0f) return center
    val unit = size.minDimension
    val inset = unit * .06f
    val face = Size(size.width - 2 * inset, size.height - unit * .21f)
    val corner = unit * .22f
    val top = Offset(inset, unit * (.055f + .09f * travel))
    val wall = top + Offset(0f, unit * (.11f - .07f * travel))
    roundedShade(Color(0xFF343A42), Well, Offset(0f, size.height * .08f),
        Size(size.width, size.height * .91f), corner + inset)
    roundedShade(Color(0xFF353A41), Color(0xFF121417), wall, face, corner)
    val upper = if (matteAmber) MatteGraphiteTop else if (illuminated) lerp(MetalTop, accent, .67f) else MetalTop
    val lower = if (matteAmber) MatteGraphiteBottom else if (illuminated) lerp(MetalBottom, accent, .56f) else MetalBottom
    roundedShade(upper, lower, top, face, corner)
    drawRoundRect(
        color = if (focused && !matteAmber) accent else Color.White.copy(alpha = if (focused) .55f else if (matteAmber) .11f else .24f),
        topLeft = top, size = face, cornerRadius = CornerRadius(corner),
        style = Stroke(if (focused) 2.dp.toPx() else 1.dp.toPx()),
    )
    return top + Offset(face.width / 2f, face.height / 2f)
}

/** A 270-degree rotary indicator. Drag vertically or adjust using directional keys. */
@Composable
fun HardwareKnob(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = HardwareAmber,
    valueDescription: String = "${(normalized(value) * 100).roundToInt()}%",
    onValueChangeFinished: (() -> Unit)? = null,
    keyboardStep: Float = .05f,
) {
    val position = normalized(value)
    val matteAmber = com.example.mimic.ui.designsystem.LocalDepth.current.matteAmber
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val travel = with(LocalDensity.current) { 180.dp.toPx() }
    val currentValue by rememberUpdatedState(position)
    val change by rememberUpdatedState(onValueChange)
    val finish by rememberUpdatedState(onValueChangeFinished)
    // Keep a gesture-local accumulator even if callbacks outrun recomposition.
    val gestureValue = remember { androidx.compose.runtime.mutableFloatStateOf(position) }
    val drag = rememberDraggableState { delta ->
        gestureValue.floatValue = (gestureValue.floatValue - delta / travel).coerceIn(0f, 1f)
        change(gestureValue.floatValue)
    }
    fun adjust(newValue: Float): Boolean {
        val next = normalized(newValue)
        if (next == position) return false
        onValueChange(next)
        onValueChangeFinished?.invoke()
        return true
    }
    Canvas(
        modifier.size(136.dp)
            .alpha(if (enabled) 1f else .48f)
            .semantics {
                contentDescription = label
                stateDescription = valueDescription
                progressBarRangeInfo = ProgressBarRangeInfo(position, 0f..1f)
                if (enabled) setProgress { adjust(it) } else disabled()
            }
            .onKeyEvent {
                if (!enabled || it.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (it.key) {
                    Key.DirectionUp, Key.DirectionRight -> adjust(position + keyboardStep)
                    Key.DirectionDown, Key.DirectionLeft -> adjust(position - keyboardStep)
                    Key.MoveHome -> adjust(0f)
                    Key.MoveEnd -> adjust(1f)
                    else -> false
                }
            }
            .focusable(enabled, interaction)
            .draggable(drag, Orientation.Vertical, enabled = enabled, interactionSource = interaction,
                onDragStarted = { gestureValue.floatValue = currentValue },
                onDragStopped = { finish?.invoke() })
    ) {
        val r = size.minDimension / 2f
        if (matteAmber) {
            drawMatteVolumeDial(r, position, focused)
            return@Canvas
        }
        val arc = r * .92f
        val bounds = center - Offset(arc, arc)
        val diameter = Size(2 * arc, 2 * arc)
        val stroke = Stroke((r * .037f).coerceAtLeast(1f), cap = StrokeCap.Round)
        drawArc(Rim, 135f, 270f, false, bounds, diameter, style = stroke)
        if (position > 0f) drawArc(accent, 135f, 270f * position, false, bounds, diameter, style = stroke)
        for (tick in 0..12) {
            val angle = 135f + tick * 22.5f
            drawLine(
                color = if (tick / 12f <= position) accent else Rim,
                start = point(center, r * .78f, angle),
                end = point(center, r * .855f, angle),
                strokeWidth = if (tick % 3 == 0) 2.dp.toPx() else 1.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        drawCircle(Well, r * .72f, center + Offset(0f, r * .045f))
        drawCircle(Brush.verticalGradient(listOf(Rim, Well), center.y - r * .7f, center.y + r * .7f), r * .69f)
        val cap = center - Offset(0f, r * .045f)
        val faceRadius = r * .60f
        drawCircle(Color(0xFF1B1F24), r * .64f, center + Offset(0f, r * .018f))
        val angle = 135f + position * 270f
        for (ridge in 0 until 48) {
            val ridgeAngle = angle + ridge * 7.5f
            val color = if (sin(ridgeAngle * PI / 180.0) < 0) MetalTop else Rim
            drawLine(color, point(cap, r * .59f, ridgeAngle), point(cap, r * .638f, ridgeAngle), 1.dp.toPx())
        }
        drawCircle(
            Brush.radialGradient(
                colorStops = arrayOf(0f to MetalTop, .70f to Color(0xFF464C54), 1f to MetalBottom),
                center = cap - Offset(r * .22f, r * .31f), radius = r * .98f,
            ),
            faceRadius, cap,
        )
        for (ring in 1..19) {
            drawCircle(Color.White.copy(alpha = if (ring % 2 == 0) .045f else .018f),
                faceRadius * ring / 20, cap, style = Stroke(.65.dp.toPx()))
        }
        drawCircle(if (focused || dragged) accent else Color.White.copy(alpha = if (focused) .55f else .33f),
            faceRadius, cap, style = Stroke(1.dp.toPx()))
        val start = point(cap, r * .30f, angle)
        val end = point(cap, r * .51f, angle)
        drawLine(Well, start, end, r * .096f, StrokeCap.Round)
        drawLine(accent, start, end, r * .054f, StrokeCap.Round)
    }
}

private fun DrawScope.drawMatteVolumeDial(radius: Float, position: Float, focused: Boolean) {
    val outer = radius * .89f
    val trackOrigin = center - Offset(outer, outer)
    val trackSize = Size(outer * 2f, outer * 2f)
    drawArc(Well, 135f, 270f, false, trackOrigin, trackSize, style = Stroke(radius * .04f))

    val ring = radius * .68f
    drawCircle(Well, ring + radius * .075f)
    drawCircle(
        MatteAmberRed.copy(alpha = .22f), ring,
        style = Stroke(radius * .104f),
    )
    drawCircle(
        matteAmberSymbolBrush(center.y - ring, center.y + ring), ring,
        style = Stroke(radius * .064f),
    )
    drawCircle(
        Brush.radialGradient(
            .0f to Well, .69f to Well, .89f to MatteGraphiteTop, 1f to Well,
            center = center, radius = ring,
        ),
        ring - radius * .037f,
    )

    val capRadius = radius * .40f
    drawCircle(Color.Black.copy(alpha = .76f), capRadius * 1.04f, center + Offset(0f, radius * .044f))
    drawCircle(
        Brush.verticalGradient(listOf(MatteGraphiteTop, MatteGraphiteBottom), center.y - capRadius, center.y + capRadius),
        capRadius,
    )
    drawCircle(Color.White.copy(alpha = if (focused) .55f else .15f), capRadius, style = Stroke(1.dp.toPx()))
    val angle = 135f + position * 270f
    val dimple = point(center, radius * .20f, angle)
    drawCircle(Well, radius * .065f, dimple)
    val marker = point(center, outer, angle)
    drawCircle(
        Brush.radialGradient(listOf(MatteAmberOrange, Color.Transparent), center = marker, radius = radius * .16f),
        radius * .16f, marker,
    )
    drawCircle(MatteAmberOrange, radius * .082f, marker)
    drawCircle(MatteAmberYellow, radius * .056f, marker)
}

/** A latching rocker with a moving center highlight. */
@Composable
fun HardwareSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = HardwareAmber,
) {
    val matteAmber = com.example.mimic.ui.designsystem.LocalDepth.current.matteAmber
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val angle by animateFloatAsState(if (checked) 1f else 0f, tween(160), label = "Rocker tilt")
    Canvas(
        modifier.size(90.dp, 52.dp)
            .alpha(if (enabled) 1f else .48f)
            .semantics { contentDescription = label }
            .toggleable(checked, interaction, indication = null, enabled = enabled,
                role = Role.Switch, onValueChange = onCheckedChange)
    ) {
        val edge = size.minDimension * .10f
        val origin = Offset(edge, edge)
        val body = Size(size.width - 2 * edge, size.height - 2 * edge)
        val radius = edge * 2f
        roundedShade(Color(0xFF040506), Rim, Offset.Zero, size, radius + edge)
        drawRoundRect(Well, origin, body, CornerRadius(radius))
        val upper = if (matteAmber) MatteGraphiteTop else MetalTop
        val lower = if (matteAmber) MatteGraphiteBottom else MetalBottom
        val left = lerp(lower, upper, angle)
        val right = lerp(upper, lower, angle)
        val hinge = .42f + .16f * angle
        drawRoundRect(
            Brush.horizontalGradient(0f to left, hinge to lerp(upper, Ink, if (matteAmber) .04f else .18f), 1f to right,
                startX = origin.x, endX = origin.x + body.width),
            origin, body, CornerRadius(radius),
        )
        val status = if (matteAmber) MatteAmberOrange.copy(alpha = .38f + .62f * angle) else lerp(Rim, accent, angle)
        val lamp = Offset(origin.x + body.width * .73f, center.y)
        if (!matteAmber) drawCircle(status.copy(alpha = .3f * angle), edge * 1.22f, lamp)
        drawCircle(status, edge * .56f, lamp)
        val mark = Offset(origin.x + body.width * .26f, center.y)
        drawLine(if (matteAmber) MatteAmberOrange.copy(alpha = 1f - .62f * angle) else Ink.copy(alpha = .67f), mark - Offset(edge * .62f, 0f),
            mark + Offset(edge * .62f, 0f), 1.5.dp.toPx(), StrokeCap.Round)
        drawRoundRect(if (focused && !matteAmber) accent else Color.White.copy(alpha = if (focused) .55f else .2f),
            origin, body, CornerRadius(radius), style = Stroke(1.dp.toPx()))
    }
}

/** A horizontal fader with native slider gestures, keyboard support, and range semantics. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwareSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = HardwareAmber,
    onValueChangeFinished: (() -> Unit)? = null,
    valueDescription: String? = null,
) {
    val matteAmber = com.example.mimic.ui.designsystem.LocalDepth.current.matteAmber
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val travel by animateFloatAsState(if (pressed || dragged) 1f else 0f, tween(100), label = "Fader travel")
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Slider(
        value = normalized(value),
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = 48.dp).alpha(if (enabled) 1f else .48f)
            .semantics {
                contentDescription = label
                if (valueDescription != null) stateDescription = valueDescription
            },
        enabled = enabled,
        interactionSource = interaction,
        onValueChangeFinished = onValueChangeFinished,
        thumb = {
            Canvas(Modifier.size(36.dp, 34.dp)) {
                val top = Offset(1.dp.toPx(), (2f + travel * 2f).dp.toPx())
                val face = Size(size.width - 2 * top.x, 26.dp.toPx())
                val radius = 7.dp.toPx()
                roundedShade(Rim, Well, top + Offset(0f, (5f - travel * 2f).dp.toPx()), face, radius)
                roundedShade(if (matteAmber) MatteGraphiteTop else MetalTop, if (matteAmber) MatteGraphiteBottom else MetalBottom, top, face, radius)
                drawRoundRect(if (focused && !matteAmber) accent else Color.White.copy(alpha = if (focused) .55f else .26f),
                    top, face, CornerRadius(radius), style = Stroke(1.dp.toPx()))
                for (i in -1..1) {
                    val x = center.x + (i * 5).dp.toPx()
                    drawLine(if (i == 0) (if (matteAmber) MatteAmberOrange else accent) else Well, Offset(x, top.y + 7.dp.toPx()),
                        Offset(x, top.y + face.height - 7.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
                }
            }
        },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().heightIn(min = 28.dp)) {
                val slot = 9.dp.toPx()
                val top = Offset(0f, center.y - slot / 2f)
                roundedShade(Well, Rim, top, Size(size.width, slot), slot / 2f)
                val active = size.width * state.value
                if (active > 0f) {
                    drawRoundRect(if (matteAmber) Color(0xFF8A828D) else accent, Offset(if (rtl) size.width - active else 0f, center.y - 1.5.dp.toPx()),
                        Size(active, 3.dp.toPx()), CornerRadius(1.5.dp.toPx()))
                }
                for (i in 0..16) {
                    val x = size.width * i / 16
                    val tick = if (i % 4 == 0) 4.dp.toPx() else 2.dp.toPx()
                    drawLine(Rim, Offset(x, center.y + slot), Offset(x, center.y + slot + tick), 1.dp.toPx())
                }
            }
        },
    )
}

private fun normalized(value: Float): Float = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)

private fun point(origin: Offset, distance: Float, degrees: Float): Offset {
    val radians = degrees * PI / 180.0
    return origin + Offset(distance * cos(radians).toFloat(), distance * sin(radians).toFloat())
}

private fun DrawScope.roundedShade(
    topColor: Color, bottomColor: Color, topLeft: Offset, bounds: Size, radius: Float,
) {
    if (bounds.width <= 0 || bounds.height <= 0) return
    drawRoundRect(
        Brush.verticalGradient(listOf(topColor, bottomColor), topLeft.y, topLeft.y + bounds.height),
        topLeft, bounds, CornerRadius(radius),
    )
}

private fun DrawScope.transportIcon(icon: TransportIcon, at: Offset, radius: Float, brush: Brush) {
    if (icon == TransportIcon.Pause) {
        for (side in listOf(-1, 1)) {
            drawRoundRect(brush, at + Offset(side * radius * .43f - radius * .21f, -radius * .71f),
                Size(radius * .42f, radius * 1.42f), CornerRadius(radius * .11f))
        }
        return
    }
    val direction = if (icon == TransportIcon.Previous) -1f else 1f
    val skip = icon != TransportIcon.Play
    val x = at.x + if (skip) -direction * radius * .20f else radius * .10f
    drawPath(Path().apply {
        moveTo(x - direction * radius * .63f, at.y - radius * .77f)
        lineTo(x + direction * radius * .74f, at.y)
        lineTo(x - direction * radius * .63f, at.y + radius * .77f)
        close()
    }, brush)
    if (skip) {
        val stop = at.x + direction * radius * .79f
        drawLine(brush, Offset(stop, at.y - radius * .65f), Offset(stop, at.y + radius * .65f),
            radius * .24f, StrokeCap.Round)
    }
}
