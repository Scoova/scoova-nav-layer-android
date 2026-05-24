package com.scoova.navlayer.core

import kotlin.math.abs

/**
 * Continuous closed-loop guidance.
 *
 * Ticked from [ScoovaNavLayer.onProgress] (GPS-rate, ~1–4 Hz) and from
 * [ScoovaNavLayer.onMotion] (sensor-rate, ~50 Hz — only the compass
 * heading is fed in to keep heading-mismatch detection live).
 *
 * Inputs:
 *   * GPS position + speed (from [ProgressEvent])
 *   * Smoothed compass heading (from [MotionFusion] via NavLayer)
 *   * Route polyline (set once at route-load via [setRoute])
 *
 * Outputs: a `List<GuidanceEvent>` per tick describing what state the
 * rider just transitioned into. NavLayer maps each event to a phrase
 * from `trip.scoova.state.*` and plays it via the voice engine. Phrases
 * live on the server — this class only emits semantic events.
 *
 * State machine (event → trigger → phrase key):
 *
 *  | Event           | Trigger                                            | Phrase            |
 *  |-----------------|----------------------------------------------------|-------------------|
 *  | KeepGoing       | 30 s of silence on a straight stretch              | keepGoing         |
 *  | DriftLeft       | 10–30 m right of polyline for 3 s (lean left)      | driftLeft         |
 *  | DriftRight      | 10–30 m left of polyline for 3 s (lean right)      | driftRight        |
 *  | OffRoute        | > 30 m off polyline for 5 s                        | wrongWay          |
 *  | WrongWayHeading | heading vs polyline bearing > 45° for 3 s @ rest   | wrongWay          |
 *  | SlowDown        | time-to-maneuver < 3 s at current speed            | slowDown          |
 *  | AlmostThere     | metersRemaining in 50..150                         | almostThere*      |
 *
 * Each event has its own cooldown (15 s default) so we don't spam the
 * rider with the same phrase. Cross-event isn't deduped — a SlowDown
 * can fire right after a KeepGoing if the situations warrant.
 */
public class GuidanceMonitor {

    // ── Tunables ──────────────────────────────────────────────────────
    private val silenceThresholdMs: Long = 30_000L
    // Keep-going also fires when the rider has covered this distance
    // since the last cue, even if the clock hasn't tripped — at speed
    // the rider hears the pulse keep pace with the ground, not a fixed
    // clock that leaves a long gap. Mirrors the iOS distance arm.
    private val silenceDistanceM: Double = 350.0
    /** Per-costing lateral thresholds. Pedestrian + cyclist sidewalk
     *  offsets routinely sit 10–20 m off the routed centerline (the
     *  sidewalk parallels the road), so a car-tight 30 m off-route
     *  threshold false-fires constantly on foot. The looser numbers
     *  in [thresholdsFor] are still tight enough that genuinely-wrong
     *  streets — a rider one block over — trigger correctly. */
    private var costing: String = "auto"
    private val driftMinM: Double get() = thresholdsFor(costing).driftMin
    private val driftMaxM: Double get() = thresholdsFor(costing).driftMax
    private val offRouteThresholdM: Double get() = thresholdsFor(costing).offRoute
    /** Parallel-walking suppressor. When the rider's GPS bearing is
     *  within this many degrees of the route segment's bearing AND
     *  they're moving above the floor speed, drift / off-route events
     *  are suppressed — they're on a sidewalk that parallels the
     *  route, not actually off route. Without this, every NYC
     *  pedestrian on the sidewalk hears "Wrong way, please turn
     *  around" every 5 seconds. */
    private val parallelHeadingTolDeg: Float = 35f
    private val parallelMinSpeedMps: Float = 0.5f
    private val driftDurationMs: Long = 3_000L
    private val offRouteDurationMs: Long = 5_000L

    private data class LateralThresholds(
        val driftMin: Double, val driftMax: Double, val offRoute: Double,
    )

