package com.scoova.navlayer.core

import kotlin.math.roundToInt

/**
 * The subtitle cue model — a 1:1 port of the iOS `ScoovaNavLayer`
 * cue engine, so both SDKs schedule and fire voice cues identically.
 *
 * Each maneuver carries a list of [CuePoint]s — like movie subtitles
 * pinned to points on the route. A cue fires once, the moment the
 * rider crosses its trigger. Approach cues (far / mid / near) fire by
 * TIME-to-maneuver; confirm / reaffirm / checkpoint fire at a fixed
 * distance. There is no client threshold ladder — the schedule IS the
 * plan.
 */
internal enum class CueKind { Approach, Confirm, Reaffirm, Checkpoint }

/**
 * One spoken cue, pinned to a point on the route. `triggerSeconds`,
 * when set, makes the cue fire that many seconds out (against the
 * rider's live speed) so the lead time is the same at any speed;
 * null means a plain distance trigger.
 */
internal data class CuePoint(
    val triggerMeters: Double,
    val phrase: String,
    val tone: CueTone,
    val pan: Float,
    val triggerSeconds: Double? = null,
    val kind: CueKind = CueKind.Approach,
)

/** Fallback far / mid / near lead distances when a maneuver ships none. */
internal data class CueDefaults(val far: Double, val mid: Double, val near: Double)

internal object CueSchedule {

    /**
     * Seconds-before-the-maneuver each approach cue aims for. The cue's
     * trigger distance is this × live speed — a faster rider hears
     * "turn" proportionally earlier and always gets the same lead time.
     * `near` is the "do it now" cue — it must land right at the turn.
     */
    val LEAD_FAR = 30.0
    val LEAD_MID = 15.0
    val LEAD_NEAR = 3.0

    private const val SPEED_CEIL_MPS = 28.0
    private const val MIN_CUE_GAP_M = 16.0
    private const val REAFFIRM_SPACING_M = 450.0

