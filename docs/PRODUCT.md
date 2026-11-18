# Product Intent & Feature Map (`PRODUCT.md`)
> **1-Line Summary:** The permanent source of truth for what the Realme 6 volume utility must do and how it should feel, independent of the current implementation.

## 1. Core Vision & User Experience Intent
* **What This App Does (Plain English):** Provides a slim, flush edge pill that docks to the left or right of the screen and expands into a rotated vertical volume slider, staying alive across reboots and aggressive Realme UI battery management.
* **How It Must Feel to Use:** Always available but unobtrusive — an ultra-compact low-priority notification, no sound or heads-up pop, smooth snapping animation, tactile discrete volume steps, and direct UP/DOWN scrubbing that matches physical hardware rockers.

## 2. Active Features (Do Not Break)

### [FEATURE: Slim Vertical Edge Pill] | Aliases: edge handle, 28dp pill, flush handle, edge snap
> **1-Line Summary:** A 28×64 dp filled vertical pill docked flush to the screen edge: drag vertically for volume, tap to toggle the vertical panel, and hold (300 ms) to move anywhere.
* **User Intention (Non-Negotiable):** Mimic Realme's Smart Sidebar with a slim, flush volume trigger that never wastes horizontal screen space and eliminates accidental Android "Back" gesture conflicts.
* **Visual / UI Behavior:** 
  * Filled gradient orange pill with subtle up/down arrows and volume icon.
  * Direct vertical drag: dragging UP increases volume in 10% steps; dragging DOWN decreases volume.
  * Single tap: toggles the rotated vertical volume panel.
  * Long-press (300 ms): pulses blue, fires a haptic tick, and enters Reposition Mode for free movement.
  * Idle dimming: smoothly fades to 35% alpha after 3.5 seconds of inactivity.
  * Gesture exclusion: registers `setSystemGestureExclusionRects()` on Android 10+ so edge swipes never trigger system Back navigation.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/overlay/OverlayController.kt` -> `show`, `attachHandleTouch`, `snapToEdge`, `updateGestureExclusion`, `setPercent`
  * `app/src/main/res/layout/floating_handle.xml` -> `handle_root`, `handle_arrow_up`, `handle_icon`, `handle_arrow_down`

### [FEATURE: Rotated Vertical Volume Panel] | Aliases: vertical slider, stream popover, volume panel
> **1-Line Summary:** A 54×192 dp vertical volume panel with a top stream icon, a 104 dp vertical scrub track, and bottom percentage readout.
* **User Intention (Non-Negotiable):** Eliminate empty horizontal space. Dragging UP increases volume, dragging DOWN decreases volume, matching the phone's physical rockers.
* **Visual / UI Behavior:**
  * Positioned directly beside the edge pill with symmetrical, centered layout.
  * Top circular stream button (🎵, 🔔, 💬, ⏰) opens an inline stream picker pop-up.
  * Vertical track with filled gradient level and discrete 10% step notches.
  * Auto-dismisses after 4 seconds of inactivity or on outside tap.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/overlay/OverlayController.kt` -> `openPanel`, `closePanel`, `positionPanel`, `togglePopup`, `setPercent`
  * `app/src/main/res/layout/volume_vertical_panel.xml` -> `panel_root`, `btn_stream_icon`, `vertical_track_frame`, `vertical_progress_fill`, `text_percent`
  * `app/src/main/res/layout/stream_popup.xml` -> `stream_popup_root`, `pop_opt_media`, `pop_opt_ring`, `pop_opt_notify`, `pop_opt_alarm`

### [FEATURE: Hardware Rocker & System Audio Sync] | Aliases: hardware keys sync, system volume listener, VOLUME_CHANGED_ACTION
> **1-Line Summary:** Dynamic broadcast receiver for `android.media.VOLUME_CHANGED_ACTION` that keeps the overlay and notification in sync with hardware button presses.
* **User Intention (Non-Negotiable):** Adjusting volume via physical phone buttons or Bluetooth headphones must update the on-screen slider and notification immediately.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/overlay/OverlayController.kt` -> `volumeChangeReceiver`, `registerReceivers`, `unregisterReceivers`

### [FEATURE: Ultra-Compact Symmetrical Notification] | Aliases: green ball notification, minimal shade, theme adaptive
> **1-Line Summary:** A symmetrical, minimal-height notification with theme-adaptive contrast, a green ball/progress track, and stream cycling on header tap.
* **User Intention (Non-Negotiable):** Take as little vertical shade space as possible while remaining fully readable in both Light and Dark themes (WCAG AAA).
* **Visual / UI Behavior:**
  * Centered stream title (`Media volume: 60% ▾`) and centered progress bar.
  * Tapping the stream title cycles streams (Media -> Ring -> Notify -> Alarm).
  * Tapping the notification body toggles the vertical overlay panel.
  * Uses `notif_text_primary` with dedicated `values-night` resources for crisp contrast on dark backgrounds.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/OverlayVolumeService.kt` -> `buildNotification`, `onStartCommand`, `scheduleNotificationRefresh`
  * `app/src/main/res/layout/notification_volume.xml` -> `notif_root`, `notif_stream_header`, `notif_status`, `notif_progress`

### [FEATURE: Boot Auto-Start & DND Crash Protection] | Aliases: reboot survival, BOOT_COMPLETED, DND safe
> **1-Line Summary:** Restarts service safely after unlock on boot, and catches SecurityException so Do Not Disturb / Zen mode never crashes the service.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/BootReceiver.kt` -> `onReceive`, `startSafely`
  * `app/src/main/java/com/volumemanager/overlay/OverlayController.kt` -> `setPercent`

### [FEATURE: Realme UI Setup Launcher] | Aliases: permissions, auto-launch deep link, battery management
> **1-Line Summary:** A setup activity with one-tap buttons that deep-link into overlay, notification, battery and Realme startup screens.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/MainActivity.kt` -> `onCreate`, `startOverlayService`, `updateStatus`
  * `app/src/main/java/com/volumemanager/util/RealmeSettings.kt` -> `openAutoLaunch`, `openAppBatteryManagement`

## 3. Planned Features & App-Merge Wishlist

### [WISHLIST: LoudnessEnhancer Fine Attenuation] | Aliases: sub-step precision, decibel trim
> **1-Line Summary:** Use `LoudnessEnhancer` on session 0 to attenuate between the 15 hardware steps for finer media control.
* **Target Integration Point:** `OverlayController -> setPercent`, behind a user setting.
