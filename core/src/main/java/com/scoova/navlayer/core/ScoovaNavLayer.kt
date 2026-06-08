package com.scoova.navlayer.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * The public Scoova Nav Layer entry point.
 *
 * **5-line integration:**
 * ```kotlin
 * val nav = ScoovaNavLayer.builder(context)
 *     .apiKey("sk_live_…")
 *     .locale("ar-EG")
 *     .profile("scooter")
 *     .landmarks(true)
 *     .build()
 * nav.start()
 * // …
 * // wire your host SDK adapter to push events:
 * yourHostAdapter.attach(nav)
 * ```
 *
 * Once attached, the layer:
 *   - Speaks dialect-aware turn-by-turn cues (mute the host SDK's voice)
 *   - Plays left-turn cues into the left ear (spatial audio, default on)
 *   - Exposes [currentInstruction] for your banner/UI to bind to
 *   - Exposes [headingDeg] for a sensor-driven heading puck
 *   - Optionally fetches the closest landmark and weaves it into the cue
 */
public class ScoovaNavLayer private constructor(
    private val ctx: Context,
    public val apiKey: String,
    public val locale: String,
    public val profile: String,
    private val landmarksEnabled: Boolean,
    private val brandedFreeTrial: Boolean,
    private val spatialAudio: Boolean,
) {
    private val voice = VoiceEngine(ctx).also {
        it.locale = locale
        it.spatialEnabled = spatialAudio
    }
    private val landmarks = if (landmarksEnabled) LandmarkClient(apiKey) else null
    /** Last GPS fix seen by [onProgress] — supplied to [HeadingProvider]
     *  so its declination (magnetic → true north) correction has a
     *  location to work from. */
    private var lastKnownLocation: android.location.Location? = null
    private val heading = HeadingProvider(ctx) { lastKnownLocation }
    private val thresholds = Thresholds.forProfile(profile)
    private val tracker = ProgressTracker(thresholds)

    /**
     * Eyes-off behaviour orchestrator — owns post-turn confirmation,
     * recovery cue on missed turns, and reaffirmation on long
     * segments. ScoovaNavLayer delegates by passing it the maneuver
     * list on each new route and pushing yaw / progress signals as
     * they arrive. The callback feeds back through [saySpoken] so
     * audio policy stays centralised here.
     */
    private val eyesOff = EyesOffGuide(
        onSpeak = { text, tone -> saySpoken(text, tone) },
    )

    /**
     * Pocket vs handheld classifier — drives accuracy tolerance in
     * the off-route monitor and weighting in the confirmation gate.
     * Public so the host can observe (e.g. surface a "phone in
     * pocket" indicator in diagnostics).
     */
    private val pocketDetector = PocketDetector()
    public val phoneInPocket: kotlinx.coroutines.flow.StateFlow<Boolean> get() =
        pocketDetector.phoneInPocket

    private var maneuvers: List<ManeuverEvent> = emptyList()
    /** Current route polyline (mirrors iOS' `shape`). Cached so the
     *  [GuidanceReasoner] can project the rider onto the line every
     *  tick without re-decoding. Set in [setRouteShape]. */
    private var routeShape: List<DoubleArray> = emptyList()
    /** Server-emitted per-route corridor (cross-streets, ordinals,
     *  graph fingerprints, neighbour graph). Null on legacy responses;
     *  reasoner then falls back to lateral-distance heuristics. */
    private var corridor: Corridor? = null
    /** Latest reasoner output — published so the host can bind a HUD
     *  to it, and consumed internally by the off-route / reroute path. */
    private val _liveGuidance = MutableStateFlow<LiveGuidanceState?>(null)
    public val liveGuidance: StateFlow<LiveGuidanceState?> = _liveGuidance.asStateFlow()
    /** Per-cue telemetry stream — every utterance fires a [CueEvent]
     *  the host can pipe into [CueTelemetrySender] or its own analytics. */
    private val _cueEvents = MutableSharedFlow<CueEvent>(extraBufferCapacity = 16)
    public val cueEvents: SharedFlow<CueEvent> = _cueEvents.asSharedFlow()
    /** Per-maneuver cue track — the "subtitles", a 1:1 mirror of the
     *  iOS engine. Built on [onRoute]; fired once-each from [onProgress]. */
    private var cueSchedule: Map<Int, List<CuePoint>> = emptyMap()
    /** Indices of cues already spoken per maneuver — each fires once. */
    private val cueFired: MutableMap<Int, MutableSet<Int>> = HashMap()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _currentInstruction = MutableStateFlow<DisplayCue?>(null)
    public val currentInstruction: StateFlow<DisplayCue?> = _currentInstruction.asStateFlow()

    private val _headingDeg = MutableStateFlow(0f)
    public val headingDeg: StateFlow<Float> = _headingDeg.asStateFlow()

    /**
     * Flips to true the moment the rider reaches the destination — the
     * arrival cue has been spoken and the trip is over. The host app
     * observes this to close out the ride (write history, show the
     * summary). Latches: it never flips back within a route, and the
     * arrival cue fires exactly once. Reset to false by [onRoute].
     */
    private val _arrived = MutableStateFlow(false)
    public val arrived: StateFlow<Boolean> = _arrived.asStateFlow()

    /**
     * Wall-clock when the rider first went still within the wider
     * arrival radius (see [ARRIVE_STOP_RADIUS_M]). 0 = not currently
     * stopped near the destination. Used for the parked-at-the-kerb
     * arrival path. Reset by [onRoute].
     */
    private var stoppedNearDestSinceMs: Long = 0L
    /**
     * When true, [_headingDeg] is being driven by the GPS course in
     * [onProgress] rather than the rotation-vector sensor. Set while
     * the phone is pocketed AND the rider is moving fast enough for
     * GPS bearing to be reliable. The sensor-stream collector checks
     * this flag and yields. Cleared back to false the moment the
     * phone leaves the pocket (sensor heading becomes the rider's
     * facing again).
     */
    private var headingFromGps: Boolean = false
    /**
     * True once [simulateHeading] has fed a heading — the simulated
     * route's bearing then owns [_headingDeg], and the live
     * rotation-vector sensor STOPS writing it. Without this the ~50 Hz
     * sensor stream overwrites the simulated heading on every tick, so
     * the map follows the physical phone instead of the simulated
     * travel direction (the map points "backward" during a sim ride).
     * Reset by [onRoute] so a subsequent real ride uses the sensor.
     */
    private var headingFromSim: Boolean = false

    // ── Sensor / IMU fusion outputs ──────────────────────────────────────
    // Filled by [onMotion]. Until the host adapter starts forwarding
    // sensor frames the flows stay at their defaults (null / no emissions).
    private val motionFusion = MotionFusion()
    private val _compassHeadingDeg = MutableStateFlow<Float?>(null)
    /** Magnetic compass heading, smoothed, 0..360. Null until the host
     *  adapter forwards its first IMU frame via [onMotion]. */
    public val compassHeadingDeg: StateFlow<Float?> = _compassHeadingDeg.asStateFlow()

    private val _crashEvents = MutableSharedFlow<CrashEvent>(
        replay = 0, extraBufferCapacity = 4,
    )
    /** One-shot stream of crash / hard-brake events the rider should be
     *  alerted to. Consumers `collectLatest` to wire crash overlays /
     *  emergency-contact prompts. */
    public val crashEvents: SharedFlow<CrashEvent> = _crashEvents.asSharedFlow()

    /** Tracks the *expected* turn direction from the most recently fired
     *  cue so we can confirm execution from gyro/compass yaw. */
    private var pendingTurnDirection: TurnDir? = null
    private var pendingTurnFiredAtMs: Long = 0
    /** Latest progress tick's metres-to-destination. Cached for the
     *  silence-filler verbal cue, which composes "Still on X. N metres
     *  to your destination." without re-receiving the ProgressEvent. */
    private var latestMetersRemaining: Int = 0
    /** Latest progress tick's metres to the upcoming maneuver. Used
     *  by the silence-filler so its distance number matches the
     *  banner — the banner counts down to the next turn, not the
     *  destination. Mismatched numbers read as a bug. */
    private var latestMetersToUpcomingManeuver: Int = 0
    /** Window after a turn cue inside which a matching yaw counts as
     *  confirmation. 8 seconds covers slow scooter turns + light delay. */
    private val turnConfirmWindowMs: Long = 8_000L

    // TurnDir is declared at top level of this file so EyesOffGuide
    // (also in :core) can reference it without a circular import.

    /**
     * Continuous closed-loop guidance — silence/drift/off-route/heading/
     * speed/almost-there state machine. Ticked from every [onProgress]
     * call; reads compass heading from [onMotion] via [GuidanceMonitor.onCompassHeading].
     */
    private val guidance = GuidanceMonitor()

    private val _diagnostics = MutableStateFlow(Diagnostics())
    public val diagnostics: StateFlow<Diagnostics> = _diagnostics.asStateFlow()

    private var headingJob: Job? = null
    private var diagnosticsJob: Job? = null
    /**
     * Hash of the maneuver list the rider was last welcomed onto.
     * Used by [onRoute] / [onProgress] to suppress duplicate welcome
     * cues when the host calls `onRoute` more than once with the same
     * effective route (re-rate, hot-restart, etc.). Resets to 0 only
     * when [onRoute] receives a structurally-different maneuver list.
     */
    /**
     * Stream of every utterance the voice engine successfully accepted
     * for playback. Each [VoiceEngine.SpokenEvent] carries text + tone
     * + spatial pan + wall-clock timestamp; the diagnostic simulator
     * captures these into a [SimulationReport].
     */
    public val spokenEvents: kotlinx.coroutines.flow.SharedFlow<VoiceEngine.SpokenEvent>
        get() = voice.spokenEvents

    /**
     * Stream of every guidance event the layer fired (drift, off-route,
     * slow-down, wrong-way-heading, almost-there, keep-going). The host
     * UI uses these to flash the spoken text on the banner so the
     * rider sees what they hear — "wrong way please turn around" on the
     * banner, not just the voice.
     */
    public data class GuidanceCue(
        public val tsMs: Long,
        public val kind: String,   // "wrongWay" / "driftLeft" / "slowDown" / ...
        public val text: String,
        public val toneName: String,
    )
    private val _guidanceCues = kotlinx.coroutines.flow.MutableSharedFlow<GuidanceCue>(
        extraBufferCapacity = 16,
    )
    public val guidanceCues: kotlinx.coroutines.flow.SharedFlow<GuidanceCue> = _guidanceCues.asSharedFlow()

    /** Configured cue thresholds (m). Exposed for the diagnostic
     *  report's expected-band comparison. */
    public val cueThresholds: IntArray get() = thresholds

    /** Read-only snapshot of the current route's maneuver list — used
     *  by the diagnostic simulator to build its report. */
    public fun maneuvers(): List<ManeuverEvent> = maneuvers.toList()

    private var welcomedRouteHash: Int = 0
    /** Wall-clock of the last welcome we spoke. Backup throttle so a
     *  pathological "welcome on every tick" can't happen even if hash
     *  comparison fails — never speak the welcome more than once per
     *  60 s window. */
    private var lastWelcomedAtMs: Long = 0L

    /**
     * Host callback fired when the SDK decides a reroute is needed
     * (OffRoute, WrongWayHeading, or MissedTurn event past throttle).
     * The host's routing adapter listens and refetches the route from
     * the rider's current location to the memoised destination. iOS
     * parity for `onRerouteNeeded` on ScoovaNavLayer.swift.
     */
    public var onRerouteNeeded: (() -> Unit)? = null

    /**
     * Host callback fired after a route lands in [setRouteShape] —
     * both initial routes and reroutes. Carries the new polyline so
     * the host UI can update its GeoJSON / camera fit. iOS parity for
     * `onRouteRefreshed` on ScoovaNavLayer.swift.
     */
    public var onRouteRefreshed: ((List<DoubleArray>) -> Unit)? = null

    /**
     * Wall-clock of the last reroute we requested. Used together with
     * [lastRerouteLandedAtMs] to throttle reroute fetches — without
     * this, every off-route tick fires a new HTTP request and the
     * rider gets a "Recalculating" cue every 250 ms. iOS uses the
     * same fields with identical millis.
     */
    private var rerouteRequestedAtMs: Long = 0
    /** Wall-clock of the last reroute response. Adapter calls
     *  [onRouteLanded] when its startRoute completes. */
    private var lastRerouteLandedAtMs: Long = 0
    private val rerouteFetchThrottleMs: Long = 8_000
    private val rerouteCueCooldownMs: Long = 10_000

    /** Adapter calls this on every successful route fetch (initial OR
     *  reroute) so [rerouteRequestedAtMs] / [lastRerouteLandedAtMs]
     *  throttling stays accurate. iOS adapter calls the equivalent. */
    public fun onRouteLanded() {
        lastRerouteLandedAtMs = System.currentTimeMillis()
    }

    /**
     * Per-route record of which maneuvers have had ANY voice cue
     * spoken. When a maneuver gets its FIRST cue (no heads-up fired
     * yet because the rider started inside the Far threshold), we
     * prepend a distance lead-in — "In 50 meters, turn left onto X" —
     * so the cue has the contextual setup a heads-up would have given.
     * Without this, the rider's first audio is the bare "Turn left
     * onto X" with no warning, which routinely lands too late for
     * scooters / cyclists to plan the lane. Cleared on [onRoute].
     */
    private val firstSpeechSpokenFor: MutableSet<Int> = mutableSetOf()

    public fun start() {
        voice.init()
        // Keep the TTS engine warm so urgent cues don't pay 200–400 ms
        // of cold-start latency. Especially important for the Near
        // phase ("Turn left now") where a stale engine costs 3+ meters
        // at 30 km/h. Stopped on shutdown so we don't drain battery
        // outside an active ride.
        voice.keepWarmStart()
        headingJob = scope.launch {
            // Sensor-driven heading feeds the map puck — but ONLY while
            // [headingFromGps] is false. When the phone is pocketed and
            // the rider is moving, [onProgress] takes over and drives
            // _headingDeg from the GPS course instead (the pocketed
            // rotation-vector sensor reports the pocket's orientation,
            // not the rider's travel direction). The guard here stops
            // the sensor stream from fighting the GPS-course value.
            heading.stream().collectLatest {
                if (!headingFromGps && !headingFromSim) _headingDeg.value = it
            }
        }
        // Stream a fresh Diagnostics whenever any input changes — route,
        // tts-ready, latency, locale fallback. Single observable surface.
        diagnosticsJob = scope.launch {
            combine(
                voice.reliability.route,
                voice.ttsReady,
                voice.lastCueLatencyMs,
                voice.voiceLocaleResolved,
                voice.voiceFallback,
            ) { route, ttsReady, latency, resolved, fallback ->
                Diagnostics(
                    audioRoute = route,
                    ttsEngineReady = ttsReady,
                    lastCueLatencyMs = latency,
                    voiceLocaleResolved = resolved,
                    voiceFallback = fallback,
                    lookaheadOffsetMs = route.defaultLookaheadMs,
                )
            }.collectLatest { _diagnostics.value = it }
        }
    }

    public fun stop() {
        headingJob?.cancel()
        diagnosticsJob?.cancel()
        voice.keepWarmStop()
        voice.shutdown()
        scope.cancel()
    }

    /** Mute / un-mute the voice cues without rebuilding the layer. */
    public fun setVoiceEnabled(enabled: Boolean) {
        voice.voiceEnabled = enabled
    }

    /**
     * Override the compass heading from outside the sensor pipeline.
     * Used by the diagnostic simulator: when the sim walks a route
     * the rider isn't actually rotating the phone, so the
     * rotation-vector sensor reports a stale heading and
     * [GuidanceMonitor]'s heading-mismatch check fires false
     * "wrong way" cues even though the puck is on the route line.
     * Feeding the segment bearing here aligns the simulated compass
     * with the simulated motion direction.
     *
     * Host apps using real GPS should NOT call this — the sensor
     * pipeline (via [onMotion]) is the canonical source.
     */
    public fun simulateHeading(deg: Float) {
        var normalised = deg
        while (normalised < 0f) normalised += 360f
        while (normalised >= 360f) normalised -= 360f
        // Publish to BOTH heading flows. The host UI (banner / puck /
        // camera bearing) reads [headingDeg] which is normally driven
        // by the sensor-fused HeadingProvider; the guidance monitor
        // reads [compassHeadingDeg] for the wrong-way-heading check.
        // Without updating headingDeg too, a sim-driven ride leaves
        // the camera bearing stuck on whatever the real rotation-
        // vector sensor reports — the map never rotates with the
        // simulated movement direction.
        // Latch sim ownership so the sensor stream stops fighting us.
        headingFromSim = true
        _headingDeg.value = normalised
        _compassHeadingDeg.value = normalised
        guidance.onCompassHeading(normalised)
    }

    /**
     * Speak a localized sample cue so the rider (or QA) can audition
     * the current voice / locale / TTS engine combination without
     * starting a real ride. Used by the Settings screen's "Preview
     * voice" button. Bypasses the muted flag — preview implies intent.
     *
     * The phrase is hardcoded per supported locale because the natural
     * way to phrase "turn right on the square ahead" varies across
     * languages (and the server phrase block isn't reachable without
     * a real route). Falls back to English for unknown locales.
     */
    public fun previewVoice() {
        val phrase = previewPhraseFor(locale)
        // Temporarily un-mute for the duration of the preview, restore
        // after. The voice engine itself queues the utterance and the
        // `say` call returns immediately, so we toggle around the
        // request rather than around the actual playback.
        val wasEnabled = voice.voiceEnabled
        voice.voiceEnabled = true
        voice.say(phrase, tone = CueTone.Normal, id = "scoova-voice-preview")
        // Restore mute state for the next real cue. The utterance
        // already in flight will still finish.
        voice.voiceEnabled = wasEnabled
    }

    // ── Reroute lifecycle speech ─────────────────────────────────
    // The host's reroute flow (off-route trigger, persona change,
    // avoid-toggle change) calls these to surface state changes the
    // eyes-off rider would otherwise miss. Each method speaks the
    // server's v2 sentence when present, falls back to legacy
    // [tripScoovaState] keys if v1, and stays silent if no copy at
    // all (no Frankenstein composition — see class doc on the
    // server-owns-full-sentence boundary).

    /** Spoken when the host begins a reroute attempt. Eyes-off rider
     *  hears something like "Lost the route, searching." */
    public fun announceRerouteSearching() {
        val phrase = tripFullSentences.rerouteSearching
            ?: tripScoovaState?.get("rerouteSearching")
            ?: tripScoovaState?.get("recalculating")
        if (!phrase.isNullOrBlank()) saySpoken(phrase, CueTone.Calm)
    }

    /** Spoken when the host's reroute succeeds and a fresh route is
     *  loaded. The rider hears confirmation that nav resumes:
     *  "Route found, continue." */
    public fun announceRerouteFound() {
        val phrase = tripFullSentences.rerouteFound
            ?: tripScoovaState?.get("rerouteFound")
            ?: tripScoovaState?.get("rerouted")
        if (!phrase.isNullOrBlank()) saySpoken(phrase, CueTone.Cheerful)
    }

    /** Spoken when the host's reroute attempt fails (network down /
     *  server unreachable). The rider hears that retry is in
     *  progress so silence doesn't read as a crash. */
    public fun announceRerouteFailed() {
        val phrase = tripFullSentences.rerouteFailed
            ?: tripScoovaState?.get("rerouteFailed")
            ?: tripScoovaState?.get("offlineRoute")
        if (!phrase.isNullOrBlank()) saySpoken(phrase, CueTone.Urgent)
    }

    /**
     * Spoken when the rider drifts off-route while the device is
     * offline. A server reroute is impossible, so instead of the
     * confusing "searching…/still trying" pair we tell the rider the
     * one thing that actually helps: get back onto the route line
     * (which is still drawn — its corridor tiles were pre-fetched).
     *
     * Prefers the server's pre-rendered offline sentence if the last
     * route carried one; otherwise speaks a hardcoded locale-correct
     * full sentence (not composed — see the server-owns-sentences
     * boundary; a fixed standalone phrase is fine, stitching isn't).
     */
    public fun announceOffline() {
        val phrase = tripScoovaState?.get("offlineRoute")
            ?.takeIf { it.isNotBlank() }
            ?: offlinePhraseFor(locale)
        saySpoken(phrase, CueTone.Urgent)
    }

    private fun offlinePhraseFor(localeTag: String): String {
        return when (localeTag.substringBefore('-').lowercase()) {
            "ar" -> "أنت غير متصل بالإنترنت. عُد إلى المسار المرسوم."
            "es" -> "Estás sin conexión. Vuelve a la ruta marcada."
            "fr" -> "Vous êtes hors ligne. Revenez sur l'itinéraire tracé."
            "de" -> "Sie sind offline. Kehren Sie zur eingezeichneten Route zurück."
            "it" -> "Sei offline. Torna sul percorso tracciato."
            "pt" -> "Você está sem conexão. Volte para a rota traçada."
            "ja" -> "オフラインです。表示されているルートに戻ってください。"
            "zh" -> "您已离线。请返回已规划的路线。"
            "tr" -> "Çevrimdışısınız. Çizili rotaya geri dönün."
            else -> "You're offline. Head back to the route shown."
        }
    }

    private fun previewPhraseFor(localeTag: String): String {
        val base = localeTag.substringBefore('-').lowercase()
        return when (base) {
            "ar" -> "بعد مائة متر، انعطف يميناً عند ميدان التحرير"
            "es" -> "En cien metros, gira a la derecha en la plaza Tahrir"
            "fr" -> "Dans cent mètres, tournez à droite sur la place Tahrir"
            "de" -> "In hundert Metern rechts abbiegen am Tahrir-Platz"
            "it" -> "Tra cento metri, gira a destra in piazza Tahrir"
            "pt" -> "Em cem metros, vire à direita na praça Tahrir"
            "ja" -> "百メートル先、タハリール広場で右折します"
            "zh" -> "一百米后,在解放广场右转"
            "tr" -> "Yüz metre sonra Tahrir Meydanı'nda sağa dönün"
            else -> "In one hundred metres, turn right onto Tahrir Square"
        }
    }

    /** Adapter calls this once when the host SDK gives us the route. */
    public fun onRoute(maneuvers: List<ManeuverEvent>) {
        onRoute(maneuvers, isReroute = false, eyesOff = false)
    }

    /**
     * Adapter-facing overload that distinguishes initial routes from
     * mid-trip reroutes. iOS parity for `onRoute(maneuvers, isReroute,
     * eyesOff)` on ScoovaNavLayer.swift. When `isReroute == true` the
     * welcome cue is suppressed (rider doesn't want to hear "Let's
     * go" again three minutes into their trip), the eyes-off voice
     * mode flag is propagated to the cue grammar, and the throttle
     * timestamp updates so the next reroute respects the cooldown.
     */
    public fun onRoute(
        maneuvers: List<ManeuverEvent>,
        isReroute: Boolean,
        eyesOff: Boolean = false,
    ) {
        this.maneuvers = maneuvers
        // On reroute, lock the welcome hash to the current maneuver
        // list so the welcome can't refire. The hash-comparison block
        // below would otherwise reset welcomedRouteHash whenever the
        // new maneuver sequence differs from the last one — which is
        // the whole point of a reroute, so it would always fire the
        // welcome again without this guard.
        if (isReroute) {
            welcomedRouteHash = maneuvers.hashCode()
            lastWelcomedAtMs = System.currentTimeMillis()
        }
        // Only re-arm the welcome cue when the route is materially
        // different from the last one we welcomed. A re-rate that
        // produces the same maneuver sequence (off-route flap on a
        // long straight stretch, persona-switch back to the same
        // mode) used to replay "Let's go" every time — confusing
        // and noisy on the simulator where the welcome could echo
        // every couple of seconds. The hashCode of the maneuver
        // list is a cheap structural fingerprint.
        val newHash = maneuvers.hashCode()
        if (newHash != welcomedRouteHash) {
            welcomedRouteHash = 0
        }
        firstSpeechSpokenFor.clear()
        // Build the subtitle cue track — far/mid/near + confirm /
        // reaffirm / checkpoint, all pinned to route points (mirrors
        // iOS buildCueSchedule). This replaces the old threshold ladder.
        cueSchedule = CueSchedule.build(
            maneuvers,
            cueDefaultsFor(profile),
            keepGoing = tripScoovaState?.get("keepGoing"),
            pan = ::panFor,
        )
        cueFired.clear()
        // Fresh route — hand the heading back to the live sensor. A
        // simulate ride re-latches it via simulateHeading() on its
        // first tick; a real ride never calls that, so the sensor wins.
        headingFromSim = false
        // Fresh route — the rider has not arrived yet. (A reroute calls
        // onRoute again; if it ever did so after a genuine arrival this
        // re-arms the arrival cue for the new leg.)
        _arrived.value = false
        stoppedNearDestSinceMs = 0L
        this.eyesOff.setManeuvers(maneuvers)
        guidance.reset()
    }

    /**
     * Adapter sets the decoded polyline shape so [GuidanceMonitor] can
     * project the rider onto the line for drift / off-route / heading
     * checks. Call once per route, ideally right after [onRoute]. Also
     * pushes the routing profile so the monitor can pick mode-aware
     * drift / off-route thresholds — pedestrian on a sidewalk lives
     * 10–20 m off the routed centerline, the same distance a car
     * would correctly call "off route."
     */
    public fun setRouteShape(shape: List<DoubleArray>) {
        routeShape = shape
        guidance.setRoute(shape)
        guidance.setCosting(profile)
        // Notify the host so the UI can redraw the polyline after a
        // reroute — without this the rider's map stays on the OLD
        // shape after an auto-reroute lands. iOS' onRouteRefreshed
        // callback handles the same. Initial-route hosts can ignore
        // the call (they already drew the shape from startRoute's
        // return value); reroute hosts NEED it.
        runCatching { onRouteRefreshed?.invoke(shape) }
    }

    /**
     * Adapter sets the decoded per-route corridor block. Call once per
     * route, ideally right after [onRoute] / [setRouteShape]. Null is
     * the legacy-server path (no corridor → reasoner falls back to
     * lateral-distance heuristics). Mirrors iOS' `onCorridor`.
     */
    public fun onCorridor(corridor: Corridor?) {
        this.corridor = corridor
    }

    /** Adapter calls this on every host-SDK route-progress update (1-4 Hz). */
    public fun onProgress(p: ProgressEvent) {
        if (maneuvers.isEmpty()) return
        val now = System.currentTimeMillis()
        // Cache for silence-filler verbal cue — see handleGuidanceEvent.
        latestMetersRemaining = p.metersRemaining
        latestMetersToUpcomingManeuver = p.metersToUpcomingManeuver.toInt()
        // Keep the latest fix for HeadingProvider's declination correction.
        lastKnownLocation = android.location.Location("scoova").apply {
            latitude = p.latitude
            longitude = p.longitude
        }

        // ── Heading source selection (pocket-aware) ──────────────────
        // Map puck / follow-camera heading normally tracks the
        // rotation-vector sensor. But when the phone is pocketed the
        // sensor reports the POCKET's orientation, not the rider's —
        // the map would spin with the rider's gait. While pocketed AND
        // moving fast enough for GPS course to be reliable, drive the
        // heading from the GPS bearing instead. At a standstill we
        // freeze on the last good course (GPS bearing is noise below
        // ~5 km/h). When the phone leaves the pocket the sensor takes
        // over again immediately.
        // Heading source. While the rider is MOVING, the GPS course is
        // the direction of travel — exactly what a heading-up map wants,
        // and far more reliable than the phone compass: no magnetic
        // interference, no dependence on how the phone is held or
        // mounted. It needs NO correction — `Location.bearing` is
        // already true-north, clockwise, 0–360. The rotation-vector
        // compass is used only at a near-standstill, where the GPS
        // course is just positional noise.
        val gpsBearing = p.bearingDeg
        val spd = p.speedMps ?: 0f
        if (gpsBearing != null && spd >= GPS_HEADING_MIN_SPEED_MPS) {
            headingFromGps = true
            _headingDeg.value = ((gpsBearing % 360f) + 360f) % 360f
        } else if (headingFromGps && spd < GPS_HEADING_MIN_SPEED_MPS) {
            // Slowed to a stop — hand the heading back to the compass.
            headingFromGps = false
        }

        val routeHash = maneuvers.hashCode()
        // Welcome cue gates:
        //   1. We haven't welcomed this exact route hash yet, AND
        //   2. It's been > 60 s since the last welcome of any route
        //      (backup throttle — if the route hash logic ever fails,
        //      this still prevents an audible repeat).
        val notYetWelcomed = welcomedRouteHash != routeHash
        val backupGapElapsed = now - lastWelcomedAtMs >= 60_000L
        if (notYetWelcomed && backupGapElapsed) {
            welcomedRouteHash = routeHash
            lastWelcomedAtMs = now
            // Welcome cue resolution, in priority order (server owns
            // composition; client just picks the highest-tier text):
            //   1. v2 [tripFullSentences.welcomeFull] — the full
            //      eyes-off briefing: "Let's go. Your trip is 4 km,
            //      12 min, with 5 turns. First turn in 400 m."
            //      Server pre-renders this with exact totals so the
            //      Arabic decimal-pronunciation problem doesn't arise.
            //   2. Legacy `tripScoovaState["welcome"]` — short "Let's
            //      go" sentence with no stats. Used by adapters that
            //      haven't migrated to v2.
            //   3. Hard-coded [welcomeText] — last resort for non-
            //      Scoova adapters.
            val v2 = tripFullSentences.welcomeFull
            val legacy = tripScoovaState?.get("welcome")
            val phrase = when {
                !v2.isNullOrBlank() -> v2
                !legacy.isNullOrBlank() -> legacy
                else -> welcomeText(locale, p.metersRemaining / 1000.0)
            }
            saySpoken(phrase, CueTone.Calm)
        }
        // ── Guidance reasoner ────────────────────────────────────────
        // Produce the unit-of-reasoning the rest of this tick reads
        // against: rider's snapped way, on-route signal, upcoming
        // decision + ordinal/ambiguity context. When the corridor is
        // missing (legacy server) the reasoner degrades gracefully.
        _liveGuidance.value = GuidanceReasoner.reason(
            p = p,
            route = maneuvers,
            corridor = corridor,
            shape = routeShape,
        )

        val idx = p.upcomingManeuverIndex.coerceIn(0, maneuvers.lastIndex)
        val maneuver = maneuvers[idx]
        latestManeuverIndexForTelemetry = idx
        // Per-maneuver thresholds from the server (farMeters / midMeters /
        // nearMeters). Server pre-computes these from the road class
        // and profile so a 90 km/h highway fires Far 30 s out (= 750 m)
        // while a 4 km/h walking lane fires Far 30 s out (= 33 m). This
        // is the time-based scheduling the eyes-off plan called for:
        // the rider always gets ~30 s lead time regardless of how fast
        // they're moving along that segment. Without server values
        // we fall back to the static profile thresholds.
        val dist = p.metersToUpcomingManeuver

        // Final-arrive correction: while still > 30 m from the last
        // maneuver, the server's past-tense "You've arrived" bannerVerb
        // is wrong — swap in a present-tense "Continue to destination"
        // until the rider reaches the arrival zone.
        val isFinalApproach = idx >= maneuvers.lastIndex
        val bannerManeuver = if (isFinalApproach && dist > 30) {
            maneuver.copy(
                bannerVerb = arrivingText(locale),
                bannerAnchor = null,
            )
        } else maneuver

        // Banner binding — reads bannerVerb / bannerAnchor (server copy).
        _currentInstruction.value = DisplayCue(
            maneuver = bannerManeuver,
            metersToManeuver = dist,
            phase = phaseFor(maneuver, dist),
            text = maneuver.voiceTurnNow
                ?: CuePhrases.build(locale, maneuver, -1, thresholds),
        )

        // ── Cue track ────────────────────────────────────────────────
        // Speak the server's pinned cues like subtitles. Each cue fires
        // once, the moment the rider reaches its trigger — a fixed
        // distance for confirm / reaffirm / checkpoint, a speed-scaled
        // distance (constant seconds-out) for the far / mid / near
        // approach cues. At most one cue per tick; when several cross
        // together the nearest one supersedes the rest. The server
        // already renders the entire utterance in the rider's locale —
        // we speak it verbatim. (1:1 with the iOS engine.)
        cueSchedule[idx]?.let { points ->
            val fired = cueFired.getOrPut(idx) { mutableSetOf() }
            var toSpeak: CuePoint? = null
            var nearest = Double.MAX_VALUE
            points.forEachIndexed { ci, cue ->
                if (ci in fired) return@forEachIndexed
                val trigger = CueSchedule.effectiveTriggerMeters(cue, p.speedMps)
                if (dist <= trigger) {
                    fired.add(ci)
                    if (trigger <= nearest) {
                        nearest = trigger
                        toSpeak = cue
                    }
                }
            }
            toSpeak?.let { cue ->
                voice.markThresholdCrossed()    // last-cue-latency diagnostic
                // The urgent (near) cue asks the rider to act now — arm
                // yaw confirmation for onMotion().
                if (cue.tone == CueTone.Urgent) {
                    pendingTurnDirection = expectedTurnDir(maneuver.type)
                    pendingTurnFiredAtMs = System.currentTimeMillis()
                }
                firstSpeechSpokenFor.add(idx)
                // A reaffirm fired on a long quiet stretch carries the
                // live distance to the NEXT TURN — same number the
                // banner shows. Mismatched voice + banner numbers
                // ("812 m" banner + "1.4 km to destination" voice)
                // read as a bug to the rider.
                val phrase = when (cue.kind) {
                    CueKind.Reaffirm ->
                        appendDistanceToNextTurn(
                            cue.phrase, locale,
                            p.metersToUpcomingManeuver.toInt())
                    CueKind.Approach ->
                        // The server bakes a static lead distance into
                        // the FAR/MID cue ("In 300 meters after Starbucks,
                        // turn right..."). The SDK fires by TIME, so on
                        // a pedestrian that lands at ~42 m, not 300 m.
                        // Rewrite the embedded number to live so the
                        // voice matches the banner.
                        rewriteEmbeddedDistance(
                            cue.phrase, locale,
                            p.metersToUpcomingManeuver.toInt())
                    else -> cue.phrase
                }
                saySpoken(phrase, cue.tone, cue.pan)
            }
        }

        // ── Arrival ──────────────────────────────────────────────────
        // The trip ends one of two ways, whichever lands first:
        //   1. The rider rolls inside the arrival radius (30 m of route
        //      remaining).
        //   2. The rider goes still (< 2.5 km/h) for 6 s within the
        //      wider 70 m radius — a rider who parks at the kerb, or
        //      whose GPS settles a little short of the pin, still gets
        //      a clean arrival. WITHOUT this, the trip never concludes:
        //      guidance keeps running and the standstill heading-
        //      mismatch check false-fires an endless "wrong way, turn
        //      around" at a rider who has simply arrived.
        // Latched via [_arrived] so the cue speaks exactly once.
        val nearLastManeuver = idx >= maneuvers.lastIndex - 1
        if (!_arrived.value && nearLastManeuver) {
            val withinArriveRadius = p.metersRemaining < ARRIVE_RADIUS_M
            val parkedNearDest = p.metersRemaining < ARRIVE_STOP_RADIUS_M &&
                (p.speedMps ?: 0f) < ARRIVE_STOP_SPEED_MPS
            if (parkedNearDest) {
                if (stoppedNearDestSinceMs == 0L) stoppedNearDestSinceMs = now
            } else {
                stoppedNearDestSinceMs = 0L
            }
            val dwellSatisfied = stoppedNearDestSinceMs != 0L &&
                now - stoppedNearDestSinceMs >= ARRIVE_STOP_DWELL_MS
            if (withinArriveRadius || dwellSatisfied) {
                _arrived.value = true
                // Arrival cue resolution — prefer server-rendered
                // "وصلت" / "You've arrived" so copy stays in lockstep
                // with the banner; v2 → legacy → hardcoded fallback,
                // same order as the welcome cue above.
                val v2Arrive = tripFullSentences.arrivedFull
                val legacyArrive = tripScoovaState?.get("arrived")
                val phrase = when {
                    !v2Arrive.isNullOrBlank() -> v2Arrive
                    !legacyArrive.isNullOrBlank() -> legacyArrive
                    else -> arrivedText(locale)
                }
                saySpoken(phrase, CueTone.Cheerful)
            }
        }

        // ── Continuous closed-loop guidance ──────────────────────────
        // Drift / off-route / heading-mismatch / speed / silence /
        // almost-there. The monitor runs its own timers; we just play
        // whatever it tells us to. Phrases come from the server-side
        // `trip.scoova.state` block — copy stays in lockstep across SDKs.
        //
        // Suppressed once the rider is at the destination — arrived, or
        // inside the arrival zone slowing to a stop. Closed-loop
        // guidance is for the journey, not the doorstep: a rider
        // stopping, dismounting, or turning the phone to pocket it
        // would otherwise trip the standstill heading-mismatch check
        // and hear "wrong way, turn around" on a loop after they have
        // already arrived.
        val atDestination = _arrived.value ||
            (nearLastManeuver && p.metersRemaining < ARRIVE_STOP_RADIUS_M)
        if (!atDestination) {
            for (event in guidance.onProgress(p)) {
                handleGuidanceEvent(event, maneuver)
            }
        }

        // Post-turn confirm + long-stretch reaffirm are now pinned cues
        // in `cueSchedule` (built in onRoute) — fired by the cue track
        // above, exactly as on iOS. EyesOffGuide is no longer driven
        // from here; the schedule owns confirm / reaffirm / checkpoint.
    }

    /** Helper: voice.say + tick guidance.markSpoke so the silence
     *  timer never fires right after we already spoke. Also forwards
     *  a [CueEvent] to anyone listening on [cueEvents] — the iOS-parity
     *  telemetry path that [CueTelemetrySender] subscribes to. */
    private fun saySpoken(
        text: String,
        tone: CueTone = CueTone.Normal,
        spatialPan: Float = 0f,
    ) {
        if (text.isBlank()) return
        voice.say(text, tone, spatialPan)
        guidance.markSpoke()
        _cueEvents.tryEmit(
            CueEvent(
                tsMs = System.currentTimeMillis(),
                text = text,
                tone = tone.name,
                locale = locale,
                maneuverIndex = if (maneuvers.isNotEmpty())
                    latestManeuverIndexForTelemetry else null,
                metersToManeuver = if (latestMetersToUpcomingManeuver > 0)
                    latestMetersToUpcomingManeuver else null,
            )
        )
    }
    private var latestManeuverIndexForTelemetry: Int = 0

    /** Map a GuidanceEvent to its server phrase + cue tone, then play. */
    private fun handleGuidanceEvent(event: GuidanceEvent, maneuver: ManeuverEvent) {
        // keepGoing → fill the silence with a VERBAL "still on X"
        // confirmation. Picks the richest source available — the
        // upcoming maneuver's per-maneuver reaffirm first (it names the
        // road + heading), then the trip-level keepGoing, then `good`.
        // Distance-to-destination is appended for a real progress
        // check. Eyes-off riders flagged the prior chime-only design as
        // "the app died" — words restore confidence.
        if (event is GuidanceEvent.KeepGoing) {
            val reaffirm = maneuver.voiceReaffirm
            val trip = tripScoovaState
            val tripKeep = trip?.get("keepGoing")
            val tripGood = trip?.get("good")
            val base = listOfNotNull(reaffirm, tripKeep, tripGood)
                .firstOrNull { it.isNotBlank() }
            if (base != null) {
                // Distance-to-next-turn so the voice matches the banner.
                // Falls back to total remaining when there's no next
                // turn (very short trip / arrival imminent) so the
                // rider isn't told "0 metres to the next turn."
                val metersForClause = if (latestMetersToUpcomingManeuver > 0)
                    latestMetersToUpcomingManeuver
                else latestMetersRemaining
                val phrase = appendDistanceToNextTurn(
                    base, locale, metersForClause)
                saySpoken(phrase, CueTone.Calm)
                _guidanceCues.tryEmit(GuidanceCue(
                    tsMs = System.currentTimeMillis(),
                    kind = "keepGoing",
                    text = phrase,
                    toneName = CueTone.Calm.name,
                ))
            }
            // No phrase to say → stay quiet (no chime fallback on
            // Android; the eyes-off rider would rather hear nothing
            // than a click). Falls back to the prior 40 s timer
            // automatically: GuidanceMonitor will fire again next
            // window when something to say is available.
            return
        }
        // Gate: don't say "destination just ahead" while there's still
        // a TURN between rider and destination. metresRemaining alone
        // doesn't see the geometry — on routes whose last turn IS at
        // the destination the rider can be 60 m from "arrival" and
        // still need to turn first. See iOS twin gate.
        if (event is GuidanceEvent.AlmostThere && maneuver.type != ManeuverType.Arrive) {
            return
        }
        val state = tripScoovaState ?: return  // no server phrases → silent
        val (key, tone) = when (event) {
            is GuidanceEvent.KeepGoing       -> "keepGoing"       to CueTone.Calm
            is GuidanceEvent.DriftLeft       -> "driftLeft"       to CueTone.Normal
            is GuidanceEvent.DriftRight      -> "driftRight"      to CueTone.Normal
            is GuidanceEvent.SlowDown        -> "slowDown"        to CueTone.Urgent
            is GuidanceEvent.WrongWayHeading -> "wrongWay"        to CueTone.Alert
            is GuidanceEvent.OffRoute        -> "wrongWay"        to CueTone.Alert
            is GuidanceEvent.AlmostThere     -> almostThereKeyFor(maneuver) to CueTone.Calm
        }

        // iOS-parity reroute trigger: OffRoute, WrongWayHeading and
        // (future) MissedTurn each request a fresh route fetch from
        // the host's routing adapter via [onRerouteNeeded]. Throttled
        // by [rerouteFetchThrottleMs] (default 8s) so a sustained
        // off-route condition fires a single reroute, not 30 of them
        // at GPS tick rate. If the throttle WILL block the fetch,
        // we also suppress the "Wrong way / Recalculating" cue — the
        // rider hearing "Recalculating" while nothing actually does
        // is a perceived lie (iOS comment: Bug H fix).
        val triggersReroute = event is GuidanceEvent.OffRoute ||
            event is GuidanceEvent.WrongWayHeading
        if (triggersReroute) {
            val nowMs = System.currentTimeMillis()
            val willBeThrottled = lastRerouteLandedAtMs > 0 &&
                (nowMs - lastRerouteLandedAtMs) < rerouteFetchThrottleMs
            if (!willBeThrottled) {
                rerouteRequestedAtMs = nowMs
                runCatching { onRerouteNeeded?.invoke() }
            } else {
                android.util.Log.d("ScoovaGuidance", "reroute throttled — cue suppressed")
                return  // don't speak the cue either
            }
        }
        val phrase = state[key]?.takeIf { it.isNotBlank() } ?: return
        android.util.Log.d("ScoovaGuidance", "fired event=$event → key=$key tone=$tone phrase=$phrase")
        saySpoken(phrase, tone)
        // Surface for the banner-overlay UI so the rider SEES what they
        // hear. Same buffer-drop policy as spokenEvents.
        _guidanceCues.tryEmit(
            GuidanceCue(
                tsMs = System.currentTimeMillis(),
                kind = key,
                text = phrase,
                toneName = tone.name,
            )
        )
    }

    /** Pick the sided variant of "almost there" if the destination
     *  maneuver type encodes a side. */
    private fun almostThereKeyFor(maneuver: ManeuverEvent): String {
        // Final maneuver in the list — peek at it for side info.
        val last = maneuvers.lastOrNull() ?: maneuver
        return when (last.type) {
            // Adapter maps Valhalla 5 → ManeuverType.Arrive but loses
            // the right/left distinction. Until that's surfaced as a
            // sub-type we use the neutral "almostThere" key.
            else -> "almostThere"
        }
    }

    /**
     * Adapter / host app calls this on every IMU sensor tick (~10–50 Hz).
     * The fusion engine smooths heading, detects completed turns, and
     * flags crash / hard-brake events. Outputs land on:
     *   * [compassHeadingDeg] — for the puck / banner heading indicator
     *   * [crashEvents]       — for emergency-contact overlays
     *
     * When a detected turn matches the most recent Near-phase cue's
     * direction (left ↔ right), the layer fires a "Good" confirmation
     * cue from the trip-level scoova state ("تمام كده" / "Good, you're
     * on track"). The rider hears reassurance the moment they execute,
     * without waiting for GPS to catch up — the missing post-turn
     * affirmation flagged by the SDK audits.
     */
    public fun onMotion(frame: MotionFrame) {
        val state = motionFusion.process(frame)
        state.headingDeg?.let {
            _compassHeadingDeg.value = it
            guidance.onCompassHeading(it)
        }
        state.crash?.let { _crashEvents.tryEmit(it) }
        // Update pocket-vs-handheld classifier on every IMU tick.
        // Downstream consumers (OffRouteMonitor, EyesOffGuide gate
        // weighting) read its StateFlow.
        pocketDetector.onMotion(frame)
        // Forward the pocket state to GuidanceMonitor so its standstill
        // wrong-way heading check stays suppressed while the phone is
        // stowed — a pocketed phone's compass is the pocket's facing,
        // not the rider's.
        guidance.onPocketState(pocketDetector.phoneInPocket.value)
        // Yaw-side of [EyesOffGuide]'s post-turn confirmation gate.
        // The previous yaw-only "Good" cue used to fire here based on
        // a single sensor channel — that produced false-positives
        // when the rider yawed for a different reason (lane change,
        // checking traffic). EyesOffGuide now requires BOTH yaw +
        // GPS-on-route to agree before speaking, so we just forward
        // the signal and let it gate.
        state.turnDeg?.let { turnDeg ->
            eyesOff.onYawTurn(turnDeg, frame.tsMs)
            // Keep the legacy pendingTurnDirection clear for the
            // diagnostic latency probe (still consumed elsewhere).
            pendingTurnDirection = null
        }
    }

    /** Maps a maneuver type to the direction the rider is expected to
     *  yaw. Returns null for non-turning maneuvers (depart / arrive /
     *  continue) — those don't produce a yaw signature. */
    private fun expectedTurnDir(type: ManeuverType): TurnDir? = when (type) {
        ManeuverType.Left, ManeuverType.SharpLeft, ManeuverType.SlightLeft,
        ManeuverType.RampLeft, ManeuverType.ExitLeft, ManeuverType.StayLeft,
        ManeuverType.RoundaboutEnter -> TurnDir.Left  // best guess for roundabout
        ManeuverType.Right, ManeuverType.SharpRight, ManeuverType.SlightRight,
        ManeuverType.RampRight, ManeuverType.ExitRight, ManeuverType.StayRight -> TurnDir.Right
        ManeuverType.Uturn -> TurnDir.Left  // U-turns are full-180 either way; we don't enforce side
        else -> null
    }

    /** Trip-level state phrases set by the adapter from the server's
     *  `trip.scoova.state` block. Adapter calls [setTripState] on route
     *  load. We read `good`, `wrongWay`, `keepGoing`, etc. from here. */
    private var tripScoovaState: Map<String, String>? = null
    /** Adapter pushes the parsed `trip.scoova.state` here on route load. */
    public fun setTripState(state: Map<String, String>?) {
        tripScoovaState = state
    }

    /**
     * Trip-level full sentences (v2). Server pre-renders the welcome
     * briefing, reroute speech, and arrival speech as complete locale-
     * correct utterances. The client picks which one to speak based
     * on lifecycle events ([EyesOffGuide] orchestrates this), but does
     * not compose them. Null fields fall back to legacy tripScoovaState
     * phrases or hardcoded copy.
     */
    public data class TripFullSentences(
        public val welcomeFull: String? = null,
        public val rerouteSearching: String? = null,
        public val rerouteFound: String? = null,
        public val rerouteFailed: String? = null,
        public val arrivedFull: String? = null,
        /**
         * Final-approach cue. Spoken by [EyesOffGuide] when the rider
         * is within ~30 m of the destination — between the last Near
         * cue and the arrival cue. Pre-rendered with the destination
         * side ("on your right" / "on your left") when the routing
         * server's arrive maneuver carries side info.
         */
        public val almostThereFull: String? = null,
    )

    private var tripFullSentences: TripFullSentences = TripFullSentences()
    /** Exposed so adapters and EyesOffGuide can read the server's
     *  pre-rendered trip-level phrases without going through Maps. */
    public fun tripFullSentences(): TripFullSentences = tripFullSentences

    /**
     * Adapter pushes the v2 trip-level full sentences here on route
     * load. Safe to call with all-nulls if the server response was
     * legacy-schema; the legacy [setTripState] still drives the
     * fallback path.
     */
    public fun setTripFullSentences(sentences: TripFullSentences) {
        tripFullSentences = sentences
        // Push the almost-there text into EyesOffGuide so it can fire
        // the final-approach cue without needing to peek at this
        // class's private state.
        eyesOff.setAlmostThereText(sentences.almostThereFull)
    }

    public data class DisplayCue(
        val maneuver: ManeuverEvent,
        val metersToManeuver: Double,
        val phase: CuePhrases.Phase,
        /**
         * Spoken text — what the voice engine will say. With the server
         * `scoova` block this is just the chosen phrase for the current
         * threshold; without it, falls back to `CuePhrases` output.
         *
         * The BANNER does NOT read this — it renders
         * `maneuver.bannerVerb` + `maneuver.bannerAnchor` instead.
         */
        val text: String,
    )

    /**
     * Crude seconds-to-maneuver estimator. Used to substitute `{secs}` in
     * the server's getReady template. We floor the speed at 5 m/s (~18
     * km/h cycling pace) so a stationary GPS fix doesn't produce
     * "in 9999 seconds" cues.
     */
    private fun estimateSecondsToManeuver(metersToManeuver: Double, speedMps: Float?): Int {
        val s = (speedMps?.toDouble() ?: 0.0).coerceAtLeast(5.0)
        return (metersToManeuver / s).toInt().coerceIn(1, 99)
    }

    /**
     * Build a sorted threshold array from the server's per-maneuver
     * far/mid/near distance hints. Returns null when ANY of the three
     * values is missing — partial hints are unsafe because the gaps
     * between tiers carry the Mid-phase classification (see [CuePhrases.pickPhase]).
     * When null, the caller falls back to the profile's static
     * thresholds.
     */
    /** Coarse far/mid/near phase for the banner, off the maneuver's
     *  own server lead distances. Mirrors the iOS `DisplayCue.phase`. */
    private fun phaseFor(m: ManeuverEvent, dist: Double): CuePhrases.Phase {
        val mid = m.midMeters?.toDouble() ?: 250.0
        val near = m.nearMeters?.toDouble() ?: 90.0
        return when {
            dist <= near -> CuePhrases.Phase.Near
            dist <= mid -> CuePhrases.Phase.Mid
            else -> CuePhrases.Phase.Far
        }
    }

    /** Fallback far/mid/near lead distances per profile — used only
     *  when a maneuver ships no server `farMeters`. Matches the iOS
     *  `Thresholds.cueOffsets`. */
    private fun cueDefaultsFor(profile: String): CueDefaults = when (profile) {
        "pedestrian" -> CueDefaults(70.0, 35.0, 14.0)
        "bicycle" -> CueDefaults(220.0, 110.0, 40.0)
        "scooter", "motor_scooter" -> CueDefaults(320.0, 160.0, 60.0)
        else -> CueDefaults(500.0, 250.0, 90.0)
    }

    private fun manuverThresholds(m: ManeuverEvent): IntArray? {
        val far = m.farMeters ?: return null
        val mid = m.midMeters ?: return null
        val near = m.nearMeters ?: return null
        if (far <= 0 || mid <= 0 || near <= 0) return null
        // Sorted ascending so the tracker fires from largest to
        // smallest as the rider closes in.
        return intArrayOf(near, mid, far).also { it.sort() }
    }

    public companion object {
        public fun builder(context: Context): Builder = Builder(context)

        /**
         * Minimum speed (m/s) at which the GPS course is trusted as
         * the heading source for a pocketed phone. ~1.4 m/s ≈ 5 km/h
         * — below this the FusedLocation bearing is dominated by
         * positional noise. 1.5 gives a small margin.
         */
        private const val GPS_HEADING_MIN_SPEED_MPS: Float = 1.5f

        // ── Arrival detection ────────────────────────────────────────
        /** Route metres remaining at which the rider counts as arrived. */
        private const val ARRIVE_RADIUS_M: Int = 30
        /**
         * A rider who parks at the kerb, or whose GPS settles a little
         * short of the pin, may never roll inside [ARRIVE_RADIUS_M].
         * Within this wider radius, holding still for
         * [ARRIVE_STOP_DWELL_MS] also counts as arrival — so the trip
         * ends cleanly instead of leaving guidance running (which would
         * false-fire an endless "wrong way, turn around").
         */
        private const val ARRIVE_STOP_RADIUS_M: Int = 70
        private const val ARRIVE_STOP_SPEED_MPS: Float = 0.7f
        private const val ARRIVE_STOP_DWELL_MS: Long = 6_000L
    }

    public class Builder(private val ctx: Context) {
        private var apiKey: String = ""
        private var locale: String = "en-US"
        private var profile: String = "auto"
        private var landmarks: Boolean = true
        private var brandedFreeTrial: Boolean = true
        private var spatialAudio: Boolean = true

        public fun apiKey(value: String): Builder = apply { apiKey = value }
        public fun locale(value: String): Builder = apply { locale = value }
        public fun profile(value: String): Builder = apply { profile = value }
        public fun landmarks(value: Boolean): Builder = apply { landmarks = value }
        public fun brandedFreeTrial(value: Boolean): Builder = apply { brandedFreeTrial = value }
        /** Pan left-turn cues to the left ear and right-turn to the right. Default on. */
        public fun spatialAudio(value: Boolean): Builder = apply { spatialAudio = value }

        public fun build(): ScoovaNavLayer {
            require(apiKey.isNotBlank()) { "apiKey is required" }
            return ScoovaNavLayer(
                ctx.applicationContext, apiKey, locale, profile,
                landmarks, brandedFreeTrial, spatialAudio,
            )
        }
    }
}

/** Maps a maneuver type to a stereo pan in [-1, +1]. */
private fun panFor(type: ManeuverType): Float = when {
    type.isLeftSide  -> -0.8f
    type.isRightSide -> +0.8f
    else             -> 0f
}

private fun welcomeText(lang: String, distanceKm: Double): String {
    val km = "%.1f".format(distanceKm)
    return when {
        lang.startsWith("ar-EG") -> "ابدأ الرحلة. حوالي $km كيلومتر."
        lang.startsWith("ar")    -> "ابدأ الرحلة. حوالي $km كيلومتر."
        lang.startsWith("fr")    -> "C'est parti. Environ $km kilomètres."
        lang.startsWith("de")    -> "Los geht's. Etwa $km Kilometer."
        lang.startsWith("es")    -> "Vamos. Unos $km kilómetros."
        lang.startsWith("tr")    -> "Başlıyoruz. Yaklaşık $km kilometre."
        else                     -> "Let's go. About $km kilometers."
    }
}

private fun arrivedText(lang: String): String = when {
    lang.startsWith("ar") -> "وصلت لوجهتك."
    lang.startsWith("fr") -> "Vous êtes arrivé."
    lang.startsWith("de") -> "Sie haben Ihr Ziel erreicht."
    lang.startsWith("es") -> "Has llegado."
    lang.startsWith("tr") -> "Hedefe ulaştınız."
    else                  -> "You have arrived."
}

/** Banner copy shown while the rider is still APPROACHING the final
 *  destination — distinct from the past-tense "arrived" cue which
 *  only fires when they're within ~30 m. Prevents the misleading
 *  "You've arrived — 156 m" sighting that was happening when the
 *  server's arrival-maneuver bannerVerb leaked into the banner
 *  before the rider actually arrived. */
private fun arrivingText(lang: String): String = when {
    lang.startsWith("ar") -> "كمل لحد الوجهة"
    lang.startsWith("fr") -> "Continuez jusqu'à destination"
    lang.startsWith("de") -> "Weiter bis zum Ziel"
    lang.startsWith("es") -> "Continúa hasta el destino"
    lang.startsWith("tr") -> "Hedefe devam edin"
    else                  -> "Continue to destination"
}

/**
 * Direction of an expected turn. Used by [EyesOffGuide]'s confirmation
 * gate and by [ScoovaNavLayer]'s yaw-detection arm. Kept at top level
 * (internal) so the two collaborators in :core can both reference it.
 */
internal enum class TurnDir { Left, Right }
