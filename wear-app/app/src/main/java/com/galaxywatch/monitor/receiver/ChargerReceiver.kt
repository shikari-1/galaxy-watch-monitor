package com.galaxywatch.monitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.galaxywatch.monitor.MainActivity
import com.galaxywatch.monitor.ble.BleConnectionService

/**
 * BroadcastReceiver that auto-launches the Hardware Monitor
 * as soon as the Galaxy Watch is placed on its desk magnetic charger.
 *
 * Starts the BLE Foreground Service first (for OS process priority),
 * then launches the full-color Dashboard Activity.
 */
class ChargerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_POWER_CONNECTED) {
            Log.i("ChargerReceiver", "Galaxy Watch docked on charger puck. Starting BLE service & monitor...")

            // 1. Start the Foreground Service for BLE connection resilience
            val serviceIntent = Intent(context, BleConnectionService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)

            // 2. Launch the Dashboard Activity
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(launchIntent)
        }
    }
}
