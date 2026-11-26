package com.volumemanager.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.volumemanager.VolumeAccessibilityService

/**
 * Checks Accessibility service enablement and battery-optimization exemption,
 * and constructs system settings intents without requiring overlay/notification permissions.
 */
object Permissions {

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        if (VolumeAccessibilityService.isConnected) return true

        val expected = ComponentName(context, VolumeAccessibilityService::class.java)
        try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val enabledList = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            if (enabledList != null) {
                for (info in enabledList) {
                    val si = info.resolveInfo?.serviceInfo ?: continue
                    if (si.packageName == expected.packageName && si.name == expected.className) {
                        return true
                    }
                }
            }
        } catch (_: Throwable) {}

        return try {
            val enabledRaw = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            enabledRaw.split(':').any { entry ->
                ComponentName.unflattenFromString(entry) == expected
            }
        } catch (_: Throwable) {
            false
        }
    }

    @SuppressLint("BatteryLife")
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Opens Android Accessibility settings, pre-highlighting Volume Manager when supported. */
    fun accessibilitySettingsIntent(context: Context): Intent {
        val component = ComponentName(context, VolumeAccessibilityService::class.java).flattenToString()
        val args = Bundle().apply {
            putString(":settings:fragment_args_key", component)
        }
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", component)
            putExtra(":settings:show_fragment_args", args)
        }
    }

    /** Opens the system "Ignore battery optimizations" prompt for this package. */
    @SuppressLint("BatteryLife")
    fun batteryOptimizationIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
}
