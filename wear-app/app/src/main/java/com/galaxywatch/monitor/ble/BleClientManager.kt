package com.galaxywatch.monitor.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.galaxywatch.monitor.data.HardwareTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

/**
 * BLE GATT Client Manager for Galaxy Watch 4.
 *
 * Connects to the PC's GATT Server hosting the telemetry service,
 * subscribes to CCCD notifications, and receives 16-byte binary
 * telemetry packets at 1Hz over a persistent BLE connection.
 *
 * Flow:
 *   1. Scan for the PC advertising SERVICE_UUID
 *   2. Stop scan, connect via connectGatt(TRANSPORT_LE)
 *   3. Discover services, subscribe to CHARACTERISTIC_UUID via CCCD
 *   4. Receive onCharacteristicChanged() every ~1000ms
 *   5. On disconnect, re-scan after a short backoff
 */
@SuppressLint("MissingPermission")
class BleClientManager(private val context: Context) {

    companion object {
        private const val TAG = "BleClientManager"

        // Must match the PC GATT Server UUIDs
        val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val CLIENT_CONFIG_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val STALE_TIMEOUT_MS = 4500L
        private const val SCAN_TIMEOUT_MS = 30000L
        private const val RECONNECT_DELAY_MS = 3000L
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private var isStopped = false

    private val _telemetryState = MutableStateFlow(HardwareTelemetry())
    val telemetryState: StateFlow<HardwareTelemetry> = _telemetryState.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())

    // Watchdog: marks telemetry offline if no packet arrives within STALE_TIMEOUT_MS
    private val livenessWatchdog = object : Runnable {
        override fun run() {
            if (isStopped) return
            val lastUpdate = _telemetryState.value.lastUpdateTimestamp
            val now = System.currentTimeMillis()
            if (lastUpdate > 0 && (now - lastUpdate > STALE_TIMEOUT_MS)) {
                if (_telemetryState.value.isOnline) {
                    Log.w(TAG, "Telemetry stale (>${STALE_TIMEOUT_MS}ms). Marking offline.")
                    _telemetryState.value = _telemetryState.value.copy(isOnline = false)
                }
                // If we lost connection without getting a disconnect callback, try reconnecting
                if (bluetoothGatt == null && !isScanning) {
                    Log.i(TAG, "No active connection or scan. Restarting scan...")
                    startScan()
                }
            }
            handler.postDelayed(this, 2000)
        }
    }

    // Safety: stop scanning after SCAN_TIMEOUT_MS to comply with Android scan limits
    private val scanTimeoutRunnable = Runnable {
        if (isScanning) {
            Log.w(TAG, "Scan timeout reached (${SCAN_TIMEOUT_MS}ms). Stopping scan, will retry.")
            stopScan()
            // Retry after a brief pause to avoid Android's 5-scans-in-30s throttle
            handler.postDelayed({ startScan() }, RECONNECT_DELAY_MS)
        }
    }

