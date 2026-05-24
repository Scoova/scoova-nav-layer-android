package com.scoova.navlayer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.scoova.navlayer.core.ScoovaNavLayer
import androidx.compose.runtime.getValue

/**
 * Cyan-cone heading puck with a soft fade. Place on top of your map at
 * the user's screen position. Bind [headingDeg] to
 * [ScoovaNavLayer.headingDeg] for the sensor-driven rotation.
 *
 * Phone-in-pocket riders get a clear "I'm facing this way" cue even
 * when stationary — GPS bearing only kicks in above ~5 km/h.
 */
@Composable
public fun ScoovaHeadingPuck(
    headingDeg: Float,
    modifier: Modifier = Modifier,
    coneColor: Color = Color(0xFF0EA5E9),
    dotColor: Color = Color(0xFF0EA5E9),
    sizeDp: Int = 96,
) {
    val animated by animateFloatAsState(targetValue = headingDeg, label = "puck-heading")
    Canvas(modifier = modifier.size(sizeDp.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        rotate(degrees = animated, pivot = Offset(cx, cy)) {
            // Cone — gradient cyan fading up
            val r = size.minDimension * 0.45f
            val left = cx - r * 0.45f
            val right = cx + r * 0.45f
            val top = cy - r
            val cone = Path().apply {
                moveTo(cx, cy)
                lineTo(left, top)
                lineTo(right, top)
                close()
            }
            drawPath(
                path = cone,
                brush = Brush.verticalGradient(
                    colors = listOf(coneColor.copy(alpha = 0.9f), coneColor.copy(alpha = 0f)),
                    startY = cy, endY = top,
                ),
            )
        }
        // Halo
        drawCircle(coneColor.copy(alpha = 0.18f), radius = size.minDimension * 0.18f, center = Offset(cx, cy))
        // Dot
        drawCircle(Color.White, radius = size.minDimension * 0.085f, center = Offset(cx, cy))
        drawCircle(dotColor, radius = size.minDimension * 0.07f, center = Offset(cx, cy))
    }
}
