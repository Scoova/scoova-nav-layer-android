package com.scoova.navlayer.core

import kotlin.math.abs

/**
 * Eyes-off navigation orchestrator.
 *
 * **Why this is its own class.** The user-experience target is a
 * rider with the phone in a pocket, AirPods in, no map visible. For
 * that audience the bare cue stream ("turn left … turn right … turn
 * left") is not enough; they need three additional behaviours layered
 * on top of the threshold-based cue firing:
 *
 *   1. **Post-turn confirmation.** Within a few seconds of executing
 *      a turn, the rider needs to hear EITHER "Good. You're on X
 *      Street. Continue Y meters." OR "Looks like you missed the
 *      turn. Recalculating." — never silence (anxiety), never the
 *      wrong one (broken trust).
 *
 *   2. **Reaffirmation on long segments.** A 1 km straight section is
 *      a void. The rider with phone-in-pocket has no idea where they
 *      are along it. Every ~300 m of continuous progress on segments
 *      > 500 m, speak a short status: "Still on X, 400 m to go, then
 *      turn right."
 *
 *   3. **Confidence gating.** Sensors disagree all the time. Yaw says
 *      yes, GPS says no? Stay silent. GPS confirms but no yaw blip?
 *      The rider may have walked the turn slowly — accept GPS alone
 *      after a longer delay. Saying "good" while the rider is
 *      heading the wrong way destroys all trust in the system; we
 *      privilege silence over false reassurance.
 *
 * **Architecture.** ScoovaNavLayer owns the cue pipeline and the
 * audio engine; EyesOffGuide owns the *eyes-off-specific* policy
 * decisions and emits texts to speak via a callback. It does NOT
 * directly own the TTS engine — that stays with VoiceEngine through
 * ScoovaNavLayer's saySpoken — so a host that wants to disable
 * eyes-off mode can just not wire the callback and the rest of the
 * system carries on.
 *
 * **Boundary with the server.** Every speakable string this class
 * emits comes from the route response's [ManeuverEvent.voiceConfirm],
 * [voiceRecover], or [voiceReaffirm] field — i.e. server-rendered
 * full sentences. We never compose. If the server didn't ship a
 * sentence for the case at hand, this class stays silent rather than
 * synthesising one (silence > wrong copy; see class doc).
 */
