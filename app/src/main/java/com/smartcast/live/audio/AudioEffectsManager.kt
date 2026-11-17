package com.smartcast.live.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

object AudioEffectsManager {
    private const val TAG = "AudioEffectsManager"

    // RNNoise already cleans the host mic, so the phone's own noise suppressor stays off.
    // Set to true only if you want to compare the two by ear.
    private const val USE_PLATFORM_NOISE_SUPPRESSOR = false

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    fun attachEffects(audioSessionId: Int) {
        // 1. Acoustic Echo Canceler (AEC): stops the speaker feeding back into the mic
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                aec = AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
                Log.d(TAG, "Acoustic Echo Canceler enabled.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable AEC", e)
            }
        } else {
            Log.w(TAG, "AEC not supported on this device.")
        }

        // 2. Platform Noise Suppressor: off by default because RNNoise replaces it
        if (USE_PLATFORM_NOISE_SUPPRESSOR) {
            if (NoiseSuppressor.isAvailable()) {
                try {
                    ns = NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
                    Log.d(TAG, "Noise Suppressor enabled.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to enable Noise Suppressor", e)
                }
            } else {
                Log.w(TAG, "Noise Suppressor not supported on this device.")
            }
        }

        // 3. Automatic Gain Control (AGC): boosts whispers, tames loud shouts
        if (AutomaticGainControl.isAvailable()) {
            try {
                agc = AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
                Log.d(TAG, "Automatic Gain Control enabled.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable AGC", e)
            }
        } else {
            Log.w(TAG, "AGC not supported on this device.")
        }
    }

    fun releaseEffects() {
        try {
            aec?.release()
            ns?.release()
            agc?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        aec = null
        ns = null
        agc = null
        Log.d(TAG, "Audio effects released.")
    }
}
