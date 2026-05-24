package com.scoova.ride

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Atoms lifted from `scoova_app/ui/components/` and adapted to Scoova
 * Ride's RideTokens. Keep each one minimal — these are the building
 * blocks every screen reaches for.
 */

// ─── AppBackground ─────────────────────────────────────────────────────
//
// The signature Scoova vertical-gradient backdrop. Used on every full-
// screen tab (History / Insights / Settings) and behind the Persona
// picker. The Map tab doesn't use it because the map tiles cover the
// screen edge-to-edge.
//
// Safe-area handling: applies `WindowInsets.statusBars` to its content
// by default so screen titles never duck under the system clock /
// signal icons (we use edge-to-edge layout — without this padding the
// title appears at y=0). The floating GlassTabBar at the bottom
// handles its own `WindowInsets.navigationBars` padding so screens
// don't need to think about it.

@Composable
fun AppBackground(
    modifier: Modifier = Modifier,
    applyStatusBarInset: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(RideTokens.AppGradient),
    ) {
        Box(
            modifier = if (applyStatusBarInset)
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
            else
                Modifier.fillMaxSize(),
        ) { content() }
    }
}

// ─── SectionHeader ──────────────────────────────────────────────────────
//
// Title + optional subtitle + optional action affordance. Replaces the
// inline section labels (e.g. "RECENT", "SHORTCUTS") scattered through
// the screens with a single atom.

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    accent: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = if (accent) RideTokens.Accent else RideTokens.Text,
                fontSize = if (accent) 11.sp else 18.sp,
                fontWeight = if (accent) FontWeight.Bold else FontWeight.SemiBold,
                letterSpacing = if (accent) 1.4.sp else 0.sp,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = RideTokens.Muted, fontSize = 12.sp)
            }
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = onAction) {
                Text(actionText, color = RideTokens.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ─── ScoovaModalSheet ──────────────────────────────────────────────────
//
// Thin wrapper over Material3 ModalBottomSheet with our brand colors +
// a custom drag handle. Used for the persona-change flow from Settings
// and any other "pick one thing without leaving the page" interaction.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoovaModalSheet(
    open: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    showDragHandle: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (open) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = state,
            modifier = modifier,
            containerColor = RideTokens.Surface,
            dragHandle = if (showDragHandle) ({ ScoovaDragHandle() }) else null,
            content = content,
        )
    }
}

@Composable
private fun ScoovaDragHandle() {
    Box(
        modifier = Modifier
            .padding(top = 10.dp, bottom = 6.dp)
            .size(width = 40.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(RideTokens.Border)
            .semantics { contentDescription = "Drag handle" },
    )
}
