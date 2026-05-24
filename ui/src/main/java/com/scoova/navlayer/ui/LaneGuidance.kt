package com.scoova.navlayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A single lane's recommended action. Mirrors what a lane-guidance
 * banner in Google Maps / Waze shows — five practical directions are
 * enough to cover ~99% of real-world signage.
 */
public enum class ScoovaLaneAction { Left, SlightLeft, Straight, SlightRight, Right }

/**
 * Lane guidance entry: what the lane CAN do, and whether the rider
 * SHOULD pick this lane for the upcoming maneuver. A lane often
 * supports multiple actions (e.g. "straight or right") — pass the
 * primary one as [primary], use [valid] = true when this lane is one
 * of the legal choices for the next turn.
 */
public data class ScoovaLane(
    public val primary: ScoovaLaneAction,
    /** True when this lane is one of the lanes the rider should be in. */
    public val valid: Boolean,
)

/**
 * Multi-arrow lane guidance pill. Renders a horizontal strip of arrow
 * icons, brightening the lanes the rider should pick. Designed to sit
 * just under the maneuver banner during the "GET READY" phase.
 *
 * Empty [lanes] hides the pill (renders nothing), so callers can keep
 * the composition always-on and let the data control visibility.
 *
 * **Usage**
 * ```kotlin
 * ScoovaLaneGuidance(
 *     lanes = listOf(
 *         ScoovaLane(ScoovaLaneAction.Left,        valid = false),
 *         ScoovaLane(ScoovaLaneAction.Straight,    valid = true),
 *         ScoovaLane(ScoovaLaneAction.SlightRight, valid = true),
 *         ScoovaLane(ScoovaLaneAction.Right,       valid = false),
 *     ),
 * )
 * ```
 */
@Composable
public fun ScoovaLaneGuidance(
    lanes: List<ScoovaLane>,
    modifier: Modifier = Modifier,
    style: ScoovaLaneStyle = ScoovaLaneStyle.Default,
) {
    if (lanes.isEmpty()) return
    Row(
        modifier = modifier
            .shadow(14.dp, RoundedCornerShape(18.dp))
            .background(style.surface, RoundedCornerShape(18.dp))
            .border(1.dp, style.border, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        lanes.forEach { lane ->
            LaneArrow(
                action = lane.primary,
                emphasized = lane.valid,
                style = style,
            )
        }
    }
}

@Composable
private fun LaneArrow(
    action: ScoovaLaneAction,
    emphasized: Boolean,
    style: ScoovaLaneStyle,
) {
    val tint = if (emphasized) style.activeTint else style.inactiveTint
    val rotation = when (action) {
        ScoovaLaneAction.Left -> -90f
        ScoovaLaneAction.SlightLeft -> -45f
        ScoovaLaneAction.Straight -> 0f
        ScoovaLaneAction.SlightRight -> 45f
        ScoovaLaneAction.Right -> 90f
    }
    // Box gives the arrow a fixed footprint so all lanes line up
    // regardless of which direction the arrow points.
    Box(
        modifier = Modifier.size(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        // We use ArrowForward at "up" rotation (the icon points right
        // natively, so we rotate -90° to make "Straight" mean "up").
        // -90° offset baked into the per-action map above gives:
        //   Left      → -180° from natural (arrow pointing left)
        //   Straight  → -90°  (arrow up)
        //   Right     →  0°   (arrow right; natural)
        // Net rotation: action rotation - 90°.
        Icon(
            Icons.Default.ArrowForward,
            contentDescription = action.name,
            tint = tint,
            modifier = Modifier
                .size(20.dp)
                .rotate(rotation - 90f),
        )
    }
}

/** Theme tokens for [ScoovaLaneGuidance]. */
public data class ScoovaLaneStyle(
    val surface: Color,
    val border: Color,
    val activeTint: Color,
    val inactiveTint: Color,
) {
    public companion object {
        public val Default: ScoovaLaneStyle = ScoovaLaneStyle(
            surface = Color(0xFF0F1421).copy(alpha = 0.96f),
            border = Color.White.copy(alpha = 0.08f),
            activeTint = Color(0xFF2EA8FF),
            inactiveTint = Color.White.copy(alpha = 0.25f),
        )
    }
}
