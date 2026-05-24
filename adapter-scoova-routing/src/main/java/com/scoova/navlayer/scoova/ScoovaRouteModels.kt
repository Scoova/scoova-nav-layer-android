package com.scoova.navlayer.scoova

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for Scoova's `/v1/route` endpoint. Mirrors Valhalla's
 * directions response shape — the upstream we depend on.
 */
@Serializable
internal data class RouteRequest(
    val locations: List<LatLon>,
    val costing: String,
    val language: String,
    @SerialName("simplified_instructions") val simplifiedInstructions: Boolean = true,
    val landmarks: Boolean? = null,
    @SerialName("directions_options") val directionsOptions: DirectionsOptions = DirectionsOptions(),
    /**
     * Per-costing penalties. Valhalla treats these as 0..1 floats where
     * 0 = avoid completely and 1 = neutral. Sent only when at least one
     * non-default is requested — null otherwise so the request body
     * stays minimal and small caches see the same shape on the default
     * path.
     */
    @SerialName("costing_options") val costingOptions: CostingOptions? = null,
    /**
     * Scoova voice-cue mode flag. "eyes_on" (default, omitted) emits
     * the distance-led copy that matches looking at the map. "eyes_off"
     * tells the routing-server enrichment to lead with landmark anchors
     * ("After McDonald's, turn right…") and auto-enables landmark
     * enrichment. Unknown values are treated as eyes_on server-side.
     */
    @SerialName("voiceMode") val voiceMode: String? = null,
)
@Serializable internal data class DirectionsOptions(val units: String = "kilometers")

/**
 * Costing-options wrapper. Each costing profile has its own block; we
 * only populate the one we're using for this trip. The rest are null
 * and dropped from serialization.
 */
@Serializable
internal data class CostingOptions(
    val auto: CostingPenalties? = null,
    val bicycle: CostingPenalties? = null,
    val pedestrian: CostingPenalties? = null,
    val motorcycle: CostingPenalties? = null,
)

@Serializable
internal data class CostingPenalties(
    @SerialName("use_highways") val useHighways: Double? = null,
    @SerialName("use_tolls") val useTolls: Double? = null,
    @SerialName("use_ferry") val useFerry: Double? = null,
)
@Serializable public data class LatLon(val lat: Double, val lon: Double)

@Serializable
internal data class RouteResponse(val trip: Trip? = null)

@Serializable
internal data class Trip(
    val legs: List<Leg>,
    val summary: TripSummary,
    // Server-rendered state-machine vocabulary (welcome / good / keepGoing
    // / almostThere / arrived / wrongWay / missedTurn / rerouting / slow).
    // All clients render whatever the server says — no client-side phrasing.
    val scoova: TripScoova? = null,
)

@Serializable
internal data class Leg(val maneuvers: List<RouteManeuver>, val shape: String)

@Serializable
internal data class TripSummary(val length: Double, val time: Double)

@Serializable
internal data class RouteManeuver(
    val type: Int,
    val instruction: String? = null,
    @SerialName("verbal_succinct_transition_instruction") val verbalSuccinct: String? = null,
    val length: Double = 0.0,
    val time: Double = 0.0,
    @SerialName("begin_shape_index") val beginShapeIndex: Int = 0,
    @SerialName("end_shape_index") val endShapeIndex: Int = 0,
    @SerialName("roundabout_exit_count") val roundaboutExitCount: Int? = null,
    /**
     * Scoova-rendered eyes-on-the-road copy for this maneuver. Source of
     * truth for the banner + voice — clients render it verbatim. See
     * landmark-proxy.py / scoova-copy.json on routing.scoo-va.info.
     */
    val scoova: ManeuverScoova? = null,
)

