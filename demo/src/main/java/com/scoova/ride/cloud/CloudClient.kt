package com.scoova.ride.cloud

import android.content.Context
import com.scoova.ride.RideRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Typed outcome of an auth-or-save call. The UI needs to distinguish
 * network failure (retry path) from server-side validation failure
 * (correct the input) from missing-credentials (sign in first), so
 * `Result<T>` from the stdlib isn't enough — we sealed it.
 */
sealed class CloudResult<out T> {
    data class Success<T>(val value: T) : CloudResult<T>()
    data class Failure(val error: CloudError) : CloudResult<Nothing>()
}

/**
 * Why a cloud call failed. Each variant carries the human-readable
 * server message when one was returned, so the UI can either render
 * it directly (server says "Email already in use") or substitute its
 * own copy (offline → "Check your connection").
 */
sealed class CloudError(open val message: String) {
    /** Couldn't reach the server. Treat as transient. */
    data class Network(override val message: String) : CloudError(message)
    /** Server rejected the credentials / payload. Not retryable. */
    data class BadRequest(override val message: String) : CloudError(message)
    /** Token expired or wasn't provided where it was required. */
    data class Unauthorized(override val message: String) : CloudError(message)
    /** Server replied 5xx — treat as transient. */
    data class Server(override val message: String) : CloudError(message)
    /** Caller tried to do something requiring sign-in while signed out. */
    object NotSignedIn : CloudError("Not signed in")
}

/**
 * Single entry point for talking to the consumer-rider backend at
 * `my.scoo-va.info` from the demo.
 *
 * Owns the session credentials [signedIn] and exposes auth verbs
 * (register / login / refresh / me / forgotPassword / signOut) plus
 * the only data verb the demo needs in v1 — [saveRide].
 *
 * **Endpoint surface** (live as of 2026-05):
 *   POST  /v1/account/register     → AuthResponse (auto-logged-in)
 *   POST  /v1/account/login        → AuthResponse
 *   POST  /v1/account/refresh      → AuthResponse  (refreshToken → fresh access)
 *   GET   /v1/account/me           → UserDTO       (Bearer)
 *   POST  /v1/account/forgot-password → 200
 *   PUT   /v1/projects/scoova-riders/databases/(default)/documents/rides/{rideId} (Bearer)
 *
 * **Threading.** Network IO happens on [Dispatchers.IO]; the public
 * API is suspending. [signedIn] is hot — collect it from a
 * `viewModelScope` or `LifecycleScope` to drive UI.
 *
 * **Storage.** Credentials persist via [AuthStore] (encrypted prefs).
 * The client rehydrates them on construction so a returning user is
 * already signed in by the time the activity composes.
 *
 * **What's intentionally NOT here.** No retry queue, no background
 * upload, no email-verification gate, no listing-rides-back. Those
 * are deferred to v1.1. This client uploads write-through only.
 */
