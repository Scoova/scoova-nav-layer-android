package com.scoova.navlayer.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Port of iOS `DeadReckoner.swift`. Dead-reckons the rider's position
 * forward when GPS drops out (tunnels, underpasses, urban canyons).
 * The navigator is most useful precisely in the places GPS fails — a
 * tunnel is exactly where a rider needs the next cue to fire on time.
 * Without dead-reckoning the cursor freezes the moment the fix goes
 * silent and every downstream cue mis-fires.
 *
 * Math is intentionally minimal: take the last known
 * `(lat, lon, courseDeg, speedMps)` and integrate forward by elapsed
 * wall-clock time. No EKF, no Kalman — constant-velocity along the
 * last-known heading. Good for ~30 s of tunnel; errors compound past
 * that. The reasoner re-anchors on the next real GPS fix.
 */
public class DeadReckoner {

    private data class Anchor(
        val lat: Double,
        val lon: Double,
        val courseDeg: Double,
        val speedMps: Double,
        val tsMs: Long,
    )

    private var anchor: Anchor? = null
    private val maxExtrapolationMs: Long = 30_000

    /** Anchor on a real GPS fix. The reckoner overwrites whatever it
     *  had — the freshest observation always wins. Discards stationary
     *  fixes (no information about future motion). */
    public fun observe(
        lat: Double, lon: Double,
        courseDeg: Double?, speedMps: Double?, tsMs: Long,
    ) {
        if (courseDeg == null || speedMps == null || speedMps < 1.0) {
            anchor = null
            return
        }
        anchor = Anchor(lat, lon, courseDeg, speedMps, tsMs)
    }

    /** Returns the dead-reckoned position at [nowMs]. Null when no
     *  anchor exists, when the anchor is too old, or when speed was
     *  too low to extrapolate from. */
    public fun project(nowMs: Long): Pair<Double, Double>? {
        val a = anchor ?: return null
        val dtMs = nowMs - a.tsMs
        if (dtMs < 0 || dtMs > maxExtrapolationMs) return null
        val distanceM = a.speedMps * dtMs / 1000.0
        val headingRad = a.courseDeg * PI / 180.0
        val dLatM = distanceM * cos(headingRad)
        val dLonM = distanceM * sin(headingRad)
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(a.lat * PI / 180.0)
        val newLat = a.lat + dLatM / mPerDegLat
        val newLon = a.lon + dLonM / mPerDegLon
        return newLat to newLon
    }

    /** Clear the anchor — call when starting / stopping nav. */
    public fun reset() { anchor = null }
}
