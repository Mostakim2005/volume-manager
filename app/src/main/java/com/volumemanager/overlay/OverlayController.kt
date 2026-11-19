package com.volumemanager.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
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
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.volumemanager.R
import com.volumemanager.util.CrashReporter
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Owns all floating overlay windows and real-time audio stream detection:
 * 1. 48x48dp squircle edge handle with vertical scrub, tap-to-panel, and hold-to-move.
 * 2. 80x228dp vertical volume slider panel with stream button and live readout.
 * 3. 164dp 6-option stream selector popup (Auto + Media, Ring, Notify, Call, Alarm).
 * 4. Transient floating HUD capsule for volume and DND feedback.
 */
class OverlayController(
    private val context: Context,
    private val audioManager: AudioManager,
    private val onOverlayPermissionMissing: () -> Unit,
    private val onVolumeChanged: (
        percent: Int,
        streamIndex: Int,
        streamName: String,
        streamIcon: String,
        isManual: Boolean,
        immediate: Boolean,
    ) -> Unit,
) {
    data class VolumeStream(val streamType: Int, val label: String, val icon: String)

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val inflater = LayoutInflater.from(context)
    private val density = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val handleWidth = context.resources.getDimensionPixelSize(R.dimen.handle_width)
    private val handleHeight = context.resources.getDimensionPixelSize(R.dimen.handle_height)
    private val panelWidth = context.resources.getDimensionPixelSize(R.dimen.vertical_panel_width)
    private val panelHeight = context.resources.getDimensionPixelSize(R.dimen.vertical_panel_height)
    private val trackHeightPx = context.resources.getDimensionPixelSize(R.dimen.vertical_track_height)
    private val popupWidth = context.resources.getDimensionPixelSize(R.dimen.stream_popup_width)
    private val edgeMargin = context.resources.getDimensionPixelSize(R.dimen.edge_margin)

    private var handle: View? = null
    private var handleParams: WindowManager.LayoutParams? = null

    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var popup: View? = null
    private var popupParams: WindowManager.LayoutParams? = null

    private var hud: View? = null
    private var hudParams: WindowManager.LayoutParams? = null

    private val streams = listOf(
        VolumeStream(AudioManager.STREAM_MUSIC, context.getString(R.string.stream_music_short), "🎵"),
        VolumeStream(AudioManager.STREAM_RING, context.getString(R.string.stream_ring_short), "🔔"),
        VolumeStream(AudioManager.STREAM_NOTIFICATION, context.getString(R.string.stream_notify_short), "💬"),
        VolumeStream(AudioManager.STREAM_VOICE_CALL, context.getString(R.string.stream_call_short), "📞"),
        VolumeStream(AudioManager.STREAM_ALARM, context.getString(R.string.stream_alarm_short), "⏰"),
    )

    private var activeStreamIndex = INDEX_MEDIA
    private var manualLockActive = false
    private var dragLastSentStep = -1
    private var receiversRegistered = false
    private var playbackCallback: Any? = null

    val activeStream: Int get() = streams[activeStreamIndex].streamType
    val activeStreamName: String get() = streams[activeStreamIndex].label
    val activeStreamIcon: String get() = streams[activeStreamIndex].icon

    private var edgeAnimator: ValueAnimator? = null
    private var isRepositionMode = false

    private val idleDimRunnable = Runnable {
        handle?.animate()?.alpha(0.35f)?.setDuration(250)?.start()
    }

    private val autoDismissPanelRunnable = Runnable {
        closePopup()
        closePanel()
    }

    private val clearManualLockRunnable = Runnable {
        if (!manualLockActive) return@Runnable
        manualLockActive = false
        activeStreamIndex = detectSmartStreamIndex()
        dragLastSentStep = -1
        syncAllUi(currentPercent(activeStream), immediate = true)
    }

    private val hideHudRunnable = Runnable {
        removeSafely(hud)
        hud = null
        hudParams = null
    }

    private val volumeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
            // Keep background Notification/Ring broadcasts from hijacking activeStreamIndex.
            if (streamType == -1 || streamType == activeStream) {
                dragLastSentStep = -1
                syncAllUi(currentPercent(activeStream), immediate = true)
            }
        }
    }

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            try {
                handleParams?.let { params ->
                    val (width, height) = screenSize()
                    params.x = if (params.x + handleWidth / 2 < width / 2) 0 else (width - handleWidth).coerceAtLeast(0)
                    params.y = params.y.coerceIn(0, (height - handleHeight).coerceAtLeast(0))
                    handle?.let { updateSafely(it, params) }
                    updateGestureExclusion()
                }
                if (panel != null) positionPanel()
            } catch (e: Throwable) {
                Log.w(TAG, "Error handling config change: ${e.message}")
            }
        }
    }

    /** Adds the 48dp squircle edge handle if overlay permission is granted. */
    fun show() {
        if (!Settings.canDrawOverlays(context)) {
            onOverlayPermissionMissing()
            return
        }
        if (handle != null) return

        try {
            val view = inflater.inflate(R.layout.floating_handle, null)
            val params = overlayParams().apply {
                width = handleWidth
                height = handleHeight
            }
            val (_, height) = screenSize()
            params.x = 0
            params.y = (height * 0.38f).toInt()

            attachHandleTouch(view, params)
            windowManager.addView(view, params)
            handle = view
            handleParams = params
            updateGestureExclusion()
            registerReceivers()
            resetIdleTimer()
            CrashReporter.logEvent(context, "OVL", "Handle added")
            Log.d(TAG, "Squircle edge handle added")
        } catch (e: Throwable) {
            CrashReporter.recordError(context, "OverlayController.show", e)
            Log.e(TAG, "Failed to add handle: ${e.message}")
        }
    }

    /** Syncs current stream detection and live hardware volume to UI and notification. */
    fun syncCurrentState() {
        resolveStreamForInteraction()
        syncAllUi(currentPercent(activeStream), immediate = true)
    }

    /** Toggles the vertical volume slider panel from the edge handle. */
    fun togglePanel() {
        if (!Settings.canDrawOverlays(context)) {
            onOverlayPermissionMissing()
            return
        }
        if (panel != null) closePanel() else openPanel()
    }

    /** Opens or refreshes the vertical volume panel after notification shade collapse. */
    fun openPanelFromNotification() {
        if (!Settings.canDrawOverlays(context)) {
            onOverlayPermissionMissing()
            return
        }
        if (panel == null) {
            openPanel()
        } else {
            resolveStreamForInteraction()
            syncAllUi(currentPercent(activeStream), immediate = false)
            resetAutoDismissPanel()
        }
    }

    /** Cycles manually to the next stream profile and locks manual override for 15s. */
    fun cycleStream() {
        selectStreamManual((activeStreamIndex + 1) % streams.size)
    }

    /** Selects a specific stream profile manually and keeps it locked for 15s of inactivity. */
    fun selectStreamManual(index: Int) {
        if (index !in streams.indices) return
        activeStreamIndex = index
        manualLockActive = true
        dragLastSentStep = -1
        refreshManualLockTimer()
        val current = currentPercent(activeStream)
        syncAllUi(current, immediate = true)
        CrashReporter.logEvent(context, "STREAM", "manual -> $activeStreamName")
        showHud("$activeStreamName: ${current.roundToInt()}%")
        scheduleHideHud()
    }

    /** Clears manual override and immediately returns to smart stream auto-detection. */
    fun enableAutoMode() {
        handler.removeCallbacks(clearManualLockRunnable)
        manualLockActive = false
        activeStreamIndex = detectSmartStreamIndex()
        dragLastSentStep = -1
        val current = currentPercent(activeStream)
        syncAllUi(current, immediate = true)
        CrashReporter.logEvent(context, "STREAM", "auto -> $activeStreamName")
        showHud("${context.getString(R.string.stream_auto_short)} • $activeStreamName: ${current.roundToInt()}%")
        scheduleHideHud()
    }

    /** Steps the active stream by at least +/-1 discrete OS hardware step and reads back truth. */
    fun stepVolume(deltaSteps: Int) {
        resolveStreamForInteraction()
        val stream = activeStream
        val min = streamMin(stream)
        val max = streamMax(stream)
        if (max <= 0) return
        val currentStep = audioManager.getStreamVolume(stream)
        val targetStep = (currentStep + deltaSteps).coerceIn(min, max)
        applyHardwareStep(targetStep, isDiscreteStep = true, showFeedbackHud = true)
    }

    /** Removes all overlay windows, callbacks, and receivers. */
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
    // Smart Stream Detection (Mode + Active Playback + Media Default)
    // ------------------------------------------------------------------

    private fun detectSmartStreamIndex(usages: List<Int>? = null): Int {
        val mode = audioManager.mode
        if (mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mode == AudioManager.MODE_CALL_SCREENING)
        ) {
            return INDEX_CALL
        }

        val activeUsages = usages ?: activePlaybackUsages()
        if (activeUsages.any {
                it == AudioAttributes.USAGE_VOICE_COMMUNICATION ||
                    it == AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING
            }
        ) {
            return INDEX_CALL
        }
        if (mode == AudioManager.MODE_RINGTONE ||
            activeUsages.any { it == AudioAttributes.USAGE_NOTIFICATION_RINGTONE }
        ) {
            return INDEX_RING
        }
        if (activeUsages.any { it == AudioAttributes.USAGE_ALARM }) {
            return INDEX_ALARM
        }
        if (audioManager.isMusicActive || activeUsages.any { usage ->
                usage == AudioAttributes.USAGE_MEDIA ||
                    usage == AudioAttributes.USAGE_GAME ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && usage == AudioAttributes.USAGE_ASSISTANT)
            }
        ) {
            return INDEX_MEDIA
        }

        // Default to Media when idle/paused/buffering so YouTube volume works immediately.
        return INDEX_MEDIA
    }

    private fun activePlaybackUsages(): List<Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()
        return activePlaybackUsagesO()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun activePlaybackUsagesO(): List<Int> {
        return try {
            audioManager.activePlaybackConfigurations.mapNotNull { it.audioAttributes?.usage }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun resolveStreamForInteraction() {
        val mode = audioManager.mode
        val liveCallOrRing = when {
            mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_IN_COMMUNICATION ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mode == AudioManager.MODE_CALL_SCREENING) -> INDEX_CALL
            mode == AudioManager.MODE_RINGTONE -> INDEX_RING
            else -> -1
        }
        if (liveCallOrRing != -1) {
            handler.removeCallbacks(clearManualLockRunnable)
            manualLockActive = false
            if (activeStreamIndex != liveCallOrRing) {
                activeStreamIndex = liveCallOrRing
                dragLastSentStep = -1
                syncAllUi(currentPercent(activeStream), immediate = true)
            }
            return
        }

        if (manualLockActive) {
            refreshManualLockTimer()
            return
        }

        val detected = detectSmartStreamIndex()
        if (detected != activeStreamIndex) {
            activeStreamIndex = detected
            dragLastSentStep = -1
            syncAllUi(currentPercent(activeStream), immediate = true)
        }
    }

    private fun refreshManualLockTimer() {
        handler.removeCallbacks(clearManualLockRunnable)
        handler.postDelayed(clearManualLockRunnable, MANUAL_LOCK_MS)
    }

    // ------------------------------------------------------------------
    // Squircle Edge Handle: Direct UP/DOWN Scrub, Tap to Open, Hold to Move
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
                    resolveStreamForInteraction()
                    startX = params.x
                    startY = params.y
                    startRawX = event.rawX
                    startRawY = event.rawY
                    dragLastSentStep = audioManager.getStreamVolume(activeStream)
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
                        params.x = (startX + dx).roundToInt().coerceIn(0, (width - handleWidth).coerceAtLeast(0))
                        params.y = (startY + dy).roundToInt().coerceIn(0, (height - handleHeight).coerceAtLeast(0))
                        updateSafely(v, params)
                        if (panel != null) positionPanel()
                    } else if (hasMoved) {
                        val totalDragPx = dp(140f).toFloat()
                        val fractionDelta = -dy / totalDragPx
                        val targetPercent = (startPercent + fractionDelta * 100f).coerceIn(0f, 100f)
                        setPercentDuringDrag(targetPercent, showFeedbackHud = true)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    dragLastSentStep = -1
                    if (isRepositionMode) {
                        isRepositionMode = false
                        v.background = ContextCompat.getDrawable(context, R.drawable.handle_background)
                        snapToEdge(v, params)
                    } else if (!hasMoved && event.actionMasked == MotionEvent.ACTION_UP) {
                        togglePanel()
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
        val target = if (params.x + handleWidth / 2 < width / 2) 0 else (width - handleWidth).coerceAtLeast(0)

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
    // Vertical Volume Slider Panel
    // ------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun openPanel() {
        if (panel != null) return
        resolveStreamForInteraction()

        val view = inflater.inflate(R.layout.volume_vertical_panel, null)
        val btnStream = view.findViewById<View>(R.id.btn_stream_icon)
        val textStreamIcon = view.findViewById<TextView>(R.id.text_stream_icon)
        val textStreamLabel = view.findViewById<TextView>(R.id.text_stream_label)
        val trackFrame = view.findViewById<FrameLayout>(R.id.vertical_track_frame)
        val progressFill = view.findViewById<View>(R.id.vertical_progress_fill)
        val textPercent = view.findViewById<TextView>(R.id.text_percent)

        val current = currentPercent(activeStream)
        textStreamIcon.text = activeStreamIcon
        textStreamLabel.text = activeStreamName
        updateTrackHeight(progressFill, trackFrame, current)
        textPercent.text = "${current.roundToInt()}%"

        btnStream.setOnClickListener {
            resetAutoDismissPanel()
            if (manualLockActive) refreshManualLockTimer()
            togglePopup()
        }

        var isScrubbingPanel = false
        val handlePanelScrub: (View, MotionEvent) -> Boolean = { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                if (popup == null) closePanel()
                true
            } else {
                resetAutoDismissPanel()
                val btnLocation = IntArray(2)
                btnStream.getLocationOnScreen(btnLocation)
                val btnRect = Rect(
                    btnLocation[0],
                    btnLocation[1],
                    btnLocation[0] + btnStream.width,
                    btnLocation[1] + btnStream.height,
                )

                if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                    btnRect.contains(event.rawX.roundToInt(), event.rawY.roundToInt())
                ) {
                    isScrubbingPanel = false
                    false
                } else {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            isScrubbingPanel = true
                            resolveStreamForInteraction()
                            dragLastSentStep = audioManager.getStreamVolume(activeStream)
                            scrubPanelAt(trackFrame, event.rawY)
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (isScrubbingPanel) {
                                scrubPanelAt(trackFrame, event.rawY)
                            }
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            isScrubbingPanel = false
                            dragLastSentStep = -1
                            true
                        }
                        else -> true
                    }
                }
            }
        }
        view.setOnTouchListener(handlePanelScrub)
        trackFrame.setOnTouchListener(handlePanelScrub)

        val params = overlayParams().apply {
            width = panelWidth
            height = panelHeight
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        panelParams = params
        positionPanel()

        try {
            windowManager.addView(view, params)
            panel = view
            resetAutoDismissPanel()
            Log.d(TAG, "Vertical panel opened")
        } catch (e: Exception) {
            CrashReporter.recordError(context, "OverlayController.openPanel", e)
            Log.e(TAG, "Failed to add panel: ${e.message}")
            panelParams = null
        }
    }

    private fun scrubPanelAt(trackFrame: FrameLayout, rawY: Float) {
        val location = IntArray(2)
        trackFrame.getLocationOnScreen(location)
        val trackTop = location[1].toFloat()
        val trackH = trackFrame.height.takeIf { it > 0 }?.toFloat() ?: trackHeightPx.toFloat()
        val fraction = (1f - ((rawY - trackTop) / trackH)).coerceIn(0f, 1f)
        setPercentDuringDrag(fraction * 100f, showFeedbackHud = false)
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
        val candidateX = if (isLeft) {
            hParams.x + handleWidth + edgeMargin
        } else {
            hParams.x - panelWidth - edgeMargin
        }
        params.x = candidateX.coerceIn(0, (width - panelWidth).coerceAtLeast(0))
        params.y = (hParams.y + handleHeight / 2 - panelHeight / 2)
            .coerceIn(0, (height - panelHeight).coerceAtLeast(0))

        panel?.let { updateSafely(it, params) }
        if (popup != null) positionPopup()
    }

    // ------------------------------------------------------------------
    // Stream Selection Pop-Up (Auto + 5 Profiles)
    // ------------------------------------------------------------------

    private fun togglePopup() {
        if (popup != null) closePopup() else openPopup()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun openPopup() {
        if (popup != null || panel == null) return
        val view = inflater.inflate(R.layout.stream_popup, null)

        view.findViewById<View>(R.id.pop_opt_auto).setOnClickListener {
            enableAutoMode()
            closePopup()
            resetAutoDismissPanel()
        }

        val streamOptionIds = intArrayOf(
            R.id.pop_opt_media,
            R.id.pop_opt_ring,
            R.id.pop_opt_notify,
            R.id.pop_opt_call,
            R.id.pop_opt_alarm,
        )

        for (i in streamOptionIds.indices) {
            view.findViewById<View>(streamOptionIds[i]).setOnClickListener {
                selectStreamManual(i)
                closePopup()
                resetAutoDismissPanel()
            }
        }

        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                closePopup()
                true
            } else {
                false
            }
        }

        val params = overlayParams().apply {
            width = popupWidth
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        popupParams = params
        popup = view
        syncPopupHighlight()
        positionPopup()

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            CrashReporter.recordError(context, "OverlayController.openPopup", e)
            Log.e(TAG, "Failed to add popup: ${e.message}")
            popup = null
            popupParams = null
        }
    }

    private fun syncPopupHighlight() {
        val view = popup ?: return
        val allIds = intArrayOf(
            R.id.pop_opt_auto,
            R.id.pop_opt_media,
            R.id.pop_opt_ring,
            R.id.pop_opt_notify,
            R.id.pop_opt_call,
            R.id.pop_opt_alarm,
        )
        val activeRowIndex = if (!manualLockActive) 0 else activeStreamIndex + 1
        val activeTextColor = ContextCompat.getColor(context, R.color.overlay_option_active_text)
        val normalTextColor = ContextCompat.getColor(context, R.color.text_primary)

        for (i in allIds.indices) {
            val row = view.findViewById<TextView>(allIds[i]) ?: continue
            val isSelected = i == activeRowIndex
            row.setBackgroundResource(
                if (isSelected) R.drawable.stream_option_active_background
                else R.drawable.stream_option_background,
            )
            row.setTextColor(if (isSelected) activeTextColor else normalTextColor)
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
        val (width, height) = screenSize()

        val isLeft = pParams.x < width / 2
        val candidateX = if (isLeft) {
            pParams.x + panelWidth + edgeMargin
        } else {
            pParams.x - popupWidth - edgeMargin
        }
        val estimatedHeight = popup?.height?.takeIf { it > 0 } ?: dp(300f)
        params.x = candidateX.coerceIn(0, (width - popupWidth).coerceAtLeast(0))
        params.y = pParams.y.coerceIn(0, (height - estimatedHeight).coerceAtLeast(0))
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
            } catch (_: Throwable) {}
        }

        hud?.findViewById<TextView>(R.id.hud_icon)?.text = activeStreamIcon
        hud?.findViewById<TextView>(R.id.hud_text)?.text = text

        val hParams = handleParams
        val hudP = hudParams
        if (hParams != null && hudP != null) {
            val (width, height) = screenSize()
            val hudEstWidth = hud?.width?.takeIf { it > 0 } ?: dp(164f)
            val isLeft = hParams.x + handleWidth / 2 < width / 2
            val candidateX = if (isLeft) hParams.x + handleWidth + edgeMargin else hParams.x - hudEstWidth - edgeMargin
            hudP.x = candidateX.coerceIn(0, (width - hudEstWidth).coerceAtLeast(0))
            hudP.y = (hParams.y + handleHeight / 2 - dp(18f)).coerceIn(0, (height - dp(36f)).coerceAtLeast(0))
            hud?.let { updateSafely(it, hudP) }
        }
    }

    private fun scheduleHideHud() {
        handler.removeCallbacks(hideHudRunnable)
        handler.postDelayed(hideHudRunnable, 1200)
    }

    // ------------------------------------------------------------------
    // Real Hardware Volume Control, Read-Back Verification & DND Guard
    // ------------------------------------------------------------------

    private fun setPercentDuringDrag(percent: Float, showFeedbackHud: Boolean) {
        val stream = activeStream
        val max = streamMax(stream)
        if (max <= 0) return
        val targetStep = percentToStep(stream, percent)

        // Dispatch Binder IPC only when the discrete integer hardware step changes.
        if (targetStep != dragLastSentStep) {
            applyHardwareStep(targetStep, isDiscreteStep = false, showFeedbackHud = showFeedbackHud)
        } else {
            val livePct = currentPercent(stream)
            syncPanelUi(livePct)
            if (showFeedbackHud) {
                showHud("$activeStreamName: ${livePct.roundToInt()}%")
            }
        }
    }

    private fun applyHardwareStep(
        targetStep: Int,
        isDiscreteStep: Boolean,
        showFeedbackHud: Boolean,
    ) {
        val stream = activeStream
        val max = streamMax(stream)
        if (max <= 0) return
        val min = streamMin(stream)
        val clampedStep = targetStep.coerceIn(min, max)
        val before = audioManager.getStreamVolume(stream)
        var dndBlocked = false

        try {
            val wasMuted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioManager.isStreamMute(stream)
            if (clampedStep > 0 && wasMuted) {
                try {
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                } catch (_: SecurityException) {
                    dndBlocked = true
                }
            }
            if (!dndBlocked) {
                audioManager.setStreamVolume(stream, clampedStep, 0)
            }
        } catch (e: SecurityException) {
            dndBlocked = true
            Log.w(TAG, "DND prevented setting volume for $activeStreamName: ${e.message}")
        }

        // Always read back the true OS volume rather than echoing optimistic targets.
        var actual = audioManager.getStreamVolume(stream)
        if (!dndBlocked && stream == AudioManager.STREAM_MUSIC && clampedStep > before && actual < clampedStep) {
            try {
                audioManager.setStreamVolume(stream, clampedStep, AudioManager.FLAG_SHOW_UI)
                actual = audioManager.getStreamVolume(stream)
            } catch (_: SecurityException) {
                dndBlocked = true
            }
        }

        val actualPct = stepToPercent(actual, max)
        dragLastSentStep = if (dndBlocked) -1 else actual
        CrashReporter.logEvent(context, "VOL", "$activeStreamName before=$before req=$clampedStep actual=$actual dnd=$dndBlocked")
        syncAllUi(actualPct, immediate = isDiscreteStep || dndBlocked)

        if (dndBlocked) {
            showHud(context.getString(R.string.hud_dnd_blocked))
            scheduleHideHud()
        } else if (showFeedbackHud) {
            showHud("$activeStreamName: ${actualPct.roundToInt()}%")
            scheduleHideHud()
        }
    }

    private fun syncAllUi(percent: Float, immediate: Boolean) {
        syncPanelUi(percent)
        syncPopupHighlight()
        onVolumeChanged(
            percent.roundToInt(),
            activeStreamIndex,
            activeStreamName,
            activeStreamIcon,
            manualLockActive,
            immediate,
        )
    }

    private fun syncPanelUi(percent: Float) {
        panel?.let { p ->
            p.findViewById<TextView>(R.id.text_stream_icon)?.text = activeStreamIcon
            p.findViewById<TextView>(R.id.text_stream_label)?.text = activeStreamName
            p.findViewById<TextView>(R.id.text_percent)?.text = "${percent.roundToInt()}%"
            val trackFrame = p.findViewById<FrameLayout>(R.id.vertical_track_frame)
            val fill = p.findViewById<View>(R.id.vertical_progress_fill)
            updateTrackHeight(fill, trackFrame, percent)
        }
    }

    private fun updateTrackHeight(fill: View?, trackFrame: FrameLayout?, percent: Float) {
        if (fill == null) return
        val trackTotalHeight = trackFrame?.height?.takeIf { it > 0 } ?: trackHeightPx
        val targetHeight = (trackTotalHeight * (percent.coerceIn(0f, 100f) / 100f)).roundToInt()
        val layoutParams = fill.layoutParams
        layoutParams.height = targetHeight
        fill.layoutParams = layoutParams
    }

    private fun streamMin(stream: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return audioManager.getStreamMinVolume(stream).coerceAtLeast(0)
            } catch (_: Throwable) {}
        }
        return 0
    }

    private fun streamMax(stream: Int): Int = audioManager.getStreamMaxVolume(stream).coerceAtLeast(0)

    private fun stepToPercent(step: Int, max: Int): Float =
        if (max <= 0) 0f else (step * 100f / max).coerceIn(0f, 100f)

    private fun percentToStep(stream: Int, percent: Float): Int {
        val min = streamMin(stream)
        val max = streamMax(stream)
        if (max <= min) return max
        val span = max - min
        return (min + ((percent.coerceIn(0f, 100f) / 100f) * span).roundToInt()).coerceIn(min, max)
    }

    private fun currentPercent(stream: Int): Float =
        stepToPercent(audioManager.getStreamVolume(stream), streamMax(stream))

    // ------------------------------------------------------------------
    // Timers, Broadcast Receivers & Playback Callback
    // ------------------------------------------------------------------

    private fun resetAutoDismissPanel() {
        handler.removeCallbacks(autoDismissPanelRunnable)
        handler.postDelayed(autoDismissPanelRunnable, AUTO_DISMISS_PANEL_MS)
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
        if (receiversRegistered) return
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                registerPlaybackCallbackO()
            }
            receiversRegistered = true
        } catch (e: Throwable) {
            CrashReporter.recordError(context, "OverlayController.registerReceivers", e)
        }
    }

    private fun unregisterReceivers() {
        if (!receiversRegistered) return
        receiversRegistered = false
        try { context.unregisterReceiver(configReceiver) } catch (_: Exception) {}
        try { context.unregisterReceiver(volumeChangeReceiver) } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            unregisterPlaybackCallbackO()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun registerPlaybackCallbackO() {
        val cb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                val usages = configs.mapNotNull { it.audioAttributes?.usage }
                val detected = detectSmartStreamIndex(usages)
                if (detected == INDEX_CALL || detected == INDEX_RING) {
                    handler.removeCallbacks(clearManualLockRunnable)
                    manualLockActive = false
                    if (activeStreamIndex != detected) {
                        activeStreamIndex = detected
                        dragLastSentStep = -1
                        syncAllUi(currentPercent(activeStream), immediate = true)
                    }
                } else if (!manualLockActive && detected != activeStreamIndex) {
                    activeStreamIndex = detected
                    dragLastSentStep = -1
                    syncAllUi(currentPercent(activeStream), immediate = true)
                }
            }
        }
        playbackCallback = cb
        audioManager.registerAudioPlaybackCallback(cb, handler)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun unregisterPlaybackCallbackO() {
        val cb = playbackCallback as? AudioManager.AudioPlaybackCallback ?: return
        try {
            audioManager.unregisterAudioPlaybackCallback(cb)
        } catch (_: Exception) {}
        playbackCallback = null
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
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
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
                val point = android.graphics.Point()
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
        const val TAG = "OverlayController"
        const val INDEX_MEDIA = 0
        const val INDEX_RING = 1
        const val INDEX_NOTIFY = 2
        const val INDEX_CALL = 3
        const val INDEX_ALARM = 4
        const val MANUAL_LOCK_MS = 15_000L
        const val AUTO_DISMISS_PANEL_MS = 6_000L
    }
}
