package com.galaxywatch.monitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.galaxywatch.monitor.MainActivity

/**
 * BroadcastReceiver that auto-launches the Hardware Monitor
 * as soon as the Galaxy Watch is placed on its desk magnetic charger.
 */
class ChargerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_POWER_CONNECTED) {
            Log.i("ChargerReceiver", "Galaxy Watch docked on charger puck. Auto-launching monitor...")
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(launchIntent)
        }
    }
}