    /**
     * Build the per-maneuver cue track. Mirrors iOS `buildCueSchedule`
     * exactly: confirm just after the previous turn, reaffirm spread
     * along a long quiet stretch, mid-segment checkpoint, and the
     * far / mid / near approach cues. Depart and arrive are skipped —
     * welcome and arrival cover those.
     */
    fun build(
        maneuvers: List<ManeuverEvent>,
        defaults: CueDefaults,
        keepGoing: String?,
        pan: (ManeuverType) -> Float,
    ): Map<Int, List<CuePoint>> {
        if (maneuvers.size <= 2) return emptyMap()
        val schedule = HashMap<Int, List<CuePoint>>()
        for (i in 1 until maneuvers.size - 1) {
            val m = maneuvers[i]
            val p = pan(m.type)
            val far = m.farMeters?.toDouble() ?: defaults.far
            val mid = m.midMeters?.toDouble() ?: defaults.mid
            val near = m.nearMeters?.toDouble() ?: defaults.near
            // Distance the rider covers on the way to this maneuver.
            val segLen = maneuvers[i - 1].segmentLengthMeters
            val points = ArrayList<CuePoint>()

            // Reassurance just after the previous turn landed — gated to
            // segments with real room (> 160 m); inside an interchange
            // there is no quiet beat for a confirm.
            val confirm = maneuvers[i - 1].voiceConfirm
            if (i >= 2 && segLen > 160 && !confirm.isNullOrEmpty()) {
                // Fire 10 m past the turn — far enough for GPS to settle
                // onto the new segment, close enough that the rider hears
                // "good, you're on X" while still finishing the turn, not
                // a block later.
                points.add(
                    CuePoint(
                        triggerMeters = maxOf(near + 1, segLen - 10),
                        phrase = confirm, tone = CueTone.Calm, pan = 0f,
                        kind = CueKind.Confirm,
                    )
                )
            }
            // Reaffirmation on the quiet stretch — spread by TIME, not
            // distance, so the cadence stays right across personas. A
            // fixed 450 m means a pedestrian (1.5 m/s) hears reaffirm
            // every 5 minutes — way too long, the rider thinks the app
            // has died. The same 450 m on a scooter (5 m/s) is every
            // 90 s, fine. Scaling by the segment's actual expected
            // speed cures both ends. Falls back to the legacy 450 m
            // when the adapter didn't send a duration (third-party
            // engines that don't model time).
            val quietZone = segLen - far
            if (quietZone > 120) {
                val targetReaffirmSec = 75.0
                val segmentSpeedMps: Double = m.segmentDurationSeconds
                    ?.takeIf { it > 0 }
                    ?.let { segLen / it } ?: 0.0
                val reaffirmSpacingM: Double = if (segmentSpeedMps > 0)
                    maxOf(150.0, targetReaffirmSec * segmentSpeedMps)
                else REAFFIRM_SPACING_M
                val reaffirm = m.voiceReaffirm
                if (!reaffirm.isNullOrEmpty()) {
                    val n = maxOf(1, minOf(24, (quietZone / reaffirmSpacingM).toInt()))
                    for (k in 1..n) {
                        points.add(
                            CuePoint(
                                triggerMeters = far + quietZone * k / (n + 1),
                                phrase = reaffirm, tone = CueTone.Calm, pan = 0f,
                                kind = CueKind.Reaffirm,
                            )
                        )
                    }
                } else if (!keepGoing.isNullOrEmpty()) {
                    // Same speed-aware cadence for the keep-going
                    // fallback — bumped up from the prior 1-3 hard cap
                    // so longer segments don't sit silent.
                    val n = maxOf(1, minOf(8, (quietZone / reaffirmSpacingM).toInt()))
                    for (k in 1..n) {
                        points.add(
                            CuePoint(
                                triggerMeters = far + quietZone * k / (n + 1),
                                phrase = keepGoing, tone = CueTone.Calm, pan = 0f,
                                kind = CueKind.Reaffirm,
                            )
                        )
                    }
                }
            }
            // Mid-segment checkpoint — "You're passing the museum on
            // your right." Server pins it to an offset from the PRIOR
            // maneuver; convert to distance-before-this-maneuver.
            val checkpoint = m.voiceCheckpoint
            val cpOffset = m.checkpointOffsetMeters
            if (!checkpoint.isNullOrEmpty() && cpOffset != null) {
                val trigger = segLen - cpOffset.toDouble()
                if (trigger > near) {
                    points.add(
                        CuePoint(
                            triggerMeters = trigger,
                            phrase = checkpoint, tone = CueTone.Calm, pan = 0f,
                            kind = CueKind.Checkpoint,
                        )
                    )
                }
            }
            // Approach cues — far / mid / near. Every turn gets all
            // three; no build-time gating. They fire by TIME, so the
            // runtime adapts: on a long approach they land spaced out,
            // on a short interchange segment they cross together and
            // the runtime speaks only the nearest.
            (m.voiceFar ?: m.voiceHeadsUp)?.let { phrase ->
                points.add(
                    CuePoint(far, phrase, CueTone.Normal, p, triggerSeconds = LEAD_FAR)
                )
            }
            m.voiceMid?.let { phrase ->
                points.add(
                    CuePoint(mid, phrase, CueTone.Normal, p, triggerSeconds = LEAD_MID)
                )
            }
            // Near cue — one turn per cue, this maneuver's turn only.
            // The chained cue is intentionally NOT used (bundling two
            // turns into one breath reads as a contradictory command).
            (m.voiceNear ?: m.voiceAtLandmark ?: m.voiceTurnNow)?.let { phrase ->
                points.add(
                    CuePoint(near, phrase, CueTone.Urgent, p, triggerSeconds = LEAD_NEAR)
                )
            }
            if (points.isNotEmpty()) {
                schedule[i] = spaceCues(points.sortedByDescending { it.triggerMeters })
            }
        }
        return schedule
    }

