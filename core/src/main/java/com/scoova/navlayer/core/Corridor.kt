package com.scoova.navlayer.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Port of iOS `Corridor.swift`. Versioned per-route data the on-device
 * guidance reasoner reads to generate cues from grammar instead of
 * replaying pre-baked strings.
 *
 * The routing service emits one [Corridor] per route at fetch time
 * alongside the existing `trip` block. The SDK decodes it once,
 * stores it on the layer, and the reasoner queries it on every
 * progress tick to answer "where am I in the road network?" and
 * "what should I say next?"
 *
 * Contract source-of-truth: docs/CorridorContract.md in the iOS repo —
 * both sides build to that document. Field defaults make older
 * server responses (no corridor block) decode cleanly so the SDK
 * degrades gracefully to the legacy baked-string voice path.
 */
@Serializable
public data class Corridor(
    /** Schema version. `1` is the initial contract. */
    val version: Int = 1,
    /** Polyline-aligned fingerprints identifying which road segment
     *  each run of polyline vertices belongs to. */
    val graphFingerprints: List<GraphFingerprint> = emptyList(),
    /** Per-maneuver context the reasoner uses to pick cue grammar. */
    val maneuvers: List<CorridorManeuver> = emptyList(),
    /** Every OSM way within ~80 m of the route polyline — "the map the
     *  navigator is holding." Empty when the routing service hasn't
     *  shipped Phase G yet; the SDK then falls back to lateral-distance
     *  heuristics. */
    val neighbourGraph: List<NeighbourWay> = emptyList(),
)

@Serializable
public data class NeighbourWay(
    val wayId: Long,
    val name: String = "",
    val roadClass: String = "",
    val oneway: Boolean = false,
    val speedLimitKph: Int? = null,
    val segments: List<NeighbourWaySegment> = emptyList(),
)

@Serializable
public data class NeighbourWaySegment(
    /** Polyline points as `[[lat, lon], …]`. */
    val shape: List<List<Double>> = emptyList(),
    /** `true` when the rider's expected direction of travel on this
     *  edge is forward (lat/lon order). */
    val forward: Boolean = true,
)

/**
 * One contiguous run of the polyline that shares the same road segment
 * + direction of travel. Adjacent fingerprints with the same `wayId`
 * + `direction` MUST be merged into one entry on the wire.
 */
@Serializable
public data class GraphFingerprint(
    /** Inclusive index into the concatenated polyline. */
    val polylineFrom: Int,
    /** Inclusive index into the concatenated polyline. */
    val polylineTo: Int,
    /** Stable OSM way id. */
    val wayId: Long,
    /** `"forward"` or `"reverse"` — direction of travel along the way. */
    val direction: String,
)

/**
 * Reasoner context for a single maneuver — what the rider passes on the
 * approach segment, how that disambiguates the upcoming decision.
 */
@Serializable
public data class CorridorManeuver(
    /** Global maneuver index. Matches [ManeuverEvent.index] after
     *  multi-leg concatenation. */
    val index: Int,
    /** Drivable cross-streets the rider passes between the previous
     *  maneuver and this one, in ride order. */
    val crossStreets: List<CrossStreet> = emptyList(),
    /** Open-vocabulary flags the reasoner reads (`multipleLeftsBeforeLeftTurn`,
     *  `multipleRightsBeforeRightTurn`, `interchangeCluster`,
     *  `roundaboutExitAmbiguous`). Unknown flags ignored. */
    val ambiguityFlags: List<String> = emptyList(),
    /** Ordinal context for "take the [Nth] left/right" grammar. */
    val ordinal: ManeuverOrdinal? = null,
    /** Coarse complexity for host UI emphasis — one of
     *  `simple`, `fourWay`, `complex`, `roundabout`, `interchange`. */
    val intersectionComplexity: String? = null,
)

/** One drivable cross-street the rider passes on the approach. */
@Serializable
public data class CrossStreet(
    /** Index into the concatenated polyline where the crossing sits. */
    val polylineIdx: Int,
    /** `"L"`, `"R"`, or `"LR"` (T-junction). */
    val side: String,
    /** `true` when a rider on the current routing profile could turn
     *  into this cross-street. The reasoner skips `drivable: false`
     *  entries when counting ordinals. */
    val drivable: Boolean,
    /** Human-readable street name. May be empty. */
    val name: String = "",
    /** Along-route distance from the crossing to the maneuver. */
    val metersBeforeManeuver: Int,
)

/**
 * Ordinal of a maneuver among the same-side turns on its approach
 * segment. `totalSameSideTurns == 0` means no competing same-side
 * turns — the reasoner uses that as proof that "the next left" is
 * safe to speak.
 */
@Serializable
public data class ManeuverOrdinal(
    /** `"L"` or `"R"` — matches the maneuver's turn direction. */
    val side: String,
    /** 1-based index of THIS maneuver among same-side turns on the
     *  approach. */
    val indexAmongSameSideTurns: Int,
    /** Total same-side turns between the previous maneuver and this
     *  one, inclusive of this maneuver. */
    val totalSameSideTurns: Int,
)
