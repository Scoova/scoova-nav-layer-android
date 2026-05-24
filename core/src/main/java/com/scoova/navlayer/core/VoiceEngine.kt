package com.scoova.navlayer.core

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Importance bucket — higher pre-empts lower mid-utterance. */
public enum class CueTone(public val priority: Int) {
    Calm(0), Normal(1), Cheerful(1), Urgent(2), Alert(3);
}

/**
 * TTS wrapper with three behaviours that matter for nav:
 *
 *   • **Min-gap** — never speak two cues within [minGapMs] of each other,
 *     unless the new cue is strictly higher priority.
 *   • **Locale fallback** — "ar-EG" gracefully degrades to "ar" if the
 *     device doesn't have the Egyptian voice installed.
 *   • **Spatial audio** — when [spatialPan] != 0, the cue is synthesised to
 *     a temp WAV and played back with a left/right volume balance, so a
 *     left-turn cue arrives in your left ear and a right-turn in your right.
 *     Drivers wearing one bud, cyclists on Bluetooth earbuds, scooter riders
 *     with helmet speakers — all get directional cues "for free".
 *
 * The spatial path costs ~50–150 ms vs the direct TTS path. Acceptable for
 * navigation cues which fire seconds before the turn anyway.
 */
public class VoiceEngine(context: Context) {
    private val ctx = context.applicationContext
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var lastSpeakAt = 0L
    @Volatile private var currentTone: CueTone = CueTone.Calm

    private val utteranceCounter = AtomicLong()
    private val pendingPlayback = mutableMapOf<String, Pair<File, Float>>()
    private val playerLock = Any()
    @Volatile private var currentPlayer: MediaPlayer? = null

    /**
     * Dialect voice pack bundled in `voicepack/{locale}/`. When present, the
     * pack is checked first inside [say] — if it has a clip for the cue
     * text, that clip plays instead of on-device TTS. Falls through to TTS
     * when the pack is missing the cue or absent entirely (e.g. on-device
     * MSA voice for non-dialect locales).
     */
    @Volatile private var voicePack: VoicePack? = null
    @Volatile private var clipSeqPlayer: ClipSequencePlayer? = null

    public var minGapMs: Long = 2500L
    public var rate: Float = 1.0f
    public var pitch: Float = 1.0f
    public var locale: String = "en-US"
        set(value) { field = value; applyLocale() }

    /** Toggle spatial routing. Default on. Off → all cues centred via direct TTS. */
    public var spatialEnabled: Boolean = true

    /** Master mute — when false, [say] silently no-ops. */
    public var voiceEnabled: Boolean = true

    // Audio reliability — audio focus, route detection, never touches system volume.
    internal val reliability: AudioReliability = AudioReliability(ctx)

    private val _ttsReady = MutableStateFlow(false)
    public val ttsReady: StateFlow<Boolean> = _ttsReady.asStateFlow()

    private val _lastCueLatencyMs = MutableStateFlow(-1L)
    public val lastCueLatencyMs: StateFlow<Long> = _lastCueLatencyMs.asStateFlow()

    private val _voiceFallback = MutableStateFlow<String?>(null)
    public val voiceFallback: StateFlow<String?> = _voiceFallback.asStateFlow()

    private val _voiceLocaleResolved = MutableStateFlow<String?>(null)
    public val voiceLocaleResolved: StateFlow<String?> = _voiceLocaleResolved.asStateFlow()

    /**
     * One-shot stream of every utterance the engine successfully
     * accepted for playback. Used by the diagnostic simulator to
     * build a cue-timing report — every "voice fired" gets a
     * timestamp + text + tone the report compares against the route's
     * threshold tiers. Buffer is small (16) on purpose; subscribers
     * that lag behind drop oldest, the report just sees fewer events.
     */
    private val _spokenEvents = MutableSharedFlow<SpokenEvent>(extraBufferCapacity = 16)
    public val spokenEvents: SharedFlow<SpokenEvent> = _spokenEvents.asSharedFlow()

    /** Payload of [spokenEvents]. */
    public data class SpokenEvent(
        public val tsMs: Long,
        public val text: String,
        public val tone: CueTone,
        public val spatialPan: Float,
        public val utteranceId: String,
    )

    /** Per-utterance latency measurement: when the threshold was crossed. */
    @Volatile private var pendingThresholdCrossedAtMs: Long = -1L

