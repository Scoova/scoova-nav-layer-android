package com.scoova.navlayer.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the user-reported "walking on the same direction
 * but not exactly on the line — that's not off-route" complaint.
 *
 * Each test feeds GuidanceMonitor a straight north-running route then
 * pushes a ProgressEvent with the rider OFFSET laterally by the amount
 * under test and a bearing that either matches the route (parallel
 * walking) or opposes it (genuine wrong-way).
 */
class ParallelWalkingTest {

    /** 1 km straight route running due north from (40.7600, -73.9850). */
    private fun straightNorthRoute(): List<DoubleArray> {
        val lat0 = 40.7600
        val lon0 = -73.9850
        return listOf(
            doubleArrayOf(lat0,           lon0),
            doubleArrayOf(lat0 + 0.00450, lon0),
            doubleArrayOf(lat0 + 0.00898, lon0),
        )
    }

    /** 15 m east of the route line ≈ +0.000179° lon at this latitude. */
    private val offsetLon15mEast = 0.000179
    private val northBearing: Float = 0f
    private val southBearing: Float = 180f

    private fun progress(
        lat: Double, lon: Double,
        speed: Float, bearing: Float,
    ) = ProgressEvent(
        latitude = lat, longitude = lon,
        speedMps = speed, bearingDeg = bearing,
        upcomingManeuverIndex = 1,
        metersToUpcomingManeuver = 500.0,
        secondsRemaining = 600,
        metersRemaining = 800,
    )

    @Test
    fun pedestrianSidewalkParallelSuppressesOffRoute() {
        val monitor = GuidanceMonitor()
        monitor.setRoute(straightNorthRoute())
        monitor.setCosting("pedestrian")

        // Sidewalk 15 m east of centerline, walking north at 1.5 m/s.
        // The NYC case from the user complaint.
        val lat0 = 40.7600
        val lon0 = -73.9850 + offsetLon15mEast
        val allEvents = mutableListOf<GuidanceEvent>()
        for (tick in 0..8) {
            val p = progress(
                lat = lat0 + 0.000004 * tick,
                lon = lon0,
                speed = 1.5f, bearing = northBearing,
            )
            allEvents += monitor.onProgress(p, nowMs = 1_000_000L + tick * 1_000L)
        }
        val driftLeft = allEvents.any { it is GuidanceEvent.DriftLeft }
        val driftRight = allEvents.any { it is GuidanceEvent.DriftRight }
        val offRoute = allEvents.any { it is GuidanceEvent.OffRoute }
        assertFalse(
            "Parallel-walking pedestrian should NOT trigger driftLeft",
            driftLeft,
        )
        assertFalse(
            "Parallel-walking pedestrian should NOT trigger driftRight",
            driftRight,
        )
        assertFalse(
            "Parallel-walking pedestrian 15 m off the centerline must NOT trigger off-route",
            offRoute,
        )
    }

    @Test
    fun pedestrianWrongDirectionDoesFireOffRoute() {
        val monitor = GuidanceMonitor()
        monitor.setRoute(straightNorthRoute())
        monitor.setCosting("pedestrian")
        // 75 m east AND heading south — parallel suppression must NOT apply.
        val lat0 = 40.7610
        val lon0 = -73.9850 + offsetLon15mEast * 5
        val allEvents = mutableListOf<GuidanceEvent>()
        for (tick in 0..8) {
            val p = progress(
                lat = lat0 - 0.000004 * tick,
                lon = lon0,
                speed = 1.5f, bearing = southBearing,
            )
            allEvents += monitor.onProgress(p, nowMs = 1_000_000L + tick * 1_000L)
        }
        assertTrue(
            "Pedestrian 75 m off-line AND heading opposite route MUST trigger off-route",
            allEvents.any { it is GuidanceEvent.OffRoute },
        )
    }

    @Test
    fun autoFiresOffRouteAt35mWhilePedestrianDoesNot() {
        fun runMonitor(costing: String): Boolean {
            val monitor = GuidanceMonitor()
            monitor.setRoute(straightNorthRoute())
            monitor.setCosting(costing)
            // 35 m east, heading south (opposite route).
            val lat0 = 40.7610
            val lon0 = -73.9850 + offsetLon15mEast * 35.0 / 15.0
            val allEvents = mutableListOf<GuidanceEvent>()
            for (tick in 0..8) {
                val p = progress(
                    lat = lat0 - 0.000004 * tick,
                    lon = lon0,
                    speed = 1.5f, bearing = southBearing,
                )
                allEvents += monitor.onProgress(p, nowMs = 1_000_000L + tick * 1_000L)
            }
            return allEvents.any { it is GuidanceEvent.OffRoute }
        }
        assertTrue(
            "Car 35 m off-line MUST trigger off-route (threshold 30 m)",
            runMonitor("auto"),
        )
        assertFalse(
            "Pedestrian 35 m off-line must NOT trigger off-route (threshold 60 m)",
            runMonitor("pedestrian"),
        )
    }

    @Test
    fun standstillNoParallelBypass() {
        val monitor = GuidanceMonitor()
        monitor.setRoute(straightNorthRoute())
        monitor.setCosting("pedestrian")
        // Stopped rider 75 m off-line. Bearing nominally matches route
        // but speed = 0 disables the parallel-walking gate.
        val lat0 = 40.7610
        val lon0 = -73.9850 + offsetLon15mEast * 5
        val allEvents = mutableListOf<GuidanceEvent>()
        for (tick in 0..8) {
            val p = progress(
                lat = lat0, lon = lon0,
                speed = 0.0f, bearing = northBearing,
            )
            allEvents += monitor.onProgress(p, nowMs = 1_000_000L + tick * 1_000L)
        }
        assertTrue(
            "Stopped rider 75 m off-line MUST trigger off-route — parallel bypass needs speed > 0.5 m/s",
            allEvents.any { it is GuidanceEvent.OffRoute },
        )
    }
}
