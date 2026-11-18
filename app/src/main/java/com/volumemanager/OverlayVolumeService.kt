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
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.volumemanager.overlay.OverlayController

/**
 * Persistent foreground service that hosts the slim vertical edge handle
 * and rotated vertical volume panel. Ultra-compact, symmetrical notification.
 */
class OverlayVolumeService : Service() {

    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private var overlayController: OverlayController? = null

    private var permissionMissing = false
    private var volumePercent = 50
    private var currentStreamName = "Media"

    private val handler = Handler(Looper.getMainLooper())
    private val refreshNotification = Runnable {
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        volumePercent = readMediaPercent()

        startForeground(NOTIFICATION_ID, buildNotification())

        overlayController = OverlayController(
            context = this,
            audioManager = audioManager,
            onOverlayPermissionMissing = {
                permissionMissing = true
                notificationManager.notify(NOTIFICATION_ID, buildNotification())
                Log.w(TAG, "Overlay permission missing")
            },
            onVolumeChanged = { percent, streamName ->
                volumePercent = percent
                currentStreamName = streamName
                scheduleNotificationRefresh()
            },
        )
        overlayController?.show()
        Log.d(TAG, "Overlay volume service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PANEL, ACTION_TOGGLE_CHIP -> overlayController?.togglePanel()
            ACTION_CYCLE_STREAM -> overlayController?.cycleStream()
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> overlayController?.show()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshNotification)
        overlayController?.destroy()
        overlayController = null
        Log.d(TAG, "Overlay volume service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleNotificationRefresh() {
        handler.removeCallbacks(refreshNotification)
        handler.postDelayed(refreshNotification, 200)
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

    private fun buildNotification(): Notification {
        val views = RemoteViews(packageName, R.layout.notification_volume).apply {
            val text = if (permissionMissing) {
                getString(R.string.notif_permission_needed)
            } else {
                getString(R.string.notif_status, currentStreamName, volumePercent)
            }
            setTextViewText(R.id.notif_status, text)
            setProgressBar(R.id.notif_progress, 100, if (permissionMissing) 0 else volumePercent, false)

            if (permissionMissing) {
                val settings = PendingIntent.getActivity(
                    this@OverlayVolumeService,
                    0,
                    Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = android.net.Uri.parse("package:$packageName")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                    immutableFlags(),
                )
                setOnClickPendingIntent(R.id.notif_root, settings)
            } else {
                // Tapping the stream title cycles streams (Media -> Ring -> Notify -> Alarm)
                setOnClickPendingIntent(
                    R.id.notif_stream_header,
                    servicePendingIntent(ACTION_CYCLE_STREAM, 2),
                )
                // Tapping the root body toggles the vertical overlay panel
                setOnClickPendingIntent(
                    R.id.notif_root,
                    servicePendingIntent(ACTION_TOGGLE_PANEL, 1),
                )
            }
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_volume_small)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(
                if (permissionMissing) getString(R.string.notif_permission_needed)
                else getString(R.string.notif_status, currentStreamName, volumePercent),
            )
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setCustomContentView(views)
            .setCustomBigContentView(views)
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, OverlayVolumeService::class.java).setAction(action)
        return PendingIntent.getService(this, requestCode, intent, immutableFlags())
    }

    private fun immutableFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    companion object {
        private const val TAG = "OverlayVolumeService"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "volume_overlay_channel"

        const val ACTION_TOGGLE_PANEL = "com.volumemanager.TOGGLE_PANEL"
        const val ACTION_TOGGLE_CHIP = "com.volumemanager.TOGGLE_CHIP"
        const val ACTION_CYCLE_STREAM = "com.volumemanager.CYCLE_STREAM"
        const val ACTION_STOP = "com.volumemanager.STOP"
    }
}
