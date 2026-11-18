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
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.SeekBar
import android.widget.TextView
import com.volumemanager.R
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Owns the two overlay windows: the orange edge handle and the compact volume
 * chip. Every WindowManager call is guarded so an OEM quirk cannot kill the
 * foreground service. Volume is changed only by dragging the edge handle
 * vertically or dragging the chip's SeekBar — there are no +/- buttons.
 */
class OverlayController(
    private val context: Context,
    private val audioManager: AudioManager,
    private val onOverlayPermissionMissing: () -> Unit,
    private val onVolumeChanged: (Int) -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val inflater = LayoutInflater.from(context)
    private val density = context.resources.displayMetrics.density

    private val touchSlop = dp(8f)
    private val volumeStepPx = dp(14f)
    private val edgeMargin = dp(8f)
    private val chipWidth = dp(176f)
    private val handleSize = dp(44f)

    private var handle: View? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var chip: View? = null
    private var chipParams: WindowManager.LayoutParams? = null

    private val streams = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_RING,
        AudioManager.STREAM_ALARM,
        AudioManager.STREAM_NOTIFICATION,
    )
    private val optionIds = intArrayOf(
        R.id.opt_media,
        R.id.opt_ring,
        R.id.opt_alarm,
        R.id.opt_notify,
    )
    private val labels = intArrayOf(
        R.string.stream_music_short,
        R.string.stream_ring_short,
        R.string.stream_alarm_short,
        R.string.stream_notify_short,
    )

    private var activeStream = AudioManager.STREAM_MUSIC
    private var activeLabelRes = R.string.stream_music_short
    private var edgeAnimator: ValueAnimator? = null

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            handleParams?.let { params ->
                val (width, height) = screenSize()
                params.x = params.x.coerceIn(0, (width - handleSize).coerceAtLeast(0))
                params.y = params.y.coerceIn(0, (height - handleSize).coerceAtLeast(0))
                handle?.let { updateSafely(it, params) }
            }
            if (chip != null) positionChip()
        }
    }

    /** Adds the edge handle if the overlay permission is granted. */
    fun show() {
        if (!Settings.canDrawOverlays(context)) {
            onOverlayPermissionMissing()
            return
        }
        if (handle != null) return

        val view = inflater.inflate(R.layout.floating_handle, null)
        val params = overlayParams()
        val (_, height) = screenSize()
        params.x = 0
        params.y = (height * 0.36f).toInt()

        attachHandleTouch(view, params)
        try {
            windowManager.addView(view, params)
            handle = view
            handleParams = params
            registerConfigReceiver()
            Log.d(TAG, "Edge handle added")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add handle: ${e.message}")
        }
    }

    /** Shows the chip when hidden, hides it when visible. */
    fun toggleChip() {
        if (!Settings.canDrawOverlays(context)) {
            onOverlayPermissionMissing()
            return
        }
        if (chip != null) closeChip() else openChip()
    }

    /** Removes both windows and cancels any running animation. */
    fun destroy() {
        edgeAnimator?.cancel()
        edgeAnimator = null
        closeChip()
        removeSafely(handle)
        handle = null
        handleParams = null
        try {
            context.unregisterReceiver(configReceiver)
        } catch (_: Exception) {
            // Receiver was never registered.
        }
    }

    // ------------------------------------------------------------------
    // Edge handle: vertical drag = volume, horizontal drag = move, tap = chip
    // ------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun attachHandleTouch(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var startRawX = 0f
        var startRawY = 0f
        var startPercent = 0f
        // 0 = undecided, 1 = moving the handle, 2 = changing volume
        var mode = 0

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    edgeAnimator?.cancel()
                    startX = params.x
                    startY = params.y
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startPercent = currentPercent(activeStream)
                    mode = 0
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY

                    if (mode == 0 && hypot(dx, dy) >= touchSlop) {
                        mode = if (abs(dx) > abs(dy)) 1 else 2
                    }

                    if (mode == 1) {
                        val (width, height) = screenSize()
                        val viewW = v.width.takeIf { it > 0 } ?: handleSize
                        params.x = (startX + dx).roundToInt().coerceIn(0, (width - viewW).coerceAtLeast(0))
                        params.y = (startY + dy).roundToInt().coerceIn(0, (height - viewW).coerceAtLeast(0))
                        updateSafely(v, params)
                        if (chip != null) positionChip()
                    } else if (mode == 2) {
                        val steps = (-dy / volumeStepPx).roundToInt()
                        setPercent(startPercent + steps * 10f)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    when (mode) {
                        0 -> if (event.actionMasked == MotionEvent.ACTION_UP) toggleChip()
                        1 -> snapToEdge(v, params)
                    }
                    mode = 0
                    true
                }

                else -> false
            }
        }
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val viewW = view.width.takeIf { it > 0 } ?: handleSize
        val (width, _) = screenSize()
        val target = if (params.x + viewW / 2 < width / 2) 0 else width - viewW

        edgeAnimator?.cancel()
        edgeAnimator = ValueAnimator.ofInt(params.x, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                params.x = animation.animatedValue as Int
                updateSafely(view, params)
                if (chip != null) positionChip()
            }
            start()
        }
    }

    // ------------------------------------------------------------------
    // Compact chip
    // ------------------------------------------------------------------

    @SuppressLint("InflateParams")
    private fun openChip() {
        if (chip != null) return
        val view = inflater.inflate(R.layout.volume_chip, null)

        val seek = view.findViewById<SeekBar>(R.id.chip_seek)
        val percentText = view.findViewById<TextView>(R.id.chip_percent)
        val streamLabel = view.findViewById<TextView>(R.id.chip_stream)
        val options = view.findViewById<View>(R.id.chip_options)

        seek.max = 10
        seek.progress = (currentPercent(activeStream) / 10f).roundToInt().coerceIn(0, 10)
        percentText.text = getPercentLabel(activeStream)
        streamLabel.text = context.getString(activeLabelRes)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                setPercent(progress * 10f)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        streamLabel.setOnClickListener {
            options.visibility = if (options.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            chipParams?.let { windowManager.updateViewLayout(view, it) }
        }

        for (i in optionIds.indices) {
            view.findViewById<TextView>(optionIds[i]).setOnClickListener {
                activeStream = streams[i]
                activeLabelRes = labels[i]
                streamLabel.text = context.getString(activeLabelRes)
                options.visibility = View.GONE
                seek.progress = (currentPercent(activeStream) / 10f).roundToInt().coerceIn(0, 10)
                percentText.text = getPercentLabel(activeStream)
                chipParams?.let { windowManager.updateViewLayout(view, it) }
            }
        }

        attachChipDrag(view)

        val params = overlayParams().apply { width = chipWidth }
        chipParams = params
        positionChip()

        try {
            windowManager.addView(view, params)
            chip = view
            Log.d(TAG, "Volume chip opened")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add chip: ${e.message}")
            chipParams = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachChipDrag(view: View) {
        var startX = 0
        var startY = 0
        var startRawX = 0f
        var startRawY = 0f

        view.setOnTouchListener { v, event ->
            // Let the SeekBar and the stream label handle their own touches.
            if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                (isInside(v, R.id.chip_seek, event) || isInside(v, R.id.chip_stream, event) ||
                    isInside(v, R.id.chip_options, event))
            ) {
                return@setOnTouchListener false
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val params = chipParams ?: return@setOnTouchListener false
                    startX = params.x
                    startY = params.y
                    startRawX = event.rawX
                    startRawY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val params = chipParams ?: return@setOnTouchListener false
                    val (width, height) = screenSize()
                    val chipH = v.height.takeIf { it > 0 } ?: dp(70f)
                    params.x = (startX + (event.rawX - startRawX)).roundToInt()
                        .coerceIn(edgeMargin, (width - chipWidth - edgeMargin).coerceAtLeast(edgeMargin))
                    params.y = (startY + (event.rawY - startRawY)).roundToInt()
                        .coerceIn(edgeMargin, (height - chipH - edgeMargin).coerceAtLeast(edgeMargin))
                    updateSafely(v, params)
                    true
                }

                else -> false
            }
        }
    }

    private fun isInside(root: View, id: Int, event: MotionEvent): Boolean {
        val child = root.findViewById<View>(id) ?: return false
        if (child.visibility != View.VISIBLE) return false
        val rect = Rect()
        return if (child.getGlobalVisibleRect(rect)) {
            rect.contains(event.rawX.toInt(), event.rawY.toInt())
        } else {
            false
        }
    }

    private fun closeChip() {
        val view = chip ?: return
        removeSafely(view)
        chip = null
        chipParams = null
    }

    private fun positionChip() {
        val params = chipParams ?: return
        val (width, height) = screenSize()
        val handleParams = handleParams
        val chipH = chip?.height?.takeIf { it > 0 } ?: dp(70f)

        if (handleParams == null) {
            params.x = ((width - chipWidth) / 2).coerceAtLeast(edgeMargin)
            params.y = dp(180f)
        } else {
            val handleW = handle?.width?.takeIf { it > 0 } ?: handleSize
            val onLeft = handleParams.x + handleW / 2 < width / 2
            params.x = if (onLeft) {
                handleParams.x + handleW + edgeMargin
            } else {
                width - chipWidth - edgeMargin
            }
            params.y = (handleParams.y - chipH / 2 + handleSize / 2)
                .coerceIn(edgeMargin, (height - chipH - edgeMargin).coerceAtLeast(edgeMargin))
        }
        chip?.let { updateSafely(it, params) }
    }

    // ------------------------------------------------------------------
    // Volume mapping
    // ------------------------------------------------------------------

    private fun setPercent(percent: Float) {
        val clamped = percent.coerceIn(0f, 100f)
        val max = audioManager.getStreamMaxVolume(activeStream)
        if (max <= 0) return
        val target = ((clamped / 100f) * max).roundToInt().coerceIn(0, max)
        audioManager.setStreamVolume(activeStream, target, 0)

        chip?.findViewById<SeekBar>(R.id.chip_seek)?.progress = (clamped / 10f).roundToInt().coerceIn(0, 10)
        chip?.findViewById<TextView>(R.id.chip_percent)?.text = "${clamped.roundToInt()}%"
        onVolumeChanged(clamped.roundToInt())
    }

    private fun currentPercent(stream: Int): Float {
        val max = audioManager.getStreamMaxVolume(stream)
        if (max <= 0) return 0f
        return (audioManager.getStreamVolume(stream) * 100f / max).coerceIn(0f, 100f)
    }

    private fun getPercentLabel(stream: Int): String =
        "${currentPercent(stream).roundToInt()}%"

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

    private fun registerConfigReceiver() {
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
