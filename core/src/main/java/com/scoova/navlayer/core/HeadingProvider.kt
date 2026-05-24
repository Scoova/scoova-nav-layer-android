package com.scoova.navlayer.core

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.abs
import kotlin.math.atan2

/**
 * TRUE-NORTH heading in degrees [0, 360) from the rotation-vector
 * sensor. Drives the heading-up follow camera + the puck's heading cone.
 *
 * ## Why this is computed directly from the rotation matrix
 *
 * `SensorManager.getOrientation()` returns an azimuth that is only
 * correct when the phone is held **flat** (screen up). When the phone
 * is held **upright** — the normal posture for a rider glancing at the
 * map, or a phone in a handlebar mount — the device's Y axis points at
 * the sky, the azimuth getOrientation() reports goes degenerate, and
 * the heading can land ~180° wrong. That is what made the map spin the
 * wrong way. No magic offset fixes it (an earlier `-45°` / `180°` fudge
 * was exactly the wrong cure).
 *
 * Instead we read the rotation matrix `R` directly. `R` maps device
 * axes into the world ENU frame (X-East, Y-North, Z-Up). We pick
 * whichever device axis is currently the most **horizontal** as the
 * heading reference, and take its compass azimuth:
 *
 *   • phone flat    → the device **Y** axis (top edge) is horizontal
 *   • phone upright → the device **-Z** axis (the back / camera) is
 *                     horizontal — and points where the rider faces
 *
 * This is correct in both postures with no offset. Then:
 *   • `+ GeomagneticField.declination` — magnetic north → true north.
 *   • EMA smoothing (alpha 0.20) on the circular angle so the map
 *     doesn't shiver on raw sensor jitter.
 */
public class HeadingProvider(
    private val ctx: Context,
    private val locationProvider: () -> Location? = { null },
) {

    public fun stream(): Flow<Float> = callbackFlow {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // ROTATION_VECTOR (accel + gyro + magnetometer) gives an
        // absolute, drift-free heading. GEOMAGNETIC_ROTATION_VECTOR
        // (accel + magnetometer, no gyro) is the fallback — jitterier
        // but still north-referenced. GAME_ROTATION_VECTOR is
        // deliberately NOT used: it has no magnetometer, so it has no
        // true-north reference and drifts freely — useless as a compass.
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        if (sensor == null) { close(); return@callbackFlow }

        val r = FloatArray(9)

        fun normalize360(d: Float): Float {
            var v = d % 360f
            if (v < 0f) v += 360f
            return v
        }

        var lastOut: Float? = null

        val listener = object : SensorEventListener {
            override fun onSensorChanged(ev: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(r, ev.values)
                // r maps device → world ENU. Device axes in world coords:
                //   device  Y (top edge)   = (r[1], r[4], r[7])
                //   device -Z (back/camera)= (-r[2], -r[5], -r[8])
                // The vertical (Up) component is r[7] and -r[8]. The axis
                // with the smaller |vertical| is the one lying flat
                // enough to yield a meaningful compass azimuth; use it.
                // Azimuth (clockwise from North) of a world vector
                // (E, N, U) is atan2(E, N).
                val deg = if (abs(r[7]) <= abs(r[8])) {
                    // Phone flat-ish — heading of the top edge.
                    Math.toDegrees(atan2(r[1], r[4]).toDouble()).toFloat()
                } else {
                    // Phone upright — heading the back of the phone faces.
                    Math.toDegrees(atan2(-r[2], -r[5]).toDouble()).toFloat()
                }

                var heading = deg
                // Magnetic north → true north with the local declination.
                val loc = locationProvider()
                if (loc != null) {
                    val field = GeomagneticField(
                        loc.latitude.toFloat(),
                        loc.longitude.toFloat(),
                        loc.altitude.toFloat(),
                        System.currentTimeMillis(),
                    )
                    heading += field.declination
                }

                var out = normalize360(heading)
                // EMA smoothing on the circular angle — shortest-path
                // interpolation so the wrap at 360/0 doesn't whip.
                lastOut?.let { prev ->
                    val diff = (((out - prev + 540f) % 360f) - 180f)
                    val alpha = 0.20f
                    out = normalize360(prev + alpha * diff)
                }
                lastOut = out
                trySend(out)
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }

        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sm.unregisterListener(listener) }
    }
}