    fun start() {
        isStopped = false
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth not available or disabled")
            return
        }
        handler.post(livenessWatchdog)
        startScan()
    }

    fun stop() {
        isStopped = true
        handler.removeCallbacks(livenessWatchdog)
        handler.removeCallbacks(scanTimeoutRunnable)
        stopScan()
        bluetoothGatt?.let { gatt ->
            gatt.disconnect()
            gatt.close()
        }
        bluetoothGatt = null
    }

    // ─── SCANNING ───────────────────────────────────────────────

    private fun startScan() {
        if (isStopped || isScanning || bluetoothGatt != null) return
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return

        isScanning = true
        Log.i(TAG, "Scanning for PC GATT Server (Service UUID: $SERVICE_UUID)...")

        // Filter specifically for our Service UUID — eliminates all unrelated BLE traffic
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        // Use LOW_LATENCY for fast initial discovery, then stop scan once connected
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .setReportDelay(0)
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)

        // Safety timeout to avoid indefinite scanning
        handler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
    }

    private fun stopScan() {
        if (!isScanning) return
        handler.removeCallbacks(scanTimeoutRunnable)
        val scanner = bluetoothAdapter.bluetoothLeScanner
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping BLE scan: ${e.message}")
        }
        isScanning = false
    }

    // ─── SCAN CALLBACK ──────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            Log.i(TAG, "Found PC GATT Server: ${device.address} (RSSI: ${result.rssi})")

            // Immediately stop scanning to save power and avoid Android throttle limits
            stopScan()

            // Connect directly via TRANSPORT_LE (no Classic Bluetooth fallback)
            connectToDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error code $errorCode")
            isScanning = false
            handler.removeCallbacks(scanTimeoutRunnable)
            // Retry after backoff
            if (!isStopped) {
                handler.postDelayed({ startScan() }, RECONNECT_DELAY_MS)
            }
        }
    }

    // ─── GATT CONNECTION ────────────────────────────────────────

    private fun connectToDevice(device: BluetoothDevice) {
        Log.i(TAG, "Connecting to PC at ${device.address} via TRANSPORT_LE...")
        bluetoothGatt = device.connectGatt(
            context,
            false,               // autoConnect=false for immediate connection attempt
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to PC GATT Server. Discovering services...")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "Disconnected from PC (status=$status). Cleaning up...")
                    gatt.close()
                    bluetoothGatt = null
                    _telemetryState.value = _telemetryState.value.copy(isOnline = false)

                    // Auto-reconnect after backoff
                    if (!isStopped) {
                        Log.i(TAG, "Will re-scan in ${RECONNECT_DELAY_MS}ms...")
                        handler.postDelayed({ startScan() }, RECONNECT_DELAY_MS)
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed with status $status")
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "Telemetry service $SERVICE_UUID not found on PC!")
                return
            }

            val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
            if (characteristic == null) {
                Log.e(TAG, "Telemetry characteristic $CHARACTERISTIC_UUID not found!")
                return
            }

            // Step 1: Enable local notification routing in Android BLE stack
            val enabled = gatt.setCharacteristicNotification(characteristic, true)
            Log.i(TAG, "setCharacteristicNotification: $enabled")

            // Step 2: Write to the CCCD descriptor on the PC to start server-side notifications
            val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_DESCRIPTOR)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val written = gatt.writeDescriptor(descriptor)
                Log.i(TAG, "CCCD writeDescriptor: $written — subscribed to 1Hz telemetry!")
            } else {
                Log.w(TAG, "CCCD descriptor not found. Notifications may still work via local routing.")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == CLIENT_CONFIG_DESCRIPTOR) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "CCCD write confirmed. 1Hz notification stream is now active!")
                } else {
                    Log.e(TAG, "CCCD write failed with status $status")
                }
            }
        }

        // Called every ~1000ms when the PC pushes a new telemetry notification
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                val bytes = characteristic.value ?: return
                decodeTelemetry(bytes)
            }
        }
    }

    // ─── TELEMETRY DECODER ──────────────────────────────────────

    /**
     * Decodes 16-byte raw binary telemetry packet:
     * [0] gpuTemp, [1] cpuTemp, [2] gpuUsage, [3] cpuUsage
     * [4-5] vramUsedMb, [6-7] vramTotalMb
     * [8-9] ramUsedMb, [10-11] ramTotalMb
     * [12] gpuPowerW, [13] istHour, [14] istMinute, [15] istSecond
     */
    private fun decodeTelemetry(bytes: ByteArray) {
        if (bytes.size < 15) return
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val gpuTemp = buffer.get().toInt() and 0xFF
        val cpuTemp = buffer.get().toInt() and 0xFF
        val gpuUsage = buffer.get().toInt() and 0xFF
        val cpuUsage = buffer.get().toInt() and 0xFF

        val vramUsedMb = buffer.short.toInt() and 0xFFFF
        val vramTotalMb = buffer.short.toInt() and 0xFFFF
        val ramUsedMb = buffer.short.toInt() and 0xFFFF
        val ramTotalMb = buffer.short.toInt() and 0xFFFF

        val gpuPowerW = buffer.get().toInt() and 0xFF
        val istHour = buffer.get().toInt() and 0xFF
        val istMinute = buffer.get().toInt() and 0xFF
        val istSecond = if (buffer.hasRemaining()) buffer.get().toInt() and 0xFF else 0

        val vramPct = if (vramTotalMb > 0) ((vramUsedMb.toFloat() / vramTotalMb) * 100).toInt() else 0
        val ramPct = if (ramTotalMb > 0) ((ramUsedMb.toFloat() / ramTotalMb) * 100).toInt() else 0

        _telemetryState.value = HardwareTelemetry(
            gpuTemp = gpuTemp,
            cpuTemp = cpuTemp,
            gpuUsage = gpuUsage,
            cpuUsage = cpuUsage,
            vramUsedMb = vramUsedMb,
            vramTotalMb = vramTotalMb,
            vramPercent = vramPct,
            ramUsedMb = ramUsedMb,
            ramTotalMb = ramTotalMb,
            ramPercent = ramPct,
            gpuPowerW = gpuPowerW,
            istHour = istHour,
            istMinute = istMinute,
            istSecond = istSecond,
            isOnline = true,
            lastUpdateTimestamp = System.currentTimeMillis()
        )

        Log.i(TAG, "[1Hz GATT] %02d:%02d:%02d | GPU: %d°C (%d%%) | CPU: %d°C (%d%%)".format(
            istHour, istMinute, istSecond, gpuTemp, gpuUsage, cpuTemp, cpuUsage
        ))
    }
}
