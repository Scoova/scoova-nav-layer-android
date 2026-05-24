# Scoova Ride — Privacy Policy

_Last updated: 2026-05-11_

Scoova Ride is a navigation app for movers — cyclists, walkers, runners,
scooter riders, drivers, and couriers. This page describes what data the
app collects, why, and where it goes. Short version: as little as possible,
and never sold.

## What we collect

- **Approximate and precise location** — only while a ride is active.
  Used to render your position on the map, compute distance covered, and
  drive turn-by-turn navigation cues. We do not collect location while
  the app is in the background or before you start a ride.
- **Ride history** — distance, time, average speed, and start/end
  timestamps for completed rides. **Stored only on your device.** Capped
  at 200 rides; oldest entries drop off automatically.
- **Settings** — your picked profile, units (metric/imperial), language,
  voice on/off. Stored only on your device.
- **Crash and performance reports** — if our [Scoova Monitor](https://monitor.scoo-va.info)
  SDK is bundled in your build of the app, it sends anonymous crash
  reports, ANRs, and battery telemetry to monitor.scoo-va.info. No
  personally identifiable information. You can disable this in Settings.

## What we do **not** collect

- Background location
- Contacts, photos, calendar, microphone, camera, SMS, files
- Advertising identifiers
- Browsing history outside the app
- Anything from third-party social accounts

## Who we share with

Nobody, by default. The only third-party data flow is:

- **Routing requests** to `routing.scoo-va.info` (operated by Scoova)
  with your origin and destination, so we can plan a route. Not stored
  for analytics.
- **Tile requests** to `tiles.scoo-va.info` (operated by Scoova) for map
  rendering. The standard map-tile protocol leaks viewport bounds; we
  log nothing beyond standard CDN access logs which are rotated weekly.

We do not sell, rent, license, or share your personal data with any third
party for advertising, profiling, or aggregation purposes.

## Your rights

- **Delete your data**: clear the app's storage in Android Settings →
  Apps → Scoova Ride → Storage → Clear data. This removes all rides,
  settings, and the picked profile.
- **Request data export**: email <privacy@scoo-va.info> — we reply within
  30 days.
- **Opt out of crash reporting**: Settings → Voice (toggle Crash reporting
  off, once Monitor integration ships).

## Children

Scoova Ride is not directed at children under 13.

## Contact

Questions, requests, complaints: <privacy@scoo-va.info>

## Changes

We'll update this page if the data flows change. The "Last updated" date
at the top is the source of truth.
