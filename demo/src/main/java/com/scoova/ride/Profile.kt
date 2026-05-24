package com.scoova.ride

import androidx.compose.ui.graphics.Color
import com.scoova.navlayer.core.CueTone
import com.scoova.navlayer.maplibre.PathHighlightMode

/**
 * The five movers Scoova ships with — keyed on the DEVICE the rider is
 * travelling on, not the activity. Walking and running are the same
 * device (your feet), so they collapse into one "On foot" profile;
 * what changes the routing + cue behaviour is the vehicle, not whether
 * the rider is jogging or strolling.
 *
 * Each profile maps to:
 *   • a Scoova routing profile (what to ask the routing engine for)
 *   • a default cue tone (on foot hears calm; a car hears urgent)
 *   • an accent color (the only thing in the UI that's profile-specific)
 */
enum class Profile(
    val id: String,
    val display: String,
    val emoji: String,
    val tagline: String,
    val routingProfile: String,
    val defaultTone: CueTone,
    val accent: Color,
) {
    // `display` and `tagline` are the English defaults kept for any
    // legacy reader. Localised reads should use [Profile.localizedDisplay]
    // / [Profile.localizedTagline] below so the chooser, settings, and
    // banner render in the user's chosen language.
    Foot(
        id = "foot",
        display = "On foot",
        emoji = "🚶",
        tagline = "Walking or running — calm cues, sidewalk routes.",
        routingProfile = "pedestrian",
        defaultTone = CueTone.Calm,
        accent = Color(0xFF7DD3FC),
    ),
    Bicycle(
        id = "bicycle",
        display = "Bicycle",
        emoji = "🚴",
        tagline = "Bike routes. Mid-range cues.",
        routingProfile = "bicycle",
        defaultTone = CueTone.Normal,
        accent = Color(0xFF0EA5E9),
    ),
    Scooter(
        id = "scooter",
        display = "Scooter",
        emoji = "🛴",
        tagline = "Scooter pace. Curb-aware.",
        // Urban kick / e-scooter — same lane vocabulary as a bike, NOT
        // the Vespa-class motor scooter Valhalla's `scooter` costing
        // was designed for. We route as `bicycle` so the proxy's
        // cyclist-friendly defaults (use_roads=0.2, bicycle_type=hybrid,
        // avoid_bad_surfaces=0.25) take effect and the route lands on
        // the cycleways the map highlights for this persona.
        routingProfile = "bicycle",
        defaultTone = CueTone.Normal,
        accent = Color(0xFF38BDF8),
    ),
    Motorcycle(
        id = "motorcycle",
        display = "Motorcycle",
        emoji = "🏍️",
        tagline = "Motorbike routes. Quick, urgent cues.",
        routingProfile = "motorcycle",
        defaultTone = CueTone.Urgent,
        accent = Color(0xFFA855F7),
    ),
    Car(
        id = "car",
        display = "Car",
        emoji = "🚗",
        tagline = "Highway speeds. Eyes up.",
        routingProfile = "auto",
        defaultTone = CueTone.Urgent,
        accent = Color(0xFFEF4444),
    );

    /** Strings key for the display name (e.g. "profile.car.display"). */
    val displayKey: String get() = "profile.$id.display"
    /** Strings key for the one-line tagline. */
    val taglineKey: String get() = "profile.$id.tagline"

    /** Map a device to its [com.scoova.navlayer.core.RideMetrics.Mode]
     *  for calorie / active-minutes computation. Motorcycle + Car map
     *  to a near-zero-MET Driving (motorised, mostly passive). */
    val metricsMode: com.scoova.navlayer.core.RideMetrics.Mode get() = when (this) {
        Foot -> com.scoova.navlayer.core.RideMetrics.Mode.Walking
        Bicycle -> com.scoova.navlayer.core.RideMetrics.Mode.Cycling
        Scooter -> com.scoova.navlayer.core.RideMetrics.Mode.Scootering
        Motorcycle -> com.scoova.navlayer.core.RideMetrics.Mode.Driving
        Car -> com.scoova.navlayer.core.RideMetrics.Mode.Driving
    }

    /** Which path-highlight bucket the map style uses for this persona.
     *  Drives `ScoovaStylePatcher.splitPathsByMode` — cycleway bright on
     *  a bike or scooter, footway bright on foot, everything muted in a
     *  motorcycle or car. Mirrors the iOS extension on `Profile`. */
    val pathHighlightMode: PathHighlightMode get() = when (this) {
        Bicycle, Scooter -> PathHighlightMode.Bike
        Foot             -> PathHighlightMode.Foot
        Motorcycle, Car  -> PathHighlightMode.Motor
    }

    companion object {
        fun fromId(id: String?): Profile? {
            if (id == null) return null
            entries.firstOrNull { it.id == id }?.let { return it }
            // Migrate the legacy activity-based ids saved by builds
            // before the device-based refactor.
            return when (id) {
                "walker", "runner" -> Foot
                "cyclist" -> Bicycle
                "driver" -> Car
                "courier" -> Motorcycle
                else -> null
            }
        }
    }
}

/** Localised display name. Falls back to the enum's English [display]
 *  when the locale tables don't carry an entry. */
fun Profile.localizedDisplay(locale: String): String {
    val translated = Strings.t(displayKey, locale)
    return if (translated == displayKey) display else translated
}

/** Localised tagline. Falls back to the English [tagline]. */
fun Profile.localizedTagline(locale: String): String {
    val translated = Strings.t(taglineKey, locale)
    return if (translated == taglineKey) tagline else translated
}
