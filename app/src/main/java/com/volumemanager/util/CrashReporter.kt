package com.volumemanager.util

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.volumemanager.VolumeAccessibilityService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 100% offline crash and diagnostic event recorder stored in app-private filesDir.
 * Never touches network or external storage; surfaces stack traces and live state
 * inside MainActivity so device-specific Realme UI issues can be diagnosed.
 */
object CrashReporter {

    private const val CRASH_FILE = "last_crash.txt"
    private const val LOG_FILE = "diagnostics.log"
    private const val MAX_LOG_LINES = 60

    private val lock = Any()

    @Volatile
    private var installed = false

    fun install(app: Application) {
        if (installed) return
        installed = true
        val appContext = app.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                synchronized(lock) {
                    recordCrashInternal(appContext, "Uncaught on thread '${thread.name}'", throwable)
                }
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, throwable)
        }
        logEvent(
            appContext,
            "APP",
            "Process started (SDK ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL})",
        )
    }

    fun logEvent(context: Context, tag: String, message: String) {
        try {
            val appCtx = context.applicationContext
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val entry = "[$ts] $tag: $message"
            synchronized(lock) {
                appendLogLineLocked(appCtx, entry)
            }
        } catch (_: Throwable) {}
    }

    fun recordError(context: Context, location: String, throwable: Throwable) {
        try {
            val appCtx = context.applicationContext
            synchronized(lock) {
                recordCrashInternal(appCtx, location, throwable)
            }
        } catch (_: Throwable) {}
    }

    fun readCrashSummary(context: Context): String? = try {
        synchronized(lock) {
            val file = File(context.applicationContext.filesDir, CRASH_FILE)
            if (!file.exists()) return null
            file.readText().trim().takeIf { it.isNotEmpty() }
        }
    } catch (_: Throwable) {
        null
    }

    fun clearCrashReport(context: Context) {
        try {
            synchronized(lock) {
                val file = File(context.applicationContext.filesDir, CRASH_FILE)
                if (file.exists()) file.delete()
            }
        } catch (_: Throwable) {}
    }

    fun buildFullBugReport(context: Context): String {
        val appCtx = context.applicationContext
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        sb.appendLine("=== Volume Manager Bug Report ===")
        sb.appendLine("Generated: $now")
        sb.appendLine("App: ${appVersionLabel(appCtx)}")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine()

        sb.appendLine("--- Service & Preferences ---")
        sb.appendLine("Accessibility enabled: ${Permissions.isAccessibilityServiceEnabled(appCtx)}")
        sb.appendLine("Accessibility connected: ${VolumeAccessibilityService.isConnected}")
        sb.appendLine("Floating button visible toggle: ${OverlayPrefs.isButtonEnabled(appCtx)}")
        sb.appendLine("Button size: ${OverlayPrefs.getSizeDp(appCtx)}dp, Idle opacity: ${OverlayPrefs.getIdleOpacityPercent(appCtx)}%")
        sb.appendLine("Battery optimization exempt: ${Permissions.isIgnoringBatteryOptimizations(appCtx)}")
        sb.appendLine()

        sb.appendLine("--- Audio State ---")
        val audioManager = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            sb.appendLine("AudioManager unavailable")
        } else {
            sb.appendLine("Mode: ${audioModeName(audioManager.mode)}")
            sb.appendLine("Ringer: ${ringerModeName(audioManager.ringerMode)}")
            sb.appendLine("isMusicActive: ${audioManager.isMusicActive}")
            val streams = listOf(
                AudioManager.STREAM_MUSIC to "Media",
                AudioManager.STREAM_RING to "Ring",
                AudioManager.STREAM_NOTIFICATION to "Notify",
                AudioManager.STREAM_VOICE_CALL to "Call",
                AudioManager.STREAM_ALARM to "Alarm",
            )
            for ((stream, label) in streams) {
                val cur = runCatching { audioManager.getStreamVolume(stream) }.getOrDefault(-1)
                val max = runCatching { audioManager.getStreamMaxVolume(stream) }.getOrDefault(-1)
                sb.appendLine("  $label: $cur / $max")
            }
        }
        sb.appendLine()

        sb.appendLine("--- Last Crash Report ---")
        sb.appendLine(readCrashSummary(appCtx) ?: "None recorded")
        sb.appendLine()

        sb.appendLine("--- Recent Events ---")
        sb.appendLine(readDiagnosticsLog(appCtx) ?: "No events recorded")
        return sb.toString().trimEnd()
    }

    private fun recordCrashInternal(appContext: Context, location: String, throwable: Throwable) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val body = buildString {
            appendLine("Time: $ts")
            appendLine("Location: $location")
            appendLine("App: ${appVersionLabel(appContext)}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
            appendLine()
            append(Log.getStackTraceString(throwable))
        }
        File(appContext.filesDir, CRASH_FILE).writeText(body)

        val shortTs = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val summary = "[$shortTs] ERROR [$location]: ${throwable::class.java.simpleName}: ${throwable.message}"
        appendLogLineLocked(appContext, summary)
    }

    private fun appendLogLineLocked(appContext: Context, line: String) {
        val file = File(appContext.filesDir, LOG_FILE)
        val lines = if (file.exists()) file.readLines().toMutableList() else mutableListOf()
        lines.add(line)
        val trimmed = if (lines.size > MAX_LOG_LINES) lines.takeLast(MAX_LOG_LINES) else lines
        file.writeText(trimmed.joinToString("\n") + "\n")
    }

    private fun readDiagnosticsLog(appContext: Context): String? = try {
        synchronized(lock) {
            val file = File(appContext.filesDir, LOG_FILE)
            if (!file.exists()) return null
            file.readText().trim().takeIf { it.isNotEmpty() }
        }
    } catch (_: Throwable) {
        null
    }

    @Suppress("DEPRECATION")
    private fun appVersionLabel(context: Context): String = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getPackageInfo(context.packageName, 0)
        }
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        "${info.versionName ?: "?"} ($code)"
    } catch (_: Throwable) {
        "unknown"
    }

    private fun audioModeName(mode: Int): String = when {
        mode == AudioManager.MODE_NORMAL -> "MODE_NORMAL"
        mode == AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
        mode == AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
        mode == AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mode == AudioManager.MODE_CALL_SCREENING -> "MODE_CALL_SCREENING"
        else -> "MODE($mode)"
    }

    private fun ringerModeName(ringer: Int): String = when (ringer) {
        AudioManager.RINGER_MODE_SILENT -> "SILENT"
        AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
        AudioManager.RINGER_MODE_NORMAL -> "NORMAL"
        else -> "RINGER($ringer)"
    }
}
