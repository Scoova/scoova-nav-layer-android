package com.scoova.navlayer.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One sensor tick from the device IMU. Adapters / host apps call
 * [ScoovaNavLayer.onMotion] with one of these per sensor frame
 * (~10–100 Hz depending on platform).
 *
 * All vectors are in the **device-local** frame. [headingDeg] is the
 * single world-frame field — it's the OS-fused magnetic compass
 * heading (Android's `TYPE_ROTATION_VECTOR`, iOS's
 * `CMAttitude.yaw`, browser `DeviceOrientationEvent.alpha`). Either
 * supply it (preferred — already de-tilted and gravity-aligned by the
 * OS) or leave null and the fusion engine will integrate [gyro] to
 * dead-reckon when GPS bearing drops.
 *
 * Every field is optional so an adapter can send partial frames; e.g.
 * a relay that ticks on each sensor independently will send one frame
 * with just `accel`, the next with just `gyro`, etc.
 */
public data class MotionFrame(
    /** Monotonic timestamp in milliseconds. Used for dt calculations. */
    val tsMs: Long,
    /**
     * Linear acceleration (gravity removed), device-local frame, m/s².
     * Used for crash / hard-brake detection on accelerometer magnitude.
     */
    val accel: FloatArray? = null,
    /**
     * Angular velocity, device-local frame, rad/s. Used as a fallback
     * source of heading-delta when the compass loses lock (indoors /
     * near electrical interference / first second after boot).
     */
    val gyro: FloatArray? = null,
    /**
     * World-frame magnetic compass heading in degrees, 0..360
     * (0 = magnetic north, 90 = east). Pre-fused by the OS — DO NOT
     * pass raw magnetometer readings here.
     */
    val headingDeg: Float? = null,
)

/**
 * Output of one motion-fusion update — the engine emits this every
 * call to [MotionFusion.process]. Consumers read what's interesting
 * to them; nulls mean "no signal this tick."
 */
public data class MotionState(
    /** Smoothed compass heading, 0..360, magnetic north reference.
     *  Null until the first valid heading reading arrives. */
    val headingDeg: Float? = null,
    /** If the fusion engine detected a turn completing during this
     *  window, the signed magnitude in degrees (positive = left,
     *  negative = right). Null otherwise. */
    val turnDeg: Float? = null,
    /** If a crash / hard-brake event fired this tick. */
    val crash: CrashEvent? = null,
)

/**
 * Detected adverse motion events the rider should be alerted about.
 * Wrapped in a sealed class so consumers can match exhaustively.
 */
public sealed class CrashEvent {
    public abstract val tsMs: Long
    /** Peak acceleration during the event, expressed in g (1g ≈ 9.81 m/s²). */
    public abstract val peakG: Float

    /** Sudden deceleration — rider braked hard or was rear-ended.
     *  Threshold: `|a| > 8 m/s²` sustained for > 300 ms. */
    public data class HardBrake(override val tsMs: Long, override val peakG: Float) : CrashEvent()

    /** Sudden impact — rider crashed. Threshold: `|a| > 30 m/s²` for
     *  any single sample. Higher confidence than HardBrake. */
    public data class Impact(override val tsMs: Long, override val peakG: Float) : CrashEvent()
}

/**
 * Stateful sensor fusion. One instance per nav session — feed it
 * every [MotionFrame] arriving from the host adapter; it returns a
 * [MotionState] describing what it derived from that frame plus the
 * recent history.
 *
 * Math is intentionally simple (no Kalman filter) so the algorithm
 * ports cleanly to Dart / Swift / TypeScript without dragging in a
 * linear-algebra dependency. The trade-off: short-term GPS-outage
 * dead reckoning is heading-only, not position. (Position dead
 * reckoning needs an EKF and weeks of per-platform tuning — deferred.)
 *
 * Tunables are all `internal const` at the top so the spec can review
 * them in one place.
 */
internal class MotionFusion {
    // ── Tunables ──────────────────────────────────────────────────────
    /** Exponential moving average alpha for compass smoothing. Higher =
     *  more responsive, less stable. 0.25 is ~4-sample low-pass. */
    private val headingEmaAlpha: Float = 0.25f
    /** Sustained yaw magnitude that counts as a "turn completing". */
    private val turnDeltaThresholdDeg: Float = 30f
    /** Window in which we accumulate heading deltas to call a turn. */
    private val turnWindowMs: Long = 4_000L
    /** Crash impact: any single sample exceeds this magnitude (m/s²). */
    private val crashImpactMps2: Float = 30f
    /** Hard brake: sustained magnitude over this for > 300 ms. */
    private val hardBrakeMps2: Float = 8f
    private val hardBrakeDurationMs: Long = 300L

