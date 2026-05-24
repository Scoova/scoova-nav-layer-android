package com.scoova.ride

import android.os.Build
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(
    profile: Profile?,
    settings: RideSettings,
    onChangeProfile: () -> Unit,
    onUnitsChange: (Boolean) -> Unit,
    onLocaleChange: (String) -> Unit,
    onVoiceToggle: (Boolean) -> Unit,
    onSpatialToggle: (Boolean) -> Unit,
    onSpatialTest: () -> Unit,
    onVoicePreview: () -> Unit = {},
    onWeightChange: (Int) -> Unit = {},
    onAutoMapThemeChange: (Boolean) -> Unit = {},
    onClearAllData: () -> Unit = {},
    onExportData: () -> Unit = {},
    onReplayOnboarding: () -> Unit = {},
    onEyesOffToggle: (Boolean) -> Unit = {},
    signedIn: com.scoova.ride.cloud.AuthCredentials? = null,
    onStartAuth: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onSaveModeChange: (SaveMode) -> Unit = {},
    onRecordRidesToggle: (Boolean) -> Unit = {},
    onStartTour: () -> Unit = {},
) {
    AppBackground {
        SettingsContent(profile, settings, onChangeProfile, onUnitsChange,
            onLocaleChange, onVoiceToggle, onSpatialToggle, onSpatialTest,
            onVoicePreview, onWeightChange, onAutoMapThemeChange,
            onClearAllData, onExportData, onReplayOnboarding,
            onEyesOffToggle, signedIn, onStartAuth, onSignOut, onSaveModeChange,
            onRecordRidesToggle, onStartTour)
    }
}