    private fun thresholdsFor(costing: String): LateralThresholds = when (costing) {
        "pedestrian"        -> LateralThresholds(20.0, 60.0, 60.0)
        "bicycle", "scooter" -> LateralThresholds(15.0, 40.0, 50.0)
        "motorcycle"        -> LateralThresholds(12.0, 35.0, 40.0)
        else                -> LateralThresholds(10.0, 30.0, 30.0)  // auto
    }
    private val headingMismatchDeg: Float = 45f
    private val headingMismatchDurationMs: Long = 3_000L
    private val maxSpeedForHeadingCheckMps: Float = 2.0f  // only at standstill
    private val slowDownTimeToManeuverSec: Double = 3.0
    // "Slow down, turn coming" is a genuine-overspeed safety cue, not a
    // per-turn nag. 13 m/s ≈ 47 km/h — above any persona's normal
    // cruising pace; a rider at normal speed gets the turn cue alone.
    // Was 5.0 m/s (18 km/h) which fired on every bike turn — too noisy.
    private val slowDownMinSpeedMps: Float = 13.0f
    private val sameEventCooldownMs: Long = 15_000L
    private val almostThereWindowMeters = 50..150

    // ── Route + sensor state ──────────────────────────────────────────
    private var shape: List<DoubleArray> = emptyList()
    private var compassHeadingDeg: Float? = null
    /**
     * Whether the phone is currently stowed in a pocket / bag. When
     * true, [compassHeadingDeg] is the orientation of the POCKET, not
     * the rider's facing — the rotation-vector sensor reports wherever
     * the fabric happens to hold the phone. The standstill heading-
     * mismatch check ("Wrong way — turn around") MUST be suppressed
     * in this state or it false-fires every time a pocketed rider
     * stops at a light. Set via [onPocketState].
     */
    private var phoneInPocket: Boolean = false

    // ── Timers & dedup ────────────────────────────────────────────────
    private var lastSpokeAt: Long = 0
    private val lastEventAt: MutableMap<EventKind, Long> = mutableMapOf()
    private var driftStartedAt: Long = 0
    private var driftDirection: DriftDir? = null
    private var offRouteStartedAt: Long = 0
    private var headingMismatchStartedAt: Long = 0
    /** Latest [Progress.metersRemaining] seen, and its value when a cue
     *  last spoke. The keep-going distance trigger compares these to
     *  measure ground covered between cues — the speed-aware arm of
     *  the silence heartbeat, mirroring iOS. */
    private var latestMetersRemaining: Int = 0
    private var metersRemainingAtSpoke: Int = 0

    /** Adapter calls this once per route load. */
    public fun setRoute(routeShape: List<DoubleArray>) {
        this.shape = routeShape
        reset()
    }

    /** Adapter sets the routing profile (pedestrian / bicycle / scooter
     *  / motorcycle / auto) so the drift + off-route thresholds can
     *  scale to mode. Pedestrian on a sidewalk lives 10–20 m off the
     *  routed centerline — without this, every NYC walk false-fires
     *  "wrong way, turn around" the moment the rider steps onto the
     *  kerb. Default `auto` matches the historic tight thresholds. */
    public fun setCosting(c: String) {
        this.costing = c
    }

    /** Reset timers (e.g. on re-route). Doesn't clear the polyline. */
    public fun reset() {
        lastSpokeAt = 0
        lastEventAt.clear()
        driftStartedAt = 0
        driftDirection = null
        offRouteStartedAt = 0
        headingMismatchStartedAt = 0
        latestMetersRemaining = 0
        metersRemainingAtSpoke = 0
    }

    /** Called by NavLayer every time it actually speaks a cue. Resets
     *  the silence timer so we don't fire keep-going right after a
     *  normal threshold cue. */
    public fun markSpoke(nowMs: Long = System.currentTimeMillis()) {
        lastSpokeAt = nowMs
        metersRemainingAtSpoke = latestMetersRemaining
    }

    /** Called from [MotionFusion] output via NavLayer. Heading is live
     *  for the next [onProgress] tick. */
    public fun onCompassHeading(degrees: Float) {
        compassHeadingDeg = degrees
    }

