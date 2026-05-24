package com.scoova.ride

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays a short two-tone test to prove stereo routing is working — a tone
 * in the left ear, then a tone in the right ear. If the user hears both,
 * earbuds are connected correctly and spatial cues will land where Scoova
 * thinks they will.
 *
 * Self-contained: no TTS, no MediaPlayer, no asset files. Runs off a single
 * AudioTrack with hand-synthesised PCM.
 */
object SpatialAudioTest {

    private const val SAMPLE_RATE = 44100
    private const val TONE_HZ_LEFT = 540
    private const val TONE_HZ_RIGHT = 720
    private const val TONE_MS = 450
    private const val GAP_MS = 120

    /**
     * Plays "left tone" → short silence → "right tone".
     *
     * Blocking-free — uses [AudioTrack.MODE_STATIC] so the buffer is written
     * once and playback handled by the audio HAL. Returns immediately.
     */
    fun play() {
        Thread { runBlocking() }.start()
    }

    private fun runBlocking() {
        val toneSamples = (SAMPLE_RATE * TONE_MS) / 1000
        val gapSamples  = (SAMPLE_RATE * GAP_MS) / 1000
        val totalFrames = (toneSamples * 2) + gapSamples
        val totalSamples = totalFrames * 2  // stereo interleaved
        val pcm = ShortArray(totalSamples)

        // Frames 0..toneSamples-1 → left tone (L=loud, R=quiet)
        // Frames toneSamples..toneSamples+gap-1 → silence
        // Frames toneSamples+gap..end → right tone (L=quiet, R=loud)
        val ampStrong = 24_000.toShort()
        val ampWeak   = 6_000.toShort()  // ~-12 dB on the other ear

        // Left tone block
        for (n in 0 until toneSamples) {
            val v = (sin(2.0 * PI * TONE_HZ_LEFT * n / SAMPLE_RATE) * Short.MAX_VALUE).toInt()
            val fade = fadeEnvelope(n, toneSamples)
            pcm[2 * n    ] = ((v * fade * ampStrong / Short.MAX_VALUE).toInt()).toShort()
            pcm[2 * n + 1] = ((v * fade * ampWeak   / Short.MAX_VALUE).toInt()).toShort()
        }

        // Right tone block — written after the silence gap
        val offset = (toneSamples + gapSamples) * 2
        for (n in 0 until toneSamples) {
            val v = (sin(2.0 * PI * TONE_HZ_RIGHT * n / SAMPLE_RATE) * Short.MAX_VALUE).toInt()
            val fade = fadeEnvelope(n, toneSamples)
            pcm[offset + 2 * n    ] = ((v * fade * ampWeak   / Short.MAX_VALUE).toInt()).toShort()
            pcm[offset + 2 * n + 1] = ((v * fade * ampStrong / Short.MAX_VALUE).toInt()).toShort()
        }

        val bytesPerSample = 2  // 16-bit PCM
        val bufferBytes = totalSamples * bytesPerSample

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        runCatching {
            track.write(pcm, 0, pcm.size)
            track.play()
            // Hold the thread until playback finishes so the track isn't released early.
            val totalMs = (TONE_MS * 2) + GAP_MS + 60
            Thread.sleep(totalMs.toLong())
        }
        runCatching { track.stop(); track.release() }
    }

    /** Linear fade-in / fade-out envelope, 12 ms each side. */
    private fun fadeEnvelope(n: Int, total: Int): Double {
        val fadeSamples = (SAMPLE_RATE * 12) / 1000
        return when {
            n < fadeSamples           -> n.toDouble() / fadeSamples
            n > total - fadeSamples   -> (total - n).toDouble() / fadeSamples
            else                      -> 1.0
        }
    }
}
