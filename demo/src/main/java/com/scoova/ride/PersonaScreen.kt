package com.scoova.ride

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * First-run screen — six movers in a 2×3 grid, tap to pick.
 *
 * Why this screen exists: a runner doesn't want "in 800 m turn left" cues,
 * and a driver doesn't want a 50 m one. The picker maps the user to a
 * routing profile, default cue tone, and accent color in one tap.
 *
 * If the user has already picked in a previous session, the activity skips
 * this screen entirely — they go straight to Plan.
 */
@Composable
fun PersonaScreen(onPick: (Profile) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(RideTokens.Bg, RideTokens.Surface)
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 22.dp, vertical = 28.dp),
    ) {
        // Top header
        Spacer(Modifier.height(28.dp))
        Text(
            rideString("persona.title"),
            color = RideTokens.Text,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            lineHeight = 38.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            rideString("persona.subtitle"),
            color = RideTokens.Muted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )

        Spacer(Modifier.height(28.dp))

        // 2×3 grid of profile cards
        val all = Profile.entries
        val rows = all.chunked(2)
        for ((rIdx, row) in rows.withIndex()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (p in row) {
                    ProfileCard(profile = p, onClick = { onPick(p) }, modifier = Modifier.weight(1f))
                }
                // Pad odd rows so cards stay equal width
                if (row.size < 2) Spacer(Modifier.weight(1f))
            }
            if (rIdx < rows.lastIndex) Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.weight(1f))

        // Footer hint
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                rideString("persona.continue"),
                color = RideTokens.Muted,
                fontSize = 12.sp,
                letterSpacing = 1.4.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = RideTokens.Muted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(150.dp)
            .background(
                Brush.verticalGradient(
                    listOf(
                        profile.accent.copy(alpha = 0.16f),
                        RideTokens.Surface,
                    )
                ),
                RoundedCornerShape(18.dp),
            )
            .border(
                width = 1.dp,
                color = profile.accent.copy(alpha = 0.32f),
                shape = RoundedCornerShape(18.dp),
            )
            .clickable { onClick() }
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(profile.emoji, fontSize = 38.sp)
        Column {
            val locale = LocalLocale.current
            Text(
                profile.localizedDisplay(locale),
                color = RideTokens.Text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                profile.localizedTagline(locale),
                color = RideTokens.Muted,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 2,
            )
        }
    }
}
