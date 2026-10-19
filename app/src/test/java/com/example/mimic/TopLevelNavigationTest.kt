package com.example.mimic

import com.example.mimic.ui.navigation.TopLevel
import com.example.mimic.ui.navigation.TopLevelNavigation
import com.example.mimic.ui.navigation.TopLevelNavigation.Plan
import org.junit.Assert.assertEquals
import org.junit.Test

/** The 3.0 one-tap-Home rules, as pure decisions over the back stack's routes. */
class TopLevelNavigationTest {

    // Back stacks as NavController reports them (null = the graph root entry).
    private val deepSettings = listOf(null, "home", "settings", "musicserver", "serverbrowse", "serveralbum/{id}")
    private val deepHome = listOf(null, "home", "album/{key}", "artist/{key}")
    private val deepLibrary = listOf(null, "home", "library", "album/{key}")

    @Test fun owning_tab_is_the_topmost_tab_root() {
        assertEquals(TopLevel.SETTINGS, TopLevelNavigation.owningTab(deepSettings))
        assertEquals(TopLevel.HOME, TopLevelNavigation.owningTab(deepHome))
        assertEquals(TopLevel.LIBRARY, TopLevelNavigation.owningTab(deepLibrary))
        assertEquals(TopLevel.HOME, TopLevelNavigation.owningTab(listOf(null)))
    }

    @Test fun home_from_deep_settings_goes_to_home_root_without_restoring() {
        // The exact 2.9 bug path: Settings > Account > Server Library > Album > Home.
        assertEquals(Plan.SwitchTab(TopLevel.HOME, restore = false), TopLevelNavigation.plan(TopLevel.HOME, deepSettings))
    }

    @Test fun home_from_deep_home_pops_to_root() {
        assertEquals(Plan.PopToRoot(TopLevel.HOME), TopLevelNavigation.plan(TopLevel.HOME, deepHome))
    }

    @Test fun home_from_library_never_restores_home_state() {
        assertEquals(Plan.SwitchTab(TopLevel.HOME, restore = false), TopLevelNavigation.plan(TopLevel.HOME, deepLibrary))
    }

    @Test fun reselecting_current_tab_pops_to_its_root() {
        assertEquals(Plan.PopToRoot(TopLevel.LIBRARY), TopLevelNavigation.plan(TopLevel.LIBRARY, deepLibrary))
        assertEquals(Plan.PopToRoot(TopLevel.SETTINGS), TopLevelNavigation.plan(TopLevel.SETTINGS, deepSettings))
    }

    @Test fun retapping_a_tab_on_its_root_scrolls_to_top() {
        assertEquals(Plan.ScrollToTop(TopLevel.HOME), TopLevelNavigation.plan(TopLevel.HOME, listOf(null, "home")))
        assertEquals(Plan.ScrollToTop(TopLevel.LIBRARY), TopLevelNavigation.plan(TopLevel.LIBRARY, listOf(null, "home", "library")))
        // Home's root is under a pushed screen: pop, don't just scroll.
        assertEquals(Plan.PopToRoot(TopLevel.HOME), TopLevelNavigation.plan(TopLevel.HOME, listOf(null, "home", "downloads")))
    }

    @Test fun browsing_tabs_restore_settings_does_not() {
        assertEquals(Plan.SwitchTab(TopLevel.LIBRARY, restore = true), TopLevelNavigation.plan(TopLevel.LIBRARY, deepHome))
        assertEquals(Plan.SwitchTab(TopLevel.SEARCH, restore = true), TopLevelNavigation.plan(TopLevel.SEARCH, deepHome))
        assertEquals(Plan.SwitchTab(TopLevel.SETTINGS, restore = false), TopLevelNavigation.plan(TopLevel.SETTINGS, deepLibrary))
    }

    @Test fun six_tabs_in_order_in_every_edition() {
        // Since 4.1 Downloads holds offline music too, so it is a tab in the standard edition as well.
        assertEquals(listOf(TopLevel.HOME, TopLevel.SEARCH, TopLevel.LIBRARY, TopLevel.RADIO, TopLevel.DOWNLOADS, TopLevel.SETTINGS), TopLevel.visible)
        // The downloader tab never collides with the Library's offline-songs screen ("downloads").
        assertEquals("downloader", TopLevel.DOWNLOADS.route)
        assertEquals(TopLevel.DOWNLOADS, TopLevelNavigation.owningTab(listOf(null, "home", "downloader")))
    }
}
