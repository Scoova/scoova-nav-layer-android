package com.scoova.navlayer.maplibre

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.scoova.navlayer.core.GeoMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.Style

/**
 * The Scoova map view — drop-in MapLibre wrapper that owns the
 * AndroidView lifecycle, style loading (with font + locale rewriting),
 * camera follow-mode with throttling, gesture detection, and
 * installation of the Scoova user puck + route + destination pin +
 * search-pin layers.
 *
 * **Why this exists** — every Scoova SDK consumer needs the same
 * MapLibre boilerplate. Without this, a host app rebuilds 300+ lines
 * of plumbing to attach a Scoova ride. With it, the consumer writes:
 *
 * ```kotlin
 * ScoovaMapView(
 *     styleUrl = "https://tiles.scoo-va.info/styles/scoova-dark/style.json",
 *     userLat = origin.lat,
 *     userLon = origin.lon,
 *     userHeadingDeg = nav.headingDeg.value,
 *     route = state.routeShape,
 *     destLat = destination.lat,
 *     destLon = destination.lon,
 *     followUser = followMode,
 *     onMapTap = { lat, lon -> vm.planRouteTo(lat, lon, "Tapped") },
 *     onPoiTap = { vm.planRouteTo(it.lat, it.lon, it.name ?: "POI") },
 * )
 * ```
 *
 * **Performance notes** — the camera path is throttled (skips updates
 * with < 3 m of movement and < 2° of bearing change within 350 ms of
 * the last frame) so a 1 Hz simulator + 10 Hz sensor heading don't
 * stack `easeCamera` animations and crash the GLES encoder on
 * fragile emulators. `tilt = 0` and the fling-velocity-disable flag
 * both stay applied for the same reason.
 *
 * Gesture detection uses MapLibre's camera-move-started reason code
 * (REASON_API_GESTURE = 1) so our own programmatic moves don't
 * trigger the `onUserGesture` callback.
 */
