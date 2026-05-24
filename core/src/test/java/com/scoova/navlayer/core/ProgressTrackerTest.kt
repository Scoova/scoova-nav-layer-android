package com.scoova.navlayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressTrackerTest {

    /** Cyclist thresholds: 15, 50, 100, 200 meters. */
    private val cyclistThresholds = intArrayOf(15, 50, 100, 200)

    @Test
    fun update_crossingThreshold_firesOnce() {
        val t = ProgressTracker(cyclistThresholds)
        // Establish history above the 200m threshold so the max-seen
        // guard is satisfied for any fire.
        t.update(0, 250.0)
        // Cross 200m on this tick.
        val s = t.update(0, 180.0)
        assertEquals(200, s.firedThresholdM)
    }

    @Test
    fun update_staticAtThreshold_doesNotFireRepeatedly() {
        val t = ProgressTracker(cyclistThresholds)
        t.update(0, 250.0)  // establish maxSeen
        val first = t.update(0, 90.0)  // crosses 100m
        val second = t.update(0, 88.0)  // still ≤ 100m, but already fired
        val third = t.update(0, 92.0)  // still ≤ 100m
        assertEquals(100, first.firedThresholdM)
        assertEquals(-1, second.firedThresholdM)
        assertEquals(-1, third.firedThresholdM)
    }

    @Test
    fun update_singleCrossingPerThreshold_evenAfterMultipleSamplesBelow() {
        val t = ProgressTracker(cyclistThresholds)
        t.update(0, 250.0)
        t.update(0, 90.0)   // fires 100
        val s = t.update(0, 40.0)  // also crosses 50
        assertEquals(50, s.firedThresholdM)
    }

    @Test
    fun update_maxSeenGuardPreventsImmediateFireOnNearStart() {
        // Rider starts 4 m from the next maneuver — no history yet. The
        // max-seen guard should suppress all threshold fires because
        // we have never observed > threshold+20.
        val t = ProgressTracker(cyclistThresholds)
        val s = t.update(0, 4.0)
        assertEquals(-1, s.firedThresholdM)
    }

    @Test
    fun update_perManeuverFireSetsAreIndependent() {
        val t = ProgressTracker(cyclistThresholds)
        t.update(0, 250.0)
        t.update(0, 180.0)  // maneuver 0 fires 200m
        // Switch to maneuver 1 — its fire set is independent.
        t.update(1, 250.0)
        val s = t.update(1, 180.0)
        assertEquals(
            "maneuver 1 should be able to fire its own 200m threshold",
            200, s.firedThresholdM,
        )
    }

    @Test
    fun update_nonFiniteInputs_doNotCrash() {
        val t = ProgressTracker(cyclistThresholds)
        val s1 = t.update(0, Double.POSITIVE_INFINITY)
        val s2 = t.update(0, Double.NaN)
        assertEquals(-1, s1.firedThresholdM)
        assertEquals(-1, s2.firedThresholdM)
    }

    @Test
    fun thresholds_forProfile_returnsSortedArray() {
        val cyclist = Thresholds.forProfile("bicycle")
        // Must be ascending so [pickPhase] can binary-search.
        for (i in 1 until cyclist.size) {
            assertTrue(
                "expected ascending, found ${cyclist.joinToString()}",
                cyclist[i] > cyclist[i - 1],
            )
        }
    }

    @Test
    fun thresholds_unknownProfile_fallsBackToAuto() {
        val auto = Thresholds.forProfile("auto")
        val unknown = Thresholds.forProfile("hot-air-balloon")
        assertTrue(auto.contentEquals(unknown))
    }
}