class CloudClient(
    private val appContext: Context,
    private val baseUrl: String = "https://my.scoo-va.info",
) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val prefs = AuthStore.prefs(appContext)
    private val _signedIn = MutableStateFlow(AuthStore.load(prefs))
    val signedIn: StateFlow<AuthCredentials?> = _signedIn.asStateFlow()

    val currentCredentials: AuthCredentials? get() = _signedIn.value

    // ─── Auth ─────────────────────────────────────────────────────────────

    suspend fun register(
        email: String,
        password: String,
        displayName: String?,
        locale: String?,
    ): CloudResult<AuthCredentials> = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            RegisterRequest.serializer(),
            RegisterRequest(
                email = email.trim(),
                password = password,
                displayName = displayName?.trim()?.takeIf { it.isNotEmpty() },
                locale = locale?.trim()?.takeIf { it.isNotEmpty() },
            ),
        ).toRequestBody(JSON_MEDIA)
        val req = Request.Builder()
            .url("$baseUrl/v1/account/register")
            .post(body)
            .build()
        execAuthCall(req)
    }

    suspend fun login(email: String, password: String): CloudResult<AuthCredentials> =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                LoginRequest.serializer(),
                LoginRequest(email = email.trim(), password = password),
            ).toRequestBody(JSON_MEDIA)
            val req = Request.Builder()
                .url("$baseUrl/v1/account/login")
                .post(body)
                .build()
            execAuthCall(req)
        }

    /**
     * Mint a fresh access token using the stored refresh token. The
     * Settings UI can call this at app start to detect token expiry
     * early; routes that get 401 should also fall back here once
     * before forcing a re-login prompt.
     */
    suspend fun refresh(): CloudResult<AuthCredentials> = withContext(Dispatchers.IO) {
        val current = _signedIn.value
            ?: return@withContext CloudResult.Failure(CloudError.NotSignedIn)
        val body = json.encodeToString(
            RefreshRequest.serializer(),
            RefreshRequest(refreshToken = current.refreshToken),
        ).toRequestBody(JSON_MEDIA)
        val req = Request.Builder()
            .url("$baseUrl/v1/account/refresh")
            .post(body)
            .build()
        execAuthCall(req)
    }

    suspend fun forgotPassword(email: String): CloudResult<Unit> = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            ForgotPasswordRequest.serializer(),
            ForgotPasswordRequest(email = email.trim()),
        ).toRequestBody(JSON_MEDIA)
        val req = Request.Builder()
            .url("$baseUrl/v1/account/forgot-password")
            .post(body)
            .build()
        runCatching { http.newCall(req).execute() }
            .map { resp ->
                resp.use { r ->
                    if (r.isSuccessful) CloudResult.Success(Unit)
                    else classifyHttpFailure(r.code, r.body?.string())
                }
            }
            .getOrElse { CloudResult.Failure(CloudError.Network(it.message ?: "Network error")) }
    }

    /** Drop the in-memory + on-disk session. No server call — the JWT
     *  just expires on its own. */
    fun signOut() {
        AuthStore.clear(prefs)
        _signedIn.value = null
    }

    // ─── Rides ────────────────────────────────────────────────────────────

    /**
     * Upload [record] to the rider's `rides` collection. Returns the
     * document ID on success (same as `record.id` — we reuse the
     * local UUID so the client can match server docs to local rides
     * without an extra lookup).
     *
     * The platform's "owner" security rule enforces that
     * `request.auth.uid == resource.data.userId`, so we ALWAYS write
     * userId from [currentCredentials] — never trust the record.
     *
     * Returns [CloudError.NotSignedIn] when the user has no session.
     */
    suspend fun saveRide(record: RideRecord): CloudResult<String> = withContext(Dispatchers.IO) {
        val creds = _signedIn.value
            ?: return@withContext CloudResult.Failure(CloudError.NotSignedIn)
        val docId = record.cloudDocId ?: record.id
        val payload = buildRideDocument(record, creds.userId, docId)
        val body = payload.toString().toRequestBody(JSON_MEDIA)
        val req = Request.Builder()
            .url("$baseUrl/v1/projects/$RIDER_PROJECT_ID/databases/(default)/documents/rides/$docId")
            .header("Authorization", "Bearer ${creds.accessToken}")
            .put(body)
            .build()
        runCatching { http.newCall(req).execute() }
            .map { resp ->
                resp.use { r ->
                    if (r.isSuccessful) CloudResult.Success(docId)
                    else classifyHttpFailure(r.code, r.body?.string())
                }
            }
            .getOrElse { CloudResult.Failure(CloudError.Network(it.message ?: "Network error")) }
    }

    // ─── Internals ────────────────────────────────────────────────────────

    /**
     * Shared parser for `/register`, `/login`, `/refresh` — all three
     * return the same `{success, data: {accessToken, refreshToken, …, user: {…}}}`
     * envelope. Persists the result to encrypted prefs and emits via
     * [signedIn] on success.
     */
    private fun execAuthCall(req: Request): CloudResult<AuthCredentials> {
        return runCatching { http.newCall(req).execute() }
            .map { resp ->
                resp.use { r ->
                    val text = r.body?.string()
                    if (!r.isSuccessful) return@map classifyHttpFailure<AuthCredentials>(r.code, text)
                    val tree = parseJsonBody(text)
                        ?: return@map CloudResult.Failure(CloudError.Server("Empty response"))
                    val data = asObject(tree["data"])
                        ?: return@map CloudResult.Failure(CloudError.Server("Missing data field"))
                    val accessToken = asString(data["accessToken"])
                        ?: return@map CloudResult.Failure(CloudError.Server("Missing accessToken"))
                    val refreshToken = asString(data["refreshToken"])
                        ?: return@map CloudResult.Failure(CloudError.Server("Missing refreshToken"))
                    val user = asObject(data["user"])
                        ?: return@map CloudResult.Failure(CloudError.Server("Missing user"))
                    val creds = AuthCredentials(
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        userId = asString(user["id"])
                            ?: return@map CloudResult.Failure(CloudError.Server("Missing user id")),
                        email = asString(user["email"])
                            ?: return@map CloudResult.Failure(CloudError.Server("Missing email")),
                        displayName = asString(user["displayName"]),
                    )
                    persistAndEmit(creds)
                    CloudResult.Success(creds)
                }
            }
            .getOrElse { CloudResult.Failure(CloudError.Network(it.message ?: "Network error")) }
    }

    private fun persistAndEmit(creds: AuthCredentials) {
        AuthStore.save(prefs, creds)
        _signedIn.value = creds
    }

    private fun parseJsonBody(text: String?): JsonObject? {
        if (text.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    }

    private fun asObject(el: JsonElement?): JsonObject? = el as? JsonObject

    private fun asString(el: JsonElement?): String? = (el as? JsonPrimitive)?.contentOrNull

    /**
     * Map HTTP status + best-effort server-message extraction into a
     * [CloudError]. Server convention is `{success: false, error: "msg"}`
     * for 4xx — we surface that to the UI when present so e.g.
     * "An account with that email already exists" reaches the user verbatim.
     */
    private fun <T> classifyHttpFailure(code: Int, body: String?): CloudResult<T> {
        val parsed = parseJsonBody(body)
        val serverMsg = parsed?.let { asString(it["error"]) ?: asString(it["message"]) }
        val msg = serverMsg ?: "HTTP $code"
        return CloudResult.Failure(
            when (code) {
                401, 403 -> CloudError.Unauthorized(msg)
                in 400..499 -> CloudError.BadRequest(msg)
                else -> CloudError.Server(msg)
            }
        )
    }

    /**
     * Build the rides-collection document the backend will store. The
     * shape is intentionally flat (no Firestore typed-values wrapping)
     * — the Scoova NoSQL platform takes raw JSON.
     *
     * Always sets `userId` from the authenticated session, never from
     * the local record, so a tampered local record can't be uploaded
     * under someone else's identity.
     */
    private fun buildRideDocument(record: RideRecord, userId: String, docId: String): JsonObject =
        buildJsonObject {
            put("id", JsonPrimitive(docId))
            put("userId", JsonPrimitive(userId))
            put("profileId", JsonPrimitive(record.profileId))
            record.destinationLabel?.let { put("destinationLabel", JsonPrimitive(it)) }
            record.destLat?.let { put("destLat", JsonPrimitive(it)) }
            record.destLon?.let { put("destLon", JsonPrimitive(it)) }
            put("distanceKm", JsonPrimitive(record.distanceKm))
            put("durationMin", JsonPrimitive(record.durationMin))
            put("coveredKm", JsonPrimitive(record.coveredKm))
            put("avgKph", JsonPrimitive(record.avgKph))
            put("startedAtMs", JsonPrimitive(record.startedAtMs))
            put("endedAtMs", JsonPrimitive(record.endedAtMs))
            record.path?.let { put("path", JsonPrimitive(it)) }
            record.notes?.let { put("notes", JsonPrimitive(it)) }
        }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        /** Platform-internal project all consumer riders share. Per-user
         *  isolation comes from document-level security rules, not from
         *  per-rider projects. Matches the server's `JWTConfig.RIDER_PROJECT_ID`. */
        const val RIDER_PROJECT_ID = "scoova-riders"
    }
}

// ─── Wire request DTOs ──────────────────────────────────────────────────────

@Serializable
private data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String? = null,
    val locale: String? = null,
)

@Serializable
private data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
private data class RefreshRequest(
    @SerialName("refreshToken") val refreshToken: String,
)

@Serializable
private data class ForgotPasswordRequest(
    @SerialName("email") val email: String,
)
