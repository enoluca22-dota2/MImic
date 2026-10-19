package com.example.mimic.data

/**
 * Home sections the user can reorder or hide. Declaration order is the default order (MImic 3.0
 * dashboard). Names are persisted — never rename one; RECENTLY_PLAYED is "Continue Listening".
 */
enum class HomeSection(val label: String) {
    QUICK_ACCESS("Quick Access"), // MImic 3.2: compact grid of Liked Songs, Downloads, recent albums
    RECENTLY_PLAYED("Continue Listening"),
    DOWNLOADS("Downloaded Music"),
    RECENT_SONGS("Recently Played"), // MImic 3.2: the play history (Continue Listening = albums)
    RECENTLY_ADDED("Recently Added"),
    FAVORITES("Favorite Songs"),
    FAVORITE_ARTISTS("Favorite Artists"),
    ALBUMS("Albums"), // newest albums from every source
    PLAYLISTS("My Playlists"),
    SMART_COLLECTIONS("Smart Mixes"),
    MOST_PLAYED("Most Played"),
    SUGGESTED("Suggested For You"),
}

/**
 * Bottom navigation styles. AUTO (the default) follows the theme: glass themes get the floating
 * glass pill, every other theme the standard bar, so a user who never touched this setting sees
 * exactly the pre-2.9 bar until they pick a glass theme.
 */
enum class NavStyle { STANDARD, FLOATING, COMPACT, GLASS, AUTO }

/**
 * Resolves the user's nav style against the active theme. AUTO follows the theme's pill-nav wish;
 * FLOATING on a glass theme *is* the floating glass pill (an opaque floating bar would look broken
 * there). STANDARD / COMPACT / GLASS are explicit choices and always win.
 */
fun NavStyle.resolve(themeWantsGlassNav: Boolean, glassTheme: Boolean = themeWantsGlassNav): NavStyle = when (this) {
    NavStyle.AUTO -> if (themeWantsGlassNav) NavStyle.GLASS else NavStyle.STANDARD
    NavStyle.FLOATING -> if (glassTheme) NavStyle.GLASS else NavStyle.FLOATING
    else -> this
}

data class HomeConfig(
    val sectionOrder: List<HomeSection> = HomeSection.entries,
    val hidden: Set<HomeSection> = emptySet(),
    val navStyle: NavStyle = NavStyle.AUTO,
) {
    /**
     * The user's order, completed with any section they have never seen (e.g. added in a newer
     * version): each missing section goes right after its nearest default-order predecessor that
     * the user has, so a saved 2.x order gains "Downloaded Music" after "Continue Listening"
     * instead of at the very bottom. Sections with no such predecessor are appended — except a new
     * *first* default section (3.2 Quick Access), which opens Home for upgraders too.
     */
    fun completeOrder(): List<HomeSection> {
        val order = sectionOrder.distinct().toMutableList()
        val defaults = HomeSection.entries
        val leading = defaults.first().takeIf { it !in order }
        for ((i, section) in defaults.withIndex()) {
            if (section in order || section == leading) continue
            val anchor = defaults.subList(0, i).lastOrNull { it in order }
            if (anchor == null) order.add(section) else order.add(order.indexOf(anchor) + 1, section)
        }
        if (leading != null) order.add(0, leading)
        return order
    }

    /** Visible sections, in the user's chosen order; always complete and de-duplicated. */
    fun visibleOrdered(): List<HomeSection> = completeOrder().filter { it !in hidden }
}

/** Move a section one step up/down in the order (pure, unit-testable). */
fun List<HomeSection>.movedBy(section: HomeSection, delta: Int): List<HomeSection> {
    val idx = indexOf(section)
    if (idx < 0) return this
    val target = (idx + delta).coerceIn(0, size - 1)
    if (target == idx) return this
    val copy = toMutableList()
    copy.removeAt(idx)
    copy.add(target, section)
    return copy
}
