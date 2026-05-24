# Changelog

All notable changes to `scoova-nav-layer-android`.

## [1.0.0] — unreleased

First packaged release of the Eye-on-Road navigation layer for Android.

### Core (`core`)
- `ScoovaNavLayer` — coordinator: routing → cue schedule → guidance →
  voice engine, with audio session lifecycle and a sticky foreground
  notification path (`ScoovaNavNotification`)
- `CueSchedule` — server-pinned cue model: each maneuver carries
  `farMeters` / `midMeters` / `nearMeters` and the schedule fires
  each `CuePoint` exactly once as the rider passes its trigger
  distance (subtitle model — mirrors iOS `buildCueSchedule`)
- `GuidanceMonitor` — persona-aware lateral thresholds (pedestrian
  60 m vs auto 30 m) with parallel-bearing suppression (suppress
  drift / off-route when GPS bearing is within 35° of the route
  segment bearing); standstill compass-mismatch wrong-way check
- Speed-adaptive cue triggers — approach cues use seconds-to-turn,
  confirm / reaffirm / checkpoint pinned to distance
- Verbal reaffirm spaced for ~75 s between cues on long stretches;
  silence-filler speaks the upcoming maneuver's `voiceReaffirm`
  with `appendDistanceToNextTurn` (matches the banner)
- `EyesOffGuide` — checkpoint, confirm, recover, almost-there cues
  for the eyes-off mode
- `VoicePack` / `VoiceEngine` — bundled dialect voice clips + on-device
  TTS fallback
- `Motion` / `HeadingProvider` / `LocationSmoother` — IMU fusion +
  compass-corrected heading + GPS smoothing

### Adapters
- `adapter-scoova-routing` — Scoova Valhalla routing with the scoova
  voice bundle (`scoova.voice.{far,mid,near,confirm,reaffirm,…}`)
- `adapter-scoova-geocoding` / `adapter-scoova-weather` — sibling
  adapters for places + weather
- `adapter-google-maps` / `adapter-mapbox` / `adapter-maplibre` —
  map surface adapters; the nav layer overlays on any of them

### UI (`ui`)
- `MapboxNavLayerView` / `MapLibreNavLayerView` — drop-in Compose
  overlays: maneuver banner, heading puck, route preview card
