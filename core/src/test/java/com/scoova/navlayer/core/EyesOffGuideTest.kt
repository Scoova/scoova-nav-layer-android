package com.scoova.navlayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EyesOffGuideTest {

    private val spoken = mutableListOf<Pair<String, CueTone>>()
    private val capture: (String, CueTone) -> Unit = { text, tone ->
        spoken.add(text to tone)
    }

    private fun fixture(
        type: ManeuverType = ManeuverType.Left,
        confirm: String? = "Good. You're on X Street.",
        recover: String? = "Looks like you missed the turn.",
        reaffirm: String? = "Still on X Street, 200 m to go.",
        segmentLengthMeters: Double = 1000.0,
    ) = ManeuverEvent(
        index = 0,
        total = 2,
        type = type,
        rawInstruction = null,
        latitude = 30.0,
        longitude = 31.0,
        segmentLengthMeters = segmentLengthMeters,
        voiceConfirm = confirm,
        voiceRecover = recover,
        voiceReaffirm = reaffirm,
    )

    @Test
    fun confirm_firesWhenBothSensorsAgree() {
        spoken.clear()
        val guide = EyesOffGuide(capture)
        guide.setManeuvers(listOf(fixture()))
        guide.armConfirmation(0, nowMs = 1_000)
        guide.onYawTurn(60f, nowMs = 1_500)   // left turn signature
        guide.onProgress(currentUpcomingManeuverIdx = 1, nowMs = 1_500)
        assertEquals(1, spoken.size)
        assertEquals("Good. You're on X Street.", spoken[0].first)
        assertEquals(CueTone.Cheerful, spoken[0].second)
    }

    @Test
    fun confirm_doesNotFireOnYawAlone() {
        spoken.clear()
        val guide = EyesOffGuide(capture)
        guide.setManeuvers(listOf(fixture()))
        guide.armConfirmation(0, nowMs = 1_000)
        guide.onYawTurn(60f, nowMs = 1_500)   // yaw OK, no GPS confirmation
        assertTrue("silence > wrong reassurance", spoken.isEmpty())
    }

    @Test
    fun confirm_doesNotFireOnGpsAlone_forLateralTurn() {
        spoken.clear()
        val guide = EyesOffGuide(capture)
        guide.setManeuvers(listOf(fixture()))
        guide.armConfirmation(0, nowMs = 1_000)
        guide.onProgress(currentUpcomingManeuverIdx = 1, nowMs = 1_500)
        // GPS confirms but yaw hasn't. Must stay silent.
        assertTrue("lateral turn needs both", spoken.isEmpty())
    }

    @Test
    fun roundabout_acceptsGpsOnlyConfirmation() {
        spoken.clear()
        val guide = EyesOffGuide(capture)
        guide.setManeuvers(listOf(fixture(type = ManeuverType.RoundaboutEnter)))
        guide.armConfirmation(0, nowMs = 1_000)
        guide.onProgress(currentUpcomingManeuverIdx = 1, nowMs = 1_500)
        // Roundabouts: GPS alone is enough (yaw is misleading on a
        // sustained 270° rotation).
        assertEquals(1, spoken.size)
        assertEquals("Good. You're on X Street.", spoken[0].first)
    }

    @Test
    fun uturn_acceptsGpsOnlyConfirmation() {
        spoken.clear()
        val guide = EyesOffGuide(capture)
        guide.setManeuvers(listOf(fixture(type = ManeuverType.Uturn)))
        guide.armConfirmation(0, nowMs = 1_000)
        guide.onProgress(currentUpcomingManeuverIdx = 1, nowMs = 1_500)
        assertEquals(1, spoken.size)
    }

    @Test
    fun recover_firesAfterWindowExpiresWithoutGpsConfirm() {
        spoken.clear()
        val guide = EyesOffGuide(capture, confirmWindowMs = 1_000L)
        guide.setManeuvers(listOf(fixture()))
        guide.armConfirmation(0, nowMs = 1_000)
        // Just after window expires (no yaw, no GPS progress).
        guide.onProgress(currentUpcomingManeuverIdx = 0, nowMs = 2_500)
        assertEquals(1, spoken.size)
        assertEquals("Looks like you missed the turn.", spoken[0].first)
        assertEquals(CueTone.Urgent, spoken[0].second)
    }

    @Test
    fun recover_doesNotFireWhenGpsAlreadyConfirmedTurn() {
        spoken.clear()
        val guide = EyesOffGuide(capture, confirmWindowMs = 1_000L)
        guide.setManeuvers(listOf(fixture()))
        guide.armConfirmation(0, nowMs = 1_000)
        // GPS confirms early, yaw never does. Roundabout rule applies
        // — actually no, this is a Left maneuver, so GPS alone
        // doesn't trigger speech, but it ALSO shouldn't trigger
        // recover when the window expires because the rider did
        // make the turn.
        guide.onProgress(currentUpcomingManeuverIdx = 1, nowMs = 1_200)
        // Window expires. GPS confirmed → silent (the rider made
        // the turn, our yaw detector just missed it).
        guide.onProgress(currentUpcomingManeuverIdx = 1, nowMs = 2_500)
        assertTrue("yaw-missed turn with GPS confirmation: stay silent", spoken.isEmpty())
    }

    @Test
    fun reaffirm_firesAfterIntervalOnLongSegment() {
        spoken.clear()
        val guide = EyesOffGuide(
            onSpeak = capture,
            reaffirmIntervalMeters = 100.0,
            reaffirmMinSegmentMeters = 500.0,
        )
        // Segment is 1000 m long.
        guide.setManeuvers(listOf(fixture(segmentLengthMeters = 1000.0)))
        // Enter segment — first call resets baseline.
        guide.onSegmentProgress(0, metersFromManeuver = 950.0)
        // Move 150 m forward in the segment (now 800 m from maneuver).
        guide.onSegmentProgress(0, metersFromManeuver = 800.0)
        assertEquals(1, spoken.size)
        assertEquals("Still on X Street, 200 m to go.", spoken[0].first)
    }

    @Test
    fun reaffirm_doesNotFireOnShortSegment() {
        spoken.clear()
        val guide = EyesOffGuide(
            onSpeak = capture,
            reaffirmIntervalMeters = 100.0,
            reaffirmMinSegmentMeters = 500.0,
        )
        // Segment shorter than the floor.
        guide.setManeuvers(listOf(fixture(segmentLengthMeters = 300.0)))
        guide.onSegmentProgress(0, metersFromManeuver = 250.0)
        guide.onSegmentProgress(0, metersFromManeuver = 100.0)
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun confirm_silentWhenServerHasNoConfirmText() {
        spoken.clear()
        val guide = EyesOffGuide(capture)
        guide.setManeuvers(listOf(fixture(confirm = null)))
        guide.armConfirmation(0, nowMs = 1_000)
        guide.onYawTurn(60f, nowMs = 1_500)
        guide.onProgress(currentUpcomingManeuverIdx = 1, nowMs = 1_500)
        // Server didn't ship voiceConfirm → silent. We do NOT
        // synthesise. See "silence > Frankenstein composition".
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun checkpoint_firesOnceWhenRiderCrossesOffset() {
        spoken.clear()
        val guide = EyesOffGuide(capture)
        // Isolate checkpoint by suppressing reaffirm text — otherwise
        // both fire on the same long segment and we can't tell them
        // apart in a single-spoken-count assertion.
        val m = fixture(segmentLengthMeters = 1000.0, reaffirm = null).copy(
            voiceCheckpoint = "You're passing Town Hall on your right.",
            checkpointOffsetMeters = 500,
        )
        guide.setManeuvers(listOf(m))
        guide.onSegmentProgress(0, metersFromManeuver = 950.0)
        assertTrue(spoken.isEmpty())
        guide.onSegmentProgress(0, metersFromManeuver = 400.0)
        assertEquals(1, spoken.size)
        assertEquals("You're passing Town Hall on your right.", spoken[0].first)
        guide.onSegmentProgress(0, metersFromManeuver = 200.0)
        assertEquals(1, spoken.size)
    }

    @Test
    fun checkpoint_skippedWhenWithin50MetersOfManeuver() {
        spoken.clear()
        val guide = EyesOffGuide(capture)
        val m = fixture(segmentLengthMeters = 1000.0).copy(
            voiceCheckpoint = "You're passing X.",
            checkpointOffsetMeters = 500,
        )
        guide.setManeuvers(listOf(m))
        // Rider is 30m from maneuver — too close, suppress.
        guide.onSegmentProgress(0, metersFromManeuver = 30.0)
        assertTrue("checkpoint must not stack on top of imminent Mid/Near cue", spoken.isEmpty())
    }

    @Test
    fun checkpoint_silentWhenServerHasNoText() {
        spoken.clear()
        val guide = EyesOffGuide(capture)
        // Server didn't ship voiceCheckpoint → silent.
        val m = fixture(segmentLengthMeters = 1000.0).copy(
            voiceCheckpoint = null,
            checkpointOffsetMeters = 500,
        )
        guide.setManeuvers(listOf(m))
        guide.onSegmentProgress(0, metersFromManeuver = 400.0)
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun almostThere_firesOnceWhenWithin30mOfFinalManeuver() {
        spoken.clear()
        val guide = EyesOffGuide(capture)
        val penultimate = fixture(segmentLengthMeters = 400.0)
        val arrive = ManeuverEvent(
            index = 1, total = 2,
            type = ManeuverType.Arrive,
            rawInstruction = null,
            latitude = 30.0, longitude = 31.0,
            segmentLengthMeters = 0.0,
        )
        guide.setManeuvers(listOf(penultimate, arrive))
        guide.setAlmostThereText("In 30 meters, your destination is on your right.")
        // Rider is 100m from arrive — no fire.
        guide.onSegmentProgress(1, metersFromManeuver = 100.0)
        assertTrue(spoken.isEmpty())
        // Rider closes to 20m — fire.
        guide.onSegmentProgress(1, metersFromManeuver = 20.0)
        assertEquals(1, spoken.size)
        assertEquals(
            "In 30 meters, your destination is on your right.",
            spoken[0].first,
        )
        assertEquals(CueTone.Cheerful, spoken[0].second)
        // Closer still — must NOT re-fire.
        guide.onSegmentProgress(1, metersFromManeuver = 5.0)
        assertEquals(1, spoken.size)
    }

    @Test
    fun almostThere_silentWhenAdapterDidNotSetText() {
        spoken.clear()
        val guide = EyesOffGuide(capture)
        val penultimate = fixture(segmentLengthMeters = 400.0)
        val arrive = ManeuverEvent(
            index = 1, total = 2, type = ManeuverType.Arrive,
            rawInstruction = null, latitude = 30.0, longitude = 31.0,
            segmentLengthMeters = 0.0,
        )
        guide.setManeuvers(listOf(penultimate, arrive))
        // No setAlmostThereText call — should stay silent.
        guide.onSegmentProgress(1, metersFromManeuver = 20.0)
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun setManeuvers_resetsCheckpointAndAlmostThereFlags() {
        spoken.clear()
        val guide = EyesOffGuide(capture)
        val m = fixture(segmentLengthMeters = 1000.0).copy(
            voiceCheckpoint = "checkpoint A",
            checkpointOffsetMeters = 500,
        )
        guide.setManeuvers(listOf(m))
        guide.onSegmentProgress(0, metersFromManeuver = 400.0)
        assertEquals(1, spoken.size)
        // New route. Same checkpoint text — should fire again because
        // setManeuvers clears the fired set.
        spoken.clear()
        guide.setManeuvers(listOf(m))
        guide.onSegmentProgress(0, metersFromManeuver = 400.0)
        assertEquals(1, spoken.size)
    }
}
