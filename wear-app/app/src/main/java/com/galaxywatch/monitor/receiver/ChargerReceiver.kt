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
 * when the Galaxy Watch is placed on its magnetic charger puck
 * or when the watch restarts / boots up.
 *
 * Starts the BLE Foreground Service first (for OS process priority),
 * then brings the full-color Dashboard Activity into focus.
 */
class ChargerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val isBoot = action == Intent.ACTION_BOOT_COMPLETED ||
                     action == "android.intent.action.QUICKBOOT_POWERON" ||
                     action == "com.htc.intent.action.QUICKBOOT_POWERON"
        val isCharger = action == Intent.ACTION_POWER_CONNECTED

        if (isBoot || isCharger) {
            val reason = if (isBoot) "Watch rebooted / boot completed" else "Watch docked on charger puck"
            Log.i("ChargerReceiver", "$reason. Starting BLE service & bringing monitor to focus...")

            // 1. Start the Foreground Service for BLE connection resilience
            val serviceIntent = Intent(context, BleConnectionService::class.java)
            try {
                ContextCompat.startForegroundService(context, serviceIntent)
            } catch (e: Exception) {
                Log.e("ChargerReceiver", "Failed to start BleConnectionService: ${e.message}")
            }

            // 2. Launch the Dashboard Activity directly into focus
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            try {
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                Log.e("ChargerReceiver", "Failed to start MainActivity directly: ${e.message}")
            }
        }
    }
}
