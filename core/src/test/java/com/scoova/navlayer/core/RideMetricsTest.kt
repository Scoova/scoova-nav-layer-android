package com.scoova.navlayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideMetricsTest {

    @Test
    fun metFor_cyclingScales_byEffortBand() {
        // The MET curve must be monotonic — faster cycling = more MET.
        val slow = RideMetrics.metFor(RideMetrics.Mode.Cycling, 12.0)
        val mid = RideMetrics.metFor(RideMetrics.Mode.Cycling, 18.0)
        val fast = RideMetrics.metFor(RideMetrics.Mode.Cycling, 28.0)
        assertTrue("$slow <= $mid", slow <= mid)
        assertTrue("$mid <= $fast", mid <= fast)
    }

    @Test
    fun caloriesBurned_zeroDistance_isZero() {
        val cal = RideMetrics.caloriesBurned(
            mode = RideMetrics.Mode.Cycling,
            distanceKm = 0.0,
            durationMinutes = 0,
            weightKg = 70.0,
        )
        assertEquals(0, cal)
    }

    @Test
    fun caloriesBurned_oneHourModeratePace_inExpectedRange() {
        // 18 km/h cycling for 60 min at 70 kg ≈ 7 MET · 70 kg · 1 h ≈ 490 kcal
        // Allow ±25% to absorb minor MET-curve revisions.
        val cal = RideMetrics.caloriesBurned(
            mode = RideMetrics.Mode.Cycling,
            distanceKm = 18.0,
            durationMinutes = 60,
            weightKg = 70.0,
        )
        assertTrue("cycling 18km in 60min ≈ 490 kcal, got $cal", cal in 350..650)
    }

    @Test
    fun caloriesBurned_heavierRider_burnsMore() {
        val light = RideMetrics.caloriesBurned(
            RideMetrics.Mode.Cycling, 18.0, 60, 50.0,
        )
        val heavy = RideMetrics.caloriesBurned(
            RideMetrics.Mode.Cycling, 18.0, 60, 100.0,
        )
        assertTrue("$heavy should be > $light", heavy > light)
    }

    @Test
    fun co2SavedGrams_drivingMode_isZero() {
        // Driving doesn't avoid emissions — it produces them. The
        // "saved" estimate is for human-powered modes only.
        val saved = RideMetrics.co2SavedGrams(RideMetrics.Mode.Driving, 10.0)
        assertEquals(0, saved)
    }

    @Test
    fun co2SavedGrams_cyclingTenKm_returnsPositive() {
        val saved = RideMetrics.co2SavedGrams(RideMetrics.Mode.Cycling, 10.0)
        assertTrue("expected positive savings for cycling, got $saved", saved > 0)
    }

    @Test
    fun activeMinutes_drivingIsZero() {
        assertEquals(0, RideMetrics.activeMinutes(RideMetrics.Mode.Driving, 60))
    }

    @Test
    fun activeMinutes_cyclingIsFull() {
        // Cycling counts every minute as active.
        assertEquals(60, RideMetrics.activeMinutes(RideMetrics.Mode.Cycling, 60))
    }
}
