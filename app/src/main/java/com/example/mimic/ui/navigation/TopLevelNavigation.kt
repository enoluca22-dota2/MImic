package com.example.mimic.ui.navigation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination

/** Re-taps of a tab while its root screen is showing (MImic 3.2): that screen scrolls to the top. */
val LocalTabReselect = staticCompositionLocalOf<SharedFlow<TopLevel>> { MutableSharedFlow() }

/** Scrolls [state] back to the top whenever [tab] is re-tapped on its root screen. */
@Composable
fun ScrollToTopOnReselect(tab: TopLevel, state: LazyListState) {
    val events = LocalTabReselect.current
    LaunchedEffect(events, state) {
        events.collect {
            if (it != tab) return@collect
            // Jump most of the way first so a long list doesn't animate through every row.
            if (state.firstVisibleItemIndex > 12) state.scrollToItem(6)
            state.animateScrollToItem(0)
        }
    }
}

/**
 * The primary destinations: Home | Search | Library | Downloads | Settings (Downloads since 4.0,
 * offline music + the full edition's link downloader). Everything else is pushed on top of one of them.
 */
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
    // Route "downloader": "downloads" is the Library's offline-songs screen.
    DOWNLOADS("downloader", "Downloads", Icons.Default.Download, restoresState = true),
    SETTINGS("settings", "Settings", Icons.Default.Settings, restoresState = false),
    ;

    companion object {
        fun ofRoute(route: String?): TopLevel? = entries.firstOrNull { it.route == route }

        /** Tabs shown in the navigation bar (Downloads holds offline music in every edition since 4.1). */
        val visible: List<TopLevel> = entries.toList()
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

        /** Already on [tab]'s root screen: nothing to navigate, the screen scrolls back to the top. */
        data class ScrollToTop(val tab: TopLevel) : Plan
    }

    fun plan(target: TopLevel, backStackRoutes: List<String?>): Plan {
        val current = owningTab(backStackRoutes)
        return when {
            current == target && backStackRoutes.lastOrNull() == target.route -> Plan.ScrollToTop(target)
            current == target -> Plan.PopToRoot(target)
            else -> Plan.SwitchTab(target, restore = target.restoresState)
        }
    }

    /** Applies the plan; returns the tab to scroll to the top when the user re-tapped its root. */
    @android.annotation.SuppressLint("RestrictedApi") // currentBackStack: the only whole-stack view
    fun navigate(navController: NavController, target: TopLevel): TopLevel? {
        val routes = navController.currentBackStack.value.map { it.destination.route }
        when (val p = plan(target, routes)) {
            is Plan.ScrollToTop -> return p.tab
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
        return null
    }
}
