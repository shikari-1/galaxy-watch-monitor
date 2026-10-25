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
 * High-performance Bluetooth Low Energy (BLE) Client Manager.
 * Scans for the PC GATT Server and decodes the 16-byte 1Hz binary telemetry packet.
 */
@SuppressLint("MissingPermission")
class BleClientManager(private val context: Context) {

    companion object {
        private const val TAG = "BleClientManager"

        // Custom 128-bit UUID for PC Hardware Monitor Telemetry Service
        val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val CLIENT_CONFIG_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val STALE_TIMEOUT_MS = 2500L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false

    private val _telemetryState = MutableStateFlow(HardwareTelemetry())
    val telemetryState: StateFlow<HardwareTelemetry> = _telemetryState.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())

    // Watchdog to mark telemetry offline if BLE packet stops arriving
    private val livenessWatchdog = object : Runnable {
        override fun run() {
            val lastUpdate = _telemetryState.value.lastUpdateTimestamp
            if (lastUpdate > 0 && (System.currentTimeMillis() - lastUpdate > STALE_TIMEOUT_MS)) {
                if (_telemetryState.value.isOnline) {
                    _telemetryState.value = _telemetryState.value.copy(isOnline = false)
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    init {
        handler.post(livenessWatchdog)
    }

    fun start() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth not available or disabled")
            return
        }
        startScan()
    }

    fun stop() {
        stopScan()
        bluetoothGatt?.close()
        bluetoothGatt = null
        handler.removeCallbacks(livenessWatchdog)
    }

    private fun startScan() {
        if (isScanning || bluetoothGatt != null) return
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return

        isScanning = true
        Log.i(TAG, "Scanning for PC Hardware Monitor BLE Service...")

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    private fun stopScan() {
        if (!isScanning) return
        val scanner = bluetoothAdapter.bluetoothLeScanner
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping BLE scan: ${e.message}")
        }
        isScanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            // 1. Direct Service Data Reception
            val serviceData = record?.getServiceData(ParcelUuid(SERVICE_UUID))
            if (serviceData != null && serviceData.size >= 15) {
                decodeTelemetry(serviceData)
                return
            }
            // 2. Direct Manufacturer Data Reception
            val mfgData = record?.manufacturerSpecificData
            if (mfgData != null && mfgData.size() > 0) {
                for (i in 0 until mfgData.size()) {
                    val bytes = mfgData.valueAt(i)
                    if (bytes != null && bytes.size >= 15) {
                        decodeTelemetry(bytes)
                        return
                    }
                }
            }

            // 3. Fallback: Connect via GATT
            val device = result.device
            Log.i(TAG, "Discovered PC BLE Host: ${device.name ?: device.address}")
            stopScan()
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error code $errorCode")
            isScanning = false
            handler.postDelayed({ startScan() }, 3000)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to PC BLE Server. Discovering services...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Disconnected from PC. Re-scanning...")
                gatt.close()
                bluetoothGatt = null
                _telemetryState.value = _telemetryState.value.copy(isOnline = false)
                handler.postDelayed({ startScan() }, 2000)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                if (characteristic != null) {
                    gatt.setCharacteristicNotification(characteristic, true)
                    val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_DESCRIPTOR)
                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                        Log.i(TAG, "Subscribed to 1Hz telemetry notifications!")
                    }
                }
            }
        }

        @Deprecated("Deprecated in Java")
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

    /**
     * Decodes 16-byte raw binary telemetry packet:
     * [0] gpuTemp, [1] cpuTemp, [2] gpuUsage, [3] cpuUsage
     * [4-5] vramUsedMb, [6-7] vramTotalMb
     * [8-9] ramUsedMb, [10-11] ramTotalMb
     * [12] gpuPowerW, [13] istHour, [14] istMinute, [15] flags
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
            isOnline = true,
            lastUpdateTimestamp = System.currentTimeMillis()
        )
    }
}
