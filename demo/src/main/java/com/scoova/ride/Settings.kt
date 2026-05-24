package com.scoova.ride

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * A user-saved destination — Home, Work, or any pinned place.
 *
 * Persisted in [RideSettings]. Used by the Map tab's shortcut row so a
 * user can tap one button to plan a route home / to work.
 */
data class SavedPlace(
    val label: String,
    val lat: Double,
    val lon: Double,
) {
    fun toJsonString(): String = JSONObject().apply {
        put("label", label)
        put("lat", lat)
        put("lon", lon)
    }.toString()

    companion object {
        fun fromJsonString(raw: String?): SavedPlace? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(raw)
                SavedPlace(
                    label = o.getString("label"),
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                )
            }.getOrNull()
        }
    }
}

/**
 * User-level settings persisted across launches.
 *
 * Units, voice and language live here instead of inside ScoovaNavLayer so
 * the user can flip them without rebuilding the layer (we still rebuild it
 * when [locale] changes — that's a hard locale switch on TTS).
 */
data class RideSettings(
    val unitsMetric: Boolean = true,
    val locale: String = "en-US",
    val voiceEnabled: Boolean = true,
    val spatialAudio: Boolean = true,
    val homePlace: SavedPlace? = null,
    val workPlace: SavedPlace? = null,
    /**
     * Starred places — POIs the rider tapped "Save" on from the
     * map. Capped at [MAX_FAVORITES]; oldest entries are dropped
     * past the cap. Distinct from [homePlace] / [workPlace] which
     * have their own dedicated wizard flow.
     */
    val favorites: List<SavedPlace> = emptyList(),
    /**
     * Body weight in kilograms, used by [com.scoova.navlayer.core.RideMetrics]
     * for calorie estimates. Defaults to 70 kg (the figure Apple Fitness
     * uses when the user hasn't supplied one). Riders can adjust on the
     * Settings screen.
     */
    val weightKg: Int = 70,
    /**
     * When true (default), the map basemap auto-switches between Dark
     * and Light using [com.scoova.navlayer.core.SolarTime.isNight] at
     * the rider's current location. When false, the rider's last
     * manual style FAB tap persists across sessions.
     */
    val autoMapTheme: Boolean = true,
    /** First-launch flag — gates the OnboardingScreen value-prop tour.
     *  Flipped true when the rider taps Skip or Get Started. Re-runnable
     *  from Settings → "How does Scoova work?". */
    val onboardingDone: Boolean = false,
    /** Per-install flag — once the rider has dismissed or actioned the
     *  battery-optimisation reliability banner on the Plan map, we
     *  stop showing it. They can still find the toggle in Settings.
     *  Persists across launches so we don't pester. */
    val batteryHintDismissed: Boolean = false,
    /** Eyes-on-the-road navigation mode. When true, the routing server
     *  emits landmark-led voice cues ("After McDonald's, turn right…")
     *  instead of distance-led ones ("In 350 m, turn right…"). The
     *  rider hears something they can act on without translating
     *  metres into block-counts and without glancing at the phone.
     *  Default ON — keeping the rider's eyes on the road is Scoova's
     *  whole reason to exist; riders who'd rather read distances off
     *  the map can switch it off in Settings. */
    val eyesOff: Boolean = true,
    /** Set the first time we surface (or skip) the post-onboarding "Take
     *  a tour?" prompt. We never auto-show it twice — if the rider
     *  dismisses it, they can still launch the tour any time from
     *  Settings → About → "Take a tour". */
    val tourOffered: Boolean = false,
    /**
     * How rides upload to cloud.scoo-va.info after they end. Defaults
     * to [SaveMode.AskEachTime] so we never auto-upload without the
     * rider explicitly opting in — privacy-first. The per-ride
     * Summary screen still lets the rider override this single ride
     * regardless of the global mode.
     */
    val saveMode: SaveMode = SaveMode.AskEachTime,
    /**
     * Master switch for ride tracking. When true (default), each
     * finished trip is recorded — stats, the GPS route trail, and a
     * history entry — and the Summary screen is shown on arrival.
     * When false, Scoova is navigation-only: no trail is collected,
     * no history entry is written, no Summary appears, and the
     * cloud [saveMode] is moot. Riders who only want turn-by-turn
     * guidance can opt out entirely. Existing history is untouched —
     * turning this off stops new recording, it doesn't erase the past.
     */
    val recordRides: Boolean = true,
) {
    public companion object {
        public const val MAX_FAVORITES: Int = 30
    }
}

