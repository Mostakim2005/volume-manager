# Product Intent & Feature Map (`PRODUCT.md`)
> **1-Line Summary:** The permanent source of truth for what the Realme 6 volume utility must do and how it should feel, independent of the current implementation.

## 1. Core Vision & User Experience Intent
* **What This App Does (Plain English):** Provides a floating edge pill that docks to the left or right of the screen and expands into a compact stepped volume chip, staying alive across reboots and aggressive Realme UI battery management.
* **How It Must Feel to Use:** Always available but unobtrusive — a silent low-priority notification, no sound or heads-up pop, smooth snapping animation, and tactile discrete volume steps that are repeatable rather than fiddly.

## 2. Active Features (Do Not Break)

### [FEATURE: Edge-Docking Volume Handle] | Aliases: orange pill, sidebar overlay, drag handle, edge snap
> **1-Line Summary:** A 44 dp orange squircle on the screen edge: drag vertically to change volume in 10% steps, drag horizontally to move and snap, tap to toggle the chip.
* **User Intention (Non-Negotiable):** Mimic Realme's Smart Sidebar with a tiny, always-reachable volume control that works without opening the app.
* **Visual / UI Behavior:** Vertical-dominant movement adjusts the active stream; horizontal-dominant movement moves the handle and animates it to the nearest edge on release; a sub-slop tap toggles the chip.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/overlay/OverlayController.kt` -> `show`, `attachHandleTouch`, `snapToEdge`, `setPercent`
  * `app/src/main/res/layout/floating_handle.xml` -> `handle_root`, `handle_icon`

### [FEATURE: Compact Volume Chip] | Aliases: small overlay, bar only, movable chip, stream selector
> **1-Line Summary:** A tiny movable chip containing only a 10-step SeekBar and a tappable stream label that expands Media / Ring / Alarm / Notify. No +/- buttons.
* **User Intention (Non-Negotiable):** Keep the UI minimal — dragging is the primary volume gesture; the chip can be dragged anywhere on screen.
* **Visual / UI Behavior:** `SeekBar` max 10, each step = 10%; percentage shown beside the stream label; whole chip draggable except the bar and label; options expand inline when the label is tapped.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/overlay/OverlayController.kt` -> `openChip`, `attachChipDrag`, `setPercent`, `positionChip`
  * `app/src/main/res/layout/volume_chip.xml` -> `chip_seek`, `chip_stream`, `chip_options`

### [FEATURE: Persistent Foreground Service] | Aliases: notification, keep-alive, START_STICKY
> **1-Line Summary:** A silent foreground service with a low-importance notification that keeps the overlay process alive.
* **User Intention (Non-Negotiable):** The utility must survive memory pressure and not be killed by Realme UI's background restrictions.
* **Visual / UI Behavior:** `IMPORTANCE_LOW` channel (no sound, no badge), ongoing single-row notification showing active volume percentage with a progress strip; tapping the row toggles the chip.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/OverlayVolumeService.kt` -> `onCreate`, `onStartCommand`, `buildNotification`, `scheduleNotificationRefresh`
  * `app/src/main/res/layout/notification_volume.xml` -> `notif_status`, `notif_progress`, `notif_root`

### [FEATURE: Boot Auto-Start] | Aliases: reboot survival, BOOT_COMPLETED, auto-launch
> **1-Line Summary:** A boot receiver that restarts the service after reboot, deferring locked-boot starts until the user unlocks.
* **User Intention (Non-Negotiable):** The overlay must come back automatically after a restart without the user reopening the app.
* **Visual / UI Behavior:** No UI; listens for `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `QUICKBOOT_POWERON` and `MY_PACKAGE_REPLACED`.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/BootReceiver.kt` -> `onReceive`, `waitForUnlock`, `startSafely`

### [FEATURE: Realme UI Setup Launcher] | Aliases: permissions, auto-launch deep link, battery management
> **1-Line Summary:** A setup activity with one-tap buttons that deep-link into overlay, notification, battery and Realme startup screens.
* **User Intention (Non-Negotiable):** Surface the three Realme UI manual settings that are otherwise impossible to discover and that gate boot survival.
* **Visual / UI Behavior:** Scrollable list of Material buttons plus a live permission status block.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/MainActivity.kt` -> `onCreate`, `startOverlayService`, `updateStatus`
  * `app/src/main/java/com/volumemanager/util/RealmeSettings.kt` -> `openAutoLaunch`, `openAppBatteryManagement`
  * `app/src/main/java/com/volumemanager/util/Permissions.kt` -> `canDrawOverlays`, `isIgnoringBatteryOptimizations`, `hasNotificationPermission`

## 3. Planned Features & App-Merge Wishlist

### [WISHLIST: Hardware Volume Rocker Sync] | Aliases: hardware keys sync, system volume listener, VOLUME_CHANGED_ACTION
> **1-Line Summary:** Register a dynamic receiver for `android.media.VOLUME_CHANGED_ACTION` so external volume changes keep the overlay and notification in sync.
* **User Intention:** When adjusting volume via physical rocker buttons or Bluetooth headphones, the chip and notification must reflect the change instantly.
* **Target Integration Point:** `OverlayVolumeService -> onCreate` / `onDestroy`.

### [WISHLIST: Android 10+ Gesture Exclusion Rects] | Aliases: no back swipe conflict, exclusion rect, setSystemGestureExclusionRects
> **1-Line Summary:** Register the handle's bounding box with `View.setSystemGestureExclusionRects()` on API 29+ to avoid triggering Android's system Back gesture.
* **User Intention:** Swiping on or near the edge pill must not accidentally trigger system Back navigation.
* **Target Integration Point:** `OverlayController -> snapToEdge`, `updateSafely`.

### [WISHLIST: Smart Chip Auto-Dismiss & Idle Dimming] | Aliases: outside tap dismiss, idle opacity, auto collapse
> **1-Line Summary:** Auto-collapse the chip after 4 seconds of inactivity or on outside tap, and fade the edge handle to 30% alpha when idle.
* **User Intention:** Prevent the overlay from obstructing full-screen games or video content.
* **Target Integration Point:** `OverlayController -> openChip`, `attachHandleTouch`.

### [WISHLIST: Floating Volume HUD Capsule] | Aliases: volume tooltip, drag feedback, on-screen display
> **1-Line Summary:** Display a transient floating HUD tooltip next to the handle while dragging vertically to give real-time volume feedback.
* **User Intention:** Provide immediate visual confirmation of the new volume level without opening the full chip.
* **Target Integration Point:** `OverlayController -> attachHandleTouch`.

### [WISHLIST: Theme-Adaptive Dark Mode Notification] | Aliases: notification dark mode fix, contrast compliance
> **1-Line Summary:** Replace hardcoded `#1A1A24` text with theme-adaptive resources so notification text is clear in both Light and Dark themes.
* **User Intention:** Maintain WCAG AAA readability in dark mode without text disappearing into the shade background.
* **Target Integration Point:** `app/src/main/res/layout/notification_volume.xml`.

### [WISHLIST: LoudnessEnhancer Fine Attenuation] | Aliases: sub-step precision, decibel trim
> **1-Line Summary:** Use `LoudnessEnhancer` on session 0 to attenuate between the 15 hardware steps for finer media control.
* **User Intention:** Make 10% steps feel less coarse on music playback.
* **Target Integration Point:** `OverlayController -> setPercent`, behind a user setting.
