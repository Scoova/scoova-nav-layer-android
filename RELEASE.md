# Scoova Ride — Release Guide

Everything needed to ship Scoova Ride to the Play Store, end to end.

---

## 1. One-time keystore generation

A release keystore signs every Play Store upload for the life of the app
— losing it means losing the ability to update Scoova Ride forever. Store
the `.jks` file off-disk (1Password / iCloud Keychain / encrypted backup).

```bash
keytool -genkey -v \
  -keystore ~/Documents/scoova-ride-release.jks \
  -alias scoova-ride \
  -keyalg RSA -keysize 2048 -validity 36500 \
  -storepass <YOUR_STORE_PW> -keypass <YOUR_KEY_PW> \
  -dname "CN=Scoova, O=Scoova, L=Cairo, C=EG"
```

> `validity 36500` = 100 years. Play Console requires the key to outlive
> October 2033 at minimum — go long, you can't change it later.

Then add to `~/.gradle/gradle.properties` (NOT in the project):

```properties
RIDE_KEYSTORE_PATH=/Users/<you>/Documents/scoova-ride-release.jks
RIDE_KEYSTORE_PASSWORD=<store password>
RIDE_KEY_ALIAS=scoova-ride
RIDE_KEY_PASSWORD=<key password>
```

The Gradle config reads from there; nothing sensitive lives in this repo.

---

## 2. Build the release artifact

```bash
cd scoova-nav-layer-android
./gradlew :demo:bundleRelease     # → AAB for Play Console
# or
./gradlew :demo:assembleRelease   # → APK for sideload testing
```

Outputs:

- `demo/build/outputs/bundle/release/demo-release.aab`  (upload this)
- `demo/build/outputs/apk/release/demo-release.apk`     (sideload test)

Verify it's signed:

```bash
jarsigner -verify -verbose -certs demo/build/outputs/apk/release/demo-release.apk \
  | head -20
```

---

## 3. Play Console — first upload (~30 min)

1. **Create the app** at <https://play.google.com/console>
   - App name: **Scoova Ride**
   - Default language: English (United States)
   - App or game: App
   - Free or paid: Free
   - Confirm developer policy declarations

2. **Set up your app** (left sidebar checklist):
   - **App access** → Available without restrictions
   - **Ads** → No ads
   - **Content rating** → Run the questionnaire (Navigation = E for Everyone)
   - **Target audience** → Age 13+ (location use)
   - **News app** → No
   - **COVID-19 contact tracing** → No
   - **Data safety** → Fill from `PRIVACY.md` (location used while ride is active, never shared)
   - **Government app** → No
   - **Financial features** → None

3. **Store listing**:
   - **Short description** (80 char):
     `Eyes on the road. Cycling navigation that doesn't need your eyes.`
   - **Full description** (4000 char): use `STORE_LISTING.md` if present
   - **App icon**: 512×512 PNG — use the `ic_launcher-playstore.png` from
     scoova_app/app/src/main/
   - **Feature graphic**: 1024×500 — TODO
   - **Phone screenshots**: 2–8 PNGs from running the app in an emulator
   - **Category**: Maps & Navigation

4. **Privacy policy** — point at <https://scoo-va.info/privacy>
   (host `PRIVACY.md` rendered to HTML on that URL)

5. **Internal testing track** → Create release
   - Upload the AAB
   - Release name: `1.0.0 (1)`
   - Release notes:
     ```
     Eye-on-Road navigation for cyclists. Six personas
     (walker / runner / cyclist / scooter / driver / courier).
     Phase-based cues. Spatial audio. 7 languages.
     ```
   - Save → Review release → Start rollout to internal testing
   - Add yourself + ~5 testers to the testers list

The first review usually goes through in 1–2 hours. After that, every
update is generally same-day.

---

## 4. Verify on a real device

After Play Console accepts the AAB, install via the internal-testing link
that comes by email:

- Open the link on your phone (signed in with the tester Google account)
- Tap "Become a tester"
- Install from Play Store

That's the real smoke test — it proves the signing, the bundle, and the
Play Store delivery path all work end-to-end.

---

## 5. Common gotchas

- **"Your app uses or accesses sensitive permissions"** — Play Console
  flags fine-location. Declaration: "Used during active rides to render
  the user position on the map and report progress to the navigation
  layer. Not stored, not shared."
- **"Target API level"** — Must be ≥34 for new apps from August 2024.
  We're at 35 ✅.
- **App signing by Google Play** — opt in. Google holds your upload key
  hostage, but in exchange they re-sign per-device on download. Lower
  blast radius if your upload key leaks.
- **AAB not APK** — Play Store stopped accepting APKs for new apps.
  Use `bundleRelease`, not `assembleRelease`.
