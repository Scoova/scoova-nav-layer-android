package com.scoova.ride

import com.scoova.navlayer.weather.ScoovaWeather
import com.scoova.navlayer.weather.ScoovaWeatherSnapshot

/**
 * Demo-side shim around the SDK's [ScoovaWeather]. The weather client
 * itself lives in `:adapter-scoova-weather`.
 *
 * [WeatherSnapshot] stays as a typealias so the rest of the demo
 * (HomeActions, WeatherChip, ViewModel) keeps working unchanged.
 */
typealias WeatherSnapshot = ScoovaWeatherSnapshot

class WeatherChipClient(apiKey: String = ScoovaApi.KEY) {
    private val sdk = ScoovaWeather(apiKey = apiKey)
    suspend fun now(lat: Double, lon: Double): WeatherSnapshot? = sdk.now(lat, lon)
}
