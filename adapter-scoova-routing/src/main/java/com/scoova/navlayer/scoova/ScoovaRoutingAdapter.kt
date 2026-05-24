package com.scoova.navlayer.scoova

import com.scoova.navlayer.core.GeoMath
import com.scoova.navlayer.core.ManeuverEvent
import com.scoova.navlayer.core.ManeuverType
import com.scoova.navlayer.core.ProgressEvent
import com.scoova.navlayer.core.ScoovaNavLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Use Scoova's routing API together with **any** map display (Google Maps,
 * Mapbox GL, MapLibre, Apple MapKit, your in-house renderer). No host nav
 * SDK required.
 *
 * The flow:
 *
 * ```kotlin
 * val nav = ScoovaNavLayer.builder(ctx).apiKey(KEY).locale("ar-EG").profile("scooter").build()
 * nav.start()
 *
 * val routing = ScoovaRoutingAdapter(KEY, nav)
 *
 * // 1. Fetch the route + drop the polyline on your map
 * val polyline = routing.startRoute(
 *     from = LatLon(30.0444, 31.2357),
 *     to   = LatLon(30.0626, 31.2497),
 *     profile = "scooter",
 *     language = "ar-EG",
 *     landmarks = true,
 * )
 * yourMap.drawRoute(polyline)
 *
 * // 2. Pipe your existing location updates through the adapter
 * fusedLocationProvider.onUpdate { loc ->
 *     routing.onLocation(loc.latitude, loc.longitude, loc.speed, loc.bearing)
 * }
 * ```
 *
 * The adapter handles polyline-snap, current-step detection, and ETA
 * recompute internally — the customer never touches our maneuver model.
 */
