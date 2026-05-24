# Scoova Nav Layer — Integration Samples

The Scoova Nav Layer drops on top of whatever map and routing stack you
already use. Below: four real integrations, copy-paste ready.

All snippets assume you've added the artifacts:

```kotlin
dependencies {
    implementation("com.scoova:nav-layer-core:1.0.0")
    implementation("com.scoova:nav-layer-ui:1.0.0")             // optional, drop-in Compose UI
    implementation("com.scoova:nav-layer-scoova-routing:1.0.0") // optional, Scoova routing adapter
    implementation("com.scoova:nav-layer-google-maps:1.0.0")    // optional, Google Maps adapter
}
```

---

## 1. The 5-line core

Used by every integration. Build the layer once, wire two callbacks.

```kotlin
val nav = ScoovaNavLayer.builder(context)
    .apiKey("sk_live_…")
    .locale("ar-EG")          // 7 dialects ship: en-US, ar-EG, ar, fr, de, es, tr
    .profile("bicycle")       // pedestrian, bicycle, scooter, motor_scooter, auto, truck
    .landmarks(true)          // "turn right at the mosque" instead of "turn right"
    .spatialAudio(true)       // left-turn cues in your left ear (default on)
    .build()
nav.start()

// Then your host SDK adapter pushes events:
//   nav.onRoute(maneuvers)            once when route is known
//   nav.onProgress(progressEvent)     1–4 Hz while driving / riding
```

---

## 2. Mapbox Navigation SDK

```kotlin
import com.mapbox.navigation.core.MapboxNavigation
import com.scoova.navlayer.mapbox.MapboxNavLayerAdapter

val mapboxNav: MapboxNavigation = /* your existing instance */
val nav = ScoovaNavLayer.builder(context)
    .apiKey("sk_live_…")
    .locale("en-US")
    .profile("bicycle")
    .build().also { it.start() }

// One-line attach. Mapbox's RoutesObserver + RouteProgressObserver are
// registered for you and translated into ManeuverEvent / ProgressEvent.
MapboxNavLayerAdapter.attach(mapboxNav, nav)

// Suppress Mapbox's built-in voice — Scoova owns audio now:
mapboxNav.setVoiceInstructionsPlayer(null)
```

Build-time requirement: the `:adapter-mapbox` module is token-gated. Set
`MAPBOX_DOWNLOADS_TOKEN` in `~/.gradle/gradle.properties` to pull it in.
Without the token, Gradle silently skips it.

---

## 3. Google Maps Navigation SDK

Decoupled by design — Google's StepInfo enum drifts between SDK minors, so
the adapter exposes a push API instead of a hard dep:

```kotlin
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.Navigator
import com.scoova.navlayer.google.GoogleMapsNavLayerAdapter

val nav = ScoovaNavLayer.builder(context).apiKey("sk_live_…").build()
nav.start()

val adapter = GoogleMapsNavLayerAdapter(nav)

NavigationApi.getNavigator(activity, object : NavigationApi.NavigatorListener {
    override fun onNavigatorReady(navigator: Navigator) {
        navigator.addRouteChangedListener {
            val stepCount = navigator.currentRouteSegment?.destinationWaypoint?.let { /* … */ } ?: 0
            // Map each step into Scoova ManeuverEvents:
            // adapter.pushRoute(stepCount) { idx, step -> ... }
        }
        navigator.addArrivalListener { /* nav.onArrive() */ }
        navigator.addRemainingTimeOrDistanceChangedListener(1000L, 50) {
            adapter.pushProgress(
                lat = navigator.locationProvider?.lastLocation?.latitude ?: 0.0,
                lon = navigator.locationProvider?.lastLocation?.longitude ?: 0.0,
                metersToManeuver = navigator.currentRouteSegment?.destinationWaypoint /* … */,
                metersRemaining = navigator.timeAndDistanceToNextDestination?.meters ?: 0,
                secondsRemaining = navigator.timeAndDistanceToNextDestination?.seconds ?: 0,
                upcomingManeuverIndex = 0,
            )
        }
    }
    override fun onError(errorCode: Int) {}
})
```

The push-API style means you control the StepInfo→ManeuverType mapping in
*your* app, not in our SDK. Google bumps `StepInfo` next month? You change
one mapping in your code, the SDK keeps working.

---

## 4. MapLibre + Scoova routing (the demo path)

The most common setup for open-source stacks. MapLibre draws the tiles,
Scoova plans the route, Scoova Nav Layer narrates.

```kotlin
import com.scoova.navlayer.scoova.LatLon
import com.scoova.navlayer.scoova.ScoovaRoutingAdapter

val nav = ScoovaNavLayer.builder(context)
    .apiKey("sk_live_…")
    .profile("bicycle")
    .locale("en-US")
    .build().also { it.start() }

val routing = ScoovaRoutingAdapter("sk_live_…", nav)

// Plan a route — returns the polyline as List<DoubleArray> ([lat, lon] pairs)
val shape: List<DoubleArray> = routing.startRoute(
    from = LatLon(30.0444, 31.2357),
    to   = LatLon(30.0568, 31.2434),
    profile  = "bicycle",
    language = "en-US",
    landmarks = true,
)

// Draw `shape` on your map however you like. Then drive location updates in:
routing.onLocation(lat, lon, speedMps, bearingDeg)
```

The `Scoova Ride` demo app (`demo/`) is a complete, working version of
this — six personas, ride history, settings, spatial audio test. Read it.

---

## 5. Drop-in Compose UI

If you're on Compose, the `ui` module gives you three drop-in components
that bind to `ScoovaNavLayer`:

```kotlin
import com.scoova.navlayer.ui.ScoovaManeuverBanner
import com.scoova.navlayer.ui.ScoovaRoutePreviewCard
import com.scoova.navlayer.ui.ScoovaHeadingPuck

@Composable
fun MyRideScreen(nav: ScoovaNavLayer, state: RideState) {
    val cue by nav.currentInstruction.collectAsStateWithLifecycle()
    val heading by nav.headingDeg.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        // Your map fills the screen — whatever renderer you use

        cue?.let {
            ScoovaManeuverBanner(
                cue = it,
                modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
            )
        }

        ScoovaHeadingPuck(
            headingDeg = heading,
            modifier = Modifier.align(Alignment.Center),
        )

        ScoovaRoutePreviewCard(
            destinationLabel = state.destinationLabel,
            distanceKm       = state.distanceKm,
            etaMinutes       = state.etaMin,
            profileLabel     = "Cycling",
            onStart          = { /* … */ },
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}
```

Theming hooks: every component takes a `Scoova*Style` object with the
brand defaults. White-label customers override colors there.
