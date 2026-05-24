package com.scoova.navlayer.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Lightweight weather lookup for the current location and the route's
 * destination, used by the WeatherBanner to decide whether to flag rain
 * / fog / wind ahead of the rider.
 */
public class WeatherClient(
    private val apiKey: String,
    // Keyed gateway — forwards the `current=` param straight to Open-Meteo.
    private val baseUrl: String = "https://api.scoo-va.info/api/v1/weather",
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    public data class Snapshot(
        val temperatureC: Double?,
        val weatherCode: Int?,
        val windKph: Double?,
        val isRainOrSnow: Boolean,
        val isFog: Boolean,
        val isThunder: Boolean,
    )

    public suspend fun current(lat: Double, lon: Double): Snapshot? = withContext(Dispatchers.IO) {
        val params = "latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,wind_speed_10m"
        val req = Request.Builder()
            .url("$baseUrl?$params")
            .header("X-API-Key", apiKey)
            .header("User-Agent", "scoova-nav-layer/0.1 (android)")
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val cur = json.parseToJsonElement(body).jsonObject["current"]?.jsonObject ?: return@use null
                val temp = cur["temperature_2m"]?.jsonPrimitive?.contentOrNullDouble()
                val code = cur["weather_code"]?.jsonPrimitive?.contentOrNullInt()
                val wind = cur["wind_speed_10m"]?.jsonPrimitive?.contentOrNullDouble()
                Snapshot(
                    temperatureC = temp,
                    weatherCode = code,
                    windKph = wind,
                    isRainOrSnow = code in setOf(51, 53, 55, 61, 63, 65, 71, 73, 75, 80, 81, 82, 85, 86),
                    isFog = code in setOf(45, 48),
                    isThunder = code in setOf(95, 96, 99),
                )
            }
        } catch (_: Throwable) { null }
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullDouble(): Double? =
    runCatching { content.toDoubleOrNull() }.getOrNull()

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullInt(): Int? =
    runCatching { content.toIntOrNull() }.getOrNull()
