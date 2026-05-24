package com.scoova.ride

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.scoova.navlayer.core.NavRuntimeBus
import com.scoova.navlayer.core.ScoovaLocationSmoother
import com.scoova.navlayer.core.ScoovaNavNotificationBuilder
import com.scoova.navlayer.core.ScoovaNavNotificationContent

/**
 * Foreground service that OWNS the ride's location + sensor pipelines.
 *
 * Architecture rationale: the activity can be destroyed under memory
 * pressure or OEM-aggressive battery savers (Samsung / Xiaomi /
 * Huawei) even while a foreground service is alive. If the location
 * `LocationCallback` lives inside the activity, that callback dies
 * with it — and the rider sees a frozen puck + silent voice cues
 * mid-trip. Moving these subscriptions into the service decouples them
 * from the activity lifecycle entirely.
 *
 * Publish path: the service emits every GPS fix + IMU frame to
 * [NavRuntimeBus] (a singleton on the Application classloader). The
 * RideViewModel — whichever instance is currently alive — collects
 * from the bus and feeds the nav engine. If the activity dies, the
 * service keeps publishing; if the activity comes back, the new VM
 * picks up the latest values via the bus's `replay = 1` cache.
 *
 * **Actions wired**:
 *   * Tap notification → re-opens [RideActivity].
 *   * "Mute" button   → broadcast [BROADCAST_TOGGLE_MUTE] for the VM
 *     to flip `voiceEnabled`. Same action button text + role on
 *     lock screen.
 *   * "End ride" button → broadcast [BROADCAST_END_RIDE], stop self.
 *
 * Lifecycle:
 *   • [start] — called when a ride begins. Service requests location
 *     + sensors, posts the foreground notification.
 *   • [update] — called when ETA / maneuver / mute state changes.
 *     Re-posts the notification, doesn't touch location / sensors.
 *   • [stop]   — called when the ride ends. Service releases
 *     location + sensors before stopping foreground.
 */
class NavigationService : Service() {

    private var fusedClient: com.google.android.gms.location.FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var sensorRelay: SensorRelay? = null

