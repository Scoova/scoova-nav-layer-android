package com.scoova.navlayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Three-cell mid-ride status card: **ETA**, **remaining time**, and
 * **remaining distance**. Mirrors what Google Maps / Apple Maps render
 * at the bottom of the screen during navigation — it's the single
 * most-stared-at piece of info during a ride.
 *
 * The card also surfaces two transient banners that overlay the top
 * edge:
 *   * `isOffRoute=true` flips the strip to a warning tone (orange band
 *     reads "Off route — heading back").
 *   * `isRerouting=true` shows a thin progress band ("Calculating a new
 *     route…"). Distinct from off-route — the latter is the *state*,
 *     the former is what we're *doing* about it.
 *
 * **Usage**
 * ```kotlin
 * ScoovaEtaCard(
 *     distanceRemainingKm = state.routeDistanceKm - state.coveredKm,
 *     minutesRemaining    = remaining,
 *     unitsMetric         = state.settings.unitsMetric,
 *     isOffRoute          = vm.isOffRoute.collectAsState().value,
 *     isRerouting         = vm.isRerouting.collectAsState().value,
 * )
 * ```
 */
@Composable
public fun ScoovaEtaCard(
    distanceRemainingKm: Double,
    minutesRemaining: Int,
    unitsMetric: Boolean,
    modifier: Modifier = Modifier,
    isOffRoute: Boolean = false,
    isRerouting: Boolean = false,
    /**
     * Now-clock used to compute arrival time. Defaults to
     * `System.currentTimeMillis()` — the param exists so callers can
     * pin it for previews/snapshots without touching wall-clock.
     */
    nowMs: Long = System.currentTimeMillis(),
    style: ScoovaEtaStyle = ScoovaEtaStyle.Default,
) {
    val distanceLabel = formatDistance(distanceRemainingKm, unitsMetric)
    val arrivalLabel: String = remember(minutesRemaining, nowMs) {
        // SimpleDateFormat is allocation-y, but ETA card recomposes
        // ~once per second at most; not worth a global formatter cache
        // for the ~200 µs we'd save.
        val arrival = Date(nowMs + minutesRemaining * 60_000L)
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(arrival)
    }
    val durationLabel = formatDuration(minutesRemaining)

    Column(modifier = modifier) {
        // Off-route / rerouting banner overlays the card. AnimatedVisibility
        // collapses the strip when neither is true so the card's height
        // doesn't jump as state flips.
        AnimatedVisibility(
            visible = isOffRoute || isRerouting,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            val (banner, tone) = when {
                isRerouting -> "Calculating a new route…" to style.rerouteBg
                isOffRoute  -> "Off route — heading back to it" to style.offRouteBg
                else        -> "" to Color.Transparent
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tone, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(
                    banner,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(20.dp, RoundedCornerShape(22.dp))
                .background(style.surface, RoundedCornerShape(22.dp))
                .border(1.dp, style.border, RoundedCornerShape(22.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EtaCell(
                primary = arrivalLabel,
                label = "Arrival",
                primaryColor = style.accent,
                labelColor = style.label,
                modifier = Modifier.weight(1f),
            )
            VDivider(style.border)
            EtaCell(
                primary = durationLabel,
                label = "Remaining",
                primaryColor = style.primary,
                labelColor = style.label,
                modifier = Modifier.weight(1f),
            )
            VDivider(style.border)
            EtaCell(
                primary = distanceLabel,
                label = "Distance",
                primaryColor = style.primary,
                labelColor = style.label,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun EtaCell(
    primary: String,
    label: String,
    primaryColor: Color,
    labelColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            primary,
            color = primaryColor,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            color = labelColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun VDivider(color: Color) {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(36.dp)
            .background(color),
    )
}

/**
 * Memoised `remember` from the Compose stdlib, lifted here so we don't
 * import the full runtime in callers via wildcard. (Just a wrapper.)
 */
@Composable
private fun <T> remember(key1: Any?, key2: Any?, calc: () -> T): T =
    androidx.compose.runtime.remember(key1, key2, calc)

private fun formatDistance(km: Double, metric: Boolean): String {
    if (metric) {
        if (km < 1.0) return "${(km * 1000).roundToInt()} m"
        if (km < 10) return "%.1f km".format(km)
        return "${km.roundToInt()} km"
    } else {
        val mi = km * 0.621371
        if (mi < 0.5) return "${(mi * 5280).roundToInt()} ft"
        if (mi < 10) return "%.1f mi".format(mi)
        return "${mi.roundToInt()} mi"
    }
}

private fun formatDuration(minutes: Int): String {
    if (minutes < 1) return "<1 min"
    if (minutes < 60) return "$minutes min"
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "${h}h" else "${h}h ${m}m"
}

/** Theme tokens for [ScoovaEtaCard]. Override fields to re-skin. */
public data class ScoovaEtaStyle(
    val surface: Color,
    val border: Color,
    val primary: Color,
    val accent: Color,
    val label: Color,
    val offRouteBg: Color,
    val rerouteBg: Color,
) {
    public companion object {
        public val Default: ScoovaEtaStyle = ScoovaEtaStyle(
            surface = Color(0xFF0F1421).copy(alpha = 0.96f),
            border = Color.White.copy(alpha = 0.08f),
            primary = Color.White,
            accent = Color(0xFF2EA8FF),
            label = Color.White.copy(alpha = 0.55f),
            offRouteBg = Color(0xFFFF6A00),
            rerouteBg = Color(0xFF2EA8FF),
        )
    }
}
