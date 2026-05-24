package com.scoova.navlayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSmootherTest {

    @Test
    fun update_firstFix_returnsMeasurementUnchanged() {
        val s = ScoovaLocationSmoother()
        val out = s.update(tsMs = 1_000, lat = 30.0444, lon = 31.2357, accuracyM = 5f)
        assertEquals(30.0444, out.lat, 1e-9)
        assertEquals(31.2357, out.lon, 1e-9)
        // No velocity history yet → speed/bearing zero.
        assertEquals(0f, out.speedMps, 1e-3f)
    }

    @Test
    fun update_stationaryNoiseInput_smoothesPosition() {
        val s = ScoovaLocationSmoother()
        // Anchor.
        s.update(1_000, 30.0, 31.0, 5f)
        // Inject ±3m jitter (in lat/lon ≈ ±0.00003°) at 1 Hz for 10 ticks.
        var lastDriftFromAnchor = 0.0
        for (i in 1..10) {
            val noisy = s.update(
                tsMs = 1_000L + i * 1_000L,
                lat = 30.0 + (if (i % 2 == 0) 0.00003 else -0.00003),
                lon = 31.0 + (if (i % 3 == 0) 0.00003 else -0.00003),
                accuracyM = 5f,
            )
            lastDriftFromAnchor = GeoMath.haversineMeters(30.0, 31.0, noisy.lat, noisy.lon)
        }
        // Smoothed output should remain within a few metres of the
        // true anchor — strictly less than the input jitter envelope.
        assertTrue(
            "stationary smoothed should stay close to anchor, drifted $lastDriftFromAnchor m",
            lastDriftFromAnchor < 4.0,
        )
    }

    @Test
    fun update_constantVelocityMotion_recoversBearing() {
        val s = ScoovaLocationSmoother()
        // Walk east at ~1 m/s for 10 ticks (1 sec apart).
        // 1 m east at lat=30° ≈ 1 / (111_320 * cos(30°)) ≈ 1.038e-5 deg lon
        val degLonPerMeter = 1.0 / (111_320.0 * Math.cos(Math.toRadians(30.0)))
        var ts = 1_000L
        for (i in 0..15) {
            s.update(ts, 30.0, 31.0 + i * degLonPerMeter, 3f)
            ts += 1_000
        }
        val out = s.update(ts, 30.0, 31.0 + 16 * degLonPerMeter, 3f)
        // East = 90°. Allow ±15° slack for Kalman convergence latency.
        assertTrue(
            "expected ~90° bearing, got ${out.bearingDeg}",
            out.bearingDeg in 75f..105f,
        )
        // Speed: we walked at 1 m/s.
        assertTrue(
            "expected ~1 m/s, got ${out.speedMps}",
            out.speedMps in 0.5f..1.5f,
        )
    }

    @Test
    fun update_outlierFix_isRejected() {
        val s = ScoovaLocationSmoother()
        // Build up a stable history at (30, 31).
        for (i in 0..10) {
            s.update(1_000L + i * 1_000L, 30.0, 31.0, 5f)
        }
        // Inject a single wildly-off fix (~10 km away).
        val outlier = s.update(12_000L, 30.1, 31.1, 5f)
        // Smoother should NOT have jumped — should still be near origin.
        val drift = GeoMath.haversineMeters(30.0, 31.0, outlier.lat, outlier.lon)
        assertTrue(
            "outlier should be rejected, drifted $drift m",
            drift < 200.0,
        )
    }

    @Test
    fun reset_clearsState() {
        val s = ScoovaLocationSmoother()
        s.update(1_000, 30.0, 31.0, 5f)
        s.update(2_000, 30.01, 31.0, 5f)
        s.reset()
        // After reset the next update should behave like a first fix.
        val out = s.update(3_000, 40.0, 50.0, 5f)
        assertEquals(40.0, out.lat, 1e-9)
        assertEquals(50.0, out.lon, 1e-9)
        assertEquals(0f, out.speedMps, 1e-3f)
    }

    @Test
    fun update_longGap_resetsBootstrap() {
        val s = ScoovaLocationSmoother(resetAfterMs = 5_000)
        s.update(1_000, 30.0, 31.0, 5f)
        s.update(2_000, 30.001, 31.0, 5f)
        // 60 second gap → reset.
        val out = s.update(62_000, 31.0, 32.0, 5f)
        // After a reset, the smoother snaps to the new fix exactly.
        assertEquals(31.0, out.lat, 1e-9)
        assertEquals(32.0, out.lon, 1e-9)
    }

    @Test
    fun update_zeroAccuracy_doesNotDivideByZero() {
        val s = ScoovaLocationSmoother()
        // The filter has a 1 m floor — should not crash or NaN.
        val out = s.update(1_000, 30.0, 31.0, 0f)
        assertNotEquals(Float.NaN, out.speedMps)
        assertNotEquals(Float.NaN, out.bearingDeg)
        assertTrue("output accuracy should be ≥ 1 m floor", out.accuracyM >= 1f)
    }
}
