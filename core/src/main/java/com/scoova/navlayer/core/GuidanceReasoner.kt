package com.scoova.navlayer.core

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Port of iOS `GuidanceReasoner.swift`. One coherent answer the
 * reasoner produces per GPS tick. Every other system reads from
 * this struct: the cue speaker reads [upcomingDecision] +
 * [ambiguityFlags], the off-route detector reads [alignment], the
 * reroute trigger reads [segmentOnGraph] against the route's
 * corridor, the heading puck reads [alignment.courseMatchesSegment].
 *
 * The reasoner runs on every tick — so this struct is the unit of
 * reasoning. If it's wrong, downstream is wrong. If it's right,
 * downstream becomes mechanical.
 */
public data class LiveGuidanceState(
    /** Real map-matched snap to the nearest road in the neighbour
     *  graph. Null when no neighbour graph was shipped OR when no way
     *  in the graph is within `maxLateralM`. The latter is the
     *  **strongest** off-route signal we have. */
    val snap: Localizer.Snap?,
    /** `true` when [snap.wayId] is one of the route's expected ways. */
    val isOnRouteWay: Boolean,
    /** Road-graph segment the rider is currently on. Null when the
     *  corridor isn't available OR the rider's projection lands
     *  outside every fingerprint. */
    val segmentOnGraph: GraphFingerprint?,
    /** Index of the upcoming maneuver. Matches
     *  [ProgressEvent.upcomingManeuverIndex]. */
    val segmentOnRoute: Int,
    val alignment: Alignment,
    /** What the rider is approaching. Null when the route is empty. */
    val upcomingDecision: UpcomingDecision?,
    /** Open-vocabulary flags from the corridor's per-maneuver block. */
    val ambiguityFlags: List<String>,
) {
    public data class Alignment(
        /** Rider is on the route polyline within an acceptable lateral
         *  band. False means lateral distance has crossed the
         *  off-route threshold AND no fingerprint match. */
        val onRoute: Boolean,
        /** Perpendicular distance from rider to the route polyline (m). */
        val lateralM: Double,
        /** True when the rider's GPS course matches the expected
         *  direction-of-travel for the segment they're on. Null when
         *  GPS course is unavailable. */
        val courseMatchesSegment: Boolean?,
    )

    public data class UpcomingDecision(
        val type: ManeuverType,
        /** Along-route distance from the rider to the maneuver (m). */
        val distance: Double,
        /** 1-based ordinal among same-side turns on the approach
         *  segment. Null when the corridor wasn't available or the
         *  maneuver has no side. */
        val ordinal: Int?,
        /** Total same-side turns the rider passes on the approach. */
        val totalSameSideTurns: Int?,
    )
}

/**
 * Stateless reasoning function — given the latest progress event, the
 * route's maneuver list, and the route's corridor (when available),
 * produces the live state every other system reads from.
 *
 * Lives outside [ScoovaNavLayer] so it can be unit-tested in
 * isolation: build a synthetic [ProgressEvent] + [Corridor] +
 * `List<ManeuverEvent>`, call [reason], assert on the result.
 *
 * Re-uses [projectOntoPolyline] from `GuidanceMonitor.kt` for the
 * polyline projection — single source of truth for "where on the
 * route is the rider."
 */
public object GuidanceReasoner {

