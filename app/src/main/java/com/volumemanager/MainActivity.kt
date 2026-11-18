package com.volumemanager

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
        try {
            updateStatus()
        } catch (_: Throwable) {}
    }

    private fun startOverlayService() {
        try {
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
        } catch (e: Throwable) {
            Toast.makeText(this, "Could not start overlay: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopOverlayService() {
        try {
            stopService(Intent(this, OverlayVolumeService::class.java))
            Toast.makeText(this, "Volume overlay stopped", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "Could not stop overlay: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatus() {
        val hasOverlay = Permissions.canDrawOverlays(this)
        val hasNotif = Permissions.hasNotificationPermission(this)
        val isBatteryExempt = Permissions.isIgnoringBatteryOptimizations(this)

        if (hasOverlay) {
            binding.btnOverlay.text = "GRANTED ✓"
            binding.btnOverlay.setTextColor(ContextCompat.getColor(this, R.color.status_pill_ok_text))
        } else {
            binding.btnOverlay.text = "SETUP ➔"
            binding.btnOverlay.setTextColor(ContextCompat.getColor(this, R.color.primary))
        }

        if (hasNotif) {
            binding.btnNotifications.text = "ENABLED ✓"
            binding.btnNotifications.setTextColor(ContextCompat.getColor(this, R.color.status_pill_ok_text))
        } else {
            binding.btnNotifications.text = "SETUP ➔"
            binding.btnNotifications.setTextColor(ContextCompat.getColor(this, R.color.primary))
        }

        if (isBatteryExempt) {
            binding.btnBatteryOptimization.text = "EXEMPT ✓"
            binding.btnBatteryOptimization.setTextColor(ContextCompat.getColor(this, R.color.status_pill_ok_text))
        } else {
            binding.btnBatteryOptimization.text = "SETUP ➔"
            binding.btnBatteryOptimization.setTextColor(ContextCompat.getColor(this, R.color.primary))
        }

        val isReady = hasOverlay && hasNotif
        if (isReady) {
            binding.statusPillService.text = getString(R.string.status_running)
            binding.statusPillService.setTextColor(ContextCompat.getColor(this, R.color.status_pill_ok_text))
        } else {
            binding.statusPillService.text = "Setup Required"
            binding.statusPillService.setTextColor(ContextCompat.getColor(this, R.color.status_pill_warn_text))
        }

        fun mark(ok: Boolean) = if (ok) "ON" else "OFF"
        binding.statusText.text = listOf(
            "Display over other apps: ${mark(hasOverlay)}",
            "Notifications: ${mark(hasNotif)}",
            "Battery optimization ignored: ${mark(isBatteryExempt)}",
        ).joinToString(separator = "\n")
    }
}
