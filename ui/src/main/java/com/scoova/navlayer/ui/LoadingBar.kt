package com.scoova.navlayer.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A small indeterminate-progress banner — used by host apps to surface
 * "we're doing something" states the rider would otherwise read as
 * "the app is stuck". Two flavours:
 *   * [ScoovaLoadingBanner] — full-width pill with label + spinner.
 *   * [ScoovaLoadingDot] — bare 18 dp spinner alone, for inline use
 *     inside another row.
 *
 * Both use a hand-rolled rotating-arc Canvas instead of Material's
 * CircularProgressIndicator so the animation tone matches Scoova's
 * brand cyan and the diameter is controllable without theming.
 */
@Composable
public fun ScoovaLoadingBanner(
    label: String,
    modifier: Modifier = Modifier,
    style: ScoovaLoadingStyle = ScoovaLoadingStyle.Default,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(16.dp))
            .background(style.surface, RoundedCornerShape(16.dp))
            .border(1.dp, style.accent.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScoovaLoadingDot(style = style)
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            color = style.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * 18 dp rotating arc — slot anywhere a small "loading" indicator
 * needs to live. Burns ~one Compose frame per 32 ms via the
 * infinite transition; safe to use multiple at once without a
 * battery hit.
 */
@Composable
public fun ScoovaLoadingDot(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 18.dp,
    style: ScoovaLoadingStyle = ScoovaLoadingStyle.Default,
) {
    val infinite = rememberInfiniteTransition(label = "loading-dot")
    val angle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = Stroke(width = (size.toPx() * 0.18f), cap = StrokeCap.Round)
            val inset = stroke.width / 2f
            // Faint full ring underneath — gives the arc context so a
            // brain reading the spinner doesn't process it as a
            // disconnected stray arc.
            drawArc(
                color = style.accent.copy(alpha = 0.18f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - inset * 2, this.size.height - inset * 2),
                style = stroke,
            )
            // Bright sweeping arc — 280° of fill rotating once / 900 ms.
            drawArc(
                brush = Brush.sweepGradient(
                    0f to style.accent.copy(alpha = 0f),
                    1f to style.accent,
                ),
                startAngle = angle,
                sweepAngle = 280f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - inset * 2, this.size.height - inset * 2),
                style = stroke,
            )
        }
    }
}

public data class ScoovaLoadingStyle(
    val surface: Color,
    val accent: Color,
    val text: Color,
) {
    public companion object {
        public val Default: ScoovaLoadingStyle = ScoovaLoadingStyle(
            surface = Color(0xFF0F1421).copy(alpha = 0.96f),
            accent = Color(0xFF2EA8FF),
            text = Color.White,
        )
    }
}
