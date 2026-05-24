package com.scoova.navlayer.core

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import org.json.JSONObject
import java.io.IOException

/**
 * Pre-rendered dialect voice clips bundled in the SDK / app assets.
 *
 * For dialects where the on-device TTS only knows MSA (every Arabic dialect),
 * we ship a folder of WAV clips synthesised at build time by a real dialect
 * voice (Egyptian, Gulf, Levantine, Maghrebi). At runtime, when [VoiceEngine]
 * is about to speak a cue, it first asks the pack: "do you have a clip for
 * this exact text?" If yes — play the clip; the rider hears authentic dialect
 * audio. If no — fall through to on-device TTS as before.
 *
 * Two lookup paths:
 *   1. **Exact** — the cue text is one whole sentence we pre-rendered
 *      (e.g. "حوّد يمين دلوقتي." → one clip).
 *   2. **Comma-split** — the cue is a distance lead-in + an instruction
 *      ("بعد 200 متر، حوّد يمين.") — we play *two* clips back-to-back, joined
 *      at a natural comma pause. The seam is where a human would breathe
 *      anyway, so it doesn't sound chopped.
 *
 * Pack location: assets `voicepack/{locale}/manifest.json` + clip files.
 */
internal class VoicePack private constructor(
    private val ctx: Context,
    private val assetDir: String,
    private val textToClip: Map<String, String>,
) {

    /** Find a clip for the literal cue text. Returns the asset path, or null. */
    fun lookup(text: String): String? {
        for (c in candidates(text)) {
            textToClip[c]?.let { return "$assetDir/$it" }
        }
        return null
    }

    /**
     * Try to split the cue at the Arabic comma (،) and look up each part.
     * Returns the ordered list of asset paths, or null if any part is
     * missing. Each part is matched leniently (street suffix / landmark
     * prefix stripped if needed).
     */
    fun lookupCompound(text: String): List<String>? {
        // Try each candidate form of the whole text — landmark-prefix may
        // wrap a comma-split cue, and stripping it first can yield a
        // single-clip match.
        for (form in formsToTry(text)) {
            if ('،' !in form) continue
            val parts = form.split('،').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size < 2) continue
            val clips = ArrayList<String>(parts.size)
            var ok = true
            for ((i, part) in parts.withIndex()) {
                val key = if (i < parts.size - 1) "$part،" else part
                val clip = lookup(key)
                if (clip == null) { ok = false; break }
                clips.add(clip)
            }
            if (ok) return clips
        }
        return null
    }

    // ── Lenient matching: strip server-side landmark/street wrappers ────
    //
    // The server's voice cue can come wrapped with a leading landmark
    // anchor ("بعد ميدان X على شمالك، ") and/or a trailing street suffix
    // (" ع شارع Y.") that the pack doesn't have a clip for. The PACK
    // intentionally only ships the bare instructions (eyes-off design:
    // street names live on screen, not in voice). To match wrapped cues,
    // we try the literal text first, then variants with prefix/suffix
    // stripped, then both stripped together.

    private val sideWords = setOf("يمينك", "شمالك", "يدك", "يسارك",
                                  "اليمين", "اليسار")
    private val landmarkPrefixRegex = Regex("^(بعد|عند)\\s+[^،]+،\\s*")
    private val ontoStreetRegex     = Regex("\\s+(?:ع|على)\\s+(\\S+)")

    /** Strip a trailing onto-street suffix like " ع شارع X." — but NOT
     *  " ع يمينك" / " ع شمالك" (intrinsic side words). */
    private fun stripStreetSuffix(s: String): String {
        val matches = ontoStreetRegex.findAll(s).toList()
        if (matches.isEmpty()) return s
        // Walk from last to first; first non-side word match is the
        // street suffix the server appended.
        for (m in matches.reversed()) {
            val firstWord = m.groupValues[1].trimEnd('.', '،')
            if (firstWord in sideWords) continue
            return s.substring(0, m.range.first).trimEnd('.', '،', ' ') + "."
        }
        return s
    }

    /** Strip a "بعد X على Y، " or "عند X على Y، " landmark prefix. */
    private fun stripLandmarkPrefix(s: String): String =
        landmarkPrefixRegex.replaceFirst(s, "").trim()

    private fun formsToTry(text: String): List<String> {
        val cleaned = text.trim()
        val out = LinkedHashSet<String>()
        out.add(cleaned)
        val noStreet = stripStreetSuffix(cleaned)
        if (noStreet != cleaned) out.add(noStreet)
        val noLm = stripLandmarkPrefix(cleaned)
        if (noLm != cleaned) {
            out.add(noLm)
            val both = stripStreetSuffix(noLm)
            if (both != noLm) out.add(both)
        }
        return out.toList()
    }

    private fun candidates(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (form in formsToTry(text)) {
            out.add(form)
            val trimmed = form.trimEnd('.', '،', ' ')
            if (trimmed.isNotEmpty()) out.add(trimmed)
            if (!form.endsWith(".") && trimmed.isNotEmpty()) out.add("$trimmed.")
        }
        return out.toList()
    }

    companion object {
        private const val TAG = "VoicePack"

        /**
         * Try to load the pack for [locale] from assets. Returns null if no
         * pack is bundled for that locale (caller treats absence as "fall
         * through to device TTS for everything").
         */
        fun loadOrNull(ctx: Context, locale: String): VoicePack? {
            val dir = "voicepack/$locale"
            return try {
                val json = ctx.assets.open("$dir/manifest.json")
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                val obj = JSONObject(json)
                val mapObj = obj.getJSONObject("text_to_clip")
                val keys = mapObj.keys()
                val map = HashMap<String, String>()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k.trim()] = mapObj.getString(k)
                }
                Log.i(TAG, "loaded $dir → ${map.size} entries")
                VoicePack(ctx, dir, map)
            } catch (e: IOException) {
                // No pack bundled for this locale → null, that's fine.
                null
            } catch (e: Exception) {
                Log.w(TAG, "voice pack at $dir is malformed: $e")
                null
            }
        }
    }
}


/**
 * Plays a sequence of asset-bundled audio clips back-to-back, then invokes
 * [onDone]. Used by [VoiceEngine] when a cue resolves to one or more
 * dialect-pack clips instead of synthesised TTS.
 *
 * Why MediaPlayer + AssetFileDescriptor: the alternative — copying each
 * clip out to cacheDir first — burns disk I/O and battery on every cue.
 * AssetFileDescriptor lets MediaPlayer stream directly from the .apk.
 */
internal class ClipSequencePlayer(
    private val ctx: Context,
    private val assetPaths: List<String>,
    private val onDone: () -> Unit,
    private val onError: () -> Unit,
) {
    private var index = 0
    private var current: MediaPlayer? = null
    @Volatile private var cancelled = false

    fun start() { advance() }

    fun stop() {
        cancelled = true
        current?.let { runCatching { it.stop(); it.release() } }
        current = null
    }

    private fun advance() {
        if (cancelled) return
        if (index >= assetPaths.size) { onDone(); return }
        val path = assetPaths[index++]
        val mp = try {
            val afd: AssetFileDescriptor = ctx.assets.openFd(path)
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setOnCompletionListener {
                    runCatching { it.release() }
                    current = null
                    advance()
                }
                setOnErrorListener { p, _, _ ->
                    runCatching { p.release() }
                    current = null
                    onError()
                    true
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            onError()
            return
        }
        current = mp
    }
}