    /**
     * Per-ride Kalman smoother. Sits between FusedLocation's raw fixes
     * and the bus — every consumer of [NavRuntimeBus.locations] reads
     * smoothed values. Reset on service stop so a new ride doesn't
     * inherit velocity from the last one.
     */
    private val smoother = ScoovaLocationSmoother()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MUTE -> {
                sendBroadcast(
                    Intent(BROADCAST_TOGGLE_MUTE).setPackage(packageName)
                )
                return START_STICKY
            }
            ACTION_END -> {
                sendBroadcast(
                    Intent(BROADCAST_END_RIDE).setPackage(packageName)
                )
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val content = extractContent(intent)
        ScoovaNavNotificationBuilder.ensureNavigationChannel(this)
        val notification = ScoovaNavNotificationBuilder.build(
            ctx = this,
            content = content,
            openAppIntent = openAppPendingIntent(this),
            muteIntent = mutePendingIntent(this),
            endIntent = endPendingIntent(this),
            smallIconRes = android.R.drawable.ic_menu_directions,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        // First-time start: claim location + sensors. Subsequent calls
        // (intent.action == ACTION_UPDATE) just re-post the notification.
        if (locationCallback == null) {
            startLocationPipeline()
        }
        if (sensorRelay == null) {
            startSensorPipeline()
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationPipeline() {
        // Service requires location permission to be granted before it
        // requests updates. The activity is responsible for asking; if
        // the user denied, we silently skip — the rider sees an empty
        // puck which is correct behaviour.
        if (!hasLocationPermission()) return
        val client = LocationServices.getFusedLocationProviderClient(this)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMaxUpdateDelayMillis(2000L)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                val l = r.lastLocation ?: return
                publishSmoothed(
                    tsMs = System.currentTimeMillis(),
                    lat = l.latitude,
                    lon = l.longitude,
                    rawAccuracyM = if (l.hasAccuracy()) l.accuracy else 0f,
                )
            }
        }
        client.requestLocationUpdates(req, cb, Looper.getMainLooper())
        client.lastLocation.addOnSuccessListener { last ->
            last?.let {
                publishSmoothed(
                    tsMs = System.currentTimeMillis(),
                    lat = it.latitude,
                    lon = it.longitude,
                    rawAccuracyM = if (it.hasAccuracy()) it.accuracy else 0f,
                )
            }
        }
        fusedClient = client
        locationCallback = cb
    }

    /**
     * Smooth a raw GPS fix and publish the result to [NavRuntimeBus].
     * The bus contract is "smoothed values only" — raw fixes never
     * leave this service. We synthesise bearing + speed from the
     * Kalman velocity vector rather than passing through the device-
     * reported values: those are derived from inter-fix deltas inside
     * FusedLocationProvider, which suffers the same jitter we're
     * trying to filter out.
     */
    private fun publishSmoothed(tsMs: Long, lat: Double, lon: Double, rawAccuracyM: Float) {
        val s = smoother.update(tsMs, lat, lon, rawAccuracyM)
        NavRuntimeBus.publishLocation(
            NavRuntimeBus.LocationUpdate(
                tsMs = s.tsMs,
                lat = s.lat,
                lon = s.lon,
                speedMps = s.speedMps,
                bearingDeg = s.bearingDeg,
                accuracyM = s.accuracyM,
            )
        )
    }

    private fun startSensorPipeline() {
        sensorRelay = SensorRelay(
            context = this,
            onFrame = { frame -> NavRuntimeBus.publishMotion(frame) },
            onCompassAccuracy = { accuracy -> NavRuntimeBus.publishCompassAccuracy(accuracy) },
        ).also { it.start() }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    override fun onDestroy() {
        fusedClient?.let { client ->
            locationCallback?.let { cb -> client.removeLocationUpdates(cb) }
        }
        locationCallback = null
        fusedClient = null
        sensorRelay?.stop()
        sensorRelay = null
        // Drop Kalman state so the next ride doesn't inherit velocity
        // / covariance from this one.
        smoother.reset()
        super.onDestroy()
    }

    private fun extractContent(intent: Intent?): ScoovaNavNotificationContent {
        return ScoovaNavNotificationContent(
            title = intent?.getStringExtra(EXTRA_TITLE) ?: "Ride in progress",
            maneuverText = intent?.getStringExtra(EXTRA_MANEUVER),
            destinationLabel = intent?.getStringExtra(EXTRA_DEST) ?: "your destination",
            isMuted = intent?.getBooleanExtra(EXTRA_MUTED, false) == true,
        )
    }

    companion object {
        private const val NOTIF_ID = 0x5C0A  // "SCOA"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_MANEUVER = "maneuver"
        private const val EXTRA_DEST = "destination"
        private const val EXTRA_MUTED = "muted"

        private const val ACTION_UPDATE = "com.scoova.ride.action.UPDATE"
        private const val ACTION_MUTE = "com.scoova.ride.action.MUTE"
        private const val ACTION_END = "com.scoova.ride.action.END"

        /** Local broadcasts forwarded from notification action taps. */
        const val BROADCAST_TOGGLE_MUTE: String = "com.scoova.ride.NOTIF_TOGGLE_MUTE"
        const val BROADCAST_END_RIDE: String = "com.scoova.ride.NOTIF_END_RIDE"

        fun start(ctx: Context, content: ScoovaNavNotificationContent) {
            val intent = buildContentIntent(ctx, content)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun update(ctx: Context, content: ScoovaNavNotificationContent) {
            val intent = buildContentIntent(ctx, content).setAction(ACTION_UPDATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, NavigationService::class.java))
        }

        private fun buildContentIntent(
            ctx: Context,
            content: ScoovaNavNotificationContent,
        ): Intent = Intent(ctx, NavigationService::class.java)
            .putExtra(EXTRA_TITLE, content.title)
            .putExtra(EXTRA_MANEUVER, content.maneuverText)
            .putExtra(EXTRA_DEST, content.destinationLabel)
            .putExtra(EXTRA_MUTED, content.isMuted)

        private fun openAppPendingIntent(ctx: Context): PendingIntent {
            val intent = Intent(ctx, RideActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
            return PendingIntent.getActivity(
                ctx, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun mutePendingIntent(ctx: Context): PendingIntent {
            val intent = Intent(ctx, NavigationService::class.java).setAction(ACTION_MUTE)
            return PendingIntent.getService(
                ctx, 1, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun endPendingIntent(ctx: Context): PendingIntent {
            val intent = Intent(ctx, NavigationService::class.java).setAction(ACTION_END)
            return PendingIntent.getService(
                ctx, 2, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