@Serializable
internal data class ManeuverScoova(
    /** Canonical direction kind: right / left / roundabout / depart / arrive / etc. */
    val kind: String,
    /** Roundabout exit number (1-indexed). Null when not a roundabout. */
    val exit: Int? = null,
    /** Locale the strings are in (e.g. "ar-EG"). */
    val lang: String? = null,
    /** Raw POI name (without infix), useful for icon-overlay lookups. */
    val landmark: String? = null,
    /**
     * Count of intersection-class nodes the rider crosses between this
     * maneuver's anchor and the previous one. Server-computed from
     * the routing graph (Valhalla already walks it). Drives "turn at
     * the second street" phrasing — null when the count is 1 (just
     * use "next street") or unavailable.
     */
    val streetsToTurn: Int? = null,
    /** Name of the street the rider IS on going into this maneuver
     *  (i.e. the segment BEFORE the turn). Used for reaffirmation
     *  cues ("Still on Al-Tahrir Street, 200 meters to go"). */
    val currentStreetName: String? = null,
    /** Name of the street the rider will be on AFTER this maneuver.
     *  Used for confirmation cues ("Good. You're on Al-Hasen Street."). */
    val nextStreetName: String? = null,
    val banner: ManeuverBannerCopy? = null,
    val voice: ManeuverVoiceCopy? = null,
)

@Serializable
internal data class ManeuverBannerCopy(
    /** Big primary line on the banner: "Turn right" / "حوّد يمين". */
    val verb: String? = null,
    /** Secondary line: "after the gas station" / "بعد البنزينة". Null if no landmark. */
    val anchor: String? = null,
    val kind: String? = null,
)

@Serializable
internal data class ManeuverVoiceCopy(
    // ── Legacy template fields (kept for adapters / server versions
    //    that haven't rolled out the full-sentence schema yet). The
    //    full-sentence fields below take precedence whenever present.
    val headsUp: String? = null,
    val turnNow: String? = null,
    val atLandmark: String? = null,
    val getReadyTemplate: String? = null,
    val atDistanceTemplate: String? = null,

    // ── Full-sentence schema (v2) — server owns the entire utterance.
    //    Each field is a complete, locale-correct, ready-to-speak
    //    sentence. Client plays verbatim, no string interpolation
    //    happens on this side. This removes Frankenstein dialect
    //    risk from the chained-suffix / distance-lead-in compositing
    //    we used to do client-side. See [feedback-native-first].
    /**
     * Long-lead cue. Includes distance prefix + verb + landmark/street +
     * ordinal where applicable, e.g.:
     *   "In 200 meters, turn right at the gas station onto Al-Hasen Street."
     *   "بعد ٢٠٠ متر، حوّد يمين بعد البنزينة في شارع الحسن."
     */
    val far: String? = null,
    /**
     * Mid-lead cue. Shorter than [far], adds urgency, e.g.:
     *   "In 80 meters, get ready to turn right."
     */
    val mid: String? = null,
    /**
     * At-the-maneuver cue. Imperative, with the destination street so
     * the rider can confirm by sound:
     *   "Turn right now, onto Al-Hasen Street."
     */
    val near: String? = null,
    /**
     * Chained-turn cue. Server pre-composes this when the NEXT
     * maneuver is < 100 m away AND is a turn class — it bundles
     * THIS maneuver's "do it now" with a heads-up about the
     * immediate next turn:
     *   "Turn right now onto West 33rd Street. Then quickly turn
     *   right again."
     * When non-null, the client should speak this INSTEAD of [near]
     * — it carries the same "now" content plus the chain warning.
     * Without it the rider hears the first turn cue, executes, then
     * runs out of cue-time for the second turn that's ~11 s away.
     */
    val chained: String? = null,
    /**
     * Post-turn confirmation. Spoken ONLY when [com.scoova.navlayer.core.EyesOffGuide]
     * decides GPS-on-route AND yaw delta both agree the rider executed
     * the turn correctly:
     *   "Good. You're on Al-Hasen Street. Continue 400 meters."
     */
    val confirm: String? = null,
    /**
     * Post-turn recovery. Spoken when the same gate decides the turn
     * was missed (GPS or yaw disagree past tolerance):
     *   "Looks like you missed the turn. Recalculating."
     */
    val recover: String? = null,
    /**
     * Mid-segment reaffirmation for long segments. Spoken every
     * ~300 m of straight progress on segments > 500 m:
     *   "Still on Al-Tahrir Street. 200 meters to go, then turn right."
     */
    val reaffirm: String? = null,
    /**
     * Grid-city variant of [far] using block counts. Spoken instead
     * of [far] when the rider's region was tagged grid-pattern by
     * the server's enrichment pass:
     *   "In two blocks, turn right onto Al-Hasen Street."
     */
    val blocks: String? = null,
    /**
     * Mid-segment "you're passing X" cue. Server pre-renders this
     * with a landmark roughly halfway through long segments + the
     * side (left/right/ahead). Client fires it when the rider has
     * travelled [checkpointOffsetMeters] into the segment after the
     * PRIOR maneuver. Null when the segment was too short or no
     * usable POI sits along it.
     */
    val checkpoint: String? = null,
    /**
     * Distance in metres from the prior maneuver at which the
     * client should fire the [checkpoint] cue. Typically ~50 % of
     * the prior-segment length. Null when [checkpoint] is null.
     */
    val checkpointOffsetMeters: Int? = null,
    /**
     * Server-recommended firing distance (metres) for each phase.
     * Computed from the profile's typical speed × target seconds-out
     * (30 s far, 15 s mid, 5 s near is the rule of thumb). Client may
     * adjust based on live speed but defaults to these.
     */
    val farMeters: Int? = null,
    val midMeters: Int? = null,
    val nearMeters: Int? = null,
)

