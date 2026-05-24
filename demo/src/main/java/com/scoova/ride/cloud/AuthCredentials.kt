package com.scoova.ride.cloud

import android.content.Context
import android.content.SharedPreferences
import com.scoova.ride.SecureStorage

/**
 * Credentials a signed-in rider holds after register / login against
 * the consumer backend at `my.scoo-va.info`.
 *
 * Pure JWT scheme — no per-rider apiKey, no per-rider project. All
 * riders share the platform-internal project ("scoova-riders") for
 * their document writes; per-document security rules enforce isolation.
 *
 * - [accessToken]: short-lived (24 h) Bearer; used for every API call.
 * - [refreshToken]: long-lived (30 d); the only way to mint a new
 *   accessToken without re-prompting for the password. Stored
 *   encrypted alongside the access token; never sent except to
 *   `/v1/account/refresh`.
 * - [userId] / [email] / [displayName]: identity for the Settings →
 *   Account row + for use in the rides-collection's userId field.
 */
data class AuthCredentials(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val email: String,
    val displayName: String?,
)

/**
 * Encrypted-prefs-backed store for the rider's cloud-account session.
 *
 * Shares the same encrypted SharedPreferences as [com.scoova.ride.SettingsStore]
 * — one file (`scoova_ride_encrypted.xml`), AES-256 GCM at rest via
 * [SecureStorage]. Keys are namespaced under `auth_` so they don't
 * collide with regular settings.
 *
 * Signed-out state is represented by [load] returning null. Sign-out
 * clears every `auth_` key in one batch.
 */
internal object AuthStore {
    private const val K_ACCESS_TOKEN = "auth_access_token"
    private const val K_REFRESH_TOKEN = "auth_refresh_token"
    private const val K_USER_ID = "auth_user_id"
    private const val K_EMAIL = "auth_email"
    private const val K_DISPLAY_NAME = "auth_display_name"

    fun prefs(ctx: Context): SharedPreferences = SecureStorage.encryptedPrefs(ctx)

    fun load(prefs: SharedPreferences): AuthCredentials? {
        val accessToken = prefs.getString(K_ACCESS_TOKEN, null) ?: return null
        val refreshToken = prefs.getString(K_REFRESH_TOKEN, null) ?: return null
        val userId = prefs.getString(K_USER_ID, null) ?: return null
        val email = prefs.getString(K_EMAIL, null) ?: return null
        return AuthCredentials(
            accessToken = accessToken,
            refreshToken = refreshToken,
            userId = userId,
            email = email,
            displayName = prefs.getString(K_DISPLAY_NAME, null),
        )
    }

    fun save(prefs: SharedPreferences, creds: AuthCredentials) {
        prefs.edit()
            .putString(K_ACCESS_TOKEN, creds.accessToken)
            .putString(K_REFRESH_TOKEN, creds.refreshToken)
            .putString(K_USER_ID, creds.userId)
            .putString(K_EMAIL, creds.email)
            .putString(K_DISPLAY_NAME, creds.displayName)
            .apply()
    }

    fun clear(prefs: SharedPreferences) {
        prefs.edit()
            .remove(K_ACCESS_TOKEN)
            .remove(K_REFRESH_TOKEN)
            .remove(K_USER_ID)
            .remove(K_EMAIL)
            .remove(K_DISPLAY_NAME)
            .apply()
    }
}
