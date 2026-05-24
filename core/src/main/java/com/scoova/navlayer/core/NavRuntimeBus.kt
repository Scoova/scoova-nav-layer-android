package com.scoova.navlayer.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Application-scoped event bus for location + motion + ride control.
 *
 * **Why this exists.** Foreground services own the lifecycle of GPS and
 * sensors during a ride (they have to — the activity can die under
 * memory pressure or aggressive OEM battery savers, but a foreground
 * service with `foregroundServiceType="location"` survives). The
 * service needs a way to publish location + motion updates to whatever
 * UI is currently alive (or queue them until a fresh UI shows up).
 *
 * The pattern is the standard "service publishes, UI collects":
 *   1. NavigationService starts the FusedLocationProviderClient and a
 *      SensorRelay when a ride begins. Each callback emits to the
 *      bus.
 *   2. RideViewModel (in whatever activity instance is alive) collects
 *      from the bus and feeds the nav engine.
 *   3. If the activity dies, the service keeps publishing — TTS keeps
 *      firing because the nav engine is on the application scope too.
 *      When the activity comes back, the new VM picks up the latest
 *      values via [replayCache] without missing a beat.
 *
 * Singleton on purpose: there is exactly one rider per device. A
 * second one would mean another instance of this whole module, not a
 * second bus.
 */
public object NavRuntimeBus {

    public data class LocationUpdate(
        public val tsMs: Long,
        public val lat: Double,
        public val lon: Double,
        public val speedMps: Float,
        public val bearingDeg: Float,
        public val accuracyM: Float,
    )

    public data class MotionUpdate(
        public val tsMs: Long,
        public val frame: MotionFrame,
    )

    /**
     * Latest GPS fix. Replay = 1 so a freshly-created collector sees
     * the most recent location immediately rather than waiting for
     * the next provider tick.
     */
    private val _locations = MutableSharedFlow<LocationUpdate>(
        replay = 1,
        extraBufferCapacity = 16,
    )
    public val locations: SharedFlow<LocationUpdate> = _locations.asSharedFlow()

    /**
     * IMU sensor stream. Replay = 0 — motion frames are continuous and
     * a stale one isn't useful to a new collector.
     */
    private val _motion = MutableSharedFlow<MotionUpdate>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    public val motion: SharedFlow<MotionUpdate> = _motion.asSharedFlow()

    /**
     * Rotation-vector accuracy from the sensor manager (0..3, see
     * SensorManager.SENSOR_STATUS_*). Replay = 1 so a re-attached UI
     * can immediately render the compass calibration prompt if needed.
     */
    private val _compassAccuracy = MutableSharedFlow<Int>(replay = 1)
    public val compassAccuracy: SharedFlow<Int> = _compassAccuracy.asSharedFlow()

    @JvmStatic
    public fun publishLocation(update: LocationUpdate) {
        _locations.tryEmit(update)
    }

    @JvmStatic
    public fun publishMotion(frame: MotionFrame) {
        _motion.tryEmit(MotionUpdate(tsMs = frame.tsMs, frame = frame))
    }

    @JvmStatic
    public fun publishCompassAccuracy(accuracy: Int) {
        _compassAccuracy.tryEmit(accuracy)
    }
}
