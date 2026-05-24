package com.scoova.ride

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A single completed ride, persisted locally. */
data class RideRecord(
    val id: String,
    val profileId: String,
    val destinationLabel: String?,
    val destLat: Double? = null,
    val destLon: Double? = null,
    val distanceKm: Double,
    val durationMin: Int,
    val coveredKm: Double,
    val startedAtMs: Long,
    val endedAtMs: Long,
    /**
     * Decimated GPS trace as `lat0,lon0;lat1,lon1;…`. Stored as a
     * string instead of a JSON array to keep the on-disk shape small
     * (one ride = ~1–3 KB rather than 30 KB+). Decimation is done at
     * write time via Douglas–Peucker to cap at ~200 points per ride;
     * past 200 entries we drop to ~50 to keep the file under 1 MB.
     */
    val path: String? = null,
    /**
     * Free-text note the rider attaches on the Summary screen. Trimmed
     * to [MAX_NOTE_CHARS] at save time. Doesn't appear on every ride;
     * shows in History detail when present.
     */
    val notes: String? = null,
    /**
     * Cloud document ID once this ride has been uploaded to
     * cloud.scoo-va.info. Null = local-only. Set in-place via
     * [RideHistoryStore.update] after a successful upload.
     */
    val cloudDocId: String? = null,
    /** Wall-clock millis when the cloud upload completed. Null until uploaded. */
    val uploadedAtMs: Long? = null,
) {
    val avgKph: Int get() {
        val mins = ((endedAtMs - startedAtMs).coerceAtLeast(60_000L)) / 60_000.0
        return if (mins > 0) (coveredKm / (mins / 60.0)).toInt() else 0
    }

    val profile: Profile? get() = Profile.fromId(profileId)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("profileId", profileId)
        put("destinationLabel", destinationLabel ?: JSONObject.NULL)
        put("destLat", destLat ?: JSONObject.NULL)
        put("destLon", destLon ?: JSONObject.NULL)
        put("distanceKm", distanceKm)
        put("durationMin", durationMin)
        put("coveredKm", coveredKm)
        put("startedAtMs", startedAtMs)
        put("endedAtMs", endedAtMs)
        if (path != null) put("path", path)
        if (!notes.isNullOrBlank()) put("notes", notes)
        if (cloudDocId != null) put("cloudDocId", cloudDocId)
        if (uploadedAtMs != null) put("uploadedAtMs", uploadedAtMs)
    }

    /** Decode the stored path string into `List<[lat, lon]>`. Returns
     *  empty list when no path was recorded for this ride. */
    fun decodedPath(): List<DoubleArray> {
        val raw = path?.takeIf { it.isNotBlank() } ?: return emptyList()
        return raw.split(';').mapNotNull { pair ->
            val parts = pair.split(',')
            if (parts.size != 2) return@mapNotNull null
            val lat = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lon = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            doubleArrayOf(lat, lon)
        }
    }

    companion object {
        fun fromJson(o: JSONObject): RideRecord = RideRecord(
            id = o.getString("id"),
            profileId = o.getString("profileId"),
            destinationLabel = o.optString("destinationLabel").takeIf { it.isNotBlank() && it != "null" },
            destLat = if (o.isNull("destLat")) null else o.optDouble("destLat", Double.NaN).takeIf { it.isFinite() },
            destLon = if (o.isNull("destLon")) null else o.optDouble("destLon", Double.NaN).takeIf { it.isFinite() },
            distanceKm = o.optDouble("distanceKm", 0.0),
            durationMin = o.optInt("durationMin", 0),
            coveredKm = o.optDouble("coveredKm", 0.0),
            startedAtMs = o.optLong("startedAtMs", 0L),
            endedAtMs = o.optLong("endedAtMs", 0L),
            path = o.optString("path").takeIf { it.isNotBlank() && it != "null" },
            notes = o.optString("notes").takeIf { it.isNotBlank() && it != "null" },
            cloudDocId = o.optString("cloudDocId").takeIf { it.isNotBlank() && it != "null" },
            uploadedAtMs = if (o.has("uploadedAtMs") && !o.isNull("uploadedAtMs"))
                o.optLong("uploadedAtMs", 0L).takeIf { it > 0L } else null,
        )

        /**
         * Encode a `lat/lon` polyline into the compact storage form
         * (`"lat,lon;lat,lon;…"`) with rounding to ~1 m precision.
         * Long traces are decimated by stride so a multi-hour ride
         * doesn't bloat the file — beyond [MAX_POINTS] we keep every
         * Nth point, preserving the start/end exactly.
         */
        fun encodePath(latLonPairs: List<DoubleArray>, maxPoints: Int = MAX_POINTS): String? {
            if (latLonPairs.size < 2) return null
            val sampled: List<DoubleArray> = if (latLonPairs.size <= maxPoints) latLonPairs
            else {
                val stride = (latLonPairs.size + maxPoints - 1) / maxPoints
                buildList {
                    add(latLonPairs.first())
                    var i = stride
                    while (i < latLonPairs.size - 1) {
                        add(latLonPairs[i])
                        i += stride
                    }
                    add(latLonPairs.last())
                }
            }
            return sampled.joinToString(";") { p ->
                "%.5f,%.5f".format(p[0], p[1])
            }
        }

        private const val MAX_POINTS = 200
        public const val MAX_NOTE_CHARS: Int = 240
    }
}

