package com.volumemanager

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.volumemanager.util.CrashReporter

/**
 * Zero-UI transparent trampolineless activity used to collapse the notification
 * or Quick Settings shade natively on Android 10–15 without restricted
 * ACTION_CLOSE_SYSTEM_DIALOGS broadcasts, then open either the floating panel
 * or Realme's native system volume dialog.
 */
class PanelLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        suppressTransition(isOpen = true)

        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_OVERLAY_PANEL
        if (mode == MODE_SYSTEM_VOLUME_UI) {
            val appCtx = applicationContext
            Handler(Looper.getMainLooper()).postDelayed({ showSystemVolumeUi(appCtx) }, 180)
        } else {
            val serviceIntent = Intent(this, OverlayVolumeService::class.java)
                .setAction(OverlayVolumeService.ACTION_OPEN_PANEL)
            try {
                if (OverlayVolumeService.isRunning || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    startService(serviceIntent)
                } else {
                    startForegroundService(serviceIntent)
                }
            } catch (e: Throwable) {
                CrashReporter.recordError(this, "PanelLaunchActivity.startService", e)
            }
        }

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

    companion object {
        const val EXTRA_MODE = "com.volumemanager.LAUNCH_MODE"
        const val MODE_OVERLAY_PANEL = "overlay_panel"
        const val MODE_SYSTEM_VOLUME_UI = "system_volume_ui"

        fun showSystemVolumeUi(context: Context) {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioManager?.adjustVolume(AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
                CrashReporter.logEvent(context, "UI", "Opened native system volume slider")
            } catch (e: Throwable) {
                CrashReporter.recordError(context, "PanelLaunchActivity.showSystemVolumeUi", e)
            }
        }
    }
}
