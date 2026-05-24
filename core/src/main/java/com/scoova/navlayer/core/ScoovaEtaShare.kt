package com.scoova.navlayer.core

import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Build a system-share intent that says "I'll be there at HH:MM" plus
 * an optional destination label and route preview URL.
 *
 * Why this is in `:core`: every host app's "share ETA" button does the
 * same thing — format the wall-clock arrival, build an Intent.ACTION_SEND,
 * launch the chooser. Repetition is the SDK's job.
 *
 * **Usage**
 * ```kotlin
 * ScoovaEtaShare.launch(
 *     ctx,
 *     minutesRemaining = 12,
 *     destinationLabel = "Cairo Mall",
 * )
 * ```
 */
public object ScoovaEtaShare {

    /**
     * Build the share intent without launching it. Useful when the
     * host app wants to attach extra recipients or pre-fill an
     * SMS-only intent.
     */
    @JvmStatic
    @JvmOverloads
    public fun buildIntent(
        minutesRemaining: Int,
        destinationLabel: String? = null,
        nowMs: Long = System.currentTimeMillis(),
        previewUrl: String? = null,
    ): Intent {
        val arrivalMs = nowMs + minutesRemaining.coerceAtLeast(0) * 60_000L
        val arrival = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(arrivalMs))
        val mins = if (minutesRemaining < 1) "<1 min" else "$minutesRemaining min"
        val tail = destinationLabel?.takeIf { it.isNotBlank() }?.let { " to $it" } ?: ""
        val urlSuffix = previewUrl?.let { "\n$it" } ?: ""
        val body = "I'll be there at $arrival ($mins away$tail).$urlSuffix"
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
            // Email subject line — ignored by SMS, used by Gmail / etc.
            putExtra(Intent.EXTRA_SUBJECT, "ETA via Scoova")
        }
    }

    /**
     * Build the intent and launch the system chooser. Convenience for
     * the 99% case where the host app just wants a "Share" button.
     */
    @JvmStatic
    @JvmOverloads
    public fun launch(
        ctx: Context,
        minutesRemaining: Int,
        destinationLabel: String? = null,
        nowMs: Long = System.currentTimeMillis(),
        previewUrl: String? = null,
        chooserTitle: String = "Share ETA",
    ) {
        val send = buildIntent(minutesRemaining, destinationLabel, nowMs, previewUrl)
        val chooser = Intent.createChooser(send, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(chooser)
    }
}
