package com.example.mimic.data

/** Home carousels the user can reorder or hide. "All songs" is always shown. */
enum class HomeSection { RECENTLY_PLAYED, MOST_PLAYED, FAVORITES, RECENTLY_ADDED, SUGGESTED }

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
    /** Visible sections, in the user's chosen order; always complete and de-duplicated. */
    fun visibleOrdered(): List<HomeSection> {
        val ordered = (sectionOrder + HomeSection.entries).distinct()
        return ordered.filter { it !in hidden }
    }
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
