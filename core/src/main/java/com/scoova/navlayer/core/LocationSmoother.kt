package com.scoova.navlayer.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Two-dimensional constant-velocity Kalman filter for GPS fixes.
 *
 * **Why this exists.** Raw FusedLocationProvider output, even on a
 * decent device, jitters in the 3–8 m range while the rider is
 * stationary at a red light, and produces zig-zag artefacts on slow
 * pedestrian segments. The puck dances around the screen, the route-
 * snap distance ping-pongs (which forces off-route hysteresis to
 * over-tolerate noise), and turn-by-turn pace cues fire at the wrong
 * meter values. A small Kalman post-filter solves all of that for the
 * cost of ~30 lines of math.
 *
 * **Model.**
 *   • State: (x, y, vx, vy) — position (m) and velocity (m/s) in a
 *     local equirectangular projection.
 *   • State transition: position += velocity·dt (constant-velocity
 *     assumption — good enough for the 1 Hz tick rate we run at).
 *   • Process noise: parameterised by [accelStdMps2] — how aggressively
 *     velocity is allowed to change per second. Tuned to 1.0 for
 *     cyclist / scooter / driver use; for runners we keep it the same
 *     because the 1 Hz cadence dominates the noise budget.
 *   • Measurement: raw GPS fix with reported accuracy. Measurement
 *     variance R = accuracy² (1-σ assumption).
 *
 * **Numerics.** Position is held in metres relative to a moving
 * reference point — after each update we slide the reference to the
 * smoothed location so x/y stay near zero. That avoids float-precision
 * blow-up on long rides where absolute metres-from-equator would
 * approach 10 million.
 *
 * **Outlier rejection.** Each measurement is gated by the Mahalanobis
 * distance √((innovation²)/(P+R)). Values above [outlierSigmaThreshold]
 * are rejected (we skip the update step but still predict forward) —
 * common when a fix from a totally different city sneaks through after
 * cell-tower fallback.
 *
 * **Reset.** Long GPS gaps (>[resetAfterMs]) force a fresh bootstrap
 * because the constant-velocity assumption breaks down. The next fix
 * after the gap is treated as a cold-start measurement, not a delta.
 */