    // ── State ─────────────────────────────────────────────────────────
    private var smoothedHeadingDeg: Float? = null
    private var lastRawHeadingDeg: Float? = null
    private var lastTsMs: Long = 0
    /** Accumulated signed heading change inside the active turn window. */
    private var turnAccum: Float = 0f
    private var turnWindowStartMs: Long = 0
    /** Last time we saw |accel| above the brake threshold. */
    private var brakeStartMs: Long = 0

    fun process(frame: MotionFrame): MotionState {
        val ts = frame.tsMs
        val dtMs = if (lastTsMs > 0) ts - lastTsMs else 0
        lastTsMs = ts

        // ── Heading: prefer OS compass, fall back to gyro integration ──
        val newHeading: Float? = when {
            frame.headingDeg != null -> {
                val h = wrap360(frame.headingDeg)
                val current = smoothedHeadingDeg
                if (current == null) h
                else circularEma(current, h, headingEmaAlpha)
            }
            frame.gyro != null && smoothedHeadingDeg != null && dtMs in 1..200 -> {
                // Gyro z-axis ≈ yaw rate in device frame. Imperfect
                // (assumes phone roughly upright on a handlebar mount)
                // but better than letting heading freeze when compass
                // is briefly unavailable. Convention: positive yaw =
                // left turn = decreasing magnetic heading.
                val dHeading = Math.toDegrees((-frame.gyro[2] * dtMs / 1000.0)).toFloat()
                wrap360(smoothedHeadingDeg!! + dHeading)
            }
            else -> smoothedHeadingDeg
        }
        smoothedHeadingDeg = newHeading

        // ── Turn detection (heading delta accumulating in a 4s window) ──
        var turnFired: Float? = null
        val raw = frame.headingDeg
        if (raw != null) {
            val last = lastRawHeadingDeg
            if (last != null) {
                val delta = headingDelta(last, raw) // signed, in -180..180
                if (turnWindowStartMs == 0L || ts - turnWindowStartMs > turnWindowMs) {
                    turnWindowStartMs = ts
                    turnAccum = 0f
                }
                turnAccum += delta
                if (abs(turnAccum) > turnDeltaThresholdDeg) {
                    turnFired = turnAccum
                    turnAccum = 0f
                    turnWindowStartMs = ts
                }
            }
            lastRawHeadingDeg = raw
        }

        // ── Crash / hard-brake detection ──
        var crash: CrashEvent? = null
        frame.accel?.let { a ->
            val mag = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
            when {
                mag > crashImpactMps2 -> {
                    crash = CrashEvent.Impact(ts, mag / 9.81f)
                    brakeStartMs = 0
                }
                mag > hardBrakeMps2 -> {
                    if (brakeStartMs == 0L) brakeStartMs = ts
                    else if (ts - brakeStartMs > hardBrakeDurationMs) {
                        crash = CrashEvent.HardBrake(ts, mag / 9.81f)
                        brakeStartMs = 0
                    }
                }
                else -> brakeStartMs = 0
            }
        }

        return MotionState(
            headingDeg = smoothedHeadingDeg,
            turnDeg = turnFired,
            crash = crash,
        )
    }

    fun reset() {
        smoothedHeadingDeg = null
        lastRawHeadingDeg = null
        lastTsMs = 0
        turnAccum = 0f
        turnWindowStartMs = 0
        brakeStartMs = 0
    }
}

// ── Math helpers ──────────────────────────────────────────────────────

/** Normalise an angle to [0, 360). */
internal fun wrap360(deg: Float): Float {
    var x = deg % 360f
    if (x < 0) x += 360f
    return x
}

/**
 * Signed shortest-path delta between two headings.
 * `headingDelta(350, 10) == +20`, `headingDelta(10, 350) == -20`.
 * Positive = left turn (counter-clockwise from above in NED). */
internal fun headingDelta(prev: Float, next: Float): Float {
    var d = next - prev
    while (d > 180f) d -= 360f
    while (d < -180f) d += 360f
    return -d  // flip sign so left = positive (matches gyro convention)
}

/**
 * Exponential moving average that respects the 360°→0° wrap-around.
 * Used to smooth compass readings without "snapping" across the boundary.
 */
internal fun circularEma(prev: Float, next: Float, alpha: Float): Float {
    // Average via unit vectors — sums to a stable mean even across 0°/360°.
    val pRad = Math.toRadians(prev.toDouble())
    val nRad = Math.toRadians(next.toDouble())
    val x = (1 - alpha) * cos(pRad) + alpha * cos(nRad)
    val y = (1 - alpha) * sin(pRad) + alpha * sin(nRad)
    return wrap360(Math.toDegrees(Math.atan2(y, x)).toFloat())
}