@Composable
private fun SettingsContent(
    profile: Profile?,
    settings: RideSettings,
    onChangeProfile: () -> Unit,
    onUnitsChange: (Boolean) -> Unit,
    onLocaleChange: (String) -> Unit,
    onVoiceToggle: (Boolean) -> Unit,
    onSpatialToggle: (Boolean) -> Unit,
    onSpatialTest: () -> Unit,
    onVoicePreview: () -> Unit,
    onWeightChange: (Int) -> Unit,
    onAutoMapThemeChange: (Boolean) -> Unit,
    onClearAllData: () -> Unit,
    onExportData: () -> Unit,
    onReplayOnboarding: () -> Unit,
    onEyesOffToggle: (Boolean) -> Unit = {},
    signedIn: com.scoova.ride.cloud.AuthCredentials? = null,
    onStartAuth: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onSaveModeChange: (SaveMode) -> Unit = {},
    onRecordRidesToggle: (Boolean) -> Unit = {},
    onStartTour: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Title (no back-arrow — tab bar handles navigation)
        Text(
            Strings.t("settings.title", settings.locale),
            color = RideTokens.Text,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 18.dp, top = 24.dp, bottom = 6.dp),
        )

        Spacer(Modifier.height(8.dp))

        // ── About / value-props card ──────────────────────────────────
        AboutCard(settings.locale)

        // ── Re-show the onboarding tour ──────────────────────────────
        ReplayOnboardingRow(settings.locale, onReplayOnboarding)

        // ── Profile section ───────────────────────────────────────────
        SectionLabel(Strings.t("settings.profile", settings.locale))
        ProfileCard(profile, settings.locale, onChangeProfile)

        // ── Units section ─────────────────────────────────────────────
        SectionLabel(Strings.t("settings.units", settings.locale))
        Card {
            ToggleRow(
                label = Strings.t("settings.units.metric", settings.locale),
                selected = settings.unitsMetric,
                onClick = { onUnitsChange(true) },
            )
            Divider()
            ToggleRow(
                label = Strings.t("settings.units.imperial", settings.locale),
                selected = !settings.unitsMetric,
                onClick = { onUnitsChange(false) },
            )
        }

        // ── Language section ──────────────────────────────────────────
        SectionLabel(Strings.t("settings.language", settings.locale))
        Text(
            Strings.t("settings.language.description", settings.locale),
            color = RideTokens.Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        )
        LanguageDropdown(
            current = settings.locale,
            onPick = onLocaleChange,
        )

        // ── Map theme section ─────────────────────────────────────────
        SectionLabel("Map")
        Card {
            SwitchRow(
                label = "Auto day / night",
                description = "Switches between Dark and Light based on sunrise / sunset at your location.",
                checked = settings.autoMapTheme,
                onCheckedChange = onAutoMapThemeChange,
            )
        }

        // ── Voice + spatial section ───────────────────────────────────
        SectionLabel(Strings.t("settings.voice", settings.locale))
        Card {
            SwitchRow(
                label = Strings.t("settings.voice.speak", settings.locale),
                description = Strings.t("settings.voice.speak.desc", settings.locale),
                checked = settings.voiceEnabled,
                onCheckedChange = onVoiceToggle,
            )
            Divider()
            // Eyes-on-the-road mode: routing server picks landmark-led
            // templates instead of distance-led ones so the rider can
            // navigate without looking at the phone. On by default —
            // it's the core of what Scoova does; riders who prefer
            // Google-Maps-style "in 350 m turn right" copy can opt out.
            SwitchRow(
                label = "Eyes on the road",
                description = "Speak cues anchored on what you can see (\"After McDonald's, turn right\") instead of distances — so you can navigate without looking at the phone.",
                checked = settings.eyesOff,
                onCheckedChange = onEyesOffToggle,
            )
            Divider()
            SwitchRow(
                label = Strings.t("settings.voice.spatial", settings.locale),
                description = Strings.t("settings.voice.spatial.desc", settings.locale),
                checked = settings.spatialAudio,
                onCheckedChange = onSpatialToggle,
            )
            Divider()
            // Voice preview row — speaks a localized sample cue so the
            // rider can audition the language + voice without starting
            // a ride. Sits above the spatial-audio test (which is purely
            // a stereo-routing check, not a voice quality check).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onVoicePreview() }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    null,
                    tint = RideTokens.Cyan2,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Preview voice",
                        color = RideTokens.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Hear a sample cue in your selected language",
                        color = RideTokens.Muted, fontSize = 12.sp,
                    )
                }
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .background(RideTokens.Cyan, RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "PLAY",
                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                    )
                }
            }
            Divider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSpatialTest() }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Headphones,
                    null,
                    tint = RideTokens.Cyan2,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(Strings.t("settings.voice.test", settings.locale), color = RideTokens.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        Strings.t("settings.voice.test.desc", settings.locale),
                        color = RideTokens.Muted, fontSize = 12.sp,
                    )
                }
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .background(RideTokens.Cyan, RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(Strings.t("settings.voice.test.cta", settings.locale), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                }
            }
        }

        // ── Health section ────────────────────────────────────────────
        SectionLabel("Health")
        Card {
            WeightRow(settings.weightKg, onWeightChange)
        }

        Spacer(Modifier.height(20.dp))

        // ── Reliability section ──────────────────────────────────────
        // Aggressive OEMs (Samsung, Xiaomi, Huawei, OPPO, Vivo …) will
        // doze our foreground service after a few minutes of screen-off
        // unless the user has explicitly whitelisted the app from
        // battery optimisation. Surface this as an actionable row with
        // live status so the rider can see whether they're set up for
        // a reliable background ride.
        SectionLabel("Reliability")
        Card {
            BatteryOptimizationRow()
        }

        Spacer(Modifier.height(20.dp))

        // ── Account section ──────────────────────────────────────────
        // Signed-out: a single "Sign in to back up rides" row that
        // launches Phase.Auth. Signed-in: shows email + "Sign out" row.
        SectionLabel("Account")
        Card {
            AccountRow(signedIn = signedIn, onStartAuth = onStartAuth, onSignOut = onSignOut)
        }

        Spacer(Modifier.height(20.dp))

        // ── Rides section ────────────────────────────────────────────
        // Master switch for ride tracking. Off → Scoova is
        // navigation-only: no history entry, no Summary screen, no GPS
        // trail. Cloud upload (SaveMode) sits underneath as a
        // sub-option — only meaningful while recording is on and the
        // rider is signed in.
        SectionLabel("Rides")
        Card {
            SwitchRow(
                label = "Record rides",
                description = "Save each trip's stats, route map, and history. Turn off to use Scoova for navigation only — nothing is logged.",
                checked = settings.recordRides,
                onCheckedChange = onRecordRidesToggle,
            )
            if (settings.recordRides && signedIn != null) {
                Divider()
                SaveModeRow(
                    current = settings.saveMode,
                    onChange = onSaveModeChange,
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        // ── Privacy section ───────────────────────────────────────────
        SectionLabel("Privacy")
        Card {
            ExportDataRow(onExportData)
            Divider()
            ClearDataRow(onClearAllData)
        }

        Spacer(Modifier.height(20.dp))

        // ── About section ─────────────────────────────────────────────
        SectionLabel("About")
        TakeTourRow(settings.locale, onStartTour)
        Spacer(Modifier.height(8.dp))
        Card {
            AboutRow()
        }

        Spacer(Modifier.height(28.dp))

        // Footer
        Text(
            Strings.t("settings.about.tagline", settings.locale),
            color = RideTokens.Muted, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
        )
        // Clear the floating GlassTabBar AND the system gesture inset.
        // The tab bar lives inside MainTabShell with its own
        // navigationBars padding, so the scroll container needs:
        //   gesture-inset height  (so we don't sit under the handle)
        // + tab-bar height + 22.dp bottom margin
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun WeightRow(weightKg: Int, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Body weight",
                    color = RideTokens.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Used for calorie estimates on ride summary",
                    color = RideTokens.Muted, fontSize = 12.sp,
                )
            }
            Text(
                "$weightKg kg",
                color = RideTokens.Cyan2, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(10.dp))
        // Compose Material3 doesn't have a stable Slider that fits the
        // demo's color palette without importing more material APIs.
        // Use a custom segmented stepper: − / value / + buttons.
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeightStepperButton(label = "−") { onChange((weightKg - 1).coerceAtLeast(30)) }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .background(RideTokens.Surface2, RoundedCornerShape(10.dp))
                    .border(1.dp, RideTokens.Border, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("$weightKg kg", color = RideTokens.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.width(8.dp))
            WeightStepperButton(label = "+") { onChange((weightKg + 1).coerceAtMost(200)) }
        }
    }
}

