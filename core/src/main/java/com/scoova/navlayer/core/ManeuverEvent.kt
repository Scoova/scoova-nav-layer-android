package com.scoova.navlayer.core

/**
 * Generic maneuver event that any host nav SDK adapter pushes into Scoova
 * Nav Layer. Mapbox, Google Maps, in-house engines — they all reduce to
 * this shape after their own observers are translated.
 *
 * Keep it small. Anything we don't strictly need is intentionally absent;
 * a richer model would lock us into one host SDK's vocabulary.
 */
public data class ManeuverEvent(
    /** Stable index of this maneuver in the route's step list. */
    val index: Int,
    /** Total number of maneuvers in the route. */
    val total: Int,
    /** Type of maneuver — see [ManeuverType] for the canonical mapping. */
    val type: ManeuverType,
    /**
     * Host-SDK-supplied instruction string. We keep it for fallback /
     * diagnostics, but the canonical render fields are [bannerVerb] +
     * [bannerAnchor] + [voiceHeadsUp]/[voiceTurnNow] which are filled by
     * adapters from the server's `scoova` block — that's the only path
     * that produces eyes-on-the-road copy consistent across all 5 SDKs.
     */
    val rawInstruction: String?,
    /** Latitude of the maneuver point. */
    val latitude: Double,
    /** Longitude of the maneuver point. */
    val longitude: Double,
    /** Length of this maneuver's segment in metres. */
    val segmentLengthMeters: Double,
    /**
     * Expected duration (seconds) of the segment from the previous
     * maneuver to this one. Server-provided. Used by the cue scheduler
     * to space reaffirm/checkpoint cues by *time* (every ~75 s of
     * riding) instead of by distance — without it, a 1 km walk gets
     * one reaffirm in five minutes while the same 1 km on a scooter
     * gets one every 40 seconds. Null ⇒ scheduler falls back to a
     * fixed-distance heuristic.
     */
    val segmentDurationSeconds: Double? = null,
    /**
     * Roundabout / sign exit number (1-indexed) when [type] is a roundabout.
     * Null for everything else.
     */
    val roundaboutExit: Int? = null,

    // ── Server-rendered Scoova navigation copy (the scoova.* block) ──────
    // Adapters that fetch from routing.scoo-va.info pass these straight
    // through; adapters built on third-party engines (Mapbox, Google Maps)
    // leave them null and the banner falls back to [rawInstruction].

    /** Banner primary line — short verb. "Turn right" / "حوّد يمين". */
    val bannerVerb: String? = null,
    /** Banner secondary line — landmark anchor. "after the gas station" / "بعد البنزينة". Null if none. */
    val bannerAnchor: String? = null,

    // ── Legacy voice template fields (v1). Kept for adapters / server
    //    versions that haven't shipped the full-sentence schema yet. The
    //    full-sentence fields below take precedence.
    val voiceHeadsUp: String? = null,
    val voiceTurnNow: String? = null,
    val voiceAtLandmark: String? = null,
    val voiceGetReadyTemplate: String? = null,
    val voiceAtDistanceTemplate: String? = null,

    /** Raw POI name (no infix) for icon overlays. */
    val landmark: String? = null,

    // ── Server-rendered full sentences (v2) ────────────────────────
    //    Each field is a complete, locale-correct utterance that the
    //    client speaks verbatim — no client-side composition. Empty/
    //    null fields cause the client to fall back to legacy templates,
    //    and ultimately to CuePhrases.build for non-Scoova adapters.
    /** Long-lead cue, full sentence with distance + verb + landmark/street. */
    val voiceFar: String? = null,
    /** Mid-lead cue, full sentence. */
    val voiceMid: String? = null,
    /** At-the-maneuver cue, full sentence with destination street. */
    val voiceNear: String? = null,
    /**
     * Chained-turn cue, full sentence. Server pre-composes this when
     * the NEXT maneuver is < 100 m away AND is a turn class — it
     * bundles THIS maneuver's "do it now" with a heads-up about the
     * immediate next turn ("Turn right now onto X. Then quickly turn
     * right again."). The near-phase speak path should prefer this
     * over [voiceNear] when non-null. Without it the rider hears the
     * first turn cue, executes, and runs out of cue-time for the
     * 11-second-away second turn.
     */
    val voiceChained: String? = null,
    /** Post-turn confirmation, full sentence ("Good. You're on X…"). */
    val voiceConfirm: String? = null,
    /** Post-turn recovery, full sentence ("Looks like you missed…"). */
    val voiceRecover: String? = null,
    /** Mid-segment reaffirmation for long segments. */
    val voiceReaffirm: String? = null,
    /** Grid-city block-count variant of [voiceFar]. */
    val voiceBlocks: String? = null,
    /**
     * Mid-segment "you're passing X" cue, pre-rendered with the
     * landmark + side. Spoken by [EyesOffGuide] when the rider has
     * travelled [checkpointOffsetMeters] into the segment after the
     * prior maneuver. Null when no usable POI sits mid-segment.
     */
    val voiceCheckpoint: String? = null,
    /**
     * Distance in metres (from the prior maneuver) at which the
     * client should fire [voiceCheckpoint]. Null when checkpoint is
     * null.
     */
    val checkpointOffsetMeters: Int? = null,

    /** Server-recommended firing distances per phase (metres). */
    val farMeters: Int? = null,
    val midMeters: Int? = null,
    val nearMeters: Int? = null,

    /** Intersection-class node count between this maneuver and the
     *  previous one. Lets the UI render "second street" badges where
     *  appropriate. Null when count is 1 or unavailable. */
    val streetsToTurn: Int? = null,
    /** Street name the rider is on going INTO this maneuver. */
    val currentStreetName: String? = null,
    /** Street name the rider will be on AFTER this maneuver. */
    val nextStreetName: String? = null,
)

