# Product Intent & Feature Map (`PRODUCT.md`)
> **1-Line Summary:** The permanent source of truth for what the ultra-lightweight Realme 6 native volume-slider launcher must do and how it should feel.

## 1. Core Vision & User Experience Intent
* **What This App Does (Plain English):** Provides an ultra-lightweight floating edge button hosted via Android's `AccessibilityService` (`TYPE_ACCESSIBILITY_OVERLAY`) plus a Quick Settings `Volume` tile, whose sole job is to summon Realme UI's own native system volume slider anywhere on the phone with zero notification shade clutter.
* **Target Handset Context (Critical Hardware Defect):** The target device's physical volume rocker buttons are completely broken and non-functional. Calling Realme UI's native `SystemUI` volume dialog ensures rock-solid volume adjustment across all apps, streams (Media, Ring, Notify, Call, Alarm), and DND states without background service freezes.
* **Legacy Custom Panel Branch:** The full custom vertical slider (`80×228 dp`), 5-stream popover, Smart Auto-Detect, and dual-state notification edition (`v1.4.0`) is preserved on the `archive/full-custom-panel` branch.
* **How It Must Feel to Use:** Invisible when idle, instant on tap — zero notification cards, zero `"displaying over other apps"` Android banners, smooth edge snapping, customizable size and idle opacity, and effortless drag/hold repositioning.

## 2. Active Features (Do Not Break)

### [FEATURE: Zero-Notification Accessibility Floating Button] | Aliases: edge handle, squircle, TYPE_ACCESSIBILITY_OVERLAY, native slider tap
> **1-Line Summary:** A resizable squircle edge button (`36`/`48`/`56 dp`) hosted by `VolumeAccessibilityService` that opens Realme UI's native system volume dialog on tap and moves smoothly on drag or hold.
* **User Intention (Non-Negotiable):** Works reliably outside the app across every screen without occupying notification shade space or triggering Android's `"displaying over other apps"` persistent banner.
* **Visual / UI Behavior:**
  * Single tap: immediately invokes `AudioManager.adjustVolume(ADJUST_SAME, FLAG_SHOW_UI)` to pop up Realme UI's native volume slider.
  * Drag or hold (~280 ms): highlights blue with haptic feedback, moves freely within screen bounds, and snaps smoothly to the left or right edge while persisting dock side and vertical ratio.
  * Idle dimming: fades smoothly to the user-configured idle opacity (`15%`–`100%`, default `35%`) after 3 seconds of inactivity.
  * Gesture exclusion: registers `setSystemGestureExclusionRects()` on Android 10+ so edge drags never collide with the system Back gesture.
* **Links:** `[SUBSYSTEM: Accessibility Overlay & Touch Controller]`, `[TRAP: SharedPreferences WeakReference Drops Anonymous Listeners]`, `[TRAP: Android 10+ Back Gesture Edge Collision]`
* **Verified By:** `.github/workflows/ci.yml` (`./gradlew assembleDebug lintDebug`)

### [FEATURE: Live Visibility Toggle, Size & Opacity Controls] | Aliases: button size, idle opacity, switch, Compact Standard Large, preferences
> **1-Line Summary:** Lets the user show/hide the floating button instantaneously via an in-app switch and tune its size (`36`/`48`/`56 dp`) and idle opacity (`15%`–`100%`) with live preview.
* **User Intention (Non-Negotiable):** Hide or resize the floating button in one tap inside the app without diving into Android Accessibility Settings every time.
* **Links:** `[SUBSYSTEM: Preferences & Main Launcher UI]`, `[TRAP: SharedPreferences WeakReference Drops Anonymous Listeners]`
* **Verified By:** `.github/workflows/ci.yml` (`./gradlew assembleDebug lintDebug`)

### [FEATURE: Quick Settings Volume Tile & In-App Slider Trigger] | Aliases: quick settings tile, VolumeTileService, FLAG_SHOW_UI, system slider
> **1-Line Summary:** Provides a zero-battery Quick Settings `Volume` tile and an in-app `Open System Volume Slider` button to summon Realme's native volume dialog even when the floating button is hidden.
* **Links:** `[SUBSYSTEM: Quick Settings Shade-Collapse Launcher]`
* **Verified By:** `.github/workflows/ci.yml` (`./gradlew assembleDebug lintDebug`)

### [FEATURE: Realme Background Survival & Honest Status] | Aliases: Auto-Launch, battery optimization, accessibility status, reboot survival
> **1-Line Summary:** Detects both Accessibility enablement and active binding state honestly in `MainActivity`, and links directly to Accessibility, Doze exemption, Realme Auto-Launch, and App Battery Management.
* **Links:** `[SUBSYSTEM: Preferences & Main Launcher UI]`, `[TRAP: Service Status Pill Inferring Running State From Settings Only]`
* **Verified By:** `.github/workflows/ci.yml` (`./gradlew assembleDebug lintDebug`)

### [FEATURE: Offline Crash Reporter & Bug Report Export] | Aliases: crash log, bug report, diagnostics, Copy Report, Share Report
> **1-Line Summary:** Records uncaught exceptions and runtime events offline in app-private storage (`filesDir`) with zero `INTERNET` permission and exports full diagnostics via Copy/Share.
* **Links:** `[SUBSYSTEM: Offline Diagnostics & Survival Helpers]`, `[TRAP: Google Play Protect Warning & Offline Trust]`
* **Verified By:** `.github/workflows/ci.yml` (`./gradlew assembleDebug lintDebug`)

## 3. Planned Features & App-Merge Wishlist

### [WISHLIST: Full Custom Vertical Panel Edition] | Aliases: custom panel, 5-stream selector, dual notification, archive branch
> **1-Line Summary:** Full custom `80×228 dp` vertical slider, Smart Auto-Detect, and dual-state 5-profile notification remain archived on `archive/full-custom-panel` if custom per-stream fine control is ever revisited.
* **Target Integration Point:** `archive/full-custom-panel` git branch (`v1.4.0`).

### [WISHLIST: LoudnessEnhancer Fine Attenuation] | Aliases: sub-step precision, decibel trim
> **1-Line Summary:** Use `LoudnessEnhancer` on session 0 to attenuate between hardware steps for finer media control.
* **Target Integration Point:** Separate audio effect module behind a user setting (if revisited beyond native SystemUI slider).