    /** Called from NavLayer with the [PocketDetector] state. When the
     *  phone is pocketed, the compass heading is the pocket's
     *  orientation, not the rider's — the standstill wrong-way check
     *  is suppressed for the duration. */
    public fun onPocketState(pocketed: Boolean) {
        phoneInPocket = pocketed
        if (pocketed) headingMismatchStartedAt = 0
    }

    /**
     * Called from `ScoovaNavLayer.onProgress`. Returns the events that
     * fired this tick — caller plays the matching phrase and calls
     * [markSpoke] when it does. Empty list = nothing to say.
     */
    public fun onProgress(
        p: ProgressEvent,
        nowMs: Long = System.currentTimeMillis(),
    ): List<GuidanceEvent> {
        if (shape.size < 2) return emptyList()
        val proj = projectOntoPolyline(p.latitude, p.longitude, shape)
            ?: return emptyList()

        // Track for the speed-aware keep-going arm below.
        latestMetersRemaining = p.metersRemaining

        val events = mutableListOf<GuidanceEvent>()

        // ── Parallel-walking suppression ─────────────────────────────
        // If the rider is moving and their GPS heading aligns with the
        // route's local bearing, they're walking parallel to the
        // route — on a sidewalk, in a bike lane, or just hugging one
        // side of a wide path. That is NOT off-route, no matter how
        // far they look from the polyline. Skip the drift and off-
        // route checks for this tick; the underlying lateral-offset
        // sample is real but it does not describe a navigation error.
        //
        // Falls through to the standard checks at standstill (speed
        // is unreliable, can't trust the direction) so a parked rider
        // who genuinely strayed still gets a "wrong way" eventually.
        val parallelBearing = p.bearingDeg
        val parallelSpeed = p.speedMps
        val isParallel: Boolean = if (parallelBearing != null && parallelSpeed != null &&
            parallelSpeed >= parallelMinSpeedMps
        ) {
            val delta = angleDeltaAbs(parallelBearing, proj.segmentBearingDeg)
            delta < parallelHeadingTolDeg
        } else false

        // ── Off-route: highest priority ──────────────────────────────
        if (!isParallel && proj.lateralM > offRouteThresholdM) {
            if (offRouteStartedAt == 0L) {
                offRouteStartedAt = nowMs
            } else if (nowMs - offRouteStartedAt > offRouteDurationMs &&
                shouldFire(EventKind.OffRoute, nowMs)
            ) {
                events += GuidanceEvent.OffRoute(proj.lateralM)
                offRouteStartedAt = nowMs
            }
            // Also clear any drift state — off-route supersedes drift.
            driftStartedAt = 0
            driftDirection = null
        } else {
            offRouteStartedAt = 0
        }

        // ── Drift: only when not off-route AND not walking parallel ──
        if (!isParallel && offRouteStartedAt == 0L &&
            proj.lateralM > driftMinM && proj.lateralM <= driftMaxM
        ) {
            val dir = if (proj.lateralSign > 0) DriftDir.Right else DriftDir.Left
            if (driftDirection != dir) {
                driftStartedAt = nowMs
                driftDirection = dir
            } else if (nowMs - driftStartedAt > driftDurationMs) {
                // Tell the rider to lean OPPOSITE to where they drifted
                val event = if (dir == DriftDir.Right)
                    GuidanceEvent.DriftLeft(proj.lateralM)
                else
                    GuidanceEvent.DriftRight(proj.lateralM)
                if (shouldFire(event.kind, nowMs)) {
                    events += event
                    driftStartedAt = nowMs
                }
            }
        } else if (offRouteStartedAt == 0L) {
            driftStartedAt = 0
            driftDirection = null
        }

        // ── Heading mismatch: only at standstill (GPS bearing unreliable) ─
        // Suppressed entirely when the phone is pocketed — a stowed
        // phone's compass points wherever the pocket faces, so a
        // perfectly on-route rider stopped at a light would otherwise
        // get a false "Wrong way — turn around". A pocketed rider
        // doesn't need standstill wrong-way detection anyway: the
        // moment they move, GPS course takes over and off-route
        // detection (lateral offset) covers the real case.
        val compass = compassHeadingDeg
        val speed = p.speedMps
        if (!phoneInPocket && compass != null &&
            (speed == null || speed < maxSpeedForHeadingCheckMps)) {
            val mismatchDeg = angleDeltaAbs(compass, proj.segmentBearingDeg)
            if (mismatchDeg > headingMismatchDeg) {
                if (headingMismatchStartedAt == 0L) {
                    headingMismatchStartedAt = nowMs
                } else if (nowMs - headingMismatchStartedAt > headingMismatchDurationMs &&
                    shouldFire(EventKind.WrongWayHeading, nowMs)
                ) {
                    events += GuidanceEvent.WrongWayHeading(mismatchDeg)
                    headingMismatchStartedAt = nowMs
                }
            } else {
                headingMismatchStartedAt = 0
            }
        } else {
            headingMismatchStartedAt = 0
        }

        // ── Speed warning: going too fast for upcoming maneuver ──────
        if (speed != null && speed > slowDownMinSpeedMps &&
            p.metersToUpcomingManeuver > 5.0
        ) {
            val timeToManeuver = p.metersToUpcomingManeuver / speed
            if (timeToManeuver < slowDownTimeToManeuverSec &&
                shouldFire(EventKind.SlowDown, nowMs)
            ) {
                events += GuidanceEvent.SlowDown(timeToManeuver)
            }
        }

        // ── Almost-there: between 50 and 150 m of total route remaining ──
        if (p.metersRemaining in almostThereWindowMeters &&
            shouldFire(EventKind.AlmostThere, nowMs)
        ) {
            // Side hint lives on the maneuver type at the final step —
            // adapter populates it. We don't have it here directly, so
            // emit a neutral AlmostThere; NavLayer can pick the
            // sided variant from its current maneuver.
            events += GuidanceEvent.AlmostThere
        }

        // ── Keep-going heartbeat ─────────────────────────────────────
        // Fires the soft chime after 30 s of silence OR ~350 m covered
        // since the last cue — whichever first. The distance arm makes
        // it speed-aware: at speed (50 km/h on a scooter ≈ 1.4 km in
        // 30 s of clock-only silence) the rider hears the pulse keep
        // pace with the ground, not a fixed clock that leaves a long
        // gap. Mirrors the iOS arm.
        val coveredSinceCue = metersRemainingAtSpoke - p.metersRemaining
        if (lastSpokeAt > 0 &&
            (nowMs - lastSpokeAt > silenceThresholdMs ||
             coveredSinceCue.toDouble() > silenceDistanceM) &&
            shouldFire(EventKind.KeepGoing, nowMs)
        ) {
            events += GuidanceEvent.KeepGoing
        }

        return events
    }

