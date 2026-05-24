package com.scoova.navlayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scoova.navlayer.core.CuePhrases
import com.scoova.navlayer.core.ManeuverEvent
import com.scoova.navlayer.core.ManeuverType
import com.scoova.navlayer.core.ScoovaNavLayer

/**
 * Drop-in maneuver banner. Bind it to [ScoovaNavLayer.currentInstruction]:
 *
 * ```kotlin
 * val cue by nav.currentInstruction.collectAsStateWithLifecycle()
 * cue?.let { ScoovaManeuverBanner(it, modifier = Modifier.padding(16.dp)) }
 * ```
 *
 * Default styling matches Scoova's design language (dark surface, cyan
 * accent). Override colors via [ScoovaBannerStyle] for white-label tier.
 */
@Composable
public fun ScoovaManeuverBanner(
    cue: ScoovaNavLayer.DisplayCue,
    modifier: Modifier = Modifier,
    style: ScoovaBannerStyle = ScoovaBannerStyle.Default,
    showPhase: Boolean = true,
) {
    // Eyes-on-the-road hierarchy:
    //   • Arrow chip (left, big) — the "what to do" shape
    //   • Distance (top-right, BIGGEST) — the only number a glance needs
    //   • Verb (below distance, bold) — server-rendered short action
    //     ("Turn right" / "حوّد يمين"), NEVER the verbose Valhalla string
    //   • Anchor (under verb, muted) — landmark cue when present
    //     ("after the gas station" / "بعد البنزينة"), null otherwise
    //   • Phase label (top, tiny caps) — distance-driven "GET READY / NEXT / NOW"
    //
    // Text source priority:
    //   1. cue.maneuver.bannerVerb (server's scoova.banner.verb — the
    //      canonical eyes-on-road copy, identical across all 5 SDKs)
    //   2. cue.text (legacy / third-party adapters that don't ship a
    //      scoova block — falls back to dialect-aware synthesized phrase)
    val hasDistance = !cue.metersToManeuver.isNaN() && cue.metersToManeuver > 5
    // Urgency is driven by distance, not by CuePhrases.Phase — that
    // enum reads "Near" for every idle frame before any threshold has
    // fired (firedThresholdM coerced to 1), so it can't drive UI color.
    val nearAccent = hasDistance && cue.metersToManeuver < 80
    val accentColor = if (nearAccent) style.urgent else style.accent
    val primary = cue.maneuver.bannerVerb?.takeIf { it.isNotBlank() } ?: cue.text
    val anchor = cue.maneuver.bannerAnchor?.takeIf { it.isNotBlank() }
    Row(
        modifier = modifier
            .shadow(20.dp, RoundedCornerShape(22.dp))
            .background(style.surface, RoundedCornerShape(22.dp))
            .border(1.dp, style.border, RoundedCornerShape(22.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArrowChip(cue.maneuver.type, accentColor)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (showPhase) {
                Text(
                    distancePhaseLabel(cue.metersToManeuver),
                    color = if (nearAccent) accentColor else style.muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                )
                Spacer(Modifier.height(2.dp))
            }
            if (hasDistance) {
                Text(
                    formatDistance(cue.metersToManeuver),
                    color = accentColor,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 36.sp,
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                primary,
                color = style.text,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                lineHeight = 24.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (anchor != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    anchor,
                    color = style.muted,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ArrowChip(type: ManeuverType, color: Color) {
    val icon: ImageVector = when (type) {
        ManeuverType.SlightRight, ManeuverType.Right, ManeuverType.SharpRight,
        ManeuverType.RampRight, ManeuverType.ExitRight, ManeuverType.StayRight -> Icons.AutoMirrored.Filled.RotateRight
        ManeuverType.SlightLeft, ManeuverType.Left, ManeuverType.SharpLeft,
        ManeuverType.RampLeft, ManeuverType.ExitLeft, ManeuverType.StayLeft -> Icons.AutoMirrored.Filled.RotateLeft
        ManeuverType.Uturn -> Icons.Default.Refresh
        ManeuverType.Arrive -> Icons.Default.Flag
        else -> Icons.Default.ArrowUpward
    }
    Box(
        modifier = Modifier
            .size(60.dp)
            .background(color.copy(alpha = 0.15f), CircleShape)
            .border(2.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(34.dp))
    }
}

private fun phaseLabel(phase: CuePhrases.Phase): String = when (phase) {
    CuePhrases.Phase.Far -> "GET READY"
    CuePhrases.Phase.Mid -> "NEXT"
    CuePhrases.Phase.Near -> "NOW"
}

// Distance-driven urgency for the banner. The core's CuePhrases.Phase is
// for the voice engine — it coerces firedThresholdM to 1 in idle frames,
// which makes phase=Near fire for the entire ride before any threshold
// is crossed. Visually we just want a coherent "how close is the turn"
// indicator. 80m = within the visual horizon, 250m = next major move.
private fun distancePhaseLabel(m: Double): String = when {
    m.isNaN() || m <= 0 -> "NEXT"
    m < 80              -> "NOW"
    m < 250             -> "NEXT"
    else                -> "GET READY"
}

private fun formatDistance(m: Double): String = when {
    m < 50 -> "${m.toInt()} m"
    m < 1000 -> "${m.toInt()} m"
    else -> "${"%.1f".format(m / 1000)} km"
}

/** Theming hook. Override on white-label tier; defaults match Scoova brand. */
public data class ScoovaBannerStyle(
    val surface: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    /** Distance + arrow color when the maneuver is imminent (Phase.Near). */
    val urgent: Color = accent,
) {
    public companion object {
        public val Default: ScoovaBannerStyle = ScoovaBannerStyle(
            surface = Color(0xFF0F1421),
            border  = Color(0xFF2D3548),
            text    = Color(0xFFFFFFFF),
            muted   = Color(0xFF94A3B8),
            accent  = Color(0xFF0EA5E9),
            urgent  = Color(0xFFFB7C2C),  // scoova orange — match brand pulse
        )
        public val Light: ScoovaBannerStyle = ScoovaBannerStyle(
            surface = Color(0xFFFFFFFF),
            border  = Color(0xFFE5E7EB),
            text    = Color(0xFF0F0F0F),
            muted   = Color(0xFF6B7280),
            accent  = Color(0xFF0EA5E9),
            urgent  = Color(0xFFFB7C2C),
        )
    }
}