public class ScoovaRoutingAdapter(
    apiKey: String,
    private val layer: ScoovaNavLayer,
    // Routing goes through the keyed gateway — `/api/v1/route` proxies
    // POST bodies straight to the Valhalla landmark-proxy. The raw
    // routing subdomain is firewalled.
    routingUrl: String = "https://api.scoo-va.info/api/v1/route",
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val routingUrl = routingUrl
    private val apiKey = apiKey

    private var maneuvers: List<ManeuverEvent> = emptyList()
    /** Parallel to [maneuvers] — each entry is that maneuver's polyline
     *  shape-index (Valhalla's `begin_shape_index`). Used to decide
     *  which segment of the route the rider is currently in. */
    private var maneuverShapeIdx: IntArray = IntArray(0)
    private var shape: List<DoubleArray> = emptyList()
    private var totalSeconds: Double = 0.0
    private var totalMeters: Double = 0.0
    /**
     * Index of the last maneuver the rider has already reached or
     * passed. -1 = haven't reached any yet (still on the run-up to
     * the first turn). Banner / cue logic adds 1 to get "upcoming".
     */
    private var currentManeuverIdx: Int = -1

    /**
     * Cumulative distance (m) from the polyline's start to each
     * maneuver's anchor point. Filled in [startRoute] right after
     * we decode the shape; consulted on every [onLocation] tick
     * to compare against the rider's projected progress.
     *
     * This is the canonical "where is each turn along the route"
     * table. Pre-computing avoids a per-tick O(n) sum.
     */
    private var maneuverProgressM: DoubleArray = DoubleArray(0)

    /**
     * Pad (m) the rider must advance PAST the maneuver's anchor
     * point before we consider it "passed". Prevents the banner
     * from flipping to the next maneuver while the rider is
     * physically still at the previous turn — the original
     * nearest-shape-vertex heuristic flipped early because vertex
     * snapping treats "halfway between two vertices" as "at the
     * later vertex". 8 m matches typical urban-block centerline
     * resolution.
     */
    private val maneuverPastPadM: Double = 8.0

    /**
     * Fetch a route and start driving the layer. Returns the decoded
     * polyline so the caller can draw it on whatever map they're using.
     */
    public suspend fun startRoute(
        from: LatLon,
        to: LatLon,
        profile: String = "auto",
        language: String = "en-US",
        landmarks: Boolean = true,
        avoidHighways: Boolean = false,
        avoidTolls: Boolean = false,
        avoidFerries: Boolean = false,
        /**
         * Eyes-off voice mode. When true, the routing server picks
         * landmark-led voice templates ("After McDonald's, turn right
         * onto X") and auto-enables landmark enrichment. When false
         * (default), the server emits the distance-led copy ("In 350
         * meters, turn right onto X") that matches the eyes-on /
         * looking-at-the-map mental model.
         */
        eyesOff: Boolean = false,
    ): List<DoubleArray> = withContext(Dispatchers.IO) {
        // Build the per-costing penalty block only when at least one
        // avoid flag is set — keeps the default request body identical
        // to what it was before so caches don't get polluted.
        val anyAvoid = avoidHighways || avoidTolls || avoidFerries
        val penalties = if (anyAvoid) CostingPenalties(
            useHighways = if (avoidHighways) 0.0 else null,
            useTolls = if (avoidTolls) 0.0 else null,
            useFerry = if (avoidFerries) 0.0 else null,
        ) else null
        val costingOptions: CostingOptions? = penalties?.let {
            when (profile) {
                "bicycle" -> CostingOptions(bicycle = it)
                "pedestrian" -> CostingOptions(pedestrian = it)
                "motorcycle" -> CostingOptions(motorcycle = it)
                else -> CostingOptions(auto = it)  // "auto", "truck", etc.
            }
        }
        val body = json.encodeToString(
            RouteRequest.serializer(),
            RouteRequest(
                locations = listOf(from, to),
                costing = profile,
                language = language,
                simplifiedInstructions = true,
                landmarks = if (landmarks) true else null,
                costingOptions = costingOptions,
                voiceMode = if (eyesOff) "eyes_off" else null,
            ),
        ).toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(routingUrl)
            .header("X-API-Key", apiKey)
            .header("User-Agent", "scoova-nav-layer/0.1 (android)")
            .post(body)
            .build()
        val resp = http.newCall(req).execute()
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) error("routing http ${resp.code}: ${text.take(200)}")
        val parsed = json.decodeFromString(RouteResponse.serializer(), text)
        val trip = parsed.trip ?: error("routing: no trip in response")
        val leg = trip.legs.firstOrNull() ?: error("routing: no legs in response")

        shape = Polyline6.decode(leg.shape)
        totalSeconds = trip.summary.time
        totalMeters = trip.summary.length * 1000.0  // km → m
        // -1 = "haven't reached any maneuver yet" so the first
        // onProgress tick correctly identifies the FIRST turn as
        // upcoming (not the second).
        currentManeuverIdx = -1

        maneuverShapeIdx = IntArray(leg.maneuvers.size) {
            leg.maneuvers[it].beginShapeIndex.coerceIn(0, (shape.lastIndex).coerceAtLeast(0))
        }
        // Pre-compute the cumulative distance to each maneuver's
        // anchor point, so [onLocation] can compare it directly
        // against the rider's projected progress without re-summing
        // the polyline on every tick.
        maneuverProgressM = DoubleArray(maneuverShapeIdx.size) { i ->
            GeoMath.cumulativeDistanceMeters(shape, maneuverShapeIdx[i])
        }
        maneuvers = leg.maneuvers.mapIndexed { idx, m ->
            val pt = shape.getOrNull(m.beginShapeIndex.coerceIn(0, shape.lastIndex))
                ?: doubleArrayOf(from.lat, from.lon)
            val sc = m.scoova
            val banner = sc?.banner
            val voice = sc?.voice
            ManeuverEvent(
                index = idx,
                total = leg.maneuvers.size,
                type = mapValhallaType(m.type),
                // Legacy fallback — only used when server didn't ship a
                // scoova block (older proxies, third-party engines).
                rawInstruction = m.verbalSuccinct?.takeIf { it.isNotBlank() } ?: m.instruction,
                latitude = pt[0],
                longitude = pt[1],
                segmentLengthMeters = m.length * 1000.0,
                // Pass through Valhalla's per-maneuver duration so the
                // cue scheduler can space reaffirm cues by time, not
                // distance — the only way one heuristic works across
                // pedestrian / bike / scooter / car at their wildly
                // different speeds.
                segmentDurationSeconds = m.time,
                roundaboutExit = sc?.exit ?: m.roundaboutExitCount,
                // Banner — server-rendered short copy for the eyes-on
                // visual surface. The eyes-off audio path uses the
                // full-sentence voiceFar/Mid/Near/etc fields below.
                bannerVerb = banner?.verb,
                bannerAnchor = banner?.anchor,
                // Legacy template voice fields — kept so adapters /
                // server versions without v2 full-sentence support
                // still produce something. v2 fields below take
                // precedence at speak time.
                voiceHeadsUp = voice?.headsUp,
                voiceTurnNow = voice?.turnNow,
                voiceAtLandmark = voice?.atLandmark,
                voiceGetReadyTemplate = voice?.getReadyTemplate,
                voiceAtDistanceTemplate = voice?.atDistanceTemplate,
                landmark = sc?.landmark,
                // Full-sentence v2 fields — server is the only place
                // these get composed. Client plays verbatim.
                voiceFar = voice?.far,
                voiceMid = voice?.mid,
                voiceNear = voice?.near,
                voiceChained = voice?.chained,
                voiceConfirm = voice?.confirm,
                voiceRecover = voice?.recover,
                voiceReaffirm = voice?.reaffirm,
                voiceBlocks = voice?.blocks,
                voiceCheckpoint = voice?.checkpoint,
                checkpointOffsetMeters = voice?.checkpointOffsetMeters,
                farMeters = voice?.farMeters,
                midMeters = voice?.midMeters,
                nearMeters = voice?.nearMeters,
                streetsToTurn = sc?.streetsToTurn,
                currentStreetName = sc?.currentStreetName,
                nextStreetName = sc?.nextStreetName,
            )
        }
        layer.onRoute(maneuvers)
        // Pass the decoded polyline so the NavLayer's GuidanceMonitor
        // can project the rider's GPS onto it for drift / off-route /
        // heading-mismatch detection.
        layer.setRouteShape(shape)
        // Forward the trip-level state phrases (welcome / good /
        // keepGoing / wrongWay / etc.) — the NavLayer reads these when
        // emitting confirmation cues from sensor-fusion turn-detection.
        layer.setTripState(trip.scoova?.state)
        // v2 full-sentence trip phrases — server is the only place
        // these get composed. Client speaks them verbatim.
        layer.setTripFullSentences(
            com.scoova.navlayer.core.ScoovaNavLayer.TripFullSentences(
                welcomeFull = trip.scoova?.welcomeFull,
                rerouteSearching = trip.scoova?.rerouteSearching,
                rerouteFound = trip.scoova?.rerouteFound,
                rerouteFailed = trip.scoova?.rerouteFailed,
                arrivedFull = trip.scoova?.arrivedFull,
                almostThereFull = trip.scoova?.almostThereFull,
            )
        )
        shape
    }

    /**
     * Feed a location update from your own location provider. Drives the
     * layer's progress / cue firing.
     */
    public fun onLocation(
        lat: Double,
        lon: Double,
        speedMps: Float? = null,
        bearingDeg: Float? = null,
    ) {
        if (maneuvers.isEmpty() || shape.isEmpty()) return
        // currentManeuverIdx is the index of the LAST maneuver the
        // rider has already reached or passed (-1 = haven't reached
        // any yet). nextIdx then becomes "the upcoming one to
        // execute" — clamped to the last maneuver so we don't fall
        // off the end.
        currentManeuverIdx = advanceCurrentIndex(lat, lon, currentManeuverIdx)
        val nextIdx = (currentManeuverIdx + 1).coerceIn(0, maneuvers.lastIndex)
        val nextManeuver = maneuvers[nextIdx]
        // ALONG-ROUTE distance to the next maneuver, not straight
        // line. Straight-line haversine underestimates on curved
        // routes (street grids, switchbacks) — the threshold
        // tracker then fires the "Bear right NOW" cue too early.
        // Along-route distance is what every premium nav app uses
        // for cue timing.
        val riderProgressM = GeoMath.progressAlongPolyline(lat, lon, shape)
        val distM = if (riderProgressM.isFinite() && nextIdx < maneuverProgressM.size) {
            (maneuverProgressM[nextIdx] - riderProgressM).coerceAtLeast(0.0)
        } else {
            GeoMath.haversineMeters(lat, lon, nextManeuver.latitude, nextManeuver.longitude)
        }

        // Crude ETA: scale total by remaining distance fraction
        val remainingFraction = remainingFractionFromShape(lat, lon)
        val secondsRemaining = (totalSeconds * remainingFraction).toInt()
        val metersRemaining = (totalMeters * remainingFraction).toInt()

        layer.onProgress(
            ProgressEvent(
                latitude = lat,
                longitude = lon,
                speedMps = speedMps,
                bearingDeg = bearingDeg,
                upcomingManeuverIndex = nextIdx,
                metersToUpcomingManeuver = distM,
                secondsRemaining = secondsRemaining,
                metersRemaining = metersRemaining,
            ),
        )
    }

    /** Returns the route shape so the caller can draw it on their map. */
    public fun routeShape(): List<DoubleArray> = shape

    /**
     * Returns the index of the last maneuver the rider has *physically*
     * passed — or -1 when the rider hasn't reached the first one yet.
     *
     * Implementation uses [GeoMath.progressAlongPolyline] to project
     * the rider's (lat, lon) onto the route line and read their
     * cumulative distance from start. That progress is compared
     * against the pre-computed [maneuverProgressM] table for each
     * maneuver. A maneuver counts as "passed" iff the rider's
     * progress is at least [maneuverPastPadM] (8 m) past its anchor.
     *
     * The 8 m pad is critical: vertex-snapping (the previous heuristic)
     * treated "halfway between two vertices" as "at the later vertex",
     * which flipped the upcoming-maneuver pointer prematurely. With a
     * pad, the banner stays on the current maneuver until the rider
     * is unambiguously past it.
     *
     * Once a maneuver is marked passed, it stays passed — the rider
     * never un-passes a turn. So we only consider advancement, never
     * regression. `hint` is the previous result; we walk forward
     * from it.
     */
    private fun advanceCurrentIndex(lat: Double, lon: Double, hint: Int): Int {
        if (shape.size < 2 || maneuverProgressM.isEmpty()) return hint.coerceAtLeast(-1)
        val riderProgressM = GeoMath.progressAlongPolyline(lat, lon, shape)
        if (!riderProgressM.isFinite()) return hint.coerceAtLeast(-1)
        var idx = hint.coerceAtLeast(-1)
        while (idx + 1 < maneuverProgressM.size &&
            riderProgressM >= maneuverProgressM[idx + 1] + maneuverPastPadM
        ) {
            idx++
        }
        return idx
    }

    private fun remainingFractionFromShape(lat: Double, lon: Double): Double {
        if (shape.size < 2 || totalMeters <= 0.0) return 1.0
        // Project onto the polyline (same metric as the maneuver-
        // advance code) so remaining-distance / remaining-time
        // reflect real along-route progress instead of vertex-index
        // ratio. Previous version returned (size - i) / size which
        // gave coarse jumps and could disagree with the puck's
        // visible position.
        val progressM = GeoMath.progressAlongPolyline(lat, lon, shape)
        if (!progressM.isFinite() || progressM <= 0.0) return 1.0
        val fraction = 1.0 - (progressM / totalMeters)
        return fraction.coerceIn(0.0, 1.0)
    }

    private fun mapValhallaType(t: Int): ManeuverType = when (t) {
        1, 2, 3 -> ManeuverType.Depart
        4, 5, 6 -> ManeuverType.Arrive
        7 -> ManeuverType.Becomes
        8 -> ManeuverType.Continue
        9 -> ManeuverType.SlightRight
        10 -> ManeuverType.Right
        11 -> ManeuverType.SharpRight
        12, 13 -> ManeuverType.Uturn
        14 -> ManeuverType.SharpLeft
        15 -> ManeuverType.Left
        16 -> ManeuverType.SlightLeft
        17 -> ManeuverType.RampStraight
        18 -> ManeuverType.RampRight
        19 -> ManeuverType.RampLeft
        20 -> ManeuverType.ExitRight
        21 -> ManeuverType.ExitLeft
        22 -> ManeuverType.StayStraight
        23 -> ManeuverType.StayRight
        24 -> ManeuverType.StayLeft
        25 -> ManeuverType.Merge
        26 -> ManeuverType.RoundaboutEnter
        27 -> ManeuverType.RoundaboutExit
        else -> ManeuverType.Other
    }
}
