package com.scoova.navlayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scoova.navlayer.core.ManeuverEvent
import com.scoova.navlayer.core.ManeuverType
import com.scoova.navlayer.core.ScoovaNavLayer

/**
 * Compact maneuver card — the Apple-Maps / Tesla-style top-left
 * navigation chip. Tighter footprint than [ScoovaManeuverBanner],
 * suited for tilted 3D nav views where the map underneath is the
 * star of the show.
 *
 * Layout: arrow icon on the left in a tinted square, distance + verb
 * stacked on the right. Whole card is fixed-width-ish (max-width
 * caps overflow on long street names) and floats over the map.
 *
 * **Usage**
 * ```kotlin
 * val cue by nav.currentInstruction.collectAsState()
 * cue?.let {
 *     ScoovaCompactManeuverCard(
 *         cue = it,
 *         unitsMetric = state.settings.unitsMetric,
 *         modifier = Modifier
 *             .align(Alignment.TopStart)
 *             .padding(16.dp)
 *     )
 * }
 * ```
 */
@Composable
public fun ScoovaCompactManeuverCard(
    cue: ScoovaNavLayer.DisplayCue,
    modifier: Modifier = Modifier,
    unitsMetric: Boolean = true,
    style: ScoovaCompactStyle = ScoovaCompactStyle.Default,
) {
    val distLabel = formatDistanceCompact(cue.metersToManeuver, unitsMetric)
    val verb = cue.maneuver.bannerVerb?.takeIf { it.isNotBlank() }
        ?: cue.text.takeIf { it.isNotBlank() }
        ?: "Continue"

    Row(
        modifier = modifier
            .widthIn(min = 180.dp, max = 280.dp)
            .shadow(18.dp, RoundedCornerShape(20.dp))
            .background(style.surface, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArrowChip(maneuver = cue.maneuver, tint = style.icon)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                distLabel,
                color = style.distance,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                verb,
                color = style.verb,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun ArrowChip(maneuver: ManeuverEvent, tint: Color) {
    // We have a stable two-icon vocabulary in the SDK:
    //   • RotateLeft / RotateRight for soft turns and U-turns
    //   • ArrowUpward (rotated) for hard turns + straight
    //   • Flag for the arrive maneuver
    // Hard turns render as an up-arrow rotated 90° rather than
    // pulling in TurnLeft / TurnRight icons that aren't in every
    // material-icons version.
    val (icon, rotation) = when (maneuver.type) {
        ManeuverType.Left, ManeuverType.SharpLeft     -> Icons.Filled.ArrowUpward to -90f
        ManeuverType.Right, ManeuverType.SharpRight   -> Icons.Filled.ArrowUpward to 90f
        ManeuverType.SlightLeft, ManeuverType.StayLeft   -> Icons.AutoMirrored.Filled.RotateLeft to 0f
        ManeuverType.SlightRight, ManeuverType.StayRight -> Icons.AutoMirrored.Filled.RotateRight to 0f
        ManeuverType.Uturn  -> Icons.AutoMirrored.Filled.RotateLeft to 0f
        ManeuverType.Arrive -> Icons.Filled.Flag to 0f
        else                -> Icons.Filled.ArrowUpward to 0f
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(32.dp).rotate(rotation),
    )
}

private fun formatDistanceCompact(meters: Double, metric: Boolean): String {
    if (metric) {
        if (meters < 1000) return "${meters.toInt()} m"
        return "%.1f km".format(meters / 1000.0)
    }
    val mi = meters * 0.000621371
    if (mi < 0.1) return "${(meters * 3.28084).toInt()} ft"
    return "%.1f mi".format(mi)
}

/** Theme tokens for [ScoovaCompactManeuverCard]. */
public data class ScoovaCompactStyle(
    val surface: Color,
    val icon: Color,
    val distance: Color,
    val verb: Color,
) {
    public companion object {
        public val Default: ScoovaCompactStyle = ScoovaCompactStyle(
            surface = Color(0xFF2E72FF),    // Apple-Maps-style blue
            icon = Color.White,
            distance = Color.White,
            verb = Color.White.copy(alpha = 0.92f),
        )
    }
}
