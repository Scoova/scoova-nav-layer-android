package com.scoova.navlayer.core

import kotlin.math.max
import kotlin.math.min

/**
 * Port of iOS `NavigatorStateMachine.swift`. The navigator sitting next
 * to the rider. Owns the conceptual state of the trip — what is the
 * rider doing right now, and what (if anything) should I say about it.
 * Replaces the cue scheduler with a state machine that runs on every
 * progress tick.
 *
 * Six states. One active at a time. Transitions are driven by the
 * reasoner's [LiveGuidanceState] and the upcoming maneuver. Cues are
 * emitted ONLY at state transitions or at clear sub-events within a
 * state. The navigator doesn't talk on a schedule — he talks when
 * something needs saying.
 */
public class NavigatorStateMachine {

    public sealed class State {
        public object Idle : State()
        public object Cruising : State()
        public data class ApproachingTurn(val maneuverIndex: Int) : State()
        public data class OffRoute(val snappedWayName: String, val sinceMs: Long) : State()
        public data class WrongWay(val snappedWayName: String, val sinceMs: Long) : State()
        public data class StuckInTraffic(val sinceMs: Long) : State()
        public data class PastDestination(val sinceMs: Long) : State()
        public object Arrived : State()
    }

    /**
     * One emitted cue intent — the navigator's decision about WHAT
     * needs saying. [ScoovaNavLayer] maps the intent to a phrase (via
     * grammar, server vocab, or `voiceRecover` etc.) and speaks it.
     * The intent isn't text — it's the reason for speaking.
     */
    public sealed class CueIntent {
        public object Welcome : CueIntent()
        public data class Approach(val phase: CueGrammar.Phase, val maneuverIndex: Int) : CueIntent()
        public data class AmbiguityHeadsUp(val maneuverIndex: Int) : CueIntent()
        public object StuckInTraffic : CueIntent()
        public data class Checkpoint(val maneuverIndex: Int) : CueIntent()
        public data class Reaffirm(val maneuverIndex: Int) : CueIntent()
        public data class MissedTurn(val maneuverIndex: Int) : CueIntent()
        public data class OffRoute(val snappedName: String) : CueIntent()
        public data class WrongWay(val snappedName: String) : CueIntent()
        public object PastDestination : CueIntent()
        public object Arrived : CueIntent()
        public data class Confirm(val maneuverIndex: Int) : CueIntent()
    }

    public var state: State = State.Idle
        private set

    private val lastIntentAtMs: MutableMap<String, Long> = mutableMapOf()
    private val perIntentCooldownMs: Long = 15_000

    private val approachLeadSeconds: Map<CueGrammar.Phase, Double> = mapOf(
        CueGrammar.Phase.Far to 25.0,
        CueGrammar.Phase.Mid to 12.0,
        CueGrammar.Phase.Near to 3.0,
    )
    private val approachFallbackSpeedMps = 5.0
    private val approachMinSpeedMps = 0.5
    private val approachMaxSpeedMps = 28.0

    private val firedApproachPhases: MutableMap<Int, MutableSet<CueGrammar.Phase>> = mutableMapOf()
    private var lastNearFiredFor: Int? = null
    private val confirmedManeuvers: MutableSet<Int> = mutableSetOf()
    private val preTurnSnapWayId: MutableMap<Int, Long> = mutableMapOf()

    private var stationarySinceMs: Long = 0
    private val stationarySpeedMps: Float = 0.5f
    private val stationaryGraceMs: Long = 30_000
    private val stuckRepeatMs: Long = 60_000
    private var lastStuckIntentAtMs: Long = 0

    private var routeInstalledAtMs: Long = 0
    private val routeStartGraceMs: Long = 5_000

    private var lastAnyIntentAtMs: Long = 0
    private val reaffirmSilenceMs: Long = 75_000
    private val reaffirmMinDistanceToTurnM: Double = 200.0
    private val reaffirmedManeuvers: MutableMap<Int, Int> = mutableMapOf()

