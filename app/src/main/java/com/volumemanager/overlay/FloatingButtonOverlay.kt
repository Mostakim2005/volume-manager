package com.volumemanager.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.volumemanager.R
import com.volumemanager.util.CrashReporter
import com.volumemanager.util.OverlayPrefs
import com.volumemanager.util.SystemVolumeHelper
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Lightweight Accessibility-hosted floating edge button (`TYPE_ACCESSIBILITY_OVERLAY`).
 * - Single tap: immediately opens Realme UI / Android's native system volume slider.
 * - Drag or hold: freely repositions the button and snaps to the nearest screen edge.
 */
class FloatingButtonOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val inflater = LayoutInflater.from(context)
    private val density = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var handle: View? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var edgeAnimator: ValueAnimator? = null

    private val idleDimRunnable = Runnable {
        val targetAlpha = OverlayPrefs.getIdleOpacityPercent(context) / 100f
        handle?.animate()?.alpha(targetAlpha)?.setDuration(250)?.start()
    }

    /** Shows or hides the floating button according to current preferences. */
    fun applyPreferences() {
        if (!OverlayPrefs.isButtonEnabled(context)) {
            hide()
            return
        }
        val view = handle
        val params = handleParams
        if (view == null || params == null) {
            show()
            return
        }

        val sizePx = dp(OverlayPrefs.getSizeDp(context).toFloat())
        params.width = sizePx
        params.height = sizePx
        applyDockPosition(params, sizePx)
        scaleIcon(view, sizePx)
        updateSafely(view, params)
        updateGestureExclusion()
        wakeHandle()
        resetIdleTimer()
    }

    fun show() {
        if (!OverlayPrefs.isButtonEnabled(context) || handle != null) return
        try {
            val view = inflater.inflate(R.layout.floating_handle, null)
            val sizePx = dp(OverlayPrefs.getSizeDp(context).toFloat())
            val params = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            applyDockPosition(params, sizePx)
            scaleIcon(view, sizePx)
            attachHandleTouch(view, params)
            windowManager.addView(view, params)
            handle = view
            handleParams = params
            updateGestureExclusion()
            wakeHandle()
            resetIdleTimer()
            CrashReporter.logEvent(context, "OVL", "Accessibility button added (${OverlayPrefs.getSizeDp(context)}dp)")
        } catch (e: Throwable) {
            CrashReporter.recordError(context, "FloatingButtonOverlay.show", e)
            Log.e(TAG, "Failed to add accessibility handle: ${e.message}")
        }
    }

    fun hide() {
        handler.removeCallbacks(idleDimRunnable)
        edgeAnimator?.cancel()
        edgeAnimator = null
        removeSafely(handle)
        handle = null
        handleParams = null
    }

    fun onConfigurationChanged() {
        val view = handle ?: return
        val params = handleParams ?: return
        try {
            applyDockPosition(params, params.width)
            updateSafely(view, params)
            updateGestureExclusion()
        } catch (e: Throwable) {
            Log.w(TAG, "Error handling configuration change: ${e.message}")
        }
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        hide()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachHandleTouch(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var startRawX = 0f
        var startRawY = 0f
        var hasMoved = false
        var isRepositionMode = false

        val longPressRunnable = Runnable {
            if (!hasMoved) {
                isRepositionMode = true
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                view.background = ContextCompat.getDrawable(context, R.drawable.handle_reposition_background)
            }
        }

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wakeHandle()
                    edgeAnimator?.cancel()
                    startX = params.x
                    startY = params.y
                    startRawX = event.rawX
                    startRawY = event.rawY
                    hasMoved = false
                    isRepositionMode = false
                    handler.postDelayed(longPressRunnable, HOLD_REPOSITION_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!hasMoved && hypot(dx, dy) >= touchSlop) {
                        hasMoved = true
                        handler.removeCallbacks(longPressRunnable)
                        if (!isRepositionMode) {
                            isRepositionMode = true
                            v.background = ContextCompat.getDrawable(context, R.drawable.handle_reposition_background)
                        }
                    }

                    if (isRepositionMode) {
                        val sizePx = params.width
                        val (width, height) = screenSize()
                        params.x = (startX + dx).roundToInt().coerceIn(0, (width - sizePx).coerceAtLeast(0))
                        params.y = (startY + dy).roundToInt().coerceIn(0, (height - sizePx).coerceAtLeast(0))
                        updateSafely(v, params)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    v.background = ContextCompat.getDrawable(context, R.drawable.handle_background)
                    if (hasMoved) {
                        snapToEdge(v, params)
                    } else if (!isRepositionMode && event.actionMasked == MotionEvent.ACTION_UP) {
                        SystemVolumeHelper.showSystemVolumeUi(context)
                    }
                    isRepositionMode = false
                    resetIdleTimer()
                    true
                }

                else -> false
            }
        }
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val sizePx = params.width
        val (width, height) = screenSize()
        val isRight = params.x + sizePx / 2 >= width / 2
        val targetX = if (isRight) (width - sizePx).coerceAtLeast(0) else 0
        val maxY = (height - sizePx).coerceAtLeast(1)
        val yFraction = (params.y.toFloat() / maxY).coerceIn(0f, 1f)
        OverlayPrefs.saveDockPosition(context, isRight, yFraction)

        edgeAnimator?.cancel()
        edgeAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                params.x = anim.animatedValue as Int
                updateSafely(view, params)
                updateGestureExclusion()
            }
            start()
        }
    }

    private fun applyDockPosition(params: WindowManager.LayoutParams, sizePx: Int) {
        val (width, height) = screenSize()
        val maxX = (width - sizePx).coerceAtLeast(0)
        val maxY = (height - sizePx).coerceAtLeast(0)
        params.x = if (OverlayPrefs.isDockRight(context)) maxX else 0
        params.y = (maxY * OverlayPrefs.getDockYFraction(context)).roundToInt().coerceIn(0, maxY)
    }

    private fun scaleIcon(view: View, sizePx: Int) {
        val icon = view.findViewById<ImageView>(R.id.handle_icon) ?: return
        val iconPx = (sizePx * ICON_SCALE_RATIO).roundToInt().coerceAtLeast(dp(14f))
        val lp = icon.layoutParams
        if (lp.width != iconPx || lp.height != iconPx) {
            lp.width = iconPx
            lp.height = iconPx
            icon.layoutParams = lp
        }
    }

    private fun updateGestureExclusion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            handle?.let { v ->
                val size = handleParams?.width ?: dp(OverlayPrefs.getSizeDp(context).toFloat())
                val rect = Rect(0, 0, v.width.coerceAtLeast(size), v.height.coerceAtLeast(size))
                try {
                    v.systemGestureExclusionRects = listOf(rect)
                } catch (_: Exception) {}
            }
        }
    }

    private fun wakeHandle() {
        handler.removeCallbacks(idleDimRunnable)
        handle?.animate()?.cancel()
        handle?.alpha = 1.0f
    }

    private fun resetIdleTimer() {
        handler.removeCallbacks(idleDimRunnable)
        handler.postDelayed(idleDimRunnable, IDLE_DIM_MS)
    }

    private fun screenSize(): Pair<Int, Int> {
        try {
            val dm = context.resources.displayMetrics
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                return dm.widthPixels to dm.heightPixels
            }
        } catch (_: Throwable) {}

        try {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            if (display != null) {
                val point = Point()
                @Suppress("DEPRECATION")
                display.getRealSize(point)
                if (point.x > 0 && point.y > 0) {
                    return point.x to point.y
                }
            }
        } catch (_: Throwable) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val bounds = windowManager.currentWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    return bounds.width() to bounds.height()
                }
            } catch (_: Throwable) {}
        }

        return 1080 to 2400
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
        const val TAG = "FloatingButtonOverlay"
        const val HOLD_REPOSITION_MS = 280L
        const val IDLE_DIM_MS = 3000L
        const val ICON_SCALE_RATIO = 0.44f
    }
}
