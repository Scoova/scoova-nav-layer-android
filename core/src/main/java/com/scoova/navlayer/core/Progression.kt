package com.scoova.navlayer.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Per-costing distance ladder. The cue fires when we cross one of these
 * thresholds for a maneuver — far, mid, near. Picked so a runner doesn't
 * get an "in 800 m turn left" cue and a driver doesn't get a 50 m one.
 */
public object Thresholds {
    private val PER_PROFILE: Map<String, IntArray> = mapOf(
        "pedestrian"    to intArrayOf(50, 25, 10, 3),
        "bicycle"       to intArrayOf(200, 100, 50, 15),
        "scooter"       to intArrayOf(300, 150, 75, 20),
        "motor_scooter" to intArrayOf(600, 300, 150, 30),
        "motorcycle"    to intArrayOf(800, 400, 200, 40),
        "auto"          to intArrayOf(800, 400, 200, 50),
        "truck"         to intArrayOf(800, 400, 200, 50),
    )
    public fun forProfile(profile: String): IntArray =
        (PER_PROFILE[profile] ?: PER_PROFILE.getValue("auto")).sortedArray()
}

/**
 * Progress tracker that fires at most once per (maneuverIndex, threshold).
 *
 * Anti-spurious-fire guards:
 *   • Crossing only — fires when prevDist > T and curDist ≤ T (so a static
 *     reading never re-triggers).
 *   • Max-seen — only fires threshold T if the maneuver was ever observed
 *     at distance > (T + 20m). Prevents "Turn now" the moment GPS arrives
 *     when you're already 4m from the next turn.
 */
public class ProgressTracker(private val thresholdsMeters: IntArray) {

    public data class Snapshot(
        val maneuverIndex: Int,
        val metersToManeuver: Double,
        val firedThresholdM: Int,
    )

    private val firedFor = mutableMapOf<Int, MutableSet<Int>>()
    private val prevDistFor = mutableMapOf<Int, Double>()
    private val maxSeenFor = mutableMapOf<Int, Double>()

    /**
     * Tick the tracker. [overrideThresholds] lets the caller swap in
     * per-maneuver thresholds (server-provided farMeters/midMeters/
     * nearMeters, computed from the road's speed limit × seconds-out
     * target). When null, the tracker uses the profile defaults the
     * ProgressTracker was constructed with.
     *
     * Mixing profile defaults with per-maneuver overrides across
     * different maneuvers is safe — each maneuver tracks its own
     * fired-set keyed by index, so a switch between override and
     * default between maneuvers doesn't bleed state. Inside a single
     * maneuver the caller should pass a consistent threshold array
     * for the duration; if the server doesn't supply overrides, pass
     * null and the tracker uses defaults the whole time.
     */
    @JvmOverloads
    public fun update(
        maneuverIndex: Int,
        metersToManeuver: Double,
        overrideThresholds: IntArray? = null,
    ): Snapshot {
        val active = overrideThresholds?.takeIf { it.isNotEmpty() } ?: thresholdsMeters
        val prev = prevDistFor[maneuverIndex]
        val firedSet = firedFor.getOrPut(maneuverIndex) { mutableSetOf() }

        val newMax = if (metersToManeuver.isFinite())
            maxOf(maxSeenFor[maneuverIndex] ?: metersToManeuver, metersToManeuver)
        else maxSeenFor[maneuverIndex] ?: 0.0
        maxSeenFor[maneuverIndex] = newMax

        val candidate = if (prev != null && prev.isFinite() && metersToManeuver.isFinite()) {
            active.firstOrNull { t ->
                prev > t && metersToManeuver <= t && t !in firedSet && newMax >= t + 20
            }
        } else null
        if (candidate != null) firedSet.add(candidate)
        prevDistFor[maneuverIndex] = metersToManeuver

        return Snapshot(maneuverIndex, metersToManeuver, candidate ?: -1)
    }
}

public object GeoMath {
    private const val R_EARTH = 6371000.0
    public fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return R_EARTH * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Forward bearing from (lat1, lon1) → (lat2, lon2), degrees, 0..360.
     * 0 = north, 90 = east. Standard great-circle initial bearing formula.
     */
    public fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val φ1 = Math.toRadians(lat1)
        val φ2 = Math.toRadians(lat2)
        val Δλ = Math.toRadians(lon2 - lon1)
        val y = sin(Δλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
        var θ = Math.toDegrees(atan2(y, x))
        if (θ < 0) θ += 360.0
        return θ
    }

