package com.volumemanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import android.util.Log

/**
 * Restarts the overlay utility after a reboot (or an app update).
 *
 * BOOT_COMPLETED fires after unlock, so that path starts the service directly.
 * LOCKED_BOOT_COMPLETED fires before unlock; because the overlay cannot draw
 * over the lock screen and the service is not directBootAware, we defer the
 * start until ACTION_USER_UNLOCKED instead of trying to launch it too early.
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
                } else {
                    waitForUnlock(context)
                }
            }
        }
    }

    /** Registers a one-shot receiver in device-protected storage. */
    private fun waitForUnlock(context: Context) {
        val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        val unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                try {
                    c.unregisterReceiver(this)
                } catch (_: Exception) {
                    // Already unregistered.
                }
                startSafely(c)
            }
        }
        try {
            val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                storageContext.registerReceiver(unlockReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                storageContext.registerReceiver(unlockReceiver, filter)
            }
            Log.d(TAG, "Waiting for ACTION_USER_UNLOCKED before starting overlay")
        } catch (e: Exception) {
            Log.w(TAG, "Could not register unlock receiver: ${e.message}")
        }
    }

    private fun isUserUnlocked(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
            userManager.isUserUnlocked
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
            Log.d(TAG, "Overlay service start requested")
        } catch (e: Exception) {
            // Some OEM builds reject background starts; BOOT_COMPLETED usually
            // arrives later and retries, so this is not fatal.
            Log.w(TAG, "Could not start service from boot receiver: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
        const val QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
