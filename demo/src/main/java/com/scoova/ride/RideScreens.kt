package com.scoova.ride

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scoova.navlayer.core.CrashEvent
import com.scoova.navlayer.core.ScoovaNavLayer
import com.scoova.navlayer.scoova.LatLon
import com.scoova.navlayer.ui.ScoovaCompactManeuverCard
import com.scoova.navlayer.ui.ScoovaCompassCalibrationBanner
import com.scoova.navlayer.ui.ScoovaCrashPrompt
import com.scoova.navlayer.ui.ScoovaEtaCard
import com.scoova.navlayer.ui.ScoovaSpeedChip
import kotlinx.coroutines.launch

// ─── Helpers ────────────────────────────────────────────────────────────

private fun haversineKm(a: LatLon, lat: Double, lon: Double): Double {
    val R = 6371.0
    val dLat = Math.toRadians(lat - a.lat)
    val dLon = Math.toRadians(lon - a.lon)
    val sa = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        kotlin.math.cos(Math.toRadians(a.lat)) * kotlin.math.cos(Math.toRadians(lat)) *
        kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
    return R * 2 * kotlin.math.atan2(kotlin.math.sqrt(sa), kotlin.math.sqrt(1 - sa))
}

private fun formatKm(km: Double): String = when {
    km < 1.0 -> "${(km * 1000).toInt()} m"
    else     -> "%.1f km".format(km)
}

// ─── PlanScreen — map-first ─────────────────────────────────────────────
//
// Layout principle (per memory rule "map-first UI"):
//   • Map fills the entire screen edge-to-edge.
//   • Controls float ON the map. No permanent bottom card.
//   • Search pill floats at top. Persona row sits just under it.
//   • Weather chip floats at top-right next to the search pill.
//   • Route preview card appears ONLY when a route is computed.
//   • Tab bar (drawn by MainTabShell) lives below everything.

