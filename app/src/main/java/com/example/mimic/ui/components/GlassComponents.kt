package com.example.mimic.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.mimic.ui.designsystem.LocalGlass
import com.example.mimic.ui.designsystem.NavIndicator
import com.example.mimic.ui.designsystem.glassSurface
import com.example.mimic.ui.designsystem.pressScale
import com.example.mimic.ui.designsystem.roundControlShape
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

data class GlassNavItem(val label: String, val icon: ImageVector)

/**
 * Floating Liquid Glass navigation: a rounded pill that hovers above the system navigation bar
 * (caller applies the nav-bar inset), with a sliding selection indicator. The indicator is the
 * only animated element and it animates only on a selection change — nothing runs while idle.
 * Each tab is a full-height, ≥48dp touch target.
 */
@Composable
fun GlassNavBar(
    items: List<GlassNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true,
) {
    val env = LocalGlass.current
    val shape = RoundedRectangle(env.style.navCornerRadius.dp)
    val primary = MaterialTheme.colorScheme.primary
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .height(if (showLabels) 64.dp else 56.dp)
            .glassSurface(shape, fallback = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val itemWidth: Dp = maxWidth / items.size.coerceAtLeast(1)
        val target = itemWidth * selectedIndex.coerceAtLeast(0)
        val offset by animateDpAsState(
            targetValue = target,
            animationSpec = if (env.motionScale == 0f) snap() else spring(
                dampingRatio = 0.72f,
                stiffness = Spring.StiffnessMediumLow * env.style.transitionSpeed,
            ),
            label = "navIndicator",
        )
        if (selectedIndex >= 0 && env.style.navIndicator == NavIndicator.PILL) {
            Box(
                Modifier
                    .offset(x = offset)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .padding(5.dp)
                    .background(primary.copy(alpha = if (env.darkGlass) 0.22f else 0.16f), Capsule()),
            )
        }
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                val interaction = remember { MutableInteractionSource() }
                val tint = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pressScale(interaction)
                        .clickable(
                            interactionSource = interaction,
                            indication = ripple(bounded = false, radius = 36.dp),
                            role = Role.Tab,
                            onClick = { onSelect(index) },
                        )
                        .semantics { this.selected = selected },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(24.dp))
                    if (showLabels) {
                        Text(
                            item.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = tint,
                            maxLines = 1,
                        )
                    }
                    if (env.style.navIndicator == NavIndicator.DOT) {
                        Spacer(Modifier.height(3.dp))
                        Box(
                            Modifier.size(4.dp).background(if (selected) primary else Color.Transparent, CircleShape),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A round glass button (≥48dp target). [accent] fills it with the primary color through the
 * glass for the main action (play/pause); otherwise it is a neutral frosted circle.
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    accent: Boolean = false,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    val env = LocalGlass.current
    val interaction = remember { MutableInteractionSource() }
    val shape = env.style.roundControlShape()
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .size(size)
            .pressScale(interaction, maxShrink = 0.08f)
            .glassSurface(
                shape,
                fallback = if (accent) primary else MaterialTheme.colorScheme.surfaceVariant,
                tint = if (accent) primary else null,
                opacityBoost = if (accent) 0.35f else 0f,
            )
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint ?: if (accent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(iconSize),
        )
    }
}