public class ScoovaLocationSmoother(
    public val accelStdMps2: Double = 1.0,
    public val outlierSigmaThreshold: Double = 5.0,
    public val resetAfterMs: Long = 10_000L,
) {

    public data class Smoothed(
        public val tsMs: Long,
        public val lat: Double,
        public val lon: Double,
        public val speedMps: Float,
        public val bearingDeg: Float,
        /** Effective accuracy (1-σ in metres) — derived from the
         *  smoothed covariance, NOT the raw GPS report. Always ≤ raw
         *  unless an outlier was rejected, in which case it can grow
         *  while we wait for the next clean fix. */
        public val accuracyM: Float,
    )

    private var initialized = false
    private var lastTsMs: Long = 0L

    // Reference point — moves with the smoother to keep x/y near zero.
    private var refLat: Double = 0.0
    private var refLon: Double = 0.0
    private var mPerDegLat: Double = 111_320.0
    private var mPerDegLon: Double = 111_320.0

    // State in local metres / m·s⁻¹.
    private var x: Double = 0.0
    private var y: Double = 0.0
    private var vx: Double = 0.0
    private var vy: Double = 0.0

    // Diagonal covariance approximation — full 4×4 doesn't buy enough
    // accuracy here to justify the matrix code, and the diagonal form
    // is numerically robust. Position vs velocity covariances are
    // tracked independently per axis.
    private var pxx: Double = 0.0
    private var pyy: Double = 0.0
    private var pvxvx: Double = 0.0
    private var pvyvy: Double = 0.0

    /**
     * Feed a raw GPS fix; receive a smoothed estimate. Safe to call
     * out-of-order timestamps (treated as a reset), and safe to call
     * with [accuracyM] = 0 (clamped to a small floor so the filter
     * doesn't lock onto a single fix and stop trusting future ones).
     */
    public fun update(
        tsMs: Long,
        lat: Double,
        lon: Double,
        accuracyM: Float,
    ): Smoothed {
        val accClamped = max(accuracyM.toDouble(), 1.0)  // 1 m floor
        if (!initialized || tsMs - lastTsMs > resetAfterMs || tsMs < lastTsMs) {
            initialise(tsMs, lat, lon, accClamped)
            return emitSmoothed(tsMs, accClamped)
        }
        val dtSec = (tsMs - lastTsMs).coerceAtLeast(1L) / 1000.0

        // ── Predict ──────────────────────────────────────────────────
        x += vx * dtSec
        y += vy * dtSec
        // Process-noise contributions: position grows by ½ a·dt², velocity
        // by a·dt. Treated as independent per axis (cross-correlations
        // approximated as zero — adequate for the noise levels here).
        val q = accelStdMps2 * accelStdMps2
        val posQ = (dtSec * dtSec * dtSec * dtSec / 4.0) * q
        val velQ = (dtSec * dtSec) * q
        pxx += posQ
        pyy += posQ
        pvxvx += velQ
        pvyvy += velQ

        // ── Measure ──────────────────────────────────────────────────
        // Project the new fix into local-metres coordinates relative to
        // the *current* reference (which is still the previous smoothed
        // position). Innovation = measurement minus predicted state.
        val measY = (lat - refLat) * mPerDegLat
        val measX = (lon - refLon) * mPerDegLon
        val innX = measX - x
        val innY = measY - y
        val r = accClamped * accClamped

        // Mahalanobis-distance outlier gate. If the fix is wildly
        // outside our 5-σ ellipse, skip the update step — but keep the
        // predicted state. Next clean fix will pull us back in.
        val sX = pxx + r
        val sY = pyy + r
        val mahal2 = (innX * innX) / sX + (innY * innY) / sY
        val sigmaGate = outlierSigmaThreshold * outlierSigmaThreshold * 2  // 2-DoF χ²
        val isOutlier = mahal2 > sigmaGate

        if (!isOutlier) {
            // ── Standard Kalman position+velocity update, per axis ──
            // Kalman gain on position: K = P / (P + R)
            val kxx = pxx / sX
            val kyy = pyy / sY
            x += kxx * innX
            y += kyy * innY
            // Cross-axis pulls a tiny bit of velocity update from the
            // position innovation (since they're correlated through the
            // dynamics). Coefficient `kxx · (vCorr)` with vCorr proxied
            // by dt — empirical and bounded so a single bad fix can't
            // flip the velocity sign by itself.
            val vCoupling = (dtSec * 0.5).coerceIn(0.0, 0.5)
            vx += kxx * vCoupling * (innX / max(dtSec, 0.1))
            vy += kyy * vCoupling * (innY / max(dtSec, 0.1))
            // Shrink position covariance.
            pxx *= (1.0 - kxx)
            pyy *= (1.0 - kyy)
            // Velocity covariance shrinks proportionally to position
            // certainty gain — keeps the relationship stable across
            // long runs.
            pvxvx *= (1.0 - 0.5 * kxx)
            pvyvy *= (1.0 - 0.5 * kyy)
        }

        // Slide the reference to the new smoothed position so x/y stay
        // small. This is exact for the equirectangular projection
        // (longitude stretch follows latitude), only approximate across
        // large dy — but we slide on every tick so it converges.
        slideReferenceToCurrent()
        lastTsMs = tsMs
        return emitSmoothed(tsMs, accClamped)
    }

    /**
     * Drop all state. Next [update] will treat its input as the cold-
     * start measurement. Call when stopping a ride / restarting the
     * pipeline so a 200-km-old velocity doesn't bleed into the next
     * trip.
     */
    public fun reset() {
        initialized = false
        lastTsMs = 0L
        x = 0.0; y = 0.0; vx = 0.0; vy = 0.0
        pxx = 0.0; pyy = 0.0; pvxvx = 0.0; pvyvy = 0.0
    }

    private fun initialise(tsMs: Long, lat: Double, lon: Double, acc: Double) {
        refLat = lat
        refLon = lon
        mPerDegLat = M_PER_DEG_LAT
        mPerDegLon = M_PER_DEG_LAT * cos(Math.toRadians(lat))
        x = 0.0; y = 0.0
        vx = 0.0; vy = 0.0
        // Initial covariance reflects the measurement accuracy for
        // position, and a generous velocity prior (we don't know which
        // way the rider is heading yet).
        pxx = acc * acc
        pyy = acc * acc
        pvxvx = 25.0  // 5 m/s 1-σ
        pvyvy = 25.0
        initialized = true
        lastTsMs = tsMs
    }

    private fun slideReferenceToCurrent() {
        // Convert (x, y) into a lat/lon delta and re-anchor the
        // reference. Re-cache the longitude scale because cos(lat)
        // changes slightly as latitude shifts.
        val dLat = y / mPerDegLat
        val dLon = x / mPerDegLon
        refLat += dLat
        refLon += dLon
        x = 0.0; y = 0.0
        mPerDegLon = M_PER_DEG_LAT * cos(Math.toRadians(refLat))
    }

    private fun emitSmoothed(tsMs: Long, _accIn: Double): Smoothed {
        val speed = sqrt(vx * vx + vy * vy)
        // Bearing only meaningful above a tiny speed floor — at standstill
        // it's just noise. Clamp to 0 below 0.3 m/s (≈ 1 km/h) so the puck
        // doesn't spin when the rider stops.
        val bearing = if (speed > 0.3) {
            val deg = Math.toDegrees(atan2(vx, vy))
            (if (deg < 0) deg + 360.0 else deg)
        } else 0.0
        // Effective accuracy = the larger of x/y 1-σ. Caller may show
        // this as the GPS accuracy ring.
        val accOut = sqrt(max(pxx, pyy)).coerceAtLeast(1.0)
        return Smoothed(
            tsMs = tsMs,
            lat = refLat,
            lon = refLon,
            speedMps = speed.toFloat(),
            bearingDeg = bearing.toFloat(),
            accuracyM = min(accOut, 1000.0).toFloat(),
        )
    }

    public companion object {
        private const val M_PER_DEG_LAT: Double = 111_320.0
    }
}
