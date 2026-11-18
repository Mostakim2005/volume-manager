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
import android.os.IBinder
import android.util.Log
import android.widget.RemoteViews
import com.volumemanager.overlay.OverlayController

/**
 * Persistent foreground service that hosts the edge overlay. START_STICKY makes
 * Android recreate it after low-memory kills, and Realme UI needs its manual
 * Auto-launch / battery exemptions to leave it alone across reboots.
 */
class OverlayVolumeService : Service() {

    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private var overlayController: OverlayController? = null
    private var statusText = DEFAULT_STATUS

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        // Must be called immediately or the system kills the service on O+.
        startForeground(NOTIFICATION_ID, buildNotification())

        overlayController = OverlayController(
            context = this,
            audioManager = audioManager,
            onOverlayPermissionMissing = {
                statusText = PERMISSION_STATUS
                notificationManager.notify(NOTIFICATION_ID, buildNotification())
                Log.w(TAG, "Overlay permission missing - tap the notification to grant it")
            },
        )
        overlayController?.show()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PANEL -> overlayController?.togglePanel()
            ACTION_MEDIA_UP -> adjustMedia(AudioManager.ADJUST_RAISE)
            ACTION_MEDIA_DOWN -> adjustMedia(AudioManager.ADJUST_LOWER)
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> overlayController?.show()
        }
        // Recreate the service if the system kills it under memory pressure.
        return START_STICKY
    }

    override fun onDestroy() {
        overlayController?.destroy()
        overlayController = null
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun adjustMedia(direction: Int) {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        statusText = DEFAULT_STATUS
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW, // silent: no sound, no heads-up pop
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            immutableFlags(),
        )

        val views = RemoteViews(packageName, R.layout.notification_volume).apply {
            setTextViewText(R.id.notif_status, statusText)
            setOnClickPendingIntent(R.id.notif_media_down, servicePendingIntent(ACTION_MEDIA_DOWN, 1))
            setOnClickPendingIntent(R.id.notif_media_up, servicePendingIntent(ACTION_MEDIA_UP, 2))
            setOnClickPendingIntent(R.id.notif_toggle, servicePendingIntent(ACTION_TOGGLE_PANEL, 3))
            setOnClickPendingIntent(R.id.notif_stop, servicePendingIntent(ACTION_STOP, 4))
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(R.drawable.ic_volume_small)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText)
            .setContentIntent(openApp)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private companion object {
        const val TAG = "OverlayVolumeService"
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_CHANNEL_ID = "volume_overlay_channel"

        const val ACTION_TOGGLE_PANEL = "com.volumemanager.TOGGLE_PANEL"
        const val ACTION_MEDIA_UP = "com.volumemanager.MEDIA_UP"
        const val ACTION_MEDIA_DOWN = "com.volumemanager.MEDIA_DOWN"
        const val ACTION_STOP = "com.volumemanager.STOP"

        const val DEFAULT_STATUS = "Edge volume panel is active"
        const val PERMISSION_STATUS = "Tap to grant overlay permission"
    }
}
