package com.scoova.navlayer.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One forecast row — an hour ahead (hourly) or a day ahead (daily). */
public data class ScoovaForecastPoint(
    /** ISO-8601 string straight from the feed — "2026-05-21T15:00"
     *  (hourly) or "2026-05-21" (daily). The host formats it. */
    public val time: String,
    /** Hourly: that hour's temperature. Daily: the day's HIGH. */
    public val temperatureC: Double,
    /** Daily only — the day's low. null for hourly rows. */
    public val lowC: Double?,
    /** Coarse condition bucket — same vocabulary as the snapshot. */
    public val condition: String,
    /** Chance of precipitation 0–100; null when the feed omitted it. */
    public val precipitationPct: Int?,
)

/**
 * Weather at the rider's location — the realtime reading plus the
 * hourly and daily forecast. The chip shows the realtime fields;
 * [hourly] / [daily] back a forecast panel.
 */
public data class ScoovaWeatherSnapshot(
    public val temperatureC: Double,
    /** Coarse bucket: clear, clouds, partly_cloudy, rain, drizzle,
     *  snow, fog, thunderstorm, unknown. */
    public val condition: String,
    public val windKph: Double,
    /** Direction the wind is blowing FROM (0 = north). */
    public val windFromDeg: Double,
    /** Next ~12 h, oldest first. Empty when only `current` came back. */
    public val hourly: List<ScoovaForecastPoint> = emptyList(),
    /** Next ~3 days, oldest first. */
    public val daily: List<ScoovaForecastPoint> = emptyList(),
) {
    /** Emoji glyph matching the condition bucket. Cheap stable mapping. */
    public val emoji: String get() = emojiFor(condition)

    public companion object {
        public fun emojiFor(condition: String): String = when (condition.lowercase()) {
            "clear", "sunny" -> "☀️"
            "clouds", "cloudy", "overcast", "partly_cloudy" -> "⛅"
            "rain", "shower", "drizzle" -> "🌧️"
            "thunderstorm", "thunder" -> "⛈️"
            "snow", "sleet" -> "❄️"
            "mist", "fog", "haze" -> "🌫️"
            else -> "🌤️"
        }
    }
}

/**
 * Thin client over Scoova's Open-Meteo-compatible weather endpoint.
 *
 * [fetch] returns the realtime reading PLUS the hourly + daily
 * forecast — the Scoova weather service is Open-Meteo-compatible, so
 * `current`, `hourly` and `daily` all come back from one
 * `/v1/forecast` call. Failures turn into null so host UIs can hide
 * the chip gracefully instead of crashing.
 *
 * **Usage**
 * ```kotlin
 * val weather = ScoovaWeather().fetch(lat = 30.04, lon = 31.23)
 * weather?.let { chip.show(it.emoji, "${it.temperatureC.toInt()}°") }
 * weather?.hourly?.forEach { … }   // forecast panel
 * ```
 */
public class ScoovaWeather
@JvmOverloads constructor(
    private val apiKey: String = "sk_demo_local",
    // Weather goes through the keyed gateway — it forwards every query
    // param straight through to Open-Meteo. Raw subdomain is firewalled.
    private val baseUrl: String = "https://api.scoo-va.info",
) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /** Realtime + hourly(12 h) + daily(3 d) in one request. */
    public suspend fun fetch(lat: Double, lon: Double): ScoovaWeatherSnapshot? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$baseUrl/api/v1/weather?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,precipitation,windspeed_10m," +
                    "winddirection_10m,weathercode" +
                    "&hourly=temperature_2m,weathercode,precipitation_probability" +
                    "&daily=temperature_2m_max,temperature_2m_min,weathercode" +
                    "&forecast_days=3&timezone=auto"
                val req = Request.Builder()
                    .url(url)
                    .header("X-API-Key", apiKey)
                    .header("User-Agent", "scoova-nav-layer/0.1 (android)")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val current = root.optJSONObject("current")
                        ?: return@withContext null
                    val temp = current.optDouble("temperature_2m", Double.NaN)
                    if (!temp.isFinite()) return@withContext null
                    ScoovaWeatherSnapshot(
                        temperatureC = temp,
                        condition = wmoToCondition(current.optInt("weathercode", -1)),
                        windKph = current.optDouble("windspeed_10m", 0.0),
                        windFromDeg = current.optDouble("winddirection_10m", 0.0),
                        hourly = parseHourly(root.optJSONObject("hourly")),
                        daily = parseDaily(root.optJSONObject("daily")),
                    )
                }
            }.getOrNull()
        }

    /** Legacy name kept so existing call sites still compile — prefer
     *  [fetch], which carries the forecast. */
    public suspend fun now(lat: Double, lon: Double): ScoovaWeatherSnapshot? =
        fetch(lat, lon)
}

