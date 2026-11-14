package com.example.mimic.ui.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mimic.ui.player.drawHardwareKey
import com.example.mimic.ui.components.GlowIcon

private val DockShape = RoundedCornerShape(16.dp)

@Composable
internal fun ControlDeckNavigation(
    selected: TopLevel?,
    onSelect: (TopLevel) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val matteAmber = com.example.mimic.ui.designsystem.LocalDepth.current.matteAmber
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
            .background(Brush.verticalGradient(listOf(Color(0xFF30353C), Color(0xFF191D22))), DockShape)
            .border(1.dp, Color(0xFF484E56), DockShape)
            .padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        for (tab in TopLevel.visible) {
            val active = tab == selected
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val focused by interaction.collectIsFocusedAsState()
            val travel by animateFloatAsState(if (pressed) 1f else 0f, tween(100), label = "Tab travel")
            val ink = if (matteAmber) Color(0xFFF6F2EA) else if (active) Color(0xFF0C0E11) else Color(0xFFF6F2EA)
            Column(
                Modifier.weight(1f).height(if (compact) 50.dp else 56.dp)
                    .then(if (compact) Modifier.semantics { contentDescription = tab.label } else Modifier)
                    .selectable(active, interaction, indication = null, role = Role.Tab, onClick = { onSelect(tab) })
                    .drawBehind { drawHardwareKey(if (matteAmber && active) maxOf(.45f, travel) else travel, active, focused, matteAmber = matteAmber) }
                    .graphicsLayer { translationY = size.minDimension * (-.05f + .09f * travel) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (matteAmber) GlowIcon(tab.icon, contentDescription = null, size = 20.dp, muted = !active)
                else Icon(tab.icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
                if (!compact) {
                    Text(tab.label, color = ink, fontSize = 9.sp, lineHeight = 12.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