@Composable
public fun ScoovaMapView(
    styleUrl: String,
    modifier: Modifier = Modifier,
    userLat: Double? = null,
    userLon: Double? = null,
    userHeadingDeg: Float? = null,
    route: List<DoubleArray> = emptyList(),
    destLat: Double? = null,
    destLon: Double? = null,
    searchPins: List<DoubleArray> = emptyList(),
    followUser: Boolean = false,
    /** When true, the follow camera uses the standard 45° perspective
     *  "3D nav view". Pass false for sim-driven rides on fragile
     *  GLES backends (emulator) — flat 2D draw is cheaper and won't
     *  segfault libGLESv2_enc under sustained ticks. */
    followTiltEnabled: Boolean = true,
    /** Bumped by the host to force a recenter on the user with bearing=0. */
    recenterTick: Int = 0,
    /** OSM locale tag — drives [ScoovaStylePatcher.rewriteTextLanguage]. */
    locale: String = "en-US",
    /** Persona's path-highlight bucket — drives
     *  [ScoovaStylePatcher.splitPathsByMode]. Defaults to [Motor] (all
     *  paths muted), which is the right baseline for callers that
     *  don't expose a persona. */
    pathMode: PathHighlightMode = PathHighlightMode.Motor,
    onMapTap: (lat: Double, lon: Double) -> Unit = { _, _ -> },
    onMapLongPress: (lat: Double, lon: Double) -> Unit = { _, _ -> },
    onUserGesture: () -> Unit = {},
    onBearingChange: (Float) -> Unit = {},
    onPoiTap: (ScoovaMapFeature) -> Unit = {},
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val holder = remember { ScoovaMapHolder() }

    // rememberUpdatedState wraps the callbacks so the listeners installed
    // once on map-ready don't capture stale closures.
    val longPressHandler = rememberUpdatedState(onMapLongPress)
    val gestureHandler = rememberUpdatedState(onUserGesture)
    val bearingHandler = rememberUpdatedState(onBearingChange)
    val poiTapHandler = rememberUpdatedState(onPoiTap)
    val tapHandler = rememberUpdatedState(onMapTap)
    val currentLocale = rememberUpdatedState(locale)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            ensureMapLibreInit(ctx)
            // Pre-style background — what the user sees BEFORE the style.json
            // and the first tile-batch render. Default is light cream which
            // reads as broken / empty; force ink black so the loading state
            // matches a dark-mode brand.
            //
            // SurfaceView renderer (textureMode default = false). MapLibre's
            // setRenderingRefreshMode is only supported on SurfaceView,
            // and WHEN_DIRTY is what we need to keep the emulator's GLES
            // encoder alive (CONTINUOUS hammers it into a SIGSEGV).
            val opts = MapLibreMapOptions.createFromAttributes(ctx)
                .foregroundLoadColor(android.graphics.Color.parseColor("#0A0A14"))
            MapView(ctx, opts).apply {
                holder.view = this
                setBackgroundColor(android.graphics.Color.parseColor("#0A0A14"))
                // WHEN_DIRTY: only render frames when the map's contents
                // change (camera move, layer property update, source
                // update). The CONTINUOUS default hammers libGLESv2_enc
                // on the Android emulator at the display refresh rate
                // and segfaults inside s_glDrawElements within 5–15 s.
                // WHEN_DIRTY keeps the camera + puck movement crisp on
                // real devices and stops the runaway draw loop the
                // encoder can't sustain.
                setRenderingRefreshMode(
                    org.maplibre.android.maps.renderer.MapRenderer.RenderingRefreshMode.WHEN_DIRTY
                )
                // Safety belt — even in CONTINUOUS mode (e.g. style
                // swap bursts), cap at 20 Hz so we never sustain
                // 60 Hz draw work on the encoder.
                setMaximumFps(20)
                onCreate(null)
                getMapAsync { map ->
                    holder.map = map
                    // Gesture stability hardening — see KDoc above for why.
                    map.uiSettings.isFlingVelocityAnimationEnabled = false
                    map.uiSettings.isIncreaseScaleThresholdWhenRotating = false
                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(userLat ?: 30.0444, userLon ?: 31.2357))
                        .zoom(14.0).build()
                    // Single tap is intentionally inert — relays the
                    // raw coordinate to [onMapTap] so the host can
                    // dismiss sheets / popovers, but doesn't run a
                    // feature-query or trigger heavy actions. Premium
                    // nav apps reserve "open something" for an
                    // intentional long-press; touch-to-open caused
                    // misfires when riders tapped through to the
                    // map while reaching for a chip.
                    map.addOnMapClickListener { ll ->
                        tapHandler.value(ll.latitude, ll.longitude); true
                    }
                    // Long press is the "open" gesture: query POI
                    // features under the finger, hand a typed POI
                    // through [onPoiTap] when one is hit, otherwise
                    // fall through to [onMapLongPress] so the host
                    // can decide (route-to-here, drop pin, etc.).
                    map.addOnMapLongClickListener { ll ->
                        val screen = map.projection.toScreenLocation(ll)
                        val feat = queryScoovaFeature(
                            map = map,
                            screenPoint = screen,
                            localeTag = currentLocale.value,
                        )
                        if (feat != null) poiTapHandler.value(feat)
                        else longPressHandler.value(ll.latitude, ll.longitude)
                        true
                    }
                    // Manual-gesture detach. REASON_API_GESTURE = 1 in
                    // MapLibre Android 11.x; programmatic moves report
                    // REASON_DEVELOPER_ANIMATION / REASON_API_ANIMATION
                    // and don't trigger the detach.
                    map.addOnCameraMoveStartedListener { reason ->
                        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                            gestureHandler.value()
                        }
                    }
                    map.addOnCameraMoveListener {
                        bearingHandler.value(map.cameraPosition.bearing.toFloat())
                    }
                    loadAndApplyStyle(map, holder, styleUrl, locale, pathMode,
                        userLat, userLon, userHeadingDeg, route, destLat, destLon, searchPins)
                }
            }
        },
        update = { _ ->
            val map = holder.map ?: return@AndroidView
            // Reload the style when any input that bakes into it changed:
            //   • style URL (host swapped Dark/Light/Satellite)
            //   • locale (text-field is rewritten at style-load time)
            //   • path-highlight mode (the per-persona lane palette is
            //     baked into three subclass-filtered layers at load time)
            if (styleUrl != holder.currentStyleUrl
                || locale != holder.currentLocale
                || pathMode != holder.currentPathMode) {
                holder.styleReady = false
                loadAndApplyStyle(map, holder, styleUrl, locale, pathMode,
                    userLat, userLon, userHeadingDeg, route, destLat, destLon, searchPins)
                return@AndroidView
            }
            // Locate-me tap — re-center on the user, reset bearing to 0,
            // clear throttle so the next follow tick can't skip.
            if (recenterTick != holder.lastRecenterTick) {
                holder.lastRecenterTick = recenterTick
                holder.centeredOnUser = false
                holder.lastCameraAtMs = 0
                if (userLat != null && userLon != null) {
                    map.easeCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(LatLng(userLat, userLon))
                                .zoom(17.0).tilt(0.0)
                                .bearing((userHeadingDeg ?: 0f).toDouble())
                                .build(),
                        ),
                        300,
                    )
                }
            }
            val styleObj = map.style?.takeIf { it.isFullyLoaded } ?: return@AndroidView
            if (!holder.styleReady) return@AndroidView
            applyMapData(styleObj, holder, userLat, userLon, userHeadingDeg, route, destLat, destLon, searchPins)
            applyCamera(map, holder, userLat, userLon, userHeadingDeg, route, followUser, followTiltEnabled, animate = true)
        },
    )

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> holder.view?.onStart()
                Lifecycle.Event.ON_RESUME -> holder.view?.onResume()
                Lifecycle.Event.ON_PAUSE -> holder.view?.onPause()
                Lifecycle.Event.ON_STOP -> holder.view?.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    holder.puck?.dispose(); holder.puck = null
                    holder.route?.dispose(); holder.route = null
                    holder.dest?.dispose(); holder.dest = null
                    holder.searchPins?.dispose(); holder.searchPins = null
                    runCatching { holder.view?.onDestroy() }
                    holder.view = null; holder.map = null
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

