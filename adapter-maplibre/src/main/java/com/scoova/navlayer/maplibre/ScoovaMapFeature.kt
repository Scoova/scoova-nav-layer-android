package com.scoova.navlayer.maplibre

import android.graphics.PointF
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.geojson.Point

/**
 * A rendered feature the rider tapped on (POI, named road, place).
 * Surfaces the locale-aware name, the OpenMapTiles class/subclass for
 * categorisation, and the latitude/longitude so a host app can
 * immediately plan a route or pin the spot.
 */
public data class ScoovaMapFeature(
    /** Localised display name; falls back to romanised then the OSM `name` tag. */
    public val name: String?,
    /** OpenMapTiles `class` — e.g. "restaurant", "hospital", "highway". */
    public val featureClass: String?,
    /** OpenMapTiles `subclass` — e.g. "cafe", "fuel". Null on transportation. */
    public val subclass: String?,
    public val lat: Double,
    public val lon: Double,
    /** Which OMT source-layer this came from. Useful for icon picking. */
    public val sourceLayer: String,
)

/**
 * Layer-id sets matching the published Scoova styles.
 *
 * Exposed publicly so a host app can extend or swap them if its own
 * style adds custom layers (e.g. parks, transit stops).
 */
public object ScoovaMapFeatureLayers {
    public val Poi: Array<String> = arrayOf(
        "poi-hospital", "poi-restaurant", "poi-hotel", "poi-school",
        "poi-park", "poi-shop", "poi-transit", "poi-parking", "poi-amenity",
    )
    public val Place: Array<String> = arrayOf(
        "place_other", "place_village", "place_town", "place_city",
    )
    public val Road: Array<String> = arrayOf("road_label")

    /** Everything taps on a basemap normally hit. POIs first so a
     *  café on a road wins over the road itself. */
    public val Tappable: Array<String> = Poi + Place + Road
}

/**
 * Query the rendered features at a screen point and return the
 * closest POI / place / road we recognise. Returns `null` if the tap
 * landed on empty basemap.
 *
 * Implementation notes:
 *   • `queryRenderedFeatures` returns features in z-order. We accept
 *     the first hit — that's whatever was rendered on top, which is
 *     usually what the rider was visually aiming at.
 *   • Name resolution mirrors the style's `text-field` rewrite:
 *     prefer `name:<locale-base>`, fall back to `name:latin`, then the
 *     raw OSM `name` tag if present.
 *   • Coordinates come from the feature's geometry — point features
 *     pass through unchanged, lines/polygons collapse to their first
 *     coordinate. Good enough for "route to this thing" UX.
 *
 * @param tapTolerancePx widen the hit-test box to make taps less
 *   fussy on small icons (default 12 dp ~ 36 px on a 3x device).
 */
@JvmOverloads
public fun queryScoovaFeature(
    map: MapLibreMap,
    screenPoint: PointF,
    localeTag: String = "en-US",
    layerIds: Array<String> = ScoovaMapFeatureLayers.Tappable,
    tapTolerancePx: Float = 18f,
): ScoovaMapFeature? {
    val box = android.graphics.RectF(
        screenPoint.x - tapTolerancePx,
        screenPoint.y - tapTolerancePx,
        screenPoint.x + tapTolerancePx,
        screenPoint.y + tapTolerancePx,
    )
    val features = map.queryRenderedFeatures(box, *layerIds)
    val feature = features.firstOrNull() ?: return null

    val base = localeTag.substringBefore('-').lowercase()
    fun str(k: String) = feature.getStringProperty(k)?.takeIf { it.isNotBlank() }
    val name = str("name:$base")
        ?: str("name:latin")
        ?: str("name")
        ?: str("name:en")

    val geom = feature.geometry()
    val (lat, lon) = when (geom) {
        is Point -> geom.latitude() to geom.longitude()
        else -> {
            // Lines, polygons, multi-geometries — pull the first point.
            val pt = (geom?.toJson() ?: "").let { runCatching {
                org.json.JSONObject(it)
                    .optJSONArray("coordinates")
                    ?.let { c ->
                        // Drill down until we hit two numbers.
                        var cur: Any? = c
                        while (cur is org.json.JSONArray && cur.length() > 0 && cur.opt(0) is org.json.JSONArray) {
                            cur = cur.opt(0)
                        }
                        if (cur is org.json.JSONArray && cur.length() >= 2) {
                            cur.optDouble(1, Double.NaN) to cur.optDouble(0, Double.NaN)
                        } else null
                    }
            }.getOrNull() }
            pt ?: (Double.NaN to Double.NaN)
        }
    }
    if (lat.isNaN() || lon.isNaN()) return null

    return ScoovaMapFeature(
        name = name,
        featureClass = str("class"),
        subclass = str("subclass"),
        lat = lat,
        lon = lon,
        sourceLayer = feature.getStringProperty("source-layer")
            ?: deriveSourceLayer(feature.id() ?: "", layerIds),
    )
}

private fun deriveSourceLayer(featureId: String, layerIds: Array<String>): String {
    // queryRenderedFeatures doesn't always populate source-layer in
    // the feature properties; infer from which layer-id was queried
    // by checking the prefix of the feature's id. Fallback to "poi".
    return when {
        featureId.startsWith("place_") -> "place"
        featureId.startsWith("road_")  -> "transportation_name"
        else -> "poi"
    }
}