/**
 * Canonical maneuver-type taxonomy. Mirrors the Valhalla / OSRM model
 * because that's what most modern routing engines emit. Adapters are
 * responsible for translating their host SDK's enum into this one.
 */
public enum class ManeuverType {
    Depart,
    Arrive,
    Continue,
    SlightLeft, Left, SharpLeft,
    SlightRight, Right, SharpRight,
    Uturn,
    RoundaboutEnter, RoundaboutExit,
    RampLeft, RampRight, RampStraight,
    ExitLeft, ExitRight,
    StayLeft, StayRight, StayStraight,
    Merge,
    Becomes,
    Other;

    public val isLowValueOnFoot: Boolean get() = when (this) {
        Becomes, Continue, SlightLeft, SlightRight, StayStraight -> true
        else -> false
    }
    public val isExit: Boolean get() = when (this) {
        ExitLeft, ExitRight, RampLeft, RampRight, RampStraight -> true
        else -> false
    }
    public val isRoundabout: Boolean get() = when (this) {
        RoundaboutEnter, RoundaboutExit -> true
        else -> false
    }
    public val isUturn: Boolean get() = this == Uturn
    public val isLeftSide: Boolean get() = when (this) {
        SlightLeft, Left, SharpLeft, RampLeft, ExitLeft, StayLeft -> true
        else -> false
    }
    public val isRightSide: Boolean get() = when (this) {
        SlightRight, Right, SharpRight, RampRight, ExitRight, StayRight -> true
        else -> false
    }
}

/**
 * Per-tick progress info pushed by the host adapter. Adapters should call
 * [ScoovaNavLayer.onProgress] every time the host emits a route-progress
 * update — typically 1-4 times per second.
 */
public data class ProgressEvent(
    /** Current location in WGS84. */
    val latitude: Double,
    val longitude: Double,
    /** Speed in metres per second, if known. */
    val speedMps: Float? = null,
    /** GPS-derived bearing in degrees, if known and reliable. */
    val bearingDeg: Float? = null,
    /** Index into the maneuvers list of the upcoming/current step. */
    val upcomingManeuverIndex: Int,
    /** Distance in metres to the upcoming maneuver point. */
    val metersToUpcomingManeuver: Double,
    /** Estimated seconds remaining on the trip. */
    val secondsRemaining: Int,
    /** Estimated metres remaining on the trip. */
    val metersRemaining: Int,
)
