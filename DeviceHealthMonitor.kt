package com.gitco.smartcast.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi

data class DeviceHealthState(
    val batteryLevelPercent: Int,
    val isCharging: Boolean,
    val thermalStatus: Int // 0 = NONE, 1 = LIGHT, 2 = MODERATE, 3 = SEVERE, 4 = CRITICAL
)

class DeviceHealthMonitor(
    private val context: Context,
    private val onHealthStateChanged: (DeviceHealthState) -> Unit
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    fun startMonitoring() {
        // 1. Check initial battery & power state
        val initialState = pollCurrentState()
        onHealthStateChanged(initialState)

        // 2. Register for thermal status changes (Android 10 / API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                val current = pollCurrentState().copy(thermalStatus = status)
                onHealthStateChanged(current)
            }
            powerManager.addThermalStatusListener(context.mainExecutor, thermalListener!!)
        }
    }

    fun pollCurrentState(): DeviceHealthState {
        // Battery percentage and charging check
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else 100

        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || 
                         status == BatteryManager.BATTERY_STATUS_FULL

        // Thermal status check
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.currentThermalStatus
        } else {
            0 // Default to NONE on older legacy devices
        }

        return DeviceHealthState(batteryPct, isCharging, thermalStatus)
    }

    fun stopMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
            powerManager.removeThermalStatusListener(thermalListener!!)
            thermalListener = null
        }
    }
}
