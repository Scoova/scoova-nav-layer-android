package com.scoova.navlayer.maplibre

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Path-rendering bucket — drives which infrastructure the map highlights
 * to the rider. Three buckets cover the five personas:
 *
 *  - [Bike]  — bicycle + scooter (lightweight two-wheel) → cycleways
 *              bright cyan, footways dimmed (you don't ride on a sidewalk).
 *  - [Foot]  — walking + running → footways bright amber, cycleways
 *              visible but dimmed (you can use them but they're not yours).
 *  - [Motor] — motorcycle + car → all paths muted grey (irrelevant to
 *              a driver; they care about roads).
 *
 * Mirrors the iOS `PathHighlightMode` enum.
 */
public enum class PathHighlightMode { Bike, Foot, Motor }

/**
 * Style.json pre-flight rewriter. Fetches a remote style, applies four
 * Scoova-specific transforms, and caches the result.
 *
 * **1. Font collapse** ([rewriteFonts])
 * Tile servers that don't synthesise font-stack URLs on the fly (most
 * of them) reply 404 to a request like
 * `/fonts/Noto Sans Bold,Noto Sans Regular/0-255.pbf`. The renderer
 * doesn't gracefully fall back to the second font — it just drops the
 * glyphs, which on Arabic / RTL text looks like every letter is
 * disconnected. Forcing every text-font chain to a single known-good
 * font (`Noto Sans Regular` on the Scoova tile server) prevents this.
 *
 * **2. 3D building extrusions** ([addBuildingExtrusions])
 * Inject a `fill-extrusion` layer for the `building` source-layer so
 * 3D footprints render when the camera tilts.
 *
 * **3. Locale-aware text-field** ([rewriteTextLanguage])
 * OpenMapTiles vector tiles ship a `name:<lang>` property per feature
 * (e.g. `name:ar`, `name:fr`, `name:latin`) but NOT a plain `name`
 * column on POI / transportation_name / place. Published styles
 * typically hardcode `{name:latin}`. We rewrite every text-field to a
 * `coalesce` expression that prefers the rider's locale (with full
 * region tag → base language → latin fallback) so a feature with
 * `name:ar-EG` renders Egyptian Arabic on an Egyptian-Arabic locale.
 *
 * **4. Mode-aware path split** ([splitPathsByMode])
 * The base style lumps cycleways, footways and generic paths into one
 * dimmed `road_path_pedestrian` layer, so a rider looking at the map
 * can't tell which dashed line is a bike lane vs a sidewalk. This pass
 * replaces that layer with three subclass-filtered layers, coloured
 * per the active travel mode — cycleway cyan in [PathHighlightMode.Bike]
 * mode, footway amber in [PathHighlightMode.Foot] mode, everything
 * muted in [PathHighlightMode.Motor] mode.
 *
 * All passes are idempotent.
 *
 * **Usage**
 * ```kotlin
 * val patched = ScoovaStylePatcher.loadAndPatch(
 *     url = "https://tiles.scoo-va.info/styles/scoova-dark/style.json",
 *     locale = "ar-EG",
 *     mode = PathHighlightMode.Bike,
 * )
 * Style.Builder().fromJson(patched).let { map.setStyle(it) { ... } }
 * ```
 */
public object ScoovaStylePatcher {

    // Shared OkHttp client. Held internally so callers don't have to
    // set one up. If a host app wants to reuse its own client, it can
    // call [rewriteFonts] / [rewriteTextLanguage] directly with JSON
    // it already fetched.
    private val http = OkHttpClient()

    private val cache = mutableMapOf<String, String>()

    /**
     * Fetch the style at [url], apply the font collapse, optional 3D
     * building extrusions, and the locale text-field rewrite, then
     * return the patched JSON. Subsequent calls with the same URL +
     * 3D flag hit a process-level cache; the locale rewrite runs each
     * call because changing locale shouldn't bust the font cache.
     */
    @JvmStatic
    @JvmOverloads
    public suspend fun loadAndPatch(
        url: String,
        locale: String = "",
        userAgent: String = "scoova-nav-layer/0.1 (android)",
        building3d: Boolean = true,
        mode: PathHighlightMode = PathHighlightMode.Motor,
    ): String {
        val cacheKey = "$url|3d=$building3d"
        val fontAnd3dPatched = cache[cacheKey] ?: run {
            val text = withContext(Dispatchers.IO) {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("style http ${resp.code}")
                    resp.body!!.string()
                }
            }
            var patched = rewriteFonts(text)
            if (building3d) patched = addBuildingExtrusions(patched)
            cache[cacheKey] = patched
            patched
        }
        // Locale + mode passes are NOT cached because both change per
        // request; the font + 3D passes are heavy and run once per URL.
        val withLocale = if (locale.isBlank()) fontAnd3dPatched
                         else rewriteTextLanguage(fontAnd3dPatched, locale)
        return splitPathsByMode(withLocale, mode)
    }

    /**
     * Inject a `fill-extrusion` layer for the `building` source-layer
     * so 3D building footprints render when the camera tilts. Uses
     * the OpenMapTiles `render_height` + `render_min_height` numeric
     * fields the Scoova tile build already ships. Layer is inserted
     * just before the first symbol layer so it draws under labels.
     *
     * Visual tuning: brown-grey base, lighter on top via `fill-
     * extrusion-color` constant, opacity 0.92 so labels remain
     * readable when tilt is high.
     */
    @JvmStatic
    public fun addBuildingExtrusions(json: String): String {
        val root = JSONObject(json)
        val layers = root.optJSONArray("layers") ?: return json
        // Don't double-add if a previous patch already injected it.
        for (i in 0 until layers.length()) {
            val l = layers.optJSONObject(i) ?: continue
            if (l.optString("id") == BUILDING_3D_LAYER_ID) return json
        }
        // Find the first symbol layer — buildings should draw UNDER
        // labels so road names aren't occluded.
        var firstSymbolIdx = layers.length()
        for (i in 0 until layers.length()) {
            val l = layers.optJSONObject(i) ?: continue
            if (l.optString("type") == "symbol") {
                firstSymbolIdx = i
                break
            }
        }
        // Identify the vector source name (usually "scoova" or
        // "openmaptiles"). We pick the first vector source declared.
        val sources = root.optJSONObject("sources") ?: return json
        val sourceName = sources.keys().asSequence().firstOrNull { key ->
            sources.optJSONObject(key)?.optString("type") == "vector"
        } ?: return json

        val extrusion = JSONObject().apply {
            put("id", BUILDING_3D_LAYER_ID)
            put("type", "fill-extrusion")
            put("source", sourceName)
            put("source-layer", "building")
            put("minzoom", 14.0)
            put("paint", JSONObject().apply {
                put("fill-extrusion-color", "#3a3f4c")
                put("fill-extrusion-height", JSONArray().apply {
                    put("get"); put("render_height")
                })
                put("fill-extrusion-base", JSONArray().apply {
                    put("get"); put("render_min_height")
                })
                put("fill-extrusion-opacity", 0.92)
            })
        }
        // Splice into the layers array at firstSymbolIdx.
        val newLayers = JSONArray()
        for (i in 0 until firstSymbolIdx) newLayers.put(layers.opt(i))
        newLayers.put(extrusion)
        for (i in firstSymbolIdx until layers.length()) newLayers.put(layers.opt(i))
        root.put("layers", newLayers)
        return root.toString()
    }

    private const val BUILDING_3D_LAYER_ID = "scoova-building-3d"

    /**
     * Apply the font-collapse pass to an already-fetched style. Use
     * this when the host app has its own HTTP client.
     */
    @JvmStatic
    public fun rewriteFonts(json: String): String {
        val root = JSONObject(json)
        val layers = root.optJSONArray("layers") ?: return json
        for (i in 0 until layers.length()) {
            val layer = layers.optJSONObject(i) ?: continue
            val layout = layer.optJSONObject("layout") ?: continue
            val hasTextField = layout.opt("text-field") != null
            if (hasTextField) {
                layout.put(
                    "text-font",
                    JSONArray().apply { put("Noto Sans Regular") },
                )
            }
        }
        return root.toString()
    }

    /**
     * Apply the locale rewrite to an already-fetched (and ideally
     * already font-patched) style. Pass-through for blank / unknown
     * locales.
     *
     * Three-tier coalesce: full locale → base language → latin. Some
     * tile sources carry region-specific keys (`name:ar-EG`,
     * `name:zh-Hant`) and we want those when present rather than
     * dropping straight to the Latin transliteration for a dialect
     * speaker. Mirrors the iOS pass.
     */
    @JvmStatic
    public fun rewriteTextLanguage(json: String, locale: String): String {
        if (locale.isBlank()) return json
        val trimmed = locale.lowercase()
        val base = trimmed.substringBefore('-').ifBlank { return json }
        val root = JSONObject(json)
        val layers = root.optJSONArray("layers") ?: return json
        for (i in 0 until layers.length()) {
            val layer = layers.optJSONObject(i) ?: continue
            val layout = layer.optJSONObject("layout") ?: continue
            val tf = layout.opt("text-field") ?: continue
            // Skip road shields (refs like "M1" / "A4" are language-neutral).
            if (tf is String && tf.contains("{ref}")) continue
            // ["coalesce", ["get", "name:<full>"], ["get", "name:<base>"], ["get", "name:latin"]]
            val coalesce = JSONArray().apply {
                put("coalesce")
                put(JSONArray().apply { put("get"); put("name:$trimmed") })
                put(JSONArray().apply { put("get"); put("name:$base") })
                put(JSONArray().apply { put("get"); put("name:latin") })
            }
            layout.put("text-field", coalesce)
        }
        return root.toString()
    }

    /**
     * Replace the base style's catch-all `road_path_pedestrian` layer
     * with three subclass-filtered layers (cycleway / footway / generic
     * path), coloured per the active travel [mode]. Looking at the map
     * the rider can now tell which dashed line is a bike lane vs a
     * sidewalk — the routing engine has always known the difference,
     * the map style just wasn't showing it.
     *
     * Idempotent: if the patcher has already split the layer (the
     * `scoova_road_*` ids are present) we wipe them and re-apply
     * with the current mode, so swapping persona mid-session re-skins
     * the paths.
     *
     * Mirrors the iOS `splitPathsByMode` pass byte-for-byte.
     */
    @JvmStatic
    public fun splitPathsByMode(json: String, mode: PathHighlightMode): String {
        val root = JSONObject(json)
        val layers = root.optJSONArray("layers") ?: return json

        // Wipe any prior split — keeps the function safe to call when
        // the persona changes (we replace the three layers in place).
        val previouslySplit = (0 until layers.length()).any { i ->
            (layers.optJSONObject(i)?.optString("id") ?: "").startsWith("scoova_road_")
        }
        var workLayers = if (previouslySplit) {
            JSONArray().also { kept ->
                for (i in 0 until layers.length()) {
                    val l = layers.optJSONObject(i) ?: continue
                    if (!(l.optString("id").startsWith("scoova_road_"))) kept.put(l)
                }
            }
        } else layers

        // Find the catch-all path layer the base style ships with.
        val target = "road_path_pedestrian"
        var idx = -1
        for (i in 0 until workLayers.length()) {
            if (workLayers.optJSONObject(i)?.optString("id") == target) { idx = i; break }
        }
        if (idx < 0) {
            // Style is already custom-cooked or doesn't include paths.
            if (previouslySplit) root.put("layers", workLayers)
            return root.toString()
        }
        val baseLayer = workLayers.optJSONObject(idx) ?: return json
        val source = baseLayer.optString("source", "openmaptiles")
        val sourceLayer = baseLayer.optString("source-layer", "transportation")
        val minzoom = if (baseLayer.has("minzoom")) baseLayer.opt("minzoom") else null
        val maxzoom = if (baseLayer.has("maxzoom")) baseLayer.opt("maxzoom") else null
        val lineWidth = baseLayer.optJSONObject("paint")?.opt("line-width")

        // Per-mode palette — bright = "this is what you want to use",
        // dim = "exists but not yours". Hex strings match iOS exactly.
        data class Tint(val color: String, val opacity: Double)
        val cycleway: Tint
        val footway: Tint
        val generic: Tint
        when (mode) {
            PathHighlightMode.Bike -> {
                cycleway = Tint("#06b6d4", 1.00)   // bright cyan
                footway  = Tint("#3d5446", 0.45)
                generic  = Tint("#5a8c5a", 0.80)
            }
            PathHighlightMode.Foot -> {
                cycleway = Tint("#5a8c5a", 0.65)
                footway  = Tint("#f59e0b", 1.00)   // bright amber
                generic  = Tint("#a3b18a", 0.90)
            }
            PathHighlightMode.Motor -> {
                cycleway = Tint("#3d5446", 0.45)
                footway  = Tint("#3d5446", 0.40)
                generic  = Tint("#3d5446", 0.45)
            }
        }

        fun makeLayer(
            id: String,
            subclasses: List<String>,
            tint: Tint,
            dash: List<Double>,
        ): JSONObject {
            val filter = JSONArray().apply {
                put("all")
                put(JSONArray().apply { put("=="); put("\$type"); put("LineString") })
                put(JSONArray().apply {
                    put("!in"); put("brunnel"); put("bridge"); put("tunnel")
                })
                put(JSONArray().apply { put("in"); put("class"); put("path"); put("pedestrian") })
                if (subclasses.size == 1) {
                    put(JSONArray().apply { put("=="); put("subclass"); put(subclasses[0]) })
                } else {
                    put(JSONArray().apply {
                        put("in"); put("subclass")
                        subclasses.forEach { put(it) }
                    })
                }
            }
            val paint = JSONObject().apply {
                put("line-color", tint.color)
                put("line-opacity", tint.opacity)
                put("line-dasharray", JSONArray().apply { dash.forEach { put(it) } })
                if (lineWidth != null) put("line-width", lineWidth)
            }
            return JSONObject().apply {
                put("id", id)
                put("type", "line")
                put("source", source)
                put("source-layer", sourceLayer)
                put("filter", filter)
                put("paint", paint)
                if (minzoom != null) put("minzoom", minzoom)
                if (maxzoom != null) put("maxzoom", maxzoom)
            }
        }

        val cyclewayLayer = makeLayer(
            id = "scoova_road_cycleway",
            subclasses = listOf("cycleway", "mountain_bike"),
            tint = cycleway,
            dash = listOf(4.0, 2.0),
        )
        val footwayLayer = makeLayer(
            id = "scoova_road_footway",
            subclasses = listOf("footway", "pedestrian", "steps", "sidewalk"),
            tint = footway,
            dash = listOf(2.0, 2.0),
        )
        val genericLayer = makeLayer(
            id = "scoova_road_path_generic",
            subclasses = listOf("path", "track", "bridleway"),
            tint = generic,
            dash = listOf(3.0, 2.0),
        )

        // Splice the three replacements in at the original target's slot.
        val newLayers = JSONArray()
        for (i in 0 until workLayers.length()) {
            if (i == idx) {
                newLayers.put(cyclewayLayer)
                newLayers.put(footwayLayer)
                newLayers.put(genericLayer)
            } else {
                newLayers.put(workLayers.opt(i))
            }
        }
        root.put("layers", newLayers)
        return root.toString()
    }
}
