package com.scoova.ride

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Scoova Ride — the cycling-app reference implementation that demos the
 * full Scoova Nav Layer + Scoova routing + MapLibre tiles stack.
 *
 * A single linear flow, 1:1 with the iOS ScoovaRide app:
 *   Onboarding → Persona → Plan → Ride → Summary
 *
 * History and Settings are floating affordances on the Plan screen
 * (mirrors the iOS PlanView's circle buttons + sheets) — there is no
 * tab bar.
 *
 * **Note on location & sensors.** The activity does NOT own the
 * FusedLocation callback or the SensorRelay. Those live on
 * [NavigationService] (the foreground-service-with-type=location) and
 * publish to [com.scoova.navlayer.core.NavRuntimeBus]. The activity's
 * only job around location is the permission UX.
 */
class RideActivity : ComponentActivity() {

    private val vm: RideViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val ok = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (ok) vm.onLocationPermissionGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (vm.state.value.settings.onboardingDone) {
            requestNeededPermissions()
        }
        if (hasLocationPermission()) vm.onLocationPermissionGranted()

        setContent {
            MaterialTheme(colorScheme = RideColors) {
                val state by vm.state.collectAsState()
                // Keep the screen on during a ride (and only then).
                val keepOn = state.phase == RideViewModel.Phase.Ride
                androidx.compose.runtime.SideEffect {
                    val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    if (keepOn) window.addFlags(flag) else window.clearFlags(flag)
                }
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalLocale provides state.settings.locale,
                    androidx.compose.ui.platform.LocalLayoutDirection provides
                        Strings.layoutDirection(state.settings.locale),
                ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (state.phase) {
                        RideViewModel.Phase.Onboarding -> OnboardingScreen(
                            locale = state.settings.locale,
                            onFinish = {
                                vm.finishOnboarding()
                                requestNeededPermissions()
                                if (hasLocationPermission()) vm.onLocationPermissionGranted()
                            },
                        )
                        RideViewModel.Phase.Persona -> PersonaScreen(
                            onPick = { profile -> vm.selectProfile(profile) },
                        )
                        RideViewModel.Phase.Plan -> PlanPhase(state)
                        RideViewModel.Phase.Ride -> {
                            val isOff by vm.isOffRoute.collectAsState()
                            val isReroute by vm.isRerouting.collectAsState()
                            val batt by vm.batteryState.collectAsState()
                            val compassAcc by vm.compassAccuracy.collectAsState()
                            RideScreen(
                                state = state,
                                nav = vm.nav,
                                isOffRoute = isOff,
                                isRerouting = isReroute,
                                batteryLow = batt.shouldSuggestLowPower,
                                batteryPercent = batt.percent,
                                compassAccuracy = compassAcc,
                                onEnd = { vm.endRide() },
                                onSwitchProfile = { vm.switchProfile(it) },
                                onToggleVoice = { vm.setVoiceEnabled(it) },
                            )
                        }
                        RideViewModel.Phase.Summary -> SummaryScreen(
                            state = state,
                            onSaveNote = { vm.setLastRideNote(it) },
                            onAckMilestone = { vm.acknowledgeMilestone() },
                            onPlanAnother = { vm.resetToPlan() },
                        )
                    }

                    // Proactive reliability nudge — battery-optimisation hint.
                    if (state.phase == RideViewModel.Phase.Plan &&
                        vm.shouldShowBatteryHint()) {
                        var dialogShown by remember { mutableStateOf(true) }
                        if (dialogShown) {
                            AlertDialog(
                                onDismissRequest = { dialogShown = false },
                                title = { Text("Keep voice working in the background") },
                                text = {
                                    Text(
                                        "Your phone may stop GPS and voice cues a few minutes after the " +
                                            "screen turns off. Allow Scoova to ignore battery optimisation " +
                                            "so turn-by-turn keeps speaking through your whole ride.",
                                    )
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            dialogShown = false
                                            vm.dismissBatteryHint()
                                            runCatching {
                                                startActivity(
                                                    BatteryOptimizationHelper
                                                        .requestIgnoreIntent(this@RideActivity),
                                                )
                                            }
                                        },
                                    ) { Text("Allow") }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = {
                                            dialogShown = false
                                            vm.dismissBatteryHint()
                                        },
                                    ) { Text("Maybe later") }
                                },
                            )
                        }
                    }
                }
                }
            }
        }
    }

    /**
     * The Plan phase — the route planner with History and Settings as
     * floating circle buttons that open full-screen sheets. 1:1 with
     * the iOS PlanView (which itself was ported from this screen).
     */
    @Composable
    private fun PlanPhase(state: RideViewModel.RideState) {
        var overlay by remember { mutableStateOf<PlanOverlay?>(null) }
        Box(Modifier.fillMaxSize()) {
            PlanScreen(
                state = state,
                nav = vm.nav,
                onMapTap = { lat, lon -> vm.planRouteTo(lat, lon, "Tapped point") },
                onPickPreset = { lat, lon, label -> vm.planRouteTo(lat, lon, label) },
                onStart = { vm.startRide() },
                onSimulate = { vm.simulateRide() },
                onCancel = { vm.resetToPlan() },
                onSearchQueryChange = { vm.setSearchQuery(it) },
                onPickSearchResult = { vm.pickSearchResult(it) },
                onClearSearch = { vm.clearSearch() },
                onPlanToSavedPlace = { vm.planToSavedPlace(it) },
                onSaveAsHome = { lat, lon, label -> vm.saveAsHome(lat, lon, label) },
                onSaveAsWork = { lat, lon, label -> vm.saveAsWork(lat, lon, label) },
                onSwitchProfile = { vm.switchProfile(it) },
                onAvoidHighways = { vm.setAvoidHighways(it) },
                onAvoidTolls = { vm.setAvoidTolls(it) },
                onAvoidFerries = { vm.setAvoidFerries(it) },
                onToggleFavorite = { lat, lon, label -> vm.toggleFavorite(lat, lon, label) },
                onDismissError = { vm.clearRouteError() },
            )
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 12.dp, end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalIconButton(onClick = { overlay = PlanOverlay.History }) {
                    Icon(Icons.Filled.DateRange, contentDescription = "Ride history")
                }
                FilledTonalIconButton(onClick = { overlay = PlanOverlay.Settings }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            }
        }
        when (overlay) {
            PlanOverlay.History -> SheetOverlay(onClose = { overlay = null }) {
                HistoryScreen(rides = state.history)
            }
            PlanOverlay.Settings -> SheetOverlay(onClose = { overlay = null }) {
                SettingsScreen(
                    profile = state.profile,
                    settings = state.settings,
                    onChangeProfile = { vm.changeProfile() },
                    onUnitsChange = { vm.setUnitsMetric(it) },
                    onLocaleChange = { vm.setLocale(it) },
                    onVoiceToggle = { vm.setVoiceEnabled(it) },
                    onSpatialToggle = { vm.setSpatialAudio(it) },
                    onSpatialTest = { vm.runSpatialAudioTest() },
                    onVoicePreview = { vm.previewVoice() },
                    onWeightChange = { vm.setWeightKg(it) },
                    onAutoMapThemeChange = { vm.setAutoMapTheme(it) },
                    onClearAllData = { vm.clearAllData() },
                    onExportData = {
                        val json = vm.buildExportJson()
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "Scoova ride data export")
                            putExtra(android.content.Intent.EXTRA_TEXT, json)
                        }
                        startActivity(
                            android.content.Intent.createChooser(send, "Export Scoova data"),
                        )
                    },
                    onReplayOnboarding = { vm.showOnboardingAgain() },
                    onEyesOffToggle = { vm.setEyesOff(it) },
                    onStartTour = { vm.startTour() },
                )
            }
            null -> Unit
        }
    }

    /** Full-screen sheet with a close button — the Android equivalent of
     *  the iOS `.sheet` History / Settings present. */
    @Composable
    private fun SheetOverlay(onClose: () -> Unit, content: @Composable () -> Unit) {
        androidx.activity.compose.BackHandler(onBack = onClose)
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            content()
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(8.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }
        }
    }

    private enum class PlanOverlay { History, Settings }

    /** Build the missing-permission set and launch the system prompt. */
    private fun requestNeededPermissions() {
        val needed = buildList {
            if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION) &&
                !isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
            ) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                !isGranted(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun isGranted(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission(): Boolean =
        isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
}
