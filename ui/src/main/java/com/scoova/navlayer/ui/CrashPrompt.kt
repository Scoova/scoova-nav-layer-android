package com.scoova.navlayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scoova.navlayer.core.CrashEvent
import kotlinx.coroutines.delay

/**
 * Modal "Are you okay?" prompt that fires after the core's crash detector
 * flags a hard impact or hard brake.
 *
 * The prompt counts down from [countdownSeconds]; if the rider doesn't
 * tap "I'm OK" before the timer hits zero, [onTimeout] fires — host
 * apps wire that to whatever emergency-contact flow they have (SMS the
 * pinned contact, dial a number, call a webhook). The prompt is
 * intentionally agnostic about *what* timeout does — that's a host
 * decision and a regulatory minefield we don't want baked into the SDK.
 *
 * Visually heavy on purpose: black scrim, large headline, big tap
 * targets. The rider may be disoriented after an impact, so the OK
 * button has to be impossible to miss.
 *
 * **Usage**
 * ```kotlin
 * var crash: CrashEvent? by remember { mutableStateOf(null) }
 * LaunchedEffect(Unit) {
 *     nav.crashEvents.collect { crash = it }
 * }
 * crash?.let {
 *     ScoovaCrashPrompt(
 *         event       = it,
 *         onDismiss   = { crash = null },
 *         onTimeout   = { crash = null; emergency.notify(it) },
 *         onCallNow   = { emergency.notify(it) },
 *     )
 * }
 * ```
 */
@Composable
public fun ScoovaCrashPrompt(
    event: CrashEvent,
    onDismiss: () -> Unit,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
    onCallNow: (() -> Unit)? = null,
    countdownSeconds: Int = 30,
    style: ScoovaCrashStyle = ScoovaCrashStyle.Default,
) {
    var remaining by remember(event) { mutableIntStateOf(countdownSeconds) }

    // Tick the countdown once per second. Restarts when [event] changes
    // (a second crash before the first prompt dismissed = fresh timer).
    LaunchedEffect(event) {
        while (remaining > 0) {
            delay(1_000L)
            remaining -= 1
        }
        // Out of the loop = timer hit zero without dismissal.
        onTimeout()
    }

    // Full-screen scrim swallows touches so the rider can't tap-through
    // to map gestures while disoriented — the prompt MUST get a
    // response (or time out).
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(style.scrim)
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .shadow(28.dp, RoundedCornerShape(28.dp))
                .background(style.surface, RoundedCornerShape(28.dp))
                .border(1.dp, style.border, RoundedCornerShape(28.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Headline reflects whether it's an impact (rider fell /
            // collided) or a hard brake (sudden slow-down, often a
            // near-miss). Different tones avoid crying wolf — hard
            // brake is concerning but rarely an actual emergency.
            val (title, subtitle) = when (event) {
                is CrashEvent.Impact -> "Are you okay?" to
                    "We detected a hard impact (${"%.1f".format(event.peakG)}g)."
                is CrashEvent.HardBrake -> "Whoa — hard brake" to
                    "Let us know you're alright (${"%.1f".format(event.peakG)}g)."
            }
            Text(
                title,
                color = style.title,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                subtitle,
                color = style.subtitle,
                fontSize = 14.sp,
            )

            Spacer(Modifier.height(20.dp))

            // Countdown pill. We keep it as a number rather than a
            // visual ring so it works on any background and a rider
            // glancing at it under stress can read it instantly.
            Row(
                modifier = Modifier
                    .background(style.timerBg, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Calling emergency contacts in ",
                    color = style.timerLabel,
                    fontSize = 13.sp,
                )
                Text(
                    "${remaining}s",
                    color = style.timerCount,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(24.dp))

            // Primary CTA — "I'm OK" — full width, vivid colour, the
            // tap target a flustered rider can hit without aiming.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .background(style.okBg, RoundedCornerShape(16.dp))
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "I'm OK",
                    color = style.okText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (onCallNow != null) {
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .border(1.dp, style.border, RoundedCornerShape(14.dp))
                        .clickable { onCallNow() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Call for help now",
                        color = style.callText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** Theme tokens for [ScoovaCrashPrompt]. Override to re-skin. */
public data class ScoovaCrashStyle(
    val scrim: Color,
    val surface: Color,
    val border: Color,
    val title: Color,
    val subtitle: Color,
    val timerBg: Color,
    val timerLabel: Color,
    val timerCount: Color,
    val okBg: Color,
    val okText: Color,
    val callText: Color,
) {
    public companion object {
        public val Default: ScoovaCrashStyle = ScoovaCrashStyle(
            scrim = Color.Black.copy(alpha = 0.75f),
            surface = Color(0xFF0F1421),
            border = Color.White.copy(alpha = 0.10f),
            title = Color.White,
            subtitle = Color.White.copy(alpha = 0.70f),
            timerBg = Color(0xFFFF6A00).copy(alpha = 0.18f),
            timerLabel = Color.White.copy(alpha = 0.75f),
            timerCount = Color(0xFFFF6A00),
            okBg = Color(0xFF2EA8FF),
            okText = Color.White,
            callText = Color(0xFFFF6A00),
        )
    }
}
