package com.scoova.ride

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * First-launch onboarding. Three swipeable value-prop cards that
 * answer "why is this different from Google Maps?" — eyes-on-road
 * copy, dialect-aware voice, per-trip persona — before we ask the
 * rider for permissions or a profile pick.
 *
 * Lifecycle:
 *   • Shown when `settings.onboardingDone == false`
 *   • "Skip" or "Get started" both flip the flag → onPersona() runs
 *   • Re-runnable from Settings → "How does Scoova work?"
 *
 * Pages are deliberately three (not five, not seven): one for HOW we
 * speak, one for WHAT language, one for WHO the rider is. More than
 * that gets skipped; less than that doesn't earn the value-prop slot.
 */
@Composable
fun OnboardingScreen(
    locale: String,
    onFinish: () -> Unit,
) {
    // Not wrapped in remember() — the inner @Composable visuals can't be
    // recomposed from a non-composable lambda. Recreating the list per
    // composition is trivial (it's three structs).
    val pages = onboardingPages(locale)
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.0f to RideTokens.Bg,
                    0.5f to Color(0xFF131826),
                    1.0f to RideTokens.Bg,
                ),
            )
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // ── Top row: brand wordmark + Skip ───────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "SCOOVA",
                color = RideTokens.Accent,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clickable { onFinish() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    Strings.t("onboarding.skip", locale),
                    color = RideTokens.Muted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // ── Pager: 3 swipeable value-prop cards ──────────────────────
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { idx ->
            OnboardingCard(page = pages[idx])
        }

        // ── Dots + CTA ───────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 20.dp),
            ) {
                for (i in pages.indices) {
                    val width by animateFloatAsState(
                        targetValue = if (i == pagerState.currentPage) 28f else 8f,
                        animationSpec = tween(durationMillis = 240),
                        label = "dot-width",
                    )
                    Box(
                        modifier = Modifier
                            .height(8.dp)
                            .width(width.dp)
                            .background(
                                if (i == pagerState.currentPage) RideTokens.Accent
                                else RideTokens.Border,
                                CircleShape,
                            ),
                    )
                }
            }

            val onLastPage = pagerState.currentPage == pages.lastIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(20.dp, RoundedCornerShape(28.dp), clip = false)
                    .background(RideTokens.Accent, RoundedCornerShape(28.dp))
                    .clickable {
                        if (onLastPage) onFinish()
                        else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                    .padding(vertical = 18.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (onLastPage) Strings.t("onboarding.start", locale)
                    else Strings.t("onboarding.next", locale),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.3.sp,
                )
                Spacer(Modifier.width(10.dp))
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// ─── A single onboarding page ─────────────────────────────────────────

@Composable
private fun OnboardingCard(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Visual zone — composable that the page brings (sample banner /
        // dialect bubbles / persona grid).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
            contentAlignment = Alignment.Center,
        ) {
            page.visual()
        }

        Spacer(Modifier.height(36.dp))

        Text(
            page.title,
            color = RideTokens.Text,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            lineHeight = 34.sp,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            page.body,
            color = RideTokens.Muted,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ─── Page data + visuals ──────────────────────────────────────────────

private data class OnboardingPage(
    val title: String,
    val body: String,
    val visual: @Composable () -> Unit,
)

@Composable
private fun onboardingPages(locale: String): List<OnboardingPage> = listOf(
    OnboardingPage(
        title = Strings.t("onboarding.p1.title", locale),
        body = Strings.t("onboarding.p1.body", locale),
        visual = { EyesOnRoadVisual(locale) },
    ),
    OnboardingPage(
        title = Strings.t("onboarding.p2.title", locale),
        body = Strings.t("onboarding.p2.body", locale),
        visual = { DialectVisual(locale) },
    ),
    OnboardingPage(
        title = Strings.t("onboarding.p3.title", locale),
        body = Strings.t("onboarding.p3.body", locale),
        visual = { PersonaGridVisual() },
    ),
)

// Visual 1: a mock maneuver banner — the actual eyes-on-road UI the
// rider will see. Showing the real artifact is more convincing than a
// generic illustration.
@Composable
private fun EyesOnRoadVisual(locale: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(24.dp, RoundedCornerShape(22.dp), clip = false)
            .background(Color(0xFF0F1421), RoundedCornerShape(22.dp))
            .border(1.dp, Color(0xFF2D3548), RoundedCornerShape(22.dp))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(RideTokens.Accent.copy(alpha = 0.18f), CircleShape)
                    .border(2.dp, RideTokens.Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("→", color = RideTokens.Accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    Strings.t("onboarding.p1.demo.phase", locale),
                    color = RideTokens.Accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "180 m",
                    color = RideTokens.Accent,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 34.sp,
                )
                Text(
                    Strings.t("onboarding.p1.demo.verb", locale),
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    Strings.t("onboarding.p1.demo.anchor", locale),
                    color = RideTokens.Muted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

// Visual 2: three sample cues stacked, one per dialect, so the rider
// sees their language represented.
@Composable
private fun DialectVisual(locale: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DialectBubble(flag = "🇪🇬", lang = "ar-EG", phrase = "حوّد يمين في الشارع الجاي")
        DialectBubble(flag = "🇺🇸", lang = "en-US", phrase = "Turn right at the next street")
        DialectBubble(flag = "🇫🇷", lang = "fr", phrase = "Tournez à droite à la prochaine rue")
    }
}

@Composable
private fun DialectBubble(flag: String, lang: String, phrase: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RideTokens.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, RideTokens.Border, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(flag, fontSize = 22.sp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                phrase,
                color = RideTokens.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                lang,
                color = RideTokens.Muted,
                fontSize = 10.sp,
            )
        }
    }
}

// Visual 3: 6 profile chips in a 3×2 grid — the same six personas the
// app actually ships. Picks up the per-profile accent so the colour
// palette is honest.
@Composable
private fun PersonaGridVisual() {
    val profiles = Profile.entries
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (row in profiles.chunked(3)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                for (p in row) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(78.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        p.accent.copy(alpha = 0.20f),
                                        RideTokens.Surface,
                                    )
                                ),
                                RoundedCornerShape(14.dp),
                            )
                            .border(1.dp, p.accent.copy(alpha = 0.32f), RoundedCornerShape(14.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(p.emoji, fontSize = 22.sp)
                        Text(
                            p.localizedDisplay(LocalLocale.current),
                            color = RideTokens.Text,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
