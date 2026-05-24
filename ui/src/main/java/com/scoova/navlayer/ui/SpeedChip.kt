package com.scoova.navlayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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

/**
 * Live speed readout with optional speed-limit pip.
 *
 * Two layouts:
 *   * Without a speed limit, renders as a brand-coloured pill
 *     (current speed + unit).
 *   * With a speed limit, the limit number renders as a small circular
 *     "shield" alongside the pill — matches the standard road-sign
 *     vocabulary North-American and European drivers already know.
 *     The pill's number turns red when the rider exceeds the limit.
 *
 * Speed limit is optional because Scoova Routing doesn't always
 * surface it (depends on OSM completeness for the segment). The chip
 * silently degrades when [speedLimit] is null — no placeholder shown.
 */
@Composable
public fun ScoovaSpeedChip(
    speed: Int,
    unitLabel: String,
    modifier: Modifier = Modifier,
    speedLimit: Int? = null,
    style: ScoovaSpeedStyle = ScoovaSpeedStyle.Default,
) {
    val overLimit = speedLimit != null && speed > speedLimit
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .shadow(10.dp, RoundedCornerShape(18.dp))
                .background(style.surface, RoundedCornerShape(18.dp))
                .border(1.dp, style.border, RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$speed",
                color = if (overLimit) style.overLimit else style.accent,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(6.dp))
            Text(unitLabel, color = style.label, fontSize = 12.sp)
        }
        if (speedLimit != null) {
            Spacer(Modifier.width(8.dp))
            SpeedLimitShield(
                value = speedLimit,
                size = 38,
                style = style,
            )
        }
    }
}

/**
 * Road-sign-style "shield" showing the posted limit. Round white face
 * with a thick red ring — matches the EU / international convention.
 * Held as a separate composable so apps can drop it on the map
 * standalone (e.g. next to the maneuver banner during high-speed
 * stretches).
 */
@Composable
public fun SpeedLimitShield(
    value: Int,
    modifier: Modifier = Modifier,
    size: Int = 38,
    style: ScoovaSpeedStyle = ScoovaSpeedStyle.Default,
) {
    Column(
        modifier = modifier
            .size(size.dp)
            .background(style.shieldFill, CircleShape)
            .border(3.dp, style.shieldRing, CircleShape),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "$value",
            color = style.shieldText,
            fontSize = (size * 0.42f).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Theme tokens for [ScoovaSpeedChip]. */
public data class ScoovaSpeedStyle(
    val surface: Color,
    val border: Color,
    val accent: Color,
    val label: Color,
    val overLimit: Color,
    val shieldFill: Color,
    val shieldRing: Color,
    val shieldText: Color,
) {
    public companion object {
        public val Default: ScoovaSpeedStyle = ScoovaSpeedStyle(
            surface = Color(0xFF0F1421).copy(alpha = 0.96f),
            border = Color.White.copy(alpha = 0.08f),
            accent = Color(0xFF2EA8FF),
            label = Color.White.copy(alpha = 0.55f),
            overLimit = Color(0xFFFF4D4D),
            shieldFill = Color.White,
            shieldRing = Color(0xFFD4233D),
            shieldText = Color.Black,
        )
    }
}
