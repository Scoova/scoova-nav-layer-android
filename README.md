# Scoova Nav Layer · Android

Drop-in navigation enhancement for any host map / routing SDK
(Mapbox, Google Maps Navigation, in-house engines).

You keep your routing. We give your app:

- 🗣 **Dialect-aware voice** (ar-EG, ar, en, fr, de, es, tr) with phase phrasing
  ("get ready" → "at the next street" → "now") instead of robotic MSA
- 🧭 **Maneuver banner** Compose component with our enriched model
- 📍 **Landmark hints** ("Turn right *at Tahrir Square*") via Scoova Pelias
- 🌤 **Weather along the route** (rain / fog / wind warnings tuned per profile)
- 🧭 **Sensor-driven heading puck** that works phone-in-pocket

## Install

`build.gradle.kts` (app):

```kotlin
dependencies {
    implementation("com.scoova:nav-layer-core:0.1.0")
    implementation("com.scoova:nav-layer-ui:0.1.0")        // optional Compose components
    implementation("com.scoova:nav-layer-mapbox:0.1.0")     // optional Mapbox adapter
}
```

## Four ways to integrate (pick one)

### A. Mapbox Navigation SDK (most common in mobility apps)

```kotlin
val nav = ScoovaNavLayer.builder(ctx).apiKey(KEY).locale("ar-EG").profile("scooter").build()
nav.start()
MapboxNavLayerAdapter.attach(mapboxNavigation, nav)   // ← that's it
```

### B. Scoova routing on top of any map (Google Maps SDK, MapLibre, Apple, etc.)

This is the strongest config — keep your existing map display, but get
Scoova's custom scooter routing + Egyptian voice + landmarks. No host
nav SDK needed.

```kotlin
val nav = ScoovaNavLayer.builder(ctx).apiKey(KEY).locale("ar-EG").profile("scooter").build()
nav.start()

val routing = ScoovaRoutingAdapter(KEY, nav)
val polyline = routing.startRoute(
    from = LatLon(30.0444, 31.2357),
    to   = LatLon(30.0626, 31.2497),
    profile = "scooter",
    language = "ar-EG",
    landmarks = true,
)
yourMap.drawRoute(polyline)   // works for any map renderer

// Pipe your location updates in (FusedLocationProvider, etc.):
locationFlow.collect { loc -> routing.onLocation(loc.latitude, loc.longitude, loc.speed, loc.bearing) }
```

### C. Google Maps Navigation SDK

```kotlin
val nav = ScoovaNavLayer.builder(ctx).apiKey(KEY).locale("ar-EG").profile("auto").build()
nav.start()
val gm = GoogleMapsNavLayerAdapter(nav)

navigator.addRouteChangedListener {
    val steps = navigator.currentRouteSegment.stepInfoList
    gm.pushRoute(steps.size) { i ->
        val s = steps[i]
        GoogleMapsNavLayerAdapter.Step(
            type = GoogleMapsNavLayerAdapter.mapManeuverFromGoogleEnumName(s.maneuver.name),
            instruction = s.fullInstructionText,
            lat = s.location.latitude, lon = s.location.longitude,
            lengthM = s.distanceFromPrevStepMeters.toDouble(),
        )
    }
}
navigator.addRemainingTimeOrDistanceChangedListener {
    val td = navigator.currentTimeAndDistance
    gm.pushProgress(/*lat,lon from FusedLocation*/, navigator.currentStepIndex,
        navigator.metersToCurrentStep.toDouble(), td.seconds, td.meters)
}
navigator.setAudioGuidance(Navigator.AudioGuidance.SILENT)   // mute Google's voice
```

### D. Any other host SDK (HERE, TomTom, OSRM, in-house)

Build a 30-line translator that pushes events to `nav.onRoute(...)` and
`nav.onProgress(...)`. The core SDK doesn't care where events come from.

See `core/ManeuverEvent.kt` for the model — it's intentionally tiny.

## Compose UI (optional)

Bind to `nav.currentInstruction` and `nav.headingDeg`:

