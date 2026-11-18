package com.volumemanager.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

/**
 * Deep links into Realme UI (ColorOS-based) battery / startup screens.
 *
 * Realme UI 1.0/2.0 on the RMX2001 ships a security suite whose component names
 * changed between builds. We therefore keep a ranked list of candidate
 * components and launch the first one that actually resolves on the device,
 * falling back to a stock Android settings screen. Without "Auto-launch"
 * enabled, Realme UI silently drops BOOT_COMPLETED, so this screen is vital.
 */
object RealmeSettings {

    private fun componentIntent(packageName: String, className: String): Intent =
        Intent().setComponent(ComponentName(packageName, className))

    private fun appDetailsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )

    private fun launchFirst(context: Context, candidates: List<Intent>) {
        for (intent in candidates) {
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return
                }
            } catch (_: Exception) {
                // Try the next candidate.
            }
        }
        try {
            val fallback = appDetailsIntent(context)
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallback)
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open settings: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Settings -> App Management -> Auto-launch apps. */
    fun openAutoLaunch(context: Context) {
        launchFirst(
            context,
            listOf(
                componentIntent("com.realmecolor.safecenter", "com.realmecolor.safecenter.startupapp.StartupAppListActivity"),
                componentIntent("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                componentIntent("com.realmecolor.safecenter", "com.realmecolor.safecenter.startupapp.AutoLaunchManagerActivity"),
                componentIntent("com.coloros.safecenter", "com.coloros.safecenter.startupapp.AutoLaunchManagerActivity"),
                componentIntent("com.realmecolor.safecenter", "com.realmecolor.safecenter.startupapp.AppStartupManagerActivity"),
            ),
        )
    }

    /** Settings -> Battery -> App Battery Management -> [this app]. */
    fun openAppBatteryManagement(context: Context) {
        launchFirst(
            context,
            listOf(
                componentIntent("com.realmecolor.safecenter", "com.realmecolor.safecenter.battery.BatteryActivity"),
                componentIntent("com.coloros.safecenter", "com.coloros.safecenter.battery.BatteryActivity"),
                componentIntent("com.realmecolor.safecenter", "com.realmecolor.safecenter.battery.AppBatteryManagerActivity"),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            ),
        )
    }

    /** Settings -> App Management -> Display over other apps. */
    fun openDisplayOverOtherApps(context: Context) {
        try {
            val intent = Permissions.overlaySettingsIntent(context)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            launchFirst(context, listOf(appDetailsIntent(context)))
        }
    }
}
