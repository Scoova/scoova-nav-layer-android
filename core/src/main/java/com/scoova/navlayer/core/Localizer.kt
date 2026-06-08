package com.scoova.navlayer.core

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Port of iOS `Localizer.swift`. The "guy holding the map" — snaps a
 * GPS fix to the nearest road in the route's neighbour graph and tells
 * the reasoner WHICH road the rider is physically on, not "near the
 * polyline" but the actual OSM way they're traversing. This is the
 * difference between a polyline tripwire and a real navigator.
 *
 * Stateless by design: every fix is independent. State lives in
 * [GuidanceReasoner], which sees these snaps over time.
 */
public object Localizer {

    /** Result of snapping a GPS fix to the neighbour graph. */
    public data class Snap(
        /** OSM way the rider is closest to. */
        val wayId: Long,
        /** Way name (may be empty). */
        val name: String,
        /** Distance from the rider to the snap point (m). */
        val lateralM: Double,
        /** Bearing of the snapped segment in the way's `forward`
         *  direction. 0..360, where 0 = north. */
        val segmentBearingDeg: Double,
        /** `true` when the rider's GPS course aligns with the segment's
         *  `forward` direction within ~60°. Null when no course
         *  supplied (stationary / first fix). */
        val courseMatchesForward: Boolean?,
        /** The way's `oneway` flag — propagated for the wrong-way
         *  detector. On a oneway, course-against-forward is an
         *  unambiguous wrong-way fire. */
        val oneway: Boolean,
        /** Snap point itself. Lets the host draw a "snapped position"
         *  puck or compute follow-on math. */
        val snappedLat: Double,
        val snappedLon: Double,
    )

    /**
     * Snap a GPS fix against the neighbour graph. Returns null when
     * the graph is empty (legacy server) OR no way is within
     * `maxLateralM`. The reasoner reads null as "the rider is far
     * from any road in the corridor" — a strong signal they have
     * left the route's neighbourhood entirely.
     *
     * O(N·M) where N = number of ways, M = total segments. For a
     * typical urban route (100 ways, 1500 segments) this is
     * ~150k operations per tick — sub-millisecond on real hardware.
     */
    public fun snap(
        lat: Double,
        lon: Double,
        courseDeg: Double?,
        speedMps: Double?,
        graph: List<NeighbourWay>,
        maxLateralM: Double = 30.0,
    ): Snap? {
        if (graph.isEmpty()) return null
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(lat * Math.PI / 180.0)
        val rx = lon * mPerDegLon
        val ry = lat * mPerDegLat

        // Course is trustworthy at speed only. Below 2 m/s GPS bearing
        // is mostly positional noise.
        val trustCourse = courseDeg != null && (speedMps ?: 0.0) >= 2.0

        var bestScore = Double.MAX_VALUE
        var bestWay: NeighbourWay? = null
        var bestSnapX = 0.0
        var bestSnapY = 0.0
        var bestSegBearingDeg = 0.0
        var bestForward = true
        var bestLateralM = Double.MAX_VALUE

        for (way in graph) {
            for (seg in way.segments) {
                if (seg.shape.size < 2) continue
                for (i in 0 until seg.shape.size - 1) {
                    val a = seg.shape[i]
                    val b = seg.shape[i + 1]
                    val ax = a[1] * mPerDegLon
                    val ay = a[0] * mPerDegLat
                    val bx = b[1] * mPerDegLon
                    val by = b[0] * mPerDegLat
                    val abx = bx - ax
                    val aby = by - ay
                    val ab2 = abx * abx + aby * aby
                    if (ab2 < 1e-9) continue
                    val tRaw = ((rx - ax) * abx + (ry - ay) * aby) / ab2
                    val t = tRaw.coerceIn(0.0, 1.0)
                    val cx = ax + abx * t
                    val cy = ay + aby * t
                    val dx = rx - cx
                    val dy = ry - cy
                    val lateralM = sqrt(dx * dx + dy * dy)

                    val segBearing = GeoMath.bearingDeg(a[0], a[1], b[0], b[1])
                    var score = lateralM
                    if (trustCourse && courseDeg != null) {
                        val delta = angleDeltaAbs(courseDeg.toFloat(), segBearing.toFloat())
                        val bidiDelta = minOf(delta, 180f - delta)
                        val pen = when {
                            bidiDelta <= 30f -> 0.0
                            bidiDelta >= 90f -> 25.0
                            else -> (bidiDelta - 30f).toDouble() / 60.0 * 25.0
                        }
                        score += pen
                    }

                    if (score < bestScore) {
                        bestScore = score
                        bestWay = way
                        bestSnapX = cx
                        bestSnapY = cy
                        bestSegBearingDeg = segBearing
                        bestForward = seg.forward
                        bestLateralM = lateralM
                    }
                }
            }
        }

        val way = bestWay ?: return null
        if (bestLateralM > maxLateralM) return null

        val courseMatchesForward: Boolean? = if (
            courseDeg != null && (speedMps ?: 0.0) >= 2.0
        ) {
            val segDir = if (bestForward) bestSegBearingDeg
                else (bestSegBearingDeg + 180.0) % 360.0
            angleDeltaAbs(courseDeg.toFloat(), segDir.toFloat()) < 60f
        } else null

        return Snap(
            wayId = way.wayId,
            name = way.name,
            lateralM = bestLateralM,
            segmentBearingDeg = bestSegBearingDeg,
            courseMatchesForward = courseMatchesForward,
            oneway = way.oneway,
            snappedLat = bestSnapY / mPerDegLat,
            snappedLon = bestSnapX / mPerDegLon,
        )
    }

    /**
     * The set of OSM ways the ROUTE itself rides — derived from the
     * corridor's graph fingerprints. The reasoner uses this to ask
     * "is the rider's snapped way one of the route's ways?" — that
     * IS the on-route signal.
     */
    public fun routeWayIds(corridor: Corridor): Set<Long> =
        corridor.graphFingerprints.map { it.wayId }.toSet()
}
