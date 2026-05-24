package com.scoova.ride

/**
 * Single source of truth for how the demo reaches Scoova's backend.
 * Every routing / weather / geocoding call goes through the keyed API
 * gateway at `api.scoo-va.info` — the raw service subdomains
 * (`routing.` / `geocoding.` / `weather.`) are firewalled, so a call
 * without a valid key is rejected with 401.
 */
object ScoovaApi {
    /** Gateway base. Service paths hang off this: `/route`, `/weather`,
     *  `/autocomplete`, `/reverse`. */
    const val GATEWAY = "https://api.scoo-va.info/api/v1"

    /** API key issued from the cloud.scoo-va.info admin panel for the
     *  ScoovaRide demo. Sent as the `X-API-Key` header on every call. */
    const val KEY = "sk_live_f92af0e8-73af-487f-a289-2368f9e3df13"
}
