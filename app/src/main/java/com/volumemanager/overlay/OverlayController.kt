package com.volumemanager.overlay

import android.animation.DecelerateInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import com.google.android.material.slider.Slider
import com.volumemanager.R
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Owns both overlay windows: the draggable edge handle and the expandable
 * volume card. All WindowManager add/update/remove calls live here so the
 * foreground service stays small and lifecycle-safe.
 */
class OverlayController(
    private val context: Context,
    private val audioManager: AudioManager,
    private val onOverlayPermissionMissing: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val inflater = LayoutInflater.from(context)
    private val density = context.resources.displayMetrics.density

    private val dragStepPx = dp(16f)     // discrete vertical grid while dragging
    private val touchSlopPx = dp(8f)     // below this => treated as a click
    private val edgeMarginPx = dp(8f)
    private val panelWidthPx = dp(280f)
    private val handleFallbackPx = dp(56f)

    private var handleView: View? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var isPanelOpen = false
    private var isSyncingSliders = false
    private var edgeAnimator: ValueAnimator? = null

    private val streamSliderIds = intArrayOf(
        R.id.slider_music,
        R.id.slider_ring,
        R.id.slider_alarm,
        R.id.slider_notification,
    )
    private val streams = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_RING,
        AudioManager.STREAM_ALARM,
        AudioManager.STREAM_NOTIFICATION,
    )

    /** Re-clamp both windows after a rotation or multi-window resize. */
    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            handleParams?.let { params ->
                val (width, height) = screenSize()
                val viewW = handleView?.width?.takeIf { it > 0 } ?: handleFallbackPx
                params.x = params.x.coerceIn(0, (width - viewW).coerceAtLeast(0))
                params.y = params.y.coerceIn(0, (height - viewW).coerceAtLeast(0))
                handleView?.let { updateSafely(it, params) }
            }
            if (isPanelOpen) positionPanel()
        }
    }

    /** Adds the edge handle if the overlay permission is granted. */
    fun show() {
        if (!Settings.canDrawOverlays(context)) {
            onOverlayPermissionMissing()
            return
        }
        if (handleView != null) return

        val view = inflater.inflate(R.layout.floating_handle, null)
        val params = overlayParams()
        val (_, height) = screenSize()
        params.x = 0
        params.y = (height * 0.35f).toInt()

        attachHandleTouchListener(view, params)
        try {
            windowManager.addView(view, params)
            handleView = view
            handleParams = params
            try {
                val filter = IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(configReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(configReceiver, filter)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not register config receiver: ${e.message}")
            }
            Log.d(TAG, "Edge handle added")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add handle: ${e.message}")
        }
    }

    /** Opens the volume card when it is closed, and closes it when open. */
    fun togglePanel() {
        if (!Settings.canDrawOverlays(context)) {
            onOverlayPermissionMissing()
            return
        }
        if (isPanelOpen) closePanel() else openPanel()
    }

    /** Removes every window and cancels running animations. */
    fun destroy() {
        edgeAnimator?.cancel()
        edgeAnimator = null
        closePanel()
        removeSafely(handleView)
        handleView = null
        handleParams = null
        try {
            context.unregisterReceiver(configReceiver)
        } catch (_: Exception) {
            // Receiver was never registered (overlay permission was missing).
        }
    }

    // ------------------------------------------------------------------
    // Touch: drag with a stepped Y grid, click to expand, snap on release
    // ------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun attachHandleTouchListener(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var startRawX = 0f
        var startRawY = 0f

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    edgeAnimator?.cancel()
                    startX = params.x
                    startY = params.y
                    startRawX = event.rawX
                    startRawY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    val (width, height) = screenSize()
                    val viewW = v.width.takeIf { it > 0 } ?: handleFallbackPx
                    val viewH = v.height.takeIf { it > 0 } ?: handleFallbackPx

                    params.x = (startX + dx).roundToInt().coerceIn(0, width - viewW)
                    val steppedDy = ((dy / dragStepPx).roundToInt() * dragStepPx).toInt()
                    params.y = (startY + steppedDy).coerceIn(0, height - viewH)
                    updateSafely(v, params)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val distance = hypot(event.rawX - startRawX, event.rawY - startRawY)
                    if (distance < touchSlopPx) {
                        togglePanel()
                    } else {
                        snapToNearestEdge()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun snapToNearestEdge() {
        val view = handleView ?: return
        val params = handleParams ?: return
        val viewW = view.width.takeIf { it > 0 } ?: handleFallbackPx
        val (width, _) = screenSize()
        val targetX = if (params.x + viewW / 2 < width / 2) 0 else width - viewW

        edgeAnimator?.cancel()
        edgeAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                params.x = animation.animatedValue as Int
                updateSafely(view, params)
                if (isPanelOpen) positionPanel()
            }
            start()
        }
    }

    // ------------------------------------------------------------------
    // Volume panel
    // ------------------------------------------------------------------

    @SuppressLint("InflateParams")
    private fun openPanel() {
        if (panelView != null) return
        val view = inflater.inflate(R.layout.volume_panel, null)
        bindSliders(view)
        view.findViewById<ImageButton>(R.id.btn_close).setOnClickListener { closePanel() }

        // Pre-measure so we can place the card without an extra layout pass.
        view.measure(
            View.MeasureSpec.makeMeasureSpec(panelWidthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val panelW = view.measuredWidth.takeIf { it > 0 } ?: panelWidthPx
        val panelH = view.measuredHeight.takeIf { it > 0 } ?: dp(280f)

        val params = overlayParams().apply { width = panelW; height = panelH }
        panelParams = params
        positionPanel() // computes x/y; updateSafely is a no-op until the view is attached

        try {
            windowManager.addView(view, params)
            panelView = view
            isPanelOpen = true
            syncSlidersFromAudio()
            Log.d(TAG, "Volume panel opened")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add panel: ${e.message}")
        }
    }

    private fun closePanel() {
        val view = panelView ?: return
        removeSafely(view)
        panelView = null
        panelParams = null
        isPanelOpen = false
    }

    /** Keeps the panel anchored beside whichever edge the handle is docked to. */
    private fun positionPanel() {
        val params = panelParams ?: return
        val (width, height) = screenSize()
        val panelW = params.width.takeIf { it > 0 } ?: panelWidthPx
        val panelH = params.height.takeIf { it > 0 } ?: dp(280f)
        val handle = handleParams

        if (handle == null) {
            params.x = ((width - panelW) / 2).coerceAtLeast(edgeMarginPx)
            params.y = edgeMarginPx
        } else {
            val handleW = handleView?.width?.takeIf { it > 0 } ?: handleFallbackPx
            val dockedLeft = handle.x + handleW / 2 < width / 2
            params.x = if (dockedLeft) {
                handle.x + handleW + edgeMarginPx
            } else {
                width - panelW - edgeMarginPx
            }
            params.y = (handle.y - panelH / 2 + handleFallbackPx / 2)
                .coerceIn(edgeMarginPx, (height - panelH - edgeMarginPx).coerceAtLeast(edgeMarginPx))
        }
        panelView?.let { updateSafely(it, params) }
    }

    private fun bindSliders(root: View) {
        for (i in streamSliderIds.indices) {
            val slider = root.findViewById<Slider>(streamSliderIds[i])
            val stream = streams[i]
            slider.valueFrom = 0f
            slider.valueTo = 100f
            slider.stepSize = 10f
            slider.addOnChangeListener { _, value, fromUser ->
                if (fromUser && !isSyncingSliders) {
                    val max = audioManager.getStreamMaxVolume(stream)
                    val target = ((value / 100f) * max).roundToInt().coerceIn(0, max)
                    audioManager.setStreamVolume(stream, target, 0)
                }
            }
        }
    }

    private fun syncSlidersFromAudio() {
        val view = panelView ?: return
        isSyncingSliders = true
        for (i in streamSliderIds.indices) {
            val slider = view.findViewById<Slider>(streamSliderIds[i])
            slider.value = streamToPercent(streams[i])
        }
        isSyncingSliders = false
    }

    private fun streamToPercent(stream: Int): Float {
        val max = audioManager.getStreamMaxVolume(stream)
        if (max <= 0) return 0f
        return (audioManager.getStreamVolume(stream) * 100f / max).coerceIn(0f, 100f)
    }

    // ------------------------------------------------------------------
    // Window helpers
    // ------------------------------------------------------------------

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
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
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
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout failed: ${e.message}")
        }
    }

    private fun removeSafely(view: View?) {
        if (view == null || !view.isAttachedToWindow) return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "removeView failed: ${e.message}")
        }
    }

    private fun dp(value: Float): Int = (value * density).roundToInt().coerceAtLeast(1)

    private companion object {
        const val TAG = "OverlayController"
    }
}
