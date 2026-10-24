package com.galaxywatch.monitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.galaxywatch.monitor.ble.BleClientManager
import com.galaxywatch.monitor.ui.DualArcHudScreen

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: BleClientManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            bleManager.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen awake in full color while resting on the desk charger
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        bleManager = BleClientManager(applicationContext)

        checkAndRequestPermissions()

        setContent {
            val telemetry by bleManager.telemetryState.collectAsState()
            DualArcHudScreen(telemetry = telemetry)
        }
    }

    private fun checkAndRequestPermissions() {
        val required = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                required.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                required.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        if (required.isEmpty()) {
            bleManager.start()
        } else {
            permissionLauncher.launch(required.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        bleManager.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.stop()
    }
}
