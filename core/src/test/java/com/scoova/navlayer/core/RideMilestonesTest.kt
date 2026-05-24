package com.scoova.navlayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideMilestonesTest {

    @Test
    fun lastCrossed_noCrossing_returnsNull() {
        assertNull(RideMilestones.lastCrossed(5.0, 9.0))   // both < first threshold (1) — wait, 5>1
        // Real "no crossing" case: both above all thresholds-of-interest.
        assertNull(RideMilestones.lastCrossed(15.0, 18.0))
    }

    @Test
    fun lastCrossed_exactlyAtThreshold_fires() {
        // newTotal >= threshold counts as crossed.
        assertEquals(RideMilestones.Crossed(10), RideMilestones.lastCrossed(5.0, 10.0))
    }

    @Test
    fun lastCrossed_multipleThresholdsAtOnce_returnsHighest() {
        // First ever ride, 120 km. Crosses 1, 10, 50, 100 — should
        // celebrate 100 not 1.
        assertEquals(RideMilestones.Crossed(100), RideMilestones.lastCrossed(0.0, 120.0))
    }

    @Test
    fun lastCrossed_newEqualsPrev_returnsNull() {
        assertNull(RideMilestones.lastCrossed(100.0, 100.0))
    }

    @Test
    fun lastCrossed_newLessThanPrev_returnsNull() {
        // Defensive — shouldn't happen in practice.
        assertNull(RideMilestones.lastCrossed(100.0, 80.0))
    }

    @Test
    fun lastCrossed_eachThresholdInTurn() {
        for (i in RideMilestones.THRESHOLDS_KM.indices) {
            val t = RideMilestones.THRESHOLDS_KM[i]
            val prev = (t - 0.5).coerceAtLeast(0.0)
            val crossed = RideMilestones.lastCrossed(prev, t.toDouble())
            assertEquals("threshold $t should fire", RideMilestones.Crossed(t), crossed)
        }
    }
}
