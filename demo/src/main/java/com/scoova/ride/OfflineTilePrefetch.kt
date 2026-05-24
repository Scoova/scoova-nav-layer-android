package com.scoova.ride

import android.content.Context
import android.util.Log
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineGeometryRegionDefinition
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Route-corridor tile pre-fetch — the "survive a mid-ride drop" half of
 * Scoova's offline story.
 *
 * **The problem.** MapLibre keeps an ambient cache of tiles it has
 * already fetched, so areas the rider has *seen* still render after the
 * connection drops. But tiles AHEAD of the rider — the rest of the
 * route — were never fetched, so as they move forward the map goes
 * blank. Turn-by-turn voice keeps working (the maneuvers are in
 * memory) but the rider loses the visual.
 *
 * **The fix.** The moment a route is computed (while still online), we
 * hand its polyline to MapLibre's [OfflineManager] as an
 * [OfflineGeometryRegionDefinition] — a LineString that MapLibre
 * buffers into a corridor and downloads tile-by-tile into its offline
 * database. When the signal drops mid-ride the whole route corridor is
 * already on disk and the map renders the entire way.
 *
 * **Why this is cheap for Scoova specifically.** The tiles are served
 * from Scoova's own `tiles.scoo-va.info` (out of PMTiles archives) —
 * no third-party per-tile cost, no licensing restriction on bulk
 * download. We can pre-fetch as much corridor as we like.
 *
 * **Zoom range.** 11–16. Below 11 the rider would never zoom out that
 * far mid-ride; above 16 the tile count explodes on a long route and
 * the nav camera (zoom 18-19) simply over-zooms the cached z16 tile —
 * slightly soft, never blank. The vector tiles are style-independent
 * (`/v1/{z}/{x}/{y}.mvt` is the same bytes for Dark / Light /
 * Satellite), so one download covers every map style.
 */
object OfflineTilePrefetch {

    private const val TAG = "ScoovaOfflinePrefetch"

    /** Metadata marker so we can find + delete our own corridor regions
     *  on the next route without disturbing anything else. */
    private const val META_MARKER = "scoova-route-corridor"

    private const val MIN_ZOOM = 11.0
    private const val MAX_ZOOM = 16.0

    /**
     * Pre-fetch the tile corridor for [routeShape] (list of `[lat, lon]`
     * pairs). Safe to call on every route computation — the previous
     * corridor region is deleted first so the offline DB doesn't grow
     * unbounded. No-op for routes shorter than 2 points.
     *
     * @param styleUrl any Scoova style URL — the corridor's vector
     *   tiles are shared across styles, so the choice only affects the
     *   (tiny) style.json / sprite / glyph download.
     */
    fun prefetchCorridor(
        context: Context,
        styleUrl: String,
        routeShape: List<DoubleArray>,
    ) {
        if (routeShape.size < 2) return
        val appCtx = context.applicationContext
        // OfflineManager needs MapLibre initialised — idempotent, the
        // map view normally does this first, but a prefetch could race
        // ahead on a cold start.
        runCatching { MapLibre.getInstance(appCtx) }
        val manager = OfflineManager.getInstance(appCtx)
        val pixelRatio = appCtx.resources.displayMetrics.density

        // Delete any prior corridor region, THEN create the new one in
        // the callback so we never hold two corridors at once.
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(regions: Array<OfflineRegion>?) {
                regions
                    ?.filter { region ->
                        runCatching { String(region.metadata) }
                            .getOrNull()
                            ?.contains(META_MARKER) == true
                    }
                    ?.forEach { stale ->
                        stale.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                            override fun onDelete() {}
                            override fun onError(error: String) {
                                Log.w(TAG, "stale region delete failed: $error")
                            }
                        })
                    }
                createRegion(manager, styleUrl, routeShape, pixelRatio)
            }

            override fun onError(error: String) {
                // Couldn't enumerate — still create the new one; worst
                // case the DB carries an extra stale corridor.
                Log.w(TAG, "listOfflineRegions failed: $error")
                createRegion(manager, styleUrl, routeShape, pixelRatio)
            }
        })
    }

    private fun createRegion(
        manager: OfflineManager,
        styleUrl: String,
        routeShape: List<DoubleArray>,
        pixelRatio: Float,
    ) {
        // GeoJSON wants [lon, lat]; our route shape is [lat, lon].
        val line = LineString.fromLngLats(
            routeShape.map { Point.fromLngLat(it[1], it[0]) },
        )
        val definition = OfflineGeometryRegionDefinition(
            styleUrl,
            line,
            MIN_ZOOM,
            MAX_ZOOM,
            pixelRatio,
        )
        val metadata = META_MARKER.toByteArray()
        manager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    // Fire-and-forget. The download runs on MapLibre's
                    // own thread; tiles land in the shared offline DB
                    // and the live map reads from it transparently when
                    // the network is gone.
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    Log.i(TAG, "corridor download started (${routeShape.size} pts)")
                }

                override fun onError(error: String) {
                    Log.w(TAG, "createOfflineRegion failed: $error")
                }
            },
        )
    }
}