// ───────────────────────── Internals ──────────────────────────────────

private val styleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

private fun ensureMapLibreInit(ctx: Context) {
    MapLibre.getInstance(ctx.applicationContext)
}

private class ScoovaMapHolder {
    var view: MapView? = null
    var map: MapLibreMap? = null
    // Last camera state we *actually applied* — throttle bookkeeping.
    var lastCameraLat: Double = 0.0
    var lastCameraLon: Double = 0.0
    var lastCameraBearing: Double = 0.0
    var lastCameraAtMs: Long = 0
    var styleReady: Boolean = false
    var lastShapeHash: Int = 0
    /** Whether the last route-bounds fit ran while the camera was
     *  in follow mode. Used so we re-fit exactly once when the host
     *  transitions from follow (Plan with no route, or Ride) into
     *  non-follow (Plan with route, or sim) — the UI bottom-height
     *  changes between modes so a previous fit goes stale. */
    var lastFitFollow: Boolean = true
    var centeredOnUser: Boolean = false
    var currentStyleUrl: String? = null
    var currentLocale: String? = null
    var currentPathMode: PathHighlightMode? = null
    var lastRecenterTick: Int = 0
    // Layer handles — disposed on style swap and on destroy.
    var puck: ScoovaUserPuck? = null
    var route: ScoovaRouteLayer? = null
    var dest: ScoovaDestinationPin? = null
    var searchPins: ScoovaPinLayer? = null
}

private fun loadAndApplyStyle(
    map: MapLibreMap,
    holder: ScoovaMapHolder,
    styleUrl: String,
    locale: String,
    pathMode: PathHighlightMode,
    userLat: Double?, userLon: Double?, userHeadingDeg: Float?,
    route: List<DoubleArray>,
    destLat: Double?, destLon: Double?,
    searchPins: List<DoubleArray>,
) {
    styleScope.launch {
        val patched = runCatching {
            ScoovaStylePatcher.loadAndPatch(
                url = styleUrl, locale = locale, mode = pathMode)
        }.getOrNull()
        val builder = if (patched != null) Style.Builder().fromJson(patched)
                      else Style.Builder().fromUri(styleUrl)
        map.setStyle(builder) { style ->
            holder.route?.dispose()
            holder.puck?.dispose()
            holder.dest?.dispose()
            holder.searchPins?.dispose()
            holder.route = ScoovaRouteLayer.install(style)
            holder.puck = ScoovaUserPuck.install(style)
            holder.dest = ScoovaDestinationPin.install(style)
            holder.searchPins = ScoovaPinLayer.install(style = style, namespace = "search")
            applyMapData(style, holder, userLat, userLon, userHeadingDeg, route, destLat, destLon, searchPins)
            applyCamera(map, holder, userLat, userLon, userHeadingDeg, route, followUser = false, tiltEnabled = false, animate = false)
            holder.styleReady = true
            holder.currentStyleUrl = styleUrl
            holder.currentLocale = locale
            holder.currentPathMode = pathMode
        }
    }
}

private fun applyMapData(
    @Suppress("UNUSED_PARAMETER") style: Style,
    holder: ScoovaMapHolder,
    userLat: Double?, userLon: Double?, userHeadingDeg: Float?,
    route: List<DoubleArray>,
    destLat: Double?, destLon: Double?,
    searchPins: List<DoubleArray>,
) {
    holder.route?.setShape(route)

    val puck = holder.puck
    if (puck != null) {
        if (userLat != null && userLon != null) {
            puck.update(userLat, userLon, userHeadingDeg)
        } else {
            puck.clear()
        }
    }

    val dest = holder.dest
    if (dest != null) {
        if (destLat != null && destLon != null) {
            dest.setLocation(destLat, destLon)
        } else {
            dest.clear()
        }
    }

    holder.searchPins?.setPoints(searchPins)
}