    /** Reset for a new route (initial start OR reroute). */
    public fun reset(isReroute: Boolean, nowMs: Long = System.currentTimeMillis()) {
        state = State.Cruising
        routeInstalledAtMs = nowMs
        firedApproachPhases.clear()
        lastNearFiredFor = null
        confirmedManeuvers.clear()
        preTurnSnapWayId.clear()
        reaffirmedManeuvers.clear()
        lastAnyIntentAtMs = nowMs
        stationarySinceMs = 0
        if (!isReroute) {
            lastIntentAtMs.clear()
        } else {
            // Preserve only `wrongWay` throttle — that one's about the
            // rider's physical direction of travel, unchanged by a
            // reroute. Stops the "Wrong way" double-fire across the
            // reroute boundary.
            val preservedWrongWay = lastIntentAtMs["wrongWay"]
            lastIntentAtMs.clear()
            if (preservedWrongWay != null) lastIntentAtMs["wrongWay"] = preservedWrongWay
            lastStuckIntentAtMs = 0
        }
    }

    /**
     * Tick. Given the latest reasoner state + the upcoming maneuver,
     * returns the cue intents (zero or more) the navigator wants to
     * speak this tick.
     */
    public fun tick(
        live: LiveGuidanceState,
        upcoming: ManeuverEvent?,
        metersRemainingToDestination: Int,
        arrivedLatched: Boolean,
        speedMps: Float?,
        riderLat: Double? = null,
        riderLon: Double? = null,
        riderBearingDeg: Float? = null,
        destLat: Double? = null,
        destLon: Double? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): List<CueIntent> {
        val intents = mutableListOf<CueIntent>()

        // ── Hard arrival latch — once flipped, stay there. ─────────
        if (arrivedLatched) {
            if (state !is State.Arrived) {
                state = State.Arrived
                if (shouldFire("arrived", nowMs)) intents += CueIntent.Arrived
            }
            return intents
        }

        // ── Past destination ───────────────────────────────────────
        val pastDest: Boolean = run {
            if (metersRemainingToDestination >= 50) return@run false
            if (riderLat == null || riderLon == null || destLat == null || destLon == null || riderBearingDeg == null) {
                if (live.snap != null) return@run !live.isOnRouteWay
                return@run live.alignment.lateralM > 80
            }
            val distToDestM = GeoMath.haversineMeters(riderLat, riderLon, destLat, destLon)
            if (distToDestM >= 80) return@run false
            val brgToDest = GeoMath.bearingDeg(riderLat, riderLon, destLat, destLon)
            angleDeltaAbs(riderBearingDeg, brgToDest.toFloat()) > 110
        }
        if (pastDest) {
            val sinceMs = stateStartedAtMs(stateSamplePastDestination = true) ?: nowMs
            state = State.PastDestination(sinceMs = sinceMs)
            if (shouldFire("pastDestination", nowMs)) intents += CueIntent.PastDestination
            return intents
        }

        val withinStartGrace = routeInstalledAtMs > 0 && (nowMs - routeInstalledAtMs) < routeStartGraceMs

        // ── Missed-turn (specific phrasing before generic off-route)
        run {
            if (withinStartGrace) return@run
            val last = lastNearFiredFor ?: return@run
            val m = upcoming ?: return@run
            if (m.index <= last) return@run
            if (confirmedManeuvers.contains(last)) return@run
            val preWid = preTurnSnapWayId[last] ?: return@run
            val curWid = live.snap?.wayId ?: return@run
            if (preWid != curWid) return@run
            confirmedManeuvers.add(last)
            state = State.OffRoute(snappedWayName = live.snap?.name.orEmpty(), sinceMs = nowMs)
            if (shouldFire("missedTurn-$last", nowMs)) {
                intents += CueIntent.MissedTurn(last)
            }
            return intents
        }

        val wrongWayJustFired = (nowMs - (lastIntentAtMs["wrongWay"] ?: 0)) < 10_000

        // Parallel-heading suppression — within 45 m of the line and
        // course matches the segment ⇒ neither off-route nor wrong-way.
        val isParallel = (live.alignment.courseMatchesSegment == true) && live.alignment.lateralM <= 45

        // ── Off-route ──────────────────────────────────────────────
        val isOff: Boolean = run {
            if (withinStartGrace) return@run false
            if (isParallel) return@run false
            if (live.snap == null) return@run live.alignment.lateralM > 60
            !live.isOnRouteWay
        }
        if (isOff) {
            val name = live.snap?.name.orEmpty()
            val stateChanging = state !is State.OffRoute
            if (stateChanging) state = State.OffRoute(snappedWayName = name, sinceMs = nowMs)
            if (shouldFire("offRoute", nowMs) && !wrongWayJustFired) {
                intents += CueIntent.OffRoute(snappedName = name)
            }
            return intents
        }

        // ── Wrong-way ───────────────────────────────────────────────
        val wrongWay: Boolean = run {
            val s = live.snap ?: return@run false
            if (!live.isOnRouteWay) return@run false
            val matches = s.courseMatchesForward ?: return@run false
            if (matches) return@run false
            if (isParallel) return@run false
            if (live.alignment.lateralM > 45) return@run false
            s.oneway || (live.alignment.courseMatchesSegment == false)
        }
        if (wrongWay) {
            val name = live.snap?.name.orEmpty()
            if (state !is State.WrongWay) {
                state = State.WrongWay(snappedWayName = name, sinceMs = nowMs)
                if (shouldFire("wrongWay", nowMs)) intents += CueIntent.WrongWay(snappedName = name)
            }
            return intents
        }

        // ── Post-turn: missed-turn vs confirmation ─────────────────
        run {
            val last = lastNearFiredFor ?: return@run
            val m = upcoming ?: return@run
            if (m.index <= last) return@run
            if (confirmedManeuvers.contains(last)) return@run
            confirmedManeuvers.add(last)
            val preWid = preTurnSnapWayId[last]
            val curWid = live.snap?.wayId
            val missedTurn = (preWid != null && curWid != null && preWid == curWid)
            if (missedTurn) {
                if (shouldFire("missedTurn-$last", nowMs)) intents += CueIntent.MissedTurn(last)
            } else if (shouldFire("confirm-$last", nowMs)) {
                intents += CueIntent.Confirm(last)
            }
        }

        // ── Stuck-in-traffic ───────────────────────────────────────
        if (live.isOnRouteWay && metersRemainingToDestination > 50 && speedMps != null && speedMps < stationarySpeedMps) {
            if (stationarySinceMs == 0L) stationarySinceMs = nowMs
            val stuckFor = nowMs - stationarySinceMs
            if (stuckFor > stationaryGraceMs) {
                val canSpeak = (nowMs - lastStuckIntentAtMs) > stuckRepeatMs
                if (canSpeak) {
                    state = State.StuckInTraffic(sinceMs = stationarySinceMs)
                    lastStuckIntentAtMs = nowMs
                    intents += CueIntent.StuckInTraffic
                    return intents
                }
            }
        } else {
            stationarySinceMs = 0
        }

        // ── Reaffirm on long quiet stretches ───────────────────────
        run {
            if (!live.isOnRouteWay) return@run
            val m = upcoming ?: return@run
            val dec = live.upcomingDecision ?: return@run
            if (dec.distance <= reaffirmMinDistanceToTurnM) return@run
            if (nowMs - lastAnyIntentAtMs <= reaffirmSilenceMs) return@run
            if (!coordIsAhead(m.latitude, m.longitude, riderLat, riderLon, riderBearingDeg, speedMps)) return@run
            val count = reaffirmedManeuvers[m.index] ?: 0
            if (count < 4 && shouldFire("reaffirm-${m.index}-$count", nowMs)) {
                reaffirmedManeuvers[m.index] = count + 1
                intents += CueIntent.Reaffirm(m.index)
            }
        }

        // ── Checkpoint narration ───────────────────────────────────
        run {
            val m = upcoming ?: return@run
            val offset = m.checkpointOffsetMeters ?: return@run
            val dec = live.upcomingDecision ?: return@run
            val cp = m.voiceCheckpoint
            if (cp.isNullOrEmpty()) return@run
            if (!coordIsAhead(m.latitude, m.longitude, riderLat, riderLon, riderBearingDeg, speedMps)) return@run
            val covered = m.segmentLengthMeters - dec.distance
            if (covered >= offset && shouldFire("checkpoint-${m.index}", nowMs)) {
                intents += CueIntent.Checkpoint(m.index)
            }
        }

        // ── Ambiguity heads-up ─────────────────────────────────────
        run {
            val m = upcoming ?: return@run
            val dec = live.upcomingDecision ?: return@run
            if (dec.distance <= 180 || dec.distance > 280) return@run
            val flagHit = live.ambiguityFlags.contains("multipleLeftsBeforeLeftTurn") ||
                live.ambiguityFlags.contains("multipleRightsBeforeRightTurn")
            if (!flagHit) return@run
            if (!coordIsAhead(m.latitude, m.longitude, riderLat, riderLon, riderBearingDeg, speedMps)) return@run
            if (shouldFire("ambiguity-${m.index}", nowMs)) intents += CueIntent.AmbiguityHeadsUp(m.index)
        }

        // ── Approaching a turn ─────────────────────────────────────
        run {
            val m = upcoming ?: return@run
            val dec = live.upcomingDecision ?: return@run
            if (m.type == ManeuverType.Depart || m.type == ManeuverType.Arrive) return@run
            val fired = firedApproachPhases.getOrPut(m.index) { mutableSetOf() }
            val dist = dec.distance

            val liveSpeed = max(
                approachMinSpeedMps,
                min(approachMaxSpeedMps, ((speedMps ?: approachFallbackSpeedMps.toFloat()).toDouble())),
            )
            val mAhead = coordIsAhead(
                m.latitude, m.longitude, riderLat, riderLon, riderBearingDeg, speedMps,
            )

            // Pre-mark earlier phases if we landed inside a later phase
            // already, to avoid rapid-fire repeats on tight reroutes.
            if (mAhead) {
                approachLeadSeconds[CueGrammar.Phase.Mid]?.let { midSec ->
                    if (dist <= midSec * liveSpeed) fired.add(CueGrammar.Phase.Far)
                }
                approachLeadSeconds[CueGrammar.Phase.Near]?.let { nearSec ->
                    if (dist <= nearSec * liveSpeed) {
                        fired.add(CueGrammar.Phase.Far)
                        fired.add(CueGrammar.Phase.Mid)
                    }
                }
            }

            for (phase in listOf(CueGrammar.Phase.Far, CueGrammar.Phase.Mid, CueGrammar.Phase.Near)) {
                val secondsOut = approachLeadSeconds[phase] ?: continue
                val lead = secondsOut * liveSpeed
                if (dist > lead || fired.contains(phase)) continue
                if (!mAhead) continue
                fired.add(phase)
                if (phase == CueGrammar.Phase.Mid) fired.add(CueGrammar.Phase.Far)
                if (phase == CueGrammar.Phase.Near) {
                    fired.add(CueGrammar.Phase.Far); fired.add(CueGrammar.Phase.Mid)
                }
                state = State.ApproachingTurn(maneuverIndex = m.index)
                if (shouldFire("approach-${phase.name}-${m.index}", nowMs)) {
                    intents += CueIntent.Approach(phase, m.index)
                    if (phase == CueGrammar.Phase.Near) {
                        lastNearFiredFor = m.index
                        live.snap?.wayId?.let { preTurnSnapWayId[m.index] = it }
                    }
                }
                break // at most one phase per tick
            }
        }

        // ── Cruising default ───────────────────────────────────────
        if (intents.isEmpty()) {
            when (state) {
                is State.ApproachingTurn,
                is State.OffRoute,
                is State.WrongWay,
                is State.PastDestination -> state = State.Cruising
                else -> Unit
            }
        }

        return intents
    }

