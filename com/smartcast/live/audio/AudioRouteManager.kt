package com.smartcast.live.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Picks the best voice route and keeps it correct when headsets come and go.
 *
 * Android 12+ (API 31): setCommunicationDevice() / clearCommunicationDevice().
 * Android 10-11: legacy SCO / speakerphone fallback (the only place startBluetoothSco is used).
 *
 * Use enterCommunication() only for host mic + guest push-to-talk. A listen-only receiver should
 * NOT enter it, so Bluetooth stays on the high-quality media path (A2DP / LE Audio / hearing aids).
 */
class AudioRouteManager(
    context: Context,
    private val onRouteChanged: (String) -> Unit
) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var inCommunication = false
    private var monitoring = false

    // Best first. Wired/USB beat Bluetooth: a Bluetooth SCO mic is narrowband and hurts broadcast quality.
    private val priority = listOf(
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    )

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = onDevicesChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onDevicesChanged()
    }

    /** Start watching for headset / speaker changes (safe to call once from onCreate). */
    fun startMonitoring() {
        synchronized(lock) {
            if (monitoring) return
            monitoring = true
            audioManager.registerAudioDeviceCallback(deviceCallback, main)
        }
        report()
    }

    fun stopMonitoring() {
        synchronized(lock) {
            if (!monitoring) return
            monitoring = false
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        }
    }

    /** Call while the app is visible or a typed foreground service is running. Idempotent. */
    fun enterCommunication() {
        synchronized(lock) {
            if (!inCommunication) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                inCommunication = true
            }
            applyBestDevice()
        }
    }

    fun leaveCommunication() {
        synchronized(lock) {
            if (!inCommunication) return
            inCommunication = false
            if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice() else legacyStop()
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        report()
    }

    // ---------------------------------------------------------

    private fun onDevicesChanged() {
        synchronized(lock) {
            // A headset appeared or vanished: re-pick so we never stay on a dead route.
            if (inCommunication) applyBestDevice() else report()
        }
    }

    private fun applyBestDevice() {
        if (Build.VERSION.SDK_INT >= 31) {
            val best = bestOf(audioManager.availableCommunicationDevices)
            val ok = best != null && audioManager.setCommunicationDevice(best)
            // If the request is refused, drop back to the system default instead of staying silent.
            if (!ok) audioManager.clearCommunicationDevice()
        } else {
            legacyStart()
        }
        report()
    }

    private fun rank(type: Int): Int = priority.indexOf(type).let { if (it < 0) Int.MAX_VALUE else it }

    private fun bestOf(devices: List<AudioDeviceInfo>): AudioDeviceInfo? =
        devices.filter { rank(it.type) != Int.MAX_VALUE }.minByOrNull { rank(it.type) }

    @Suppress("DEPRECATION")
    private fun legacyStart() {
        val types = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        val wired = types.any {
            it == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        val sco = types.contains(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        when {
            wired -> {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = false
            }
            sco -> {
                audioManager.isSpeakerphoneOn = false
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
            else -> {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = true
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyStop() {
        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false
        audioManager.isSpeakerphoneOn = false
    }

    private fun currentOutput(): AudioDeviceInfo? {
        if (inCommunication && Build.VERSION.SDK_INT >= 31) {
            audioManager.communicationDevice?.let { return it }
        }
        return bestOf(audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList())
    }

    private fun describe(d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headset"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "LE Audio headset"
        AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        else -> "Other output"
    }

    private fun report() {
        val label = "Audio: " + (currentOutput()?.let { describe(it) } ?: "default")
        main.post { onRouteChanged(label) }
    }
}
