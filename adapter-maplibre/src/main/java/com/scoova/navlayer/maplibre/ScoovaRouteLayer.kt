package com.scoova.navlayer.maplibre

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * On-map route polyline — the cyan line that previews the planned ride
 * and stays painted under the rider during navigation.
 *
 * Two stacked line layers driven by one [GeoJsonSource]:
 *   1. **Casing** — thick translucent halo so the route reads on every
 *      basemap (dark, light, satellite).
 *   2. **Core** — solid accent line on top.
 *
 * Coordinates are passed in lat/lon pairs (the same shape the routing
 * client returns); the layer flips them to lon/lat internally because
 * MapLibre's `Point.fromLngLat` insists on the GeoJSON convention.
 *
 * **Usage**
 * ```kotlin
 * val route = ScoovaRouteLayer.install(style)
 * route.setShape(routingClient.lastShape)   // List<DoubleArray(lat, lon)>
 * route.clear()                              // when user cancels
 * route.dispose()                            // on map destroy
 * ```
 */
class ScoovaRouteLayer private constructor(
    private val style: Style,
) {

    private var disposed: Boolean = false
    /**
     * Cached shape signature so [setShape] can short-circuit when the
     * caller passes the same polyline twice — which the demo's
     * `applyData` does on every tick during a ride. Without this cache
     * we'd re-upload the geojson hundreds of times during one ride,
     * driving needless render work on the emulator.
     */
    private var lastShapeSignature: Long = 0L

    /**
     * Paint the route polyline. Pass an empty list to clear without
     * disposing — that path is what [clear] uses.
     *
     * Each element is a `[lat, lon]` pair; matches the format Scoova
     * Routing returns so callers can hand the shape straight through.
     */
    fun setShape(latLonPairs: List<DoubleArray>) {
        if (disposed) return
        val src = style.getSourceAs<GeoJsonSource>(SOURCE_ID) ?: return
        // Cheap structural signature: first + last point + count.
        // A route only changes via planRouteTo / reroute, so endpoints
        // moving is a strong signal. False sharing across two routes
        // with same endpoints + same length is vanishingly rare in
        // practice; the cost of a false-positive (one stale render) is
        // also tiny, so we accept the trade-off for the per-tick win.
        val sig = if (latLonPairs.isEmpty()) 0L else
            (latLonPairs.size.toLong() shl 48) xor
                latLonPairs.first()[0].toRawBits() xor
                latLonPairs.first()[1].toRawBits() xor
                latLonPairs.last()[0].toRawBits() xor
                latLonPairs.last()[1].toRawBits()
        if (sig == lastShapeSignature) return
        lastShapeSignature = sig
        if (latLonPairs.isEmpty()) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            return
        }
        val pts = latLonPairs.map { Point.fromLngLat(it[1], it[0]) }
        src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(pts)))
    }

    fun clear() = setShape(emptyList())

    fun dispose() {
        if (disposed) return
        disposed = true
        runCatching { style.removeLayer(LAYER_CORE) }
        runCatching { style.removeLayer(LAYER_CASING) }
        runCatching { style.removeSource(SOURCE_ID) }
    }

    companion object {
        const val SOURCE_ID = "scoova-route"
        const val LAYER_CASING = "scoova-route-casing"
        const val LAYER_CORE = "scoova-route-core"

        // Brand cyan, same as the puck. Held as a literal so the
        // adapter doesn't import a tokens file from the demo or UI
        // module — host apps re-skin via [install] params.
        private const val DEFAULT_ACCENT = "#2EA8FF"

        @JvmStatic
        @JvmOverloads
        fun install(
            style: Style,
            accentColor: String = DEFAULT_ACCENT,
            casingWidth: Float = 11f,
            coreWidth: Float = 7f,
            casingOpacity: Float = 0.33f,
        ): ScoovaRouteLayer {
            if (style.getSource(SOURCE_ID) == null) {
                style.addSource(GeoJsonSource(SOURCE_ID))
            }
            if (style.getLayer(LAYER_CASING) == null) {
                style.addLayer(
                    LineLayer(LAYER_CASING, SOURCE_ID).withProperties(
                        PropertyFactory.lineColor(accentColor),
                        PropertyFactory.lineWidth(casingWidth),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineOpacity(casingOpacity),
                    )
                )
            }
            if (style.getLayer(LAYER_CORE) == null) {
                style.addLayer(
                    LineLayer(LAYER_CORE, SOURCE_ID).withProperties(
                        PropertyFactory.lineColor(accentColor),
                        PropertyFactory.lineWidth(coreWidth),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    )
                )
            }
            return ScoovaRouteLayer(style)
        }
    }
}