    private fun shouldFire(key: String, nowMs: Long): Boolean {
        val last = lastIntentAtMs[key] ?: 0
        if (nowMs - last < perIntentCooldownMs) return false
        lastIntentAtMs[key] = nowMs
        lastAnyIntentAtMs = nowMs
        return true
    }

    /**
     * Geometric precondition shared by every cue that references a
     * specific place. Returns true when the coordinate is in front of
     * the rider, false when behind / abreast. Returns true
     * (pass-through) when bearing is unknown OR speed too low to trust
     * the heading.
     */
    internal fun coordIsAhead(
        lat: Double, lon: Double,
        riderLat: Double?, riderLon: Double?,
        riderBearingDeg: Float?,
        speedMps: Float?,
    ): Boolean {
        val rLat = riderLat ?: return true
        val rLon = riderLon ?: return true
        val brg = riderBearingDeg ?: return true
        if ((speedMps ?: 0f) < 1f) return true
        val toCoord = GeoMath.bearingDeg(rLat, rLon, lat, lon)
        return angleDeltaAbs(brg, toCoord.toFloat()) < 90
    }

    private fun stateStartedAtMs(stateSamplePastDestination: Boolean): Long? {
        val s = state
        if (stateSamplePastDestination && s is State.PastDestination) return s.sinceMs
        return null
    }
}