    /**
     * `points` come in far → near. Keep the near cue, then keep each
     * earlier cue only if it leads the last kept one by a speakable gap,
     * so two cues never tread on each other.
     */
    fun spaceCues(points: List<CuePoint>): List<CuePoint> {
        val kept = ArrayList<CuePoint>()
        for (cue in points.asReversed()) {           // near → far
            val last = kept.lastOrNull()
            if (last != null && cue.triggerMeters - last.triggerMeters < MIN_CUE_GAP_M) {
                continue
            }
            kept.add(cue)
        }
        return kept.asReversed()
    }

    /**
     * The distance-before-the-maneuver at which a cue fires right now.
     * Distance-pinned cues use their fixed `triggerMeters`. Time-pinned
     * cues convert their seconds-out to metres against the live speed —
     * so "turn" fires three times farther out at 60 km/h than at
     * 20 km/h and the rider always gets the same seconds to react.
     */
    fun effectiveTriggerMeters(cue: CuePoint, speedMps: Float?): Double {
        val seconds = cue.triggerSeconds ?: return cue.triggerMeters
        val speed = (speedMps?.toDouble() ?: 8.0).coerceIn(0.0, SPEED_CEIL_MPS)
        return seconds * speed
    }
}

/**
 * A spoken distance — "19 kilometers" past 1 km, "350 meters" below it
 * (rounded to the nearest 50). Mirrors the iOS helper.
 */
internal fun spokenDistance(lang: String, meters: Int): String {
    val base = lang.substringBefore('-').lowercase()
    if (meters >= 1000) {
        val km = meters / 1000.0
        val n = if (km >= 10) "%.0f".format(km) else "%.1f".format(km)
        return when (base) {
            "ar" -> "$n كيلومتر"
            "fr" -> "$n kilomètres"
            "de" -> "$n Kilometer"
            "es" -> "$n kilómetros"
            "it" -> "$n chilometri"
            "pt" -> "$n quilômetros"
            "nl" -> "$n kilometer"
            else -> "$n kilometers"
        }
    }
    val mtr = maxOf(50, (meters / 50.0).roundToInt() * 50)
    return when (base) {
        "ar" -> "$mtr متر"
        "fr" -> "$mtr mètres"
        "de" -> "$mtr Meter"
        "es" -> "$mtr metros"
        "it" -> "$mtr metri"
        "pt" -> "$mtr metros"
        "nl" -> "$mtr meter"
        else -> "$mtr meters"
    }
}

/**
 * Appends a live distance clause to a reaffirm / silence-filler cue.
 *
 * The clause names the distance to the **next turn**, not the
 * destination — so the voice number matches what the banner shows
 * the rider. A mismatched "812 m" banner + "1.4 km" voice reads as
 * a bug: the rider can't square the two and loses trust. Skipped
 * when the rider is near the maneuver (the far / mid / near approach
 * cues own that last stretch).
 */
internal fun appendDistanceToNextTurn(
    phrase: String, lang: String, metersToTurn: Int,
): String {
    if (metersToTurn <= 300) return phrase
    val dist = spokenDistance(lang, metersToTurn)
    val clause = when (lang.substringBefore('-').lowercase()) {
        "ar" -> "باقي $dist للتحويلة الجاية."
        "fr" -> "$dist avant le prochain virage."
        "de" -> "Noch $dist bis zur nächsten Abbiegung."
        "es" -> "$dist hasta el próximo giro."
        "it" -> "$dist alla prossima svolta."
        "pt" -> "$dist até a próxima curva."
        "nl" -> "Nog $dist tot de volgende afslag."
        else -> "$dist to the next turn."
    }
    val base = phrase.trim()
    return if (base.isEmpty()) clause else "$base $clause"
}

/**
 * DEPRECATED: kept only because external adapters may import the
 * symbol. New callers use [appendDistanceToNextTurn], which matches
 * the banner's distance number.
 */
