package com.gitco.smartcast.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

object AudioEffectsManager {
    private const val TAG = "AudioEffectsManager"

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    fun attachEffects(audioSessionId: Int) {
        // 1. Acoustic Echo Canceler (AEC)
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                aec = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                }
                Log.d(TAG, "Acoustic Echo Canceler enabled.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable AEC", e)
            }
        } else {
            Log.w(TAG, "AEC not supported on this device.")
        }

        // 2. Noise Suppressor (NS) - Filters out wind, crowd chatter, background hums
        if (NoiseSuppressor.isAvailable()) {
            try {
                ns = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                }
                Log.d(TAG, "Noise Suppressor enabled.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable Noise Suppressor", e)
            }
        } else {
            Log.w(TAG, "Noise Suppressor not supported on this device.")
        }

        // 3. Automatic Gain Control (AGC) - Boosts whispers, tames loud shouts
        if (AutomaticGainControl.isAvailable()) {
            try {
                agc = AutomaticGainControl.create(audioSessionId)?.apply {
                    enabled = true
                }
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
