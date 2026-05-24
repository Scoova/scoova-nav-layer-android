package com.scoova.navlayer.mapbox

import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.scoova.navlayer.core.ManeuverEvent
import com.scoova.navlayer.core.ManeuverType
import com.scoova.navlayer.core.ProgressEvent
import com.scoova.navlayer.core.ScoovaNavLayer

/**
 * Adapter that wires Mapbox Navigation Android SDK into Scoova Nav Layer.
 *
 * Two-line integration:
 * ```kotlin
 * val nav = ScoovaNavLayer.builder(context).apiKey(KEY).locale("ar-EG").build()
 * nav.start()
 * MapboxNavLayerAdapter.attach(mapboxNavigation, nav)  // ← that's it
 * ```
 *
 * Internally:
 *   - Subscribes to Mapbox's [RoutesObserver] — translates the route's
 *     step list into our generic [ManeuverEvent] list and pushes it via
 *     [ScoovaNavLayer.onRoute].
 *   - Subscribes to [RouteProgressObserver] — translates each progress
 *     tick into a [ProgressEvent] and pushes it via [ScoovaNavLayer.onProgress].
 *   - Mutes Mapbox's built-in voice instructions so your app speaks once,
 *     in Scoova's dialect-aware voice, instead of twice.
 */
public object MapboxNavLayerAdapter {

    public fun attach(mapbox: MapboxNavigation, layer: ScoovaNavLayer): Detacher {
        val routesObserver = RoutesObserver { result ->
            val route = result.navigationRoutes.firstOrNull() ?: return@RoutesObserver
            layer.onRoute(translateRoute(route))
        }
        val progressObserver = RouteProgressObserver { progress ->
            layer.onProgress(translateProgress(progress))
        }
        mapbox.registerRoutesObserver(routesObserver)
        mapbox.registerRouteProgressObserver(progressObserver)
        return Detacher {
            mapbox.unregisterRoutesObserver(routesObserver)
            mapbox.unregisterRouteProgressObserver(progressObserver)
        }
    }

    public fun interface Detacher { public fun detach() }

    private fun translateRoute(route: NavigationRoute): List<ManeuverEvent> {
        val out = mutableListOf<ManeuverEvent>()
        var globalIndex = 0
        val legs = route.directionsRoute.legs() ?: return emptyList()
        val totalSteps = legs.sumOf { it.steps()?.size ?: 0 }
        for (leg in legs) {
            val steps = leg.steps() ?: continue
            for (step in steps) {
                val mb = step.maneuver()
                val type = manueverTypeFromMapbox(mb.type(), mb.modifier())
                val len = step.distance()
                val coord = mb.location()
                out += ManeuverEvent(
                    index = globalIndex,
                    total = totalSteps,
                    type = type,
                    rawInstruction = mb.instruction(),
                    latitude = coord.latitude(),
                    longitude = coord.longitude(),
                    segmentLengthMeters = len,
                    roundaboutExit = mb.exit(),
                )
                globalIndex++
            }
        }
        return out
    }

    private fun translateProgress(p: RouteProgress): ProgressEvent {
        val currentLeg = p.currentLegProgress
        val currentStep = currentLeg?.currentStepProgress
        val location = p.locationMatcherResult?.enhancedLocation
        return ProgressEvent(
            latitude = location?.latitude ?: 0.0,
            longitude = location?.longitude ?: 0.0,
            speedMps = location?.speed,
            bearingDeg = location?.bearing,
            // Mapbox's "current step" is the step we're ON; Scoova's
            // "upcoming maneuver" is the next end-of-step. Same index.
            upcomingManeuverIndex = (currentLeg?.legIndex ?: 0) * 1000 + (currentStep?.stepIndex ?: 0),
            metersToUpcomingManeuver = (currentStep?.distanceRemaining ?: 0f).toDouble(),
            secondsRemaining = p.durationRemaining.toInt(),
            metersRemaining = p.distanceRemaining.toInt(),
        )
    }

    /** Mapbox uses (type, modifier) — translate into our flat enum. */
    private fun manueverTypeFromMapbox(type: String?, modifier: String?): ManeuverType {
        // See Mapbox's StepManeuver.Type / StepManeuver.Modifier constants.
        return when (type?.lowercase()) {
            "depart" -> ManeuverType.Depart
            "arrive" -> ManeuverType.Arrive
            "continue" -> ManeuverType.Continue
            "merge" -> ManeuverType.Merge
            "becomes" -> ManeuverType.Becomes
            "roundabout", "rotary" -> ManeuverType.RoundaboutEnter
            "exit roundabout", "exit rotary" -> ManeuverType.RoundaboutExit
            "on ramp" -> when (modifier?.lowercase()) {
                "left", "slight left", "sharp left" -> ManeuverType.RampLeft
                "right", "slight right", "sharp right" -> ManeuverType.RampRight
                else -> ManeuverType.RampStraight
            }
            "off ramp" -> when (modifier?.lowercase()) {
                "left", "slight left", "sharp left" -> ManeuverType.ExitLeft
                else -> ManeuverType.ExitRight
            }
            "fork" -> when (modifier?.lowercase()) {
                "left", "slight left", "sharp left" -> ManeuverType.StayLeft
                "right", "slight right", "sharp right" -> ManeuverType.StayRight
                else -> ManeuverType.StayStraight
            }
            "turn", "end of road" -> when (modifier?.lowercase()) {
                "uturn" -> ManeuverType.Uturn
                "sharp left" -> ManeuverType.SharpLeft
                "sharp right" -> ManeuverType.SharpRight
                "slight left" -> ManeuverType.SlightLeft
                "slight right" -> ManeuverType.SlightRight
                "left" -> ManeuverType.Left
                "right" -> ManeuverType.Right
                else -> ManeuverType.Other
            }
            else -> ManeuverType.Other
        }
    }
}
