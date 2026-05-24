package com.scoova.navlayer.geocoding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A single autocomplete suggestion from the Pelias geocoder. */
public data class ScoovaPlaceSuggestion(
    /** Human-readable, e.g. "Tahrir Square, Cairo". */
    public val label: String,
    public val lat: Double,
    public val lon: Double,
    /** Pelias layer — `venue`, `address`, `street`, `region`, etc. */
    public val category: String? = null,
)

/**
 * Thin client over Scoova's Pelias-backed geocoding endpoint.
 *
 * Endpoints in play:
 *   • `/v1/autocomplete?text=...&focus.point.lat=...&focus.point.lon=...`
 *     — fast type-as-you-go (debounced from the UI).
 *
 * Failures are intentionally turned into empty results — the typical
 * host-app UI shows a subtle "Search unavailable" hint instead of
 * crashing; riders can fall back to long-pressing the map for a tap
 * route. If a host needs the exception surface, switch to the
 * lower-level `okhttp3` directly.
 *
 * **Usage**
 * ```kotlin
 * val geocoder = ScoovaGeocoder(apiKey = "sk_live_...")
 * val results = geocoder.autocomplete("tahrir", focusLat = 30.04, focusLon = 31.23)
 * ```
 */
public class ScoovaGeocoder
@JvmOverloads constructor(
    private val apiKey: String = "sk_demo_local",
    // All geocoding goes through the keyed gateway. `$baseUrl/v1/autocomplete`
    // resolves to `api.scoo-va.info/api/v1/autocomplete`, which the gateway
    // proxies to Pelias. The raw geocoding subdomain is firewalled.
    private val baseUrl: String = "https://api.scoo-va.info/api",
) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    public suspend fun autocomplete(
        text: String,
        focusLat: Double? = null,
        focusLon: Double? = null,
        maxResults: Int = 8,
    ): List<ScoovaPlaceSuggestion> = withContext(Dispatchers.IO) {
        if (text.isBlank() || text.length < 2) return@withContext emptyList()
        runCatching {
            val urlBuilder = StringBuilder("$baseUrl/v1/autocomplete?text=")
                .append(java.net.URLEncoder.encode(text, "UTF-8"))
                .append("&size=").append(maxResults)
            if (focusLat != null && focusLon != null) {
                urlBuilder.append("&focus.point.lat=$focusLat")
                urlBuilder.append("&focus.point.lon=$focusLon")
            }
            val req = Request.Builder()
                .url(urlBuilder.toString())
                .header("X-API-Key", apiKey)
                .header("User-Agent", "scoova-nav-layer/0.1 (android)")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList<ScoovaPlaceSuggestion>()
                val body = resp.body?.string().orEmpty()
                parseFeatureCollection(body)
            }
        }.getOrDefault(emptyList())
    }

    private fun parseFeatureCollection(text: String): List<ScoovaPlaceSuggestion> {
        return runCatching {
            val root = JSONObject(text)
            val features = root.optJSONArray("features") ?: return emptyList()
            (0 until features.length()).mapNotNull { i ->
                val feat = features.optJSONObject(i) ?: return@mapNotNull null
                val props = feat.optJSONObject("properties") ?: return@mapNotNull null
                val geom = feat.optJSONObject("geometry") ?: return@mapNotNull null
                val coords = geom.optJSONArray("coordinates") ?: return@mapNotNull null
                val label = props.optString("label").ifBlank {
                    props.optString("name").ifBlank { return@mapNotNull null }
                }
                ScoovaPlaceSuggestion(
                    label = label,
                    lon = coords.optDouble(0, Double.NaN),
                    lat = coords.optDouble(1, Double.NaN),
                    category = props.optString("layer").ifBlank { null },
                ).takeIf { it.lat.isFinite() && it.lon.isFinite() }
            }
        }.getOrDefault(emptyList())
    }
}