    private fun shouldFire(kind: EventKind, nowMs: Long): Boolean {
        val last = lastEventAt[kind] ?: 0L
        if (nowMs - last < sameEventCooldownMs) return false
        lastEventAt[kind] = nowMs
        return true
    }

    private enum class DriftDir { Left, Right }
    private enum class EventKind {
        KeepGoing, DriftLeft, DriftRight, SlowDown,
        WrongWayHeading, OffRoute, AlmostThere,
    }
    /** Map a public event back to its dedup key. */
    private val GuidanceEvent.kind: EventKind get() = when (this) {
        is GuidanceEvent.KeepGoing -> EventKind.KeepGoing
        is GuidanceEvent.DriftLeft -> EventKind.DriftLeft
        is GuidanceEvent.DriftRight -> EventKind.DriftRight
        is GuidanceEvent.SlowDown -> EventKind.SlowDown
        is GuidanceEvent.WrongWayHeading -> EventKind.WrongWayHeading
        is GuidanceEvent.OffRoute -> EventKind.OffRoute
        is GuidanceEvent.AlmostThere -> EventKind.AlmostThere
    }
}

/**
 * Semantic guidance events. Stateless — the [GuidanceMonitor] times
 * them and the [ScoovaNavLayer] maps each to a server-rendered phrase
 * (`trip.scoova.state.keepGoing` / `.driftLeft` / `.slowDown` / etc.).
 */
