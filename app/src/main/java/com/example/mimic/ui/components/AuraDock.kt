package com.example.mimic.ui.components

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlinx.coroutines.launch

/** One dock destination and the accent light it gives the dock while selected. */
data class AuraNavItem(val label: String, val icon: ImageVector, val accent: Color)

/** Each tab's accent light in the Floating Aura dock. */
fun auraAccent(tab: TopLevel): Color = when (tab) {
    TopLevel.HOME -> Color(0xFFFF8A3D)      // orange
    TopLevel.SEARCH -> Color(0xFFA98BFF)    // violet
    TopLevel.LIBRARY -> Color(0xFFFFC857)   // golden
    TopLevel.RADIO -> Color(0xFF4ADE9B)     // emerald
    TopLevel.DOWNLOADS -> Color(0xFF3DD9EB) // cyan
    TopLevel.SETTINGS -> Color(0xFFFFA3CF)  // soft pink
}

/** The dock items for MImic's real top-level destinations, in navigation order. */
val auraTabs: List<AuraNavItem> = TopLevel.visible.map { AuraNavItem(it.label, it.icon, auraAccent(it)) }

// Geometry (dp). The composable is RISE + DOCK_HEIGHT tall; the active button sits in a cradle
// cut into the dock's top edge and pokes RISE above it.
private const val RISE = 24f
private const val DOCK_HEIGHT = 64f
private const val BUBBLE_RADIUS = 25f
private const val BUBBLE_Y = 3f        // bubble center below the dock's top edge, at rest
private const val LIFT = 6f            // extra rise while the bubble travels
private const val NOTCH_HALF_WIDTH = 42f
private const val NOTCH_DEPTH = 36f

private val DockTop = Color(0xFF1C1529)
private val DockBottom = Color(0xFF110C1A)
private val IdleIcon = Color(0xFFB9AECF)
private val BubbleIcon = Color(0xFF160E1F)

