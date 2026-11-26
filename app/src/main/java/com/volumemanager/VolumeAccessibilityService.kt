package com.volumemanager

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.volumemanager.overlay.FloatingButtonOverlay
import com.volumemanager.util.CrashReporter
import com.volumemanager.util.OverlayPrefs

/**
 * OS-bound AccessibilityService hosting the floating volume-slider trigger button
 * (`TYPE_ACCESSIBILITY_OVERLAY`) with zero persistent notifications and zero
 * window-content inspection.
 */
class VolumeAccessibilityService : AccessibilityService() {

    private var overlay: FloatingButtonOverlay? = null
    private var receiverRegistered = false

    // Strong reference required because SharedPreferences stores listeners weakly.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == OverlayPrefs.KEY_BUTTON_ENABLED ||
            key == OverlayPrefs.KEY_SIZE_DP ||
            key == OverlayPrefs.KEY_IDLE_OPACITY
        ) {
            overlay?.applyPreferences()
        }
    }

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            overlay?.onConfigurationChanged()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
        onConnectionChanged?.invoke(true)
        CrashReporter.logEvent(this, "ACC", "Accessibility service connected")

        overlay = FloatingButtonOverlay(this).apply {
            applyPreferences()
        }
        OverlayPrefs.registerListener(this, prefListener)
        registerConfigReceiver()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No window or event tracking needed; service exists solely for TYPE_ACCESSIBILITY_OVERLAY.
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun registerConfigReceiver() {
        if (receiverRegistered) return
        try {
            val filter = IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(configReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(configReceiver, filter)
            }
            receiverRegistered = true
        } catch (e: Throwable) {
            CrashReporter.recordError(this, "VolumeAccessibilityService.registerConfigReceiver", e)
        }
    }

    private fun cleanup() {
        if (!isConnected && overlay == null) return
        isConnected = false
        onConnectionChanged?.invoke(false)
        OverlayPrefs.unregisterListener(this, prefListener)
        if (receiverRegistered) {
            receiverRegistered = false
            try {
                unregisterReceiver(configReceiver)
            } catch (_: Exception) {}
        }
        overlay?.destroy()
        overlay = null
        CrashReporter.logEvent(this, "ACC", "Accessibility service disconnected")
    }

    companion object {
        @Volatile
        var isConnected: Boolean = false
            private set

        @Volatile
        var onConnectionChanged: ((Boolean) -> Unit)? = null
    }
}
