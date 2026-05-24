package com.scoova.ride

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * One-stop UX for the "stop killing my navigation in the background"
 * dance Android demands.
 *
 * On vanilla Android the only switch that matters is the per-app
 * battery optimisation flag — turning it off lets our foreground
 * service run reliably while the screen is off. Most OEMs add a
 * second layer of "smart battery" / "auto-launch" / "sleeping apps"
 * lists that can silently kill foreground services anyway (Samsung's
 * is the most aggressive); for those we can only point users at the
 * relevant Settings page and explain why.
 *
 * **Public surface**
 *   • [isIgnoringBatteryOptimizations] — am I whitelisted right now?
 *   • [requestIgnoreIntent] — launch the system "ask once" dialog.
 *   • [openBatterySettingsIntent] — fall back to the full settings list
 *     (used when [requestIgnoreIntent] was denied previously and the
 *     dialog won't reappear, or when the user wants to whitelist
 *     manually on an aggressive OEM).
 *   • [isLikelyAggressiveOem] — Samsung / Xiaomi / Huawei / Honor / OPPO
 *     / Vivo / Realme. Used by the Plan-screen banner to decide whether
 *     to nudge the user proactively; on stock Pixel / Sony the banner
 *     stays hidden unless the rider has explicitly hit the Settings
 *     entry first.
 */
internal object BatteryOptimizationHelper {

    /**
     * Returns true when the app is whitelisted from battery
     * optimisation (i.e. the OS won't doze our foreground service
     * during deep sleep). Pre-Marshmallow always returns true — the
     * permission didn't exist then.
     */
    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /**
     * Returns an intent for the *one-tap* "allow once" dialog. The
     * system shows it as a modal; the user picks Allow / Deny.
     *
     * Google Play forbids this for apps that aren't in their "eligible
     * use-case" list, but turn-by-turn navigation IS an eligible use
     * case (see the IID_ALARM_MANAGER and SCHEDULE_EXACT_ALARM policy
     * doc — same allowlist).
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.fromParts("package", ctx.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Fallback intent — opens the system-wide "Battery optimisation"
     * settings page where the user can find our app in the "Not
     * optimised" / "Optimised" lists. Useful when the [requestIgnoreIntent]
     * dialog has been dismissed before (it won't reappear automatically
     * on some OEMs).
     */
    fun openBatterySettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Heuristic for OEMs that historically kill foreground services
     * regardless of the battery-optimisation flag (each has their own
     * proprietary "auto-launch" / "memory cleaning" / "sleeping apps"
     * lists). When true, surface the reliability banner unconditionally
     * — these riders need a nudge.
     *
     * On stock Android (Pixel) the OS respects FG service semantics
     * and the banner only appears if the user has actually been
     * killed.
     */
    fun isLikelyAggressiveOem(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m in AGGRESSIVE_OEMS
    }

    private val AGGRESSIVE_OEMS = setOf(
        "samsung", "xiaomi", "redmi", "huawei", "honor",
        "oppo", "vivo", "realme", "oneplus", "meizu",
    )
}
