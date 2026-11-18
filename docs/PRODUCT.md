# Product Intent & Feature Map (`PRODUCT.md`)
> **1-Line Summary:** The permanent source of truth for what the Realme 6 volume utility must do and how it should feel, independent of the current implementation.

## 1. Core Vision & User Experience Intent
* **What This App Does (Plain English):** Provides a slim, flush edge pill that docks to the left or right of the screen and expands into a rotated vertical volume slider, staying alive across reboots and aggressive Realme UI battery management.
* **Target Handset Context (Critical Hardware Defect):** The target device's physical volume rocker buttons are completely broken and non-functional. This app is not just a secondary convenience—it is the phone's primary, indispensable hardware volume controller.
* **Media Stream Primacy Rule:** On modern smartphones with broken hardware keys, 99.9% of user volume adjustments are for media playback (YouTube, Spotify, games, podcasts, video calls). The app must default to Media (`STREAM_MUSIC`) at all times, auto-revert to Media after stream adjustments, and strictly prevent background notification alerts from hijacking the active stream.
* **How It Must Feel to Use:** Always available but unobtrusive — an ultra-compact low-priority notification, no sound or heads-up pop, smooth snapping animation, tactile discrete volume steps, and direct UP/DOWN scrubbing that matches physical hardware rockers.

## 2. Active Features (Do Not Break)

### [FEATURE: Squircle Edge Handle & Zero-Lag Touch] | Aliases: edge handle, 48dp squircle, tactile handle, zero-lag scrub
> **1-Line Summary:** A 48×48 dp squircle handle with 16dp rounded corners: drag vertically for volume with IPC step throttling, tap to toggle vertical panel, and hold (320 ms) to move anywhere.
* **User Intention (Non-Negotiable):** Touch-friendly 48dp target for comfortable one-handed thumb use that never lags the phone and eliminates accidental Android "Back" gesture conflicts.
* **Visual / UI Behavior:** 
  * Filled gradient orange squircle with subtle up/down arrows and centered speaker icon.
  * Direct vertical drag: dragging UP increases volume in 10% steps; dragging DOWN decreases volume.
  * Zero-lag IPC throttling: dispatches Binder IPC calls to `AudioService` only when the rounded volume integer step changes, preventing 90Hz/120Hz touch loop flooding.
  * Native touch slop: calibrated via `ViewConfiguration.scaledTouchSlop` so taps reliably toggle the vertical panel without being swallowed as moves.
  * Single tap: toggles the rotated vertical volume panel.
  * Long-press (320 ms): pulses blue, fires a haptic tick, and enters Reposition Mode for free movement.
  * Idle dimming: smoothly fades to 35% alpha after 3.5 seconds of inactivity.
  * Gesture exclusion: registers `setSystemGestureExclusionRects()` on Android 10+ so edge swipes never trigger system Back navigation.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/overlay/OverlayController.kt` -> `show`, `attachHandleTouch`, `snapToEdge`, `updateGestureExclusion`, `setPercent`
  * `app/src/main/res/layout/floating_handle.xml` -> `handle_root`, `handle_arrow_up`, `handle_icon`, `handle_arrow_down`
  * `app/src/main/res/values/dimens.xml` -> `handle_width`, `handle_height`, `handle_corner_radius`

### [FEATURE: Rotated Vertical Volume Panel] | Aliases: vertical slider, stream popover, volume panel
> **1-Line Summary:** A 54×192 dp vertical volume panel with full-surface touch scrubbing, auto-focus on active media, and bottom percentage readout.
* **User Intention (Non-Negotiable):** Dragging UP increases volume, dragging DOWN decreases volume. Automatically targets Media volume when playing videos/music.
* **Visual / UI Behavior:**
  * Full-surface touch capture: touching or dragging anywhere vertically across the panel scrubs the volume smoothly.
  * Auto-focus Media: if video/music is active (e.g. YouTube, Spotify), automatically focuses `STREAM_MUSIC`.
  * Continuous 1-to-1 hardware step mapping without dead zones.
  * Top circular stream button (🎵, 🔔, 💬, ⏰) opens an inline stream picker pop-up.
  * Theme-adaptive contrast for text readout in both Light and Dark modes.
  * Auto-dismisses after 4 seconds of inactivity or on outside tap.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/overlay/OverlayController.kt` -> `openPanel`, `closePanel`, `positionPanel`, `togglePopup`, `setPercent`, `autoFocusMediaIfActive`
  * `app/src/main/res/layout/volume_vertical_panel.xml` -> `panel_root`, `btn_stream_icon`, `vertical_track_frame`, `vertical_progress_fill`, `text_percent`
  * `app/src/main/res/layout/stream_popup.xml` -> `stream_popup_root`, `pop_opt_media`, `pop_opt_ring`, `pop_opt_notify`, `pop_opt_alarm`

