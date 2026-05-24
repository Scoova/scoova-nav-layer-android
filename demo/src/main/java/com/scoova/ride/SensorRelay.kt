package com.scoova.ride

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import com.scoova.navlayer.core.MotionFrame

/**
 * Bridges Android's [SensorManager] into the nav layer's
 * `nav.onMotion()` channel. Subscribes to three sensors:
 *
 *   * [Sensor.TYPE_ROTATION_VECTOR]   → world-frame compass heading
 *   * [Sensor.TYPE_LINEAR_ACCELERATION] → device-frame accel, gravity removed
 *   * [Sensor.TYPE_GYROSCOPE]         → device-frame angular velocity
 *
 * Each sensor fires asynchronously at ~50 Hz (GAME delay). On every
 * event we coalesce the latest readings into a fresh [MotionFrame] and
 * forward via [onFrame]. Doing it per-event (rather than buffering)
 * keeps latency low — the fusion engine only does work when something
 * actually changed.
 *
 * Battery: the three sensors at SENSOR_DELAY_GAME draw ~3–6 mW on a
 * Snapdragon 8-gen-2. Negligible compared to GPS + tile rendering.
 */
class SensorRelay(
    context: Context,
    private val onFrame: (MotionFrame) -> Unit,
    /**
     * Fired whenever the rotation-vector sensor's reported accuracy
     * changes. Pass-through of [SensorManager.SENSOR_STATUS_*]:
     *   * `SENSOR_STATUS_ACCURACY_HIGH` (3) — calibrated.
     *   * `SENSOR_STATUS_ACCURACY_MEDIUM` (2) — usable.
     *   * `SENSOR_STATUS_ACCURACY_LOW` (1) — heading drifts heavily.
     *   * `SENSOR_STATUS_UNRELIABLE` (0) — wave the phone to recalibrate.
     * The host VM watches this to decide whether to prompt the rider
     * for the figure-8 calibration wave.
     */
    private val onCompassAccuracy: (Int) -> Unit = {},
) {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotation: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val linAccel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var latestHeadingDeg: Float? = null
    private var latestAccel: FloatArray? = null
    private var latestGyro: FloatArray? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val tsMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                // event.timestamp is nanoseconds since boot — convert to wall clock
                System.currentTimeMillis()
            } else {
                System.currentTimeMillis()
            }
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val q = FloatArray(4)
                    SensorManager.getQuaternionFromVector(q, event.values)
                    // Heading = yaw of the quaternion converted to compass deg.
                    // q is [w, x, y, z] from getQuaternionFromVector (note Android
                    // doc convention).
                    val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
                    val sinYcosP = 2.0 * (w * z + x * y)
                    val cosYcosP = 1.0 - 2.0 * (y * y + z * z)
                    val yawRad = kotlin.math.atan2(sinYcosP, cosYcosP)
                    var deg = Math.toDegrees(yawRad).toFloat()
                    if (deg < 0) deg += 360f
                    latestHeadingDeg = deg
                }
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    latestAccel = event.values.copyOf()
                }
                Sensor.TYPE_GYROSCOPE -> {
                    latestGyro = event.values.copyOf()
                }
                else -> return
            }
            onFrame(
                MotionFrame(
                    tsMs = tsMs,
                    accel = latestAccel,
                    gyro = latestGyro,
                    headingDeg = latestHeadingDeg,
                ),
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // Only forward the rotation-vector accuracy — that's what
            // drives the heading drift. Gyro / linear-accel accuracy
            // doesn't help the rider make a decision.
            if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                onCompassAccuracy(accuracy)
            }
        }
    }

    fun start() {
        // SENSOR_DELAY_GAME ≈ 20 ms. Plenty for turn detection on a
        // scooter (~30°/s yaw rate) without burning battery on UI delay.
        rotation?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        linAccel?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        gyro?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sm.unregisterListener(listener)
    }
}
