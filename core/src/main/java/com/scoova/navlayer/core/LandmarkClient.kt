package com.scoova.navlayer.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Looks up the nearest named POI to a maneuver point so the cue can read
 * "Turn left at Tahrir Square" instead of just "Turn left".
 *
 * Backend is the public Pelias-enriched landmark proxy at
 * api.scoo-va.info — same instance the platform's own routing service
 * uses internally.
 */
public class LandmarkClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.scoo-va.info/v1/landmarks",
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    public suspend fun nearest(lat: Double, lon: Double, lang: String = "en"): String? =
        withContext(Dispatchers.IO) {
            val params = "lat=$lat&lon=$lon&lang=${URLEncoder.encode(lang.split("-")[0], "UTF-8")}"
            val req = Request.Builder()
                .url("$baseUrl?$params")
                .header("X-API-Key", apiKey)
                .header("User-Agent", "scoova-nav-layer/0.1 (android)")
                .build()
            try {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string() ?: return@use null
                    val obj = json.parseToJsonElement(body).jsonObject
                    obj["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                }
            } catch (_: Throwable) { null }
        }
}
