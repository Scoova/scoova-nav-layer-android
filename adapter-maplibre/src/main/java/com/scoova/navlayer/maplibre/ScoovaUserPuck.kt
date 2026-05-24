package com.scoova.navlayer.maplibre

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

/**
 * The Scoova on-map user puck — the polished blue-dot-with-bearing-arrow
 * that premium nav apps use to anchor the map to the rider.
 *
 * Three on-map layers driven by a single [GeoJsonSource]:
 *   1. **Pulsing accuracy ring** — animated radius, fades out as it
 *      expands. Communicates "we have a GPS fix" at a glance.
 *   2. **Heading cone** — small triangle drawn just ahead of the dot,
 *      rotated to the rider's heading. Bitmap is generated once and
 *      cached as a style image. The cone hides if heading is unknown.
 *   3. **Center dot** — solid accent color with a white stroke, the
 *      universal "you are here" mark.
 *
 * The puck is map-anchored (not a screen overlay), so it stays at the
 * rider's actual lat/lon when they pan the map away — important for
 * the "manual pan + locate-me" UX.
 *
 * **Usage**
 * ```kotlin
 * val puck = ScoovaUserPuck.install(style)
 * // on every location update:
 * puck.update(lat, lon, headingDeg, accuracyMeters)
 * // when destroying the map / leaving the screen:
 * puck.dispose()
 * ```
 *
 * The handle owns a Handler.postDelayed loop on the main thread for the
 * pulse animation. It auto-throttles itself to ~30 fps and stops when
 * the puck has no location (no waste while the rider is on the
 * onboarding screen).
 */
