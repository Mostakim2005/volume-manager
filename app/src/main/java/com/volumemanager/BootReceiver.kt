package com.volumemanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log

/**
 * Restarts the overlay utility after a reboot or app update.
 *
 * BOOT_COMPLETED fires after user unlock, which starts the service directly.
 * LOCKED_BOOT_COMPLETED only starts if the user is already unlocked; otherwise,
 * it safely waits for the standard BOOT_COMPLETED broadcast delivered by the OS.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            QUICKBOOT_POWERON,
            Intent.ACTION_MY_PACKAGE_REPLACED -> startSafely(context)

            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                if (isUserUnlocked(context)) {
                    startSafely(context)
                }
            }
        }
    }

    private fun isUserUnlocked(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
            userManager?.isUserUnlocked ?: true
        } else {
            true
        }

    private fun startSafely(context: Context) {
        try {
            val serviceIntent = Intent(context, OverlayVolumeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "Overlay service start requested from boot receiver")
        } catch (e: Exception) {
            Log.w(TAG, "Could not start service from boot receiver: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
        const val QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
