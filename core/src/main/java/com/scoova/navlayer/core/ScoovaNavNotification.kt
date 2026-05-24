package com.scoova.navlayer.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build

/**
 * The data the rider's sticky navigation notification displays. Held as
 * a plain value class so host apps can build it from whatever state
 * shape they have (the ViewModel's snapshot, a routing client response,
 * etc.) without coupling to a specific framework.
 *
 * Pass through [ScoovaNavNotificationBuilder.build] to turn into a
 * platform [Notification] ready for `startForeground`.
 */
public data class ScoovaNavNotificationContent(
    /**
     * Headline. Convention: "Xmin · Y.Z km" — ETA followed by remaining
     * distance. Renders as the notification title (large, bold) so the
     * rider can read it from the pulldown without expanding.
     */
    public val title: String,

    /**
     * Next maneuver, ready-rendered. Convention: "Turn right onto
     * Tahrir Square in 50 m". Null while no maneuver is available
     * (just-started, just-rerouted) — falls back to [destinationLabel].
     */
    public val maneuverText: String?,

    /**
     * "Heading to <label>" backup line — shown when [maneuverText] is
     * null. Use the place name the rider picked, not coordinates.
     */
    public val destinationLabel: String,

    /** Currently muted? Drives a tonal hint in [maneuverText] line. */
    public val isMuted: Boolean = false,
)

/**
 * Builds the Notification for an active ride from [ScoovaNavNotificationContent].
 *
 * The builder doesn't claim the channel for you — call
 * [ensureNavigationChannel] once on the [Context] before posting the
 * notification (it's idempotent so wiring it into every post is safe).
 *
 * **Why a separate builder vs. baking this into the service?** Host
 * apps may run navigation under their own foreground service (existing
 * MDM tooling, custom service-restart logic). The builder lets them
 * keep their service while still getting the Scoova-styled
 * notification UI for free.
 *
 * Example:
 * ```kotlin
 * ScoovaNavNotificationBuilder.ensureNavigationChannel(ctx)
 * val n = ScoovaNavNotificationBuilder.build(
 *     ctx = ctx,
 *     content = content,
 *     openAppIntent = openIntent,
 *     smallIconRes = R.drawable.ic_directions,
 * )
 * startForeground(NOTIF_ID, n, FOREGROUND_SERVICE_TYPE_LOCATION)
 * ```
 */
public object ScoovaNavNotificationBuilder {

    /** Channel ID used by [build]. Stable across builder versions. */
    public const val CHANNEL_ID: String = "scoova_navigation"

    /**
     * Create the notification channel if it doesn't exist. No-op on
     * pre-O. Safe to call repeatedly — the OS dedupes by [CHANNEL_ID].
     */
    @JvmStatic
    public fun ensureNavigationChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Navigation",
                // LOW = no sound or vibration. Critical: our TTS cues
                // handle audio; the notification just provides visual
                // continuity for when the screen's off or the user
                // switches apps. A sound on every maneuver update
                // would be jarring.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Sticky notification shown while a ride is in progress."
                setShowBadge(false)
            },
        )
    }

    /**
     * Build the Notification. Returns a [Notification] ready for
     * `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_LOCATION)`.
     *
     * @param openAppIntent fires when the rider taps the notification body —
     *   typically a PendingIntent that re-launches the host Activity.
     * @param muteIntent optional PendingIntent for a "Mute" action. Pass
     *   null to omit the action button (e.g. demo builds).
     * @param endIntent optional PendingIntent for an "End ride" action.
     *   Pass null to omit. Host apps that want this should route the
     *   intent through their own BroadcastReceiver / Service so it can
     *   stop the foreground service and ferry the event to the VM.
     */
    @JvmStatic
    @JvmOverloads
    public fun build(
        ctx: Context,
        content: ScoovaNavNotificationContent,
        openAppIntent: PendingIntent,
        smallIconRes: Int,
        muteIntent: PendingIntent? = null,
        endIntent: PendingIntent? = null,
    ): Notification {
        val body = buildString {
            append(content.maneuverText ?: "Heading to ${content.destinationLabel}")
            if (content.isMuted) append("  ·  Muted")
        }
        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setContentTitle(content.title)
            .setContentText(body)
            .setSmallIcon(smallIconRes)
            .setContentIntent(openAppIntent)
            // Sticky — can't be swiped away, since dismissing the notification
            // would orphan the rider's GPS + TTS without warning.
            .setOngoing(true)
            // Only alert (sound/vibrate) on the FIRST post; subsequent
            // updates are silent — what we want for live ETA refreshes.
            .setOnlyAlertOnce(true)
            // Tells the OS this is navigation — improves placement in
            // the shade and on lockscreen for Android 12+ pickers.
            .setCategory(Notification.CATEGORY_NAVIGATION)
            // Show the title + text on the lockscreen so the rider can
            // glance at next-maneuver without unlocking the phone.
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        // Big-text style so a long maneuver line wraps to two lines on
        // the pull-down instead of being ellipsed.
        builder.setStyle(Notification.BigTextStyle().bigText(body))

        if (muteIntent != null) {
            builder.addAction(
                Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?,
                    if (content.isMuted) "Unmute" else "Mute",
                    muteIntent,
                ).build()
            )
        }
        if (endIntent != null) {
            builder.addAction(
                Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?,
                    "End ride",
                    endIntent,
                ).build()
            )
        }
        return builder.build()
    }
}
