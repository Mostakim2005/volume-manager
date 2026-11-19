package com.volumemanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.volumemanager.overlay.OverlayController
import com.volumemanager.util.CrashReporter
import com.volumemanager.util.Permissions

/**
 * Persistent foreground service hosting the floating volume overlay and
 * dual-state (compact + expanded 5-profile) notification controls with
 * pre-flight RemoteViews validation and automatic stock fallback.
 */
class OverlayVolumeService : Service() {

    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private var overlayController: OverlayController? = null

    private var permissionMissing = false
    private var volumePercent = 50
    private var currentStreamIndex = 0
    private var currentStreamName = "Media"
    private var currentStreamIcon = "🎵"
    private var isManualMode = false

    private val handler = Handler(Looper.getMainLooper())
    private val refreshNotification = Runnable {
        postNotificationSafely()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        onRunningStateChanged?.invoke(true)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        currentStreamName = getString(R.string.stream_music_short)
        volumePercent = readMediaPercent()
        permissionMissing = !Permissions.canDrawOverlays(this)

        CrashReporter.logEvent(this, "SVC", "onCreate overlayPerm=${!permissionMissing}")
        startForeground(NOTIFICATION_ID, safeBuildNotification())

        overlayController = OverlayController(
            context = this,
            audioManager = audioManager,
            onOverlayPermissionMissing = {
                permissionMissing = true
                postNotificationSafely()
                Log.w(TAG, "Overlay permission missing")
            },
            onVolumeChanged = { percent, streamIndex, streamName, streamIcon, isManual, immediate ->
                volumePercent = percent
                currentStreamIndex = streamIndex
                currentStreamName = streamName
                currentStreamIcon = streamIcon
                isManualMode = isManual
                permissionMissing = !Permissions.canDrawOverlays(this)
                if (immediate) {
                    handler.removeCallbacks(refreshNotification)
                    postNotificationSafely()
                } else {
                    scheduleNotificationRefresh()
                }
            },
        )
        overlayController?.show()
        if (Permissions.canDrawOverlays(this)) {
            overlayController?.syncCurrentState()
        }
        Log.d(TAG, "Overlay volume service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_OPEN_PANEL -> {
                    handler.postDelayed({ overlayController?.openPanelFromNotification() }, 180)
                }
                ACTION_TOGGLE_PANEL, ACTION_TOGGLE_CHIP -> overlayController?.togglePanel()
                ACTION_CYCLE_STREAM -> overlayController?.cycleStream()
                ACTION_SELECT_STREAM -> {
                    val idx = intent.getIntExtra(EXTRA_STREAM_INDEX, -1)
                    if (idx >= 0) overlayController?.selectStreamManual(idx)
                }
                ACTION_AUTO_STREAM -> overlayController?.enableAutoMode()
                ACTION_VOLUME_UP -> overlayController?.stepVolume(1)
                ACTION_VOLUME_DOWN -> overlayController?.stepVolume(-1)
                ACTION_STOP -> {
                    stopSelf()
                    return START_NOT_STICKY
                }
                else -> overlayController?.show()
            }
        } catch (e: Throwable) {
            CrashReporter.recordError(this, "OverlayVolumeService.onStartCommand", e)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        usingStockNotificationFallback = false
        onRunningStateChanged?.invoke(false)
        handler.removeCallbacksAndMessages(null)
        overlayController?.destroy()
        overlayController = null
        CrashReporter.logEvent(this, "SVC", "onDestroy")
        Log.d(TAG, "Overlay volume service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleNotificationRefresh() {
        handler.removeCallbacks(refreshNotification)
        handler.postDelayed(refreshNotification, 200)
    }

    private fun postNotificationSafely() {
        try {
            notificationManager.notify(NOTIFICATION_ID, safeBuildNotification())
        } catch (e: Throwable) {
            CrashReporter.recordError(this, "OverlayVolumeService.postNotification", e)
        }
    }

    private fun readMediaPercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0
        return (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max).coerceIn(0, 100)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun safeBuildNotification(): Notification {
        val modeLabel = getString(if (isManualMode) R.string.notif_mode_manual else R.string.notif_mode_auto)
        val fallbackStatus = if (permissionMissing) {
            getString(R.string.notif_permission_needed)
        } else {
            getString(R.string.notif_status_expanded, currentStreamIcon, currentStreamName, volumePercent, modeLabel)
        }
        return try {
            buildCustomNotification(modeLabel, fallbackStatus) ?: run {
                usingStockNotificationFallback = true
                buildStockFallbackNotification(fallbackStatus)
            }
        } catch (e: Throwable) {
            CrashReporter.recordError(this, "OverlayVolumeService.safeBuildNotification", e)
            usingStockNotificationFallback = true
            buildStockFallbackNotification(fallbackStatus)
        }
    }

    private fun buildCustomNotification(modeLabel: String, expandedStatus: String): Notification? {
        val compactViews = RemoteViews(packageName, R.layout.notification_volume_compact)
        val expandedViews = RemoteViews(packageName, R.layout.notification_volume_expanded)

        val compactStatus = if (permissionMissing) {
            getString(R.string.notif_permission_needed)
        } else {
            getString(R.string.notif_status_compact, "$currentStreamIcon $currentStreamName", volumePercent)
        }

        compactViews.setTextViewText(R.id.notif_compact_status, compactStatus)
        compactViews.setProgressBar(R.id.notif_compact_progress, 100, if (permissionMissing) 0 else volumePercent, false)

        expandedViews.setTextViewText(R.id.notif_expanded_status, expandedStatus)
        expandedViews.setProgressBar(R.id.notif_expanded_progress, 100, if (permissionMissing) 0 else volumePercent, false)

        if (permissionMissing) {
            val settingsPending = overlaySettingsPendingIntent()
            compactViews.setOnClickPendingIntent(R.id.notif_compact_root, settingsPending)
            expandedViews.setOnClickPendingIntent(R.id.notif_expanded_root, settingsPending)
        } else {
            val panelPending = panelActivityPendingIntent()
            val downPending = servicePendingIntent(ACTION_VOLUME_DOWN, 3)
            val upPending = servicePendingIntent(ACTION_VOLUME_UP, 4)

            compactViews.setOnClickPendingIntent(
                R.id.notif_compact_stream,
                servicePendingIntent(ACTION_CYCLE_STREAM, 2),
            )
            compactViews.setOnClickPendingIntent(R.id.btn_compact_down, downPending)
            compactViews.setOnClickPendingIntent(R.id.btn_compact_up, upPending)
            compactViews.setOnClickPendingIntent(R.id.btn_compact_panel, panelPending)
            compactViews.setOnClickPendingIntent(R.id.notif_compact_progress, panelPending)

            expandedViews.setOnClickPendingIntent(
                R.id.notif_expanded_header,
                servicePendingIntent(ACTION_AUTO_STREAM, 6),
            )
            expandedViews.setOnClickPendingIntent(R.id.btn_expanded_panel, panelPending)
            expandedViews.setOnClickPendingIntent(R.id.notif_expanded_progress, panelPending)
            expandedViews.setOnClickPendingIntent(R.id.btn_expanded_down, downPending)
            expandedViews.setOnClickPendingIntent(R.id.btn_expanded_up, upPending)

            val chipIds = intArrayOf(
                R.id.btn_stream_media,
                R.id.btn_stream_ring,
                R.id.btn_stream_notify,
                R.id.btn_stream_call,
                R.id.btn_stream_alarm,
            )
            val activeBg = R.drawable.notif_chip_active_background
            val normalBg = R.drawable.notif_btn_background
            val activeText = ContextCompat.getColor(this, R.color.notif_chip_active_text)
            val normalText = ContextCompat.getColor(this, R.color.notif_btn_text)

            for (i in chipIds.indices) {
                val id = chipIds[i]
                val isSelected = i == currentStreamIndex
                expandedViews.setInt(id, "setBackgroundResource", if (isSelected) activeBg else normalBg)
                expandedViews.setTextColor(id, if (isSelected) activeText else normalText)
                expandedViews.setOnClickPendingIntent(
                    id,
                    servicePendingIntent(ACTION_SELECT_STREAM, 10 + i, streamIndex = i),
                )
            }
        }

        if (!validateRemoteViews(compactViews) || !validateRemoteViews(expandedViews)) {
            return null
        }

        usingStockNotificationFallback = false
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_volume_small)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(expandedStatus)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(compactViews)
            .setCustomBigContentView(expandedViews)
            .build()
    }

    private fun validateRemoteViews(views: RemoteViews): Boolean {
        return try {
            views.apply(applicationContext, FrameLayout(applicationContext))
            true
        } catch (e: Throwable) {
            CrashReporter.recordError(this, "RemoteViews.apply preflight", e)
            false
        }
    }

    private fun buildStockFallbackNotification(statusText: String): Notification {
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_volume_small)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)

        if (permissionMissing) {
            builder.setContentIntent(overlaySettingsPendingIntent())
        } else {
            builder
                .setProgress(100, volumePercent, false)
                .setContentIntent(panelActivityPendingIntent())
                .addAction(0, "−", servicePendingIntent(ACTION_VOLUME_DOWN, 3))
                .addAction(0, "+", servicePendingIntent(ACTION_VOLUME_UP, 4))
                .addAction(0, "$currentStreamIcon $currentStreamName", servicePendingIntent(ACTION_CYCLE_STREAM, 2))
        }
        return builder.build()
    }

    private fun overlaySettingsPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            immutableFlags(),
        )

    private fun panelActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, PanelLaunchActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
        }
        return PendingIntent.getActivity(this, 20, intent, immutableFlags())
    }

    private fun servicePendingIntent(
        action: String,
        requestCode: Int,
        streamIndex: Int = -1,
    ): PendingIntent {
        val intent = Intent(this, OverlayVolumeService::class.java).setAction(action)
        if (streamIndex >= 0) {
            intent.putExtra(EXTRA_STREAM_INDEX, streamIndex)
        }
        return PendingIntent.getService(this, requestCode, intent, immutableFlags())
    }

    private fun immutableFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    companion object {
        private const val TAG = "OverlayVolumeService"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "volume_overlay_channel"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var usingStockNotificationFallback: Boolean = false
            private set

        @Volatile
        var onRunningStateChanged: ((Boolean) -> Unit)? = null

        const val ACTION_OPEN_PANEL = "com.volumemanager.OPEN_PANEL"
        const val ACTION_TOGGLE_PANEL = "com.volumemanager.TOGGLE_PANEL"
        const val ACTION_TOGGLE_CHIP = "com.volumemanager.TOGGLE_CHIP"
        const val ACTION_CYCLE_STREAM = "com.volumemanager.CYCLE_STREAM"
        const val ACTION_SELECT_STREAM = "com.volumemanager.SELECT_STREAM"
        const val ACTION_AUTO_STREAM = "com.volumemanager.AUTO_STREAM"
        const val ACTION_VOLUME_UP = "com.volumemanager.VOLUME_UP"
        const val ACTION_VOLUME_DOWN = "com.volumemanager.VOLUME_DOWN"
        const val ACTION_STOP = "com.volumemanager.STOP"
        const val EXTRA_STREAM_INDEX = "com.volumemanager.EXTRA_STREAM_INDEX"
    }
}
