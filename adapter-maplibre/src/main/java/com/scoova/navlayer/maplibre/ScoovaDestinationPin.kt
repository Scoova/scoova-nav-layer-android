package com.scoova.navlayer.maplibre

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * On-map destination marker — the orange dot the rider is heading to.
 *
 * Two layers driven by one [GeoJsonSource]:
 *   1. **Halo** — soft outer ring so the pin still reads on the dark
 *      basemap and against satellite imagery.
 *   2. **Pin** — solid accent dot with a white stroke.
 *
 * Kept distinct from [ScoovaUserPuck] (no animation, no heading cone)
 * so the rider doesn't confuse "where I am" with "where I'm going".
 *
 * **Usage**
 * ```kotlin
 * val pin = ScoovaDestinationPin.install(style)
 * pin.setLocation(lat, lon)
 * pin.clear()
 * pin.dispose()
 * ```
 */
class ScoovaDestinationPin private constructor(
    private val style: Style,
) {

    private var disposed: Boolean = false
    private var lastLat: Double = Double.NaN
    private var lastLon: Double = Double.NaN

    fun setLocation(lat: Double, lon: Double) {
        if (disposed) return
        // The destination almost never moves during a ride — caching
        // avoids re-uploading the same point geojson on every tick.
        if (lat == lastLat && lon == lastLon) return
        lastLat = lat; lastLon = lon
        style.getSourceAs<GeoJsonSource>(SOURCE_ID)
            ?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(lon, lat)))
    }

    fun clear() {
        if (disposed) return
        if (lastLat.isNaN() && lastLon.isNaN()) return  // already cleared
        lastLat = Double.NaN; lastLon = Double.NaN
        style.getSourceAs<GeoJsonSource>(SOURCE_ID)
            ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        runCatching { style.removeLayer(LAYER_PIN) }
        runCatching { style.removeLayer(LAYER_HALO) }
        runCatching { style.removeSource(SOURCE_ID) }
    }

    companion object {
        const val SOURCE_ID = "scoova-destination"
        const val LAYER_HALO = "scoova-destination-halo"
        const val LAYER_PIN = "scoova-destination-pin"

        // Scoova brand orange — distinct from the cyan accent used for
        // user/route so the rider can tell "you are here" from "go
        // here" at a glance.
        private const val DEFAULT_ACCENT = "#FF6A00"
        private const val DEFAULT_STROKE = "#FFFFFF"

        @JvmStatic
        @JvmOverloads
        fun install(
            style: Style,
            accentColor: String = DEFAULT_ACCENT,
            strokeColor: String = DEFAULT_STROKE,
        ): ScoovaDestinationPin {
            if (style.getSource(SOURCE_ID) == null) {
                style.addSource(GeoJsonSource(SOURCE_ID))
            }
            if (style.getLayer(LAYER_HALO) == null) {
                style.addLayer(
                    CircleLayer(LAYER_HALO, SOURCE_ID).withProperties(
                        PropertyFactory.circleRadius(16f),
                        PropertyFactory.circleColor(accentColor),
                        PropertyFactory.circleOpacity(0.22f),
                    )
                )
            }
            if (style.getLayer(LAYER_PIN) == null) {
                style.addLayer(
                    CircleLayer(LAYER_PIN, SOURCE_ID).withProperties(
                        PropertyFactory.circleRadius(8f),
                        PropertyFactory.circleColor(accentColor),
                        PropertyFactory.circleStrokeColor(strokeColor),
                        PropertyFactory.circleStrokeWidth(3f),
                    )
                )
            }
            return ScoovaDestinationPin(style)
        }
    }
}