public class EyesOffGuide(
    private val onSpeak: (text: String, tone: CueTone) -> Unit,
    /** Window for both sensors to agree post-cue, in ms. After this
     *  expires we either fire `recover` (if GPS shows no progress)
     *  or stay silent (if GPS shows the rider did progress, meaning
     *  they made the turn but our yaw detector missed it). */
    private val confirmWindowMs: Long = 7_000L,
    /** Below this segment length we don't bother with reaffirmation
     *  cues — there isn't enough straight road for them to land
     *  before the next maneuver fires. */
    private val reaffirmMinSegmentMeters: Double = 500.0,
    /** Speak a reaffirmation every this many metres of continuous
     *  progress within a long segment. */
    private val reaffirmIntervalMeters: Double = 300.0,
) {

    /**
     * Per-armed-maneuver confirmation state. Created when the Near
     * cue fires for a turning maneuver, retired when either sensor
     * confirms + speech fires, or when the window expires.
     *
     * [yawRequired] = false for roundabouts and U-turns — those have
     * a yaw signature (sustained 270°+ rotation, or 180°) the
     * sharp-turn-detector inside motion fusion misclassifies. Trust
     * GPS alone for those. For lateral turns we require BOTH yaw +
     * GPS to enforce "silence > wrong reassurance".
     */
    private data class Gate(
        val maneuverIndex: Int,
        val expectedDir: TurnDir,
        val firedAtMs: Long,
        val yawRequired: Boolean,
        var yawConfirmed: Boolean = false,
        var gpsConfirmed: Boolean = false,
        var spoken: Boolean = false,
    )

    private var activeGate: Gate? = null

    private var maneuvers: List<ManeuverEvent> = emptyList()

    /** Reaffirmation bookkeeping — index of the segment we're currently
     *  reaffirming in, and the in-segment progress (meters) at which we
     *  last spoke. */
    private var reaffirmIdx: Int = -1
    private var lastReaffirmedProgressM: Double = 0.0

    /** Per-maneuver flag — has the mid-segment checkpoint cue already
     *  fired for the segment leading into this maneuver. Single-fire
     *  per maneuver because the server emits one anchor at ~50 % of
     *  the segment; repeating it after the rider passes it sounds
     *  drunk. */
    private val checkpointFiredFor: MutableSet<Int> = mutableSetOf()

    /** Final-approach pre-arrival cue text (from trip.almostThereFull)
     *  + once-per-route fired flag. Spoken when the rider closes to
     *  within [almostThereDistanceMeters] of the last (arrive)
     *  maneuver. */
    private var almostThereText: String? = null
    private var almostThereFired: Boolean = false
    private val almostThereDistanceMeters: Double = 30.0

    /**
     * Adapter sets the per-route maneuver list. Clears all gates +
     * reaffirmation state — a new route is a clean slate.
     */
    public fun setManeuvers(maneuvers: List<ManeuverEvent>) {
        this.maneuvers = maneuvers
        activeGate = null
        reaffirmIdx = -1
        lastReaffirmedProgressM = 0.0
        checkpointFiredFor.clear()
        almostThereFired = false
    }

    /**
     * Adapter pushes the route's trip-level [com.scoova.navlayer.core.ScoovaNavLayer.TripFullSentences.almostThereFull]
     * here on route load. EyesOffGuide stashes it and fires it when
     * the rider closes to within ~30 m of the final maneuver.
     */
    public fun setAlmostThereText(text: String?) {
        this.almostThereText = text?.takeIf { it.isNotBlank() }
    }

    /**
     * Arm the confirmation gate for [maneuverIndex] — called when
     * ScoovaNavLayer fires the Near-phase cue. Only meaningful for
     * lateral turns (left/right/uturn). Replaces any previous gate
     * silently; a rider crossing two close turns lands the second
     * cue before the first window closes, and the second turn is
     * the relevant one to confirm.
     */
    public fun armConfirmation(maneuverIndex: Int, nowMs: Long = System.currentTimeMillis()) {
        val m = maneuvers.getOrNull(maneuverIndex) ?: return
        val dir = expectedTurnDir(m.type) ?: return
        // Roundabouts produce a sustained 270°+ rotation; the
        // sharp-turn detector inside motion fusion only fires for
        // discrete >30° events and misclassifies the gradual yaw of
        // a roundabout traversal. U-turns are 180° back-and-forth
        // which the same detector also struggles with. In both
        // cases, GPS-on-route projection is the more reliable
        // signal, so we don't require yaw confirmation for them.
        val yawRequired = !m.type.isRoundabout && !m.type.isUturn
        activeGate = Gate(
            maneuverIndex = maneuverIndex,
            expectedDir = dir,
            firedAtMs = nowMs,
            yawRequired = yawRequired,
        )
    }

    /**
     * IMU-derived turn signature (signed degrees, positive = left).
     * Called from ScoovaNavLayer.onMotion when sensor fusion detects
     * a turn. Marks the active gate's yaw side as confirmed if the
     * direction matches and the magnitude is meaningful (> 30°).
     */
    public fun onYawTurn(turnDeg: Float, nowMs: Long = System.currentTimeMillis()) {
        val gate = activeGate ?: return
        if (checkWindow(gate, nowMs)) return  // window expired → recover fired inside
        val actual = if (turnDeg > 0) TurnDir.Left else TurnDir.Right
        if (actual == gate.expectedDir && abs(turnDeg) > 30f) {
            gate.yawConfirmed = true
            tryFireConfirm(gate)
        }
    }

    /**
     * GPS-derived progress signal. Called from ScoovaNavLayer.onProgress
     * with the current upcoming maneuver index. When that index has
     * advanced past the gate's maneuverIndex, GPS confirms the rider
     * crossed the turn anchor.
     */
    public fun onProgress(currentUpcomingManeuverIdx: Int, nowMs: Long = System.currentTimeMillis()) {
        val gate = activeGate ?: return
        if (checkWindow(gate, nowMs)) return
        if (currentUpcomingManeuverIdx > gate.maneuverIndex) {
            gate.gpsConfirmed = true
            tryFireConfirm(gate)
        }
    }

    /**
     * Reaffirmation tick. Called from ScoovaNavLayer.onProgress on
     * every host-SDK progress update. Tracks how far into the current
     * segment the rider is; speaks the reaffirm phrase every
     * [reaffirmIntervalMeters] of continuous progress, but only on
     * segments longer than [reaffirmMinSegmentMeters].
     */
    public fun onSegmentProgress(currentManeuverIdx: Int, metersFromManeuver: Double) {
        val m = maneuvers.getOrNull(currentManeuverIdx) ?: return
        // metersFromManeuver = distance to the upcoming maneuver. Convert
        // to "metres travelled in this segment" by subtracting from the
        // segment's total length. Server-supplied [segmentLengthMeters]
        // is the distance OF this maneuver's segment (i.e. from the
        // previous maneuver to this one).
        val progressInSegment = (m.segmentLengthMeters - metersFromManeuver).coerceAtLeast(0.0)

        // ── Almost-there final approach ─────────────────────────────
        // When the rider is within [almostThereDistanceMeters] of the
        // LAST maneuver (the arrive), speak the server's pre-rendered
        // almostThereFull text. One-shot per route — `almostThereFired`
        // resets in [setManeuvers].
        if (!almostThereFired
            && currentManeuverIdx >= maneuvers.lastIndex
            && metersFromManeuver in 0.0..almostThereDistanceMeters
        ) {
            almostThereText?.let {
                almostThereFired = true
                onSpeak(it, CueTone.Cheerful)
            }
        }

        // ── Mid-segment checkpoint cue ──────────────────────────────
        // Server pre-rendered a "you're passing X" cue at the ~50 %
        // point of long segments. Fire when the rider has travelled
        // [checkpointOffsetMeters] into the current segment — but
        // only once per maneuver (the offset is anchored to a single
        // POI; repeating it after the rider passes the spot sounds
        // drunk).
        val cpText = m.voiceCheckpoint
        val cpOffset = m.checkpointOffsetMeters
        if (
            cpText != null && cpOffset != null
            && currentManeuverIdx !in checkpointFiredFor
            && progressInSegment >= cpOffset.toDouble()
            // Don't fire if we're already very close to the next
            // maneuver — the Mid / Near cue is about to speak and a
            // checkpoint on top of it stacks audio.
            && metersFromManeuver > 50.0
        ) {
            checkpointFiredFor.add(currentManeuverIdx)
            onSpeak(cpText, CueTone.Calm)
        }

        // ── Reaffirmation cadence ────────────────────────────────────
        if (m.segmentLengthMeters < reaffirmMinSegmentMeters) return
        if (currentManeuverIdx != reaffirmIdx) {
            reaffirmIdx = currentManeuverIdx
            lastReaffirmedProgressM = progressInSegment
            return
        }
        if (progressInSegment - lastReaffirmedProgressM < reaffirmIntervalMeters) return
        if (metersFromManeuver < reaffirmIntervalMeters) return
        lastReaffirmedProgressM = progressInSegment
        val phrase = m.voiceReaffirm ?: return  // no server text → silent
        onSpeak(phrase, CueTone.Calm)
    }

    /**
     * Check if the gate's confirmation window has expired. If so,
     * speak the recover phrase (when warranted) and clear. Returns
     * true if we returned to caller via expiry path.
     */
    private fun checkWindow(gate: Gate, nowMs: Long): Boolean {
        if (nowMs - gate.firedAtMs <= confirmWindowMs) return false
        // Window expired.
        if (!gate.spoken && !gate.gpsConfirmed) {
            // Sensors didn't agree, AND GPS hasn't progressed past
            // the turn. Speak recover — server's pre-rendered
            // "Looks like you missed the turn" sentence. No fallback
            // composition; silence beats wrong copy.
            val m = maneuvers.getOrNull(gate.maneuverIndex)
            val phrase = m?.voiceRecover
            if (!phrase.isNullOrBlank()) {
                gate.spoken = true
                onSpeak(phrase, CueTone.Urgent)
            }
        }
        // Either we spoke recover, or GPS confirmed (rider did make the
        // turn, our yaw detector just missed it — silent is correct).
        activeGate = null
        return true
    }

    /**
     * Fire the confirm phrase iff both sensors agree AND we haven't
     * already spoken. "Silence > wrong reassurance" is enforced by
     * requiring BOTH yaw AND GPS confirmation before speaking. If
     * GPS confirms alone and the window expires, we stay silent
     * (the rider made the turn, our confirmation channel just
     * lacks evidence — saying nothing is correct).
     */
    private fun tryFireConfirm(gate: Gate) {
        if (gate.spoken) return
        if (gate.yawRequired && !gate.yawConfirmed) return
        if (!gate.gpsConfirmed) return
        val m = maneuvers.getOrNull(gate.maneuverIndex) ?: return
        val phrase = m.voiceConfirm ?: return  // no server text → silent
        gate.spoken = true
        onSpeak(phrase, CueTone.Cheerful)
        activeGate = null
    }

    private fun expectedTurnDir(type: ManeuverType): TurnDir? = when (type) {
        ManeuverType.Left, ManeuverType.SharpLeft, ManeuverType.SlightLeft,
        ManeuverType.RampLeft, ManeuverType.ExitLeft, ManeuverType.StayLeft,
        ManeuverType.RoundaboutEnter -> TurnDir.Left
        ManeuverType.Right, ManeuverType.SharpRight, ManeuverType.SlightRight,
        ManeuverType.RampRight, ManeuverType.ExitRight, ManeuverType.StayRight -> TurnDir.Right
        ManeuverType.Uturn -> TurnDir.Left
        else -> null
    }
}
