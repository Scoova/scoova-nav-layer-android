package com.scoova.navlayer.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Audio output route the user is currently hearing through. Drives
 * latency compensation (A2DP adds 100-400 ms) and feature gating (mono
 * speakers can't carry spatial audio cleanly).
 */
public enum class AudioRoute(public val displayName: String, public val defaultLookaheadMs: Int) {
    BuiltInSpeaker("Built-in speaker", 0),
    WiredHeadphones("Wired headphones", 30),
    BluetoothA2dp("Bluetooth (music)", 250),
    BluetoothSco("Bluetooth (call)",  280),
    UsbHeadset("USB headset", 60),
    Hdmi("HDMI / dock", 80),
    CarAudio("Car audio (Auto/CarPlay)", 180),
    Unknown("Unknown", 100);
}

/**
 * Wraps [AudioManager] with three reliability features:
 *
 *   • **Route detection** — reads the currently active output device
 *     and emits a [StateFlow] of it. Used to compensate for Bluetooth
 *     A2DP latency in the cue threshold ladder.
 *   • **Audio focus** — requests transient-may-duck focus before a cue
 *     and abandons it after, so other apps' music duck for the cue
 *     and restore automatically.
 *   • **No system volume mutation** — never calls `setStreamVolume`. The
 *     user's volume slider is sacred.
 *
 * Pure Kotlin, lifecycle-aware, framework-agnostic.
 */
public class AudioReliability internal constructor(
    private val ctx: Context,
) {

    private val am: AudioManager =
        ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _route = MutableStateFlow(detectRoute())
    public val route: StateFlow<AudioRoute> = _route.asStateFlow()

    private var focusReq: AudioFocusRequest? = null
    @Volatile private var holdingFocus = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> holdingFocus = false
            AudioManager.AUDIOFOCUS_GAIN -> holdingFocus = true
        }
    }

    /** Refresh the cached route. Call when you suspect a change (or hook OS callbacks). */
    public fun refreshRoute() {
        _route.value = detectRoute()
    }

    /** Request transient-may-duck focus. Returns true if granted. */
    @Synchronized
    public fun requestFocus(): Boolean {
        if (holdingFocus) return true
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener)
                .setWillPauseWhenDucked(false)
                .build()
            focusReq = req
            val res = am.requestAudioFocus(req)
            holdingFocus = res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            holdingFocus
        } else {
            @Suppress("DEPRECATION")
            val res = am.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
            holdingFocus = res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            holdingFocus
        }
    }

    @Synchronized
    public fun abandonFocus() {
        if (!holdingFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusReq?.let { am.abandonAudioFocusRequest(it) }
            focusReq = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusListener)
        }
        holdingFocus = false
    }

    private fun detectRoute(): AudioRoute {
        // API 23+ — AudioDeviceInfo is the right way to ask
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            // Priority order: external > built-in
            val ordered = devices.sortedByDescending { priority(it.type) }
            val pick = ordered.firstOrNull() ?: return AudioRoute.Unknown
            return when (pick.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.BuiltInSpeaker
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET   -> AudioRoute.WiredHeadphones
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP  -> AudioRoute.BluetoothA2dp
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO   -> AudioRoute.BluetoothSco
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE      -> AudioRoute.UsbHeadset
                AudioDeviceInfo.TYPE_HDMI,
                AudioDeviceInfo.TYPE_DOCK            -> AudioRoute.Hdmi
                else -> AudioRoute.Unknown
            }
        }
        // Pre-23 fallback
        @Suppress("DEPRECATION")
        return when {
            am.isBluetoothA2dpOn -> AudioRoute.BluetoothA2dp
            am.isBluetoothScoOn  -> AudioRoute.BluetoothSco
            am.isWiredHeadsetOn  -> AudioRoute.WiredHeadphones
            else                 -> AudioRoute.BuiltInSpeaker
        }
    }

    private fun priority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP   -> 10
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO    -> 9
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE       -> 8
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET    -> 7
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_DOCK             -> 6
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> 1
        else                                  -> 0
    }
}

/**
 * Diagnostics snapshot — what the SDK thinks is true. Mirror surface
 * across all platforms; the RN / Flutter bridges expose the same fields.
 */
public data class Diagnostics(
    val audioRoute: AudioRoute = AudioRoute.Unknown,
    val ttsEngineReady: Boolean = false,
    val lastCueLatencyMs: Long = -1,
    val voiceLocaleResolved: String? = null,
    val voiceFallback: String? = null,
    val lookaheadOffsetMs: Int = 0,
)