private fun applyCamera(
    map: MapLibreMap,
    holder: ScoovaMapHolder,
    userLat: Double?, userLon: Double?, userHeadingDeg: Float?,
    route: List<DoubleArray>,
    followUser: Boolean,
    tiltEnabled: Boolean,
    animate: Boolean,
) {
    if (followUser && userLat != null && userLon != null) {
        val now = System.currentTimeMillis()
        val prevAtMs = holder.lastCameraAtMs  // capture BEFORE we update
        val moved = GeoMath.haversineMeters(
            holder.lastCameraLat, holder.lastCameraLon, userLat, userLon,
        )
        val targetBearing = (userHeadingDeg ?: 0f).toDouble()
        val bearingDelta = run {
            var d = (targetBearing - holder.lastCameraBearing) % 360.0
            if (d > 180) d -= 360.0; if (d < -180) d += 360.0
            kotlin.math.abs(d)
        }
        // Camera-update gating matches the existing Scoova reference
        // implementation (RideMap.kt in scoova_app): bearing delta
        // ≥ 2°, zoom delta ≥ 0.25, plus a 350 ms staleness window so
        // we always re-anchor if the rider's been off-camera too long.
        val isStale = (now - prevAtMs) > 350
        val significant = moved >= 1.0 || bearingDelta >= 2.0
        if (!significant && !isStale) return
        holder.lastCameraLat = userLat
        holder.lastCameraLon = userLon
        holder.lastCameraBearing = targetBearing
        holder.lastCameraAtMs = now
        // The 3D pitch is a NAVIGATION affordance only — zoom 19 + 65°
        // tilt while actively navigating. Everywhere else (the Plan
        // screen, route preview) the map stays flat, tilt 0°, looking
        // straight down — a tilted map with no active route is just
        // disorienting.
        val zoom = if (tiltEnabled) 19.0 else 18.0
        val tiltDeg = if (tiltEnabled) 65.0 else 0.0
        val pos = CameraPosition.Builder()
            .target(LatLng(userLat, userLon))
            .zoom(zoom)
            .tilt(tiltDeg)
            .bearing(targetBearing)
            .build()
        // 450 ms easeCamera matches the reference's follow animation;
        // for back-to-back ticks within < 450 ms the previous anim
        // is still running so MapLibre interpolates the chained
        // requests smoothly.
        if (animate) {
            map.easeCamera(CameraUpdateFactory.newCameraPosition(pos), 450)
        } else {
            map.cameraPosition = pos
        }
        return
    }
    if (route.isNotEmpty()) {
        // Re-fit when:
        //   1. The route shape itself changed (fresh plan / reroute), OR
        //   2. We re-entered "not following" mode after the last fit
        //      (Plan→Ride sim transition — surrounding UI height
        //      changes, so the original fit no longer frames the route).
        // Without case 2 the destination ends up off-screen after the
        // rider taps Simulate, because the Plan-phase fit's padding
        // assumed a different bottom-card height than the Ride-phase
        // ETA card. We track lastFitFollow so the re-fit only fires
        // once per follow-state flip, not on every recomposition.
        val hash = shapeHash(route)
        val shouldFit = hash != holder.lastShapeHash ||
            (!followUser && holder.lastFitFollow)
        if (shouldFit) {
            holder.lastShapeHash = hash
            holder.lastFitFollow = followUser
            val bounds = LatLngBounds.Builder().apply {
                route.forEach { include(LatLng(it[0], it[1])) }
            }.build()
            // Padding is in PIXELS (not dp). Scale by the display
            // density so 3× screens (most modern Android handsets) get
            // a proportional inset matching the rough dp dimensions
            // of the Plan screen's top + bottom UI: ~180 dp on top
            // (brand row + search + chips) and ~280 dp on bottom
            // (avoid chips + preview card + tab bar clearance).
            // Without the density multiplier, a 380 px top inset
            // becomes ~127 dp on a 3× screen — small enough that the
            // route ends up partially hidden behind the bottom card.
            val density = map.cameraPosition.let {
                holder.view?.context?.resources?.displayMetrics?.density ?: 1f
            }
            val left = (40f * density).toInt()
            val top = (180f * density).toInt()
            val right = (40f * density).toInt()
            val bottom = (280f * density).toInt()
            map.easeCamera(
                CameraUpdateFactory.newLatLngBounds(bounds, left, top, right, bottom),
                600,
            )
        }
        return
    }
    if (!holder.centeredOnUser && userLat != null && userLon != null) {
        holder.centeredOnUser = true
        map.cameraPosition = CameraPosition.Builder()
            .target(LatLng(userLat, userLon)).zoom(15.0).build()
    }
}

private fun shapeHash(shape: List<DoubleArray>): Int {
    if (shape.isEmpty()) return 0
    val f = shape.first(); val l = shape.last()
    return (f[0].toRawBits() xor f[1].toRawBits() xor l[0].toRawBits() xor
        l[1].toRawBits() xor shape.size.toLong()).toInt()
}
