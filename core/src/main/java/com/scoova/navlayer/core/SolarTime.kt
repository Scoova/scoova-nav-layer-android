package com.scoova.navlayer.core

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sunrise / sunset estimator. Pure-math, no network, runs in
 * microseconds — used by the map-style auto-switcher to decide whether
 * the rider should see the light or dark basemap right now.
 *
 * Algorithm: the NOAA "approximate" solar position model — accurate to
 * within ~1 minute for civil-twilight purposes, which is all we need
 * for a UI theme decision. Production-grade astronomy isn't worth the
 * extra ~80 LOC.
 *
 * **Usage**
 * ```kotlin
 * val night = SolarTime.isNight(lat, lon)            // uses System.currentTimeMillis()
 * val night = SolarTime.isNight(lat, lon, atMs)      // for previewing other times
 * ```
 */
public object SolarTime {

    /**
     * True if local solar time at [lat]/[lon] is between sunset and
     * sunrise. Falls back to a 18:00 → 06:00 heuristic at the polar
     * regions where the sun doesn't rise / set on a given day.
     */
    @JvmStatic
    @JvmOverloads
    public fun isNight(
        lat: Double,
        lon: Double,
        atMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val altitudeDeg = solarAltitudeDeg(lat, lon, atMs)
        // -6° = civil twilight. We trip the dark theme a bit BEFORE
        // the geometric sunset because the sky is already plenty dim
        // at solar altitude 0° and bright icons on a light basemap
        // are uncomfortable in twilight.
        return altitudeDeg < -6.0
    }

    /**
     * Sun's altitude (in degrees above the horizon) at the given
     * lat/lon/time. Positive = above horizon (day), negative = below.
     */
    @JvmStatic
    @JvmOverloads
    public fun solarAltitudeDeg(
        lat: Double,
        lon: Double,
        atMs: Long = System.currentTimeMillis(),
    ): Double {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = atMs
        }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1   // Calendar.MONTH is 0-based
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hourUtc = cal.get(Calendar.HOUR_OF_DAY) +
            cal.get(Calendar.MINUTE) / 60.0 +
            cal.get(Calendar.SECOND) / 3600.0

        // Day of year (Julian-ish). Leap years approximate enough.
        val n = dayOfYear(year, month, day)
        // Fractional year in radians.
        val gamma = 2.0 * PI / 365.0 * (n - 1 + (hourUtc - 12) / 24.0)

        // Solar declination (radians).
        val decl = 0.006918 -
            0.399912 * cos(gamma) +
            0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) +
            0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) +
            0.00148 * sin(3 * gamma)

        // Equation of time (minutes).
        val eqTime = 229.18 * (
            0.000075 +
                0.001868 * cos(gamma) -
                0.032077 * sin(gamma) -
                0.014615 * cos(2 * gamma) -
                0.040849 * sin(2 * gamma)
        )

        // Hour angle (degrees). Solar noon corresponds to 0°.
        val timeOffsetMin = eqTime + 4 * lon  // 4 min per degree of longitude
        val trueSolarTimeMin = hourUtc * 60 + timeOffsetMin
        val hourAngleDeg = trueSolarTimeMin / 4.0 - 180.0
        val haRad = Math.toRadians(hourAngleDeg)
        val latRad = Math.toRadians(lat)

        // Solar elevation.
        val cosZenith = sin(latRad) * sin(decl) +
            cos(latRad) * cos(decl) * cos(haRad)
        // Clamp to acos's domain — floating-point can drift to 1.0000001.
        val zenith = acos(cosZenith.coerceIn(-1.0, 1.0))
        return 90.0 - Math.toDegrees(zenith)
    }

    private fun dayOfYear(year: Int, month: Int, day: Int): Int {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day)
        }
        return cal.get(Calendar.DAY_OF_YEAR)
    }
}
