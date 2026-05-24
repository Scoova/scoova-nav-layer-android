package com.scoova.ride

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scoova.navlayer.core.BatteryAwareNav
import com.scoova.navlayer.core.NavRuntimeBus
import com.scoova.navlayer.core.OffRouteMonitor
import com.scoova.navlayer.core.ScoovaNavLayer
import com.scoova.navlayer.core.SimEvent
import com.scoova.navlayer.core.SimulationReport
import com.scoova.navlayer.scoova.LatLon
import com.scoova.navlayer.scoova.ScoovaRoutingAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The view-model for Scoova Ride. Holds the Persona/Plan/Ride/Summary state
 * machine and owns the Scoova Nav Layer instance.
 */
class RideViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * Top-level phase — a single linear flow, 1:1 with iOS ScoovaRide:
     * Onboarding → Persona → Plan → Ride → Summary. No tab bar.
     */
    enum class Phase { Onboarding, Persona, Plan, Ride, Summary }

    data class RideState(
        val phase: Phase = Phase.Plan,
        val profile: Profile? = null,
        val settings: RideSettings = RideSettings(),
        val origin: LatLon? = null,
        val destination: LatLon? = null,
        val destinationLabel: String? = null,
        val routeShape: List<DoubleArray> = emptyList(),
        val routeDistanceKm: Double = 0.0,
        val routeDurationMin: Int = 0,
        /**
         * GPS breadcrumb trail — every location sample we received
         * during the ride, as `[lat, lon]` pairs. Drives the polyline
         * on the Summary screen so the rider can see the actual path
         * they took (which may diverge from the planned [routeShape]
         * if they took a shortcut or got rerouted). Reset between rides.
         */
        val actualPath: List<DoubleArray> = emptyList(),
        val rideStartedAtMs: Long = 0L,
        val rideEndedAtMs: Long = 0L,
        val coveredKm: Double = 0.0,
        val currentSpeedKph: Float = 0f,
        val history: List<RideRecord> = emptyList(),
        val isLoadingRoute: Boolean = false,
        val error: String? = null,

        // Search bar state
        val searchQuery: String = "",
        val searchResults: List<SearchSuggestion> = emptyList(),
        val isSearching: Boolean = false,

        // Weather chip state
        val weather: WeatherSnapshot? = null,

        /**
         * Milestone (lifetime distance threshold) the rider just
         * crossed on the last completed ride. Drives a celebratory
         * toast on the Summary screen. Cleared after the rider
         * dismisses or after [resetToPlan].
         */
        val milestoneJustCrossed: Int? = null,

        /**
         * True while the current ride is being driven by the
         * simulator (not real GPS). RideScreen reads this to decide
         * whether to enable heading-up follow camera — on the
         * Android emulator, sustained camera follow during a sim
         * segfaults libGLESv2_enc. A static bounded-view camera
         * during simulation avoids the crash and arguably reads
         * better anyway (the rider sees their position progressing
         * along the whole route rather than a centred close-up).
         */
        val isSimulating: Boolean = false,

        /**
         * True while a tour is running — the prepared route launched
         * from onboarding or Settings → About. The Ride screen reads
         * this and overlays [TourOverlay] with timed callouts that
         * explain each feature (voice + spatial audio, sensor fusion,
         * off-route detection, eyes-off mode, arrival summary) as the
         * synthetic ride progresses. Cleared at end-of-ride.
         */
        val isTour: Boolean = false,

        /**
         * Typed event log captured during the most recent simulated
         * ride. Banner updates + voice cues with ride-relative
         * timestamps. Cleared when [simulateRide] starts; turned into
         * [simulationReport] at end-of-ride.
         */
        val simLog: List<SimEvent> = emptyList(),
        /**
         * Diagnostic report generated from [simLog] at end-of-sim.
         * Surfaces a per-maneuver breakdown of which cue phases
         * fired and at what distance — used as a regression-test
         * fixture for the nav engine.
         */
        val simulationReport: SimulationReport? = null,

        // Per-trip routing preferences. Held on the state so the Plan
        // chips can flip them and the next routing request picks them
        // up. Reset to defaults between rides (see [resetToPlan]).
        val avoidHighways: Boolean = false,
        val avoidTolls: Boolean = false,
        val avoidFerries: Boolean = false,

        // ── Auth flow state (Phase.Auth) ────────────────────────────────
        /** True while a register/login/forgot call is in flight. The
         *  AuthScreen shows a spinner + disables form fields. */
        val authBusy: Boolean = false,
        /** Server-or-network error to surface inline on the AuthScreen.
         *  Cleared on next form input. */
        val authError: String? = null,
        /** Success message to surface inline on the AuthScreen (e.g.
         *  "If an account with that email exists, we sent a reset link.") */
        val authInfo: String? = null,
    )

    // Encrypted at rest — see [SecureStorage]. Migrates from the legacy
    // plaintext `scoova_ride.xml` on first launch.
    private val prefs = SettingsStore.prefs(app)
    private val historyStore = RideHistoryStore(app)
    private val activeRideStore = ActiveRideStore(app)

    private val initialProfile: Profile? = Profile.fromId(prefs.getString(KEY_PROFILE, null))
    private val initialSettings: RideSettings = SettingsStore.load(prefs)
    private val initialHistory: List<RideRecord> = historyStore.load()

    private val _state = MutableStateFlow(
        RideState(
            // First-launch flow:
            //   onboardingDone=false → Onboarding (value props)
            //   onboardingDone=true → Plan (map). No persona gate — we
            //     don't ask "are you a driver / walker / cyclist?" up front
            //     because that reads like a paywall before the user has
            //     even seen the map. Walker is the safest universal
            //     default; the rider can switch from Plan-screen chips
            //     or Settings → Profile any time. [Phase.Persona] is
            //     still reachable from Settings → Change profile for
            //     riders who want the dedicated picker.
            phase = if (initialSettings.onboardingDone) Phase.Plan else Phase.Onboarding,
            profile = initialProfile ?: Profile.Foot,
            settings = initialSettings,
            history = initialHistory,
        )
    )
    val state: StateFlow<RideState> = _state.asStateFlow()

    // Nullable backing fields — built lazily after persona pick.
    // `lateinit var` would be cleaner but only works on var, not val, so a
    // nullable + getter combo gives us the same one-time-init behaviour while
    // letting onCleared() safely skip cleanup when the user never picked.
    private var _nav: ScoovaNavLayer? = null
    val nav: ScoovaNavLayer get() {
        _nav?.let { return it }
        val s = _state.value
        val p = s.profile ?: Profile.Bicycle
        val instance = ScoovaNavLayer.builder(getApplication())
            .apiKey(ScoovaApi.KEY)
            .locale(s.settings.locale)
            .profile(p.routingProfile)
            .landmarks(true)
            .spatialAudio(s.settings.spatialAudio)
            .build()
            .also { it.start() }
        _nav = instance
        // The nav layer latches `arrived` the moment the rider reaches
        // the destination (rolled inside the arrival radius, or parked
        // near it). That's our cue to close the ride out — write the
        // history entry and hand over to the Summary screen. Guarded on
        // Phase.Ride so a stale `true` (or the simulator's own endRide)
        // can't double-fire the transition.
        viewModelScope.launch {
            instance.arrived.collect { hasArrived ->
                if (hasArrived && _state.value.phase == Phase.Ride) endRide()
            }
        }
        return instance
    }

    private var _routing: ScoovaRoutingAdapter? = null
    private val routing: ScoovaRoutingAdapter get() {
        _routing?.let { return it }
        val instance = ScoovaRoutingAdapter(ScoovaApi.KEY, nav)
        _routing = instance
        return instance
    }

    /** Per-trip routing toggles. Setting any of these re-rates the
     *  route if one is currently displayed (or in-progress).
     *  Otherwise just stores the flag for the next planRouteTo. */
    fun setAvoidHighways(v: Boolean) = applyAvoid { copy(avoidHighways = v) }
    fun setAvoidTolls(v: Boolean) = applyAvoid { copy(avoidTolls = v) }
    fun setAvoidFerries(v: Boolean) = applyAvoid { copy(avoidFerries = v) }

    private inline fun applyAvoid(transform: RideState.() -> RideState) {
        val before = _state.value
        val after = before.transform()
        if (before == after) return
        _state.value = after
        // Re-rate the route if there's an active plan / ride. Reuses
        // the persona-change rerate path: Plan-phase calls planRouteTo
        // (with isLoadingRoute UI feedback), Ride-phase calls the
        // silent reroute.
        val dest = after.destination ?: return
        val origin = after.origin ?: return
        when (after.phase) {
            Phase.Plan -> if (after.routeShape.isNotEmpty()) {
                planRouteTo(dest.lat, dest.lon, after.destinationLabel)
            }
            Phase.Ride -> {
                lastRerouteAtMs = System.currentTimeMillis()
                reroute(origin, dest)
            }
            else -> Unit
        }
    }

    /** Force a rebuild on next access — used after locale or spatial-audio change. */
    private fun resetNav() {
        _nav?.stop()
        _nav = null
        _routing = null
    }

    private var simulationJob: Job? = null
    private var simEventCaptureJob: Job? = null
    private var searchJob: Job? = null
    private var weatherJob: Job? = null


    private val searchClient = SearchClient(apiKey = ScoovaApi.KEY)
    private val weatherClient = WeatherChipClient(apiKey = ScoovaApi.KEY)

    // Consumer-rider backend (my.scoo-va.info). Holds the rider's JWT
    // session in encrypted prefs; survives process restart.
    private val cloudClient = com.scoova.ride.cloud.CloudClient(app)
    /** Reactive signed-in state — null when signed out. UI collects
     *  this directly to flip Settings → Account between "Sign in" and
     *  "<email> / Sign out" without us mirroring the value onto
     *  [RideState]. */
    val signedIn: StateFlow<com.scoova.ride.cloud.AuthCredentials?> = cloudClient.signedIn

    // Off-route monitor lives in :core. We feed it the route polyline
    // on each successful plan + the rider's GPS samples; it exposes
    // a hysteretic StateFlow that flips true after the rider has drifted
    // off-route consistently. The auto-reroute coroutine reacts to that.
    private val offRoute = OffRouteMonitor()
    val isOffRoute: StateFlow<Boolean> get() = offRoute.isOffRoute

    // Battery awareness lives in :core. Surfaces percent + charging
    // flag + "suggest low-power mode" recommendation. The activity
    // observes it to decide whether to keep the screen on.
    private val battery = BatteryAwareNav(app).also { it.start() }
    val batteryState: StateFlow<BatteryAwareNav.State> get() = battery.state

    /** True while a reroute request is in flight. Drives the UI banner. */
    private val _isRerouting = MutableStateFlow(false)
    val isRerouting: StateFlow<Boolean> = _isRerouting.asStateFlow()

    /**
     * Latest rotation-vector accuracy from the OS — `0`=unreliable …
     * `3`=high. The Ride screen surfaces the figure-8 calibration
     * banner when this drops below `2` (LOW or UNRELIABLE).
     *
     * **Declared up here, not down with the rest of the sensor
     * plumbing**, because the [init] block below starts a flow
     * collector on `NavRuntimeBus.compassAccuracy`. On a warm
     * restart of the activity, that SharedFlow may already have a
     * buffered value, which it emits synchronously into the
     * collector during init — so the backing field must be
     * initialised before the init block runs. (Kotlin initialises
     * properties top-to-bottom; a field declared below init is
     * still null when init executes.) Declaring it here closes that
     * NPE race. See [onCompassAccuracy] for the setter.
     */
    private val _compassAccuracy = MutableStateFlow(3)
    val compassAccuracy: StateFlow<Int> = _compassAccuracy.asStateFlow()

    /**
     * Wall-clock of the last reroute we issued. Used to debounce — we
     * don't want to spam the routing server if the rider yo-yos in and
     * out of the corridor in dense GPS-shadow areas.
     */
    private var lastRerouteAtMs: Long = 0L
    private val rerouteMinIntervalMs: Long = 8_000L

    /**
     * Wall-clock of the last offline "head back to the route" cue.
     * The off-route watchdog retriggers [reroute] every
     * [rerouteMinIntervalMs]; when offline we still want to remind
     * the rider, but on a calmer cadence than that — not every 8 s.
     */
    private var lastOfflineAnnounceAtMs: Long = 0L
    private val offlineAnnounceMinIntervalMs: Long = 25_000L

    /**
     * Wall-clock of the last [ActiveRideStore] write. Persistence is
     * throttled to once every [activeRideSaveMinIntervalMs] so we
     * don't burn disk on every 1 Hz GPS tick — the FG service publishes
     * fast enough that unthrottled writes would do ~3,600 fsyncs per
     * hour of riding for no benefit. The throttle is bypassed at phase
     * transitions ([startRide], [endRide]) so the snapshot reflects
     * boundaries exactly.
     */
    private var lastActiveSaveAtMs: Long = 0L
    private val activeRideSaveMinIntervalMs: Long = 5_000L

    /**
     * Latch for the "Try the SDK · 60s demo" hero CTA on the Plan tab.
     * When the buyer taps the CTA we route through [simulateRide],
     * which plans a synthetic Cairo route and flips this flag; the
     * [planRouteTo] success path reads it and self-recurses into
     * [simulateRide] so the demo starts on the same tap. Reset by
     * the same success path (and by route-fetch failures) so a
     * subsequent legitimate Simulate doesn't accidentally chain.
     */
    private var pendingDemoSimulate: Boolean = false

    /** Receiver for the notification-action broadcasts ([NavigationService]). */
    private val notifReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                NavigationService.BROADCAST_TOGGLE_MUTE -> {
                    setVoiceEnabled(!_state.value.settings.voiceEnabled)
                }
                NavigationService.BROADCAST_END_RIDE -> {
                    endRide()
                }
            }
        }
    }

    init {
        // Note: we deliberately do NOT seed an origin from a hard-coded
        // city when GPS hasn't arrived yet. The previous "fallback to
        // Cairo" was a debugging hack that overrode whatever location
        // the user / emulator panel was actually set to. If GPS is
        // slow, the route-plan wizard shows "Waiting for GPS fix…" and
        // the user can tap on the map to plan without a fix. Weather
        // chip stays as its placeholder until [setOrigin] gets a real
        // value — that's also intentional, weather should reflect the
        // user's actual location.

        // Register receiver for notification-action taps. Mute / end
        // ride from the pulldown route through here. Package-scoped
        // intent + `RECEIVER_NOT_EXPORTED` so other apps can't poke us.
        val filter = android.content.IntentFilter().apply {
            addAction(NavigationService.BROADCAST_TOGGLE_MUTE)
            addAction(NavigationService.BROADCAST_END_RIDE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (app as android.content.Context).registerReceiver(
                notifReceiver, filter,
                android.content.Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            (app as android.content.Context).registerReceiver(notifReceiver, filter)
        }

        // Auto-reroute watchdog. The OffRouteMonitor only reports —
        // this coroutine reacts. We trigger a fresh route plan when:
        //   • we're in the Ride phase (no point rerouting on Plan), AND
        //   • we have a destination set, AND
        //   • we haven't issued a reroute in the last few seconds
        //     (debounce — see [rerouteMinIntervalMs]).
        viewModelScope.launch {
            offRoute.isOffRoute.collect { off ->
                if (!off) return@collect
                val s = _state.value
                if (s.phase != Phase.Ride) return@collect
                val dest = s.destination ?: return@collect
                val origin = s.origin ?: return@collect
                val now = System.currentTimeMillis()
                if (now - lastRerouteAtMs < rerouteMinIntervalMs) return@collect
                lastRerouteAtMs = now
                reroute(origin, dest)
            }
        }

        // Push notification updates when the next-maneuver flips
        // (banner verb, anchor, distance milestone). The nav layer
        // already throttles its currentInstruction emissions to ~1 Hz,
        // so this won't churn unnecessarily.
        viewModelScope.launch {
            // Touch lazy `nav` once so it builds + starts streaming
            // cues. Without this, currentInstruction stays null until
            // a ride begins and we'd miss the first 'getReady' cue
            // for the notification preview.
            // (Cheap idempotent no-op if already built.)
            // No-op: nav is also accessed on startRide / planRouteTo,
            // but defensive in case the rider starts mid-flow.
            kotlinx.coroutines.delay(0)
            _nav?.currentInstruction?.collect { refreshNotification() }
        }

        // Subscribe to the application-scoped location + sensor bus.
        // [NavigationService] owns the FusedLocation client + SensorRelay
        // and publishes here; the VM is the only consumer (UI never sees
        // raw fixes). Routing the streams through this single collection
        // point means location keeps reaching the nav engine even when
        // the activity is killed by the OS mid-ride — the next activity
        // instance picks up where the last one left off via the bus's
        // replay-cache.
        viewModelScope.launch {
            NavRuntimeBus.locations.collect { fix ->
                // The simulator drives onLocation() directly with
                // interpolated coordinates. Without this gate, real
                // GPS fixes from the (still-running) FusedLocation
                // client would yank the puck back and forth between
                // sim position and real position. Plan-phase + cold
                // boot still consume real fixes (setOrigin) so the
                // map opens at the rider's actual location.
                //
                // [isTour] is the second gate: during a tour the only
                // legitimate source of "where the puck is" is the
                // simulator walking the prepared route. Real GPS in
                // that window would either pull the puck off the
                // route or — if the user is far from Cairo — make the
                // tour disorienting. Drop everything.
                if (_state.value.isSimulating || _state.value.isTour) return@collect
                when (_state.value.phase) {
                    Phase.Onboarding,
                    Phase.Persona,
                    Phase.Plan -> setOrigin(fix.lat, fix.lon)
                    Phase.Ride -> onLocation(
                        lat = fix.lat,
                        lon = fix.lon,
                        speedMps = fix.speedMps,
                        bearingDeg = fix.bearingDeg,
                        accuracyM = fix.accuracyM,
                    )
                    Phase.Summary -> Unit
                }
            }
        }
        viewModelScope.launch {
            NavRuntimeBus.motion.collect { onMotion(it.frame) }
        }
        viewModelScope.launch {
            NavRuntimeBus.compassAccuracy.collect { onCompassAccuracy(it) }
        }

        // Cold-start mid-ride restoration. If the process was reaped
        // by the OS (memory pressure, swipe-from-recents, watchdog
        // kill) during an active ride, [ActiveRideStore] holds the
        // last known snapshot. We hydrate the in-memory state now,
        // BEFORE returning from init, so the first composition shows
        // the Ride screen with the saved route + breadcrumb trail
        // already in place — no "blank Plan flash" while we re-fetch
        // the route from the server.
        restoreActiveRideIfAny()
    }

    /**
     * Read [activeRideStore] and, if a snapshot exists, hydrate the
     * VM into the Ride phase. The routing adapter's per-maneuver
     * pipeline is not in the snapshot (server-derived, not durable);
     * we fire a fresh [reroute] to repopulate it, using the cached
     * origin as the starting point so the rider's progress feels
     * continuous. The cached [RideState.routeShape] keeps the map
     * showing the original route while the re-fetch is in flight,
     * which avoids a 1–3 second blank window on slow networks.
     *
     * Best-effort: any failure inside the routing call is swallowed
     * and the rider stays in the Ride phase with the cached shape.
     * They can hit End to bail back to Plan if the network is dead.
     */
    private fun restoreActiveRideIfAny() {
        val snap = activeRideStore.load() ?: return
        val profile = Profile.fromId(snap.profileId) ?: return
        // Freshness guard. If the snapshot is older than this window
        // we treat it as abandoned and land the user on Plan instead
        // of dropping them into a stale Ride from yesterday. A buyer
        // opening the demo app for the second time should see the
        // beautiful Plan/Map hero, not a half-finished ride they don't
        // remember starting. The FG service caps active rides well
        // under 30 min of process-death, so a fresh kill still resumes
        // cleanly; only truly abandoned snapshots get discarded.
        val ageMs = System.currentTimeMillis() - snap.rideStartedAtMs
        val staleAfterMs = 30L * 60L * 1000L
        if (snap.rideStartedAtMs <= 0L || ageMs > staleAfterMs) {
            activeRideStore.clear()
            return
        }
        // Hydrate state directly into Ride. Note: the cached origin is
        // the rider's last GPS fix BEFORE the crash; the FG service
        // (when it starts after permission re-grant) will overwrite
        // it via the bus on the next location tick.
        _state.value = _state.value.copy(
            phase = Phase.Ride,
            profile = profile,
            origin = snap.origin,
            destination = snap.destination,
            destinationLabel = snap.destinationLabel,
            routeShape = snap.routeShape,
            routeDistanceKm = snap.routeDistanceKm,
            routeDurationMin = snap.routeDurationMin,
            rideStartedAtMs = snap.rideStartedAtMs,
            coveredKm = snap.coveredKm,
            actualPath = snap.actualPath,
            avoidHighways = snap.avoidHighways,
            avoidTolls = snap.avoidTolls,
            avoidFerries = snap.avoidFerries,
        )
        offRoute.setRoute(snap.routeShape)
        // Kick the FG service so location resumes flowing. The
        // service hydrates the notification from buildNotificationContent
        // which now reflects the restored state.
        NavigationService.start(getApplication(), buildNotificationContent())
        // Repopulate the routing adapter's maneuver pipeline. Without
        // this, the banner stays blank and no voice cues fire — the
        // adapter holds its maneuvers in memory, not on disk.
        val origin = snap.origin ?: return
        lastRerouteAtMs = System.currentTimeMillis()
        reroute(origin, snap.destination)
    }

    /**
     * Persist the current state as the active-ride snapshot. Throttled
     * via [lastActiveSaveAtMs]; pass `force = true` at phase transitions
     * (startRide, endRide) so the file always reflects a stable
     * boundary even if the throttle window hasn't elapsed.
     */
    private fun saveActiveRide(force: Boolean = false) {
        val s = _state.value
        if (s.phase != Phase.Ride) return
        // Don't persist simulations — restoring a fake ride on cold
        // start would be confusing UX. Real rides only.
        if (s.isSimulating) return
        val dest = s.destination ?: return
        val profile = s.profile ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastActiveSaveAtMs < activeRideSaveMinIntervalMs) return
        lastActiveSaveAtMs = now
        activeRideStore.save(
            ActiveRideStore.Snapshot(
                profileId = profile.id,
                origin = s.origin,
                destination = dest,
                destinationLabel = s.destinationLabel,
                routeShape = s.routeShape,
                routeDistanceKm = s.routeDistanceKm,
                routeDurationMin = s.routeDurationMin,
                rideStartedAtMs = s.rideStartedAtMs,
                coveredKm = s.coveredKm,
                actualPath = s.actualPath,
                avoidHighways = s.avoidHighways,
                avoidTolls = s.avoidTolls,
                avoidFerries = s.avoidFerries,
            )
        )
    }

    /**
     * Pending-cancel handle for the Plan-phase location flow (see
     * [startPlanLocationFlow]). The flow is started on permission-grant
     * and cancelled when a ride starts (the foreground service takes
     * over location ownership at that point). Cancelled-and-resumed
     * across ride starts.
     */
    private var planLocationJob: Job? = null

    /**
     * Activity reports that the user just granted location permission
     * (or that it was already granted on cold start). We do NOT start
     * the foreground service yet — that would post a "ride in progress"
     * sticky notification while the user is still picking a destination.
     * Instead, kick a VM-scoped location flow that drives the Plan-time
     * puck. The FG service starts at [startRide] and takes over
     * location + sensor ownership via [NavRuntimeBus].
     *
     * Idempotent — re-entrant calls are silently ignored.
     */
    fun onLocationPermissionGranted() {
        if (planLocationJob?.isActive == true) return
        planLocationJob = viewModelScope.launch {
            planLocationFlow().collect { (lat, lon) ->
                // If a ride started while we were in flight, the FG
                // service is now the source of truth — drop this fix
                // rather than racing against bus updates. Same for
                // tours — [startTour] seeds a synthetic Cairo origin
                // and the simulator owns every subsequent puck move.
                if (_state.value.phase == Phase.Ride) return@collect
                if (_state.value.isTour) return@collect
                setOrigin(lat, lon)
            }
        }
    }

    /**
     * Cold flow over FusedLocationProviderClient, scoped to the VM.
     * Used during Plan / Persona / Onboarding only — once a ride
     * starts, [NavigationService] takes over and publishes to
     * [NavRuntimeBus]. The update cadence here is slower than the
     * in-ride one (1.5 s vs 1 s) — the Plan map doesn't need
     * tight-loop fixes, it just needs the user to see their position.
     *
     * Emits null-free `(lat, lon)` pairs.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun planLocationFlow(): kotlinx.coroutines.flow.Flow<Pair<Double, Double>> =
        kotlinx.coroutines.flow.callbackFlow {
            val ctx = getApplication<Application>()
            val hasPerm = listOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ).any {
                androidx.core.content.ContextCompat
                    .checkSelfPermission(ctx, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (!hasPerm) {
                close()
                return@callbackFlow
            }
            val client = com.google.android.gms.location.LocationServices
                .getFusedLocationProviderClient(ctx)
            val req = com.google.android.gms.location.LocationRequest.Builder(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                1500L,
            ).setMinUpdateIntervalMillis(800L).build()
            val cb = object : com.google.android.gms.location.LocationCallback() {
                override fun onLocationResult(r: com.google.android.gms.location.LocationResult) {
                    r.lastLocation?.let { l -> trySend(l.latitude to l.longitude) }
                }
            }
            try {
                client.requestLocationUpdates(req, cb, android.os.Looper.getMainLooper())
                client.lastLocation.addOnSuccessListener { l ->
                    l?.let { trySend(it.latitude to it.longitude) }
                }
            } catch (_: SecurityException) {
                close()
                return@callbackFlow
            }
            awaitClose { client.removeLocationUpdates(cb) }
        }

    /**
     * True when the device has a validated, internet-capable network.
     * Used to skip a doomed server reroute when the signal has
     * dropped mid-ride. When connectivity state can't be read we
     * assume online and let the network call decide — a false
     * "offline" would wrongly suppress a perfectly good reroute.
     */
    private fun isOnline(): Boolean {
        val cm = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Issue a fresh route plan from the rider's current GPS to the
     * existing destination. Distinct from [planRouteTo] so the UI can
     * surface a "Rerouting…" state without confusing it with a
     * first-time route plan (which has its own [RideState.isLoadingRoute]).
     */
    private fun reroute(origin: LatLon, dest: LatLon) {
        val profile = _state.value.profile ?: Profile.Bicycle
        // Offline? A server reroute is doomed — it would just burn the
        // socket timeout and then speak a confusing "still trying".
        // Skip it entirely: the planned route line stays drawn on the
        // map (its corridor tiles were pre-fetched while we were
        // online, so it still renders), and we periodically remind the
        // rider to head back onto it. Throttled so the off-route
        // watchdog's 8 s cadence doesn't turn into a nag.
        if (!isOnline()) {
            val now = System.currentTimeMillis()
            if (now - lastOfflineAnnounceAtMs >= offlineAnnounceMinIntervalMs) {
                lastOfflineAnnounceAtMs = now
                _nav?.announceOffline()
            }
            return
        }
        _isRerouting.value = true
        // Tell the rider audibly that we're rerouting — eyes-off they
        // can't see the spinner. The phrase comes from the server's
        // pre-rendered trip-level sentence; silent if not present
        // (we don't synthesise — silence > Frankenstein copy).
        _nav?.announceRerouteSearching()
        viewModelScope.launch {
            try {
                val shape = routing.startRoute(
                    from = origin,
                    to = dest,
                    profile = profile.routingProfile,
                    language = _state.value.settings.locale,
                    landmarks = true,
                    avoidHighways = _state.value.avoidHighways,
                    avoidTolls = _state.value.avoidTolls,
                    avoidFerries = _state.value.avoidFerries,
                    eyesOff = _state.value.settings.eyesOff,
                )
                val distKm = computeDistanceKm(shape)
                val durMin = (distKm / profile.averageKmh * 60).toInt()
                _state.value = _state.value.copy(
                    routeShape = shape,
                    routeDistanceKm = distKm,
                    routeDurationMin = durMin,
                )
                offRoute.setRoute(shape)
                // Refresh the offline tile corridor for the new route
                // so a later signal drop still has map coverage.
                OfflineTilePrefetch.prefetchCorridor(
                    getApplication(),
                    MapStyleChoice.Dark.styleUrl,
                    shape,
                )
                // Confirm the rider is back on a route. Eyes-off this
                // is critical — silence after the "searching" cue
                // would leave them wondering whether nav recovered.
                _nav?.announceRerouteFound()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Network or server failure. Speak the "still trying"
                // sentence so silence doesn't read as a crash. The
                // OffRouteMonitor will retrigger after the debounce
                // window if the rider is still off route.
                _nav?.announceRerouteFailed()
            } finally {
                _isRerouting.value = false
            }
        }
    }

    /** Mark the onboarding tour complete and advance to the next phase
     *  straight to Plan with a Walker default. Persona pick is offered
     *  from Settings → Change profile or from the Plan-screen chips, not
     *  as a forced gate after onboarding. Persisted so we never replay
     *  the onboarding tour automatically. */
    fun finishOnboarding() {
        val newSettings = _state.value.settings.copy(onboardingDone = true)
        _state.value = _state.value.copy(
            settings = newSettings,
            phase = Phase.Plan,
            profile = _state.value.profile ?: Profile.Foot,
        )
        SettingsStore.save(prefs, newSettings)
    }

    /** Re-launch the onboarding tour from Settings → "How does Scoova
     *  work?". Doesn't reset the done-flag — closing the tour returns
     *  to Plan whether it's been seen or not. */
    fun showOnboardingAgain() {
        _state.value = _state.value.copy(phase = Phase.Onboarding)
    }

    /** Called from the first-run picker (or Settings → Change profile). */
    fun selectProfile(profile: Profile) {
        prefs.edit().putString(KEY_PROFILE, profile.id).apply()
        _state.value = _state.value.copy(
            phase = Phase.Plan,
            profile = profile,
        )
    }

    /** Settings hook — drops the user back into the persona picker. */
    fun changeProfile() {
        _state.value = _state.value.copy(phase = Phase.Persona)
    }

    /**
     * One-tap mode swap from the Map tab's persona row.
     * Stays on the current tab; rebuilds the nav layer so the
     * thresholds / routing-profile / voice tone all flip together, and
     * if there's a destination set, re-rates the route under the new
     * profile (cyclist vs. driver vs. walker routes can diverge a lot
     * even between the same two points — one-way restrictions, bike
     * lanes, motorway access).
     */
    fun switchProfile(profile: Profile) {
        if (_state.value.profile == profile) return
        prefs.edit().putString(KEY_PROFILE, profile.id).apply()
        _state.value = _state.value.copy(profile = profile)
        resetNav()  // next nav access rebuilds with new profile

        // Re-rate the route if the rider is mid-plan or mid-ride and
        // has a destination locked in. Different personas often pick
        // genuinely different roads (cycle lanes vs. highways), so
        // honouring the swap requires a fresh route.
        val s = _state.value
        val dest = s.destination ?: return
        val origin = s.origin ?: return
        if (s.phase == Phase.Plan && s.routeShape.isNotEmpty()) {
            // Re-plan from the current origin to the same destination.
            planRouteTo(dest.lat, dest.lon, s.destinationLabel)
        } else if (s.phase == Phase.Ride) {
            // Mid-trip: silent reroute, same code path as off-route.
            lastRerouteAtMs = System.currentTimeMillis()
            reroute(origin, dest)
        }
    }

    fun setUnitsMetric(metric: Boolean) {
        val newSettings = _state.value.settings.copy(unitsMetric = metric)
        _state.value = _state.value.copy(settings = newSettings)
        SettingsStore.save(prefs, newSettings)
    }

    fun setLocale(localeTag: String) {
        val newSettings = _state.value.settings.copy(locale = localeTag)
        _state.value = _state.value.copy(settings = newSettings)
        SettingsStore.save(prefs, newSettings)
        // Hard switch — rebuild the layer so TTS speaks in the new language.
        resetNav()
    }

    fun setVoiceEnabled(enabled: Boolean) {
        val newSettings = _state.value.settings.copy(voiceEnabled = enabled)
        _state.value = _state.value.copy(settings = newSettings)
        SettingsStore.save(prefs, newSettings)
        _nav?.setVoiceEnabled(enabled)
        refreshNotification()
    }

    /** Play the localised sample cue. Lazy-builds the nav layer if
     *  needed so the preview works before any ride starts. */
    fun previewVoice() {
        nav.previewVoice()
    }

    fun setSpatialAudio(enabled: Boolean) {
        val newSettings = _state.value.settings.copy(spatialAudio = enabled)
        _state.value = _state.value.copy(settings = newSettings)
        SettingsStore.save(prefs, newSettings)
        // Rebuild so the layer's VoiceEngine picks up the new flag.
        resetNav()
    }

    fun runSpatialAudioTest() {
        SpatialAudioTest.play()
    }

    fun setOrigin(lat: Double, lon: Double) {
        val prev = _state.value.origin
        _state.value = _state.value.copy(origin = LatLon(lat, lon))

        // First fix (or moved >500 m since last fetch): refresh weather chip.
        val shouldRefreshWeather = prev == null ||
            haversineMeters(prev.lat, prev.lon, lat, lon) > 500
        if (shouldRefreshWeather) refreshWeather(lat, lon)
    }

    private fun refreshWeather(lat: Double, lon: Double) {
        weatherJob?.cancel()
        weatherJob = viewModelScope.launch {
            val snap = weatherClient.now(lat, lon)
            if (snap != null) _state.value = _state.value.copy(weather = snap)
        }
    }

    // ─── Search ────────────────────────────────────────────────────────

    /** Called as the user types. Debounced — fires autocomplete 250 ms after the last keystroke. */
    fun setSearchQuery(q: String) {
        _state.value = _state.value.copy(searchQuery = q)
        searchJob?.cancel()
        if (q.isBlank() || q.length < 2) {
            _state.value = _state.value.copy(searchResults = emptyList(), isSearching = false)
            return
        }
        searchJob = viewModelScope.launch {
            delay(250)
            _state.value = _state.value.copy(isSearching = true)
            val origin = _state.value.origin
            val results = searchClient.autocomplete(
                text = q,
                focusLat = origin?.lat,
                focusLon = origin?.lon,
            )
            _state.value = _state.value.copy(
                searchResults = results,
                isSearching = false,
            )
        }
    }

    /** User tapped one of the suggestion rows → plan the route. */
    fun pickSearchResult(s: SearchSuggestion) {
        _state.value = _state.value.copy(
            searchQuery = "",
            searchResults = emptyList(),
        )
        planRouteTo(s.lat, s.lon, s.label)
    }

    fun clearSearch() {
        searchJob?.cancel()
        _state.value = _state.value.copy(
            searchQuery = "",
            searchResults = emptyList(),
            isSearching = false,
        )
    }

    // ─── Saved places ─────────────────────────────────────────────────

    /** Save the user's current location as Home or Work. */
    fun saveCurrentAsHome(label: String = "Home") {
        val o = _state.value.origin ?: return
        val place = SavedPlace(label, o.lat, o.lon)
        val s = _state.value.settings.copy(homePlace = place)
        _state.value = _state.value.copy(settings = s)
        SettingsStore.save(prefs, s)
    }

    fun saveCurrentAsWork(label: String = "Work") {
        val o = _state.value.origin ?: return
        val place = SavedPlace(label, o.lat, o.lon)
        val s = _state.value.settings.copy(workPlace = place)
        _state.value = _state.value.copy(settings = s)
        SettingsStore.save(prefs, s)
    }

    /** Save an arbitrary place (from the set-place wizard's search). */
    fun saveAsHome(lat: Double, lon: Double, label: String) {
        val s = _state.value.settings.copy(homePlace = SavedPlace(label, lat, lon))
        _state.value = _state.value.copy(settings = s)
        SettingsStore.save(prefs, s)
    }

    fun saveAsWork(lat: Double, lon: Double, label: String) {
        val s = _state.value.settings.copy(workPlace = SavedPlace(label, lat, lon))
        _state.value = _state.value.copy(settings = s)
        SettingsStore.save(prefs, s)
    }

    fun clearHome() {
        val s = _state.value.settings.copy(homePlace = null)
        _state.value = _state.value.copy(settings = s)
        SettingsStore.save(prefs, s)
    }

    /**
     * Toggle whether a place is in the rider's favorites list. Lookup
     * is by (lat, lon) rounded to ~10 m so two near-identical pins
     * collapse to one. Newest favorites move to the head of the list
     * so the most-recently-starred shows up first in any chip strip.
     */
    fun toggleFavorite(lat: Double, lon: Double, label: String) {
        val key = "%.4f,%.4f".format(lat, lon)
        val current = _state.value.settings.favorites
        val existing = current.firstOrNull { "%.4f,%.4f".format(it.lat, it.lon) == key }
        val updated = if (existing != null) {
            current - existing
        } else {
            (listOf(SavedPlace(label, lat, lon)) + current)
                .take(RideSettings.MAX_FAVORITES)
        }
        val s = _state.value.settings.copy(favorites = updated)
        _state.value = _state.value.copy(settings = s)
        SettingsStore.save(prefs, s)
    }

    /** Is this lat/lon already starred? Used by the POI sheet to flip
     *  the star icon's fill state in real time. */
    fun isFavorite(lat: Double, lon: Double): Boolean {
        val key = "%.4f,%.4f".format(lat, lon)
        return _state.value.settings.favorites.any {
            "%.4f,%.4f".format(it.lat, it.lon) == key
        }
    }

    fun clearWork() {
        val s = _state.value.settings.copy(workPlace = null)
        _state.value = _state.value.copy(settings = s)
        SettingsStore.save(prefs, s)
    }

    /** Tap on Home / Work chip → plan a route to that place. */
    fun planToSavedPlace(place: SavedPlace) {
        planRouteTo(place.lat, place.lon, place.label)
    }

    fun planRouteTo(lat: Double, lon: Double, label: String?) {
        val from = _state.value.origin ?: run {
            _state.value = _state.value.copy(
                error = "We need your location first. Make sure GPS is on and try again.",
            )
            return
        }
        val profile = _state.value.profile ?: Profile.Bicycle
        _state.value = _state.value.copy(
            destination = LatLon(lat, lon),
            destinationLabel = label,
            isLoadingRoute = true,
            error = null,
        )
        viewModelScope.launch {
            try {
                val shape = routing.startRoute(
                    from = from,
                    to = LatLon(lat, lon),
                    profile = profile.routingProfile,
                    // Pass the user-selected locale so the server renders
                    // the scoova.banner / scoova.voice block in the right
                    // language. Was hardcoded to detectLocale() (en-US)
                    // which meant Arabic users always got English copy.
                    language = _state.value.settings.locale,
                    landmarks = true,
                    avoidHighways = _state.value.avoidHighways,
                    avoidTolls = _state.value.avoidTolls,
                    avoidFerries = _state.value.avoidFerries,
                    eyesOff = _state.value.settings.eyesOff,
                )
                val distKm = computeDistanceKm(shape)
                val avgKmh = profile.averageKmh
                val durMin = (distKm / avgKmh * 60).toInt()
                _state.value = _state.value.copy(
                    routeShape = shape,
                    routeDistanceKm = distKm,
                    routeDurationMin = durMin,
                    isLoadingRoute = false,
                )
                offRoute.setRoute(shape)
                // Pre-fetch the route corridor's map tiles into
                // MapLibre's offline DB while we're still online, so
                // the map keeps rendering if the signal drops
                // mid-ride. Fire-and-forget — see [OfflineTilePrefetch].
                OfflineTilePrefetch.prefetchCorridor(
                    getApplication(),
                    MapStyleChoice.Dark.styleUrl,
                    shape,
                )
                if (pendingDemoSimulate) {
                    pendingDemoSimulate = false
                    simulateRide()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                pendingDemoSimulate = false
                _state.value = _state.value.copy(
                    isLoadingRoute = false,
                    error = composeRouteError(t, profile),
                )
            }
        }
    }

    /**
     * Translate a routing-pipeline throwable into a single short
     * user-facing line. Never leaks HTTP codes, JSON, stack traces, or
     * raw server copy — the rider sees a clear, actionable sentence.
     * Falls back to a generic "couldn't plan a route" when we don't
     * recognise the signal; recognised cases get specific guidance
     * (e.g. "switch to driving" for over-distance walker routes).
     */
    private fun composeRouteError(t: Throwable, profile: Profile?): String {
        val raw = (t.message ?: "").lowercase()
        val mode = profile?.localizedDisplay(_state.value.settings.locale)?.lowercase()
            ?: "this mode"
        return when {
            "max distance" in raw || "path distance" in raw ->
                "That spot is a little far for $mode. Try a closer place or switch to driving."
            "no trip" in raw || "no route" in raw || "unreachable" in raw ||
                "no path" in raw ->
                "We couldn't find a route to that spot. Try another point nearby."
            t is java.net.UnknownHostException || "unable to resolve" in raw ||
                "no address associated" in raw ->
                "You appear to be offline. Reconnect and try again."
            t is java.net.SocketTimeoutException || "timeout" in raw ->
                "The routing service is slow right now. Try again in a moment."
            "http 5" in raw || "http 50" in raw ->
                "Our routing service hit a glitch. Please try again."
            "http 4" in raw ->
                "We can't plan that route right now. Try a different destination."
            else -> "We couldn't plan a route. Try again, or pick a different spot."
        }
    }

    /** Clear the inline error banner (X tap on the Plan screen). */
    fun clearRouteError() {
        if (_state.value.error == null) return
        _state.value = _state.value.copy(error = null)
    }

    fun startRide() {
        if (_state.value.routeShape.isEmpty()) return
        _state.value = _state.value.copy(
            phase = Phase.Ride,
            rideStartedAtMs = System.currentTimeMillis(),
            coveredKm = 0.0,
            actualPath = emptyList(),  // fresh trail per ride
        )
        // Hand location off from the VM-scoped Plan flow to the
        // foreground service. From this point on, [NavRuntimeBus] is
        // the single source of truth — the service publishes, this VM
        // collects (see init block). Cancel the Plan flow FIRST so
        // there's a clean handover with no overlap window where both
        // sources would drive setOrigin().
        planLocationJob?.cancel()
        planLocationJob = null
        // Promote to foreground: keeps GPS + TTS alive when the screen
        // turns off or the user switches apps mid-ride.
        NavigationService.start(getApplication(), buildNotificationContent())
        // Persist the freshly-started ride immediately so a process
        // kill in the first few seconds doesn't lose the destination.
        saveActiveRide(force = true)
    }

    /**
     * Build the current foreground-notification payload from the
     * ride's running state. Pulled into a function so the broadcast
     * receivers + ride-progress collector can both refresh it without
     * duplicating the formatting.
     */
    private fun buildNotificationContent(): com.scoova.navlayer.core.ScoovaNavNotificationContent {
        val s = _state.value
        val profile = s.profile ?: Profile.Bicycle
        val totalKm = s.routeDistanceKm.coerceAtLeast(0.001)
        val remainingKm = (totalKm - s.coveredKm).coerceAtLeast(0.0)
        val totalMin = s.routeDurationMin
        val progress = (s.coveredKm / totalKm).coerceIn(0.0, 1.0)
        val minutesRemaining = (totalMin * (1.0 - progress)).toInt().coerceAtLeast(0)
        val title = when {
            minutesRemaining < 1 -> "Arriving · ${"%.1f".format(remainingKm)} km"
            else                 -> "$minutesRemaining min · ${"%.1f".format(remainingKm)} km"
        }
        // Next-maneuver text comes from the nav layer's current cue.
        // Falls back to the destination label so the rider has SOMETHING
        // to read in the notification even before the first cue arrives.
        val cue = _nav?.currentInstruction?.value
        val maneuver = cue?.let { c ->
            // Distance + verb. The nav layer's DisplayCue surfaces a
            // localised banner verb + landmark anchor; we glue them
            // into a single short line because the notification body
            // gets truncated on the lockscreen.
            val meters = c.metersToManeuver.toInt()
            val verb = c.maneuver.bannerVerb?.takeIf { it.isNotBlank() }
                ?: c.text.takeIf { it.isNotBlank() }
                ?: profile.localizedDisplay(s.settings.locale)
            val anchor = c.maneuver.bannerAnchor?.takeIf { it.isNotBlank() }
            val head = if (anchor != null) "$verb $anchor" else verb
            if (meters > 0) "$head · in ${meters}m" else head
        }
        return com.scoova.navlayer.core.ScoovaNavNotificationContent(
            title = title,
            maneuverText = maneuver,
            destinationLabel = s.destinationLabel ?: "your destination",
            isMuted = !s.settings.voiceEnabled,
        )
    }

    /**
     * Push the latest notification content. Called from the
     * ride-progress observer (state-flow combine) and from the
     * notification-action broadcast receivers when the rider taps
     * mute / end from the pulldown.
     */
    private fun refreshNotification() {
        if (_state.value.phase != Phase.Ride) return
        NavigationService.update(getApplication(), buildNotificationContent())
    }

    fun endRide() {
        // Idempotent: a ride can be ended from several places that may
        // race — the nav layer's `arrived` latch, the simulator's own
        // end-of-route call, and the notification "End ride" action.
        // Only the first one through does the work; the rest no-op so
        // we never re-copy state (which would, e.g., null out a
        // just-computed simulation report).
        if (_state.value.phase != Phase.Ride) return
        simulationJob?.cancel()
        simEventCaptureJob?.cancel()
        NavigationService.stop(getApplication())
        // Active-ride snapshot served its purpose — the ride is over,
        // so drop the file. If we don't, the next cold start would
        // try to "resume" a ride the user already finished.
        activeRideStore.clear()
        // Service is gone; resume the lightweight Plan-phase location
        // flow so the Summary / Plan map has live coordinates again.
        // Idempotent — no-op if already running, which it shouldn't be
        // since startRide cancelled it, but the guard is cheap.
        onLocationPermissionGranted()
        // If we were simulating, distil the captured event log into a
        // diagnostic report — per-maneuver cue timing + anomaly list.
        // Computed BEFORE we copy ended state so the report rides
        // along with the Summary screen.
        val simReport: SimulationReport? = if (_state.value.isSimulating && _state.value.simLog.isNotEmpty()) {
            val nav = _nav
            if (nav != null) {
                val totalMeters = computeDistanceKm(_state.value.routeShape) * 1000.0
                SimulationReport.from(
                    log = _state.value.simLog,
                    maneuvers = nav.maneuvers(),
                    thresholds = nav.cueThresholds,
                    routeDistanceMeters = totalMeters,
                )
            } else null
        } else null
        val wasTour = _state.value.isTour
        val ended = _state.value.copy(
            phase = Phase.Summary,
            rideEndedAtMs = System.currentTimeMillis(),
            isSimulating = false,
            isTour = false,
            simulationReport = simReport,
        )
        // Tour rides shouldn't pollute the rider's real history.
        if (wasTour) {
            _state.value = ended.copy(
                rideStartedAtMs = 0,
                rideEndedAtMs = 0,
                coveredKm = 0.0,
                actualPath = emptyList(),
            )
            return
        }
        // Recording opted out — this rider uses Scoova for navigation
        // only. No history entry, no GPS trail, no Summary takeover:
        // we drop straight back to the Plan map, exactly as
        // [resetToPlan] would. Any history from before they opted out
        // is left intact (see [RideSettings.recordRides]).
        if (!_state.value.settings.recordRides) {
            offRoute.setRoute(emptyList())
            _state.value = RideState(
                phase = Phase.Plan,
                profile = _state.value.profile,
                origin = _state.value.origin,
                settings = _state.value.settings,
                history = _state.value.history,
            )
            return
        }
        // Persist if there's actual covered distance — otherwise it's a
        // cancelled ride and shouldn't pollute history.
        val prevTotalKm = ended.history.sumOf { it.coveredKm }
        val updatedHistory = if (ended.coveredKm > 0.05 && ended.rideStartedAtMs > 0) {
            val record = buildRecord(ended)
            historyStore.append(record)
            historyStore.load()
        } else {
            ended.history
        }
        val newTotalKm = updatedHistory.sumOf { it.coveredKm }
        val milestone = com.scoova.navlayer.core.RideMilestones
            .lastCrossed(prevTotalKm, newTotalKm)?.km
        _state.value = ended.copy(
            history = updatedHistory,
            milestoneJustCrossed = milestone,
        )
    }

    /** Clear the milestone after the Summary toast has been seen. */
    fun acknowledgeMilestone() {
        _state.value = _state.value.copy(milestoneJustCrossed = null)
    }

    fun resetToPlan() {
        simulationJob?.cancel()
        NavigationService.stop(getApplication())
        activeRideStore.clear()
        offRoute.setRoute(emptyList())
        // Preserve the saved-state slices we want to keep across rides:
        // settings, history, profile, origin. Everything else (route
        // shape, breadcrumb trail, milestone toast) resets fresh.
        _state.value = RideState(
            phase = Phase.Plan,
            profile = _state.value.profile,
            origin = _state.value.origin,
            settings = _state.value.settings,
            history = _state.value.history,
        )
        // Make sure the Plan-time location flow is running again so
        // the puck has live coordinates. Idempotent.
        onLocationPermissionGranted()
    }

    /** Real GPS update tick — pipe FusedLocation into here.
     *  [accuracyM] is the smoothed accuracy from [NavRuntimeBus]; used
     *  by [OffRouteMonitor] to widen its threshold when GPS quality
     *  degrades (pocket / urban canyon). Pass 0 to keep the static
     *  threshold. */
    @JvmOverloads
    fun onLocation(
        lat: Double,
        lon: Double,
        speedMps: Float,
        bearingDeg: Float,
        accuracyM: Float = 0f,
    ) {
        if (_state.value.phase != Phase.Ride) return
        // Append to the breadcrumb trail, but drop samples that are
        // too close to the previous one (< 4 m). The fused provider
        // can emit duplicates while stationary at a red light; if we
        // keep them all, the trail becomes a 10,000-point clump at
        // the intersection that takes a noticeable beat to render on
        // the Summary map.
        // Skip the breadcrumb trail entirely when the rider has
        // opted out of ride recording — navigation-only mode keeps
        // no GPS history.
        val prev = _state.value.actualPath.lastOrNull()
        val keep = _state.value.settings.recordRides && (prev == null ||
            com.scoova.navlayer.core.GeoMath.haversineMeters(prev[0], prev[1], lat, lon) >= 4.0)
        val newPath = if (keep) _state.value.actualPath + doubleArrayOf(lat, lon)
                      else _state.value.actualPath
        _state.value = _state.value.copy(
            origin = LatLon(lat, lon),
            currentSpeedKph = (speedMps * 3.6f).coerceAtLeast(0f),
            actualPath = newPath,
        )
        routing.onLocation(lat, lon, speedMps, bearingDeg)
        offRoute.onLocation(lat, lon, accuracyM.toDouble())
        // Push fresh ETA / distance-remaining into the foreground
        // notification. Throttled by the OS — the channel is
        // IMPORTANCE_LOW + setOnlyAlertOnce, so updates are silent.
        refreshNotification()
        // Persist the active-ride snapshot (throttled — see field
        // doc). On a process kill, the next launch will hydrate from
        // here and pick the rider up mid-trip.
        saveActiveRide()
    }

    /**
     * Kick off the in-app feature tour — a prepared, voice-guided ride
     * with annotated callouts at key moments (sensor fusion, off-route
     * detection, eyes-off mode, arrival). Surfaced from:
     *   • the post-onboarding "Take a tour?" prompt on Plan
     *   • Settings → About → "Take a tour"
     * Flips [RideState.isTour] so the Ride screen renders the
     * [TourOverlay], then routes through [simulateRide], whose
     * empty-route branch self-plants a synthetic Cairo origin + plans
     * the demo destination and auto-recurses once the route arrives.
     */
    fun startTour() {
        // A tour is a self-contained, prepared ride. We intentionally
        // discard the rider's real GPS and seed a known Cairo-downtown
        // origin so:
        //   • The route is the same recognisable leg every time, not
        //     "from wherever the user happens to be standing".
        //   • The tour works the same on a real phone with GPS lock and
        //     on a dev box with mocked location.
        //   • The location-flow gates ([planLocationFlow], the
        //     [NavRuntimeBus.locations] collector) both honour [isTour]
        //     and stop writing real GPS into state.origin — so the only
        //     coordinate source during the tour is the simulator.
        _state.value = _state.value.copy(
            isTour = true,
            origin = LatLon(30.0444, 31.2357),
        )
        planLocationJob?.cancel()
        simulateRide()
    }

    /**
     * Permanently silence the post-onboarding "Take a tour?" prompt.
     * Called from both buttons of the dialog (Take tour AND Maybe
     * later) — we only auto-offer the tour once per install. The
     * Settings entry remains for replay.
     */
    fun markTourOffered() {
        val newSettings = _state.value.settings.copy(tourOffered = true)
        _state.value = _state.value.copy(settings = newSettings)
        SettingsStore.save(prefs, newSettings)
    }

    /**
     * Read by the Plan screen to decide whether to surface the
     * one-time "Take a tour?" prompt. Returns false once
     * [markTourOffered] has been called.
     */
    fun shouldOfferTour(): Boolean =
        _state.value.settings.onboardingDone && !_state.value.settings.tourOffered

    /**
     * Simulate the ride for prospects (and ourselves) who want to hear
     * the cues without leaving their chair. Walks the polyline at the
     * persona's average speed, interpolating between vertices at small
     * spatial steps so progress is smooth (not vertex-to-vertex jumps),
     * and captures a typed event log for the diagnostic report.
     *
     * **Tick model**
     * The sim ticks every [SIM_TICK_MS] ms; each tick advances the
     * rider's cumulative distance by `speedMps * (tick/1000)`. That
     * lands as roughly 1 m per tick at cyclist pace — small enough to
     * give the threshold logic in [com.scoova.navlayer.core.GuidanceMonitor]
     * a chance to fire cues at the right meter values.
     *
     * **Event log**
     * Every banner update and every voice cue spoken during the sim
     * is appended to [_state.value.simLog]. At ride-end the log is
     * turned into a [com.scoova.navlayer.core.SimulationReport] that
     * compares cue timings against the configured thresholds — a
     * regression-test fixture for the navigation engine.
     */
    fun simulateRide() {
        val shape = _state.value.routeShape
        if (shape.size < 2) {
            // No route planned yet — this is the "Try the SDK · 60s
            // demo" path fired by the Plan-tab hero CTA. We plant a
            // synthetic Cairo-downtown origin (so the absence of a
            // GPS fix doesn't block the demo), plan a ~1.5 km leg
            // through the city grid, and set [pendingDemoSimulate]
            // so [planRouteTo]'s success path self-recurses back
            // into this function the moment the route arrives. The
            // buyer experiences "one tap → SDK demonstrating" — no
            // intermediate "now pick a destination" step.
            if (_state.value.origin == null) {
                _state.value = _state.value.copy(origin = LatLon(30.0444, 31.2357))
            }
            pendingDemoSimulate = true
            planRouteTo(30.0580, 31.2419, "60-second SDK demo")
            return
        }
        val profile = _state.value.profile ?: Profile.Bicycle
        startRide()
        _state.value = _state.value.copy(
            isSimulating = true,
            simLog = emptyList(),
            simulationReport = null,
        )
        simulationJob?.cancel()
        simEventCaptureJob?.cancel()
        // Capture banner + voice events for the duration of the sim.
        // Banner: every change to currentInstruction is logged with
        // meters-to-maneuver and the rendered verb.
        // Voice: every utterance spoken (including the welcome and
        // any "Let's go" replays — should be at most one) is logged.
        val navLayer = nav
        val simStartMs = System.currentTimeMillis()
        simEventCaptureJob = viewModelScope.launch {
            launch {
                navLayer.currentInstruction.collect { cue ->
                    cue ?: return@collect
                    appendSimEvent(
                        SimEvent.Banner(
                            tsMs = System.currentTimeMillis() - simStartMs,
                            maneuverIndex = cue.maneuver.index,
                            metersToManeuver = cue.metersToManeuver,
                            verb = cue.maneuver.bannerVerb ?: cue.text,
                            anchor = cue.maneuver.bannerAnchor,
                            phase = cue.phase.name,
                        )
                    )
                }
            }
            launch {
                navLayer.spokenEvents.collect { ev ->
                    appendSimEvent(
                        SimEvent.Voice(
                            tsMs = System.currentTimeMillis() - simStartMs,
                            text = ev.text,
                            tone = ev.tone.name,
                            spatialPan = ev.spatialPan,
                        )
                    )
                }
            }
        }
        simulationJob = viewModelScope.launch {
            val speedMps = profile.averageKmh.toFloat() / 3.6f
            val tickSec = SIM_TICK_MS / 1000.0
            val stepMeters = (speedMps * tickSec)  // ~1 m at cyclist pace
            // Cumulative-distance table: cum[i] = total metres of the
            // polyline from index 0 to index i (inclusive). Used by
            // the interpolator to find which segment we're on for
            // any given cumulative-metres value.
            val cum = DoubleArray(shape.size)
            for (i in 1 until shape.size) {
                cum[i] = cum[i - 1] + com.scoova.navlayer.core.GeoMath
                    .haversineMeters(shape[i - 1][0], shape[i - 1][1], shape[i][0], shape[i][1])
            }
            val totalMeters = cum.last()
            var distanceCovered = 0.0
            var segmentIdx = 0
            while (distanceCovered < totalMeters) {
                // Advance segmentIdx until cum[idx+1] > distanceCovered
                while (segmentIdx < cum.lastIndex - 1 && cum[segmentIdx + 1] < distanceCovered) {
                    segmentIdx += 1
                }
                val segStart = shape[segmentIdx]
                val segEnd = shape[segmentIdx + 1]
                val segLen = cum[segmentIdx + 1] - cum[segmentIdx]
                val tInSeg = if (segLen > 0)
                    ((distanceCovered - cum[segmentIdx]) / segLen).coerceIn(0.0, 1.0)
                else 0.0
                val lat = segStart[0] + (segEnd[0] - segStart[0]) * tInSeg
                val lon = segStart[1] + (segEnd[1] - segStart[1]) * tInSeg
                // Approximate segment bearing for the sim's reported
                // heading. The nav layer's guidance monitor uses
                // compass heading from the sensor for its checks,
                // but reporting a real-looking bearing also helps
                // any consumer that watches ProgressEvent.bearingDeg.
                val bearing = com.scoova.navlayer.core.GeoMath
                    .bearingDeg(segStart[0], segStart[1], segEnd[0], segEnd[1]).toFloat()
                // Order matters: seed the compass FIRST so when
                // `onLocation` flows into [GuidanceMonitor.onProgress]
                // and it reads `compassHeadingDeg` for the heading-
                // mismatch check, the value reflects our simulated
                // motion direction, not whatever the device's
                // (idle) rotation-vector sensor reported last. The
                // rider isn't physically rotating the phone during a
                // sim — the real sensor lies; we override.
                navLayer.simulateHeading(bearing)
                onLocation(lat, lon, speedMps, bearing)
                _state.value = _state.value.copy(
                    coveredKm = distanceCovered / 1000.0,
                )
                distanceCovered += stepMeters
                delay(SIM_TICK_MS)
            }
            // Final pin at the destination so the puck visibly lands.
            val last = shape.last()
            onLocation(last[0], last[1], speedMps, 0f)
            endRide()
        }
    }

    private fun appendSimEvent(event: SimEvent) {
        val current = _state.value.simLog
        _state.value = _state.value.copy(simLog = current + event)
    }

    /**
     * Bridge from [SensorRelay] in the Activity through to the nav layer.
     * Each sensor tick → fusion update → optional heading / turn-confirm /
     * crash event downstream of [ScoovaNavLayer].
     */
    fun onMotion(frame: com.scoova.navlayer.core.MotionFrame) {
        _nav?.onMotion(frame)
    }

    /**
     * Save a rider's free-text note onto the most-recently-completed
     * ride record. Called from the Summary screen — by the time the
     * rider sees Summary, [endRide] has already appended the record
     * to history so we just update it in place.
     *
     * Trims to [RideRecord.MAX_NOTE_CHARS]. An empty / blank note
     * clears the field rather than writing whitespace.
     */
    fun setLastRideNote(note: String) {
        val trimmed = note.trim().take(RideRecord.MAX_NOTE_CHARS).ifBlank { null }
        val current = _state.value.history.firstOrNull() ?: return
        val updated = current.copy(notes = trimmed)
        historyStore.update(updated)
        _state.value = _state.value.copy(history = historyStore.load())
    }

    fun setWeightKg(value: Int) {
        val clamped = value.coerceIn(30, 200)
        val newSettings = _state.value.settings.copy(weightKg = clamped)
        _state.value = _state.value.copy(settings = newSettings)
        SettingsStore.save(prefs, newSettings)
    }

    fun setAutoMapTheme(enabled: Boolean) {
        val newSettings = _state.value.settings.copy(autoMapTheme = enabled)
        _state.value = _state.value.copy(settings = newSettings)
        SettingsStore.save(prefs, newSettings)
    }

    /**
     * Toggle eyes-off voice mode. Affects the NEXT route planning
     * request — the routing-server picks landmark-led templates
     * ("After McDonald's, turn right…") instead of distance-led ones
     * ("In 350 m, turn right…"). No effect on a route that's
     * already in progress; the rider has to re-plan or finish the
     * trip to hear the new style.
     */
    fun setEyesOff(enabled: Boolean) {
        val newSettings = _state.value.settings.copy(eyesOff = enabled)
        _state.value = _state.value.copy(settings = newSettings)
        SettingsStore.save(prefs, newSettings)
    }

    /**
     * Master switch for ride tracking. Off → navigation-only: no
     * trail collected, no history written, no Summary screen, and
     * the cloud [setSaveMode] preference is moot. Doesn't erase
     * existing history — see [RideSettings.recordRides].
     */
    fun setRecordRides(enabled: Boolean) {
        val newSettings = _state.value.settings.copy(recordRides = enabled)
        _state.value = _state.value.copy(settings = newSettings)
        SettingsStore.save(prefs, newSettings)
    }

    // ─── Cloud account / save mode ───────────────────────────────────────

    /** Persist the rider's global save-rides default. The per-ride
     *  Summary control still overrides this one-off. */
    fun setSaveMode(mode: SaveMode) {
        val newSettings = _state.value.settings.copy(saveMode = mode)
        _state.value = _state.value.copy(settings = newSettings)
        SettingsStore.save(prefs, newSettings)
    }


    /** Leave Phase.Auth without signing in (back-button / cancel). */
    fun cancelAuthFlow() {
        _state.value = _state.value.copy(
            phase = Phase.Plan,
            authBusy = false,
            authError = null,
            authInfo = null,
        )
    }

    /** Wipe the inline error / info banner. AuthScreen calls this on
     *  any form input so a stale "wrong password" doesn't linger. */
    fun dismissAuthMessages() {
        if (_state.value.authError != null || _state.value.authInfo != null) {
            _state.value = _state.value.copy(authError = null, authInfo = null)
        }
    }

    /**
     * Sign in with email + password. On success, pops back to Plan
     * (the rider is now signed in, [signedIn] StateFlow flips). On
     * failure, surfaces the error inline so the rider can correct
     * and retry.
     */
    fun signIn(email: String, password: String) {
        if (_state.value.authBusy) return
        _state.value = _state.value.copy(authBusy = true, authError = null, authInfo = null)
        viewModelScope.launch {
            when (val r = cloudClient.login(email, password)) {
                is com.scoova.ride.cloud.CloudResult.Success -> {
                    _state.value = _state.value.copy(
                        phase = Phase.Plan,
                        authBusy = false,
                    )
                }
                is com.scoova.ride.cloud.CloudResult.Failure -> {
                    _state.value = _state.value.copy(
                        authBusy = false,
                        authError = r.error.message,
                    )
                }
            }
        }
    }

    /** Register a new account, then auto-sign-in. Success path same as
     *  [signIn]. The display name is optional — null/blank becomes the
     *  email's local-part on the dashboard. */
    fun signUp(displayName: String?, email: String, password: String) {
        if (_state.value.authBusy) return
        _state.value = _state.value.copy(authBusy = true, authError = null, authInfo = null)
        val locale = _state.value.settings.locale
        viewModelScope.launch {
            when (val r = cloudClient.register(email, password, displayName, locale)) {
                is com.scoova.ride.cloud.CloudResult.Success -> {
                    _state.value = _state.value.copy(
                        phase = Phase.Plan,
                        authBusy = false,
                    )
                }
                is com.scoova.ride.cloud.CloudResult.Failure -> {
                    _state.value = _state.value.copy(
                        authBusy = false,
                        authError = r.error.message,
                    )
                }
            }
        }
    }

    /** Send the password-reset email. The server returns 200 regardless
     *  of whether the email exists — we mirror that ambiguity to the
     *  user to avoid leaking account existence. */
    fun forgotPassword(email: String) {
        if (_state.value.authBusy) return
        _state.value = _state.value.copy(authBusy = true, authError = null, authInfo = null)
        viewModelScope.launch {
            when (val r = cloudClient.forgotPassword(email)) {
                is com.scoova.ride.cloud.CloudResult.Success -> {
                    _state.value = _state.value.copy(
                        authBusy = false,
                        authInfo = "If an account with that email exists, we just sent a reset link.",
                    )
                }
                is com.scoova.ride.cloud.CloudResult.Failure -> {
                    _state.value = _state.value.copy(
                        authBusy = false,
                        authError = r.error.message,
                    )
                }
            }
        }
    }

    /** Sign out — drops local token + flips [signedIn] to null.
     *  No server call; the JWT just expires on its own. */
    fun signOut() {
        cloudClient.signOut()
    }

    /** Upload a ride to the consumer cloud. Used by the Summary
     *  screen's per-ride save button + the auto-upload path when
     *  [SaveMode.Always] is set. Returns true when the upload
     *  succeeded — caller updates the local RideRecord with the cloud
     *  doc ID + sets uploadedAtMs. */
    suspend fun uploadRide(record: RideRecord): Boolean {
        return when (val r = cloudClient.saveRide(record)) {
            is com.scoova.ride.cloud.CloudResult.Success -> {
                val updated = record.copy(
                    cloudDocId = r.value,
                    uploadedAtMs = System.currentTimeMillis(),
                )
                historyStore.update(updated)
                _state.value = _state.value.copy(history = historyStore.load())
                true
            }
            is com.scoova.ride.cloud.CloudResult.Failure -> false
        }
    }

    /** Dismiss the Plan-screen battery-optimisation hint. The rider
     *  can still find the toggle in Settings → Reliability. */
    fun dismissBatteryHint() {
        val newSettings = _state.value.settings.copy(batteryHintDismissed = true)
        _state.value = _state.value.copy(settings = newSettings)
        SettingsStore.save(prefs, newSettings)
    }

    /** Whether to surface the reliability nudge on the Plan map. True
     *  when (a) the user hasn't dismissed it, (b) we're not whitelisted
     *  from battery optimisation, and (c) either we've already started
     *  a ride before (so the rider has felt the pain) OR we're on a
     *  known-aggressive OEM. */
    fun shouldShowBatteryHint(): Boolean {
        if (_state.value.settings.batteryHintDismissed) return false
        val ctx = getApplication<Application>()
        if (BatteryOptimizationHelper.isIgnoringBatteryOptimizations(ctx)) return false
        val hasRidden = _state.value.history.isNotEmpty()
        return hasRidden || BatteryOptimizationHelper.isLikelyAggressiveOem()
    }

    /**
     * Build a portable JSON dump of the rider's data — settings,
     * favorites, ride history (including GPS traces and notes). Used
     * by the Settings "Export data" button.
     *
     * Returns a UTF-8 string ready for either Intent.ACTION_SEND or
     * file-save. Schema is intentionally readable; not stable for
     * cross-version migration yet (v1).
     */
    fun buildExportJson(): String {
        val s = _state.value
        val json = org.json.JSONObject()
        json.put("exportedAtMs", System.currentTimeMillis())
        json.put("schemaVersion", 1)
        json.put("rideCount", s.history.size)
        json.put("settings", org.json.JSONObject().apply {
            put("locale", s.settings.locale)
            put("unitsMetric", s.settings.unitsMetric)
            put("weightKg", s.settings.weightKg)
            put("voiceEnabled", s.settings.voiceEnabled)
            put("spatialAudio", s.settings.spatialAudio)
            put("autoMapTheme", s.settings.autoMapTheme)
        })
        json.put("homePlace", s.settings.homePlace?.toJsonString()?.let { org.json.JSONObject(it) } ?: org.json.JSONObject.NULL)
        json.put("workPlace", s.settings.workPlace?.toJsonString()?.let { org.json.JSONObject(it) } ?: org.json.JSONObject.NULL)
        json.put("favorites", org.json.JSONArray().apply {
            s.settings.favorites.forEach { put(org.json.JSONObject(it.toJsonString())) }
        })
        json.put("history", org.json.JSONArray().apply {
            s.history.forEach { put(it.toJson()) }
        })
        return json.toString(2)
    }

    /**
     * Privacy nuke. Wipes:
     *   • Ride history file
     *   • Favorites + Home/Work saved places
     *   • All settings (returns to defaults; onboarding will replay)
     * The current ride (if any) is NOT interrupted.
     */
    fun clearAllData() {
        historyStore.clear()
        activeRideStore.clear()
        prefs.edit().clear().apply()
        val fresh = SettingsStore.load(prefs)
        _state.value = _state.value.copy(
            settings = fresh,
            history = emptyList(),
            phase = RideViewModel.Phase.Onboarding,
            profile = null,
        )
    }

    /** Setter for the compass-accuracy flow. The flow itself
     *  (`_compassAccuracy`) is declared higher up — see the NPE-race
     *  comment there for why. */
    fun onCompassAccuracy(accuracy: Int) {
        _compassAccuracy.value = accuracy
    }

    override fun onCleared() {
        _nav?.stop()
        battery.stop()
        planLocationJob?.cancel()
        planLocationJob = null
        // Safety net — if the VM is being torn down mid-ride (process kill,
        // task removed), make sure we don't leave the foreground service
        // claim dangling. Idempotent on its own (no-op if already stopped).
        NavigationService.stop(getApplication())
        runCatching { (getApplication() as Context).unregisterReceiver(notifReceiver) }
        super.onCleared()
    }

    private fun detectLocale(): String {
        // Default to English; can be wired to a user setting later.
        return "en-US"
    }

    private companion object {
        // Legacy plaintext-prefs filename — referenced only by the
        // [SettingsStore] migration path. Encrypted prefs live in
        // `scoova_ride_encrypted` (see [SecureStorage]).
        const val PREFS_NAME = "scoova_ride"  // kept for migration compat
        const val KEY_PROFILE = "profile"
        /** How often the simulator advances along the polyline.
         *  Tuned to ~once per second so the follow camera has time
         *  to render between updates (the Android emulator's GLES
         *  encoder can't keep up with faster sim+follow streams) and
         *  the [com.scoova.navlayer.core.GuidanceMonitor]'s
         *  cooldown timers tick normally. Walker pace (~1.4 m/s) at
         *  1 s = ~1.4 m per step, still smooth enough that threshold
         *  crossings fire one tier at a time. */
        const val SIM_TICK_MS: Long = 1000L
    }
}

/** Average travel speed (km/h) — used for ETA estimate and simulation
 *  pace. Scooter shares 18 km/h with bicycle because they now route on
 *  the same costing (see [Profile.Scooter.routingProfile]); a 24 km/h
 *  estimate would make every scooter ETA 33 % shorter than the server
 *  actually computes. */
private val Profile.averageKmh: Double get() = when (this) {
    Profile.Foot       -> 5.5
    Profile.Bicycle    -> 18.0
    Profile.Scooter    -> 18.0
    Profile.Motorcycle -> 45.0
    Profile.Car        -> 45.0
}

private fun computeDistanceKm(shape: List<DoubleArray>): Double {
    if (shape.size < 2) return 0.0
    var total = 0.0
    for (i in 1 until shape.size) {
        val a = shape[i - 1]; val b = shape[i]
        total += haversineMeters(a[0], a[1], b[0], b[1])
    }
    return total / 1000.0
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
        kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
    return R * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
}
