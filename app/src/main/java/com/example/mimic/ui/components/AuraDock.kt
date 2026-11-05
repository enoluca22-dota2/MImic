package com.example.mimic.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mimic.ui.designsystem.LocalGlass
import com.example.mimic.ui.navigation.TopLevel
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.launch

/** One dock destination and the accent light it gives the dock while selected. */
data class AuraNavItem(val label: String, val icon: ImageVector, val accent: Color)

/** Each tab's accent in the Floating Aura dock (values taken from the reference design). */
fun auraAccent(tab: TopLevel): Color = when (tab) {
    TopLevel.HOME -> Color(0xFFE4531F)      // orange
    TopLevel.SEARCH -> Color(0xFFB7A6F5)    // violet
    TopLevel.LIBRARY -> Color(0xFFF2B632)   // golden
    TopLevel.RADIO -> Color(0xFF3FD99A)     // emerald
    TopLevel.DOWNLOADS -> Color(0xFF3DD3E6) // cyan
    TopLevel.SETTINGS -> Color(0xFFFF6FA8)  // soft pink
}

/** Outlined icons, like the reference's line icons. */
private fun auraIcon(tab: TopLevel): ImageVector = when (tab) {
    TopLevel.HOME -> Icons.Outlined.Home
    TopLevel.SEARCH -> Icons.Outlined.Search
    TopLevel.LIBRARY -> Icons.Outlined.LibraryMusic
    TopLevel.RADIO -> Icons.Outlined.Radio
    TopLevel.DOWNLOADS -> Icons.Outlined.FileDownload
    TopLevel.SETTINGS -> Icons.Outlined.Settings
}

/** The dock items for MImic's real top-level destinations, in navigation order. */
val auraTabs: List<AuraNavItem> = TopLevel.visible.map { AuraNavItem(it.label, auraIcon(it), auraAccent(it)) }

// Geometry (dp). The composable is RISE + DOCK_HEIGHT tall. The bead (active button) sits centred
// on the dock's top edge; the dock skin swells around its lower half and joins the top edge with
// two tangent fillets ("shoulders"), so the outline never creases.
private const val RISE = 30f
private const val DOCK_HEIGHT = 64f
private const val DOCK_CORNER = 20f
private const val BEAD_RADIUS = 26f
private const val SKIN_RADIUS = 31f    // bead + the thin socket ring around it
private const val BEAD_ABOVE_EDGE = 1f // bead centre this far above the top edge
private const val SHOULDER = 12f       // fillet radius at rest
private const val ICON_SIZE = 22f

private val DockFillTop = Color(0xFF15111D)
private val DockFillBottom = Color(0xFF0C0912)
private val Outline = Color(0xFF8E86A3)
private val IdleIcon = Color(0xFFB3ABC7)
private val BeadIcon = Color(0xFF17121C)

/**
 * The Floating Aura navigation dock (4.7), modelled on the reference video: a dark rounded dock
 * with a hairline outline, and the selected tab as a glowing accent bead on its top edge. On a tab
 * change the bead glides to the new place on a firm spring (a hint of overshoot); the skin's
 * shoulders lean with its speed (the trailing one stretches, the leading one tightens), its colour
 * and icon blend from the old tab to the new one in step with the travel, the wide glow under the
 * dock follows, and the tab's name fades in under the bead once it has settled.
 *
 * Everything that moves is read in the draw / layout phase, so animation frames only redraw the
 * dock — nothing recomposes — and nothing runs while idle. With motion off (theme switch or
 * Android's "Remove animations") a change is instant. The caller applies the navigation-bar inset.
 */
