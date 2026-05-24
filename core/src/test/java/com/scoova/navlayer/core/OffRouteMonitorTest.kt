package com.scoova.navlayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OffRouteMonitorTest {

    /** East-west polyline at lat=30.0, lon ∈ [31.0, 31.1]. */
    private val route = listOf(
        doubleArrayOf(30.0, 31.0),
        doubleArrayOf(30.0, 31.1),
    )

    @Test
    fun onLocation_pointOnRoute_staysClean() {
        val m = OffRouteMonitor(thresholdMeters = 25.0, confirmSamples = 3)
        m.setRoute(route)
        repeat(5) { m.onLocation(30.0, 31.05) }
        assertFalse(m.isOffRoute.value)
    }

    @Test
    fun onLocation_singleOffSample_doesNotFlip() {
        val m = OffRouteMonitor(thresholdMeters = 25.0, confirmSamples = 3)
        m.setRoute(route)
        m.onLocation(30.0, 31.05)            // on
        m.onLocation(30.002, 31.05)          // ~222m off — single sample
        assertFalse(
            "single off-sample must not flip — protects against GPS multipath",
            m.isOffRoute.value,
        )
    }

    @Test
    fun onLocation_sustainedOff_flipsAfterConfirmSamples() {
        val m = OffRouteMonitor(thresholdMeters = 25.0, confirmSamples = 3)
        m.setRoute(route)
        // 3 consecutive off-samples → flip
        m.onLocation(30.002, 31.05)
        m.onLocation(30.002, 31.05)
        m.onLocation(30.002, 31.05)
        assertTrue(m.isOffRoute.value)
    }

    @Test
    fun onLocation_returningOnRoute_flipsBackAfterConfirmSamples() {
        val m = OffRouteMonitor(thresholdMeters = 25.0, confirmSamples = 3)
        m.setRoute(route)
        repeat(3) { m.onLocation(30.002, 31.05) }
        assertTrue(m.isOffRoute.value)
        repeat(3) { m.onLocation(30.0, 31.05) }
        assertFalse(m.isOffRoute.value)
    }

    @Test
    fun setRoute_emptyShape_skipsEvaluation() {
        val m = OffRouteMonitor(thresholdMeters = 25.0, confirmSamples = 3)
        m.setRoute(emptyList())
        // Even a wildly-off sample shouldn't flip when there's no route.
        repeat(10) { m.onLocation(30.5, 31.5) }
        assertFalse(m.isOffRoute.value)
    }

    @Test
    fun reset_clearsOffRouteFlag() {
        val m = OffRouteMonitor(thresholdMeters = 25.0, confirmSamples = 3)
        m.setRoute(route)
        repeat(3) { m.onLocation(30.002, 31.05) }
        assertTrue(m.isOffRoute.value)
        m.reset()
        assertFalse(m.isOffRoute.value)
        assertEquals(0.0, m.lastDistanceMeters.value, 0.0)
    }
}
