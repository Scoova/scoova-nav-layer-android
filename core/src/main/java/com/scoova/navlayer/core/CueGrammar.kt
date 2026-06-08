package com.scoova.navlayer.core

import kotlin.math.roundToInt

/**
 * Spoken cue text plus the rule that produced it. The rule label is
 * telemetry — every cue we say carries a trace of WHY we said it.
 * When "wrong cue" bug reports come in, the rule label tells us whether
 * the grammar chose right and the data was wrong, or the grammar chose
 * wrong.
 */
public data class SpokenCueText(
    /** The text the voice engine speaks. */
    val text: String,
    /** Which grammar rule fired. One of: `landmark`, `ordinal`,
     *  `next-confirmed`, `distance`, `fallback`. */
    val rule: String,
)

/**
 * Port of iOS `CueGrammar.swift`. Cue-grammar engine — turns a
 * [LiveGuidanceState.UpcomingDecision] + a cue phase into a spoken
 * phrase. The whole point of having the corridor + the reasoner is to
 * STOP playing pre-baked strings and start choosing grammar at speak
 * time.
 *
 * Phase matters. The same maneuver fires three approach cues — FAR
 * (~30 s out), MID (~15 s out), NEAR (~3 s out). Far and mid never
 * make ordinal claims; they're heads-up cues, not commitments. "The
 * next left" lives only in near AND only when the corridor proves
 * there is exactly one same-side turn ahead.
 */
public object CueGrammar {

    /** Which approach-cue phase is firing. Caller picks the phase from
     *  the [CuePoint] being played (urgent tone ⇒ near, long trigger
     *  ⇒ far, otherwise mid). */
    public enum class Phase { Far, Mid, Near }

    public fun chooseCue(
        decision: LiveGuidanceState.UpcomingDecision,
        phase: Phase = Phase.Near,
        locale: String,
        landmark: String?,
        fallback: String?,
    ): SpokenCueText {
        val side = sideWord(decision.type, locale)
        val meters = decision.distance.roundToInt()
        return when (phase) {
            Phase.Far -> {
                // 30 s out. Heads-up. Never commits to an ordinal.
                if (side != null) SpokenCueText(
                    text = render(Pattern.Distance, side, locale, meters.toString()),
                    rule = "far-distance",
                ) else SpokenCueText(text = fallback.orEmpty(), rule = "fallback")
            }
            Phase.Mid -> {
                if (!landmark.isNullOrEmpty() && side != null) {
                    SpokenCueText(
                        text = render(Pattern.AfterLandmark, side, locale, landmark),
                        rule = "mid-landmark",
                    )
                } else if (side != null) {
                    SpokenCueText(
                        text = render(Pattern.Distance, side, locale, meters.toString()),
                        rule = "mid-distance",
                    )
                } else {
                    SpokenCueText(text = fallback.orEmpty(), rule = "fallback")
                }
            }
            Phase.Near -> {
                // 3 s out. Strongest form we know is correct.
                // Order: landmark → ordinal → next-confirmed → action-now.
                if (!landmark.isNullOrEmpty() && side != null) {
                    return SpokenCueText(
                        text = render(Pattern.AtLandmark, side, locale, landmark),
                        rule = "landmark",
                    )
                }
                val total = decision.totalSameSideTurns
                val ord = decision.ordinal
                if (total != null && ord != null && total > 1 && ord in 1..5 && side != null) {
                    val word = ordinalWord(ord, locale)
                    return SpokenCueText(
                        text = render(Pattern.Ordinal, side, locale, word),
                        rule = "ordinal",
                    )
                }
                if (decision.totalSameSideTurns == 1 && side != null) {
                    return SpokenCueText(
                        text = render(Pattern.Next, side, locale, ""),
                        rule = "next-confirmed",
                    )
                }
                if (side != null) {
                    return SpokenCueText(
                        text = render(Pattern.Now, side, locale, ""),
                        rule = "near-action",
                    )
                }
                SpokenCueText(text = fallback.orEmpty(), rule = "fallback")
            }
        }
    }

    // MARK: - Phrasebook -------------------------------------------------

    private enum class Pattern { AtLandmark, AfterLandmark, Ordinal, Next, Distance, Now }

    /** One side-word per maneuver type, per locale. */
    private fun sideWord(type: ManeuverType, locale: String): String? {
        val isArabic = locale.lowercase().startsWith("ar")
        return when (type) {
            ManeuverType.Left, ManeuverType.SlightLeft -> if (isArabic) "شمال" else "left"
            ManeuverType.SharpLeft                      -> if (isArabic) "شمال حاد" else "sharp left"
            ManeuverType.Right, ManeuverType.SlightRight -> if (isArabic) "يمين" else "right"
            ManeuverType.SharpRight                     -> if (isArabic) "يمين حاد" else "sharp right"
            ManeuverType.Uturn                          -> if (isArabic) "عكس الاتجاه" else "U-turn"
            else -> null
        }
    }

    private fun ordinalWord(n: Int, locale: String): String {
        val isArabic = locale.lowercase().startsWith("ar")
        if (isArabic) {
            return when (n) {
                1 -> "الأول"
                2 -> "التاني"
                3 -> "التالت"
                4 -> "الرابع"
                5 -> "الخامس"
                else -> n.toString()
            }
        }
        return when (n) {
            1 -> "next"          // "the next left"
            2 -> "second"
            3 -> "third"
            4 -> "fourth"
            5 -> "fifth"
            else -> "${n}th"
        }
    }

    private fun render(pattern: Pattern, side: String, locale: String, value: String): String {
        val isArabic = locale.lowercase().startsWith("ar")
        return when (pattern) {
            Pattern.AtLandmark    -> if (isArabic) "حوّد $side عند $value." else "Turn $side at $value."
            Pattern.AfterLandmark -> if (isArabic) "بعد $value، حوّد $side." else "After $value, turn $side."
            Pattern.Ordinal       -> if (isArabic) "خد ال$side $value." else "Take the $value $side."
            Pattern.Next          -> if (isArabic) "خد ال$side الجاي." else "Take the next $side."
            Pattern.Distance      -> if (isArabic) "بعد $value متر حوّد $side." else "Turn $side in $value metres."
            Pattern.Now           -> if (isArabic) "حوّد $side دلوقتي." else "Turn $side now."
        }
    }
}
