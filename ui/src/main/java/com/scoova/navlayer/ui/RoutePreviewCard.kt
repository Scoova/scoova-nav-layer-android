package com.scoova.navlayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Drop-in route preview card. Renders distance, estimated time, optional
 * profile pill, optional error/loading states, and a primary "Start" CTA.
 *
 * Bind to whatever route state your app holds:
 *
 * ```kotlin
 * ScoovaRoutePreviewCard(
 *     destinationLabel = state.destinationLabel,
 *     distanceKm       = state.routeDistanceKm,
 *     etaMinutes       = state.routeDurationMin,
 *     profileLabel     = "Cycling",
 *     onStart          = { viewModel.startRide() },
 * )
 * ```
 *
 * Style with [ScoovaPreviewStyle]; defaults match Scoova brand.
 */
@Composable
public fun ScoovaRoutePreviewCard(
    destinationLabel: String?,
    distanceKm: Double,
    etaMinutes: Int,
    modifier: Modifier = Modifier,
    profileLabel: String? = null,
    profileAccent: Color? = null,
    isLoading: Boolean = false,
    error: String? = null,
    onStart: () -> Unit = {},
    onSecondary: (() -> Unit)? = null,
    secondaryLabel: String = "Simulate",
    style: ScoovaPreviewStyle = ScoovaPreviewStyle.Default,
) {
    Column(
        modifier = modifier
            .shadow(20.dp, RoundedCornerShape(22.dp))
            .background(style.surface, RoundedCornerShape(22.dp))
            .border(1.dp, style.border, RoundedCornerShape(22.dp))
            .padding(20.dp),
    ) {
        Text(
            destinationLabel ?: "Route preview",
            color = style.text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${"%.1f".format(distanceKm)} km · ${etaMinutes} min",
            color = style.muted,
            fontSize = 13.sp,
        )

        if (isLoading) {
            Spacer(Modifier.height(10.dp))
            Text("Planning route…", color = style.accent, fontSize = 13.sp)
        }
        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(error, color = style.error, fontSize = 12.sp)
        }

        Spacer(Modifier.height(14.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatBlock("DISTANCE", "${"%.1f".format(distanceKm)} km", style.text, Modifier.weight(1f), style)
            StatBlock("EST. TIME", "${etaMinutes} min", style.warning, Modifier.weight(1f), style)
            if (!profileLabel.isNullOrBlank()) {
                StatBlock("PROFILE", profileLabel, profileAccent ?: style.success, Modifier.weight(1f), style)
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onSecondary != null) {
                SecondaryButton(secondaryLabel, Modifier.weight(1f), onSecondary, style)
            }
            PrimaryButton(
                text = "Start ride",
                modifier = Modifier.weight(if (onSecondary != null) 1.5f else 1f),
                onClick = onStart,
                style = style,
            )
        }
    }
}

@Composable
private fun StatBlock(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    style: ScoovaPreviewStyle,
) {
    Column(modifier = modifier) {
        Text(
            label,
            color = style.muted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
        Text(value, color = valueColor, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun PrimaryButton(
    text: String,
    modifier: Modifier,
    onClick: () -> Unit,
    style: ScoovaPreviewStyle,
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .background(
                Brush.verticalGradient(listOf(style.accentSoft, style.accent)),
                RoundedCornerShape(14.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
private fun SecondaryButton(
    text: String,
    modifier: Modifier,
    onClick: () -> Unit,
    style: ScoovaPreviewStyle,
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .background(style.surfaceRaised, RoundedCornerShape(14.dp))
            .border(1.dp, style.border, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text, color = style.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

public data class ScoovaPreviewStyle(
    val surface: Color,
    val surfaceRaised: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val accentSoft: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
) {
    public companion object {
        public val Default: ScoovaPreviewStyle = ScoovaPreviewStyle(
            surface       = Color(0xFF0F1421),
            surfaceRaised = Color(0xFF1A1F2E),
            border        = Color(0xFF2D3548),
            text          = Color(0xFFFFFFFF),
            muted         = Color(0xFF94A3B8),
            accent        = Color(0xFF0EA5E9),
            accentSoft    = Color(0xFF38BDF8),
            success       = Color(0xFF84CC16),
            warning       = Color(0xFFF59E0B),
            error         = Color(0xFFEF4444),
        )
    }
}