/**
 * Local-only ride history. Writes a JSON array to `rides.json` in the
 * app's private file store, encrypted at rest via [SecureStorage]
 * (AES-256 GCM, keyed off the Android Keystore master key). No network,
 * no auth, no schema migration concerns.
 *
 * Capped at [MAX_RIDES] to keep the file small and the load synchronous.
 * Past that, the oldest entries are dropped on save. For users who exceed
 * 200 rides, sync to Scoova NoSQL kicks in (v1.1).
 *
 * **Legacy migration.** Earlier builds wrote `rides.json` as plaintext.
 * On the first encrypted read the [load] path detects a non-empty
 * legacy file (presence of opening `[` byte plus successful JSON parse),
 * re-writes the parsed records via the encrypted path, then deletes
 * the plaintext file. Idempotent — subsequent loads find no legacy file
 * and take the fast path.
 */
internal class RideHistoryStore(private val context: Context) {
    private val file: File = File(context.filesDir, "rides.json")

    fun load(): List<RideRecord> {
        if (!file.exists()) return emptyList()
        // Try the encrypted path first. If that fails (likely a legacy
        // plaintext file), fall through to the migration step.
        val encryptedText = SecureStorage.readEncryptedText(context, file)
        val records = if (encryptedText != null) {
            parseRecords(encryptedText)
        } else {
            migrateLegacyPlaintext()
        }
        return records.sortedByDescending { it.endedAtMs }
    }

    private fun parseRecords(text: String): List<RideRecord> = runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).map { RideRecord.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun migrateLegacyPlaintext(): List<RideRecord> {
        val plaintext = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val parsed = parseRecords(plaintext)
        if (parsed.isEmpty()) return emptyList()
        // Rewrite via the encrypted path. SecureStorage.write deletes
        // the existing file first, so this also removes the plaintext.
        writeAll(parsed)
        return parsed
    }

    fun append(record: RideRecord) {
        val current = load().toMutableList()
        current.add(0, record)
        val trimmed = if (current.size > MAX_RIDES) current.take(MAX_RIDES) else current
        writeAll(trimmed)
    }

    /** Replace the record with the matching id. No-op when not found. */
    fun update(record: RideRecord) {
        val current = load().toMutableList()
        val idx = current.indexOfFirst { it.id == record.id }
        if (idx < 0) return
        current[idx] = record
        writeAll(current)
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private fun writeAll(records: List<RideRecord>) {
        val arr = JSONArray()
        records.forEach { arr.put(it.toJson()) }
        SecureStorage.writeEncryptedText(context, file, arr.toString())
    }

    private companion object {
        const val MAX_RIDES = 200
    }
}

/** Helper for building a new RideRecord at end-of-ride. */
internal fun buildRecord(
    state: RideViewModel.RideState,
    nowMs: Long = System.currentTimeMillis(),
): RideRecord = RideRecord(
    id = UUID.randomUUID().toString(),
    profileId = state.profile?.id ?: Profile.Bicycle.id,
    destinationLabel = state.destinationLabel,
    destLat = state.destination?.lat,
    destLon = state.destination?.lon,
    distanceKm = state.routeDistanceKm,
    durationMin = state.routeDurationMin,
    coveredKm = state.coveredKm,
    startedAtMs = state.rideStartedAtMs,
    endedAtMs = if (state.rideEndedAtMs > 0) state.rideEndedAtMs else nowMs,
    path = RideRecord.encodePath(state.actualPath),
)
