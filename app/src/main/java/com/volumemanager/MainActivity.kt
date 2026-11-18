package com.volumemanager

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.volumemanager.databinding.ActivityMainBinding
import com.volumemanager.util.Permissions
import com.volumemanager.util.RealmeSettings

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Notifications are needed for the foreground service", Toast.LENGTH_SHORT).show()
        }
        updateStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStart.setOnClickListener { startOverlayService() }
        binding.btnStop.setOnClickListener { stopOverlayService() }

        binding.btnOverlay.setOnClickListener { RealmeSettings.openDisplayOverOtherApps(this) }
        binding.btnAutoLaunch.setOnClickListener { RealmeSettings.openAutoLaunch(this) }
        binding.btnBatteryOptimization.setOnClickListener {
            try {
                startActivity(Permissions.batteryOptimizationIntent(this))
            } catch (e: Exception) {
                RealmeSettings.openAppBatteryManagement(this)
            }
        }
        binding.btnBatteryManagement.setOnClickListener { RealmeSettings.openAppBatteryManagement(this) }
        binding.btnNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Toast.makeText(this, "Not needed on Android 12 and below", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun startOverlayService() {
        if (!Permissions.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant 'Display over other apps' first", Toast.LENGTH_LONG).show()
            RealmeSettings.openDisplayOverOtherApps(this)
            return
        }
        if (!Permissions.hasNotificationPermission(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val intent = Intent(this, OverlayVolumeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Volume overlay started", Toast.LENGTH_SHORT).show()
    }

    private fun stopOverlayService() {
        stopService(Intent(this, OverlayVolumeService::class.java))
        Toast.makeText(this, "Volume overlay stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateStatus() {
        fun mark(ok: Boolean) = if (ok) "ON" else "OFF"
        binding.statusText.text = listOf(
            "Display over other apps: ${mark(Permissions.canDrawOverlays(this))}",
            "Notifications: ${mark(Permissions.hasNotificationPermission(this))}",
            "Battery optimization ignored: ${mark(Permissions.isIgnoringBatteryOptimizations(this))}",
        ).joinToString(separator = "\n")
    }
}
