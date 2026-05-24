package com.scoova.ride

import com.scoova.navlayer.geocoding.ScoovaGeocoder
import com.scoova.navlayer.geocoding.ScoovaPlaceSuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Demo-side shim around the SDK's [ScoovaGeocoder]. The geocoding
 * client itself lives in `:adapter-scoova-geocoding` so any host
 * app can drop the same Pelias-backed search in with one Gradle
 * dependency.
 *
 * [SearchSuggestion] stays as a typealias so the rest of the demo
 * code (search sheet, view-model, etc.) keeps working unchanged.
 */
typealias SearchSuggestion = ScoovaPlaceSuggestion

class SearchClient(
    private val apiKey: String = ScoovaApi.KEY,
) {
    private val sdk = ScoovaGeocoder(apiKey = apiKey)

    suspend fun autocomplete(
        text: String,
        focusLat: Double? = null,
        focusLon: Double? = null,
    ): List<SearchSuggestion> =
        sdk.autocomplete(text = text, focusLat = focusLat, focusLon = focusLon)

    /**
     * Reverse-geocode a coordinate to a human place label — "Fresh
     * Mart Supermarket, Cairo, Egypt". Used when the rider long-presses
     * the map: instead of routing to an anonymous "Tapped point", we
     * resolve what's actually there so the destination reads like a
     * real place.
     *
     * Hits the keyed gateway's `/api/v1/reverse` (the SDK's
     * [ScoovaGeocoder] only exposes autocomplete). Best-effort — any
     * failure returns null and the caller falls back to a generic label.
     */
    suspend fun reverse(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(
                "${ScoovaApi.GATEWAY}/reverse" +
                    "?point.lat=$lat&point.lon=$lon&size=1",
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                requestMethod = "GET"
                setRequestProperty("X-API-Key", apiKey)
            }
            try {
                if (conn.responseCode != 200) return@runCatching null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val features = JSONObject(body).optJSONArray("features")
                if (features == null || features.length() == 0) return@runCatching null
                features.getJSONObject(0)
                    .optJSONObject("properties")
                    ?.optString("label")
                    ?.takeIf { it.isNotBlank() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}