@Composable
fun AuraDock(
    items: List<AuraNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val env = LocalGlass.current
    val motion = env.motionScale > 0f
    val speed = env.style.transitionSpeed
    val selected = selectedIndex.coerceIn(0, items.lastIndex)

    val position = remember { Animatable(selected.toFloat()) }
    val from = remember { mutableIntStateOf(selected) }
    val label = remember { Animatable(1f) }
    LaunchedEffect(selected, motion) {
        if (!motion || position.value == selected.toFloat()) {
            from.intValue = selected
            position.snapTo(selected.toFloat())
            label.snapTo(1f)
            return@LaunchedEffect
        }
        from.intValue = position.value.roundToInt().coerceIn(0, items.lastIndex)
        label.snapTo(0f)
        position.animateTo(selected.toFloat(), spring(dampingRatio = 0.74f, stiffness = 210f * speed))
        from.intValue = selected
        launch { label.animateTo(1f, tween((200 / speed).toInt())) }
    }

    /** 0 at the old tab → 1 at the new one; drives the colour and icon blend. */
    fun progress(): Float {
        val a = from.intValue
        if (a == selected) return 1f
        return ((position.value - a) / (selected - a)).coerceIn(0f, 1f)
    }
    fun accentNow(): Color = lerp(items[from.intValue].accent, items[selected].accent, progress())

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .height((RISE + DOCK_HEIGHT).dp),
    ) {
        val count = items.size
        val itemWidth = maxWidth / count

        Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
            val c = accentNow()
            val itemW = size.width / count
            val cx = itemW * (position.value + 0.5f)
            val top = RISE.dp.toPx()
            val bottom = size.height
            val by = top - BEAD_ABOVE_EDGE.dp.toPx()
            val r = BEAD_RADIUS.dp.toPx()
            val skinR = SKIN_RADIUS.dp.toPx()

            // Wide accent glow spilling out under the dock, centred on the bead.
            val glowCenter = Offset(cx, bottom + 4.dp.toPx())
            val glowR = 130.dp.toPx()
            drawCircle(
                Brush.radialGradient(listOf(c.copy(alpha = 0.34f), c.copy(alpha = 0.12f), Color.Transparent), center = glowCenter, radius = glowR),
                radius = glowR,
                center = glowCenter,
            )

            // Shoulders lean with the bead's speed (items per second, normalised).
            val v = (position.velocity / 9f).coerceIn(-1f, 1f)
            val f = SHOULDER.dp.toPx()
            val trailing = f * (1f + 0.8f * abs(v))
            val leading = (f * (1f - 0.5f * abs(v))).coerceAtLeast(2.dp.toPx())
            val fLeft = if (v >= 0f) trailing else leading
            val fRight = if (v >= 0f) leading else trailing

            val skin = Path().apply {
                addRoundRect(RoundRect(0f, top, size.width, bottom, CornerRadius(DOCK_CORNER.dp.toPx())))
            }
            val socket = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(cx, by), skinR)) }
            var shape = Path().apply { op(skin, socket, PathOperation.Union) }
            for (side in listOf(-1f, 1f)) {
                val fr = if (side < 0f) fLeft else fRight
                val shoulder = shoulderPath(cx, by, top, skinR, fr, side)
                shape = Path().apply { op(shape, shoulder, PathOperation.Union) }
            }

            // The socket's outline climbs the bead's sides to just above its middle, then gives way
            // to the bead; the dark ring between them is the visible socket.
            clipRect(top = by - skinR * 0.45f) {
                drawPath(shape, Brush.verticalGradient(listOf(DockFillTop, DockFillBottom), startY = top, endY = bottom))
                drawPath(shape, Outline.copy(alpha = 0.45f), style = Stroke(1.dp.toPx()))
            }
            // Faint lower arc of the socket inside the dock, under the bead.
            clipRect(top = top) {
                drawCircle(Outline.copy(alpha = 0.22f), radius = skinR, center = Offset(cx, by), style = Stroke(1.dp.toPx()))
            }

            // The bead: glow, accent body (lighter towards the top), faint rim.
            val glowB = Offset(cx, by + 4.dp.toPx())
            drawCircle(
                Brush.radialGradient(listOf(c.copy(alpha = 0.6f), c.copy(alpha = 0.18f), Color.Transparent), center = glowB, radius = r * 1.9f),
                radius = r * 1.9f,
                center = glowB,
            )
            drawCircle(
                Brush.radialGradient(listOf(lerp(c, Color.White, 0.22f), c, lerp(c, Color.Black, 0.12f)), center = Offset(cx, by - r * 0.45f), radius = r * 1.6f),
                radius = r,
                center = Offset(cx, by),
            )
            drawCircle(Color.White.copy(alpha = 0.22f), radius = r - 0.5.dp.toPx(), center = Offset(cx, by), style = Stroke(1.dp.toPx()))
        }

        // Resting icons: each fades as the bead passes over its place and returns once it has gone.
        // Each cell is the full-height touch target for its tab.
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selected
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(index) },
                        )
                        .semantics {
                            this.selected = isSelected
                            contentDescription = item.label
                        },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Icon(
                        item.icon,
                        contentDescription = null,
                        tint = IdleIcon,
                        modifier = Modifier
                            .padding(top = (RISE + (DOCK_HEIGHT - ICON_SIZE) / 2f).dp)
                            .size(ICON_SIZE.dp)
                            .graphicsLayer {
                                val near = (1f - abs(position.value - index) * 1.6f).coerceIn(0f, 1f)
                                alpha = 0.85f * (1f - near)
                            },
                    )
                }
            }
        }

        // The bead's icon: the old one fades out as the new one fades in, in step with the travel.
        val beadOffset = Modifier.offset {
            val cx = itemWidth.toPx() * (position.value + 0.5f)
            val cy = (RISE - BEAD_ABOVE_EDGE).dp.toPx()
            val half = 11.dp.toPx()
            IntOffset((cx - half).roundToInt(), (cy - half).roundToInt())
        }
        if (from.intValue != selected) {
            Icon(
                items[from.intValue].icon,
                contentDescription = null,
                tint = BeadIcon,
                modifier = beadOffset.size(22.dp).graphicsLayer { alpha = 1f - progress() },
            )
        }
        Icon(
            items[selected].icon,
            contentDescription = null,
            tint = BeadIcon,
            modifier = beadOffset.size(22.dp).graphicsLayer { alpha = progress() },
        )

        // The tab's name under the bead, in its accent colour, once the bead has settled.
        val labelWidth = itemWidth * 1.8f
        Text(
            items[selected].label,
            color = items[selected].accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .offset {
                    val cx = itemWidth.toPx() * (selected + 0.5f)
                    IntOffset((cx - labelWidth.toPx() / 2).roundToInt(), (RISE + DOCK_HEIGHT - 22f).dp.roundToPx())
                }
                .width(labelWidth)
                // Hidden while the bead is away (also on the first frame after a tap, before the
                // effect has reset the fade).
                .graphicsLayer { alpha = if (abs(position.value - selected) > 0.02f) 0f else label.value },
        )
    }
}

/**
 * The filler between the dock's top edge and the socket circle on one [side] (-1 left, +1 right):
 * a fillet circle of radius [f] touches the top edge and the socket externally; the region between
 * the edge, the socket and that fillet's arc is returned, so the joined outline stays tangent.
 * Its reach from the bead's centre is sqrt((R + f)² − (f − e)²), e = bead centre above the edge.
 */
private fun shoulderPath(cx: Float, by: Float, top: Float, skinR: Float, f: Float, side: Float): Path {
    val e = top - by
    val reach = sqrt(((skinR + f) * (skinR + f) - (f - e) * (f - e)).coerceAtLeast(0f))
    val fc = Offset(cx + side * reach, top - f)            // fillet centre
    val k = f / (skinR + f)
    val t = Offset(fc.x + (cx - fc.x) * k, fc.y + (by - fc.y) * k) // tangent point on the socket
    val corner = Path().apply {
        moveTo(cx + side * reach, top)
        lineTo(t.x, t.y)
        lineTo(cx, by)
        lineTo(cx, top)
        close()
    }
    val fillet = Path().apply { addOval(androidx.compose.ui.geometry.Rect(fc, f)) }
    return Path().apply { op(corner, fillet, PathOperation.Difference) }
}