@Composable
private fun WeightStepperButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 34.dp)
            .background(RideTokens.Cyan.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
            .border(1.dp, RideTokens.Cyan.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = RideTokens.Cyan2, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ExportDataRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Export your data",
                color = RideTokens.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Save settings, favorites, and ride history as JSON.",
                color = RideTokens.Muted, fontSize = 12.sp,
            )
        }
        Box(
            modifier = Modifier
                .height(32.dp)
                .background(RideTokens.Cyan, RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "EXPORT",
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
            )
        }
    }
}

@Composable
private fun AboutRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val (versionName, versionCode) = remember {
        runCatching {
            val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode else @Suppress("DEPRECATION") pkg.versionCode.toLong()
            (pkg.versionName ?: "—") to code
        }.getOrDefault("—" to 0L)
    }
    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Scoova Ride",
                    color = RideTokens.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Reference app for the Scoova Nav Layer SDK",
                    color = RideTokens.Muted, fontSize = 12.sp,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "v$versionName",
                    color = RideTokens.Cyan2, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    "build $versionCode",
                    color = RideTokens.Muted, fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun ClearDataRow(onClick: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (confirming) {
                    onClick()
                    confirming = false
                } else {
                    confirming = true
                }
            }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (confirming) "Tap again to confirm" else "Clear all data",
                color = if (confirming) Color(0xFFFF4D4D) else RideTokens.Text,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (confirming)
                    "This deletes history, favorites, and resets onboarding."
                else
                    "Deletes ride history, favorites, and saved places.",
                color = RideTokens.Muted, fontSize = 12.sp,
            )
        }
        Box(
            modifier = Modifier
                .height(32.dp)
                .background(
                    if (confirming) Color(0xFFFF4D4D) else RideTokens.Surface2,
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (confirming) "CONFIRM" else "CLEAR",
                color = if (confirming) Color.White else RideTokens.Muted,
                fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
            )
        }
    }
}

// ─── "How does Scoova work?" — re-runs the first-launch tour ──────────

