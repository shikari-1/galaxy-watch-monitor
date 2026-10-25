package com.galaxywatch.monitor.data

/**
 * Immutable telemetry model representing live PC hardware statistics
 * received every 1.0 second over Bluetooth Low Energy (BLE).
 */
data class HardwareTelemetry(
    val gpuTemp: Int = 0,
    val cpuTemp: Int = 0,
    val gpuUsage: Int = 0,
    val cpuUsage: Int = 0,
    val vramUsedMb: Int = 0,
    val vramTotalMb: Int = 8192,
    val vramPercent: Int = 0,
    val ramUsedMb: Int = 0,
    val ramTotalMb: Int = 16384,
    val ramPercent: Int = 0,
    val gpuPowerW: Int = 0,
    val istHour: Int = 0,
    val istMinute: Int = 0,
    val istSecond: Int = 0,
    val isOnline: Boolean = false,
    val lastUpdateTimestamp: Long = 0L
) {
    val vramUsedGb: String get() = "%.1f".format(vramUsedMb / 1024f)
    val vramTotalGb: String get() = "%.1f".format(vramTotalMb / 1024f)
    val ramUsedGb: String get() = "%.1f".format(ramUsedMb / 1024f)
    val ramTotalGb: String get() = "%.1f".format(ramTotalMb / 1024f)

    val formattedIstTime: String
        get() = "%02d:%02d:%02d".format(istHour, istMinute, istSecond)
}
