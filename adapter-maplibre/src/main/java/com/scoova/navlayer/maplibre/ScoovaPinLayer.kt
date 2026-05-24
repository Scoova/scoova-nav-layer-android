package com.scoova.navlayer.maplibre

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * Generic multi-pin layer. Use it for search results, alternative
 * routes' endpoints, hazard warnings — anything you want to drop a
 * small symbol at multiple lat/lons.
 *
 * Two layers driven by one [GeoJsonSource]:
 *   1. **Halo** — soft outer ring so the pin reads on every basemap.
 *   2. **Core** — solid accent disk with a white stroke.
 *
 * Optionally each feature can carry a string `label` property; the
 * pin shows it as a small text below when [showLabels] = true. The
 * label rendering uses MapLibre's built-in symbol layer text-field —
 * font collapses through [ScoovaStylePatcher.rewriteFonts] so a
 * Scoova-served basemap renders the labels without 404ing the tile
 * server's font endpoint.
 *
 * Each handle is identified by a [namespace] string — install several
 * pin layers (search results, hazards, friends-nearby) without
 * stomping each other's source/layer IDs.
 */
public class ScoovaPinLayer private constructor(
    private val style: Style,
    private val sourceId: String,
    private val haloId: String,
    private val coreId: String,
) {
    private var disposed: Boolean = false
    /** Cached signature so setPoints short-circuits when nothing changed. */
    private var lastSignature: Long = 0L

    public fun setPoints(latLonPairs: List<DoubleArray>, labels: List<String>? = null) {
        if (disposed) return
        val src = style.getSourceAs<GeoJsonSource>(sourceId) ?: return
        val sig = if (latLonPairs.isEmpty()) 0L else {
            var h = latLonPairs.size.toLong() shl 48
            latLonPairs.forEach { h = h xor it[0].toRawBits() xor it[1].toRawBits() }
            h
        }
        if (sig == lastSignature) return
        lastSignature = sig
        if (latLonPairs.isEmpty()) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            return
        }
        val features = latLonPairs.mapIndexed { i, p ->
            Feature.fromGeometry(Point.fromLngLat(p[1], p[0])).apply {
                labels?.getOrNull(i)?.let { addStringProperty("label", it) }
            }
        }
        src.setGeoJson(FeatureCollection.fromFeatures(features.toTypedArray()))
    }

    public fun clear(): Unit = setPoints(emptyList())

    public fun dispose() {
        if (disposed) return
        disposed = true
        runCatching { style.removeLayer(coreId) }
        runCatching { style.removeLayer(haloId) }
        runCatching { style.removeSource(sourceId) }
    }

    public companion object {
        @JvmStatic
        @JvmOverloads
        public fun install(
            style: Style,
            namespace: String,
            accentColor: String = "#FFCB05",   // Scoova "tag" yellow
            strokeColor: String = "#FFFFFF",
            coreRadius: Float = 6.5f,
            haloRadius: Float = 13f,
        ): ScoovaPinLayer {
            val sourceId = "scoova-pins-$namespace"
            val haloId = "scoova-pins-$namespace-halo"
            val coreId = "scoova-pins-$namespace-core"

            if (style.getSource(sourceId) == null) {
                style.addSource(GeoJsonSource(sourceId))
            }
            if (style.getLayer(haloId) == null) {
                style.addLayer(
                    CircleLayer(haloId, sourceId).withProperties(
                        PropertyFactory.circleRadius(haloRadius),
                        PropertyFactory.circleColor(accentColor),
                        PropertyFactory.circleOpacity(0.25f),
                    )
                )
            }
            if (style.getLayer(coreId) == null) {
                style.addLayer(
                    CircleLayer(coreId, sourceId).withProperties(
                        PropertyFactory.circleRadius(coreRadius),
                        PropertyFactory.circleColor(accentColor),
                        PropertyFactory.circleStrokeColor(strokeColor),
                        PropertyFactory.circleStrokeWidth(2f),
                    )
                )
            }
            return ScoovaPinLayer(style, sourceId, haloId, coreId)
        }
    }
}
