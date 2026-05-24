package com.scoova.ride

import android.app.Application
import com.scoova.monitor.sdk.ScoovaMonitor

/**
 * Application class for Scoova Ride.
 *
 * Initializes Scoova Monitor (crash + ANR + screen views + performance)
 * on cold start. The API key is injected at build time from
 * `-PscoovaMonitorApiKey=sm_…` (see demo/build.gradle.kts); local builds
 * fall back to `sm_demo_local_ride`, which talks to the same monitor
 * endpoint but tags events as a non-prod workspace.
 */
class ScoovaRideApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ScoovaMonitor.init(this, BuildConfig.SCOOVA_MONITOR_API_KEY)
    }
}
