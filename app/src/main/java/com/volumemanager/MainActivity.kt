package com.volumemanager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.volumemanager.databinding.ActivityMainBinding
import com.volumemanager.util.CrashReporter
import com.volumemanager.util.OverlayPrefs
import com.volumemanager.util.Permissions
import com.volumemanager.util.RealmeSettings
import com.volumemanager.util.SystemVolumeHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isUpdatingUi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.switchFloatingButton.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            if (isChecked && !VolumeAccessibilityService.isConnected) {
                isUpdatingUi = true
                binding.switchFloatingButton.isChecked = false
                isUpdatingUi = false
                openAccessibilityWithHint()
                return@setOnCheckedChangeListener
            }
            OverlayPrefs.setButtonEnabled(this, isChecked)
            updateStatus()
        }

        binding.btnAccessibilityCta.setOnClickListener { openAccessibilityWithHint() }
        binding.btnSystemVolume.setOnClickListener { SystemVolumeHelper.showSystemVolumeUi(this) }

        binding.btnSizeCompact.setOnClickListener { selectButtonSize(OverlayPrefs.SIZE_COMPACT_DP) }
        binding.btnSizeStandard.setOnClickListener { selectButtonSize(OverlayPrefs.SIZE_STANDARD_DP) }
        binding.btnSizeLarge.setOnClickListener { selectButtonSize(OverlayPrefs.SIZE_LARGE_DP) }

        binding.seekIdleOpacity.max = OverlayPrefs.MAX_IDLE_OPACITY - OverlayPrefs.MIN_IDLE_OPACITY
        binding.seekIdleOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingUi) return
                val percent = OverlayPrefs.MIN_IDLE_OPACITY + progress
                binding.textIdleOpacityValue.text = getString(R.string.opacity_percent_format, percent)
                OverlayPrefs.setIdleOpacityPercent(this@MainActivity, percent)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.btnAccessibility.setOnClickListener { openAccessibilityWithHint() }
        binding.btnBatteryOptimization.setOnClickListener {
            try {
                startActivity(Permissions.batteryOptimizationIntent(this))
            } catch (_: Exception) {
                RealmeSettings.openAppBatteryManagement(this)
            }
        }
        binding.btnAutoLaunch.setOnClickListener { RealmeSettings.openAutoLaunch(this) }
        binding.btnBatteryManagement.setOnClickListener { RealmeSettings.openAppBatteryManagement(this) }

        binding.btnCopyCrash.setOnClickListener {
            val crash = CrashReporter.readCrashSummary(this)
            if (crash != null) {
                copyTextToClipboard("Volume Manager Crash", crash, R.string.toast_crash_copied)
            }
        }
        binding.btnClearCrash.setOnClickListener {
            CrashReporter.clearCrashReport(this)
            updateStatus()
        }
        binding.btnCopyReport.setOnClickListener {
            copyTextToClipboard(
                "Volume Manager Bug Report",
                CrashReporter.buildFullBugReport(this),
                R.string.toast_report_copied,
            )
        }
        binding.btnShareReport.setOnClickListener { shareBugReport() }
    }

    override fun onStart() {
        super.onStart()
        VolumeAccessibilityService.onConnectionChanged = {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    updateStatus()
                }
            }
        }
    }

    override fun onStop() {
        VolumeAccessibilityService.onConnectionChanged = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        try {
            updateStatus()
        } catch (e: Throwable) {
            CrashReporter.recordError(this, "MainActivity.onResume", e)
        }
    }

    private fun openAccessibilityWithHint() {
        Toast.makeText(this, R.string.toast_enable_accessibility, Toast.LENGTH_LONG).show()
        RealmeSettings.openAccessibilitySettings(this)
    }

    private fun selectButtonSize(sizeDp: Int) {
        OverlayPrefs.setSizeDp(this, sizeDp)
        updateSizeSelection(sizeDp)
    }

    private fun updateSizeSelection(selectedDp: Int) {
        val pairs = listOf(
            binding.btnSizeCompact to OverlayPrefs.SIZE_COMPACT_DP,
            binding.btnSizeStandard to OverlayPrefs.SIZE_STANDARD_DP,
            binding.btnSizeLarge to OverlayPrefs.SIZE_LARGE_DP,
        )
        val activeBg = ContextCompat.getColor(this, R.color.chip_active_bg)
        val activeText = ContextCompat.getColor(this, R.color.chip_active_text)
        val activeStroke = ContextCompat.getColor(this, R.color.primary)
        val normalBg = ContextCompat.getColor(this, R.color.card_background)
        val normalText = ContextCompat.getColor(this, R.color.text_secondary)
        val normalStroke = ContextCompat.getColor(this, R.color.card_stroke)

        for ((btn, size) in pairs) {
            styleSizeButton(
                button = btn,
                selected = size == selectedDp,
                activeBg = activeBg,
                activeText = activeText,
                activeStroke = activeStroke,
                normalBg = normalBg,
                normalText = normalText,
                normalStroke = normalStroke,
            )
        }
    }

    private fun styleSizeButton(
        button: MaterialButton,
        selected: Boolean,
        activeBg: Int,
        activeText: Int,
        activeStroke: Int,
        normalBg: Int,
        normalText: Int,
        normalStroke: Int,
    ) {
        button.backgroundTintList = ColorStateList.valueOf(if (selected) activeBg else normalBg)
        button.setTextColor(if (selected) activeText else normalText)
        button.strokeColor = ColorStateList.valueOf(if (selected) activeStroke else normalStroke)
    }

    private fun copyTextToClipboard(label: String, text: String, toastResId: Int) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
            Toast.makeText(this, toastResId, Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            CrashReporter.recordError(this, "MainActivity.copyTextToClipboard", e)
            Toast.makeText(this, "Clipboard failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareBugReport() {
        try {
            val report = CrashReporter.buildFullBugReport(this)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Volume Manager Bug Report")
                putExtra(Intent.EXTRA_TEXT, report)
            }
            startActivity(Intent.createChooser(sendIntent, getString(R.string.share_report_title)))
        } catch (e: Throwable) {
            CrashReporter.recordError(this, "MainActivity.shareBugReport", e)
            Toast.makeText(this, "Could not share report: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatus() {
        val accEnabled = Permissions.isAccessibilityServiceEnabled(this)
        val accConnected = VolumeAccessibilityService.isConnected
        val buttonEnabled = OverlayPrefs.isButtonEnabled(this)
        val isBatteryExempt = Permissions.isIgnoringBatteryOptimizations(this)
        val sizeDp = OverlayPrefs.getSizeDp(this)
        val opacityPercent = OverlayPrefs.getIdleOpacityPercent(this)

        isUpdatingUi = true
        binding.switchFloatingButton.isChecked = accConnected && buttonEnabled
        binding.seekIdleOpacity.progress = opacityPercent - OverlayPrefs.MIN_IDLE_OPACITY
        binding.textIdleOpacityValue.text = getString(R.string.opacity_percent_format, opacityPercent)
        isUpdatingUi = false

        updateSizeSelection(sizeDp)

        when {
            accConnected -> {
                binding.btnAccessibility.text = getString(R.string.perm_connected)
                binding.btnAccessibility.setTextColor(ContextCompat.getColor(this, R.color.status_pill_ok_text))
                binding.btnAccessibilityCta.visibility = View.GONE
            }
            accEnabled -> {
                binding.btnAccessibility.text = getString(R.string.perm_retoggle)
                binding.btnAccessibility.setTextColor(ContextCompat.getColor(this, R.color.status_pill_warn_text))
                binding.btnAccessibilityCta.visibility = View.VISIBLE
                binding.btnAccessibilityCta.text = getString(R.string.btn_accessibility_retoggle)
            }
            else -> {
                binding.btnAccessibility.text = getString(R.string.perm_setup)
                binding.btnAccessibility.setTextColor(ContextCompat.getColor(this, R.color.primary))
                binding.btnAccessibilityCta.visibility = View.VISIBLE
                binding.btnAccessibilityCta.text = getString(R.string.btn_accessibility_enable)
            }
        }

        if (isBatteryExempt) {
            binding.btnBatteryOptimization.text = getString(R.string.perm_exempt)
            binding.btnBatteryOptimization.setTextColor(ContextCompat.getColor(this, R.color.status_pill_ok_text))
        } else {
            binding.btnBatteryOptimization.text = getString(R.string.perm_setup)
            binding.btnBatteryOptimization.setTextColor(ContextCompat.getColor(this, R.color.primary))
        }

        when {
            accConnected && buttonEnabled -> applyStatusPill(
                textRes = R.string.status_running,
                textColorRes = R.color.status_pill_ok_text,
                bgColorRes = R.color.status_pill_ok_bg,
            )
            accConnected -> applyStatusPill(
                textRes = R.string.status_hidden,
                textColorRes = R.color.status_pill_warn_text,
                bgColorRes = R.color.status_pill_warn_bg,
            )
            accEnabled -> applyStatusPill(
                textRes = R.string.status_restart_needed,
                textColorRes = R.color.status_pill_warn_text,
                bgColorRes = R.color.status_pill_warn_bg,
            )
            else -> applyStatusPill(
                textRes = R.string.status_setup_required,
                textColorRes = R.color.status_pill_warn_text,
                bgColorRes = R.color.status_pill_warn_bg,
            )
        }

        fun mark(ok: Boolean) = if (ok) "ON" else "OFF"
        binding.statusText.text = listOf(
            "Accessibility enabled: ${mark(accEnabled)}",
            "Accessibility connected: ${mark(accConnected)}",
            "Floating button visible: ${mark(buttonEnabled)}",
            "Battery optimization ignored: ${mark(isBatteryExempt)}",
        ).joinToString(separator = "\n")

        val crashSummary = CrashReporter.readCrashSummary(this)
        if (crashSummary != null) {
            binding.cardCrashAlert.visibility = View.VISIBLE
            binding.textCrashPreview.text = crashSummary
        } else {
            binding.cardCrashAlert.visibility = View.GONE
        }
    }

    private fun applyStatusPill(textRes: Int, textColorRes: Int, bgColorRes: Int) {
        binding.statusPillService.text = getString(textRes)
        binding.statusPillService.setTextColor(ContextCompat.getColor(this, textColorRes))
        ViewCompat.setBackgroundTintList(
            binding.statusPillService,
            ColorStateList.valueOf(ContextCompat.getColor(this, bgColorRes)),
        )
    }
}
