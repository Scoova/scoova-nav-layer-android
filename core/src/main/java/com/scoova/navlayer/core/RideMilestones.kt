package com.scoova.navlayer.core

/**
 * Detects when a rider's lifetime distance crosses a celebratory
 * threshold — the moments a host app should pop a "🎉 You've ridden
 * 100 km on Scoova!" toast.
 *
 * Pure stateless math: given the rider's *previous* lifetime total
 * and their *new* total after the latest ride, returns the threshold
 * that was just crossed (or null if none).
 *
 * Thresholds were picked to land roughly once a month for an average
 * commuter, plus once a year for a serious one — frequent enough to
 * feel rewarding, rare enough not to spam.
 */
public object RideMilestones {

    /**
     * Distance thresholds in km. Crossing any of these (from below)
     * triggers a celebration. First entry doubles as the "you started
     * tracking!" moment.
     */
    @JvmStatic
    public val THRESHOLDS_KM: IntArray = intArrayOf(
        1, 10, 50, 100, 250, 500, 1_000, 2_500, 5_000, 10_000,
    )

    public data class Crossed(val km: Int)

    /**
     * Returns the highest threshold that was just crossed by going
     * from [previousTotalKm] to [newTotalKm]. Null when no threshold
     * was crossed (or when previous already includes new — a no-op).
     *
     * If a single ride crosses MULTIPLE thresholds (the rider's first
     * ever ride and it happens to be 120 km), the highest threshold
     * wins — celebrating "100" is more meaningful than "10".
     */
    @JvmStatic
    public fun lastCrossed(previousTotalKm: Double, newTotalKm: Double): Crossed? {
        if (newTotalKm <= previousTotalKm) return null
        return THRESHOLDS_KM
            .lastOrNull { previousTotalKm < it && newTotalKm >= it }
            ?.let { Crossed(it) }
    }
}