/**
 * The Floating Aura navigation dock (4.7): a translucent rounded dock whose top edge curves down
 * around a raised circular button. On a tab change the button glides to its new place on a spring
 * with a little overshoot, lifts and settles; the glow, the cradle in the dock and the accent light
 * follow it, and the tab's name fades in beneath it with a short blur.
 *
 * Everything moving is read in the draw / layout phase, so an animation frame only redraws the dock
 * — nothing recomposes — and nothing runs while idle. With motion off (theme toggle or Android's
 * "Remove animations") every change snaps. The caller applies the navigation-bar inset.
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
    val lift = remember { Animatable(0f) }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(selected, motion) {
        if (!motion || position.value == selected.toFloat()) {
            position.snapTo(selected.toFloat()); lift.snapTo(0f); pop.snapTo(1f)
            return@LaunchedEffect
        }
        launch { position.animateTo(selected.toFloat(), spring(dampingRatio = 0.62f, stiffness = 240f * speed)) }
        launch {
            pop.snapTo(0.7f)
            pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 420f * speed))
        }
        lift.animateTo(1f, tween((150 / speed).toInt(), easing = FastOutSlowInEasing))
        lift.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = 300f * speed))
    }
    // Read only inside the draw lambda, so a color change redraws without recomposing.
    val accent = animateColorAsState(
        items[selected].accent,
        if (motion) tween((450 / speed).toInt()) else snap(),
        label = "auraAccent",
    )

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .height((RISE + DOCK_HEIGHT).dp),
    ) {
        val count = items.size
        val itemWidth = maxWidth / count

        Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
            val c = accent.value
            val itemW = size.width / count
            val cx = itemW * (position.value + 0.5f)
            val top = RISE.dp.toPx()
            val corner = env.style.navCornerRadius.coerceIn(16, 40).dp.toPx()

            // Soft ambient light behind the dock, centred under the active button.
            val ambient = Offset(cx, top + 30.dp.toPx())
            drawCircle(
                Brush.radialGradient(listOf(c.copy(alpha = 0.30f), Color.Transparent), center = ambient, radius = 150.dp.toPx()),
                radius = 150.dp.toPx(),
                center = ambient,
            )

            // The dock with its cradle: the rounded body minus a smooth cup around the button.
            val body = Path().apply { addRoundRect(RoundRect(0f, top, size.width, size.height, CornerRadius(corner))) }
            val w = NOTCH_HALF_WIDTH.dp.toPx()
            val d = NOTCH_DEPTH.dp.toPx()
            val cup = Path().apply {
                moveTo(cx - w, top - 1f)
                cubicTo(cx - w * 0.52f, top - 1f, cx - w * 0.64f, top + d, cx, top + d)
                cubicTo(cx + w * 0.64f, top + d, cx + w * 0.52f, top - 1f, cx + w, top - 1f)
                close()
            }
            val dock = Path().apply { op(body, cup, PathOperation.Difference) }
            drawPath(
                dock,
                Brush.verticalGradient(listOf(lerp(DockTop, c, 0.06f).copy(alpha = 0.88f), DockBottom.copy(alpha = 0.94f)), startY = top, endY = size.height),
            )
            drawPath(
                dock,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.02f)), startY = top, endY = size.height),
                style = Stroke(1.dp.toPx()),
            )

            // The raised button: a glow beneath it, the accent sphere, a thin highlight rim.
            val cy = top + (BUBBLE_Y - LIFT * lift.value).dp.toPx()
            val r = BUBBLE_RADIUS.dp.toPx()
            val glow = Offset(cx, cy + 8.dp.toPx())
            drawCircle(
                Brush.radialGradient(listOf(c.copy(alpha = 0.55f), Color.Transparent), center = glow, radius = r * 2.1f),
                radius = r * 2.1f,
                center = glow,
            )
            drawCircle(
                Brush.linearGradient(
                    listOf(lerp(c, Color.White, 0.35f), c, lerp(c, Color.Black, 0.18f)),
                    start = Offset(cx - r, cy - r),
                    end = Offset(cx + r, cy + r),
                ),
                radius = r,
                center = Offset(cx, cy),
            )
            drawCircle(Color.White.copy(alpha = 0.35f), radius = r - 0.5.dp.toPx(), center = Offset(cx, cy), style = Stroke(1.dp.toPx()))
        }

        // Resting icons: each fades and shrinks as the button passes over its place, and returns
        // when the button moves on. Each cell is the full-height touch target for its tab.
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
                            .padding(top = (RISE + 20f).dp)
                            .size(24.dp)
                            .graphicsLayer {
                                val near = (1f - abs(position.value - index)).coerceIn(0f, 1f)
                                alpha = 0.78f * (1f - near)
                                scaleX = 1f - 0.3f * near
                                scaleY = scaleX
                            },
                    )
                }
            }
        }

        // The active icon rides in the button and pops in gently on a change.
        Icon(
            items[selected].icon,
            contentDescription = null,
            tint = BubbleIcon,
            modifier = Modifier
                .offset {
                    val cx = itemWidth.toPx() * (position.value + 0.5f)
                    val cy = (RISE + BUBBLE_Y - LIFT * lift.value).dp.toPx()
                    val half = 13.dp.toPx()
                    IntOffset((cx - half).roundToInt(), (cy - half).roundToInt())
                }
                .size(26.dp)
                .graphicsLayer { scaleX = pop.value * 1.06f; scaleY = scaleX },
        )

        // The active tab's name under the button: blur + fade + slight rise on change.
        val labelWidth = itemWidth * 1.8f
        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                if (!motion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    val ms = (260 / speed).toInt()
                    (fadeIn(tween(ms, delayMillis = ms / 3)) + slideInVertically(tween(ms)) { it / 3 }) togetherWith
                        (fadeOut(tween(ms / 2)) + slideOutVertically(tween(ms)) { -it / 3 })
                }
            },
            modifier = Modifier
                .offset {
                    val cx = itemWidth.toPx() * (position.value + 0.5f)
                    IntOffset((cx - labelWidth.toPx() / 2).roundToInt(), (RISE + 40f).dp.roundToPx())
                }
                .width(labelWidth),
            label = "auraTitle",
        ) { index ->
            val blur by transition.animateFloat(label = "auraTitleBlur") { if (it == EnterExitState.Visible) 0f else 6f }
            Text(
                items[index].label,
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blur > 0.3f) {
                            val px = blur.dp.toPx()
                            renderEffect = BlurEffect(px, px, TileMode.Decal)
                        }
                    },
            )
        }
    }
}
