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
 */
@SuppressLint("MissingPermission")
class BleClientManager(private val context: Context) {

    companion object {
        private const val TAG = "BleClientManager"

        val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val CLIENT_CONFIG_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val STALE_TIMEOUT_MS = 4500L
        private const val SCAN_TIMEOUT_MS = 30000L
        private const val RECONNECT_DELAY_MS = 2500L
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private var isStopped = false
    private var connectionAttemptTimestamp = 0L

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
                    val offline = _telemetryState.value.copy(isOnline = false)
                    _telemetryState.value = offline
                    BleConnectionService.updateTelemetry(offline)
                }

                // If connection is dead/zombie, only clean it up if it has been trying for > 8s without packets
                if (bluetoothGatt != null && (now - connectionAttemptTimestamp > 8000L)) {
                    Log.w(TAG, "Cleaning up zombie GATT connection...")
                    try {
                        bluetoothGatt?.disconnect()
                        bluetoothGatt?.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing zombie GATT: ${e.message}")
                    }
                    bluetoothGatt = null
                }

                if (bluetoothGatt == null && !isScanning) {
                    Log.i(TAG, "Restarting scan for PC server...")
                    startScan()
                }
            }
            handler.postDelayed(this, 2000)
        }
    }

    private val scanTimeoutRunnable = Runnable {
        if (isScanning) {
            Log.w(TAG, "Scan timeout reached (${SCAN_TIMEOUT_MS}ms). Pausing scan before retry.")
            stopScan()
            handler.postDelayed({ startScan() }, RECONNECT_DELAY_MS)
        }
    }

    fun start() {
        isStopped = false
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth not available or disabled")
            return
        }

        // Clean up any lingering dead GATT handle from previous sessions
        if (bluetoothGatt != null && !_telemetryState.value.isOnline) {
            try {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
            } catch (e: Exception) {}
            bluetoothGatt = null
        }

        handler.removeCallbacks(livenessWatchdog)
        handler.post(livenessWatchdog)
        startScan()
    }

    fun stop() {
        isStopped = true
        handler.removeCallbacks(livenessWatchdog)
        handler.removeCallbacks(scanTimeoutRunnable)
        stopScan()
        bluetoothGatt?.let { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: Exception) {}
        }
        bluetoothGatt = null
    }

    // ─── SCANNING ───────────────────────────────────────────────

    private fun startScan() {
        if (isStopped || isScanning || bluetoothGatt != null) return
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return

        isScanning = true
        Log.i(TAG, "Scanning for PC GATT Server (Service UUID: $SERVICE_UUID)...")

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .setReportDelay(0)
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)
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
            stopScan()
            connectToDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error code $errorCode")
            isScanning = false
            handler.removeCallbacks(scanTimeoutRunnable)
            if (!isStopped) {
                handler.postDelayed({ startScan() }, RECONNECT_DELAY_MS)
            }
        }
    }

    // ─── GATT CONNECTION ────────────────────────────────────────

    private fun connectToDevice(device: BluetoothDevice) {
        Log.i(TAG, "Connecting to PC at ${device.address} via TRANSPORT_LE...")
        connectionAttemptTimestamp = System.currentTimeMillis()
        bluetoothGatt = device.connectGatt(
            context,
            false,
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
                    try {
                        gatt.close()
                    } catch (e: Exception) {}
                    bluetoothGatt = null
                    val offline = _telemetryState.value.copy(isOnline = false)
                    _telemetryState.value = offline
                    BleConnectionService.updateTelemetry(offline)

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

            val enabled = gatt.setCharacteristicNotification(characteristic, true)
            Log.i(TAG, "setCharacteristicNotification: $enabled")

            val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_DESCRIPTOR)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val written = gatt.writeDescriptor(descriptor)
                Log.i(TAG, "CCCD writeDescriptor: $written — subscribed to 1Hz telemetry!")
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

        val telemetry = HardwareTelemetry(
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

        _telemetryState.value = telemetry
        BleConnectionService.updateTelemetry(telemetry)

        Log.i(TAG, "[1Hz GATT] %02d:%02d:%02d | GPU: %d°C (%d%%) | CPU: %d°C (%d%%)".format(
            istHour, istMinute, istSecond, gpuTemp, gpuUsage, cpuTemp, cpuUsage
        ))
    }
}
