package com.scoova.navlayer.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Banner that prompts the rider to calibrate the device compass when
 * the OS reports the magnetometer accuracy as `LOW` or `UNRELIABLE`.
 *
 * Why this is needed: a phone whose compass hasn't been calibrated
 * recently (or that's sitting near a metal frame, magnet, or speaker)
 * reports headings that drift by 20–90°. The nav layer's heading-up
 * mode then constantly snaps to a wrong bearing, which is more
 * disorienting than no follow-mode at all. Surfacing the prompt and
 * teaching the rider the figure-8 wave fixes the underlying issue —
 * and once accuracy climbs back to MEDIUM/HIGH, this banner hides.
 *
 * The animated infinity-eight loops to demonstrate the motion. It's
 * a CSS-style trick: rotate a small dot through one cycle every 1.6 s
 * on a path drawn via `rotate` deg + small offset — no Canvas needed.
 */
@Composable
public fun ScoovaCompassCalibrationBanner(
    modifier: Modifier = Modifier,
    style: ScoovaCalibrationStyle = ScoovaCalibrationStyle.Default,
) {
    val infinite = rememberInfiniteTransition(label = "compass-cal")
    val angle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(14.dp, RoundedCornerShape(16.dp))
            .background(style.surface, RoundedCornerShape(16.dp))
            .border(1.dp, style.accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Mini "figure-8" indicator on the left. Single dot orbiting a
        // small circle is enough to suggest motion without leaning on
        // Canvas for a real lemniscate path.
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(style.accent.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
                .border(1.dp, style.accent, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .rotate(angle),
            ) {
                // Dot at the top of the rotation circle — visually
                // orbits the center as `angle` advances.
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(6.dp)
                        .align(Alignment.TopCenter)
                        .background(style.accent, RoundedCornerShape(3.dp)),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Calibrate compass",
                color = style.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Wave your phone in a figure-8 motion",
                color = style.body,
                fontSize = 11.sp,
            )
        }
    }
}

public data class ScoovaCalibrationStyle(
    val surface: Color,
    val accent: Color,
    val title: Color,
    val body: Color,
) {
    public companion object {
        public val Default: ScoovaCalibrationStyle = ScoovaCalibrationStyle(
            surface = Color(0xFF0F1421).copy(alpha = 0.96f),
            accent = Color(0xFFFF6A00),
            title = Color.White,
            body = Color.White.copy(alpha = 0.7f),
        )
    }
}
