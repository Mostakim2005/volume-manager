package com.volumemanager.util

import android.content.Context
import android.media.AudioManager

/**
 * Summons Realme UI / Android's native system volume dialog on demand.
 */
object SystemVolumeHelper {

    fun showSystemVolumeUi(context: Context): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager == null) {
                CrashReporter.logEvent(context, "UI", "AudioManager unavailable")
                false
            } else {
                audioManager.adjustVolume(AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
                CrashReporter.logEvent(context, "UI", "Opened native system volume slider")
                true
            }
        } catch (e: Throwable) {
            CrashReporter.recordError(context, "SystemVolumeHelper.showSystemVolumeUi", e)
            false
        }
    }
}
