package com.scoova.navlayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scoova.navlayer.core.WeatherClient
import kotlinx.coroutines.flow.StateFlow

/**
 * Auto-fetches weather for the user's current location and shows a slim
 * banner — but only when there's something worth saying ("rain ahead",
 * "fog", "high wind for cyclists"). Stays out of the way otherwise.
 *
 * Bind it to your live location flow; it polls every 10 minutes max.
 */
@Composable
public fun ScoovaWeatherBanner(
    apiKey: String,
    locationFlow: StateFlow<Pair<Double, Double>?>,
    profile: String,
    modifier: Modifier = Modifier,
) {
    val client = remember(apiKey) { WeatherClient(apiKey) }
    var snapshot by remember { mutableStateOf<WeatherClient.Snapshot?>(null) }
    val location by locationFlow.collectAsState()

    LaunchedEffect(location?.let { (it.first * 50).toInt() to (it.second * 50).toInt() }) {
        val l = location ?: return@LaunchedEffect
        snapshot = client.current(l.first, l.second)
    }
    val s = snapshot ?: return
    val msg = banner(s, profile) ?: return  // hide unless meaningful

    Row(
        modifier = modifier
            .background(Color(0xFFFEF3C7), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(msg.icon, fontSize = 18.sp)
        Spacer(Modifier.width(8.dp))
        Text(msg.text, color = Color(0xFF7C2D12), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

private data class BannerMsg(val icon: String, val text: String)

/**
 * Decide whether the weather is worth surfacing. Skip on bluebird-clear
 * conditions — the banner exists to *interrupt* useful, not babysit.
 *
 * Profile-aware: cyclist + 50 km/h wind = warning; driver + same = ignored.
 */
private fun banner(s: WeatherClient.Snapshot, profile: String): BannerMsg? {
    if (s.isThunder) return BannerMsg("⛈", "Thunderstorm in your area — consider waiting it out")
    if (s.isRainOrSnow) return BannerMsg("🌧", "Rain in your area — slow down for braking distance")
    if (s.isFog) return BannerMsg("🌫", "Fog — visibility is reduced, be cautious")
    val wind = s.windKph ?: 0.0
    val windThreshold = when (profile) {
        "bicycle", "scooter" -> 30.0
        "motor_scooter", "motorcycle" -> 45.0
        "pedestrian" -> 50.0
        else -> 70.0
    }
    if (wind >= windThreshold) {
        return BannerMsg("🌬", "High wind (${wind.toInt()} km/h) — be careful")
    }
    return null
}