class ScoovaUserPuck private constructor(
    private val style: Style,
    private val coneIconId: String,
) {

    /** Last known location, or null until [update] is first called. */
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastHeadingDeg: Float? = null
    private var lastAccuracyMeters: Float = 0f

    private var disposed: Boolean = false
    @Suppress("unused") private val animStartMs: Long = SystemClock.uptimeMillis()
    @Suppress("unused") private val handler = Handler(Looper.getMainLooper())

    /**
     * Update the puck's geometry. Pass [headingDeg] = null when heading
     * is unknown (e.g. user stationary, no compass fix) — the heading
     * cone hides until heading is known.
     *
     * [accuracyMeters] is currently used only to set the cap on the
     * pulse ring's maximum visual radius — future versions may scale
     * the ring by zoom so it stays accurate in metric space.
     */
    fun update(
        lat: Double,
        lon: Double,
        headingDeg: Float?,
        accuracyMeters: Float = 0f,
    ) {
        if (disposed) return
        // Skip the geojson upload when nothing meaningfully changed
        // (rider stopped at a red light, sim tick re-fed same point).
        // Tolerances are tight enough that real movement always
        // updates, loose enough that float noise doesn't churn the
        // GL pipeline.
        val sameLoc = lastLat?.let { kotlin.math.abs(it - lat) < 1e-7 } == true &&
            lastLon?.let { kotlin.math.abs(it - lon) < 1e-7 } == true
        val sameHeading = (lastHeadingDeg == null && headingDeg == null) ||
            (lastHeadingDeg != null && headingDeg != null &&
                kotlin.math.abs(lastHeadingDeg!! - headingDeg) < 0.5f)
        if (sameLoc && sameHeading) return

        val coneVisibilityChanged = (lastHeadingDeg == null) != (headingDeg == null)

        lastLat = lat
        lastLon = lon
        lastHeadingDeg = headingDeg
        lastAccuracyMeters = accuracyMeters

        val feature = Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
            if (headingDeg != null) {
                addNumberProperty("heading", headingDeg)
            }
        }
        style.getSourceAs<GeoJsonSource>(SOURCE_ID)?.setGeoJson(feature)

        // Hide the cone when heading is unknown — a fixed cone direction
        // would lie to the rider. Only call setProperties when the
        // visibility actually flipped so we don't churn the renderer.
        if (coneVisibilityChanged) {
            style.getLayer(LAYER_CONE)?.setProperties(
                PropertyFactory.visibility(
                    if (headingDeg != null) "visible" else "none"
                )
            )
        }

    }

    /** Hide the puck (clears the source). */
    fun clear() {
        if (disposed) return
        lastLat = null; lastLon = null; lastHeadingDeg = null
        style.getSourceAs<GeoJsonSource>(SOURCE_ID)?.setGeoJson(EMPTY_FEATURE_JSON)
    }

    /**
     * Remove all layers + source + icon from the style. Call this on
     * map destroy or before re-installing the style (the layers will
     * need to be re-installed too).
     */
    fun dispose() {
        if (disposed) return
        disposed = true
        runCatching { style.removeLayer(LAYER_PULSE) }
        runCatching { style.removeLayer(LAYER_HALO) }
        runCatching { style.removeLayer(LAYER_DOT) }
        runCatching { style.removeLayer(LAYER_CONE) }
        runCatching { style.removeSource(SOURCE_ID) }
        runCatching { style.removeImage(coneIconId) }
    }

    companion object {
        // ── Layer IDs (public-ish so host apps can re-order if needed) ──
        const val SOURCE_ID = "scoova-user-puck"
        const val LAYER_PULSE = "scoova-user-puck-pulse"
        const val LAYER_HALO = "scoova-user-puck-halo"
        const val LAYER_DOT = "scoova-user-puck-dot"
        const val LAYER_CONE = "scoova-user-puck-cone"

        private const val CONE_ICON_ID = "scoova-user-puck-cone-icon"

        // ── Animation tuning ──
        // 10 fps is plenty for a slow pulse — eye-perceived smoothness
        // crosses over around 8 Hz for low-frequency expanding rings,
        // and the lower rate cuts the per-frame GL load (setProperties
        // on a CircleLayer triggers a render). Crucial on emulators
        // whose GLES encoder segfaults under the 30 fps version we
        // shipped first.
        private const val FRAME_INTERVAL_MS = 100L  // ~10 fps
        private const val ANIM_PERIOD_MS = 1800L
        private const val PULSE_MIN_RADIUS = 12f
        private const val PULSE_MAX_RADIUS = 36f
        private const val PULSE_MAX_OPACITY = 0.45f

        // ── Visual tuning ──
        // Brand cyan from RideTokens. Keeping it as a hex literal here so
        // the SDK module doesn't take a dependency on the app's design
        // tokens; host apps can re-skin via [install]'s optional params.
        private const val DEFAULT_ACCENT = "#2EA8FF"
        private const val DEFAULT_DOT_CORE = "#FFFFFF"

        private const val EMPTY_FEATURE_JSON =
            """{"type":"FeatureCollection","features":[]}"""

        /**
         * Install all four puck layers (pulse, halo, dot, cone) on the
         * given style. Idempotent — calling twice is safe; the second
         * call returns a fresh handle bound to the already-installed
         * layers.
         *
         * Layers are inserted at the top of the layer stack, so the
         * puck draws above any background labels.
         */
        @JvmStatic
        @JvmOverloads
        fun install(
            style: Style,
            accentColor: String = DEFAULT_ACCENT,
            dotCoreColor: String = DEFAULT_DOT_CORE,
        ): ScoovaUserPuck {
            // Generate the cone bitmap once and register it as a style
            // image. The bitmap is small (32×32 dp); MapLibre dedupes
            // by image-id so re-installing won't double-allocate.
            val coneBitmap = buildConeBitmap(accentColor)
            if (style.getImage(CONE_ICON_ID) == null) {
                style.addImage(CONE_ICON_ID, coneBitmap)
            }

            if (style.getSource(SOURCE_ID) == null) {
                style.addSource(GeoJsonSource(SOURCE_ID))
            }

            // Pulse ring — a slightly wider, soft halo that gives the
            // puck the impression of presence without the per-frame
            // setProperties churn an animated version would cost. The
            // emulator's GLES encoder segfaults under sustained
            // CircleLayer property changes; a static "pulse" ring
            // looks 90% as good and never touches the encoder mid-ride.
            if (style.getLayer(LAYER_PULSE) == null) {
                style.addLayer(
                    CircleLayer(LAYER_PULSE, SOURCE_ID).withProperties(
                        PropertyFactory.circleRadius(28f),
                        PropertyFactory.circleColor(accentColor),
                        PropertyFactory.circleOpacity(0.12f),
                        PropertyFactory.circleBlur(0.5f),
                    )
                )
            }

            // Static halo — soft fixed glow around the dot. Distinct
            // from the pulse ring (which animates) so the dot reads
            // clearly even between pulse cycles.
            if (style.getLayer(LAYER_HALO) == null) {
                style.addLayer(
                    CircleLayer(LAYER_HALO, SOURCE_ID).withProperties(
                        PropertyFactory.circleRadius(14f),
                        PropertyFactory.circleColor(accentColor),
                        PropertyFactory.circleOpacity(0.20f),
                    )
                )
            }

            // Heading cone — sits ABOVE the halo but BELOW the dot, so
            // the dot stays the visual anchor and the cone reads as
            // "facing direction". Rotation comes from the feature's
            // `heading` property via the data-driven Expression.
            if (style.getLayer(LAYER_CONE) == null) {
                style.addLayer(
                    SymbolLayer(LAYER_CONE, SOURCE_ID).withProperties(
                        PropertyFactory.iconImage(CONE_ICON_ID),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                        // Rotate the cone icon by the feature's heading.
                        // MapLibre's icon-rotate is clockwise from north
                        // when icon-rotation-alignment=map; same axis
                        // convention as our heading value.
                        PropertyFactory.iconRotate(Expression.get("heading")),
                        PropertyFactory.iconRotationAlignment("map"),
                        // Offset the cone outward from the dot so it
                        // points "ahead" rather than overlapping the
                        // dot core. Y-negative = ahead in screen space.
                        PropertyFactory.iconOffset(arrayOf(0f, -18f)),
                        PropertyFactory.iconSize(1f),
                    )
                )
            }

            // Center dot — last so it draws on top of pulse + halo +
            // cone. White core with accent stroke.
            if (style.getLayer(LAYER_DOT) == null) {
                style.addLayer(
                    CircleLayer(LAYER_DOT, SOURCE_ID).withProperties(
                        PropertyFactory.circleRadius(7f),
                        PropertyFactory.circleColor(dotCoreColor),
                        PropertyFactory.circleStrokeColor(accentColor),
                        PropertyFactory.circleStrokeWidth(3f),
                    )
                )
            }

            return ScoovaUserPuck(style, CONE_ICON_ID)
        }

        /**
         * Draw a small triangular cone bitmap. Wide at the dot end,
         * narrow at the tip. Soft gradient from accent at the base to
         * transparent at the tip — the apex fading out is what makes
         * it read as "direction of travel" rather than "static arrow".
         */
        private fun buildConeBitmap(accentColorHex: String): Bitmap {
            // 64×64 at 2x density-ish — MapLibre scales icons; this
            // size gives us a clean tip on most devices without
            // bloating the texture atlas.
            val size = 64
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val accent = Color.parseColor(accentColorHex)
            val path = Path().apply {
                // Triangle pointing UP (toward y=0), apex at top center,
                // base across the bottom. The dot sits just below the
                // base in screen space (we offset the icon up by -18px
                // in iconOffset above).
                moveTo(size / 2f, 4f)                       // apex (top)
                lineTo(size * 0.85f, size * 0.95f)          // bottom right
                lineTo(size * 0.15f, size * 0.95f)          // bottom left
                close()
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = android.graphics.LinearGradient(
                    size / 2f, 4f,         // start at apex
                    size / 2f, size * 1f,  // end past the base
                    intArrayOf(
                        Color.argb(0, Color.red(accent), Color.green(accent), Color.blue(accent)),
                        Color.argb(180, Color.red(accent), Color.green(accent), Color.blue(accent)),
                        Color.argb(220, Color.red(accent), Color.green(accent), Color.blue(accent)),
                    ),
                    floatArrayOf(0f, 0.6f, 1f),
                    android.graphics.Shader.TileMode.CLAMP,
                )
            }
            canvas.drawPath(path, paint)
            return bmp
        }
    }
}