    /**
     * Shortest distance, in metres, from point P (lat, lon) to a
     * polyline given as a list of [lat, lon] pairs. Returns
     * [Double.POSITIVE_INFINITY] for an empty or one-point polyline.
     *
     * Uses an equirectangular projection (scales longitude by
     * cos(reference-latitude)) so the segment math is plain 2D
     * Euclidean instead of great-circle. Error is negligible for the
     * tens-of-metres scale we care about (off-route detection,
     * nearest-route-snap). The reference latitude is the query point's
     * own latitude — a polyline that spans many degrees of latitude
     * would lose accuracy at the far end, but in nav contexts the
     * relevant segment is always near the rider so this is fine.
     */
    /**
     * Project (lat, lon) onto the polyline and return the cumulative
     * distance from the polyline's start to the foot of perpendicular.
     *
     * Used by the routing adapter to decide "which maneuver is the
     * rider on the way to". Without this — using nearest-vertex
     * snapping — a rider halfway between two vertices gets classified
     * as already at the next vertex; if a maneuver is anchored there
     * the banner jumps to the FOLLOWING maneuver while the rider
     * hasn't physically reached the current one yet. Projecting onto
     * the line gives a continuous, monotonic progress measure.
     *
     * Returns [Double.POSITIVE_INFINITY] for a degenerate polyline.
     */
    public fun progressAlongPolyline(
        lat: Double,
        lon: Double,
        polyline: List<DoubleArray>,
    ): Double {
        if (polyline.size < 2) return Double.POSITIVE_INFINITY
        var bestSeg = 0
        var bestT = 0.0
        var bestLatProj = lat
        var bestLonProj = lon
        var bestDistM = Double.MAX_VALUE
        for (i in 0 until polyline.size - 1) {
            val a = polyline[i]; val b = polyline[i + 1]
            val ax = a[1]; val ay = a[0]
            val bx = b[1]; val by = b[0]
            val px = lon;   val py = lat
            val abx = bx - ax; val aby = by - ay
            val apx = px - ax; val apy = py - ay
            val ab2 = abx * abx + aby * aby
            val t = if (ab2 < 1e-15) 0.0
                else ((apx * abx + apy * aby) / ab2).coerceIn(0.0, 1.0)
            val cx = ax + abx * t
            val cy = ay + aby * t
            val d = haversineMeters(py, px, cy, cx)
            if (d < bestDistM) {
                bestDistM = d; bestSeg = i; bestT = t
                bestLatProj = cy; bestLonProj = cx
            }
        }
        var progress = 0.0
        for (i in 0 until bestSeg) {
            progress += haversineMeters(
                polyline[i][0], polyline[i][1],
                polyline[i + 1][0], polyline[i + 1][1],
            )
        }
        progress += haversineMeters(
            polyline[bestSeg][0], polyline[bestSeg][1],
            bestLatProj, bestLonProj,
        )
        return progress
    }

    /**
     * Cumulative distance from `polyline[0]` to `polyline[index]`
     * (inclusive). Cheap with precomputation but the routing adapter
     * caches its own table so this is mostly a fallback / test helper.
     */
    public fun cumulativeDistanceMeters(polyline: List<DoubleArray>, index: Int): Double {
        if (index <= 0 || polyline.size < 2) return 0.0
        var sum = 0.0
        val end = index.coerceAtMost(polyline.lastIndex)
        for (i in 0 until end) {
            sum += haversineMeters(
                polyline[i][0], polyline[i][1],
                polyline[i + 1][0], polyline[i + 1][1],
            )
        }
        return sum
    }

    public fun distanceToPolylineMeters(
        lat: Double,
        lon: Double,
        polyline: List<DoubleArray>,
    ): Double {
        if (polyline.size < 2) return Double.POSITIVE_INFINITY
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat))
        val px = lon * mPerDegLon
        val py = lat * mPerDegLat
        var best = Double.POSITIVE_INFINITY
        for (i in 0 until polyline.size - 1) {
            val a = polyline[i]; val b = polyline[i + 1]
            val ax = a[1] * mPerDegLon; val ay = a[0] * mPerDegLat
            val bx = b[1] * mPerDegLon; val by = b[0] * mPerDegLat
            val dx = bx - ax; val dy = by - ay
            val seg2 = dx * dx + dy * dy
            // Degenerate segment (consecutive identical points) — fall
            // back to distance to the endpoint.
            val t = if (seg2 == 0.0) 0.0 else
                ((px - ax) * dx + (py - ay) * dy) / seg2
            val tc = t.coerceIn(0.0, 1.0)
            val cx = ax + tc * dx; val cy = ay + tc * dy
            val ddx = px - cx; val ddy = py - cy
            val d2 = ddx * ddx + ddy * ddy
            if (d2 < best) best = d2
        }
        return sqrt(best)
    }
}
