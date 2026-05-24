package com.scoova.navlayer.core

/**
 * Phase-based cue phrasing for hands-busy navigation.
 *
 * Three phases per maneuver:
 *   • Far  — "Get ready to turn left ahead" / "استعد، حوّد شمال قريب"
 *   • Mid  — "Turn left at the next street" / "في الشارع اللي جاي حوّد شمال"
 *   • Near — "Turn left now" / "حوّد شمال دلوقتي"
 *
 * Plus maneuver-shape-aware phrasing — exits get "take the exit", highways
 * get "stay in the next lane", roundabouts pass through unmodified because
 * the host SDK already says "take the Nth exit".
 *
 * 7 locales: ar-EG (Egyptian colloquial), ar (MSA), en, fr, de, es, tr.
 */
public object CuePhrases {

    public enum class Phase { Far, Mid, Near }

    public fun pickPhase(thresholdsMeters: IntArray, firedThresholdM: Int): Phase {
        if (thresholdsMeters.isEmpty() || firedThresholdM <= 0) return Phase.Mid
        val sorted = thresholdsMeters.sortedArray()
        return when {
            firedThresholdM <= sorted[0] -> Phase.Near
            firedThresholdM >= sorted.last() -> Phase.Far
            else -> Phase.Mid
        }
    }

    public fun build(
        lang: String,
        maneuver: ManeuverEvent,
        firedThresholdM: Int,
        thresholdsMeters: IntArray,
        landmark: String? = null,
    ): String {
        val phase = pickPhase(thresholdsMeters, firedThresholdM)
        val type = maneuver.type
        // Roundabouts: the host SDK already encodes "take the Nth exit".
        // Don't second-guess it — pass the host's text through.
        if (type.isRoundabout) {
            return maneuver.rawInstruction ?: localized(lang, phase, Side.Other, Setting.Generic, landmark)
        }
        if (type == ManeuverType.Depart || type == ManeuverType.Arrive) {
            return maneuver.rawInstruction ?: localized(lang, phase, Side.Other, Setting.Generic, landmark)
        }

        val side = when {
            type.isUturn -> Side.Uturn
            type == ManeuverType.Continue || type == ManeuverType.StayStraight -> Side.Straight
            type.isLeftSide -> Side.Left
            type.isRightSide -> Side.Right
            else -> Side.Other
        }
        val setting = when {
            type.isExit -> Setting.Exit
            type in setOf(ManeuverType.StayLeft, ManeuverType.StayRight, ManeuverType.Merge) -> Setting.Highway
            type == ManeuverType.Arrive -> Setting.Destination
            type in setOf(
                ManeuverType.Left, ManeuverType.Right, ManeuverType.SharpLeft, ManeuverType.SharpRight,
                ManeuverType.SlightLeft, ManeuverType.SlightRight,
            ) -> Setting.Street
            else -> Setting.Generic
        }
        return localized(lang, phase, side, setting, landmark)
    }

    private enum class Side { Left, Right, Straight, Uturn, Other }
    private enum class Setting { Street, Exit, Roundabout, Highway, Destination, Generic }

    private fun localized(lang: String, phase: Phase, side: Side, setting: Setting, landmark: String?): String {
        val raw = when {
            lang.startsWith("ar-EG") -> egyptian(phase, side, setting)
            lang.startsWith("ar")    -> msa(phase, side, setting)
            lang.startsWith("fr")    -> french(phase, side, setting)
            lang.startsWith("de")    -> german(phase, side, setting)
            lang.startsWith("es")    -> spanish(phase, side, setting)
            lang.startsWith("tr")    -> turkish(phase, side, setting)
            else                     -> english(phase, side, setting)
        }.trim()
        return if (landmark != null) appendLandmark(lang, raw, landmark) else raw
    }

    // NOTE: distanceLeadIn and chainedTurnSuffix used to live here as
    // client-side composers when the server only sent verb+anchor.
    // With the v2 schema the server renders the entire utterance
    // (including distance lead-in and chained-turn suffix) in the
    // rider's locale, so the composers were removed. Re-introducing
    // them would re-create the Frankenstein-Arabic risk we explicitly
    // avoided when picking the "server owns full sentence" boundary.

    private fun appendLandmark(lang: String, base: String, name: String): String {
        val (inline, _) = when {
            lang.startsWith("ar") -> "، عند {n}" to "."
            lang.startsWith("fr") -> " à {n}" to "."
            lang.startsWith("de") -> " bei {n}" to "."
            lang.startsWith("es") -> " en {n}" to "."
            lang.startsWith("tr") -> " {n} yanında" to "."
            else                  -> " at {n}" to "."
        }
        val tail = inline.replace("{n}", name)
        return if (base.endsWith(".")) base.dropLast(1) + tail + "." else base + tail
    }

    private fun egyptian(phase: Phase, side: Side, setting: Setting): String {
        val verb = when (side) {
            Side.Left -> "حوّد شمال"
            Side.Right -> "حوّد يمين"
            Side.Uturn -> "اعمل يو تيرن"
            Side.Straight -> "كمل على طول"
            Side.Other -> "كمل"
        }
        val landmark = when (setting) {
            Setting.Exit -> "خد المخرج اللي جاي"
            Setting.Highway -> "فضل في حارة جنب الجاي"
            else -> when (side) {
                Side.Left, Side.Right -> "في الشارع اللي جاي"
                else -> "قدامك"
            }
        }
        return when (phase) {
            Phase.Far -> when (setting) {
                Setting.Exit -> "استعد، المخرج قريب"
                else -> "استعد، $verb قريب"
            }
            Phase.Mid -> when (setting) {
                Setting.Exit -> "خد المخرج اللي جاي"
                Setting.Highway -> landmark
                else -> "$landmark $verb"
            }
            Phase.Near -> when (setting) {
                Setting.Exit -> "خد المخرج دلوقتي"
                else -> "$verb دلوقتي"
            }
        }
    }