@Deprecated(
    "Use appendDistanceToNextTurn — voice should match the banner.",
    ReplaceWith("appendDistanceToNextTurn(phrase, lang, metersRemaining)"),
)
internal fun appendDistanceToDestination(
    phrase: String, lang: String, metersRemaining: Int,
): String = appendDistanceToNextTurn(phrase, lang, metersRemaining)

/**
 * Rewrites any baked-in distance prefix in a server-rendered cue
 * ("In 300 meters after Starbucks, turn right onto 8th Avenue.")
 * to the LIVE distance from the rider to the maneuver. The server's
 * number is the costing's expected lead distance — often wildly
 * different from where the SDK actually fires the cue for a
 * pedestrian or scooter. Banner shows live; voice must too.
 *
 * Localised patterns cover the launch markets (en / fr / es / de /
 * it / pt / nl) plus Arabic and Turkish. Each pattern matches the
 * leading "in N meters/kilometres" clause only — landmark anchors
 * and direction words are preserved verbatim.
 *
 * Mirrors the iOS implementation byte-for-byte.
 */
internal fun rewriteEmbeddedDistance(
    phrase: String, lang: String, liveMeters: Int,
): String {
    if (liveMeters <= 0) return phrase
    val lc = lang.lowercase()
    val live = spokenDistance(lang, liveMeters)
    val patterns: List<Pair<Regex, String>> = when {
        lc.startsWith("ar") -> listOf(
            Regex(
                """^(?:في|بعد)\s+\d+(?:\.\d+)?\s*(?:متر|كيلومتر|كم|م)\b""",
                RegexOption.IGNORE_CASE,
            ) to "بعد $live",
        )
        lc.startsWith("fr") -> listOf(
            Regex(
                """^Dans\s+\d+(?:[.,]\d+)?\s*(?:m[èe]tres?|km|kilom[èe]tres?|m)\b""",
                RegexOption.IGNORE_CASE,
            ) to "Dans $live",
        )
        lc.startsWith("de") -> listOf(
            Regex(
                """^In\s+\d+(?:[.,]\d+)?\s*(?:Metern?|km|Kilometern?|m)\b""",
                RegexOption.IGNORE_CASE,
            ) to "In $live",
        )
        lc.startsWith("es") -> listOf(
            Regex(
                """^En\s+\d+(?:[.,]\d+)?\s*(?:metros?|km|kil[óo]metros?|m)\b""",
                RegexOption.IGNORE_CASE,
            ) to "En $live",
        )
        lc.startsWith("it") -> listOf(
            Regex(
                """^Tra\s+\d+(?:[.,]\d+)?\s*(?:metri|km|chilometri|m)\b""",
                RegexOption.IGNORE_CASE,
            ) to "Tra $live",
        )
        lc.startsWith("pt") -> listOf(
            Regex(
                """^Em\s+\d+(?:[.,]\d+)?\s*(?:metros?|km|quil[óo]metros?|m)\b""",
                RegexOption.IGNORE_CASE,
            ) to "Em $live",
        )
        lc.startsWith("nl") -> listOf(
            Regex(
                """^Over\s+\d+(?:[.,]\d+)?\s*(?:meter|km|kilometer|m)\b""",
                RegexOption.IGNORE_CASE,
            ) to "Over $live",
        )
        lc.startsWith("tr") -> listOf(
            Regex(
                """^\d+(?:[.,]\d+)?\s*(?:metre|km|kilometre|m)\s+sonra""",
                RegexOption.IGNORE_CASE,
            ) to "$live sonra",
        )
        else -> listOf(
            // English default + any locale not specifically handled.
            Regex(
                """^In\s+\d+(?:\.\d+)?\s*(?:meters?|metres?|km|kilometers?|kilometres?|m)\b""",
                RegexOption.IGNORE_CASE,
            ) to "In $live",
        )
    }
    for ((pat, repl) in patterns) {
        val replaced = pat.replaceFirst(phrase, repl)
        if (replaced != phrase) return replaced
    }
    return phrase
}