### [FEATURE: Hardware Rocker & System Audio Sync] | Aliases: hardware keys sync, system volume listener, VOLUME_CHANGED_ACTION
> **1-Line Summary:** Dynamic broadcast receiver for `android.media.VOLUME_CHANGED_ACTION` that keeps the overlay and notification in sync with hardware button presses.
* **User Intention (Non-Negotiable):** Adjusting volume via physical phone buttons or Bluetooth headphones must update the on-screen slider and notification immediately.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/overlay/OverlayController.kt` -> `volumeChangeReceiver`, `registerReceivers`, `unregisterReceivers`

### [FEATURE: Spacious Interactive Notification & Step Buttons] | Aliases: notification volume, shade buttons, volume buttons, minus plus
> **1-Line Summary:** A spacious, interactive notification (~68 dp card height) with dedicated [−] and [+] volume step buttons, isolated stream switcher pill, and 10dp progress track.
* **User Intention (Non-Negotiable):** Eliminate un-draggable slider frustration and accidental stream cycling by providing clear, large [−] and [+] touch buttons and a distinct stream pill.
* **Visual / UI Behavior:**
  * Top row: Demarcated stream pill (`[ 🎵 Media volume: 60% ▾ ]`) on the left; tapping cycles streams. `[ 🎚️ Expand ]` button on the right; tapping opens the on-screen vertical panel.
  * Bottom row: Dedicated 34×34 dp `[ − ]` button, centered 10dp thick progress track (`seek_progress_green`), and 34×34 dp `[ + ]` button.
  * Tapping `[ − ]` steps active volume down by 10%; tapping `[ + ]` steps volume up by 10%.
  * Isolated touch targets guarantee that volume changes never accidentally toggle or cycle streams.
  * Uses `notif_btn_bg` and `notif_btn_text` with dedicated `values-night` resources for crisp contrast in both Light and Dark modes (WCAG AAA).
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/OverlayVolumeService.kt` -> `buildNotification`, `onStartCommand`, `ACTION_VOLUME_UP`, `ACTION_VOLUME_DOWN`
  * `app/src/main/java/com/volumemanager/overlay/OverlayController.kt` -> `stepVolume`
  * `app/src/main/res/layout/notification_volume.xml` -> `btn_notif_down`, `btn_notif_up`, `notif_progress`, `notif_stream_header`, `notif_btn_panel`

### [FEATURE: Boot Auto-Start & DND Crash Protection] | Aliases: reboot survival, BOOT_COMPLETED, DND safe
> **1-Line Summary:** Restarts service safely after unlock on boot, and catches SecurityException so Do Not Disturb / Zen mode never crashes the service.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/BootReceiver.kt` -> `onReceive`, `startSafely`
  * `app/src/main/java/com/volumemanager/overlay/OverlayController.kt` -> `setPercent`

### [FEATURE: Modern Card Setup Activity & System Mute Tip] | Aliases: setup activity, permissions card, realme setup, system overlay mute
> **1-Line Summary:** A clean Material card activity featuring Prism Speaker branding, real-time permission status pills, Realme autostart deep-links, and instructions to silence Android System overlay notices.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/MainActivity.kt` -> `onCreate`, `startOverlayService`, `updateStatus`
  * `app/src/main/res/layout/activity_main.xml` -> `statusPillService`, `btn_start`, `btn_stop`, `btn_overlay`, `btn_notifications`, `btn_battery_optimization`
  * `app/src/main/java/com/volumemanager/util/RealmeSettings.kt` -> `openAutoLaunch`, `openAppBatteryManagement`

## 3. Planned Features & App-Merge Wishlist

### [WISHLIST: LoudnessEnhancer Fine Attenuation] | Aliases: sub-step precision, decibel trim
> **1-Line Summary:** Use `LoudnessEnhancer` on session 0 to attenuate between the 15 hardware steps for finer media control.
* **Target Integration Point:** `OverlayController -> setPercent`, behind a user setting.
