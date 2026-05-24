package com.scoova.ride

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.scoova.navlayer.maplibre.PathHighlightMode
import com.scoova.navlayer.maplibre.ScoovaMapFeature
import com.scoova.navlayer.maplibre.ScoovaMapView

/**
 * Demo-side map composable. A paper-thin wrapper around the SDK's
 * [com.scoova.navlayer.maplibre.ScoovaMapView] — exists so the rest
 * of the demo can keep using a typed [RideMapData] / [MapStyleChoice]
 * shape while the heavy lifting (AndroidView lifecycle, gesture
 * detection, follow-mode camera math, layer install) lives in the SDK.
 *
 * Host apps that don't need a typed data class can call
 * [ScoovaMapView] directly with the field-level params.
 */
data class RideMapData(
    val userLat: Double? = null,
    val userLon: Double? = null,
    val destLat: Double? = null,
    val destLon: Double? = null,
    val routeShape: List<DoubleArray> = emptyList(),
    val followUser: Boolean = false,
    val bearingDeg: Float = 0f,
    /** Search-result pins. Each entry is `[lat, lon]`. */
    val searchPins: List<DoubleArray> = emptyList(),
)

/**
 * The three map looks available to the user on the Map tab. Cycle
 * order is Dark → Light → Satellite → Dark — matches what users
 * expect from Apple Maps / Google Maps style toggles.
 */
enum class MapStyleChoice(val styleUrl: String, val display: String, val emoji: String) {
    // Styles load through the keyed gateway; the raw tiles subdomain is firewalled.
    Dark("${ScoovaApi.GATEWAY}/tiles/styles/scoova-dark/style.json?api_key=${ScoovaApi.KEY}", "Dark", "🌙"),
    Light("${ScoovaApi.GATEWAY}/tiles/styles/scoova-default/style.json?api_key=${ScoovaApi.KEY}", "Light", "☀️"),
    Satellite("${ScoovaApi.GATEWAY}/tiles/styles/scoova-satellite/style.json?api_key=${ScoovaApi.KEY}", "Satellite", "🛰");

    fun next(): MapStyleChoice = entries[(ordinal + 1) % entries.size]
}

@Composable
fun RideMap(
    modifier: Modifier = Modifier,
    data: RideMapData,
    style: MapStyleChoice = MapStyleChoice.Dark,
    recenterTick: Int = 0,
    /**
     * Toggle the 45° 3D tilt on the follow camera. Default false —
     * Plan, route preview, history, insights and summary maps all
     * stay top-down 2D so the rider can read the city as a map.
     *
     * The only place this should be true is during active turn-by-turn
     * navigation (RideScreen), where the 3D nav perspective makes the
     * upcoming road geometry easier to read at a glance.
     */
    followTiltEnabled: Boolean = false,
    locale: String = "en-US",
    /** Persona's path-highlight bucket. Plumbed to the SDK so the
     *  per-persona lane palette (cyan cycleways for bike/scooter,
     *  amber footways for foot, muted for motor) bakes into the style
     *  at load time. Defaults to [Motor] for non-persona surfaces
     *  (e.g. history map). */
    pathMode: PathHighlightMode = PathHighlightMode.Motor,
    onMapTap: (Double, Double) -> Unit = { _, _ -> },
    onMapLongPress: (Double, Double) -> Unit = { _, _ -> },
    onUserGesture: () -> Unit = {},
    onBearingChange: (Float) -> Unit = {},
    onPoiTap: (ScoovaMapFeature) -> Unit = {},
) {
    ScoovaMapView(
        styleUrl = style.styleUrl,
        modifier = modifier,
        userLat = data.userLat,
        userLon = data.userLon,
        // Pass heading only when meaningful; the SDK hides the puck's
        // cone when null (rather than pointing in a fake direction).
        userHeadingDeg = if (data.bearingDeg != 0f) data.bearingDeg else null,
        route = data.routeShape,
        destLat = data.destLat,
        destLon = data.destLon,
        searchPins = data.searchPins,
        followUser = data.followUser,
        followTiltEnabled = followTiltEnabled,
        recenterTick = recenterTick,
        locale = locale,
        pathMode = pathMode,
        onMapTap = onMapTap,
        onMapLongPress = onMapLongPress,
        onUserGesture = onUserGesture,
        onBearingChange = onBearingChange,
        onPoiTap = onPoiTap,
    )
}