    public fun init(onReady: (Boolean) -> Unit = {}) {
        tts = TextToSpeech(ctx) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                applyLocale()
                tts?.setSpeechRate(rate)
                tts?.setPitch(pitch)

                // Tell Android this is nav guidance — the OS gives it priority,
                // ducks music, routes to the right output. WITHOUT this, TTS
                // defaults to USAGE_MEDIA which competes with other media.
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        // First measurable point: TTS engine has started speaking.
                        if (utteranceId != null && !utteranceId.startsWith("prewarm-")
                            && pendingThresholdCrossedAtMs > 0) {
                            _lastCueLatencyMs.value =
                                System.currentTimeMillis() - pendingThresholdCrossedAtMs
                            pendingThresholdCrossedAtMs = -1L
                        }
                    }
                    override fun onDone(utteranceId: String?) {
                        utteranceId ?: return
                        val pending = synchronized(pendingPlayback) {
                            pendingPlayback.remove(utteranceId)
                        }
                        if (pending != null) {
                            playWithPan(pending.first, pending.second)
                        } else if (!utteranceId.startsWith("prewarm-")) {
                            // Cue is done — release focus so other apps can resume.
                            reliability.abandonFocus()
                        }
                    }
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onError(utteranceId: String?) {
                        utteranceId ?: return
                        synchronized(pendingPlayback) {
                            pendingPlayback.remove(utteranceId)?.first?.delete()
                        }
                        reliability.abandonFocus()
                    }
                })

                // Pre-warm: speak a silent utterance so the engine loads the
                // voice before the first real cue. Cuts first-cue latency
                // from ~2 s to ~100 ms on cold start.
                prewarm()
            }
            _ttsReady.value = ready
            onReady(ready)
        }
    }

    /**
     * Mark the moment a threshold was crossed in [ScoovaNavLayer.onProgress].
     * The next [say] call measures (lastCue latency = TTS-start-time minus this).
     */
    internal fun markThresholdCrossed() {
        pendingThresholdCrossedAtMs = System.currentTimeMillis()
    }

    private fun prewarm() {
        val t = tts ?: return
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f) }
        t.speak(" ", TextToSpeech.QUEUE_FLUSH, params, "prewarm-${System.nanoTime()}")
    }

    // ── Keep-warm loop ────────────────────────────────────────────
    // Android's TTS engine unloads its voice from memory when idle,
    // and the next utterance pays 200–400 ms of cold-start latency
    // while it loads back in. At cycling / scooter speeds that's
    // several metres of delay on an urgent "turn now" cue — past
    // the turn point. The loop below emits a silent (volume 0)
    // utterance every [KEEP_WARM_INTERVAL_MS] so the engine stays
    // hot. Kicked on when a ride starts (host calls [keepWarmStart])
    // and stopped at ride end ([keepWarmStop]) so we don't drain
    // battery when navigation isn't active.

    private val keepWarmHandler = Handler(Looper.getMainLooper())
    private val keepWarmRunnable = object : Runnable {
        override fun run() {
            if (ready) prewarm()
            keepWarmHandler.postDelayed(this, KEEP_WARM_INTERVAL_MS)
        }
    }
    @Volatile private var keepWarmActive = false

    /** Start the periodic keep-warm pulse. Idempotent. */
    public fun keepWarmStart() {
        if (keepWarmActive) return
        keepWarmActive = true
        keepWarmHandler.postDelayed(keepWarmRunnable, KEEP_WARM_INTERVAL_MS)
    }

    /** Stop the periodic keep-warm pulse. Idempotent. */
    public fun keepWarmStop() {
        if (!keepWarmActive) return
        keepWarmActive = false
        keepWarmHandler.removeCallbacks(keepWarmRunnable)
    }

    private companion object {
        // 30 s falls inside Android's TTS idle-unload window (the OS
        // unloads voices after ~60 s of silence on most devices),
        // with a comfortable margin. Shorter than 30 s burns extra
        // battery for no benefit; longer risks the engine going
        // cold between the keep-warm pulse and the next real cue.
        private const val KEEP_WARM_INTERVAL_MS: Long = 30_000L
    }

    public fun shutdown() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Throwable) {}
        tts = null
        ready = false
        _ttsReady.value = false
        synchronized(playerLock) {
            currentPlayer?.let { runCatching { it.release() } }
            currentPlayer = null
        }
        synchronized(pendingPlayback) {
            pendingPlayback.values.forEach { it.first.delete() }
            pendingPlayback.clear()
        }
        reliability.abandonFocus()
    }

    private fun applyLocale() {
        val t = tts ?: return
        val parts = locale.split("-")
        val loc = if (parts.size >= 2) Locale(parts[0], parts[1]) else Locale(parts[0])
        val res = t.setLanguage(loc)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            val fallback: Locale = if (loc.language == "ar") Locale("ar") else Locale.US
            t.setLanguage(fallback)
            _voiceFallback.value = "${loc.toLanguageTag()} → ${fallback.toLanguageTag()}"
            _voiceLocaleResolved.value = fallback.toLanguageTag()
        } else {
            _voiceFallback.value = null
            _voiceLocaleResolved.value = loc.toLanguageTag()
        }
        // Load the dialect voice pack for this locale (if bundled). When
        // present, [say] plays pre-rendered dialect clips instead of
        // on-device TTS — the rider hears genuine Cairo / Gulf / etc.
        // accent. When absent, [say] falls through to TTS as before.
        voicePack = VoicePack.loadOrNull(ctx, locale)
    }

    /**
     * Speak [text] with optional spatial pan.
     *
     * @param spatialPan -1.0 (full left) … 0.0 (centred) … +1.0 (full right).
     *   Values are clamped. When non-zero AND [spatialEnabled], the cue is
     *   synthesised to a file then played with stereo volume balance.
     */
    public fun say(
        text: String,
        tone: CueTone = CueTone.Normal,
        spatialPan: Float = 0f,
        id: String? = null,
    ): Boolean {
        if (!voiceEnabled) return false
        val t = tts ?: return false
        if (!ready || text.isBlank()) return false
        val now = System.currentTimeMillis()
        val gapElapsed = (now - lastSpeakAt) >= minGapMs
        if (!gapElapsed && tone.priority <= currentTone.priority) return false
        if (tone.priority > currentTone.priority) {
            t.stop()
            synchronized(playerLock) {
                currentPlayer?.let { runCatching { it.stop(); it.release() } }
                currentPlayer = null
            }
        }

        val clampedPan = spatialPan.coerceIn(-1f, 1f)
        val useSpatial = spatialEnabled && clampedPan != 0f
        val utteranceId = id ?: "cue-${utteranceCounter.incrementAndGet()}-$now"

        // Request transient-may-duck focus so other apps' music drops in
        // volume for the cue and restores after. Released in onDone / onError.
        reliability.requestFocus()
        reliability.refreshRoute()

        // ── Voice pack lookup ──────────────────────────────────────
        // Before paying for TTS synthesis, ask the dialect pack if it
        // has this cue. The pack is the only way to get a genuine
        // Egyptian / Gulf / Levantine / Maghrebi accent — on-device TTS
        // only knows MSA. If the pack has the cue, play the clip(s);
        // otherwise fall through to TTS below.
        val pack = voicePack
        if (pack != null) {
            val whole = pack.lookup(text)
            val parts = whole?.let { listOf(it) } ?: pack.lookupCompound(text)
            if (parts != null) {
                clipSeqPlayer?.stop()
                clipSeqPlayer = ClipSequencePlayer(
                    ctx,
                    parts,
                    onDone = {
                        clipSeqPlayer = null
                        reliability.abandonFocus()
                    },
                    onError = {
                        clipSeqPlayer = null
                        reliability.abandonFocus()
                    },
                ).also { it.start() }
                lastSpeakAt = now
                currentTone = tone
                if (pendingThresholdCrossedAtMs > 0) {
                    _lastCueLatencyMs.value = now - pendingThresholdCrossedAtMs
                    pendingThresholdCrossedAtMs = -1L
                }
                _spokenEvents.tryEmit(
                    SpokenEvent(now, text, tone, clampedPan, utteranceId)
                )
                return true
            }
        }

        val res = if (useSpatial) {
            // Synthesise to file, then MediaPlayer plays with stereo balance.
            val outFile = File(ctx.cacheDir, "scoova-cue-$utteranceId.wav")
            synchronized(pendingPlayback) {
                pendingPlayback[utteranceId] = outFile to clampedPan
            }
            t.synthesizeToFile(text, Bundle(), outFile, utteranceId)
        } else {
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            t.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }

        if (res == TextToSpeech.SUCCESS) {
            lastSpeakAt = now
            currentTone = tone
            // Emit on the spoken-events stream so the diagnostic
            // simulator (and any host-app observer) can record the
            // utterance + tone + pan with a timestamp.
            _spokenEvents.tryEmit(
                SpokenEvent(
                    tsMs = now,
                    text = text,
                    tone = tone,
                    spatialPan = clampedPan,
                    utteranceId = utteranceId,
                )
            )
            return true
        } else if (useSpatial) {
            // Clean up on synth failure
            synchronized(pendingPlayback) {
                pendingPlayback.remove(utteranceId)?.first?.delete()
            }
        }
        return false
    }

    /** Plays the synthesised WAV with channel volumes derived from [pan]. */
    private fun playWithPan(file: File, pan: Float) {
        if (!file.exists()) return
        val (leftVol, rightVol) = panToVolumes(pan)

        val mp = MediaPlayer().apply {
            try {
                // Same nav-guidance attributes on the spatial playback path —
                // so MediaPlayer-driven cues duck other audio just like TTS does.
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setVolume(leftVol, rightVol)
                setOnCompletionListener { player ->
                    runCatching { player.release() }
                    synchronized(playerLock) {
                        if (currentPlayer === player) currentPlayer = null
                    }
                    file.delete()
                    reliability.abandonFocus()
                }
                setOnErrorListener { player, _, _ ->
                    runCatching { player.release() }
                    synchronized(playerLock) {
                        if (currentPlayer === player) currentPlayer = null
                    }
                    file.delete()
                    reliability.abandonFocus()
                    true
                }
                prepare()
            } catch (t: Throwable) {
                runCatching { release() }
                file.delete()
                reliability.abandonFocus()
                return
            }
        }
        synchronized(playerLock) {
            currentPlayer?.let { runCatching { it.stop(); it.release() } }
            currentPlayer = mp
        }
        runCatching { mp.start() }
    }

    private fun panToVolumes(pan: Float): Pair<Float, Float> {
        // Equal-power panning, biased so the "off" side stays audible (0.4
        // floor) — fully muting one ear is disorienting under motion.
        val p = pan.coerceIn(-1f, 1f)
        val left  = if (p <= 0f) 1.0f else (1.0f - 0.6f * p)
        val right = if (p >= 0f) 1.0f else (1.0f + 0.6f * p)
        return left to right
    }
}
