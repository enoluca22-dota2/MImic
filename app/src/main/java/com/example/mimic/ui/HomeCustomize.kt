package com.example.mimic.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mimic.data.HomeSection
import com.example.mimic.data.NavStyle
import com.example.mimic.data.movedBy
import com.example.mimic.player.PlayerViewModel

private fun HomeSection.label() = name.split("_")
    .joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }

@Composable
fun HomeCustomizeScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.homeConfig

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(
                "Home & Navigation",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        SelectRow(
            "Navigation style",
            listOf("Standard", "Floating", "Compact", "Liquid Glass", "Auto (theme)"),
            config.navStyle.ordinal,
        ) { vm.setHomeConfig(config.copy(navStyle = NavStyle.entries[it])) }
        Text(
            "Auto uses the floating glass pill on Liquid Glass themes and the standard bar otherwise.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Text(
            "Home sections",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        Text(
            "Toggle to show/hide. Arrows reorder. \"All songs\" is always shown.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )

        val order = config.sectionOrder.distinct().let { it + HomeSection.entries.filter { s -> s !in it } }
        order.forEachIndexed { index, section ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = section !in config.hidden,
                    onCheckedChange = { visible ->
                        val hidden = if (visible) config.hidden - section else config.hidden + section
                        vm.setHomeConfig(config.copy(hidden = hidden))
                    },
                )
                Spacer(Modifier.width(12.dp))
                Text(section.label(), Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
                IconButton(
                    onClick = { vm.setHomeConfig(config.copy(sectionOrder = order.movedBy(section, -1))) },
                    enabled = index > 0,
                ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up") }
                IconButton(
                    onClick = { vm.setHomeConfig(config.copy(sectionOrder = order.movedBy(section, +1))) },
                    enabled = index < order.size - 1,
                ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
