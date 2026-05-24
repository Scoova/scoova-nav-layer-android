package com.scoova.navlayer.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches the rider's location against the active route polyline and
 * flips its [isOffRoute] flag when the rider has clearly drifted away.
 *
 * Design choices and why:
 *   • **Hysteresis** — a single noisy GPS sample can show 30 m of
 *     drift in dense urban canyons; that's a false alarm, not a real
 *     deviation. We require [confirmSamples] consecutive over-threshold
 *     samples before flipping ON, and [confirmSamples] consecutive
 *     under-threshold samples before flipping back OFF. That smooths
 *     out flapping at the threshold.
 *   • **Threshold** — 25 m default is the empirical sweet spot for
 *     urban cyclists (the surveyed audience). Narrower than 25 m and
 *     small position errors fire it; wider than 25 m and you can be a
 *     full block off-route before we notice.
 *   • **Equirectangular polyline math** — see
 *     [GeoMath.distanceToPolylineMeters] for the rationale. Fine for
 *     the metres-scale we care about here.
 *   • **No reroute trigger here** — this class only OBSERVES.
 *     Integrators wire the StateFlow into their reroute logic so the
 *     monitor stays orthogonal to whatever routing client they use.
 *
 * Usage:
 * ```kotlin
 * val monitor = OffRouteMonitor()
 * monitor.setRoute(routeShape)
 * // collect from main scope:
 * scope.launch { monitor.isOffRoute.collect { off -> ... } }
 * // on every location update:
 * monitor.onLocation(lat, lon)
 * ```
 */
public class OffRouteMonitor(
    /** Base distance from the route line (metres) above which a sample
     *  is "off". The effective threshold can grow above this when the
     *  rider's GPS accuracy degrades — see [onLocation]. */
    public val thresholdMeters: Double = 25.0,
    /** Consecutive samples needed to confirm a state flip. */
    public val confirmSamples: Int = 3,
    /** Multiplier on the smoothed accuracy used to derive the effective
     *  threshold when accuracy is poor. With the default 2.0× and a
     *  reported accuracy of 18 m (typical pocket / urban-canyon), the
     *  effective threshold becomes max(25, 36) = 36 m — preventing
     *  false off-route flips while the rider is actually still on
     *  the road. Set to 0 to disable accuracy adaptation. */
    public val accuracyMultiplier: Double = 2.0,
    /** Hard ceiling on the effective threshold (metres). Above this
     *  the monitor stays at the ceiling rather than tracking the
     *  reported accuracy indefinitely — at 200 m of "accuracy" the
     *  fix is meaningless and we shouldn't pretend otherwise. */
    public val maxEffectiveThresholdMeters: Double = 100.0,
) {
    private var polyline: List<DoubleArray> = emptyList()

    /** Running count toward an ON→OFF flip (negative) or OFF→ON flip (positive). */
    private var streak: Int = 0

    /** Last distance measured (metres). Useful for telemetry / debug overlays. */
    private val _lastDistanceMeters = MutableStateFlow(0.0)
    public val lastDistanceMeters: StateFlow<Double> = _lastDistanceMeters.asStateFlow()

    private val _isOffRoute = MutableStateFlow(false)
    public val isOffRoute: StateFlow<Boolean> = _isOffRoute.asStateFlow()

    /**
     * Set or replace the active route. Each element is `[lat, lon]` —
     * matches the shape Scoova Routing returns. Passing an empty list
     * disarms the monitor (it will report [isOffRoute] = false and
     * ignore [onLocation] calls until a non-empty route is set).
     */
    public fun setRoute(latLonPairs: List<DoubleArray>) {
        polyline = latLonPairs
        streak = 0
        _isOffRoute.value = false
        _lastDistanceMeters.value = 0.0
    }

    /**
     * Feed a location sample. Cheap (~few µs for a 1000-point route on
     * an emulator), so calling it on every GPS tick is fine.
     *
     * [accuracyMeters] is the smoothed GPS accuracy (1-σ in metres,
     * available from [ScoovaLocationSmoother.Smoothed.accuracyM]).
     * When the rider is in a pocket / urban canyon and accuracy
     * degrades to 15-30 m, the effective threshold grows so a
     * single noisy fix doesn't flip us off-route. Pass 0 (or use
     * the no-accuracy overload) to keep the static [thresholdMeters].
     */
    @JvmOverloads
    public fun onLocation(lat: Double, lon: Double, accuracyMeters: Double = 0.0) {
        if (polyline.size < 2) return
        val d = GeoMath.distanceToPolylineMeters(lat, lon, polyline)
        _lastDistanceMeters.value = d

        // Effective threshold = max(base, accuracyMultiplier × accuracy),
        // capped at maxEffectiveThresholdMeters. Stops the monitor
        // crying off-route on a single 30 m noise fix in a pocket
        // while still catching real route departures > base threshold.
        val effective = if (accuracyMeters > 0 && accuracyMultiplier > 0) {
            kotlin.math.min(
                maxEffectiveThresholdMeters,
                kotlin.math.max(thresholdMeters, accuracyMultiplier * accuracyMeters),
            )
        } else thresholdMeters

        val over = d > effective
        if (over) {
            // Reset under-streak when we go over; accumulate over-streak.
            if (streak < 0) streak = 0
            streak += 1
            if (!_isOffRoute.value && streak >= confirmSamples) {
                _isOffRoute.value = true
                // Don't reset the streak — we want it sticky so a
                // single back-on-route sample doesn't immediately flip
                // us back. The next sample under threshold will start
                // the under-streak from zero in the else branch.
            }
        } else {
            if (streak > 0) streak = 0
            streak -= 1
            if (_isOffRoute.value && streak <= -confirmSamples) {
                _isOffRoute.value = false
            }
        }
    }

    /** Reset all state without dropping the route. */
    public fun reset() {
        streak = 0
        _isOffRoute.value = false
        _lastDistanceMeters.value = 0.0
    }
}
