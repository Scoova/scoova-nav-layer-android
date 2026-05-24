package com.scoova.ride

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(rides: List<RideRecord>) {
    var selectedRide by remember { mutableStateOf<RideRecord?>(null) }
    AppBackground {
        HistoryContent(rides, onPickRide = { selectedRide = it })
    }
    // Full-screen detail overlay. Renders above the list when a row is
    // tapped; back/close dismisses. Done inside HistoryScreen rather
    // than via Nav so we don't introduce a routing dependency just for
    // this one drill-down.
    selectedRide?.let { ride ->
        AppBackground {
            RideDetailContent(ride = ride, onClose = { selectedRide = null })
        }
    }
}

@Composable
private fun HistoryContent(rides: List<RideRecord>, onPickRide: (RideRecord) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Title (no back-arrow — tab bar handles navigation)
        Column(modifier = Modifier.padding(start = 18.dp, top = 24.dp, bottom = 6.dp)) {
            Text(rideString("history.title"), color = RideTokens.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                rideString("history.count", "n" to rides.size.toString()),
                color = RideTokens.Muted, fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(8.dp))

        if (rides.isEmpty()) {
            EmptyHistory()
        } else {
            // Roll-up totals
            val totalKm = rides.sumOf { it.coveredKm }
            val totalMin = rides.sumOf { (it.endedAtMs - it.startedAtMs) / 60_000L }.coerceAtLeast(0L)
            Row(
                modifier = Modifier
                    .padding(horizontal = 14.dp)
                    .fillMaxWidth()
                    .background(RideTokens.Surface, RoundedCornerShape(14.dp))
                    .border(1.dp, RideTokens.Border, RoundedCornerShape(14.dp))
                    .padding(16.dp),
            ) {
                TotalsCell(label = rideString("history.total_distance"), value = "%.1f km".format(totalKm), tint = RideTokens.Cyan2, modifier = Modifier.weight(1f))
                TotalsCell(label = rideString("history.total_time"), value = "${totalMin} min", tint = RideTokens.Sun, modifier = Modifier.weight(1f))
                TotalsCell(label = rideString("history.rides_label"), value = "${rides.size}", tint = RideTokens.Lime, modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(20.dp))

            for ((i, ride) in rides.withIndex()) {
                RideRow(ride, onClick = { onPickRide(ride) })
                if (i < rides.lastIndex) Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(110.dp))  // clear floating tab bar
    }
}

@Composable
private fun TotalsCell(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, color = RideTokens.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, color = tint, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RideRow(ride: RideRecord, onClick: () -> Unit) {
    val accent = ride.profile?.accent ?: RideTokens.Cyan
    val emoji = ride.profile?.emoji ?: "🚴"
    val dateStr = remember(ride.startedAtMs) {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ride.startedAtMs))
    }
    Row(
        modifier = Modifier
            .padding(horizontal = 14.dp)
            .fillMaxWidth()
            .background(RideTokens.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, accent.copy(alpha = 0.32f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(46.dp).background(RideTokens.Surface2, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(emoji, fontSize = 22.sp) }

        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                ride.destinationLabel ?: rideString("summary.fallback_label"),
                color = RideTokens.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(dateStr, color = RideTokens.Muted, fontSize = 11.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("%.1f km".format(ride.coveredKm), color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(rideString("history.avg", "kph" to ride.avgKph.toString()), color = RideTokens.Muted, fontSize = 11.sp)
        }
    }
}

/**
 * Full-screen detail view for a single ride. Shows the breadcrumb
 * trace on a map, distance / time / avg-speed / calories / CO₂
 * stats, notes (if any), and a share button that fires an
 * Intent.ACTION_SEND with the ride summary.
 *
 * Rendered as an overlay above [HistoryContent] when a row is tapped;
 * close button dismisses back to the list.
 */
@Composable
private fun RideDetailContent(ride: RideRecord, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val accent = ride.profile?.accent ?: RideTokens.Cyan
    val durationMin = ((ride.endedAtMs - ride.startedAtMs) / 60_000L).toInt().coerceAtLeast(1)
    val dateStr = remember(ride.startedAtMs) {
        SimpleDateFormat("EEEE, MMM d · h:mm a", Locale.getDefault()).format(Date(ride.startedAtMs))
    }
    val mode = (ride.profile ?: Profile.Bicycle).metricsMode
    val calories = com.scoova.navlayer.core.RideMetrics.caloriesBurned(
        mode = mode, distanceKm = ride.coveredKm, durationMinutes = durationMin,
    )
    val co2 = com.scoova.navlayer.core.RideMetrics.co2SavedGrams(mode, ride.coveredKm)
    val path = remember(ride.id) { ride.decodedPath() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 110.dp),
    ) {
        // Header strip — close (X) + title + share. Mirrors the
        // detail-view convention every messaging app uses.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(RideTokens.Surface, CircleShape)
                    .border(1.dp, RideTokens.Border, CircleShape)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Close, null, tint = RideTokens.Muted, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    ride.destinationLabel ?: rideString("summary.fallback_label"),
                    color = RideTokens.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(dateStr, color = RideTokens.Muted, fontSize = 12.sp)
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(RideTokens.Surface, CircleShape)
                    .border(1.dp, RideTokens.Border, CircleShape)
                    .clickable {
                        shareRide(ctx, ride, calories, co2)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Share, null, tint = accent, modifier = Modifier.size(18.dp))
            }
        }

        // Map preview — the actual GPS trace from this ride.
        if (path.size >= 2) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .padding(horizontal = 14.dp)
                    .shadow(20.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(20.dp)),
            ) {
                RideMap(
                    modifier = Modifier.fillMaxSize(),
                    data = RideMapData(
                        userLat = path.first()[0],
                        userLon = path.first()[1],
                        destLat = ride.destLat ?: path.last()[0],
                        destLon = ride.destLon ?: path.last()[1],
                        routeShape = path,
                        followUser = false,
                    ),
                )
            }
            Spacer(Modifier.height(18.dp))
        }

        // Stat tiles — distance / duration / avg / calories / CO₂.
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp)
                .fillMaxWidth()
                .background(RideTokens.Surface, RoundedCornerShape(16.dp))
                .border(1.dp, RideTokens.Border, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            TotalsCell(label = "Distance", value = "%.1f km".format(ride.coveredKm), tint = accent, modifier = Modifier.weight(1f))
            TotalsCell(label = "Time", value = "$durationMin min", tint = RideTokens.Sun, modifier = Modifier.weight(1f))
            TotalsCell(label = "Avg", value = "${ride.avgKph} km/h", tint = RideTokens.Lime, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp)
                .fillMaxWidth()
                .background(RideTokens.Surface, RoundedCornerShape(16.dp))
                .border(1.dp, RideTokens.Border, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            TotalsCell(label = "Calories", value = "$calories kcal", tint = Color(0xFFFF6A00), modifier = Modifier.weight(1f))
            if (co2 > 0) {
                val co2Label = if (co2 >= 1000) "%.1f kg".format(co2 / 1000.0) else "$co2 g"
                TotalsCell(label = "CO₂ saved", value = co2Label, tint = Color(0xFF22C55E), modifier = Modifier.weight(1f))
            }
            TotalsCell(label = "Mode", value = ride.profile?.display ?: "—", tint = RideTokens.Cyan2, modifier = Modifier.weight(1f))
        }

        // Notes if any.
        if (!ride.notes.isNullOrBlank()) {
            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier
                    .padding(horizontal = 14.dp)
                    .fillMaxWidth()
                    .background(RideTokens.Surface, RoundedCornerShape(14.dp))
                    .border(1.dp, RideTokens.Border, RoundedCornerShape(14.dp))
                    .padding(16.dp),
            ) {
                Text(
                    "Notes",
                    color = RideTokens.Cyan2, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(ride.notes, color = RideTokens.Text, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
}

private fun shareRide(
    ctx: android.content.Context,
    ride: RideRecord,
    calories: Int,
    co2Grams: Int,
) {
    val date = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ride.startedAtMs))
    val co2Bit = if (co2Grams > 0) {
        if (co2Grams >= 1000) ", saved %.1f kg CO₂".format(co2Grams / 1000.0)
        else ", saved $co2Grams g CO₂"
    } else ""
    val body = "Scoova ride on $date — %.1f km in ${(ride.endedAtMs - ride.startedAtMs) / 60_000L} min ($calories kcal$co2Bit)"
        .format(ride.coveredKm)
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, "My Scoova ride")
        putExtra(android.content.Intent.EXTRA_TEXT, body)
    }
    ctx.startActivity(
        android.content.Intent.createChooser(send, "Share ride").apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}

@Composable
private fun EmptyHistory() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🚴", fontSize = 48.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            rideString("history.empty.title"),
            color = RideTokens.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            rideString("history.empty.body"),
            color = RideTokens.Muted,
            fontSize = 13.sp,
        )
    }
}