/**
 * Global default for cloud-uploading completed rides. The rider picks
 * this once in Settings; each ride's Summary screen offers a
 * per-ride override.
 *
 * - [Always]: upload every ride automatically. Summary still shows
 *   a "Don't save this one" override.
 * - [AskEachTime] (default): no upload until the rider taps
 *   "Save this ride" on the Summary screen.
 * - [Never]: never upload, no UI prompt. Local history-only mode.
 *
 * All three modes still write the ride locally to `rides.json` — this
 * setting only controls cloud sync.
 */
enum class SaveMode { Always, AskEachTime, Never }

/** Languages the SDK ships cues for. Order = picker order. */
data class LocaleOption(val tag: String, val display: String, val flag: String)

// Arabic is a single `ar` entry — formal MSA, spoken by on-device TTS.
// The Egyptian / Gulf / Levantine / Maghrebi dialect blocks still exist
// server-side but currently all resolve to MSA, so separate picker rows
// would be a lie. Egyptian gets its row back when its voice pack ships
// as a user-facing option.
val SCOOVA_LOCALES: List<LocaleOption> = listOf(
    LocaleOption("en-US", "English",   "🇺🇸"),
    LocaleOption("ar",    "العربية",   "🌍"),
    LocaleOption("fr",    "Français",  "🇫🇷"),
    LocaleOption("de",    "Deutsch",   "🇩🇪"),
    LocaleOption("es",    "Español",   "🇪🇸"),
    LocaleOption("tr",    "Türkçe",    "🇹🇷"),
)

/** Collapse any persisted Arabic dialect tag (`ar-EG`, `ar-SA`, …) to
 *  the single `ar` entry — keeps a returning rider whose saved locale
 *  is no longer a picker row from landing on a blank selection. */
fun normalizedLocaleTag(tag: String): String =
    if (tag.lowercase().startsWith("ar")) "ar" else tag

internal object SettingsStore {
    private const val LEGACY_PREFS = "scoova_ride"
    private const val K_UNITS_METRIC = "units_metric"
    private const val K_LOCALE = "locale"
    private const val K_VOICE_ENABLED = "voice_enabled"
    private const val K_SPATIAL_AUDIO = "spatial_audio"
    private const val K_HOME = "home_place"
    private const val K_WORK = "work_place"
    private const val K_FAVORITES = "favorite_places"
    private const val K_WEIGHT_KG = "weight_kg"
    private const val K_AUTO_MAP_THEME = "auto_map_theme"
    private const val K_ONBOARDING_DONE = "onboarding_done"
    private const val K_BATTERY_HINT_DISMISSED = "battery_hint_dismissed"
    private const val K_EYES_OFF = "eyes_off"
    private const val K_SAVE_MODE = "save_mode"
    private const val K_TOUR_OFFERED = "tour_offered"
    private const val K_RECORD_RIDES = "record_rides"
    /** Marker key set the first time we migrate from legacy plaintext
     *  to encrypted prefs. Stops the migration from re-running on
     *  every launch. */
    private const val K_MIGRATED_FROM_LEGACY = "_migrated_from_legacy_v1"

    /**
     * Encrypted prefs accessor — migrates from the legacy plaintext
     * `scoova_ride.xml` on first call, then returns the encrypted
     * instance. Migration copies every key/value across, sets the
     * [K_MIGRATED_FROM_LEGACY] marker, and clears the legacy file so
     * no plaintext copy lingers on disk.
     */
    fun prefs(ctx: android.content.Context): SharedPreferences {
        val encrypted = SecureStorage.encryptedPrefs(ctx)
        if (!encrypted.getBoolean(K_MIGRATED_FROM_LEGACY, false)) {
            migrateLegacy(ctx, encrypted)
        }
        return encrypted
    }