    public fun reason(
        p: ProgressEvent,
        route: List<ManeuverEvent>,
        corridor: Corridor?,
        shape: List<DoubleArray>,
    ): LiveGuidanceState {
        // ── Real map-matching ────────────────────────────────────────
        // The neighbour-graph snap is the navigator's "which road are
        // you on" answer. When present + matched, it's authoritative
        // for on-route / off-route / wrong-way. When the graph is
        // empty (legacy server) we fall back to the polyline
        // projection below.
        val snap: Localizer.Snap? = if (corridor != null && corridor.neighbourGraph.isNotEmpty()) {
            Localizer.snap(
                lat = p.latitude,
                lon = p.longitude,
                courseDeg = p.bearingDeg?.toDouble(),
                speedMps = p.speedMps?.toDouble(),
                graph = corridor.neighbourGraph,
            )
        } else null
        val isOnRouteWay = if (corridor != null && snap != null) {
            Localizer.routeWayIds(corridor).contains(snap.wayId)
        } else false

        // ── Polyline projection (fallback + secondary signal) ────────
        val proj = projectOntoPolyline(p.latitude, p.longitude, shape)
        val lateralM = proj?.lateralM ?: Double.MAX_VALUE
        val segmentBearing = proj?.segmentBearingDeg

        // ── Graph fingerprint match ──────────────────────────────────
        // Same lateral gate as iOS: fingerprint match alone always
        // succeeds when the polyline is the route, so require the
        // rider to be within a road-width of the polyline. 25 m is a
        // typical urban-road half-width including sidewalk.
        val graphMatchMaxLateralM = 25.0
        val nearestVertex = nearestPolylineVertex(p.latitude, p.longitude, shape)
        val segmentOnGraph: GraphFingerprint? = if (corridor != null && lateralM <= graphMatchMaxLateralM) {
            corridor.graphFingerprints.firstOrNull {
                nearestVertex in it.polylineFrom..it.polylineTo
            }
        } else null

        // ── Course-vs-segment direction ──────────────────────────────
        val courseMatchesSegment: Boolean? = run {
            val bearing = p.bearingDeg ?: return@run null
            val speed = p.speedMps ?: return@run null
            if (speed < 2f) return@run null
            val segB = segmentBearing ?: return@run null
            angleDeltaAbs(bearing, segB) < 60f
        }

        // ── On-route decision ────────────────────────────────────────
        val onRoute = if (snap != null) isOnRouteWay else lateralM < 60.0

        // ── Upcoming decision ────────────────────────────────────────
        val upcomingIdx = if (route.isEmpty()) 0
            else max(0, min(p.upcomingManeuverIndex, route.size - 1))
        val upcoming: LiveGuidanceState.UpcomingDecision? = if (route.isEmpty()) null else {
            val m = route[upcomingIdx]
            val block = corridor?.maneuvers?.firstOrNull { it.index == upcomingIdx }
            LiveGuidanceState.UpcomingDecision(
                type = m.type,
                distance = p.metersToUpcomingManeuver,
                ordinal = block?.ordinal?.indexAmongSameSideTurns,
                totalSameSideTurns = block?.ordinal?.totalSameSideTurns,
            )
        }

        val flags = corridor?.maneuvers
            ?.firstOrNull { it.index == upcomingIdx }
            ?.ambiguityFlags
            ?: emptyList()

        return LiveGuidanceState(
            snap = snap,
            isOnRouteWay = isOnRouteWay,
            segmentOnGraph = segmentOnGraph,
            segmentOnRoute = upcomingIdx,
            alignment = LiveGuidanceState.Alignment(
                onRoute = onRoute,
                lateralM = lateralM,
                courseMatchesSegment = courseMatchesSegment,
            ),
            upcomingDecision = upcoming,
            ambiguityFlags = flags,
        )
    }

    /** Nearest-vertex index — used to look up the active fingerprint.
     *  Cheaper than a full segment projection because we only need
     *  "which entry contains the rider," not the perpendicular foot. */
    private fun nearestPolylineVertex(
        lat: Double, lon: Double, shape: List<DoubleArray>,
    ): Int {
        if (shape.isEmpty()) return 0
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(lat * Math.PI / 180.0)
        val rx = lon * mPerDegLon
        val ry = lat * mPerDegLat
        var bestIdx = 0
        var bestDistSq = Double.MAX_VALUE
        for (i in shape.indices) {
            val pt = shape[i]
            val dx = pt[1] * mPerDegLon - rx
            val dy = pt[0] * mPerDegLat - ry
            val d = dx * dx + dy * dy
            if (d < bestDistSq) {
                bestDistSq = d
                bestIdx = i
            }
        }
        return bestIdx
    }
}
