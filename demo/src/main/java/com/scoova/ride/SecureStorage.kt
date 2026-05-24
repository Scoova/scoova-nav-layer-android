package com.scoova.ride

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Encryption helpers for ride history, settings, and the active-ride
 * snapshot.
 *
 * **Threat model.** A user's ride history is one of the most sensitive
 * data points the app holds — it's literally a tracked GPS trace of
 * everywhere they've been. On a shared family phone or a lost / stolen
 * device, plaintext `rides.json` + `SharedPreferences` would be
 * readable by anyone who can pull the app's private storage (typically
 * via root, USB debugging, or a forensic tool). Encrypting at rest
 * raises the bar from "trivial cat" to "extract the master key from
 * the Keystore" — and on devices with hardware-backed Keystore
 * (Pixel ≥ 3, Galaxy ≥ S8) that's near-infeasible without unlocking
 * the device.
 *
 * **Implementation.**
 *   • Master key: AES-256 GCM, generated once per install, stored in
 *     the Android Keystore. Hardware-backed where the device supports
 *     it; software-fallback otherwise (still a major upgrade vs
 *     plaintext).
 *   • Files: AES-256 GCM with HKDF-derived per-file keys (4 KB chunk
 *     size — the AndroidX default). Bytes on disk look like random
 *     noise to a hex-dump.
 *   • Preferences: keys encrypted with AES-256 SIV (deterministic — so
 *     SharedPreferences.contains() still works), values with AES-256
 *     GCM (probabilistic).
 *
 * Tied to the app's package signature — wiping and reinstalling
 * resets the key, which means a backup of the encrypted file alone is
 * useless on a fresh install. That's intentional: ride history is
 * meant to be a local-only artifact (export-to-JSON is the explicit
 * portability path via Settings → Export data).
 */
internal object SecureStorage {

    private const val PREFS_FILE = "scoova_ride_encrypted"

    @Volatile
    private var cachedMasterKey: MasterKey? = null

    @Synchronized
    fun masterKey(ctx: Context): MasterKey {
        cachedMasterKey?.let { return it }
        val k = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        cachedMasterKey = k
        return k
    }

    /**
     * Wrap a [File] in an [EncryptedFile] handle suitable for both
     * read and write streams. The file location is unchanged — only
     * its bytes are encrypted. Callers should treat the wrapped handle
     * as opaque: use `openFileInput` / `openFileOutput`, not direct
     * `File.readText()` (which would return ciphertext).
     */
    fun encryptedFile(ctx: Context, file: File): EncryptedFile =
        EncryptedFile.Builder(
            ctx,
            file,
            masterKey(ctx),
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()

    /**
     * Encrypted [SharedPreferences] singleton. The underlying file is
     * named [PREFS_FILE]; legacy `scoova_ride` plaintext prefs are
     * migrated on first read by [SettingsStore].
     */
    fun encryptedPrefs(ctx: Context): SharedPreferences =
        EncryptedSharedPreferences.create(
            ctx,
            PREFS_FILE,
            masterKey(ctx),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    /**
     * Read every byte from an [EncryptedFile], returning the decoded
     * UTF-8 string. Returns null on missing file or decryption
     * failure (e.g. file was written before encryption rolled out
     * and the migration step needs to run).
     */
    fun readEncryptedText(ctx: Context, file: File): String? {
        if (!file.exists()) return null
        return runCatching {
            encryptedFile(ctx, file).openFileInput().use { it.readBytes() }
                .toString(Charsets.UTF_8)
        }.getOrNull()
    }

    /**
     * Encrypted overwrite of [file]. EncryptedFile requires the
     * target not to already exist (and binds the encryption to the
     * file's NAME via the AEAD associated-data, so write-tmp-then-
     * rename produces ciphertext that the next reader can't decrypt).
     * So we delete-and-replace in place; the read path uses
     * [runCatching] to handle the rare process-kill-mid-write case
     * by returning a fresh-start state rather than crashing.
     */
    fun writeEncryptedText(ctx: Context, file: File, text: String) {
        runCatching {
            if (file.exists()) file.delete()
            encryptedFile(ctx, file).openFileOutput().use { os ->
                os.write(text.toByteArray(Charsets.UTF_8))
            }
        }
    }
}
