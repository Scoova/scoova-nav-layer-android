package com.scoova.navlayer.google

import com.scoova.navlayer.core.ManeuverEvent
import com.scoova.navlayer.core.ManeuverType
import com.scoova.navlayer.core.ProgressEvent
import com.scoova.navlayer.core.ScoovaNavLayer

/**
 * Helper for integrating with **Google Maps Navigation Android SDK**.
 *
 * We deliberately don't take a hard dependency on Google's Navigation SDK
 * — it's a paid product, the API has shifted between minor versions, and
 * the integrating app already brings its own license. Instead, this
 * helper offers a clean *push API* the integrator wires up against
 * whatever Google SDK version they have:
 *
 * ```kotlin
 * val nav = ScoovaNavLayer.builder(ctx).apiKey(KEY).locale("ar-EG").build()
 * nav.start()
 *
 * val gm = GoogleMapsNavLayerAdapter(nav)
 *
 * // 1. When Google fires onRouteChanged — push the new step list:
 * navigator.addRouteChangedListener {
 *     val segment = navigator.currentRouteSegment ?: return@addRouteChangedListener
 *     val steps = segment.stepInfoList.orEmpty()  // (your SDK's accessor)
 *     gm.pushRoute(steps.size) { stepIndex ->
 *         val s = steps[stepIndex]
 *         GoogleMapsNavLayerAdapter.Step(
 *             type        = mapManeuverEnum(s.maneuver.name),
 *             instruction = s.fullInstructionText,
 *             lat         = s.location.latitude,
 *             lon         = s.location.longitude,
 *             lengthM     = s.distanceFromPrevStepMeters.toDouble(),
 *             roundaboutExitNumber = s.roundaboutTurnNumber.takeIf { it > 0 },
 *         )
 *     }
 * }
 *
 * // 2. When Google fires onRemainingTimeOrDistanceChanged — push progress:
 * navigator.addRemainingTimeOrDistanceChangedListener {
 *     val td = navigator.currentTimeAndDistance
 *     val loc = currentLocation()  // your fused-location source
 *     gm.pushProgress(
 *         lat = loc.latitude, lon = loc.longitude,
 *         upcomingStepIndex = navigator.currentStepIndex,
 *         metersToUpcomingStep = navigator.metersToCurrentStep.toDouble(),
 *         secondsRemaining = td.seconds, metersRemaining = td.meters,
 *     )
 * }
 *
 * // 3. Mute Google's voice (do this once after Navigator is ready):
 * navigator.setAudioGuidance(Navigator.AudioGuidance.SILENT)
 * ```
 *
 * The 10-line wiring is something the integrator does once and never
 * touches again. We stay decoupled from Google SDK API drift.
 *
 * Use [mapManeuverFromGoogleEnumName] to translate Google's Maneuver enum
 * name to Scoova's [ManeuverType] — works against every Google Nav SDK
 * version 5+ because we match by enum value name, not by enum reference.
 */
public class GoogleMapsNavLayerAdapter(private val layer: ScoovaNavLayer) {

    /**
     * One step as Scoova understands it. Provided as a small data class
     * so the integrator's mapping lambda is type-safe.
     */
    public data class Step(
        val type: ManeuverType,
        val instruction: String?,
        val lat: Double,
        val lon: Double,
        val lengthM: Double,
        val roundaboutExitNumber: Int? = null,
    )

    /**
     * Push a new route to the layer. The lambda is called once per step
     * to translate Google's StepInfo into our [Step].
     */
    public fun pushRoute(stepCount: Int, mapStep: (Int) -> Step) {
        val maneuvers = (0 until stepCount).map { i ->
            val s = mapStep(i)
            ManeuverEvent(
                index = i,
                total = stepCount,
                type = s.type,
                rawInstruction = s.instruction,
                latitude = s.lat,
                longitude = s.lon,
                segmentLengthMeters = s.lengthM,
                roundaboutExit = s.roundaboutExitNumber,
            )
        }
        layer.onRoute(maneuvers)
    }

    public fun pushProgress(
        lat: Double,
        lon: Double,
        upcomingStepIndex: Int,
        metersToUpcomingStep: Double,
        secondsRemaining: Int,
        metersRemaining: Int,
        speedMps: Float? = null,
        bearingDeg: Float? = null,
    ) {
        layer.onProgress(
            ProgressEvent(
                latitude = lat,
                longitude = lon,
                speedMps = speedMps,
                bearingDeg = bearingDeg,
                upcomingManeuverIndex = upcomingStepIndex,
                metersToUpcomingManeuver = metersToUpcomingStep,
                secondsRemaining = secondsRemaining,
                metersRemaining = metersRemaining,
            ),
        )
    }

    public companion object {
        /**
         * Map Google's [com.google.android.libraries.navigation.StepInfo.Maneuver]
         * enum-name string into Scoova's [ManeuverType]. Pass it whatever
         * `googleStep.maneuver.name` returns. Survives SDK minor-version
         * upgrades because we match strings, not enum references.
         */
        public fun mapManeuverFromGoogleEnumName(name: String?): ManeuverType =
            when (name) {
                "DEPART" -> ManeuverType.Depart
                "ARRIVE" -> ManeuverType.Arrive
                "STRAIGHT" -> ManeuverType.Continue
                "NAME_CHANGE" -> ManeuverType.Becomes
                "TURN_LEFT" -> ManeuverType.Left
                "TURN_RIGHT" -> ManeuverType.Right
                "TURN_SLIGHT_LEFT" -> ManeuverType.SlightLeft
                "TURN_SLIGHT_RIGHT" -> ManeuverType.SlightRight
                "TURN_SHARP_LEFT" -> ManeuverType.SharpLeft
                "TURN_SHARP_RIGHT" -> ManeuverType.SharpRight
                "TURN_U_TURN_CLOCKWISE", "TURN_U_TURN_COUNTERCLOCKWISE" -> ManeuverType.Uturn
                "MERGE_LEFT", "MERGE_RIGHT", "MERGE_UNSPECIFIED" -> ManeuverType.Merge
                "FORK_LEFT" -> ManeuverType.StayLeft
                "FORK_RIGHT" -> ManeuverType.StayRight
                "RAMP_LEFT" -> ManeuverType.RampLeft
                "RAMP_RIGHT" -> ManeuverType.RampRight
                null -> ManeuverType.Other
                else -> when {
                    name.startsWith("ROUNDABOUT_EXIT") -> ManeuverType.RoundaboutExit
                    name.startsWith("ROUNDABOUT") -> ManeuverType.RoundaboutEnter
                    else -> ManeuverType.Other
                }
            }
    }
}
