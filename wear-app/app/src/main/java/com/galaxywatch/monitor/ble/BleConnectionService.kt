package com.galaxywatch.monitor.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.galaxywatch.monitor.data.HardwareTelemetry
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground Service (type: connectedDevice) that keeps the BLE GATT
 * connection alive even when the screen is off or the Activity is paused.
 *
 * Holds a PARTIAL_WAKE_LOCK so the CPU stays awake to process incoming
 * GATT notifications without requiring the AMOLED display to stay on.
 *
 * Lifecycle:
 *   - Started by ChargerReceiver (ACTION_POWER_CONNECTED) or MainActivity
 *   - Runs until explicitly stopped or the watch is taken off the charger
 */
class BleConnectionService : Service() {

    companion object {
        private const val TAG = "BleConnectionService"
        private const val CHANNEL_ID = "ble_telemetry_channel"
        private const val NOTIFICATION_ID = 991
        const val ACTION_STOP = "com.galaxywatch.monitor.STOP_BLE_SERVICE"

        // Shared BleClientManager instance accessible by MainActivity for UI binding
        var bleManager: BleClientManager? = null
            private set
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Received STOP action. Shutting down.")
            stopSelf()
            return START_NOT_STICKY
        }

        // 1. Elevate to Foreground Service immediately
        val notification = buildNotification("Connecting to PC...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "Foreground service started (connectedDevice)")

        // 2. Acquire CPU WakeLock so BLE callbacks fire even with screen off
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "BLEWatch::TelemetryWakeLock"
            ).apply {
                acquire()
            }
            Log.i(TAG, "PARTIAL_WAKE_LOCK acquired")
        }

        // 3. Initialize BLE client and start connection
        if (bleManager == null) {
            bleManager = BleClientManager(applicationContext)
        }
        bleManager?.start()
        Log.i(TAG, "BLE GATT client started — scanning for PC server")

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroying — cleaning up BLE and WakeLock")
        bleManager?.stop()
        bleManager = null

        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "WakeLock released")
            }
        }
        wakeLock = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PC Hardware Monitor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BLE Telemetry Stream",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification for BLE telemetry connection to PC"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }
}