```kotlin
val cue by nav.currentInstruction.collectAsStateWithLifecycle()
val heading by nav.headingDeg.collectAsStateWithLifecycle()

cue?.let { ScoovaManeuverBanner(it, modifier = Modifier.padding(16.dp)) }

ScoovaWeatherBanner(
    apiKey = "sk_live_…",
    locationFlow = yourLocationFlow,
    profile = "scooter",
)

ScoovaHeadingPuck(headingDeg = heading)
```

The banner has two preset themes (`Default` dark, `Light`) and accepts a
custom `ScoovaBannerStyle` for white-label tier customers.

## What this does NOT do

We deliberately stay out of these to keep the layer lightweight and
respectful of the host SDK:

- We don't change your **routing engine**. Mapbox / Google's route stays.
- We don't change your **map style** or tiles. The host SDK still draws the map.
- We don't change your **destination search**. Your geocoding stays.
- We don't fight the host's **turn-by-turn detection** — we listen, we don't drive.

If you also want Scoova-native routing (e.g. for our custom scooter costing or
Egyptian dialect destination search), see `https://scoo-va.info/products` for
the full Scoova Cloud platform.

## Pricing

| Tier              | Per app, per month  | Includes                                                      |
|-------------------|--------------------:|---------------------------------------------------------------|
| **Founder** (limited 25 spots) | **$9** locked for 12 months  | Everything below, locked at this rate forever                 |
| **Voice**         | $19                 | Voice cues only — dialect + phase phrasing                    |
| **Layer**         | $49                 | Voice + Maneuver UI + Landmark hints                          |
| **Layer Pro**     | $99                 | + Weather banner + heading puck + custom theming              |
| **Enterprise**    | from $499           | + white-label, on-prem option, SLA, dedicated support         |

Sign up + get an API key at <https://layer.scoo-va.info>.

## Modules

| Module                     | Purpose                                                       | Host dep brought by integrator |
|----------------------------|---------------------------------------------------------------|--------------------------------|
| `core/`                    | Headless SDK — cue generator, voice, sensors, API clients     | (none)                         |
| `ui/`                      | Drop-in Compose components (banner, weather, puck)            | Compose Material3              |
| `adapter-scoova-routing/`  | Use Scoova's routing API on top of any map renderer           | (none — calls Scoova directly) |
| `adapter-mapbox/`          | Mapbox Navigation Android observer translator                 | Mapbox Nav SDK (paid, token-gated) |
| `adapter-google-maps/`     | Google Maps Navigation SDK push-API helper                    | Google Nav SDK (Cloud-billed)  |
| `demo/`                    | Synthetic-trip Compose demo. Run on emulator, hear cues.      | core + ui                      |

The product boundary is: **`core/` knows nothing about any host SDK.** All
adapters do is translate the host SDK's event shape into [ManeuverEvent]
and [ProgressEvent]. Adding a new adapter (HERE, TomTom, in-house) is
~50 lines of translation code.

## Demo

```bash
./gradlew :demo:installDebug
adb shell am start -n com.scoova.navlayer.demo/.DemoActivity
```

Tap "Run synthetic Cairo trip" — you'll hear Egyptian dialect cues
fire at far/mid/near phases against a Cairo → Ramses fake route, while
the cyan heading puck rotates with your phone's compass.

## Building Mapbox adapter

Mapbox Navigation SDK lives behind their authenticated maven. Set:

```bash
# ~/.gradle/gradle.properties
MAPBOX_DOWNLOADS_TOKEN=sk.eyJ1Ijoi…
```

Then `:adapter-mapbox` is included automatically. Without the token it's
skipped, and `:core` + `:ui` build standalone.

## License

Apache-2.0.

## Versioning + roadmap

- **0.1** (now) — Core + UI + Mapbox adapter, Founders Beta
- **0.2** — Google Maps Navigation Android adapter
- **0.3** — iOS port (Mapbox + Apple MapKit adapters)
- **1.0** — Cloud TTS premium voices, white-label tier, audit logs

Issues: <https://github.com/scoova/nav-layer-android/issues>
Docs: <https://layer.scoo-va.info/docs>
