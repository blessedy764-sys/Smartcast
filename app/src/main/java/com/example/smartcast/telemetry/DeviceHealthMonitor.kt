 package com.example.smartcast.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

data class DeviceHealthState(
    val thermalStatus: Int,
    val batteryLevelPercent: Int,
    val isCharging: Boolean
)

class DeviceHealthMonitor(
    context: Context,
    private val onUpdate: (DeviceHealthState) -> Unit
) {
    private val appContext = context.applicationContext
    private val powerManager =
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var thermalStatus = 0
    private var batteryPercent = 100
    private var charging = false
    private var running = false
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent != null) {
                readBattery(intent)
                publish()
            }
        }
    }

    fun startMonitoring() {
        if (running) return
        running = true

        // Battery: sticky broadcast gives the current value immediately
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(batteryReceiver, filter)
        }
        if (sticky != null) readBattery(sticky)

        // Thermal: only available on Android 10 (API 29) and up
        if (Build.VERSION.SDK_INT >= 29) {
            thermalStatus = powerManager.currentThermalStatus
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                thermalStatus = status
                publish()
            }
            thermalListener = listener
            powerManager.addThermalStatusListener(appContext.mainExecutor, listener)
        }

        publish()
    }

    fun stopMonitoring() {
        if (!running) return
        running = false
        try {
            appContext.unregisterReceiver(batteryReceiver)
        } catch (_: IllegalArgumentException) {
        }
        if (Build.VERSION.SDK_INT >= 29) {
            thermalListener?.let { powerManager.removeThermalStatusListener(it) }
        }
        thermalListener = null
    }

    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) {
            batteryPercent = (level * 100) / scale
        }
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun publish() {
        onUpdate(DeviceHealthState(thermalStatus, batteryPercent, charging))
    }
}
