# Product Intent & Feature Map (`PRODUCT.md`)
> **1-Line Summary:** The permanent source of truth for what the Realme 6 volume utility must do and how it should feel, independent of the current implementation.

## 1. Core Vision & User Experience Intent
* **What This App Does (Plain English):** Provides a floating edge pill that docks to the left or right of the screen and expands into a card of stepped volume sliders, staying alive across reboots and aggressive Realme UI battery management.
* **How It Must Feel to Use:** Always available but unobtrusive — a silent low-priority notification, no sound or heads-up pop, smooth snapping animation, and tactile discrete volume steps that are repeatable rather than fiddly.

## 2. Active Features (Do Not Break)

### [FEATURE: Edge-Docking Overlay Handle] | Aliases: floating pill, smart sidebar, drag handle, edge snap
> **1-Line Summary:** A draggable overlay pill that moves in discrete vertical steps and animates to the nearest screen edge on release.
* **User Intention (Non-Negotiable):** Mimic Realme's native Smart Sidebar so volume control is reachable with one thumb without leaving the current app.
* **Visual / UI Behavior:** 40×84 dp gradient pill; drag with `ACTION_MOVE`, snap Y to a ~16 dp grid, animate X to edge on `ACTION_UP`, tap (movement below slop) opens the panel.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/overlay/OverlayController.kt` -> `show`, `attachHandleTouchListener`, `snapToNearestEdge`, `positionPanel`
  * `app/src/main/res/layout/floating_handle.xml` -> `handle_root`, `handle_icon`

### [FEATURE: Stepped Volume Panel] | Aliases: sliders, volume card, discrete steps, interval control
> **1-Line Summary:** An expandable card with four Material sliders that snap to 10 discrete positions and map onto `AudioManager` streams.
* **User Intention (Non-Negotiable):** Replace fiddly 15-step hardware volume with predictable, repeatable percentage steps for Media, Ring, Alarm and Notification.
* **Visual / UI Behavior:** Card opens beside the docked edge; `stepSize = 10f`; values re-sync from the system on every open; close button and outside-tap collapse.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/overlay/OverlayController.kt` -> `openPanel`, `bindSliders`, `syncSlidersFromAudio`, `streamToPercent`
  * `app/src/main/res/layout/volume_panel.xml` -> `slider_music`, `slider_ring`, `slider_alarm`, `slider_notification`

### [FEATURE: Persistent Foreground Service] | Aliases: notification, keep-alive, START_STICKY
> **1-Line Summary:** A silent foreground service with a low-importance notification and quick volume actions that keeps the overlay alive.
* **User Intention (Non-Negotiable):** The utility must survive memory pressure and not be killed by Realme UI's background restrictions.
* **Visual / UI Behavior:** `IMPORTANCE_LOW` channel (no sound, no badge), ongoing notification with media −/+ , panel toggle and stop actions; tapping it opens MainActivity.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/OverlayVolumeService.kt` -> `onCreate`, `onStartCommand`, `startPersistentNotification`, `buildNotification`
  * `app/src/main/res/layout/notification_volume.xml` -> `notif_status`, `notif_media_down`, `notif_media_up`, `notif_toggle`, `notif_stop`

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

### [WISHLIST: LoudnessEnhancer Fine Attenuation] | Aliases: sub-step precision, decibel trim
> **1-Line Summary:** Use `LoudnessEnhancer` on session 0 to attenuate between the 15 hardware steps for finer media control.
* **User Intention:** Make 10% steps feel less coarse on music playback.
* **Target Integration Point:** `OverlayController -> bindSliders`, behind a user setting.

### [WISHLIST: Notification Compact Sliders] | Aliases: RemoteViews seek bar, shade controls
> **1-Line Summary:** Add actual `RemoteViews` progress bars to the notification for quick volume changes without expanding the overlay.
* **User Intention:** One-thumb control directly from the shade.
* **Target Integration Point:** `OverlayVolumeService -> buildNotification`.
