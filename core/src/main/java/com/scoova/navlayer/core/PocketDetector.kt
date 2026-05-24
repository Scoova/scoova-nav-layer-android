package com.scoova.navlayer.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Decides whether the rider is holding the phone (handheld) or has it
 * stowed in a pocket / bag / mount. The signal matters for two
 * downstream behaviours:
 *
 *   • **Off-route tolerance.** In a pocket the body absorbs satellite
 *     signal and GPS accuracy drops from ~3 m handheld to ~15-30 m
 *     stowed. [OffRouteMonitor]'s fixed 25 m threshold would
 *     misclassify riders standing still at a red light. With the
 *     pocket signal, it widens to ~2× accuracy.
 *
 *   • **Confirmation confidence.** [EyesOffGuide]'s yaw-side gate
 *     should be more skeptical when the phone is in a pocket — the
 *     accel/gyro pattern is dominated by step cadence, not by the
 *     rider's actual rotation. We give the GPS-side gate proportionally
 *     more weight when pocket = true.
 *
 * **Algorithm.** Maintain a rolling window of total-acceleration
 * magnitudes (|accel| - g) and compute the variance. Two regimes:
 *
 *   • **Handheld** — variance is small (< 0.5 m²/s⁴ on a steady
 *     hand); the phone moves with the rider's torso, no leg-swing
 *     oscillation.
 *   • **Pocket / bag** — variance is high (>= 2.0 m²/s⁴ during
 *     walking/cycling); leg swing produces a periodic 1–2 Hz
 *     oscillation that shows up as large variance.
 *
 * Between the two we have a hysteresis band: a phone has to spend
 * [dwellSec] continuously above 2.0 to flip to pocket, and the same
 * dwell continuously below 0.5 to flip back. Without dwell the
 * classifier would chatter every time the rider stops at a light or
 * picks up the phone briefly.
 *
 * **Not a hardware-proximity API.** We deliberately don't use the
 * proximity sensor — it doesn't fire when the phone is stationary
 * in a pocket facing outward, and many devices report stale "near"
 * indefinitely. Accelerometer variance is robust to all phone
 * orientations and works on every device that has an accel.
 */
public class PocketDetector(
    private val windowSamples: Int = 50,            // ~2.5 s at 20 Hz IMU
    private val pocketEnterVarianceThreshold: Double = 2.0,
    private val pocketExitVarianceThreshold: Double = 0.5,
    private val dwellSec: Double = 3.0,
) {

    private val window: DoubleArray = DoubleArray(windowSamples)
    private var writeIdx: Int = 0
    private var filled: Int = 0
    private var lastTsMs: Long = 0L
    /** Tracks how long we've been in the "potential transition" state
     *  for hysteresis. Positive = candidate for pocket; negative =
     *  candidate for handheld. */
    private var candidateDwellSec: Double = 0.0

    private val _phoneInPocket = MutableStateFlow(false)
    public val phoneInPocket: StateFlow<Boolean> = _phoneInPocket.asStateFlow()

    /**
     * Feed a motion frame. Reads `accel` and updates the rolling
     * window. Cheap — O(1) per call. Safe to call from any thread;
     * StateFlow handles cross-thread reads.
     */
    public fun onMotion(frame: MotionFrame) {
        val a = frame.accel ?: return
        if (a.size < 3) return
        // Subtract gravity magnitude — what we care about is *linear*
        // acceleration deltas. We don't have the linear-acceleration
        // sensor wired here, so subtract the constant ~9.81 from the
        // norm. Not perfect (it includes orientation noise), but
        // good enough to separate "still" from "leg-swing".
        val mag = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]) - 9.81
        window[writeIdx] = mag.toDouble()
        writeIdx = (writeIdx + 1) % windowSamples
        if (filled < windowSamples) filled++

        if (filled < windowSamples / 2) return  // need at least half-window before classifying

        // Compute variance over the populated window. Single-pass
        // mean + sum-of-squares is fine at this size.
        var mean = 0.0
        for (i in 0 until filled) mean += window[i]
        mean /= filled
        var ss = 0.0
        for (i in 0 until filled) {
            val d = window[i] - mean
            ss += d * d
        }
        val variance = ss / filled

        // Hysteresis dwell — convert frame timestamp into seconds-
        // accumulated in the candidate state.
        val nowMs = frame.tsMs
        val dt = if (lastTsMs == 0L) 0.0
            else ((nowMs - lastTsMs).coerceAtLeast(0L)) / 1000.0
        lastTsMs = nowMs

        val isPocket = _phoneInPocket.value
        when {
            !isPocket && variance > pocketEnterVarianceThreshold -> {
                candidateDwellSec = (candidateDwellSec + dt).coerceAtLeast(0.0)
                if (candidateDwellSec >= dwellSec) {
                    _phoneInPocket.value = true
                    candidateDwellSec = 0.0
                }
            }
            isPocket && variance < pocketExitVarianceThreshold -> {
                candidateDwellSec = (candidateDwellSec - dt).coerceAtMost(0.0)
                if (candidateDwellSec <= -dwellSec) {
                    _phoneInPocket.value = false
                    candidateDwellSec = 0.0
                }
            }
            else -> {
                // In the gap (between exit and enter thresholds) or
                // moving in the wrong direction — let the dwell timer
                // decay back to zero so transient excursions don't
                // accumulate.
                candidateDwellSec *= 0.9
            }
        }
    }

    /** Clear the rolling window + dwell + state. Used on ride start. */
    public fun reset() {
        for (i in 0 until windowSamples) window[i] = 0.0
        writeIdx = 0
        filled = 0
        lastTsMs = 0L
        candidateDwellSec = 0.0
        _phoneInPocket.value = false
    }
}