    private fun msa(phase: Phase, side: Side, setting: Setting): String {
        val verb = when (side) {
            Side.Left -> "انعطف يساراً"
            Side.Right -> "انعطف يميناً"
            Side.Uturn -> "استدر"
            Side.Straight -> "تابع مستقيماً"
            Side.Other -> "تابع"
        }
        return when (phase) {
            Phase.Far -> when (setting) {
                Setting.Exit -> "استعد، المخرج قريب"
                else -> "استعد، $verb قريباً"
            }
            Phase.Mid -> when (setting) {
                Setting.Exit -> "خذ المخرج التالي"
                Setting.Highway -> "ابقَ في المسار التالي"
                else -> "$verb عند الشارع التالي"
            }
            Phase.Near -> when (setting) {
                Setting.Exit -> "خذ المخرج الآن"
                else -> "$verb الآن"
            }
        }
    }

    private fun english(phase: Phase, side: Side, setting: Setting): String {
        val verb = when (side) {
            Side.Left -> "turn left"
            Side.Right -> "turn right"
            Side.Uturn -> "make a U-turn"
            Side.Straight -> "keep going straight"
            Side.Other -> "keep going"
        }
        return when (phase) {
            Phase.Far -> when (setting) {
                Setting.Exit -> "Get ready, exit ahead"
                else -> "Get ready to ${verb} ahead"
            }
            Phase.Mid -> when (setting) {
                Setting.Exit -> "Take the next exit"
                Setting.Highway -> "Stay in the next lane"
                else -> "${verb.replaceFirstChar { it.titlecase() }} at the next street"
            }
            Phase.Near -> when (setting) {
                Setting.Exit -> "Take the exit now"
                else -> "${verb.replaceFirstChar { it.titlecase() }} now"
            }
        }
    }

    private fun french(phase: Phase, side: Side, setting: Setting): String {
        val verb = when (side) {
            Side.Left -> "tournez à gauche"
            Side.Right -> "tournez à droite"
            Side.Uturn -> "faites demi-tour"
            Side.Straight -> "continuez tout droit"
            Side.Other -> "continuez"
        }
        return (when (phase) {
            Phase.Far -> when (setting) {
                Setting.Exit -> "Préparez-vous, sortie proche"
                else -> "Préparez-vous, $verb"
            }
            Phase.Mid -> when (setting) {
                Setting.Exit -> "Prenez la prochaine sortie"
                Setting.Highway -> "Restez sur la prochaine voie"
                else -> "À la prochaine rue, $verb"
            }
            Phase.Near -> when (setting) {
                Setting.Exit -> "Prenez la sortie maintenant"
                else -> "$verb maintenant"
            }
        }).replaceFirstChar { it.titlecase() }
    }

    private fun german(phase: Phase, side: Side, setting: Setting): String {
        val verb = when (side) {
            Side.Left -> "links abbiegen"
            Side.Right -> "rechts abbiegen"
            Side.Uturn -> "wenden"
            Side.Straight -> "geradeaus weiter"
            Side.Other -> "weiter"
        }
        return (when (phase) {
            Phase.Far -> when (setting) {
                Setting.Exit -> "Bereit machen, Ausfahrt voraus"
                else -> "Bereit machen, gleich $verb"
            }
            Phase.Mid -> when (setting) {
                Setting.Exit -> "Nehmen Sie die nächste Ausfahrt"
                Setting.Highway -> "Bleiben Sie auf der nächsten Spur"
                else -> "An der nächsten Straße $verb"
            }
            Phase.Near -> when (setting) {
                Setting.Exit -> "Jetzt die Ausfahrt nehmen"
                else -> "Jetzt $verb"
            }
        }).replaceFirstChar { it.titlecase() }
    }

    private fun spanish(phase: Phase, side: Side, setting: Setting): String {
        val verb = when (side) {
            Side.Left -> "gira a la izquierda"
            Side.Right -> "gira a la derecha"
            Side.Uturn -> "haz un cambio de sentido"
            Side.Straight -> "sigue recto"
            Side.Other -> "sigue"
        }
        return (when (phase) {
            Phase.Far -> when (setting) {
                Setting.Exit -> "Prepárate, salida cerca"
                else -> "Prepárate, vas a $verb"
            }
            Phase.Mid -> when (setting) {
                Setting.Exit -> "Toma la siguiente salida"
                Setting.Highway -> "Mantente en el siguiente carril"
                else -> "En la próxima calle, $verb"
            }
            Phase.Near -> when (setting) {
                Setting.Exit -> "Toma la salida ahora"
                else -> "$verb ahora"
            }
        }).replaceFirstChar { it.titlecase() }
    }

    private fun turkish(phase: Phase, side: Side, setting: Setting): String {
        val verb = when (side) {
            Side.Left -> "sola dön"
            Side.Right -> "sağa dön"
            Side.Uturn -> "U dönüşü yap"
            Side.Straight -> "düz devam et"
            Side.Other -> "devam et"
        }
        return (when (phase) {
            Phase.Far -> when (setting) {
                Setting.Exit -> "Hazırlan, çıkış yakın"
                else -> "Hazırlan, birazdan $verb"
            }
            Phase.Mid -> when (setting) {
                Setting.Exit -> "Bir sonraki çıkıştan çık"
                Setting.Highway -> "Bir sonraki şeritte kal"
                else -> "Bir sonraki sokakta $verb"
            }
            Phase.Near -> when (setting) {
                Setting.Exit -> "Şimdi çıkıştan çık"
                else -> "Şimdi $verb"
            }
        }).replaceFirstChar { it.titlecase() }
    }
}
