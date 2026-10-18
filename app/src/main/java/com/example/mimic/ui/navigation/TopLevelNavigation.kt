package com.example.mimic.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination

/** The four primary destinations (MImic 3.0). Everything else is pushed on top of one of them. */
enum class TopLevel(
    val route: String,
    val label: String,
    val icon: ImageVector,
    /** Whether switching back to this tab restores its last screen (else: always its root). */
    val restoresState: Boolean,
) {
    HOME("home", "Home", Icons.Default.Home, restoresState = false),
    SEARCH("search", "Search", Icons.Default.Search, restoresState = true),
    LIBRARY("library", "Library", Icons.Default.LibraryMusic, restoresState = true),
    SETTINGS("settings", "Settings", Icons.Default.Settings, restoresState = false),
    ;

    companion object {
        fun ofRoute(route: String?): TopLevel? = entries.firstOrNull { it.route == route }
    }
}

/**
 * Top-level navigation rules. Pure decisions live in [owningTab] / [plan] (JVM-tested); [navigate]
 * applies them to a NavController.
 *
 * ROOT CAUSE of the 2.9 bug ("Home needs several Back presses"): every tab tap used
 * `popUpTo(start) { saveState = true }` + `restoreState = true`. Sub-screens such as
 * Settings → Account → Server Library were pushed *on top of Home* (Settings was not a tab), and a
 * non-inclusive popUpTo(home) with saveState files the popped screens under Home's own id. Tapping
 * Home then *restored* Settings → Account → Server Library instead of showing Home.
 *
 * Rules now:
 *  - HOME always lands on the Home root in one tap: its saved state is never restored.
 *  - SETTINGS also always opens at its root: a half-finished settings flow (Account, Sign In,
 *    Server Library) is never resurrected by a tab tap.
 *  - SEARCH / LIBRARY are browsing contexts: switching back restores where you were (standard
 *    Android behavior). Re-tapping the tab you are already in pops back to its root.
 *  - launchSingleTop everywhere, so a tab destination is never duplicated.
 */
object TopLevelNavigation {

    /** The tab that owns the current back stack: the top-most tab root in it (Home if none). */
    fun owningTab(backStackRoutes: List<String?>): TopLevel =
        backStackRoutes.asReversed().firstNotNullOfOrNull { TopLevel.ofRoute(it) } ?: TopLevel.HOME

    sealed interface Plan {
        /** Pop everything above [tab]'s root (already in this tab, or going Home). */
        data class PopToRoot(val tab: TopLevel) : Plan

        /** Switch tabs, saving the current stack; [restore] brings back the target's saved stack. */
        data class SwitchTab(val tab: TopLevel, val restore: Boolean) : Plan
    }

    fun plan(target: TopLevel, backStackRoutes: List<String?>): Plan {
        val current = owningTab(backStackRoutes)
        return when {
            current == target -> Plan.PopToRoot(target)
            else -> Plan.SwitchTab(target, restore = target.restoresState)
        }
    }

    fun navigate(navController: NavController, target: TopLevel) {
        val routes = navController.currentBackStack.value.map { it.destination.route }
        when (val p = plan(target, routes)) {
            is Plan.PopToRoot -> {
                if (!navController.popBackStack(p.tab.route, inclusive = false)) {
                    navController.navigate(p.tab.route) { launchSingleTop = true }
                }
            }
            is Plan.SwitchTab -> navController.navigate(p.tab.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = p.restore
            }
        }
    }
}
