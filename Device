val healthMonitor = DeviceHealthMonitor(context) { state ->
    when (state.thermalStatus) {
        PowerManager.THERMAL_STATUS_MODERATE, 
        PowerManager.THERMAL_STATUS_SEVERE -> {
            // Phone is getting warm! Drop Opus bitrate to 16kbps to reduce CPU strain
            // opusEncoder.setBitrate(16000)
            showThermalWarningBanner("Device running warm. Optimizing audio stream...")
        }
        PowerManager.THERMAL_STATUS_CRITICAL -> {
            // Dangerous heat level — prepare user or gracefully close session
            showCriticalAlert("Device overheating. Stopping broadcast to prevent shutdown.")
        }
    }

    if (state.batteryLevelPercent <= 15 && !state.isCharging) {
        // Warn tourist that battery is critically low
        showLowBatteryWarning()
    }
}

// Start tracking when session begins
healthMonitor.startMonitoring()
