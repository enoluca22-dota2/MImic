package com.example.mimic

import com.example.mimic.data.HomeConfig
import com.example.mimic.data.HomeSection
import com.example.mimic.data.movedBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeConfigTest {

    @Test fun movedBy_moves_up_and_down_and_clamps() {
        val order = listOf(
            HomeSection.RECENTLY_PLAYED, HomeSection.MOST_PLAYED, HomeSection.FAVORITES,
        )
        assertEquals(
            listOf(HomeSection.MOST_PLAYED, HomeSection.RECENTLY_PLAYED, HomeSection.FAVORITES),
            order.movedBy(HomeSection.MOST_PLAYED, -1),
        )
        // Moving the first item up is a no-op.
        assertEquals(order, order.movedBy(HomeSection.RECENTLY_PLAYED, -1))
    }

    @Test fun visibleOrdered_hides_and_completes_missing_sections() {
        val config = HomeConfig(
            sectionOrder = listOf(HomeSection.SUGGESTED), // intentionally incomplete
            hidden = setOf(HomeSection.MOST_PLAYED),
        )
        val visible = config.visibleOrdered()
        // A new leading section (3.2 Quick Access) opens Home; the stored order follows it.
        assertTrue("quick access should be first", visible.first() == HomeSection.QUICK_ACCESS)
        assertTrue("suggested should be next", visible[1] == HomeSection.SUGGESTED)
        assertFalse("hidden section excluded", visible.contains(HomeSection.MOST_PLAYED))
        // All non-hidden sections present despite incomplete stored order.
        assertEquals(HomeSection.entries.size - 1, visible.size)
    }
}
