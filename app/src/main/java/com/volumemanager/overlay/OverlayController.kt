package com.volumemanager.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.volumemanager.R
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Owns the floating overlay windows:
 * 1. Slim filled vertical edge pill (width 28dp, height 64dp) with direct UP/DOWN scrubbing.
 * 2. Rotated vertical volume panel (width 54dp, height 192dp) with stream popover.
 * 3. Transient floating HUD tooltip.
 */
class OverlayController(
    private val context: Context,
    private val audioManager: AudioManager,
    private val onOverlayPermissionMissing: () -> Unit,
    private val onVolumeChanged: (Int, String) -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val inflater = LayoutInflater.from(context)
    private val density = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val volumeStepPx = dp(14f)
    private val edgeMargin = dp(8f)

    private val handleWidth = dp(48f)
    private val handleHeight = dp(48f)
    private val panelWidth = dp(54f)
    private val panelHeight = dp(192f)
    private val popupWidth = dp(128f)

    private var handle: View? = null
    private var handleParams: WindowManager.LayoutParams? = null

    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var popup: View? = null
    private var popupParams: WindowManager.LayoutParams? = null

    private var hud: View? = null
    private var hudParams: WindowManager.LayoutParams? = null

    private val streams = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_RING,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_ALARM,
    )
    private val streamLabels = arrayOf("Media", "Ring", "Notify", "Alarm")
    private val streamIcons = arrayOf("🎵", "🔔", "💬", "⏰")

    private var activeStreamIndex = 0
    val activeStream: Int get() = streams[activeStreamIndex]
    val activeStreamName: String get() = streamLabels[activeStreamIndex]
    val activeStreamIcon: String get() = streamIcons[activeStreamIndex]

    /** Smart auto-focus: if media is playing (YouTube, Spotify, etc.), focus Media stream */
    fun autoFocusMediaIfActive() {
        if (audioManager.isMusicActive && activeStreamIndex != 0) {
            activeStreamIndex = 0
            lastDispatchedStep = -1
        }
    }

    private var edgeAnimator: ValueAnimator? = null
    private var isRepositionMode = false

    // Auto-dismiss and idle dimming timers
    private val idleDimRunnable = Runnable {
        handle?.animate()?.alpha(0.35f)?.setDuration(250)?.start()
    }
    private val autoDismissPanelRunnable = Runnable {
        closePopup()
        closePanel()
    }
    private val hideHudRunnable = Runnable {
        removeSafely(hud)
        hud = null
        hudParams = null
    }

    // Hardware volume rocker sync receiver
    private val volumeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
            if (streamType != -1 && streamType in streams) {
                val idx = streams.indexOf(streamType)
                if (idx >= 0 && idx != activeStreamIndex) {
                    activeStreamIndex = idx
                    lastDispatchedStep = -1
                }
            }
            val current = currentPercent(activeStream)
            lastDispatchedStep = audioManager.getStreamVolume(activeStream)
            syncPanelUi(current)
            onVolumeChanged(current.roundToInt(), activeStreamName)
        }
    }

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            handleParams?.let { params ->
                val (width, height) = screenSize()
                params.x = if (params.x + handleWidth / 2 < width / 2) 0 else width - handleWidth
                params.y = params.y.coerceIn(0, (height - handleHeight).coerceAtLeast(0))
                handle?.let { updateSafely(it, params) }
                updateGestureExclusion()
            }
            if (panel != null) positionPanel()
        }
    }

    /** Adds the slim edge pill if permission is granted. */
    fun show() {
        if (!Settings.canDrawOverlays(context)) {
            onOverlayPermissionMissing()
            return
        }
        if (handle != null) return

        val view = inflater.inflate(R.layout.floating_handle, null)
        val params = overlayParams().apply {
            width = handleWidth
            height = handleHeight
        }
        val (_, height) = screenSize()
        params.x = 0
        params.y = (height * 0.38f).toInt()

        attachHandleTouch(view, params)
        try {
            windowManager.addView(view, params)
            handle = view
            handleParams = params
            updateGestureExclusion()
            registerReceivers()
            resetIdleTimer()
            Log.d(TAG, "Slim edge handle added")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add handle: ${e.message}")
        }
    }

    /** Toggles the vertical volume slider panel. */
    fun togglePanel() {
        if (!Settings.canDrawOverlays(context)) {
            onOverlayPermissionMissing()
            return
        }
        if (panel != null) closePanel() else openPanel()
    }

    /** Cycles to the next audio stream (for notification tap). */
    fun cycleStream() {
        activeStreamIndex = (activeStreamIndex + 1) % streams.size
        val current = currentPercent(activeStream)
        syncPanelUi(current)
        onVolumeChanged(current.roundToInt(), activeStreamName)
        showHud("$activeStreamName: ${current.roundToInt()}%")
        scheduleHideHud()
    }

    /** Steps the active stream volume up (+1) or down (-1) by 10% increments. */
    fun stepVolume(deltaSteps: Int) {
        val current = currentPercent(activeStream)
        val target = (current + deltaSteps * 10f).coerceIn(0f, 100f)
        setPercent(target)
        showHud("$activeStreamName: ${target.roundToInt()}%")
        scheduleHideHud()
    }

    /** Removes all overlay windows and receivers. */
    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        edgeAnimator?.cancel()
        edgeAnimator = null
        closePopup()
        closePanel()
        removeSafely(hud)
        hud = null
        hudParams = null
        removeSafely(handle)
        handle = null
        handleParams = null
        unregisterReceivers()
    }

    // ------------------------------------------------------------------
    // Slim Edge Pill: Direct UP/DOWN Scrub, Tap to Open, Hold to Move
    // ------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun attachHandleTouch(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var startRawX = 0f
        var startRawY = 0f
        var startPercent = 0f
        var hasMoved = false

        val longPressRunnable = Runnable {
            if (!hasMoved) {
                isRepositionMode = true
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                view.background = ContextCompat.getDrawable(context, R.drawable.handle_reposition_background)
                showHud("Reposition Mode ✥")
            }
        }

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wakeHandle()
                    edgeAnimator?.cancel()
                    autoFocusMediaIfActive()
                    startX = params.x
                    startY = params.y
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startPercent = currentPercent(activeStream)
                    hasMoved = false
                    isRepositionMode = false
                    handler.postDelayed(longPressRunnable, 320)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    val distance = hypot(dx, dy)

                    if (!hasMoved && distance >= touchSlop) {
                        hasMoved = true
                        handler.removeCallbacks(longPressRunnable)
                    }

                    if (isRepositionMode) {
                        val (width, height) = screenSize()
                        params.x = (startX + dx).roundToInt().coerceIn(0, width - handleWidth)
                        params.y = (startY + dy).roundToInt().coerceIn(0, height - handleHeight)
                        updateSafely(v, params)
                        if (panel != null) positionPanel()
                    } else if (hasMoved) {
                        // Direct vertical drag: UP = louder (+), DOWN = quieter (-)
                        val totalDragPx = dp(140f).toFloat()
                        val fractionDelta = -dy / totalDragPx
                        val target = (startPercent + fractionDelta * 100f).coerceIn(0f, 100f)
                        setPercent(target)
                        showHud("$activeStreamName: ${target.roundToInt()}%")
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (isRepositionMode) {
                        isRepositionMode = false
                        v.background = ContextCompat.getDrawable(context, R.drawable.handle_background)
                        snapToEdge(v, params)
                    } else if (!hasMoved && event.actionMasked == MotionEvent.ACTION_UP) {
                        togglePanel()
                    } else {
                        lastDispatchedStep = audioManager.getStreamVolume(activeStream)
                    }
                    resetIdleTimer()
                    scheduleHideHud()
                    true
                }

                else -> false
            }
        }
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val (width, _) = screenSize()
        val target = if (params.x + handleWidth / 2 < width / 2) 0 else width - handleWidth

        edgeAnimator?.cancel()
        edgeAnimator = ValueAnimator.ofInt(params.x, target).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                params.x = anim.animatedValue as Int
                updateSafely(view, params)
                updateGestureExclusion()
                if (panel != null) positionPanel()
            }
            start()
        }
    }

    private fun updateGestureExclusion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            handle?.let { v ->
                val rect = Rect(0, 0, v.width.coerceAtLeast(handleWidth), v.height.coerceAtLeast(handleHeight))
                try {
                    v.systemGestureExclusionRects = listOf(rect)
                } catch (_: Exception) {}
            }
        }
    }

    // ------------------------------------------------------------------
    // Rotated Vertical Volume Slider Panel
    // ------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun openPanel() {
        if (panel != null) return
        val view = inflater.inflate(R.layout.volume_vertical_panel, null)

        val btnStream = view.findViewById<View>(R.id.btn_stream_icon)
        val textStreamIcon = view.findViewById<TextView>(R.id.text_stream_icon)
        val trackFrame = view.findViewById<FrameLayout>(R.id.vertical_track_frame)
        val progressFill = view.findViewById<View>(R.id.vertical_progress_fill)
        val textPercent = view.findViewById<TextView>(R.id.text_percent)

        autoFocusMediaIfActive()

        textStreamIcon.text = activeStreamIcon
        val current = currentPercent(activeStream)
        updateTrackHeight(progressFill, current)
        textPercent.text = "${current.roundToInt()}%"

        // Stream button opens popup
        btnStream.setOnClickListener {
            resetAutoDismissPanel()
            togglePopup()
        }

        // Scrubbing across the entire vertical panel: UP = louder, DOWN = quieter
        val handlePanelScrub: (View, MotionEvent) -> Boolean = { _, event ->
            resetAutoDismissPanel()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val location = IntArray(2)
                    trackFrame.getLocationOnScreen(location)
                    val trackTop = location[1].toFloat()
                    val trackH = trackFrame.height.takeIf { it > 0 }?.toFloat() ?: dp(104f).toFloat()
                    val fraction = (1f - ((event.rawY - trackTop) / trackH)).coerceIn(0f, 1f)
                    val percent = fraction * 100f
                    setPercent(percent)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    lastDispatchedStep = audioManager.getStreamVolume(activeStream)
                    true
                }
                else -> true
            }
        }
        view.setOnTouchListener(handlePanelScrub)
        trackFrame.setOnTouchListener(handlePanelScrub)

        val params = overlayParams().apply {
            width = panelWidth
            height = panelHeight
        }
        panelParams = params
        positionPanel()

        try {
            windowManager.addView(view, params)
            panel = view
            resetAutoDismissPanel()
            Log.d(TAG, "Vertical panel opened")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add panel: ${e.message}")
            panelParams = null
        }
    }

    private fun closePanel() {
        val view = panel ?: return
        removeSafely(view)
        panel = null
        panelParams = null
        closePopup()
    }

    private fun positionPanel() {
        val params = panelParams ?: return
        val (width, height) = screenSize()
        val hParams = handleParams ?: return

        val isLeft = hParams.x + handleWidth / 2 < width / 2
        params.x = if (isLeft) {
            hParams.x + handleWidth + edgeMargin
        } else {
            hParams.x - panelWidth - edgeMargin
        }
        params.y = (hParams.y + handleHeight / 2 - panelHeight / 2)
            .coerceIn(edgeMargin, (height - panelHeight - edgeMargin).coerceAtLeast(edgeMargin))

        panel?.let { updateSafely(it, params) }
        if (popup != null) positionPopup()
    }

    // ------------------------------------------------------------------
    // Stream Selection Pop-Up
    // ------------------------------------------------------------------

    private fun togglePopup() {
        if (popup != null) closePopup() else openPopup()
    }

    private fun openPopup() {
        if (popup != null || panel == null) return
        val view = inflater.inflate(R.layout.stream_popup, null)

        val itemIds = intArrayOf(
            R.id.pop_opt_media,
            R.id.pop_opt_ring,
            R.id.pop_opt_notify,
            R.id.pop_opt_alarm,
        )

        for (i in itemIds.indices) {
            view.findViewById<View>(itemIds[i]).setOnClickListener {
                activeStreamIndex = i
                lastDispatchedStep = -1
                val current = currentPercent(activeStream)
                syncPanelUi(current)
                onVolumeChanged(current.roundToInt(), activeStreamName)
                showHud("$activeStreamName: ${current.roundToInt()}%")
                closePopup()
                resetAutoDismissPanel()
            }
        }

        val params = overlayParams().apply { width = popupWidth }
        popupParams = params
        positionPopup()

        try {
            windowManager.addView(view, params)
            popup = view
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add popup: ${e.message}")
            popupParams = null
        }
    }

    private fun closePopup() {
        val view = popup ?: return
        removeSafely(view)
        popup = null
        popupParams = null
    }

    private fun positionPopup() {
        val params = popupParams ?: return
        val pParams = panelParams ?: return
        val (width, _) = screenSize()

        val isLeft = pParams.x < width / 2
        params.x = if (isLeft) {
            pParams.x + panelWidth + edgeMargin
        } else {
            pParams.x - popupWidth - edgeMargin
        }
        params.y = pParams.y
        popup?.let { updateSafely(it, params) }
    }

    // ------------------------------------------------------------------
    // Transient Floating HUD Capsule
    // ------------------------------------------------------------------

    private fun showHud(text: String) {
        if (hud == null) {
            val view = inflater.inflate(R.layout.floating_hud, null)
            val params = overlayParams()
            hudParams = params
            try {
                windowManager.addView(view, params)
                hud = view
            } catch (_: Exception) {}
        }

        hud?.findViewById<TextView>(R.id.hud_icon)?.text = activeStreamIcon
        hud?.findViewById<TextView>(R.id.hud_text)?.text = text

        val hParams = handleParams
        val hudP = hudParams
        if (hParams != null && hudP != null) {
            val (width, _) = screenSize()
            val isLeft = hParams.x + handleWidth / 2 < width / 2
            hudP.x = if (isLeft) hParams.x + handleWidth + edgeMargin else hParams.x - dp(120f) - edgeMargin
            hudP.y = hParams.y + handleHeight / 2 - dp(18f)
            hud?.let { updateSafely(it, hudP) }
        }
    }

    private fun scheduleHideHud() {
        handler.removeCallbacks(hideHudRunnable)
        handler.postDelayed(hideHudRunnable, 1200)
    }

    // ------------------------------------------------------------------
    // Volume Control & DND Protection (Zero-Lag Throttled IPC)
    // ------------------------------------------------------------------

    private var lastDispatchedStep = -1
    private var lastDispatchedStream = -1

    private fun setPercent(percent: Float) {
        val clamped = percent.coerceIn(0f, 100f)
        val max = audioManager.getStreamMaxVolume(activeStream)
        if (max <= 0) return
        val target = ((clamped / 100f) * max).roundToInt().coerceIn(0, max)

        // PERFORMANCE GUARD: Prevent 90Hz/120Hz Binder IPC flood to system_server
        // Only invoke audioManager.setStreamVolume and onVolumeChanged when the actual volume step changes!
        if (target != lastDispatchedStep || activeStream != lastDispatchedStream) {
            lastDispatchedStep = target
            lastDispatchedStream = activeStream
            // Trap Guard: SecurityException on DND/Zen mode
            try {
                audioManager.setStreamVolume(activeStream, target, 0)
            } catch (e: SecurityException) {
                Log.w(TAG, "DND prevented setting volume: ${e.message}")
            }
            onVolumeChanged(clamped.roundToInt(), activeStreamName)
        }

        syncPanelUi(clamped)
    }

    private fun syncPanelUi(percent: Float) {
        panel?.let { p ->
            p.findViewById<TextView>(R.id.text_stream_icon)?.text = activeStreamIcon
            p.findViewById<TextView>(R.id.text_percent)?.text = "${percent.roundToInt()}%"
            val fill = p.findViewById<View>(R.id.vertical_progress_fill)
            updateTrackHeight(fill, percent)
        }
    }

    private fun updateTrackHeight(fill: View?, percent: Float) {
        if (fill == null) return
        val trackTotalHeight = dp(104f)
        val targetHeight = (trackTotalHeight * (percent.coerceIn(0f, 100f) / 100f)).roundToInt()
        val layoutParams = fill.layoutParams
        layoutParams.height = targetHeight
        fill.layoutParams = layoutParams
    }

    private fun currentPercent(stream: Int): Float {
        val max = audioManager.getStreamMaxVolume(stream)
        if (max <= 0) return 0f
        return (audioManager.getStreamVolume(stream) * 100f / max).coerceIn(0f, 100f)
    }

    // ------------------------------------------------------------------
    // Timers & Helper Utilities
    // ------------------------------------------------------------------

    private fun resetAutoDismissPanel() {
        handler.removeCallbacks(autoDismissPanelRunnable)
        handler.postDelayed(autoDismissPanelRunnable, 4000)
    }

    private fun resetIdleTimer() {
        handler.removeCallbacks(idleDimRunnable)
        handler.postDelayed(idleDimRunnable, 3500)
    }

    private fun wakeHandle() {
        handler.removeCallbacks(idleDimRunnable)
        handle?.alpha = 1.0f
    }

    private fun registerReceivers() {
        try {
            val configFilter = IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
            val volFilter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(configReceiver, configFilter, Context.RECEIVER_EXPORTED)
                context.registerReceiver(volumeChangeReceiver, volFilter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(configReceiver, configFilter)
                context.registerReceiver(volumeChangeReceiver, volFilter)
            }
        } catch (_: Exception) {}
    }

    private fun unregisterReceivers() {
        try { context.unregisterReceiver(configReceiver) } catch (_: Exception) {}
        try { context.unregisterReceiver(volumeChangeReceiver) } catch (_: Exception) {}
    }

    private fun overlayParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getSize(point)
            point.x to point.y
        }

    private fun updateSafely(view: View, params: WindowManager.LayoutParams) {
        if (!view.isAttachedToWindow) return
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {}
    }

    private fun removeSafely(view: View?) {
        if (view == null || !view.isAttachedToWindow) return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {}
    }

    private fun dp(value: Float): Int = (value * density).roundToInt().coerceAtLeast(1)

    private companion object {
        const val TAG = "OverlayController"
    }
}
