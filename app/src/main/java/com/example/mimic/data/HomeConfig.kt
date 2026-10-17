package com.example.mimic.data

/** Home carousels the user can reorder or hide. "All songs" is always shown. */
enum class HomeSection { RECENTLY_PLAYED, MOST_PLAYED, FAVORITES, RECENTLY_ADDED, SUGGESTED }

/** Bottom navigation styles. */
enum class NavStyle { STANDARD, FLOATING, COMPACT }

data class HomeConfig(
    val sectionOrder: List<HomeSection> = HomeSection.entries,
    val hidden: Set<HomeSection> = emptySet(),
    val navStyle: NavStyle = NavStyle.STANDARD,
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
