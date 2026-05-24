package com.scoova.ride

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Scoova brand — pulled verbatim from scoova_app/ThemeCatalog.scoova().dark.
// Orange-on-ink is the brand. Cyan is reserved for the NAV route color only,
// not as a generic UI accent. Read the file's commit notes before swapping
// these for any other palette — the brand has lived here for a while.
object RideTokens {
    // Surface stack — near-black → ink, with a subtle vertical gradient
    val BgTop      = Color(0xFF000000)
    val BgBottom   = Color(0xFF0A0A14)
    val Surface    = Color(0xFF1A1A1D)
    val Surface2   = Color(0xFF22232A)
    val Surface3   = Color(0xFF2C2D35)
    val Border     = Color(0x26FFFFFF)        // 15% white
    val Text       = Color(0xFFFFFFFF)
    val TextMuted  = Color(0xB3FFFFFF)         // 70% white
    val Muted      = Color(0xFF8A8E96)

    // Brand orange — primary action, accent, persona pills
    val Accent     = Color(0xFFFF6A00)
    val AccentAlt  = Color(0xFFFF3D00)
    val AccentSoft = Color(0xFFFF8B40)

    // Map route — cyan, used only on the polyline and turn arrow
    val RouteCore  = Color(0xFF2EA8FF)
    val RouteHalo  = Color(0x552EA8FF)

    // Status
    val Success    = Color(0xFF22C55E)
    val Warning    = Color(0xFFF59E0B)
    val Error      = Color(0xFFEF4444)

    // Backwards-compat aliases — older screens reference these. Map to the
    // new brand so we don't touch 12 files for a one-token rename.
    val Bg         = BgTop
    val Cyan       = Accent
    val Cyan2      = AccentSoft
    val Lime       = Success
    val Sun        = Warning
    val Red        = Error

    // The app's signature background gradient — used by AppBackground.
    val AppGradient: Brush = Brush.verticalGradient(listOf(BgTop, BgBottom))

    // Primary action button gradient (orange horizontal).
    val PrimaryButton: Brush = Brush.horizontalGradient(listOf(Accent, AccentAlt))
}

val RideColors = darkColorScheme(
    primary       = RideTokens.Accent,
    onPrimary     = Color.White,
    secondary     = RideTokens.AccentAlt,
    onSecondary   = Color.White,
    background    = RideTokens.BgTop,
    onBackground  = RideTokens.Text,
    surface       = RideTokens.Surface,
    onSurface     = RideTokens.Text,
    surfaceVariant   = RideTokens.Surface2,
    onSurfaceVariant = RideTokens.Muted,
    outline       = RideTokens.Border,
    error         = RideTokens.Error,
)
