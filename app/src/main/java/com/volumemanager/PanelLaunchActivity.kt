package com.volumemanager

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.volumemanager.util.SystemVolumeHelper

/**
 * Zero-UI transparent trampolineless activity used by the Quick Settings tile
 * to collapse the shade natively before opening Realme UI's native system volume dialog.
 */
class PanelLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        suppressTransition(isOpen = true)

        val appCtx = applicationContext
        Handler(Looper.getMainLooper()).postDelayed(
            { SystemVolumeHelper.showSystemVolumeUi(appCtx) },
            SHADE_COLLAPSE_DELAY_MS,
        )

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

    private companion object {
        const val SHADE_COLLAPSE_DELAY_MS = 180L
    }
}
