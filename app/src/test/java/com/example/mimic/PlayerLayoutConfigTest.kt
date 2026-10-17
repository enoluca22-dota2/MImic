package com.example.mimic

import com.example.mimic.data.ArtworkShape
import com.example.mimic.data.ArtworkSize
import com.example.mimic.data.cornerDp
import com.example.mimic.data.fraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLayoutConfigTest {

    @Test fun artwork_sizes_increase_monotonically_within_bounds() {
        val s = ArtworkSize.SMALL.fraction()
        val m = ArtworkSize.MEDIUM.fraction()
        val l = ArtworkSize.LARGE.fraction()
        assertTrue(s < m && m < l)
        assertTrue(s > 0f && l <= 1f)
    }

    @Test fun shape_corners_are_sensible() {
        assertEquals(0, ArtworkShape.SQUARE.cornerDp())
        assertTrue(ArtworkShape.ROUNDED.cornerDp() > 0)
    }
}
