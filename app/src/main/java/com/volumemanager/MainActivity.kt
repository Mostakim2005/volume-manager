package com.volumemanager

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
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
        binding.btnDndAccess.setOnClickListener {
            try {
                startActivity(Permissions.notificationPolicyAccessIntent())
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open DND settings: ${e.message}", Toast.LENGTH_SHORT).show()
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

    override fun onStart() {
        super.onStart()
        OverlayVolumeService.onRunningStateChanged = {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    updateStatus()
                }
            }
        }
    }

    override fun onStop() {
        OverlayVolumeService.onRunningStateChanged = null
        super.onStop()
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
            binding.root.postDelayed({
                if (!isFinishing && !isDestroyed) updateStatus()
            }, 250)
            Toast.makeText(this, "Volume overlay started", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "Could not start overlay: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopOverlayService() {
        try {
            stopService(Intent(this, OverlayVolumeService::class.java))
            binding.root.postDelayed({
                if (!isFinishing && !isDestroyed) updateStatus()
            }, 250)
            Toast.makeText(this, "Volume overlay stopped", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "Could not stop overlay: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatus() {
        val hasOverlay = Permissions.canDrawOverlays(this)
        val hasNotif = Permissions.hasNotificationPermission(this)
        val isBatteryExempt = Permissions.isIgnoringBatteryOptimizations(this)
        val hasDndAccess = Permissions.hasNotificationPolicyAccess(this)

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

        if (hasDndAccess) {
            binding.btnDndAccess.text = "GRANTED ✓"
            binding.btnDndAccess.setTextColor(ContextCompat.getColor(this, R.color.status_pill_ok_text))
        } else {
            binding.btnDndAccess.text = "OPTIONAL ➔"
            binding.btnDndAccess.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        when {
            OverlayVolumeService.isRunning -> {
                binding.statusPillService.text = getString(R.string.status_running)
                binding.statusPillService.setTextColor(ContextCompat.getColor(this, R.color.status_pill_ok_text))
                ViewCompat.setBackgroundTintList(
                    binding.statusPillService,
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_pill_ok_bg)),
                )
            }
            hasOverlay && hasNotif -> {
                binding.statusPillService.text = getString(R.string.status_stopped)
                binding.statusPillService.setTextColor(ContextCompat.getColor(this, R.color.status_pill_warn_text))
                ViewCompat.setBackgroundTintList(
                    binding.statusPillService,
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_pill_warn_bg)),
                )
            }
            else -> {
                binding.statusPillService.text = getString(R.string.status_setup_required)
                binding.statusPillService.setTextColor(ContextCompat.getColor(this, R.color.status_pill_warn_text))
                ViewCompat.setBackgroundTintList(
                    binding.statusPillService,
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_pill_warn_bg)),
                )
            }
        }

        fun mark(ok: Boolean) = if (ok) "ON" else "OFF"
        binding.statusText.text = listOf(
            "Display over other apps: ${mark(hasOverlay)}",
            "Notifications: ${mark(hasNotif)}",
            "Battery optimization ignored: ${mark(isBatteryExempt)}",
            "DND policy access: ${mark(hasDndAccess)}",
        ).joinToString(separator = "\n")
    }
}