@Composable
private fun TakeTourRow(locale: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 14.dp)
            .fillMaxWidth()
            .background(RideTokens.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, RideTokens.Border, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(RideTokens.Accent.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = RideTokens.Accent,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                Strings.t("settings.take_tour", locale),
                color = RideTokens.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                Strings.t("settings.take_tour.desc", locale),
                color = RideTokens.Muted,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ReplayOnboardingRow(locale: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 14.dp)
            .fillMaxWidth()
            .background(RideTokens.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, RideTokens.Border, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(RideTokens.Cyan.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("?", color = RideTokens.Cyan2, fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                Strings.t("settings.replay_tour", locale),
                color = RideTokens.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                Strings.t("settings.replay_tour.desc", locale),
                color = RideTokens.Muted,
                fontSize = 11.sp,
            )
        }
        Icon(
            Icons.Default.KeyboardArrowDown,
            null,
            tint = RideTokens.Muted,
            modifier = Modifier.size(22.dp),
        )
    }
    Spacer(Modifier.height(12.dp))
}

// ─── About / value-props card ─────────────────────────────────────────
// Sits at the top of Settings so the user always knows what Scoova is
// and why the demo is shaped the way it is. Tappable to expand the
// bullets; collapsed shows the title + tagline.

@Composable
private fun AboutCard(locale: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .padding(horizontal = 14.dp)
            .fillMaxWidth()
            .background(RideTokens.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, RideTokens.Accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(RideTokens.Accent.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("S", color = RideTokens.Accent, fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    Strings.t("about.title", locale),
                    color = RideTokens.Text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    Strings.t("settings.about.tagline", locale),
                    color = RideTokens.Muted,
                    fontSize = 11.sp,
                )
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                null,
                tint = RideTokens.Muted,
                modifier = Modifier.size(22.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Text(
                Strings.t("about.body", locale),
                color = RideTokens.Text.copy(alpha = 0.85f),
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(10.dp))
            for (key in listOf(
                "about.bullet1", "about.bullet2", "about.bullet3",
                "about.bullet4", "about.bullet5",
            )) {
                Row(
                    modifier = Modifier.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text("•", color = RideTokens.Accent, fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        Strings.t(key, locale),
                        color = RideTokens.Text.copy(alpha = 0.9f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = RideTokens.Cyan2,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
    )
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 14.dp)
            .fillMaxWidth()
            .background(RideTokens.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, RideTokens.Border, RoundedCornerShape(14.dp)),
        content = content,
    )
}

@Composable
private fun Divider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(RideTokens.Border))
}

@Composable
private fun ProfileCard(profile: Profile?, locale: String, onChange: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 14.dp)
            .fillMaxWidth()
            .background(RideTokens.Surface, RoundedCornerShape(14.dp))
            .border(
                1.dp,
                (profile?.accent ?: RideTokens.Border).copy(alpha = 0.4f),
                RoundedCornerShape(14.dp),
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .background(RideTokens.Surface2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(profile?.emoji ?: "🚴", fontSize = 26.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                profile?.localizedDisplay(locale) ?: Profile.Bicycle.localizedDisplay(locale),
                color = RideTokens.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            )
            Text(profile?.localizedTagline(locale) ?: "—", color = RideTokens.Muted, fontSize = 12.sp)
        }
        Box(
            modifier = Modifier
                .background(RideTokens.Surface2, RoundedCornerShape(10.dp))
                .border(1.dp, RideTokens.Border, RoundedCornerShape(10.dp))
                .clickable { onChange() }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                Strings.t("settings.profile.change", locale),
                color = RideTokens.Text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = RideTokens.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (selected) {
            Icon(Icons.Default.Check, null, tint = RideTokens.Cyan2, modifier = Modifier.size(20.dp))
        }
    }
}

// ─── Language dropdown ─────────────────────────────────────────────────
// One-tap chooser instead of a long vertical list. Visually mirrors a
// system spinner: current selection in a card row with a chevron, tap to
// expand a DropdownMenu of the remaining locales.

@Composable
private fun LanguageDropdown(current: String, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = SCOOVA_LOCALES.firstOrNull { it.tag == current } ?: SCOOVA_LOCALES.first()
    Box(modifier = Modifier.padding(horizontal = 14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(RideTokens.Surface, RoundedCornerShape(14.dp))
                .border(1.dp, RideTokens.Border, RoundedCornerShape(14.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(selected.flag, fontSize = 22.sp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(selected.display, color = RideTokens.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(selected.tag, color = RideTokens.Muted, fontSize = 11.sp)
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = RideTokens.Muted,
                modifier = Modifier.size(22.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(RideTokens.Surface)
                .fillMaxWidth(0.9f),
        ) {
            for (opt in SCOOVA_LOCALES) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(opt.flag, fontSize = 18.sp)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(opt.display, color = RideTokens.Text, fontSize = 14.sp)
                                Text(opt.tag, color = RideTokens.Muted, fontSize = 10.sp)
                            }
                            if (opt.tag == current) {
                                Icon(
                                    Icons.Default.Check, null,
                                    tint = RideTokens.Cyan2,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onPick(opt.tag)
                    },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = RideTokens.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = RideTokens.Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = RideTokens.Cyan,
                uncheckedThumbColor = RideTokens.Muted,
                uncheckedTrackColor = RideTokens.Surface2,
            ),
        )
    }
}

/**
 * Reliability row — exposes the per-app battery-optimisation whitelist
 * toggle.
 *
 * Live status is re-read each composition so the row reflects the
 * post-grant state without a forced refresh: tap it, the system dialog
 * pops, the rider taps Allow, returns to us, recomposition reads the
 * new value and flips "Limited" → "Allowed". `mutableStateOf` keyed off
 * the lifecycle would be cleaner but each Settings entry is its own
 * navigation so a fresh composition is the dominant pattern.
 *
 * Tap behaviour:
 *   • If currently *not* whitelisted → fire the
 *     ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog. Single tap,
 *     user-friendly modal. On most stock Android variants this is
 *     enough.
 *   • If the rider is already whitelisted → open the full battery-
 *     optimisation settings list so they can see / undo, or whitelist
 *     other Scoova-related items (the FG-service notification channel,
 *     etc.) per their OEM's UI.
 */
@Composable
private fun BatteryOptimizationRow() {
    val ctx = LocalContext.current
    // Recompute each composition. Cheap (single binder call) and self-
    // refreshes once the system dialog closes and the user returns.
    val whitelisted = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(ctx)
    val aggressive = BatteryOptimizationHelper.isLikelyAggressiveOem()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val intent = if (!whitelisted) {
                    BatteryOptimizationHelper.requestIgnoreIntent(ctx)
                } else {
                    BatteryOptimizationHelper.openBatterySettingsIntent()
                }
                runCatching { ctx.startActivity(intent) }
            }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Background navigation",
                color = RideTokens.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            val statusColor = if (whitelisted) RideTokens.Cyan2 else Color(0xFFE6A23C)
            val statusText = when {
                whitelisted -> "Allowed — voice keeps working with the screen off"
                aggressive -> "Limited — your phone may stop the ride after a few minutes"
                else -> "Limited — tap to keep voice working while the screen is off"
            }
            Spacer(Modifier.height(2.dp))
            Text(statusText, color = statusColor, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .height(32.dp)
                .background(
                    if (whitelisted) RideTokens.Surface2 else RideTokens.Cyan,
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (whitelisted) "MANAGE" else "ALLOW",
                color = if (whitelisted) RideTokens.Text else Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
        }
    }
}

/**
 * Account row — either "Sign in to back up rides" (signed out) or
 * "<email> · Sign out" (signed in). One-tap reach to the AuthScreen
 * from the signed-out state; signed-in tap of "Sign out" drops the
 * local JWT and flips the row back.
 *
 * No avatar / picture intentionally — the consumer rider product
 * doesn't ask for one at sign-up; we don't render a generic silhouette
 * because it adds visual noise without value.
 */
@Composable
private fun AccountRow(
    signedIn: com.scoova.ride.cloud.AuthCredentials?,
    onStartAuth: () -> Unit,
    onSignOut: () -> Unit,
) {
    if (signedIn == null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onStartAuth() }
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Sign in to back up rides",
                    color = RideTokens.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Free account. Your ride history syncs to my.scoo-va.info.",
                    color = RideTokens.Muted, fontSize = 12.sp,
                )
            }
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .background(RideTokens.Accent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "SIGN IN",
                    color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                )
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    signedIn.displayName?.takeIf { it.isNotBlank() } ?: signedIn.email,
                    color = RideTokens.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
                if (!signedIn.displayName.isNullOrBlank()) {
                    Text(
                        signedIn.email,
                        color = RideTokens.Muted, fontSize = 12.sp,
                    )
                } else {
                    Text(
                        "Signed in",
                        color = RideTokens.Muted, fontSize = 12.sp,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .background(RideTokens.Surface3, RoundedCornerShape(8.dp))
                    .clickable { onSignOut() }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "SIGN OUT",
                    color = RideTokens.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                )
            }
        }
    }
}

/**
 * Save-rides tri-state radio row. The three options are mutually
 * exclusive; the Summary screen lets the rider override the single
 * upcoming ride regardless.
 */
@Composable
private fun SaveModeRow(
    current: SaveMode,
    onChange: (SaveMode) -> Unit,
) {
    Column {
        SaveModeOption(
            label = "Always save",
            description = "Every ride uploads automatically. You can still discard a single ride on the Summary screen.",
            selected = current == SaveMode.Always,
            onClick = { onChange(SaveMode.Always) },
        )
        Divider()
        SaveModeOption(
            label = "Ask each time",
            description = "Default. The Summary screen shows a Save / Discard button after each ride.",
            selected = current == SaveMode.AskEachTime,
            onClick = { onChange(SaveMode.AskEachTime) },
        )
        Divider()
        SaveModeOption(
            label = "Never save",
            description = "Nothing uploads. Local history only. Toggle off cloud sync entirely.",
            selected = current == SaveMode.Never,
            onClick = { onChange(SaveMode.Never) },
        )
    }
}

@Composable
private fun SaveModeOption(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Custom radio dot — matches the rest of the app's visual
        // language (Material's default radio is grey-on-grey here).
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(
                    if (selected) RideTokens.Accent else Color.Transparent,
                    CircleShape,
                )
                .border(
                    width = 2.dp,
                    color = if (selected) RideTokens.Accent else RideTokens.Border,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = RideTokens.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = RideTokens.Muted, fontSize = 12.sp)
        }
    }
}
