package com.scoova.navlayer.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches the device battery during navigation and exposes a single
 * [state] StateFlow that bundles charge percentage, charging flag, and
 * a derived "low power" recommendation.
 *
 * Why this lives in `core`: power management is a nav-engine concern,
 * not a host-app one. A rider running navigation on a 12 % battery
 * mid-ride is the canonical case for switching to:
 *   • voice-only mode (drop the screen)
 *   • coarser GPS sampling
 *   • throttled re-routes
 *
 * The class is observation-only — it surfaces the signal; the host
 * (or higher SDK layers) decide what to do. The defaults expose a
 * "should the UI prompt for low-power mode?" boolean that flips at
 * 15 % discharging, restores at 25 % to avoid flapping.
 *
 * **Usage**
 * ```kotlin
 * val battery = BatteryAwareNav(application)
 * battery.start()
 * scope.launch {
 *     battery.state.collect { s ->
 *         if (s.shouldSuggestLowPower) prompt.show()
 *     }
 * }
 * // on activity destroy:
 * battery.stop()
 * ```
 */
public class BatteryAwareNav(
    private val ctx: Context,
    private val lowEnterPct: Int = 15,
    private val lowExitPct: Int = 25,
) {

    public data class State(
        public val percent: Int,
        public val isCharging: Boolean,
        public val shouldSuggestLowPower: Boolean,
    )

    private val _state = MutableStateFlow(
        State(percent = 100, isCharging = false, shouldSuggestLowPower = false)
    )
    public val state: StateFlow<State> = _state.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            applySnapshot(intent)
        }
    }

    private var started: Boolean = false

    public fun start() {
        if (started) return
        started = true
        // ACTION_BATTERY_CHANGED is sticky — registerReceiver returns
        // the most recent broadcast immediately, so we get the current
        // level without waiting for the next status change.
        //
        // On Android 14+ (target SDK 34+) a context-registered receiver
        // MUST specify an export flag or it throws SecurityException
        // at runtime. ACTION_BATTERY_CHANGED is a protected system
        // broadcast — only the system can dispatch it — so the export
        // flag has no actual security effect; we pass NOT_EXPORTED for
        // tightness and to silence the runtime guard. Pre-Tiramisu the
        // flag overload doesn't exist; fall back to the legacy two-arg
        // form which uses the old default behaviour.
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, filter)
        }
        if (sticky != null) applySnapshot(sticky)
    }

    public fun stop() {
        if (!started) return
        started = false
        runCatching { ctx.unregisterReceiver(receiver) }
    }

    private fun applySnapshot(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val pct = if (level >= 0) (level * 100) / scale else _state.value.percent
        val charging = plugged != 0
        // Hysteresis on the recommendation so a battery dipping
        // between 14 % and 16 % doesn't flap the prompt.
        val prev = _state.value.shouldSuggestLowPower
        val suggest = when {
            charging -> false                                  // charging clears the warning
            !prev && pct <= lowEnterPct -> true                // crossing down into "low"
            prev && pct >= lowExitPct -> false                 // climbed back out
            else -> prev                                       // unchanged
        }
        _state.value = State(percent = pct, isCharging = charging, shouldSuggestLowPower = suggest)
    }
}
