package com.scoova.ride

import android.content.Context
import com.scoova.navlayer.scoova.LatLon
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Single-slot store for the *currently in-progress* ride.
 *
 * **Why this exists.** A ride is the only piece of app state that the
 * user expects to survive a process kill. If Android reaps the app
 * under memory pressure (or the rider task-swipes by accident), the
 * next launch should pick up where the ride left off: same
 * destination, same route preview on the map, same breadcrumb trail,
 * preserving the timer and covered distance. Completed rides are
 * handled by [RideHistoryStore]; this store covers the one *active*
 * ride at most.
 *
 * **Lifecycle.**
 *   • Written by [RideViewModel] on each onLocation tick (throttled).
 *   • Cleared at end-of-ride / cancel.
 *   • Read once at VM construction. If a non-null snapshot is
 *     returned, the VM hydrates into the Ride phase with the cached
 *     state, then kicks a fresh routing request to repopulate the
 *     adapter's maneuver pipeline (we don't persist that — it's
 *     server-derived and cheap to refetch).
 *
 * The file lives in app-private `filesDir`, never on external
 * storage. Single JSON object per save; no array, no append. Best-effort
 * load — any deserialisation failure returns null and the user starts
 * cleanly in Plan rather than wedged in a half-restored Ride.
 */
internal class ActiveRideStore(private val context: Context) {
    private val file: File = File(context.filesDir, "active_ride.json")

    data class Snapshot(
        val profileId: String,
        val origin: LatLon?,
        val destination: LatLon,
        val destinationLabel: String?,
        val routeShape: List<DoubleArray>,
        val routeDistanceKm: Double,
        val routeDurationMin: Int,
        val rideStartedAtMs: Long,
        val coveredKm: Double,
        val actualPath: List<DoubleArray>,
        val avoidHighways: Boolean,
        val avoidTolls: Boolean,
        val avoidFerries: Boolean,
    )

    fun save(snap: Snapshot) {
        // Serialise on the caller's thread. Writes are tiny (<= ~20 KB
        // even on a long ride thanks to actualPath being trimmed
        // upstream by the caller), and the throttling in
        // [RideViewModel] caps frequency to roughly once every 5 s.
        // Bytes on disk are AES-256 GCM ciphertext via [SecureStorage] —
        // the active-ride snapshot includes destination + breadcrumb
        // trail, both of which the threat model in [SecureStorage]
        // covers.
        val o = JSONObject().apply {
            put("profileId", snap.profileId)
            snap.origin?.let {
                put("origin", JSONObject().apply {
                    put("lat", it.lat); put("lon", it.lon)
                })
            }
            put("destination", JSONObject().apply {
                put("lat", snap.destination.lat)
                put("lon", snap.destination.lon)
            })
            snap.destinationLabel?.let { put("destinationLabel", it) }
            put("routeShape", encodePolyline(snap.routeShape))
            put("routeDistanceKm", snap.routeDistanceKm)
            put("routeDurationMin", snap.routeDurationMin)
            put("rideStartedAtMs", snap.rideStartedAtMs)
            put("coveredKm", snap.coveredKm)
            put("actualPath", encodePolyline(snap.actualPath))
            put("avoidHighways", snap.avoidHighways)
            put("avoidTolls", snap.avoidTolls)
            put("avoidFerries", snap.avoidFerries)
        }
        SecureStorage.writeEncryptedText(context, file, o.toString())
    }

    fun load(): Snapshot? {
        if (!file.exists()) return null
        val text = SecureStorage.readEncryptedText(context, file) ?: return null
        return runCatching {
            val o = JSONObject(text)
            Snapshot(
                profileId = o.getString("profileId"),
                origin = o.optJSONObject("origin")?.let {
                    LatLon(it.getDouble("lat"), it.getDouble("lon"))
                },
                destination = o.getJSONObject("destination").let {
                    LatLon(it.getDouble("lat"), it.getDouble("lon"))
                },
                destinationLabel = o.optString("destinationLabel")
                    .takeIf { it.isNotBlank() && it != "null" },
                routeShape = decodePolyline(o.optString("routeShape")),
                routeDistanceKm = o.optDouble("routeDistanceKm", 0.0),
                routeDurationMin = o.optInt("routeDurationMin", 0),
                rideStartedAtMs = o.optLong("rideStartedAtMs", 0L),
                coveredKm = o.optDouble("coveredKm", 0.0),
                actualPath = decodePolyline(o.optString("actualPath")),
                avoidHighways = o.optBoolean("avoidHighways", false),
                avoidTolls = o.optBoolean("avoidTolls", false),
                avoidFerries = o.optBoolean("avoidFerries", false),
            )
        }.getOrNull()
    }

    fun clear() {
        runCatching { file.delete() }
    }

    /** Compact `lat,lon;lat,lon;…` encoding, same shape as
     *  [RideRecord.encodePath] uses but without decimation. The active
     *  ride may need every point so coveredKm survives restart with
     *  high fidelity. */
    private fun encodePolyline(points: List<DoubleArray>): String {
        if (points.isEmpty()) return ""
        return points.joinToString(";") { "%.6f,%.6f".format(it[0], it[1]) }
    }

    private fun decodePolyline(encoded: String?): List<DoubleArray> {
        if (encoded.isNullOrBlank()) return emptyList()
        return encoded.split(';').mapNotNull { pair ->
            val parts = pair.split(',')
            if (parts.size != 2) return@mapNotNull null
            val lat = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lon = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            doubleArrayOf(lat, lon)
        }
    }
}