@Serializable
internal data class TripScoova(
    val lang: String? = null,
    val dir: String? = null,
    /** Legacy state phrases — kept for backward compatibility. The
     *  full-sentence trip phrases below supersede this for v2 adapters. */
    val state: Map<String, String> = emptyMap(),

    // ── Full-sentence trip-level phrases (v2) ─────────────────────
    /**
     * Pre-ride briefing. Spoken once at startRide() before the first
     * maneuver cue. Sets expectations for the eyes-off rider:
     *   "Let's go. Your trip is 4 kilometers, 12 minutes, with 5 turns.
     *    First turn in 400 meters."
     */
    val welcomeFull: String? = null,
    /** Spoken when a reroute begins (off-route or network drop):
     *  "Lost the route, searching." */
    val rerouteSearching: String? = null,
    /** Spoken when the reroute completes successfully:
     *  "Route found, continue." */
    val rerouteFound: String? = null,
    /** Spoken when a reroute attempt fails (network down):
     *  "Can't reach the routing service, retrying." */
    val rerouteFailed: String? = null,
    /** Spoken on arrival — verbose variant for eyes-off. Includes
     *  "you've arrived" verbiage with the destination label:
     *  "You've arrived at Al-Tahrir Square." */
    val arrivedFull: String? = null,
    /** Final-approach cue, spoken ~30 m before destination.
     *  Includes destination side when available:
     *  "In 30 meters, your destination is on your right." */
    val almostThereFull: String? = null,
)

/** Decode Valhalla's polyline6 string. Stdlib only. */
internal object Polyline6 {
    fun decode(encoded: String): List<DoubleArray> {
        val out = ArrayList<DoubleArray>(encoded.length / 4)
        var i = 0; var lat = 0; var lng = 0
        while (i < encoded.length) {
            for (axis in 0..1) {
                var shift = 0; var result = 0
                while (true) {
                    val b = encoded[i].code - 63; i++
                    result = result or ((b and 0x1f) shl shift); shift += 5
                    if (b < 0x20) break
                }
                val delta = if (result and 1 != 0) (result shr 1).inv() else result shr 1
                if (axis == 0) lat += delta else lng += delta
            }
            out += doubleArrayOf(lat / 1e6, lng / 1e6)
        }
        return out
    }
}
