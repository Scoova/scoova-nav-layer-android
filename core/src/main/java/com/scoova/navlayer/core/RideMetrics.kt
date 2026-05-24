package com.scoova.navlayer.core

import kotlin.math.roundToInt

/**
 * Health-style ride metrics: calories burned, active minutes, average
 * speed. Used by Summary screens, history rows, and weekly stats.
 *
 * Calorie math uses the Compendium of Physical Activities MET values
 * (Ainsworth et al. 2011) — the same numbers Apple Fitness and Google
 * Fit derive their estimates from. Coarse but well-calibrated for
 * moderate effort levels; absolute accuracy is impossible without
 * heart-rate input, but trend-fidelity is what users actually use it
 * for ("did I burn more this week than last week?").
 *
 * Defaults assume a 70 kg rider — host apps with a real body-weight
 * value should pass it. The Settings screen could expose a slider
 * later; v1 just uses the default.
 */
public object RideMetrics {

    /** Sport / mode used to look up the MET value. */
    public enum class Mode { Cycling, Walking, Running, Driving, Scootering }

    /**
     * MET (Metabolic Equivalent of Task) by mode + average speed.
     * Returns ~3.5 for very slow movement so we don't claim 0 for
     * a leisurely 5 km/h walk.
     */
    @JvmStatic
    public fun metFor(mode: Mode, speedKph: Double): Double = when (mode) {
        Mode.Cycling -> when {
            speedKph < 16 -> 5.8   // Compendium "bicycling, leisure, < 10 mph"
            speedKph < 20 -> 6.8   // "bicycling, 10–11.9 mph, light effort"
            speedKph < 23 -> 8.0   // "bicycling, 12–13.9 mph, moderate"
            speedKph < 27 -> 10.0  // "bicycling, 14–15.9 mph, vigorous"
            else          -> 12.0  // "bicycling, 16–19 mph, racing"
        }
        Mode.Walking -> when {
            speedKph < 3.5 -> 2.5  // "walking, < 2 mph, very slow"
            speedKph < 5.0 -> 3.0  // "walking, 2–2.9 mph"
            speedKph < 6.5 -> 3.8  // "walking, 3 mph, moderate"
            else           -> 5.0  // "walking, 4 mph, brisk"
        }
        Mode.Running -> when {
            speedKph < 8  -> 8.0
            speedKph < 10 -> 9.8
            speedKph < 13 -> 11.5
            else          -> 14.0
        }
        Mode.Scootering -> 4.5     // kick-scooter; "self-powered, electric assist"
        Mode.Driving -> 1.5        // passive — light upper-body
    }

    /**
     * Estimated calories burned in kcal. Returns 0 for a non-trip
     * (duration < 1 min) so a paused / cancelled ride doesn't claim
     * spurious calorie counts.
     */
    @JvmStatic
    @JvmOverloads
    public fun caloriesBurned(
        mode: Mode,
        distanceKm: Double,
        durationMinutes: Int,
        weightKg: Double = DEFAULT_WEIGHT_KG,
    ): Int {
        if (durationMinutes < 1 || distanceKm <= 0) return 0
        val hours = durationMinutes / 60.0
        val speedKph = if (hours > 0) distanceKm / hours else 0.0
        val met = metFor(mode, speedKph)
        // kcal = MET * weight_kg * hours
        return (met * weightKg * hours).roundToInt()
    }

    /**
     * "Active minutes" — minutes spent in motion above a movement
     * threshold. For the v1 stats, we just count durationMinutes when
     * mode is Cycling/Walking/Running/Scootering (driving doesn't
     * count). Future versions could fold in the actual GPS speed
     * trail to subtract red-light stops.
     */
    @JvmStatic
    public fun activeMinutes(mode: Mode, durationMinutes: Int): Int = when (mode) {
        Mode.Driving -> 0
        else -> durationMinutes.coerceAtLeast(0)
    }

    /**
     * Grams of CO₂ a rider avoided emitting by NOT taking a car for
     * this trip. Returns 0 for Mode.Driving (driving doesn't save CO₂)
     * and for sub-100 m trips (rounding noise).
     *
     * Baseline: 170 g CO₂ / km — the EU passenger-car fleet average
     * for 2023 (EEA, "CO2 emissions from new passenger cars" 2024).
     * Slightly conservative vs the US fleet average (~250 g/km) to
     * keep the number defensible for global audiences.
     */
    @JvmStatic
    public fun co2SavedGrams(mode: Mode, distanceKm: Double): Int {
        if (mode == Mode.Driving) return 0
        if (distanceKm < 0.1) return 0
        return (distanceKm * 170.0).roundToInt()
    }

    public const val DEFAULT_WEIGHT_KG: Double = 70.0
}
