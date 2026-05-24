# Voice-Navigation Reliability — what kills it, and what Scoova does about it

Voice navigation in cross-platform apps has 13 well-known failure modes.
Most of them come from doing audio work in the wrong layer (JS / Dart)
or from libraries fighting for the same audio session. This page lists
every mode we know about and what the Scoova Nav Layer SDK does to
neutralize it.

If you hit a reliability issue with our SDK, open an issue with the row
number — that's our shared vocabulary for what's likely wrong.

---

| # | Failure mode | Why it happens | What Scoova does |
|---|---|---|---|
| 1 | **First-cue latency** (1.5–2.5 s delay on the first turn) | Android/iOS TTS engines load voices lazily. The first `speak()` triggers the load. | **Pre-warm**: during `nav.start()` the SDK synthesises one silent utterance to wake the engine. First real cue lands in ~100 ms. |
| 2 | **Audio session conflicts** (other libs stop working) | Several libraries set conflicting AVAudioSession categories. `react-native-track-player`, `expo-av`, and `react-native-tts` are the usual suspects. | Native SDK uses **`.playback` + `.voicePrompt` + `.duckOthers`** on iOS, and **`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`** on Android. These are the iOS/Android-blessed nav settings; other libs respect them. |
| 3 | **Stomped phone media volume** (user's music permanently quiet) | Some SDKs call `AudioManager.setStreamVolume()` to "boost" cues. That's the **system** volume slider — it persists. | **The SDK never calls `setStreamVolume` or any equivalent.** Per-utterance volume is set on the TTS engine and on the `MediaPlayer` for spatial cues. The user's volume slider stays where they left it. |
| 4 | **Voice goes silent in background** | iOS suspends apps without `UIBackgroundModes: audio`. Android suspends without a foreground service. | SDK doesn't request these for you (they're host-app concerns), but the docs include the exact `Info.plist` and foreground-service snippets you need. |
| 5 | **A2DP / Bluetooth latency** ("Turn now!" arrives 300 ms late) | Bluetooth A2DP adds 100–400 ms of audio latency. Our cue thresholds assume zero-latency speakers. | SDK detects audio route (`getCurrentRoute()`) and offsets the "near" threshold by 250 ms when A2DP is connected. Configurable via `Builder.routeLookahead()`. |
| 6 | **Headphones plugged in mid-ride** (spatial cues stop making sense) | Route change after route is computed. Spatial pan was set assuming phone speaker; suddenly user has earbuds. | SDK listens for route-change events and re-evaluates spatial routing on the next cue automatically. The current cue completes as-is. |
| 7 | **Phone call interruption — no resume** | iOS/Android interrupt audio, fire an interruption notification. If the SDK doesn't observe it, voice silently dies. | SDK observes `AVAudioSession` interruption notifications (iOS) and `AudioManager.OnAudioFocusChangeListener` (Android). After the call ends, the next cue fires normally. |
| 8 | **Cue queue stutter** (3 cues queued up, fire in a burst) | Apps push progress at high frequency; threshold crossings stack up. | `ProgressTracker` enforces **crossing-only** firing (`prev > T ≥ cur`) AND a **max-seen guard** (`maxObserved ≥ T + 20m`). A cue can fire at most once per (maneuver, threshold) pair. Burst is mathematically impossible. |
| 9 | **Audio-deactivation pops** (clicking sound between cues) | iOS pops when `setActive(false)` is called while audio is still draining. | SDK never deactivates the session mid-cue. It deactivates only on `stop()` or after the playback completion callback fires. |
| 10 | **RN bridge serialization stall** (cues bunch up after JS-heavy work) | RN serialises every cross-language call. If JS thread is animating a list at 60 fps, your bridge calls queue. | **All audio + sensor logic runs in native code.** The RN bridge transports primitives only. Cue firing is *unaffected* by JS thread state. (See [native-first architecture](#native-first-architecture).) |
| 11 | **Locale fallback to English** (asked for Egyptian Arabic, heard English) | Device doesn't have the requested voice installed. `AVSpeechSynthesizer` / `TextToSpeech` silently fall back to the default. | SDK detects fallback at init time and degrades intelligently: `ar-EG` → `ar` (MSA) → `en-US`. Logged as a `voiceFallback` diagnostic; the consumer can choose to alert. |
| 12 | **Sample-rate mismatch** (stuttery cues on Bluetooth) | TTS engines render at 22050 Hz. Bluetooth devices often advertise 48000 Hz. Bad mixers crackle. | iOS path uses `AVAudioPCMBuffer` with the device's preferred sample rate; Android path uses the engine's native rate piped through MediaPlayer, which the OS resamples cleanly. |
| 13 | **Sensor / GPS in JS** (heading puck jitters, GPS lags) | Reading sensors from the JS side adds 2–5 ms per call. At 10 Hz, that's a noticeable lag. | All sensor + GPS reading lives in the **native SDK**. `CMMotionManager` / `CLLocationManager` / `SensorManager` / `FusedLocationProvider` are accessed only natively. RN/Flutter receive smoothed values via a 1-Hz stream. |

---

## Native-first architecture

This is the rule we live by. It's why our RN and Flutter wrappers don't
have any of the reliability issues that bite hand-rolled bridges:

> **All hardware-touching work (audio, GPS, sensors, TTS) lives in the
> native Android (Kotlin) or iOS (Swift) SDK. The React Native and
> Flutter wrappers are thin bridges that delegate to the native SDK.**

What this means in practice:

| Layer | Allowed to do |
|---|---|
| **Native (Kotlin / Swift)** | All audio playback, TTS, sensor reading, location, route detection, audio focus, voice fallback, diagnostics |
| **RN bridge (Kotlin + Swift)** | Marshal primitives in/out of the JS thread. Subscribe to native StateFlows/Publishers and emit them as RN events |
| **JS / TypeScript** | Hold the user-facing API, manage UI state, pass arrays of plain objects across the bridge |
| **Flutter bridge (Kotlin + Swift)** | Marshal primitives in/out of the platform channel. Subscribe to native StateFlows/Publishers and emit them as EventChannel streams |
| **Dart** | Hold the user-facing API, manage UI state, pass `Map<String, dynamic>` across the channel |

**What the bridges are not allowed to do:**

- ❌ Decode audio in JS / Dart
- ❌ Play audio buffers from JS / Dart
- ❌ Read sensors / GPS / compass directly from JS / Dart
- ❌ Configure `AVAudioSession` or `AudioManager` from JS / Dart
- ❌ Buffer or queue cues in JS / Dart

If you find yourself wanting to do any of those things in the wrapper
layer, the answer is to extend the native SDK and expose a new bridge
method instead. Open an issue.

---

## Volume safety guarantee

The Scoova Nav Layer SDK makes one promise we treat as a hard constraint:

> **The user's media volume slider, ringer volume, and per-app volume
> settings are sacred. The SDK reads them. The SDK never writes them.**

Mechanism:

- Android: per-utterance volume is set via `TextToSpeech.KEY_PARAM_VOLUME`
  and `MediaPlayer.setVolume(left, right)` — both operate on the playback
  stream only, never on system volume.
- iOS: per-utterance volume is set via `AVSpeechUtterance.volume` and
  `AVAudioPlayer.volume` / `AVAudioPlayer.pan` — both operate on the
  playback object only.
- Ducking (lowering OTHER apps' volume during a cue) uses standard OS
  ducking APIs (`AVAudioSession.CategoryOptions.duckOthers` on iOS,
  `AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` on Android). The OS
  releases the duck automatically when our cue ends — the SDK doesn't
  manually restore anyone else's volume.

If you see your phone media volume "drift" while using a Scoova-powered
app, **it isn't us** — it's almost certainly another library doing
`setStreamVolume` or `MPVolumeView` mutation. File a ticket with the
specific stack you're running and we'll help you find which one.

---

## Diagnostics API

When something does go wrong, you can read what the SDK thinks is true:

```kotlin
// Android
nav.diagnostics.value     // ScoovaNavLayer.Diagnostics(
                          //   audioRoute = AudioRoute.BluetoothA2dp,
                          //   ttsEngineReady = true,
                          //   lastCueLatencyMs = 87,
                          //   voiceFallback = LocaleFallback.EgyptianToMSA,
                          // )
```

```swift
// iOS
nav.diagnostics // @Published — observe with Combine
```

```ts
// React Native
nav.onDiagnostics(d => console.log(d))
```

```dart
// Flutter
nav.diagnosticsStream.listen(print)
```

---

## What to do if you still hit a reliability issue

1. Read the diagnostics snapshot — is `ttsEngineReady` true? Is the
   `audioRoute` what you expect? Is there a `voiceFallback`?
2. Verify no other audio library is reaching for `AVAudioSession` /
   `AudioManager`. The `audioSessionAuditor()` debug helper prints every
   non-Scoova caller it can detect.
3. File an issue with the diagnostics dump and the row number from the
   table above that you suspect.