public sealed class GuidanceEvent {
    /** 30 s of silence on a straight stretch — reassure the rider. */
    public object KeepGoing : GuidanceEvent()
    /** Drifting too far right of the polyline; tell rider to lean left. */
    public data class DriftLeft(val lateralM: Double) : GuidanceEvent()
    /** Drifting too far left of the polyline; tell rider to lean right. */
    public data class DriftRight(val lateralM: Double) : GuidanceEvent()
    /** Speed × time-to-maneuver below comfort threshold — slow down. */
    public data class SlowDown(val secondsToManeuver: Double) : GuidanceEvent()
    /** Standstill but facing > 45° away from the polyline bearing. */
    public data class WrongWayHeading(val mismatchDeg: Float) : GuidanceEvent()
    /** Persistently > 30 m off the polyline — needs re-route. */
    public data class OffRoute(val lateralM: Double) : GuidanceEvent()
    /** 50–150 m to destination — fire the "almost there" cue. */
    public object AlmostThere : GuidanceEvent()
}

// ─────────────────────────────────────────────────────────────────────
// Polyline projection — internal but exposed for unit tests.
// ─────────────────────────────────────────────────────────────────────

internal data class PolylineProjection(
    /** Distance travelled along the polyline up to the projected point. */
    val progressM: Double,
    /** Unsigned perpendicular distance from polyline to query point. */
    val lateralM: Double,
    /** Sign of the lateral offset. Positive = right of polyline (in
     *  travel direction), negative = left. */
    val lateralSign: Double,
    /** Forward bearing of the polyline segment at the projection. */
    val segmentBearingDeg: Float,
)

/**
 * Project a point onto the polyline. Uses Euclidean approximation
 * (treats lat/lon as planar — fine at city scale, errors < 0.1% inside
 * ~10 km radius). For each segment computes the foot of perpendicular,
 * picks the closest segment, and returns progress + lateral offset +
 * segment bearing.
 */
internal fun projectOntoPolyline(
    lat: Double,
    lon: Double,
    shape: List<DoubleArray>,
): PolylineProjection? {
    if (shape.size < 2) return null
    var bestSeg = 0
    var bestT = 0.0   // 0..1 along segment
    var bestLatProj = lat
    var bestLonProj = lon
    var bestDistM = Double.MAX_VALUE

    for (i in 0 until shape.size - 1) {
        val a = shape[i]; val b = shape[i + 1]
        // Use lon = x, lat = y in planar approx.
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
        val d = GeoMath.haversineMeters(py, px, cy, cx)
        if (d < bestDistM) {
            bestDistM = d
            bestSeg = i
            bestT = t
            bestLatProj = cy
            bestLonProj = cx
        }
    }

    val a = shape[bestSeg]; val b = shape[bestSeg + 1]
    // Lateral sign — 2D cross product of (segment vector) × (a→point vector).
    // Positive cross = point lies to the left of travel direction, negative = right.
    // We want POSITIVE = right (matches "drift right" semantics) → invert sign.
    val cross = (b[1] - a[1]) * (lat - a[0]) - (b[0] - a[0]) * (lon - a[1])
    val sign = if (cross < 0) 1.0 else -1.0

    // Progress: sum of full segments before bestSeg + partial of bestSeg.
    var progress = 0.0
    for (i in 0 until bestSeg) {
        progress += GeoMath.haversineMeters(
            shape[i][0], shape[i][1], shape[i + 1][0], shape[i + 1][1],
        )
    }
    progress += GeoMath.haversineMeters(a[0], a[1], bestLatProj, bestLonProj)

    val bearing = GeoMath.bearingDeg(a[0], a[1], b[0], b[1]).toFloat()
    return PolylineProjection(progress, bestDistM, sign, bearing)
}

/** Smallest absolute angular distance between two bearings (degrees). */
internal fun angleDeltaAbs(a: Float, b: Float): Float {
    var d = abs(a - b) % 360f
    if (d > 180f) d = 360f - d
    return d
}
