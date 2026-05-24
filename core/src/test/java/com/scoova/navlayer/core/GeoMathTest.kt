package com.scoova.navlayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {

    private val cairoTahrir = doubleArrayOf(30.0444, 31.2357)  // Tahrir Square
    private val cairoRamses = doubleArrayOf(30.0626, 31.2497)  // Ramses Station — ~2 km north-east

    @Test
    fun haversine_zeroDistance_returnsZero() {
        val d = GeoMath.haversineMeters(30.0, 31.0, 30.0, 31.0)
        assertEquals(0.0, d, 1e-6)
    }

    @Test
    fun haversine_cairoLandmarks_inExpectedRange() {
        val d = GeoMath.haversineMeters(
            cairoTahrir[0], cairoTahrir[1], cairoRamses[0], cairoRamses[1],
        )
        // Tahrir-Ramses straight-line distance is around 2.4 km
        // depending on the exact coords used. Generous tolerance — the
        // test is asserting "haversine doesn't return garbage", not
        // exact distance.
        assertTrue("expected 2–3km got $d", d in 2_000.0..3_000.0)
    }

    @Test
    fun bearing_pointDirectlyNorth_returnsZero() {
        val b = GeoMath.bearingDeg(30.0, 31.0, 30.01, 31.0)
        // 0° is north; small tolerance for spherical approximation.
        assertTrue("expected ~0° got $b", b < 1.0 || b > 359.0)
    }

    @Test
    fun bearing_pointDirectlyEast_returnsNinety() {
        val b = GeoMath.bearingDeg(30.0, 31.0, 30.0, 31.01)
        assertTrue("expected ~90° got $b", b in 89.0..91.0)
    }

    @Test
    fun bearing_pointDirectlySouth_returnsOneEighty() {
        val b = GeoMath.bearingDeg(30.0, 31.0, 29.99, 31.0)
        assertTrue("expected ~180° got $b", b in 179.0..181.0)
    }

    @Test
    fun distanceToPolyline_pointOnLine_returnsNearZero() {
        // Straight east-west line.
        val poly = listOf(
            doubleArrayOf(30.0, 31.0),
            doubleArrayOf(30.0, 31.1),
        )
        // Exact midpoint.
        val d = GeoMath.distanceToPolylineMeters(30.0, 31.05, poly)
        assertTrue("expected ≤ 2m got $d", d < 2.0)
    }

    @Test
    fun distanceToPolyline_pointOffLine_returnsPerpendicular() {
        // Same line, but point shifted ~111 m north of midpoint.
        val poly = listOf(
            doubleArrayOf(30.0, 31.0),
            doubleArrayOf(30.0, 31.1),
        )
        val d = GeoMath.distanceToPolylineMeters(30.001, 31.05, poly)
        // 0.001° latitude ≈ 111 m. Allow ±5 m.
        assertTrue("expected ~111m got $d", d in 106.0..116.0)
    }

    @Test
    fun distanceToPolyline_emptyOrSingleVertex_returnsInfinity() {
        assertEquals(Double.POSITIVE_INFINITY, GeoMath.distanceToPolylineMeters(30.0, 31.0, emptyList()), 0.0)
        assertEquals(
            Double.POSITIVE_INFINITY,
            GeoMath.distanceToPolylineMeters(30.0, 31.0, listOf(doubleArrayOf(30.0, 31.0))),
            0.0,
        )
    }

    @Test
    fun progressAlongPolyline_atStart_isZero() {
        val poly = listOf(
            doubleArrayOf(30.0, 31.0),
            doubleArrayOf(30.0, 31.1),
        )
        val progress = GeoMath.progressAlongPolyline(30.0, 31.0, poly)
        assertTrue("expected ~0m got $progress", progress < 5.0)
    }

    @Test
    fun progressAlongPolyline_atMidpoint_isHalf() {
        val poly = listOf(
            doubleArrayOf(30.0, 31.0),
            doubleArrayOf(30.0, 31.1),
        )
        val total = GeoMath.cumulativeDistanceMeters(poly, 1)
        val progress = GeoMath.progressAlongPolyline(30.0, 31.05, poly)
        // Within 1% of the half-total.
        val half = total / 2
        assertTrue("expected ~${half} got $progress", kotlin.math.abs(progress - half) < total * 0.01)
    }

    @Test
    fun progressAlongPolyline_atEnd_isTotal() {
        val poly = listOf(
            doubleArrayOf(30.0, 31.0),
            doubleArrayOf(30.0, 31.1),
        )
        val total = GeoMath.cumulativeDistanceMeters(poly, 1)
        val progress = GeoMath.progressAlongPolyline(30.0, 31.1, poly)
        assertTrue("expected ~total got $progress", kotlin.math.abs(progress - total) < 5.0)
    }

    @Test
    fun progressAlongPolyline_multipleSegments_aggregates() {
        // L-shaped path: east 0.1° then north 0.1°.
        val poly = listOf(
            doubleArrayOf(30.0, 31.0),
            doubleArrayOf(30.0, 31.1),
            doubleArrayOf(30.1, 31.1),
        )
        val total = GeoMath.cumulativeDistanceMeters(poly, 2)
        // Probe a point on the second leg, halfway up the north segment.
        val progress = GeoMath.progressAlongPolyline(30.05, 31.1, poly)
        // First leg full + half second leg.
        val secondLegHalf =
            GeoMath.haversineMeters(30.0, 31.1, 30.05, 31.1)
        val firstLeg = GeoMath.haversineMeters(30.0, 31.0, 30.0, 31.1)
        val expected = firstLeg + secondLegHalf
        assertTrue(
            "expected ~$expected (of total $total) got $progress",
            kotlin.math.abs(progress - expected) < 10.0,
        )
    }

    @Test
    fun cumulativeDistance_atIndexZero_isZero() {
        val poly = listOf(
            doubleArrayOf(30.0, 31.0),
            doubleArrayOf(30.0, 31.1),
        )
        assertEquals(0.0, GeoMath.cumulativeDistanceMeters(poly, 0), 0.0)
    }
}