@Composable
fun PlanScreen(
    state: RideViewModel.RideState,
    nav: ScoovaNavLayer,
    onMapTap: (Double, Double) -> Unit,
    onPickPreset: (Double, Double, String) -> Unit,
    onStart: () -> Unit,
    onSimulate: () -> Unit,
    onCancel: () -> Unit,
    onSearchQueryChange: (String) -> Unit = {},
    onPickSearchResult: (SearchSuggestion) -> Unit = {},
    onClearSearch: () -> Unit = {},
    onPlanToSavedPlace: (SavedPlace) -> Unit = {},
    onSaveAsHome: (lat: Double, lon: Double, label: String) -> Unit = { _, _, _ -> },
    onSaveAsWork: (lat: Double, lon: Double, label: String) -> Unit = { _, _, _ -> },
    onSwitchProfile: (Profile) -> Unit = {},
    onAvoidHighways: (Boolean) -> Unit = {},
    onAvoidTolls: (Boolean) -> Unit = {},
    onAvoidFerries: (Boolean) -> Unit = {},
    onToggleFavorite: (lat: Double, lon: Double, label: String) -> Unit = { _, _, _ -> },
    onDismissError: () -> Unit = {},
) {
    var searchExpanded by remember { mutableStateOf(false) }
    // Quick-actions strip (Set Home / Work + POI category chips) is
    // collapsible — riders who know where they're going want the map,
    // not a wall of chips. Toggled by the chevron under the search row.
    var quickActionsExpanded by remember { mutableStateOf(true) }
    // Coroutine scope + geocoder for the long-press reverse-geocode.
    val planScope = rememberCoroutineScope()
    val reverseGeocoder = remember { SearchClient() }
    // Live compass/GPS-fused heading — drives the heading-up follow
    // camera on the Plan map (rotates the map as the rider turns,
    // same as the Ride screen). Sourced from the nav layer's sensor
    // stream, which is alive whenever the layer is started.
    val planHeading by nav.headingDeg.collectAsState()
    // Map style derivation:
    //   • If the user has tapped the style FAB, [manualStyle] is set
    //     and pins that choice (overrides everything until they cycle).
    //   • Else if [autoMapTheme] setting is on, drive from SolarTime
    //     at the rider's location (Dark at night, Light by day).
    //   • Else default to Dark.
    var manualStyle by remember { mutableStateOf<MapStyleChoice?>(null) }
    val autoStyle: MapStyleChoice = remember(state.origin, state.settings.autoMapTheme) {
        val o = state.origin
        when {
            !state.settings.autoMapTheme -> MapStyleChoice.Dark
            o == null -> MapStyleChoice.Dark
            com.scoova.navlayer.core.SolarTime.isNight(o.lat, o.lon) -> MapStyleChoice.Dark
            else -> MapStyleChoice.Light
        }
    }
    val mapStyle: MapStyleChoice = manualStyle ?: autoStyle
    var recenterTick by remember { mutableStateOf(0) }
    // Follow-me / compass state. Default ON — the map opens centered on
    // the user and tracks them. The moment the user pans, pinches, or
    // rotates the map by hand, followMode flips off and the locate-me
    // FAB swaps into a compass face that mirrors the current bearing.
    // Tapping the FAB in either face turns follow back on and re-centers.
    var followMode by remember { mutableStateOf(true) }
    var mapBearing by remember { mutableFloatStateOf(0f) }
    // POI tapped on the map → shows the bottom sheet with "Route here".
    var tappedPoi by remember {
        mutableStateOf<com.scoova.navlayer.maplibre.ScoovaMapFeature?>(null)
    }
    // Which place the user is currently configuring via the wizard (null
    // when the wizard is closed). The chips below open the wizard rather
    // than silently saving the current GPS fix.
    var setPlaceTarget by remember { mutableStateOf<PlaceTarget?>(null) }
    // Pin-drop mode — when set, the wizard is dismissed and the user is
    // primed to long-press anywhere on the map to drop a pin for that
    // place. A floating banner at the top tells them what to do and how
    // to cancel.
    var pinDropTarget by remember { mutableStateOf<PlaceTarget?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(RideTokens.Bg)) {
        // When a route is on screen we want the rider to see the WHOLE
        // path, not a puck-centered close-up — so follow is suppressed
        // automatically until the route is dismissed or the ride
        // starts. (RideScreen has its own follow logic for actual
        // navigation; Plan is for previewing the route end-to-end.)
        val followInPlan = followMode && state.routeShape.isEmpty()

        // Map — fills everything underneath
        RideMap(
            modifier = Modifier.fillMaxSize(),
            data = RideMapData(
                userLat = state.origin?.lat,
                userLon = state.origin?.lon,
                destLat = state.destination?.lat,
                destLon = state.destination?.lon,
                routeShape = state.routeShape,
                followUser = followInPlan,
                // Heading-up while we're following the user (no route
                // planned) — the map rotates as the rider turns, so
                // "up" is always where they're facing. Once a route
                // is planned `followInPlan` goes false and we hold
                // bearing 0 (north-up) so the rider sees the whole
                // route correctly oriented.
                bearingDeg = if (followInPlan) planHeading else 0f,
                // Drop a pin on the map for every search result so the
                // rider can see WHERE the matches are while scanning
                // the list. Cleared automatically when the rider
                // dismisses search (state.searchResults goes empty).
                searchPins = state.searchResults.map { doubleArrayOf(it.lat, it.lon) },
            ),
            style = mapStyle,
            recenterTick = recenterTick,
            locale = state.settings.locale,
            // Paint the lane palette to match the selected persona —
            // cyan cycleways for bike/scooter, amber footways for
            // foot, all muted for motor. Defaults to motor when no
            // persona is set yet (first-launch persona picker).
            pathMode = state.profile?.pathHighlightMode
                ?: com.scoova.navlayer.maplibre.PathHighlightMode.Motor,
            // Single tap is now the "dismiss" gesture only — closes
            // the search expand, POI sheet, etc. without committing
            // to any route. Premium nav apps reserve "open something"
            // for an intentional long-press; touch-to-open caused too
            // many misfires as riders reached for chips.
            onMapTap = { _, _ ->
                tappedPoi = null
                searchExpanded = false
            },
            onMapLongPress = { lat, lon ->
                when (pinDropTarget) {
                    PlaceTarget.Home -> {
                        onSaveAsHome(lat, lon, "Home")
                        pinDropTarget = null
                    }
                    PlaceTarget.Work -> {
                        onSaveAsWork(lat, lon, "Work")
                        pinDropTarget = null
                    }
                    // Long-press on empty map → reverse-geocode the
                    // point and route there with the REAL place name
                    // ("Fresh Mart Supermarket") instead of an
                    // anonymous "Tapped point". Best-effort: if the
                    // geocoder is unreachable we fall back to a
                    // generic "Dropped pin" label. The Home/Work
                    // pin-drop mode (above) takes priority when active.
                    null -> planScope.launch {
                        val label = reverseGeocoder.reverse(lat, lon)
                            ?.takeIf { it.isNotBlank() }
                            ?: "Dropped pin"
                        onPickPreset(lat, lon, label)
                    }
                }
            },
            onUserGesture = { followMode = false },
            onBearingChange = { mapBearing = it },
            onPoiTap = { tappedPoi = it },
        )

        // ── Top floating controls (search pill + persona row + weather) ──
        // statusBars inset pushes us below the system clock / signal icons —
        // without it the pill renders *under* the status bar on edge-to-edge.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Brand header — wordmark + time-aware greeting on the left,
            // weather chip on the right. Gives the app a real identity at
            // the top rather than dropping the user straight into search.
            // WeatherChip always renders so the corner is anchored — a
            // placeholder shows while the snapshot is in-flight.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                BrandGreeting(modifier = Modifier.weight(1f), locale = state.settings.locale)
                WeatherChip(weather = state.weather, unitsMetric = state.settings.unitsMetric)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PersonaPicker(
                    selected = state.profile,
                    onPick = onSwitchProfile,
                )
                SearchPill(
                    value = state.searchQuery,
                    onValueChange = {
                        onSearchQueryChange(it)
                        searchExpanded = it.isNotEmpty()
                    },
                    onClear = {
                        onClearSearch()
                        searchExpanded = false
                    },
                    onFocus = { searchExpanded = true },
                    modifier = Modifier.weight(1f),
                )
            }

            // Quick destinations strip — Set Home / Work + POI category
            // chips. Collapsible via the chevron so riders can reclaim
            // the screen for the map. Hidden entirely while search is
            // expanded (sheet covers it) or a route is on screen.
            if (!searchExpanded && state.routeShape.isEmpty()) {
                QuickActionsToggle(
                    expanded = quickActionsExpanded,
                    onToggle = { quickActionsExpanded = !quickActionsExpanded },
                )
                if (quickActionsExpanded) {
                    HomeActions(
                        state = state,
                        onSaveAsHome = { setPlaceTarget = PlaceTarget.Home },
                        onSaveAsWork = { setPlaceTarget = PlaceTarget.Work },
                        onPlanToSavedPlace = onPlanToSavedPlace,
                        onPickRecent = { lat, lon, label ->
                            onPickPreset(lat, lon, label)
                        },
                    )
                    // POI category quick-search — one-tap "what's nearby?".
                    // Each chip seeds the search query with a category
                    // keyword; the existing autocomplete plumbing finds
                    // matches around the rider's current focus point and
                    // pins them on the map.
                    PoiCategoryChipsRow(
                        onCategoryTap = { query ->
                            onSearchQueryChange(query)
                            searchExpanded = true
                        },
                    )
                }
            }
        }

        // Search results / recents / shortcuts — only when search is expanded
        // OR there are typed results. Floats below the persona row.
        if (searchExpanded || state.searchQuery.isNotEmpty() || state.isSearching) {
            SearchSheet(
                state = state,
                onPickSearchResult = {
                    onPickSearchResult(it)
                    searchExpanded = false
                },
                onPlanToSavedPlace = {
                    onPlanToSavedPlace(it)
                    searchExpanded = false
                },
                onSaveAsHome = { setPlaceTarget = PlaceTarget.Home; searchExpanded = false },
                onSaveAsWork = { setPlaceTarget = PlaceTarget.Work; searchExpanded = false },
                onPickRecent = { lat, lon, label ->
                    onPickPreset(lat, lon, label)
                    searchExpanded = false
                },
                onDismiss = {
                    searchExpanded = false
                    onClearSearch()
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 150.dp, start = 14.dp, end = 14.dp),
            )
        }

        // ── Right edge: vertical stack of map FABs ──
        // Style toggle on top (cycles Dark→Light→Satellite), locate-me
        // below it (re-centers on user). Both stay above the floating
        // tab-bar by clearing the navigation-bars inset + tab-bar height.
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(end = 14.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            MapFab(
                onClick = {
                    // Tapping the style cycles to the next style and
                    // pins the choice — auto-day/night respects it.
                    manualStyle = mapStyle.next()
                },
                content = { Text(mapStyle.emoji, fontSize = 20.sp) },
            )
            MapFab(
                onClick = {
                    // Single button, two semantics:
                    //   • follow ON  → "recenter" (refresh user position)
                    //   • follow OFF → "snap back to follow + north-up"
                    // Both paths set followMode=true and bump recenterTick;
                    // MapView's update block reads the tick and animates
                    // the camera to the user with bearing=0.
                    followMode = true
                    recenterTick++
                },
                content = {
                    if (followMode) {
                        Icon(
                            Icons.Default.MyLocation,
                            contentDescription = "Recenter on me",
                            tint = RideTokens.Accent,
                            modifier = Modifier.size(22.dp),
                        )
                    } else {
                        // Compass face — the arrow rotates inverse to the
                        // map's bearing so it always points to true north.
                        // (Map bearing = how far the map is rotated CW from
                        // north-up; the arrow needs the opposite rotation
                        // to stay pointing north visually.)
                        Icon(
                            Icons.Default.Navigation,
                            contentDescription = "Map rotated — tap to recenter and face north",
                            tint = RideTokens.Accent,
                            modifier = Modifier
                                .size(22.dp)
                                .rotate(-mapBearing),
                        )
                    }
                },
            )
        }

        // ── Bottom: route preview + avoid toggle chips ──────────────
        // Chips sit ABOVE the preview card so the rider can see the
        // route on the map while flipping them. Toggling any chip
        // re-rates the route through the existing planRouteTo flow
        // — the card flashes a brief "Updating…" state via
        // [RideState.isLoadingRoute].
        // Bottom stack:
        //   • Route-loading banner (when a plan/re-rate is in flight)
        //   • Avoid-chips row (when a route is on screen)
        //   • Route preview card (when a route is on screen)
        //   • Error banner (when [state.error] is non-null)
        //
        // We render them as one Column so they stack cleanly without
        // each overlapping the others. Empty cases collapse to zero
        // height — the layout still anchors to BottomCenter.
        if (state.routeShape.isNotEmpty() || state.isLoadingRoute || state.error != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 110.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // "Calculating route…" — sticks through both first-time
                // plans and re-rates triggered by persona or avoid
                // toggles, so the rider sees the app working rather
                // than guessing whether it's stuck.
                if (state.isLoadingRoute) {
                    com.scoova.navlayer.ui.ScoovaLoadingBanner(
                        label = if (state.routeShape.isEmpty()) "Calculating route…"
                                else "Updating route…",
                    )
                }
                state.error?.let { err ->
                    RouteErrorCard(message = err, onDismiss = onDismissError)
                }
                if (state.routeShape.isNotEmpty()) {
                    AvoidChipsRow(
                        avoidHighways = state.avoidHighways,
                        avoidTolls = state.avoidTolls,
                        avoidFerries = state.avoidFerries,
                        onAvoidHighways = onAvoidHighways,
                        onAvoidTolls = onAvoidTolls,
                        onAvoidFerries = onAvoidFerries,
                    )
                    RoutePreviewCard(
                        state = state,
                        onStart = onStart,
                        onSimulate = onSimulate,
                        onCancel = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Set-place wizard — opens when the user taps Home / Work chip
        // (either in HomeActions or in the SearchSheet's shortcuts row).
        // Lets them confirm "use current location" or search for a
        // different address. Was a silent save-current-fix before.
        SetPlaceWizard(
            target = setPlaceTarget,
            state = state,
            onSave = { lat, lon, label ->
                when (setPlaceTarget) {
                    PlaceTarget.Home -> onSaveAsHome(lat, lon, label)
                    PlaceTarget.Work -> onSaveAsWork(lat, lon, label)
                    null -> Unit
                }
                setPlaceTarget = null
            },
            onPickOnMap = {
                pinDropTarget = setPlaceTarget
                setPlaceTarget = null  // close sheet so the user can see the map
            },
            onDismiss = { setPlaceTarget = null },
        )

        // Pin-drop instruction banner — shown while the user is in
        // long-press-to-drop mode. Floats above all other top controls so
        // the call-to-action can't be missed. Tap "Cancel" to bail out.
        pinDropTarget?.let { t ->
            PinDropBanner(
                target = t,
                onCancel = { pinDropTarget = null },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        // POI bottom sheet — renders when the user tapped a named map
        // feature (POI, place, road label). Shows the localized name +
        // category + a one-tap "Route here" CTA.
        tappedPoi?.let { poi ->
            val label = poi.name ?: poi.featureClass ?: "Selected place"
            val starred = state.settings.favorites.any { fav ->
                "%.4f,%.4f".format(fav.lat, fav.lon) ==
                    "%.4f,%.4f".format(poi.lat, poi.lon)
            }
            PoiSheet(
                poi = poi,
                isStarred = starred,
                onRouteTo = {
                    onPickPreset(poi.lat, poi.lon, label)
                    tappedPoi = null
                },
                onToggleStar = { onToggleFavorite(poi.lat, poi.lon, label) },
                onDismiss = { tappedPoi = null },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 110.dp),
            )
        }
    }
}

/**
 * "What's nearby?" quick-chip row. Each chip seeds the search query
 * with a category keyword; the existing autocomplete flow pins the
 * matches on the map and surfaces them in the search sheet below the
 * pill — no separate code path, no new SDK surface area.
 *
 * Categories chosen to cover the ~80% common "I need this thing now"
 * cases: caffeine, fuel, food, parking. Adding more would push the
 * row past the screen width on a small device; if a fifth needs to
 * land, drop one rather than scrolling — discoverability wins.
 */
/**
 * Collapse / expand control for the quick-actions strip (Set Home /
 * Work + POI category chips). A compact chevron pill — riders who
 * already know their destination tap it to reclaim the screen for
 * the map; the strip springs back with one more tap.
 */
@Composable
private fun QuickActionsToggle(expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(RideTokens.Surface.copy(alpha = 0.92f))
            .border(1.dp, RideTokens.Border, RoundedCornerShape(20.dp))
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = RideTokens.Muted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            if (expanded) "Hide shortcuts" else "Shortcuts",
            color = RideTokens.Muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PoiCategoryChipsRow(onCategoryTap: (String) -> Unit) {
    val cats = listOf(
        "☕" to "Coffee",
        "⛽" to "Gas",
        "🍔" to "Food",
        "🅿️" to "Parking",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        cats.forEach { (emoji, label) ->
            Row(
                modifier = Modifier
                    .shadow(8.dp, RoundedCornerShape(20.dp), clip = false)
                    .background(RideTokens.Surface.copy(alpha = 0.96f), RoundedCornerShape(20.dp))
                    .border(1.dp, RideTokens.Border, RoundedCornerShape(20.dp))
                    .clickable { onCategoryTap(label.lowercase()) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(emoji, fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    color = RideTokens.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * Plan-screen error card. Renders the VM's user-facing route-failure
 * copy with a proper visual hierarchy:
 *   • Amber alert icon on the left (warning, not destructive — the
 *     user's data is fine, the route just didn't compute)
 *   • Bold title-style message (single short line, 1–2 lines max)
 *   • Dismiss "X" on the right that clears the error so the rider can
 *     plan another route without the banner lingering
 *
 * Background is dark surface (matches the rest of the floating UI),
 * not a saturated red — saturated red reads as "destructive / data
 * lost" and that's the wrong emotional register for "we couldn't
 * compute a route, try again". Amber accent on the icon + border
 * preserves the warning signal without being shouty.
 */
@Composable
private fun RouteErrorCard(message: String, onDismiss: () -> Unit) {
    val amber = Color(0xFFFFA94D)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(18.dp))
            .background(Color(0xFF15192A), RoundedCornerShape(18.dp))
            .border(1.dp, amber.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
            .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(amber.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "!",
                color = amber,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            message,
            color = RideTokens.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Dismiss",
                tint = RideTokens.Muted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun AvoidChipsRow(
    avoidHighways: Boolean,
    avoidTolls: Boolean,
    avoidFerries: Boolean,
    onAvoidHighways: (Boolean) -> Unit,
    onAvoidTolls: (Boolean) -> Unit,
    onAvoidFerries: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AvoidChip("Avoid highways", avoidHighways) { onAvoidHighways(!avoidHighways) }
        AvoidChip("Avoid tolls", avoidTolls) { onAvoidTolls(!avoidTolls) }
        AvoidChip("Avoid ferries", avoidFerries) { onAvoidFerries(!avoidFerries) }
    }
}

@Composable
private fun AvoidChip(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) RideTokens.Cyan else RideTokens.Surface
    val border = if (active) RideTokens.Cyan else RideTokens.Border
    val fg = if (active) Color.White else RideTokens.Muted
    Box(
        modifier = Modifier
            .shadow(8.dp, RoundedCornerShape(18.dp), clip = false)
            .background(bg.copy(alpha = if (active) 1f else 0.85f), RoundedCornerShape(18.dp))
            .border(1.dp, border, RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PoiSheet(
    poi: com.scoova.navlayer.maplibre.ScoovaMapFeature,
    isStarred: Boolean,
    onRouteTo: () -> Unit,
    onToggleStar: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = poi.name?.takeIf { it.isNotBlank() }
        ?: poi.subclass?.replaceFirstChar { it.uppercase() }
        ?: poi.featureClass?.replaceFirstChar { it.uppercase() }
        ?: "Unnamed place"
    val subtitle = listOfNotNull(
        poi.featureClass?.replaceFirstChar { it.uppercase() },
        poi.subclass?.takeIf { it != poi.featureClass },
    ).joinToString(" · ").ifBlank { "Map feature" }

    Row(
        modifier = modifier
            .shadow(20.dp, RoundedCornerShape(22.dp))
            .background(RideTokens.Surface, RoundedCornerShape(22.dp))
            .border(1.dp, RideTokens.Border, RoundedCornerShape(22.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = RideTokens.Text,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = RideTokens.Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        // Star toggle — filled+orange when saved, outline+muted when not.
        // Lives between the title and the primary CTA so the rider can
        // save without losing context. Tap doesn't dismiss the sheet.
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    if (isStarred) Color(0xFFFF6A00).copy(alpha = 0.18f) else RideTokens.Surface2,
                    CircleShape,
                )
                .border(
                    1.dp,
                    if (isStarred) Color(0xFFFF6A00) else RideTokens.Border,
                    CircleShape,
                )
                .clickable { onToggleStar() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isStarred) Icons.Filled.Star else Icons.Filled.StarOutline,
                contentDescription = if (isStarred) "Unsave" else "Save place",
                tint = if (isStarred) Color(0xFFFF6A00) else RideTokens.Muted,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .height(40.dp)
                .background(RideTokens.Cyan, RoundedCornerShape(12.dp))
                .clickable { onRouteTo() }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Route here", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(RideTokens.Surface2, CircleShape)
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Close, null, tint = RideTokens.Muted, modifier = Modifier.size(20.dp))
        }
    }
}

// ─── Pin-drop instruction banner ───────────────────────────────────────
//
// Renders only while [pinDropTarget] is non-null. Tells the user how to
// finish the action ("long-press anywhere") and offers a one-tap escape
// hatch. Sits above brand/search so it dominates the screen — the
// underlying controls are still visible but visually de-prioritized.

@Composable
private fun PinDropBanner(
    target: PlaceTarget,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 18.dp, shape = RoundedCornerShape(20.dp), clip = false)
            .background(RideTokens.Accent, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.MyLocation, null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Drop a pin for ${target.labelDefault}",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Long-press anywhere on the map.",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
                .clickable { onCancel() }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                "Cancel",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ─── Map FAB — compact circular floating button used on the right edge ─

@Composable
private fun MapFab(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .shadow(elevation = 14.dp, shape = CircleShape, clip = false)
            .background(RideTokens.Surface.copy(alpha = 0.96f), CircleShape)
            .border(1.dp, RideTokens.Border, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

// ─── Search pill (floats on top of map) ────────────────────────────────

@Composable
private fun SearchPill(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .shadow(elevation = 16.dp, shape = RoundedCornerShape(28.dp), clip = false)
            .background(RideTokens.Surface.copy(alpha = 0.96f), RoundedCornerShape(28.dp))
            .border(1.dp, RideTokens.Border, RoundedCornerShape(28.dp))
            .clickable(enabled = value.isEmpty()) { onFocus() }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = RideTokens.Accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    rideString("search.placeholder"),
                    color = RideTokens.Text.copy(alpha = 0.5f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = RideTokens.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(RideTokens.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { }),
            )
        }
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(RideTokens.Surface3, CircleShape)
                    .clickable { onClear() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Close, null, tint = RideTokens.Text, modifier = Modifier.size(14.dp))
            }
        }
    }
}

// ─── Persona picker (compact pill + dropdown of all modes) ─────────────
//
// Replaced the always-visible icon strip with a single pill: shows the
// current mode's emoji + chevron, tap to open a dropdown listing every
// profile. Frees the whole row below the search bar for other things
// (recent destinations, group ride status, ETA, etc.) and matches the
// Apple Maps / Google Maps pattern of one compact mode toggle on-map.

@Composable
private fun PersonaPicker(
    selected: Profile?,
    onPick: (Profile) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val profile = selected ?: Profile.entries.first()
    val shape = RoundedCornerShape(28.dp)

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .shadow(elevation = 12.dp, shape = shape, clip = false)
                .background(RideTokens.Surface.copy(alpha = 0.96f), shape)
                .border(1.dp, profile.accent.copy(alpha = 0.5f), shape)
                .clickable { expanded = true }
                .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(profile.emoji, fontSize = 18.sp)
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Change mode",
                tint = RideTokens.Muted,
                modifier = Modifier.size(18.dp),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = RideTokens.Surface,
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 16.dp,
        ) {
            for (p in Profile.entries) {
                val isSelected = selected == p
                DropdownMenuItem(
                    onClick = {
                        onPick(p)
                        expanded = false
                    },
                    leadingIcon = {
                        Text(p.emoji, fontSize = 20.sp)
                    },
                    text = {
                        Text(
                            p.localizedDisplay(LocalLocale.current),
                            color = if (isSelected) p.accent else RideTokens.Text,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        )
                    },
                    trailingIcon = if (isSelected) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = p.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else null,
                    colors = MenuDefaults.itemColors(
                        textColor = RideTokens.Text,
                    ),
                )
            }
        }
    }
}

// ─── Set-place wizard — pick Home / Work via search or current GPS ─────
//
// Replaces the old "tap chip → silently save current GPS fix" flow. Opens
// when the user taps Home/Work and the place isn't set yet. Lets them:
//   1) Use their current location (one-tap, brand orange CTA)
//   2) Search for an address (geocoding autocomplete, picks one from list)
//
// "Pick on the map" is intentionally deferred — needs a one-tap pin-drop
// mode that takes over the map and we don't have that surface yet. The
// hint at the bottom of the sheet points users there for v2.

enum class PlaceTarget(val title: String, val labelDefault: String) {
    Home("Set your home", "Home"),
    Work("Set your work", "Work"),
}

@Composable
private fun SetPlaceWizard(
    target: PlaceTarget?,
    state: RideViewModel.RideState,
    onSave: (lat: Double, lon: Double, label: String) -> Unit,
    onPickOnMap: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (target == null) return
    var query by remember(target) { mutableStateOf("") }
    var results by remember(target) { mutableStateOf(emptyList<SearchSuggestion>()) }
    var searching by remember(target) { mutableStateOf(false) }
    val searchClient = remember { SearchClient(apiKey = ScoovaApi.KEY) }

    // Debounce typing — match the 250ms cadence the main search uses.
    LaunchedEffect(query, target) {
        if (query.length < 2) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        kotlinx.coroutines.delay(250)
        val r = searchClient.autocomplete(
            text = query,
            focusLat = state.origin?.lat,
            focusLon = state.origin?.lon,
        )
        results = r
        searching = false
    }

    ScoovaModalSheet(open = true, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                target.title,
                color = RideTokens.Text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Where should we route you when you tap ${target.labelDefault}?",
                color = RideTokens.Muted,
                fontSize = 13.sp,
            )

            // Inline search field — geocoding autocomplete, same Pelias
            // endpoint as the main search bar, just scoped to the wizard.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(RideTokens.Surface2, RoundedCornerShape(22.dp))
                    .border(1.dp, RideTokens.Border, RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Search, null,
                    tint = RideTokens.Accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            "Search address",
                            color = RideTokens.Muted,
                            fontSize = 14.sp,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = RideTokens.Text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        cursorBrush = SolidColor(RideTokens.Accent),
                    )
                }
            }

            // Results list (if any) — picking a result saves immediately.
            if (results.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (s in results.take(5)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSave(s.lat, s.lon, s.label)
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.MyLocation, null,
                                tint = RideTokens.Muted,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                s.label,
                                color = RideTokens.Text,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else if (searching) {
                Text("Searching…", color = RideTokens.Muted, fontSize = 12.sp)
            }

            // Divider with "or" — only when there's no active query, so the
            // current-location CTA reads as the primary path.
            if (query.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f).height(1.dp).background(RideTokens.Border))
                    Text(
                        "  OR  ",
                        color = RideTokens.Muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                    )
                    Box(modifier = Modifier.weight(1f).height(1.dp).background(RideTokens.Border))
                }

                // Primary CTA — use current GPS fix. Disabled (subtle) if
                // we don't have a fix yet so the button isn't a lie.
                val originAvail = state.origin != null
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (originAvail) RideTokens.PrimaryButton
                            else Brush.horizontalGradient(
                                listOf(RideTokens.Surface3, RideTokens.Surface3),
                            ),
                            RoundedCornerShape(28.dp),
                        )
                        .clickable(enabled = originAvail) {
                            val o = state.origin ?: return@clickable
                            onSave(o.lat, o.lon, target.labelDefault)
                        }
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.MyLocation, null,
                        tint = if (originAvail) Color.White else RideTokens.Muted,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (originAvail) "Use my current location"
                        else "Waiting for GPS fix…",
                        color = if (originAvail) Color.White else RideTokens.Muted,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                // Tertiary CTA — exit the sheet and switch the map into
                // pin-drop mode. The instruction banner takes over from
                // here. Visually de-emphasized vs. the primary GPS button
                // so the common path stays one tap.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(RideTokens.Surface3, RoundedCornerShape(20.dp))
                        .clickable { onPickOnMap() }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.LocationOn, null,
                        tint = RideTokens.Accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Pick on the map",
                        color = RideTokens.Text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

// ─── Brand header — wordmark + time-aware greeting ─────────────────────
//
// Sits above the search row. Gives the app a real identity instead of
// dropping the user straight into a search field. Greeting changes by
// hour: Good morning / afternoon / evening / late ride. The wordmark uses
// brand orange + tracked letterspacing so it reads as a logo without
// shipping a separate image asset.

@Composable
private fun BrandGreeting(
    modifier: Modifier = Modifier,
    locale: String = "en-US",
) {
    val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    val greetingKey = when (hour) {
        in 5..11  -> "greeting.morning"
        in 12..16 -> "greeting.afternoon"
        in 17..21 -> "greeting.evening"
        else      -> "greeting.lateride"
    }
    val greeting = Strings.t(greetingKey, locale)
    Column(modifier = modifier) {
        Text(
            "SCOOVA",
            color = RideTokens.Accent,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 3.sp,
        )
        Text(
            greeting,
            color = RideTokens.Text.copy(alpha = 0.85f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ─── Home actions — quick destinations strip ───────────────────────────
//
// One-tap row right under the search bar: Home, Work, and the user's last
// three distinct destinations. Pure on-map control (no sheet open), so the
// user can re-route home from a cold app in a single tap. Mirrors the
// "Get directions to..." pattern in Apple Maps / Google Maps.

@Composable
private fun HomeActions(
    state: RideViewModel.RideState,
    onSaveAsHome: () -> Unit,
    onSaveAsWork: () -> Unit,
    onPlanToSavedPlace: (SavedPlace) -> Unit,
    onPickRecent: (Double, Double, String) -> Unit,
) {
    val recents = remember(state.history) {
        state.history
            .asSequence()
            .filter { !it.destinationLabel.isNullOrBlank() && it.destLat != null && it.destLon != null }
            .distinctBy { it.destinationLabel!!.lowercase() }
            .take(3)
            .toList()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuickDestChip(
            icon = Icons.Default.Home,
            label = state.settings.homePlace?.label ?: rideString("place.home.set"),
            accent = RideTokens.Accent,
            filled = state.settings.homePlace != null,
            onClick = {
                state.settings.homePlace?.let { onPlanToSavedPlace(it) } ?: onSaveAsHome()
            },
        )
        QuickDestChip(
            icon = Icons.Default.Business,
            label = state.settings.workPlace?.label ?: rideString("place.work.set"),
            accent = RideTokens.AccentSoft,
            filled = state.settings.workPlace != null,
            onClick = {
                state.settings.workPlace?.let { onPlanToSavedPlace(it) } ?: onSaveAsWork()
            },
        )
        // Starred favorites — saved from the POI bottom sheet. Sit
        // between Home/Work and Recents so the rider's curated list
        // gets first-class real estate.
        for (fav in state.settings.favorites) {
            QuickDestChip(
                icon = Icons.Filled.Star,
                label = fav.label,
                accent = Color(0xFFFF6A00),
                filled = true,
                onClick = { onPickRecent(fav.lat, fav.lon, fav.label) },
            )
        }
        for (r in recents) {
            QuickDestChip(
                icon = Icons.Default.MyLocation,
                label = r.destinationLabel ?: "Recent",
                accent = RideTokens.Muted,
                filled = true,
                onClick = {
                    val lat = r.destLat ?: return@QuickDestChip
                    val lon = r.destLon ?: return@QuickDestChip
                    onPickRecent(lat, lon, r.destinationLabel ?: "Recent")
                },
            )
        }
    }
}

@Composable
private fun QuickDestChip(
    icon: ImageVector,
    label: String,
    accent: Color,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = Modifier
            .shadow(elevation = 10.dp, shape = shape, clip = false)
            .background(
                if (filled) RideTokens.Surface.copy(alpha = 0.96f)
                else RideTokens.Surface.copy(alpha = 0.70f),
                shape,
            )
            .border(
                1.dp,
                if (filled) accent.copy(alpha = 0.45f) else RideTokens.Border,
                shape,
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (filled) accent else RideTokens.Muted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = if (filled) RideTokens.Text else RideTokens.Muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ─── Weather chip (top-right, next to brand header) ────────────────────
//
// Always renders so the top-right corner is visually anchored. While the
// snapshot is null (no GPS fix yet, or fetch in flight) we show a muted
// cloud + dash placeholder; once data arrives, the emoji and degree swap
// in. The chip stays the same size in both states so nothing jumps.

@Composable
private fun WeatherChip(weather: WeatherSnapshot?, unitsMetric: Boolean = true) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .shadow(elevation = 12.dp, shape = shape, clip = false)
            .background(RideTokens.Surface.copy(alpha = 0.96f), shape)
            .border(1.dp, RideTokens.Border, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(weather?.emoji ?: "☁️", fontSize = 16.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            weather?.let {
                if (unitsMetric) "${it.temperatureC.toInt()}°C"
                else "${(it.temperatureC * 9.0 / 5.0 + 32.0).toInt()}°F"
            } ?: "—°",
            color = if (weather != null) RideTokens.Text else RideTokens.Muted,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ─── Search sheet (recents + shortcuts + results) ──────────────────────

@Composable
private fun SearchSheet(
    state: RideViewModel.RideState,
    onPickSearchResult: (SearchSuggestion) -> Unit,
    onPlanToSavedPlace: (SavedPlace) -> Unit,
    onSaveAsHome: () -> Unit,
    onSaveAsWork: () -> Unit,
    onPickRecent: (Double, Double, String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(20.dp, RoundedCornerShape(20.dp), clip = false)
            .background(RideTokens.Surface, RoundedCornerShape(20.dp))
            .border(1.dp, RideTokens.Border, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        when {
            state.searchResults.isNotEmpty() -> {
                SearchResultsList(
                    results = state.searchResults,
                    origin = state.origin,
                    onPick = onPickSearchResult,
                )
            }
            state.isSearching -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.scoova.navlayer.ui.ScoovaLoadingDot()
                    Spacer(Modifier.width(10.dp))
                    Text("Searching…", color = RideTokens.Muted, fontSize = 13.sp)
                }
            }
            state.searchQuery.length >= 2 -> {
                Text(
                    "No results. Long-press the map to drop a pin instead.",
                    color = RideTokens.Muted, fontSize = 13.sp,
                )
            }
            else -> {
                // Empty / focused — show shortcuts + recents
                ShortcutsAndRecents(
                    state = state,
                    onPlanToSavedPlace = onPlanToSavedPlace,
                    onSaveAsHome = onSaveAsHome,
                    onSaveAsWork = onSaveAsWork,
                    onPickRecent = onPickRecent,
                )
            }
        }
    }
}

@Composable
private fun ShortcutsAndRecents(
    state: RideViewModel.RideState,
    onPlanToSavedPlace: (SavedPlace) -> Unit,
    onSaveAsHome: () -> Unit,
    onSaveAsWork: () -> Unit,
    onPickRecent: (Double, Double, String) -> Unit,
) {
    // Shortcuts row
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShortcutChip(
            icon = Icons.Default.Home,
            label = state.settings.homePlace?.label ?: rideString("place.home.set"),
            accent = RideTokens.Accent,
            modifier = Modifier.weight(1f),
            onClick = {
                state.settings.homePlace?.let { onPlanToSavedPlace(it) } ?: onSaveAsHome()
            },
        )
        ShortcutChip(
            icon = Icons.Default.Business,
            label = state.settings.workPlace?.label ?: rideString("place.work.set"),
            accent = RideTokens.AccentSoft,
            modifier = Modifier.weight(1f),
            onClick = {
                state.settings.workPlace?.let { onPlanToSavedPlace(it) } ?: onSaveAsWork()
            },
        )
    }

    val recents = state.history
        .asSequence()
        .filter { !it.destinationLabel.isNullOrBlank() }
        .distinctBy { it.destinationLabel!!.lowercase() }
        .take(3)
        .toList()

    if (recents.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text(
            "RECENT",
            color = RideTokens.Accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
        )
        Spacer(Modifier.height(8.dp))
        for ((i, r) in recents.withIndex()) {
            RecentRow(r, onTap = onPickRecent)
            if (i < recents.lastIndex) Spacer(Modifier.height(6.dp))
        }
    } else {
        Spacer(Modifier.height(12.dp))
        Text(
            "Type a place above, or long-press the map to drop a pin.",
            color = RideTokens.Muted, fontSize = 12.sp,
        )
    }
}

@Composable
private fun SearchResultsList(
    results: List<SearchSuggestion>,
    origin: LatLon?,
    onPick: (SearchSuggestion) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        for ((i, s) in results.withIndex()) {
            val km = origin?.let { haversineKm(it, s.lat, s.lon) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(s) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.MyLocation,
                    null,
                    tint = RideTokens.Accent.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        s.label,
                        color = RideTokens.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    s.category?.let { cat ->
                        Text(cat, color = RideTokens.Muted, fontSize = 11.sp)
                    }
                }
                if (km != null) {
                    Text(formatKm(km), color = RideTokens.Muted, fontSize = 12.sp)
                }
            }
            if (i < results.lastIndex) {
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(RideTokens.Border))
            }
        }
    }
}

@Composable
private fun RecentRow(record: RideRecord, onTap: (Double, Double, String) -> Unit) {
    val label = record.destinationLabel ?: "Ride"
    val canReroute = record.destLat != null && record.destLon != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RideTokens.Surface2, RoundedCornerShape(12.dp))
            .border(1.dp, RideTokens.Border, RoundedCornerShape(12.dp))
            .clickable(enabled = canReroute) {
                onTap(record.destLat!!, record.destLon!!, label)
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(record.profile?.emoji ?: "📍", fontSize = 20.sp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label, color = RideTokens.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${"%.1f".format(record.coveredKm)} km · ${if (canReroute) "tap to re-ride" else "last ride"}",
                color = RideTokens.Muted, fontSize = 11.sp,
            )
        }
        if (canReroute) {
            Text("›", color = RideTokens.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ShortcutChip(
    icon: ImageVector,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .background(RideTokens.Surface2, RoundedCornerShape(14.dp))
            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = RideTokens.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ─── Route preview (bottom card — only when route exists) ──────────────

@Composable
private fun RoutePreviewCard(
    state: RideViewModel.RideState,
    onStart: () -> Unit,
    onSimulate: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .shadow(20.dp, RoundedCornerShape(22.dp))
            .background(RideTokens.Surface.copy(alpha = 0.97f), RoundedCornerShape(22.dp))
            .border(1.dp, RideTokens.Border, RoundedCornerShape(22.dp))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                val locale = LocalLocale.current
                Text(
                    state.destinationLabel ?: rideString("summary.fallback_label"),
                    color = RideTokens.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val metric = state.settings.unitsMetric
                val distVal = if (metric) "%.1f".format(state.routeDistanceKm) else "%.1f".format(state.routeDistanceKm * 0.621371)
                val distUnit = if (metric) "km" else "mi"
                Text(
                    "$distVal $distUnit · ${state.routeDurationMin} ${rideString("insights.min")} · ${state.profile?.localizedDisplay(locale) ?: ""}",
                    color = RideTokens.Muted, fontSize = 12.sp,
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(RideTokens.Surface2, CircleShape)
                    .clickable { onCancel() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Close, null, tint = RideTokens.Muted, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryCta(rideString("route.simulate"), Icons.Default.Speed, Modifier.weight(1f), onSimulate)
            PrimaryCta(rideString("route.start"), Icons.Default.PlayArrow, Modifier.weight(1.5f), onStart)
        }
    }
}

// ─── Ride / Summary screens (unchanged) ─────────────────────────────────

@Composable
fun RideScreen(
    state: RideViewModel.RideState,
    nav: ScoovaNavLayer,
    isOffRoute: Boolean,
    isRerouting: Boolean,
    batteryLow: Boolean = false,
    batteryPercent: Int = 100,
    /** Rotation-vector accuracy reported by the OS — 0..3. Drives
     *  the calibration banner when ≤ 1 (LOW / UNRELIABLE). */
    compassAccuracy: Int = 3,
    onEnd: () -> Unit,
    onSwitchProfile: (Profile) -> Unit = {},
    onToggleVoice: (Boolean) -> Unit = {},
) {
    val cue by nav.currentInstruction.collectAsState()
    val heading by nav.headingDeg.collectAsState()
    // Same follow / compass story as Plan — user can pan during a ride
    // to glance at the wider area; a single FAB swaps between locate-me
    // and compass faces to bring them back.
    var followMode by remember { mutableStateOf(true) }
    var mapBearing by remember { mutableFloatStateOf(0f) }
    var recenterTick by remember { mutableStateOf(0) }

    // Crash-detect modal. The nav layer emits crash + hard-brake events
    // on a one-shot SharedFlow; we mirror the most recent one into a
    // local state slot so the prompt can be dismissed (tap "I'm OK")
    // without dropping the event from the SDK's stream.
    var crash: CrashEvent? by remember { mutableStateOf(null) }
    LaunchedEffect(nav) {
        nav.crashEvents.collect { evt -> crash = evt }
    }

    // Guidance-cue overlay — every "slow down", "wrong way", "drift
    // left", etc. surfaces here as a transient banner so the rider
    // SEES what they hear. Auto-clears 3.5 s after the last cue.
    var guidanceCue: com.scoova.navlayer.core.ScoovaNavLayer.GuidanceCue? by remember {
        mutableStateOf(null)
    }
    LaunchedEffect(nav) {
        nav.guidanceCues.collect { cue ->
            guidanceCue = cue
        }
    }
    LaunchedEffect(guidanceCue) {
        val current = guidanceCue ?: return@LaunchedEffect
        kotlinx.coroutines.delay(3_500L)
        // Only clear if it's still the same cue — a newer one may
        // have arrived during the delay.
        if (guidanceCue?.tsMs == current.tsMs) guidanceCue = null
    }

    Box(modifier = Modifier.fillMaxSize().background(RideTokens.Bg)) {
        // Active-navigation camera: heading-up follow mode + 45° 3D tilt.
        // Puck stays at screen-centre, the map rotates beneath it to
        // the rider's bearing, tilted to the standard nav perspective
        // so the upcoming road geometry reads at a glance.
        //
        // This is the ONLY screen where tilt is on. Plan, route
        // preview, History, Insights and Summary all keep the default
        // top-down 2D view from [RideMap] so the rider can read the
        // city as a map. See [RideMap.followTiltEnabled].
        val effectiveFollow = followMode
        // 3D tilt only on real hardware. The Android emulator's
        // SwiftShader-backed GLES encoder segfaults inside MapLibre's
        // RenderThread the moment the camera pitches with an active
        // follow camera (`libc: Fatal signal 11 (SIGSEGV) at addr 0x30
        // in RenderThread`). Real Mali / Adreno / Apple GPUs render
        // this fine; the runtime guard is just for the dev / CI loop
        // so simulator-driven QA doesn't hit a crash loop.
        val tiltEnabled = !isProbablyEmulator()
        RideMap(
            modifier = Modifier.fillMaxSize(),
            data = RideMapData(
                userLat = state.origin?.lat,
                userLon = state.origin?.lon,
                destLat = state.destination?.lat,
                destLon = state.destination?.lon,
                routeShape = state.routeShape,
                followUser = effectiveFollow,
                bearingDeg = heading,
            ),
            recenterTick = recenterTick,
            locale = state.settings.locale,
            followTiltEnabled = tiltEnabled,
            pathMode = state.profile?.pathHighlightMode
                ?: com.scoova.navlayer.maplibre.PathHighlightMode.Motor,
            onUserGesture = { followMode = false },
            onBearingChange = { mapBearing = it },
        )

        cue?.let {
            // Compact top-left maneuver card — Apple-Maps / Tesla
            // style. Smaller footprint than the full-width banner so
            // the 3D tilt map underneath stays the focus.
            ScoovaCompactManeuverCard(
                cue = it,
                unitsMetric = state.settings.unitsMetric,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 14.dp, top = 16.dp),
            )
        }

        // Guidance cue banner — slim, sits directly under the maneuver
        // banner. Shows whatever was JUST spoken ("slow down", "wrong
        // way please turn around", "drift left", "keep going") so the
        // rider has a visual confirmation matching the voice. Tone
        // drives colour: Alert = red, Urgent = orange, anything else
        // = cyan.
        guidanceCue?.let { g ->
            val (bg, fg) = when (g.toneName) {
                "Alert"  -> Color(0xFFFF4D4D) to Color.White
                "Urgent" -> Color(0xFFFF6A00) to Color.White
                else     -> RideTokens.Cyan to Color.White
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 16.dp, end = 16.dp, top = 130.dp)
                    .shadow(14.dp, RoundedCornerShape(14.dp))
                    .background(bg, RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    g.text,
                    color = fg,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // Single source of truth for the puck: the SDK's GeoJSON marker
        // (SRC_USER) drawn at `userLat/userLon` by [ScoovaMapView]. The
        // follow camera targets the same coordinate, so the map puck
        // appears at the camera's center — exactly where the rider's
        // eyes are. A second Compose-overlay puck used to sit here at
        // `Alignment.Center + top=100.dp` "decoratively", but it was
        // 100 dp below the map puck, so the rider saw two distinct
        // pucks (centered Compose puck, and an off-centre map puck
        // wherever the camera was pointing). Removed — one puck only,
        // anchored to where the SDK renders it.

        // Persona picker intentionally NOT shown during navigation —
        // mid-trip persona switching is a Plan-screen action. Keeping
        // the Ride canvas focused on the map + maneuver card + ETA
        // matches what Apple Maps / Google / Waze do.

        // Right-edge FAB stack — recenter on top, voice toggle below.
        // Vertically centered on the screen so the rider's thumb (in
        // the lower-right of the device) reaches them without crossing
        // the maneuver card area. Matches the layout reference
        // screenshot.
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MapFab(
                onClick = {
                    followMode = true
                    recenterTick++
                },
                content = {
                    if (followMode) {
                        Icon(
                            Icons.Default.MyLocation,
                            contentDescription = "Recenter on me",
                            tint = RideTokens.Accent,
                            modifier = Modifier.size(22.dp),
                        )
                    } else {
                        Icon(
                            Icons.Default.Navigation,
                            contentDescription = "Map rotated — tap to recenter and follow heading",
                            tint = RideTokens.Accent,
                            modifier = Modifier
                                .size(22.dp)
                                .rotate(-mapBearing),
                        )
                    }
                },
            )
            MapFab(
                onClick = { onToggleVoice(!state.settings.voiceEnabled) },
                content = {
                    Icon(
                        if (state.settings.voiceEnabled) Icons.AutoMirrored.Filled.VolumeUp
                        else Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = if (state.settings.voiceEnabled) "Mute voice cues"
                                             else "Un-mute voice cues",
                        tint = if (state.settings.voiceEnabled) RideTokens.Accent
                               else RideTokens.Muted,
                        modifier = Modifier.size(22.dp),
                    )
                },
            )
        }

        // Compass calibration prompt. Surfaces when the rotation-
        // vector sensor reports LOW (1) or UNRELIABLE (0) — heading
        // is drifting enough that follow-mode would mislead the rider.
        // Hides automatically once the rider waves the phone enough
        // for the OS to upgrade to MEDIUM/HIGH.
        if (compassAccuracy <= 1) {
            ScoovaCompassCalibrationBanner(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 86.dp, start = 14.dp, end = 14.dp),
            )
        }

        // Battery-low warning. Floats just below the maneuver banner
        // so the rider sees it without the ETA card real-estate being
        // affected. Shows once the threshold has been crossed (see
        // BatteryAwareNav hysteresis), clears when charging plugs in.
        if (batteryLow) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 86.dp, start = 80.dp, end = 80.dp)
                    .shadow(10.dp, RoundedCornerShape(14.dp))
                    .background(Color(0xFFFF6A00), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Battery $batteryPercent%  ·  voice-only mode helps",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // ── Bottom: ETA card from :ui module + thin speed pill + stop ─
        // The SDK's ScoovaEtaCard owns the three-cell readout (arrival
        // clock, remaining time, remaining distance) and the off-route
        // / rerouting banners. The thin row below it shows live speed
        // and the end-ride control — kept compact so the map underneath
        // stays the focal point.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val metric = state.settings.unitsMetric
            val coveredKm = state.coveredKm
            val totalKm = state.routeDistanceKm.coerceAtLeast(0.001)
            val remainingKm = (totalKm - coveredKm).coerceAtLeast(0.0)
            val totalMin = state.routeDurationMin
            val progress = (coveredKm / totalKm).coerceIn(0.0, 1.0)
            val minutesRemaining = (totalMin * (1.0 - progress)).toInt().coerceAtLeast(0)

            ScoovaEtaCard(
                distanceRemainingKm = remainingKm,
                minutesRemaining = minutesRemaining,
                unitsMetric = metric,
                isOffRoute = isOffRoute,
                isRerouting = isRerouting,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                val speedVal = if (metric) state.currentSpeedKph.toInt() else (state.currentSpeedKph * 0.621371f).toInt()
                val speedUnit = if (metric) "km/h" else "mph"
                // Live speed + optional posted-limit shield via the SDK
                // chip. speedLimit stays null for now (routing doesn't
                // surface maxspeed yet); the chip degrades gracefully.
                ScoovaSpeedChip(
                    speed = speedVal,
                    unitLabel = speedUnit,
                    speedLimit = null,
                )
                Spacer(Modifier.weight(1f))
                // Share ETA — launches the system chooser via the SDK
                // helper. Quiet styling vs the red end-ride button so
                // it doesn't compete for attention; this is a
                // secondary action.
                val ctx = LocalContext.current
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(RideTokens.Surface, CircleShape)
                        .border(1.dp, RideTokens.Border, CircleShape)
                        .clickable {
                            com.scoova.navlayer.core.ScoovaEtaShare.launch(
                                ctx = ctx,
                                minutesRemaining = minutesRemaining,
                                destinationLabel = state.destinationLabel,
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Share, null,
                        tint = RideTokens.Cyan2,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(RideTokens.Red, CircleShape)
                        .clickable { onEnd() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Stop, null, tint = Color.White)
                }
            }
        }

        // Crash-detect modal sits at the END of the Box so it overlays
        // every other ride control. The prompt's own scrim swallows
        // touches; the rider has to tap "I'm OK" (dismiss) or wait the
        // countdown out (timeout). Demo wires both to "clear local
        // state and keep riding" — a host app would call its emergency
        // contact integration from [onTimeout] / [onCallNow].
        crash?.let { evt ->
            ScoovaCrashPrompt(
                event = evt,
                onDismiss = { crash = null },
                onTimeout = { crash = null },
                onCallNow = { crash = null },
            )
        }

        // Feature-tour overlay. Renders only while [RideState.isTour]
        // is true (set by [RideViewModel.startTour] from onboarding's
        // post-tour dialog or Settings → About → Take a tour). As the
        // synthetic ride progresses, [TourOverlay] crosses distance
        // fractions and reveals one callout at a time explaining each
        // core feature (voice + spatial audio, sensor-fused puck,
        // off-route, eyes-off, arrival). Each card is dismissable —
        // a slow rider can skim, a quick rider can tap through.
        if (state.isTour) {
            TourOverlay(
                state = state,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 14.dp, end = 14.dp, bottom = 170.dp),
            )
        }
    }
}

private data class TourMark(
    val triggerFraction: Float,
    val title: String,
    val body: String,
    val icon: ImageVector,
)

private val tourMarks: List<TourMark> = listOf(
    TourMark(
        triggerFraction = 0.02f,
        title = "Spatial-audio voice",
        body = "Turn cues come from the direction of your next turn — left or right pans in your headphones, so you can ride with eyes on the road.",
        icon = Icons.AutoMirrored.Filled.VolumeUp,
    ),
    TourMark(
        triggerFraction = 0.22f,
        title = "Heading puck",
        body = "Scoova fuses GPS with the phone's gyroscope and compass, so the puck points the right way even when you're standing still or moving slowly.",
        icon = Icons.Default.Navigation,
    ),
    TourMark(
        triggerFraction = 0.45f,
        title = "Off-route detection",
        body = "If you take a wrong turn, Scoova reroutes automatically and you'll hear a fresh cue — no tap, no manual refresh, no missed turns.",
        icon = Icons.Default.MyLocation,
    ),
    TourMark(
        triggerFraction = 0.68f,
        title = "Eyes-off mode",
        body = "Enable Eyes-off in Settings to swap distance cues for landmark cues — \"after the cafe, turn right\" — perfect for phone-in-pocket riding.",
        icon = Icons.Default.Headphones,
    ),
    TourMark(
        triggerFraction = 0.92f,
        title = "Arrival & save",
        body = "Every ride lands in History with distance, time and average speed. Sign in to back rides up to the cloud and sync across devices.",
        icon = Icons.Default.Check,
    ),
)

/**
 * Overlay rendered on the Ride screen during a tour. Watches the
 * rider's progress fraction and pops the next [TourMark] when the
 * threshold crosses. One card visible at a time; the rider can tap
 * "Got it" to advance early or wait for the next mark to overwrite
 * it. Auto-fades out a few seconds after the last mark — the
 * arrival summary picks up the wrap.
 */
@Composable
private fun TourOverlay(
    state: RideViewModel.RideState,
    modifier: Modifier = Modifier,
) {
    val totalKm = state.routeDistanceKm.coerceAtLeast(0.0001)
    val fraction = (state.coveredKm / totalKm).toFloat().coerceIn(0f, 1f)
    var currentIdx by remember { mutableStateOf(-1) }
    LaunchedEffect(fraction) {
        val next = tourMarks.indexOfLast { fraction >= it.triggerFraction }
        if (next > currentIdx) currentIdx = next
    }
    val mark = currentIdx.takeIf { it in tourMarks.indices }?.let { tourMarks[it] } ?: return
    Column(
        modifier = modifier
            .shadow(20.dp, RoundedCornerShape(20.dp), clip = false)
            .background(RideTokens.Surface, RoundedCornerShape(20.dp))
            .border(1.dp, RideTokens.Border, RoundedCornerShape(20.dp))
            .clickable { currentIdx = (currentIdx + 1).coerceAtMost(tourMarks.size) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(RideTokens.Accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(mark.icon, null, tint = RideTokens.Accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "TOUR · ${currentIdx + 1} OF ${tourMarks.size}",
                    color = RideTokens.Accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.4.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    mark.title,
                    color = RideTokens.Text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            mark.body,
            color = RideTokens.TextMuted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap to continue",
            color = RideTokens.Muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun SummaryScreen(
    state: RideViewModel.RideState,
    onPlanAnother: () -> Unit,
    onSaveNote: (String) -> Unit = {},
    onAckMilestone: () -> Unit = {},
    signedIn: Boolean = false,
    onUploadRide: suspend (RideRecord) -> Boolean = { false },
    onStartAuth: () -> Unit = {},
) {
    val durationMin = ((state.rideEndedAtMs - state.rideStartedAtMs) / 60000).toInt().coerceAtLeast(1)
    val avgKph = if (durationMin > 0) (state.coveredKm / (durationMin / 60.0)).toInt() else 0
    val metric = state.settings.unitsMetric
    val distVal = if (metric) "%.1f".format(state.coveredKm) else "%.1f".format(state.coveredKm * 0.621371)
    val avgVal = if (metric) avgKph else (avgKph * 0.621371).toInt()
    // Health metrics from :core. Profile.Car maps to Driving (~1.5
    // MET) so the calorie display correctly stays modest for a car
    // commute; cyclist mode lights it up.
    val mode = (state.profile ?: Profile.Bicycle).metricsMode
    val calories = com.scoova.navlayer.core.RideMetrics.caloriesBurned(
        mode = mode,
        distanceKm = state.coveredKm,
        durationMinutes = durationMin,
        weightKg = state.settings.weightKg.toDouble(),
    )
    val activeMin = com.scoova.navlayer.core.RideMetrics.activeMinutes(mode, durationMin)
    val co2Grams = com.scoova.navlayer.core.RideMetrics.co2SavedGrams(mode, state.coveredKm)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(RideTokens.Bg, RideTokens.Surface)))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "${rideString("summary.complete")} 🎉",
            color = RideTokens.Text,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            state.destinationLabel ?: rideString("summary.fallback_label"),
            color = RideTokens.Muted,
            fontSize = 14.sp,
        )

        // Save-to-cloud card. Renders only when the rider has set
        // saveMode != Never AND there's a just-finished ride to act
        // on. The card surfaces three flows depending on signed-in
        // state + saveMode:
        //   • Always + signed in → auto-uploads on first render,
        //     shows "Saved ✓" or "Failed — retry" + Discard override.
        //   • Ask + signed in → manual "Save this ride" / "Discard".
        //   • Either + signed out → "Sign in to back up this ride" CTA.
        val latestRide = state.history.firstOrNull()
        if (latestRide != null && state.settings.saveMode != SaveMode.Never) {
            Spacer(Modifier.height(14.dp))
            SaveRideCard(
                ride = latestRide,
                saveMode = state.settings.saveMode,
                signedIn = signedIn,
                onUploadRide = onUploadRide,
                onStartAuth = onStartAuth,
            )
        }

        // Simulation diagnostic report. Only present when this ride
        // was driven by the simulator (real-GPS rides leave it null).
        // Surfaces the per-maneuver cue timing + an anomaly list so a
        // dev / QA can verify cues fired at the right meters from
        // each turn.
        state.simulationReport?.let { report ->
            Spacer(Modifier.height(14.dp))
            SimReportCard(report)
        }

        // Lifetime-milestone banner. Fires when the rider crossed a
        // threshold on this ride (10 / 50 / 100 / 250 / 500 / 1k km,
        // …). One tap dismisses; otherwise it survives until the
        // rider goes back to Plan.
        state.milestoneJustCrossed?.let { km ->
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(14.dp, RoundedCornerShape(18.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFFFF6A00), Color(0xFFFFCB05)),
                        ),
                        RoundedCornerShape(18.dp),
                    )
                    .clickable { onAckMilestone() }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🏆", fontSize = 28.sp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Milestone unlocked",
                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                    )
                    Text(
                        "You've ridden ${km} km on Scoova",
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Route preview card ─────────────────────────────────────
        // Renders the rider's actual breadcrumb trail (captured every
        // GPS tick during the ride) as a cyan polyline on the live map.
        // Falls back to the planned route shape when the trail is
        // empty — only happens if the ride was canceled instantly
        // (no GPS samples received between start and end).
        val pathToShow = state.actualPath.ifEmpty { state.routeShape }
        if (pathToShow.size >= 2) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .shadow(20.dp, RoundedCornerShape(22.dp))
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.dp, RideTokens.Border, RoundedCornerShape(22.dp)),
            ) {
                RideMap(
                    modifier = Modifier.fillMaxSize(),
                    data = RideMapData(
                        userLat = pathToShow.first()[0],
                        userLon = pathToShow.first()[1],
                        destLat = state.destination?.lat ?: pathToShow.last()[0],
                        destLon = state.destination?.lon ?: pathToShow.last()[1],
                        routeShape = pathToShow,
                        followUser = false,
                    ),
                    locale = state.settings.locale,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        BigStat(
            value = distVal,
            unit = if (metric) rideString("summary.unit_km") else rideString("summary.unit_mi"),
            color = RideTokens.Lime,
        )
        Spacer(Modifier.height(20.dp))
        BigStat(value = "$durationMin", unit = rideString("summary.unit_min"), color = RideTokens.Sun)
        Spacer(Modifier.height(20.dp))
        BigStat(
            value = "$avgVal",
            unit = if (metric) rideString("summary.unit_kmh") else rideString("summary.unit_mph"),
            color = RideTokens.Cyan,
        )

        Spacer(Modifier.height(24.dp))

        // Active-minutes + calories + CO₂-saved pill. CO₂ is the
        // motivational metric — riders who chose cycling over driving
        // see how many grams they kept out of the air. Hidden when
        // all three are zero (a sub-minute ride or pure passive
        // transit). CO₂ cell hides for Mode.Driving (the saved value
        // is genuinely zero).
        if (activeMin > 0 || calories > 0 || co2Grams > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(14.dp, RoundedCornerShape(18.dp))
                    .background(RideTokens.Surface, RoundedCornerShape(18.dp))
                    .border(1.dp, RideTokens.Border, RoundedCornerShape(18.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SummaryHealthCell(
                    label = "Active",
                    value = "$activeMin min",
                    color = RideTokens.Cyan2,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .width(1.dp)
                        .height(36.dp)
                        .background(RideTokens.Border),
                )
                SummaryHealthCell(
                    label = "Calories",
                    value = "$calories kcal",
                    color = Color(0xFFFF6A00),
                    modifier = Modifier.weight(1f),
                )
                if (co2Grams > 0) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(36.dp)
                            .background(RideTokens.Border),
                    )
                    val co2Label = if (co2Grams >= 1000)
                        "%.1f kg".format(co2Grams / 1000.0)
                    else
                        "$co2Grams g"
                    SummaryHealthCell(
                        label = "CO₂ saved",
                        value = co2Label,
                        color = Color(0xFF22C55E),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Trip notes — short free-text the rider attaches to this
        // ride. Persisted on the RideRecord; shows in History detail
        // for later reference. Cap is 240 chars to keep the History
        // row tidy.
        TripNotesRow(
            initial = state.history.firstOrNull()?.notes.orEmpty(),
            onSave = onSaveNote,
        )

        Spacer(Modifier.height(16.dp))

        // 1-tap rating. Three emoji buttons; tapping records the
        // sentiment locally (no telemetry call — this is purely a UX
        // signal the rider can glance at in their history). State is
        // session-local for now; future versions can fold it into
        // RideRecord so insights track average sentiment over time.
        TripRatingRow()

        Spacer(Modifier.height(24.dp))

        PrimaryCta(
            rideString("summary.plan_another_cta"),
            Icons.AutoMirrored.Filled.DirectionsBike,
            Modifier,
            onPlanAnother,
        )

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Per-ride save-to-cloud card on the Summary screen.
 *
 * Drives three flows based on [saveMode] + [signedIn]:
 *
 *  • Already uploaded ([ride.cloudDocId] != null): green "Saved" badge,
 *    no buttons.
 *  • [SaveMode.Always] + signed in: auto-fires [onUploadRide] in a
 *    LaunchedEffect on first composition, shows live status (uploading
 *    spinner → saved / retry on fail). A "Don't save this one" link
 *    lets the rider opt out for THIS ride only.
 *  • [SaveMode.AskEachTime] + signed in: manual "Save this ride" +
 *    "Discard" buttons. No auto-upload.
 *  • Signed-out (any mode): "Sign in to back up this ride" CTA;
 *    [onStartAuth] launches AuthScreen.
 *
 * After the upload completes, the [RideRecord] in [state.history] gets
 * its `cloudDocId` populated, which collapses this card to the
 * "Saved" view on recomposition.
 */
@Composable
private fun SaveRideCard(
    ride: RideRecord,
    saveMode: SaveMode,
    signedIn: Boolean,
    onUploadRide: suspend (RideRecord) -> Boolean,
    onStartAuth: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var uploading by remember(ride.id) { mutableStateOf(false) }
    var uploadFailed by remember(ride.id) { mutableStateOf(false) }
    // Rider tapped "Don't save this one" — silence the auto-upload
    // path. Scoped to this ride's id so a future ride gets a fresh card.
    var discarded by remember(ride.id) { mutableStateOf(false) }

    val alreadyUploaded = ride.cloudDocId != null
    val shouldAutoUpload =
        signedIn && saveMode == SaveMode.Always && !alreadyUploaded && !discarded && !uploadFailed

    // Auto-upload on first composition when Always mode + signed in.
    // We key the effect on ride.id so a new ride entering the Summary
    // re-fires; we also re-fire after a Retry tap by setting
    // uploadFailed=false above.
    LaunchedEffect(ride.id, shouldAutoUpload) {
        if (shouldAutoUpload && !uploading) {
            uploading = true
            val ok = onUploadRide(ride)
            uploading = false
            uploadFailed = !ok
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(14.dp, RoundedCornerShape(18.dp))
            .background(RideTokens.Surface, RoundedCornerShape(18.dp))
            .border(
                1.dp,
                when {
                    alreadyUploaded -> RideTokens.Success.copy(alpha = 0.5f)
                    uploadFailed -> RideTokens.Error.copy(alpha = 0.5f)
                    else -> RideTokens.Border
                },
                RoundedCornerShape(18.dp),
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        when {
            alreadyUploaded -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✓", color = RideTokens.Success, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            "Saved to your cloud",
                            color = RideTokens.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "View later at my.scoo-va.info",
                            color = RideTokens.Muted, fontSize = 12.sp,
                        )
                    }
                }
            }

            !signedIn -> {
                Text(
                    "Back up this ride",
                    color = RideTokens.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Sign in to save your ride history to your Scoova cloud.",
                    color = RideTokens.Muted, fontSize = 12.sp,
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(RideTokens.PrimaryButton, RoundedCornerShape(10.dp))
                        .clickable { onStartAuth() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Sign in to save",
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }

            uploading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = RideTokens.Accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Saving to your cloud…",
                        color = RideTokens.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            uploadFailed -> {
                Text(
                    "Couldn't reach the cloud",
                    color = RideTokens.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Your ride is safe on this phone. Tap retry to try again.",
                    color = RideTokens.Muted, fontSize = 12.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .background(RideTokens.PrimaryButton, RoundedCornerShape(10.dp))
                            .clickable {
                                uploadFailed = false
                                uploading = true
                                scope.launch {
                                    val ok = onUploadRide(ride)
                                    uploading = false
                                    uploadFailed = !ok
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Retry",
                            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .background(RideTokens.Surface3, RoundedCornerShape(10.dp))
                            .clickable { discarded = true; uploadFailed = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Don't save",
                            color = RideTokens.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            saveMode == SaveMode.AskEachTime -> {
                Text(
                    "Save this ride?",
                    color = RideTokens.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Upload to your Scoova cloud so it shows up across your devices.",
                    color = RideTokens.Muted, fontSize = 12.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .background(RideTokens.PrimaryButton, RoundedCornerShape(10.dp))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                uploading = true
                                scope.launch {
                                    val ok = onUploadRide(ride)
                                    uploading = false
                                    uploadFailed = !ok
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Save",
                            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .background(RideTokens.Surface3, RoundedCornerShape(10.dp))
                            .clickable { discarded = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Discard",
                            color = RideTokens.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            discarded -> {
                Text(
                    "Not saving this ride",
                    color = RideTokens.Muted, fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun SimReportCard(report: com.scoova.navlayer.core.SimulationReport) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(14.dp, RoundedCornerShape(18.dp))
            .background(RideTokens.Surface, RoundedCornerShape(18.dp))
            .border(1.dp, RideTokens.Cyan.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(
            "Simulation report",
            color = RideTokens.Cyan2, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp,
        )
        Spacer(Modifier.height(8.dp))
        // Top-line counts.
        Row {
            SimReportCell(
                label = "Maneuvers",
                value = "${report.maneuverCount}",
                tint = RideTokens.Text,
                modifier = Modifier.weight(1f),
            )
            SimReportCell(
                label = "Banners",
                value = "${report.bannerUpdateCount}",
                tint = RideTokens.Lime,
                modifier = Modifier.weight(1f),
            )
            SimReportCell(
                label = "Voice cues",
                value = "${report.voiceCueCount}",
                tint = RideTokens.Sun,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "%.1f km in %d s — cyclist pace simulation".format(
                report.routeDistanceMeters / 1000.0,
                (report.durationMs / 1000).toInt(),
            ),
            color = RideTokens.Muted, fontSize = 11.sp,
        )
        // Anomaly list (or all-clear).
        Spacer(Modifier.height(10.dp))
        if (report.anomalies.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✓", color = Color(0xFF22C55E), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Every cue fired in its expected threshold band.",
                    color = Color(0xFF22C55E), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        } else {
            Text(
                "${report.anomalies.size} timing anomaly${if (report.anomalies.size == 1) "" else "s"}",
                color = Color(0xFFFF6A00), fontSize = 12.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            report.anomalies.take(5).forEach { anom ->
                Text(
                    "• $anom",
                    color = RideTokens.Muted, fontSize = 11.sp, lineHeight = 15.sp,
                )
            }
            if (report.anomalies.size > 5) {
                Text(
                    "+ ${report.anomalies.size - 5} more",
                    color = RideTokens.Muted, fontSize = 11.sp,
                )
            }
        }
        // Per-maneuver firings — collapsed list, scroll if long.
        if (report.maneuvers.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(RideTokens.Border))
            Spacer(Modifier.height(10.dp))
            Text(
                "Per-maneuver cue firings",
                color = RideTokens.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(6.dp))
            for (m in report.maneuvers.take(6)) {
                val firingsLabel = if (m.cuesFired.isEmpty()) "no firings"
                else m.cuesFired.joinToString("  ") { "${it.phase}@${it.metersToManeuver.toInt()}m" }
                Text(
                    "#${m.index} ${m.verb ?: m.type}: $firingsLabel",
                    color = RideTokens.Text, fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun SimReportCell(
    label: String,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(value, color = tint, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, color = RideTokens.Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TripNotesRow(initial: String, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var saved by remember { mutableStateOf(initial.isNotBlank()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(16.dp))
            .background(RideTokens.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, RideTokens.Border, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            "Notes",
            color = RideTokens.Cyan2,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = text,
            onValueChange = {
                if (it.length <= RideRecord.MAX_NOTE_CHARS) {
                    text = it
                    saved = false
                }
            },
            textStyle = TextStyle(color = RideTokens.Text, fontSize = 14.sp),
            cursorBrush = SolidColor(RideTokens.Cyan),
            singleLine = false,
            decorationBox = { inner ->
                if (text.isBlank()) {
                    Text(
                        "Anything you want to remember?",
                        color = RideTokens.Muted,
                        fontSize = 14.sp,
                    )
                }
                inner()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${text.length}/${RideRecord.MAX_NOTE_CHARS}",
                color = RideTokens.Muted, fontSize = 10.sp,
            )
            Spacer(Modifier.weight(1f))
            if (saved && text.isNotBlank()) {
                Text(
                    "Saved",
                    color = Color(0xFF22C55E), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                )
            } else if (text.isNotBlank() || initial.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .background(RideTokens.Cyan, RoundedCornerShape(8.dp))
                        .clickable {
                            onSave(text)
                            saved = true
                        }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Save note",
                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun TripRatingRow() {
    var selected by remember { mutableStateOf<Int?>(null) }
    val ratings = listOf("😞" to "Rough", "😐" to "OK", "😍" to "Great")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "How was the ride?",
            color = RideTokens.Muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ratings.forEachIndexed { idx, (emoji, _) ->
                val isPicked = selected == idx
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            if (isPicked) RideTokens.Cyan.copy(alpha = 0.22f) else RideTokens.Surface,
                            CircleShape,
                        )
                        .border(
                            if (isPicked) 2.dp else 1.dp,
                            if (isPicked) RideTokens.Cyan else RideTokens.Border,
                            CircleShape,
                        )
                        .clickable { selected = idx },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(emoji, fontSize = 26.sp)
                }
            }
        }
        if (selected != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Thanks — got it.",
                color = RideTokens.Cyan2,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
        }
    }
}

// ─── Shared atoms ───────────────────────────────────────────────────────

@Composable
private fun StatBlock(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, color = RideTokens.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Text(value, color = valueColor, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun VerticalDivider() {
    Box(modifier = Modifier.width(1.dp).height(28.dp).background(RideTokens.Border).padding(horizontal = 8.dp))
}

@Composable
private fun SummaryHealthCell(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(label, color = RideTokens.Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BigStat(value: String, unit: String, color: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, color = color, fontSize = 64.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(unit, color = RideTokens.Muted, fontSize = 16.sp, modifier = Modifier.padding(bottom = 14.dp))
    }
}

@Composable
private fun PrimaryCta(text: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .height(48.dp)
            .background(RideTokens.PrimaryButton, RoundedCornerShape(14.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
private fun SecondaryCta(text: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .height(48.dp)
            .background(RideTokens.Surface2, RoundedCornerShape(14.dp))
            .border(1.dp, RideTokens.Border, RoundedCornerShape(14.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = RideTokens.Muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = RideTokens.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

/**
 * Returns true when running on the Android emulator. Used by the
 * RideScreen to disable the 45° follow-camera tilt, which crashes
 * MapLibre's RenderThread on emulators (SwiftShader GLES encoder).
 *
 * Real device fingerprints never contain `generic` / `sdk` / the
 * Goldfish hardware string, so this is a safe heuristic. Build.MODEL
 * on real Pixels reads "Pixel 7" etc.; on the emulator it reads
 * "sdk_gphone64_arm64" / "Android SDK built for x86" / similar.
 */
private fun isProbablyEmulator(): Boolean {
    val fp = android.os.Build.FINGERPRINT ?: ""
    val model = android.os.Build.MODEL ?: ""
    val product = android.os.Build.PRODUCT ?: ""
    val hardware = android.os.Build.HARDWARE ?: ""
    return fp.startsWith("generic") ||
        fp.startsWith("unknown") ||
        fp.contains("emulator") ||
        model.contains("google_sdk") ||
        model.contains("Emulator") ||
        model.contains("Android SDK built for") ||
        model.startsWith("sdk_") ||
        product.contains("sdk_gphone") ||
        hardware.contains("goldfish") ||
        hardware.contains("ranchu")
}