// ── Forecast parsing ───────────────────────────────────────────────
// Open-Meteo returns column-oriented arrays — `time[]`,
// `temperature_2m[]`, … all index-aligned. Zip them into rows.

private fun parseHourly(h: JSONObject?): List<ScoovaForecastPoint> {
    h ?: return emptyList()
    val times = h.optJSONArray("time") ?: return emptyList()
    val temps = h.optJSONArray("temperature_2m") ?: return emptyList()
    val codes = h.optJSONArray("weathercode")
    val pops = h.optJSONArray("precipitation_probability")
    // The hourly feed starts at local midnight. Keep from roughly the
    // current hour onward, capped at 12 rows so a panel stays glanceable.
    // SimpleDateFormat (not java.time) — this module is minSdk 24 and
    // has no core-library desugaring, so java.time would crash < API 26.
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US)
    val cutoff = System.currentTimeMillis() - 60L * 60L * 1000L  // 1 h ago
    val out = ArrayList<ScoovaForecastPoint>()
    for (i in 0 until minOf(times.length(), temps.length())) {
        val t = times.optString(i)
        val parsedMs = runCatching { fmt.parse(t)?.time }.getOrNull()
        if (parsedMs != null && parsedMs < cutoff) continue
        if (out.size >= 12) break
        out.add(
            ScoovaForecastPoint(
                time = t,
                temperatureC = temps.optDouble(i, Double.NaN),
                lowC = null,
                condition = wmoToCondition(codes?.optInt(i, -1) ?: -1),
                precipitationPct = pops?.let { if (i < it.length()) it.optInt(i) else null },
            ),
        )
    }
    return out
}

private fun parseDaily(d: JSONObject?): List<ScoovaForecastPoint> {
    d ?: return emptyList()
    val times = d.optJSONArray("time") ?: return emptyList()
    val highs = d.optJSONArray("temperature_2m_max") ?: return emptyList()
    val lows = d.optJSONArray("temperature_2m_min") ?: return emptyList()
    val codes = d.optJSONArray("weathercode")
    val out = ArrayList<ScoovaForecastPoint>()
    for (i in 0 until minOf(times.length(), highs.length(), lows.length())) {
        out.add(
            ScoovaForecastPoint(
                time = times.optString(i),
                temperatureC = highs.optDouble(i, Double.NaN),
                lowC = lows.optDouble(i, Double.NaN),
                condition = wmoToCondition(codes?.optInt(i, -1) ?: -1),
                precipitationPct = null,
            ),
        )
    }
    return out
}

// WMO weather-code → coarse condition bucket. Open-Meteo / Scoova
// weather returns ints (0=clear, 45=fog, 61-67=rain, 71-77=snow,
// 95-99=thunder); we collapse them to the strings the snapshot's
// emoji mapping already understands — same emoji set, just a
// different input format.
private fun wmoToCondition(code: Int): String = when (code) {
    0 -> "clear"
    1, 2 -> "partly_cloudy"
    3 -> "clouds"
    45, 48 -> "fog"
    51, 53, 55, 56, 57 -> "drizzle"
    61, 63, 65, 66, 67, 80, 81, 82 -> "rain"
    71, 73, 75, 77, 85, 86 -> "snow"
    95, 96, 99 -> "thunderstorm"
    else -> "unknown"
}
