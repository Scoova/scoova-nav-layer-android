package com.scoova.navlayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the voice ↔ banner distance mismatch the user
 * caught: voice said "in 300 meters" while banner showed 70 m.
 *
 * The server bakes a static lead distance into FAR / MID cue text;
 * the SDK fires those cues by TIME, which on a pedestrian lands at
 * a very different live distance. The rewrite replaces the embedded
 * number with the live one so voice matches banner.
 */
class LiveDistanceRewriteTest {

    @Test
    fun englishReplacesEmbeddedDistance() {
        val phrase = "In 300 meters after Starbucks, turn right onto 8th Avenue."
        val out = rewriteEmbeddedDistance(phrase, "en-US", 70)
        assertFalse(
            "The stale '300 meters' must not survive the rewrite; got: $out",
            out.contains("300 meters"),
        )
        assertTrue(
            "Landmark anchor must survive; got: $out",
            out.contains("after Starbucks"),
        )
        assertTrue(
            "Direction + destination clause must survive; got: $out",
            out.endsWith("turn right onto 8th Avenue."),
        )
    }

    @Test
    fun englishWithoutDistancePassesThrough() {
        val phrase = "Coming up, you'll turn right."
        assertEquals(
            "Eyes-off cues with no embedded distance must pass through unchanged",
            phrase,
            rewriteEmbeddedDistance(phrase, "en", 80),
        )
    }

    @Test
    fun englishMidCueReplacesDistance() {
        val phrase = "In 150 meters, get ready to turn right."
        val out = rewriteEmbeddedDistance(phrase, "en", 45)
        assertFalse(out.contains("150 meters"))
        assertTrue(out.contains("get ready to turn right."))
    }

    @Test
    fun frenchReplacesEmbeddedDistance() {
        val out = rewriteEmbeddedDistance(
            "Dans 300 mètres, après Starbucks, tournez à droite.",
            "fr", 70,
        )
        assertFalse(
            "French baked distance must be replaced; got: $out",
            out.contains("300 mètres"),
        )
        assertTrue(out.contains("après Starbucks"))
    }

    @Test
    fun germanReplacesEmbeddedDistance() {
        val out = rewriteEmbeddedDistance(
            "In 300 Metern nach Starbucks rechts abbiegen.",
            "de", 70,
        )
        assertFalse(out.contains("300 Metern"))
    }

    @Test
    fun arabicReplacesEmbeddedDistance() {
        val out = rewriteEmbeddedDistance(
            "في 300 متر بعد ستاربكس، حوّد يمين.",
            "ar-EG", 70,
        )
        assertFalse(
            "Arabic baked distance must be replaced; got: $out",
            out.contains("300"),
        )
    }

    @Test
    fun zeroOrNegativeLiveMetersIsNoop() {
        val phrase = "In 300 meters, turn right."
        assertEquals(
            "liveMeters=0 must not rewrite (likely an arrival)",
            phrase,
            rewriteEmbeddedDistance(phrase, "en", 0),
        )
    }

    @Test
    fun kmDistanceRewriteEnglish() {
        val phrase = "In 1.2 kilometers, turn right onto Main Street."
        val out = rewriteEmbeddedDistance(phrase, "en", 350)
        assertFalse(out.contains("1.2 kilometers"))
        assertTrue(out.contains("turn right onto Main Street."))
    }
}
