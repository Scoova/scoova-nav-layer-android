package com.scoova.navlayer.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One telemetry event for a cue that successfully spoke. Mirrors iOS'
 * `ScoovaNavLayer.CueEvent` so the same backend can ingest both
 * platforms' streams.
 */
public data class CueEvent(
    val tsMs: Long,
    val text: String,
    val tone: String,
    val locale: String,
    val maneuverIndex: Int? = null,
    val metersToManeuver: Int? = null,
)

/**
 * Port of iOS `CueTelemetrySender.swift`. Pluggable batch-uploader for
 * cue telemetry. The host wires this to the nav layer's cue stream and
 * forgets about it — the sender buffers cues in memory and POSTs them
 * to a configurable endpoint in batches (every N seconds or after M
 * events, whichever first). Network failures are logged and dropped;
 * nav cues never block on telemetry.
 */
public class CueTelemetrySender(private val config: Config) {

    public data class Config(
        val endpointUrl: String,
        val apiKey: String,
        /** Opaque per-trip identifier the backend uses to group cues
         *  from the same ride. The host generates one (e.g. a UUID)
         *  at trip start. */
        val tripId: String,
        /** Flush cadence. Defaults to 10 s. */
        val flushIntervalMs: Long = 10_000,
        /** Cue count that forces a flush regardless of clock. */
        val maxBatchSize: Int = 50,
    )

    private val buffer = mutableListOf<CueEvent>()
    private val lock = ReentrantLock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flushJob: Job? = null

    init { startFlushTimer() }

    /** Plug into the host's cue stream. */
    public fun observe(event: CueEvent) {
        val shouldFlush = lock.withLock {
            buffer.add(event)
            buffer.size >= config.maxBatchSize
        }
        if (shouldFlush) {
            scope.launch { flushNow() }
        }
    }

    /** Force an immediate flush — useful on app background / trip stop. */
    public suspend fun flushNow() {
        val snapshot: List<CueEvent> = lock.withLock {
            val copy = buffer.toList()
            buffer.clear()
            copy
        }
        if (snapshot.isEmpty()) return
        val body = JSONObject().apply {
            put("tripId", config.tripId)
            val cues = JSONArray()
            snapshot.forEach { e ->
                val o = JSONObject().apply {
                    put("tsMs", e.tsMs)
                    put("text", e.text)
                    put("tone", e.tone)
                    put("locale", e.locale)
                    e.maneuverIndex?.let { put("maneuverIndex", it) }
                    e.metersToManeuver?.let { put("metersToManeuver", it) }
                }
                cues.put(o)
            }
            put("cues", cues)
        }.toString()
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL(config.endpointUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-API-Key", config.apiKey)
                    setRequestProperty("User-Agent", "scoova-nav-layer-android/1.0 (telemetry)")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                conn.responseCode  // consume so the connection closes
                conn.disconnect()
            }
        }
    }

    /** Cancel timers + drop any pending buffer. Call on shutdown. */
    public fun shutdown() {
        flushJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun startFlushTimer() {
        flushJob?.cancel()
        flushJob = scope.launch {
            while (true) {
                delay(config.flushIntervalMs)
                flushNow()
            }
        }
    }
}
