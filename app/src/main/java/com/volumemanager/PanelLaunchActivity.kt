package com.volumemanager

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * Zero-UI transparent trampolineless activity used by the notification Panel button.
 * Launching an activity natively collapses the notification shade on Android 10–15
 * without deprecated/restricted ACTION_CLOSE_SYSTEM_DIALOGS broadcasts, then asks
 * OverlayVolumeService to open the floating panel once the shade clears.
 */
class PanelLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        suppressTransition(isOpen = true)

        val serviceIntent = Intent(this, OverlayVolumeService::class.java)
            .setAction(OverlayVolumeService.ACTION_OPEN_PANEL)
        try {
            if (OverlayVolumeService.isRunning || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                startService(serviceIntent)
            } else {
                startForegroundService(serviceIntent)
            }
        } catch (_: Throwable) {}

        finish()
        suppressTransition(isOpen = false)
    }

    private fun suppressTransition(isOpen: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = if (isOpen) OVERRIDE_TRANSITION_OPEN else OVERRIDE_TRANSITION_CLOSE
            overrideActivityTransition(type, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
