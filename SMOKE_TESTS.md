# Scoova Ride — Smoke Test Checklist

Run these on a real phone before each release. Each test is 1–2 minutes.
The full set takes ~15 minutes including the install.

## Setup (one-time)

1. Enable Developer Options on the phone (tap Build Number 7 times in Settings → About)
2. Enable USB debugging
3. Plug into Mac, accept the RSA prompt

```bash
adb devices -l               # should show your phone
```

## Install the build

```bash
cd scoova-nav-layer-android

# Debug build — fast iteration, full logging
./gradlew :demo:installDebug

# Release build — what ships, harder to debug
./gradlew :demo:installRelease
```

Latest builds on disk:
- `demo/build/outputs/apk/debug/demo-debug.apk`    (~59 MB)
- `demo/build/outputs/apk/release/demo-release.apk` (~53 MB)

---

## Test 1 — First-run persona picker (~90 s)

1. **Uninstall first** to force first-run: `adb uninstall com.scoova.ride`
2. Install + launch
3. **Expect**: persona picker with 6 cards. *Walker / Runner / Cyclist / Scooter / Driver / Courier*.
4. Tap **Cyclist** → expect Plan screen with cyan accent and "🚴 Cycling nav with voice" header
5. Force-stop the app, relaunch → **expect Plan screen directly** (persona persisted)

✅ PASS = persona screen appears once, then never again.

---

## Test 2 — Map + route plan (~90 s)

1. Grant location permission
2. Long-press anywhere on the map
3. **Expect**: route polyline appears in cyan, distance + ETA show at bottom
4. Tap **Simulate**
5. **Expect**: route plays out at ~20 km/h, banner shows turn-by-turn, voice cues fire

✅ PASS = route shape renders, banner updates, voice speaks.

---

## Test 3 — Spatial audio (~30 s)

**Put earbuds in.** This test only proves what it claims with earbuds.

1. Plan screen → Settings (gear icon, top-right)
2. Scroll to **Voice** section
3. Tap **Test spatial audio** → **PLAY**
4. **Expect**: a tone in the LEFT ear, then a tone in the RIGHT ear

✅ PASS = clear left/right channel separation.

Then back to Plan, start a Simulate ride and listen for left-turn cues
arriving primarily in the left ear, right-turn cues in the right.

---

## Test 4 — Language switch (~30 s)

1. Settings → **Language** → tap **العربية (مصري)**
2. Back to Plan, Simulate a ride
3. **Expect**: voice cues now in Egyptian Arabic dialect

✅ PASS = the very next cue speaks Arabic.

---

## Test 5 — Profile change persists (~30 s)

1. Settings → **Your profile** → **Change** → pick **Driver**
2. Plan a route → **expect**: red accent on header, "PROFILE: Driver" in stats
3. Simulate → cues fire at longer distances (800 m / 400 m / 200 m vs cyclist's 200/100/50)

✅ PASS = stats and cues both shift.

---

## Test 6 — Ride history persists (~60 s)

1. Complete (or simulate) at least one ride to Summary
2. Tap **Plan another ride** → back to Plan
3. Expect: small "1 past ›" pill on the empty-state copy
4. Tap it → ride history list shows the ride with date, distance, avg speed
5. Force-stop, relaunch
6. **Expect**: pill still there with the same count

✅ PASS = history persists across cold start.

---

## Test 7 — Settings persistence (~30 s)

1. Settings → switch to Imperial units, switch language to French, toggle voice OFF
2. Force-stop the app
3. Relaunch → Settings
4. **Expect**: all three changes still set

✅ PASS = settings survive force-stop.

---

## Test 8 — Permission handling (~60 s)

1. Settings (phone OS, not in-app) → Apps → Scoova Ride → Permissions → Location → Deny
2. Launch the app
3. **Expect**: persona screen still works, map still renders, but route planning fails gracefully (toast or error label)

✅ PASS = no crash, recovered with a re-grant.

---

## Test 9 — Background → foreground (~30 s)

1. Start a Simulate ride
2. Press Home (background the app)
3. Wait 30 seconds
4. Resume the app
5. **Expect**: ride still playing, no crash, banner reflects current state

✅ PASS = no rebuild required mid-ride.

---

## Test 10 — Release build verification (~60 s)

```bash
./gradlew :demo:installRelease
# launch + run through Test 1 + Test 2 once more
```

✅ PASS = release variant behaves identical to debug.

---

## If a test fails

```bash
adb logcat -d | grep -E "ScoovaRide|ScoovaNavLayer|VoiceEngine|FATAL|AndroidRuntime" | tail -50
```

For crashes, the stack trace is what we file. For nav glitches, also grab
the maneuver log:

```bash
adb logcat -d | grep -E "onProgress|firedThreshold|currentInstruction" | tail -100
```