    private fun migrateLegacy(ctx: android.content.Context, encrypted: SharedPreferences) {
        val legacy = ctx.getSharedPreferences(LEGACY_PREFS, android.content.Context.MODE_PRIVATE)
        val legacyKeys = legacy.all
        if (legacyKeys.isNotEmpty()) {
            val editor = encrypted.edit()
            for ((k, v) in legacyKeys) {
                when (v) {
                    is Boolean -> editor.putBoolean(k, v)
                    is Int -> editor.putInt(k, v)
                    is Long -> editor.putLong(k, v)
                    is Float -> editor.putFloat(k, v)
                    is String -> editor.putString(k, v)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        (v as? Set<String>)?.let { editor.putStringSet(k, it) }
                    }
                    else -> Unit  // skip unknown types (shouldn't happen with our key set)
                }
            }
            editor.putBoolean(K_MIGRATED_FROM_LEGACY, true).apply()
            legacy.edit().clear().apply()
        } else {
            // No legacy prefs to copy — just stamp the marker so we
            // don't keep retrying the migration.
            encrypted.edit().putBoolean(K_MIGRATED_FROM_LEGACY, true).apply()
        }
    }

    fun load(prefs: SharedPreferences): RideSettings = RideSettings(
        unitsMetric  = prefs.getBoolean(K_UNITS_METRIC, true),
        // First-launch default follows the device locale, picking the
        // closest match Scoova ships. Subsequent launches respect
        // whatever the user picked in Settings. Falls through to en-US
        // when the device language isn't one of our 7 supported locales.
        locale       = normalizedLocaleTag(
                           prefs.getString(K_LOCALE, null) ?: deviceLocaleDefault()),
        voiceEnabled = prefs.getBoolean(K_VOICE_ENABLED, true),
        spatialAudio = prefs.getBoolean(K_SPATIAL_AUDIO, true),
        homePlace    = SavedPlace.fromJsonString(prefs.getString(K_HOME, null)),
        workPlace    = SavedPlace.fromJsonString(prefs.getString(K_WORK, null)),
        favorites    = decodeFavorites(prefs.getString(K_FAVORITES, null)),
        weightKg     = prefs.getInt(K_WEIGHT_KG, 70).coerceIn(30, 200),
        autoMapTheme = prefs.getBoolean(K_AUTO_MAP_THEME, true),
        onboardingDone = prefs.getBoolean(K_ONBOARDING_DONE, false),
        batteryHintDismissed = prefs.getBoolean(K_BATTERY_HINT_DISMISSED, false),
        eyesOff = prefs.getBoolean(K_EYES_OFF, true),
        saveMode = prefs.getString(K_SAVE_MODE, null)?.let {
            runCatching { SaveMode.valueOf(it) }.getOrNull()
        } ?: SaveMode.AskEachTime,
        tourOffered = prefs.getBoolean(K_TOUR_OFFERED, false),
        recordRides = prefs.getBoolean(K_RECORD_RIDES, true),
    )

    private fun decodeFavorites(raw: String?): List<SavedPlace> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                SavedPlace.fromJsonString(arr.optJSONObject(i)?.toString())
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeFavorites(list: List<SavedPlace>): String? {
        if (list.isEmpty()) return null
        val arr = org.json.JSONArray()
        for (p in list) arr.put(org.json.JSONObject(p.toJsonString()))
        return arr.toString()
    }

    /** Map the OS-reported locale to one of the Scoova-shipped tags.
     *  Exact matches win; failing that we accept any "ar-*" as the
     *  single `ar` entry (every dialect block currently resolves to
     *  MSA — see SCOOVA_LOCALES comment) and fall back to en-US for
     *  anything else. */
    private fun deviceLocaleDefault(): String {
        val sys = java.util.Locale.getDefault().toLanguageTag()
        // Exact-tag match
        SCOOVA_LOCALES.firstOrNull { it.tag.equals(sys, ignoreCase = true) }
            ?.let { return it.tag }
        // Base-language match (e.g. "fr-CA" → "fr")
        val base = sys.substringBefore('-').lowercase()
        SCOOVA_LOCALES.firstOrNull { it.tag == base }?.let { return it.tag }
        // Any Arabic dialect → ar (single MSA picker row)
        if (base == "ar") return "ar"
        return "en-US"
    }

    fun save(prefs: SharedPreferences, s: RideSettings) {
        prefs.edit()
            .putBoolean(K_UNITS_METRIC, s.unitsMetric)
            .putString(K_LOCALE, s.locale)
            .putBoolean(K_VOICE_ENABLED, s.voiceEnabled)
            .putBoolean(K_SPATIAL_AUDIO, s.spatialAudio)
            .putString(K_HOME, s.homePlace?.toJsonString())
            .putString(K_WORK, s.workPlace?.toJsonString())
            .putString(K_FAVORITES, encodeFavorites(s.favorites))
            .putInt(K_WEIGHT_KG, s.weightKg)
            .putBoolean(K_AUTO_MAP_THEME, s.autoMapTheme)
            .putBoolean(K_ONBOARDING_DONE, s.onboardingDone)
            .putBoolean(K_BATTERY_HINT_DISMISSED, s.batteryHintDismissed)
            .putBoolean(K_EYES_OFF, s.eyesOff)
            .putString(K_SAVE_MODE, s.saveMode.name)
            .putBoolean(K_TOUR_OFFERED, s.tourOffered)
            .putBoolean(K_RECORD_RIDES, s.recordRides)
            .apply()
    }
}
