package com.volumemanager.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Shared preferences for the ultra-lightweight floating volume launcher button:
 * visibility toggle, squircle size, idle opacity, and last docked edge position.
 */
object OverlayPrefs {

    private const val PREFS_NAME = "volume_manager_prefs"

    const val KEY_BUTTON_ENABLED = "button_enabled"
    const val KEY_SIZE_DP = "size_dp"
    const val KEY_IDLE_OPACITY = "idle_opacity_percent"
    private const val KEY_DOCK_RIGHT = "dock_right"
    private const val KEY_DOCK_Y_FRACTION = "dock_y_fraction"

    const val SIZE_COMPACT_DP = 36
    const val SIZE_STANDARD_DP = 48
    const val SIZE_LARGE_DP = 56

    const val MIN_IDLE_OPACITY = 15
    const val MAX_IDLE_OPACITY = 100
    const val DEFAULT_IDLE_OPACITY = 35
    private const val DEFAULT_DOCK_Y_FRACTION = 0.38f

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isButtonEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BUTTON_ENABLED, true)

    fun setButtonEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BUTTON_ENABLED, enabled).apply()
    }

    fun getSizeDp(context: Context): Int =
        prefs(context).getInt(KEY_SIZE_DP, SIZE_STANDARD_DP)
            .coerceIn(SIZE_COMPACT_DP, SIZE_LARGE_DP)

    fun setSizeDp(context: Context, sizeDp: Int) {
        prefs(context).edit()
            .putInt(KEY_SIZE_DP, sizeDp.coerceIn(SIZE_COMPACT_DP, SIZE_LARGE_DP))
            .apply()
    }

    fun getIdleOpacityPercent(context: Context): Int =
        prefs(context).getInt(KEY_IDLE_OPACITY, DEFAULT_IDLE_OPACITY)
            .coerceIn(MIN_IDLE_OPACITY, MAX_IDLE_OPACITY)

    fun setIdleOpacityPercent(context: Context, percent: Int) {
        prefs(context).edit()
            .putInt(KEY_IDLE_OPACITY, percent.coerceIn(MIN_IDLE_OPACITY, MAX_IDLE_OPACITY))
            .apply()
    }

    fun isDockRight(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DOCK_RIGHT, false)

    fun getDockYFraction(context: Context): Float =
        prefs(context).getFloat(KEY_DOCK_Y_FRACTION, DEFAULT_DOCK_Y_FRACTION)
            .coerceIn(0f, 1f)

    fun saveDockPosition(context: Context, isRightEdge: Boolean, yFraction: Float) {
        prefs(context).edit()
            .putBoolean(KEY_DOCK_RIGHT, isRightEdge)
            .putFloat(KEY_DOCK_Y_FRACTION, yFraction.coerceIn(0f, 1f))
            .apply()
    }

    fun registerListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }
}
