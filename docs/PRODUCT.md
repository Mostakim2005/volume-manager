# Product Intent & Feature Map (`PRODUCT.md`)
> **1-Line Summary:** The permanent source of truth for what the Realme 6 volume utility must do and how it should feel, independent of the current implementation.

## 1. Core Vision & User Experience Intent
* **What This App Does (Plain English):** Provides a slim, flush edge pill that docks to the left or right of the screen and expands into a wide vertical volume slider, staying alive across reboots and aggressive Realme UI battery management.
* **Target Handset Context (Critical Hardware Defect):** The target device's physical volume rocker buttons are completely broken and non-functional. This app is not just a secondary convenience—it is the phone's primary, indispensable hardware volume controller.
* **Media Stream Primacy & Smart Switching Rule:** On modern smartphones with broken hardware keys, most volume adjustments are for media playback (YouTube, Spotify, games, podcasts), while calls, ringtones, notifications, and alarms still need quick access. The app must default to Media (`STREAM_MUSIC`) when idle or paused/buffering, switch automatically during calls (`STREAM_VOICE_CALL`), ringtones (`STREAM_RING`), or alarms (`STREAM_ALARM`), and honor manual profile choices via a 15-second lock before returning to Smart Auto.
* **Truthful Hardware Feedback Rule:** Every UI readout, slider bar, and notification progress bar must always reflect the real `AudioManager.getStreamVolume()` read-back rather than optimistic targets, and must warn clearly if Do Not Disturb blocks changes.
* **How It Must Feel to Use:** Always available but unobtrusive — an ultra-compact low-priority notification that expands to full 5-profile controls, smooth snapping animation, guaranteed `±1` hardware steps, and direct UP/DOWN scrubbing that matches physical hardware rockers.

## 2. Active Features (Do Not Break)

### [FEATURE: Squircle Edge Handle & Zero-Lag Touch] | Aliases: edge handle, 48dp squircle, tactile handle, zero-lag scrub
> **1-Line Summary:** A 48×48 dp squircle handle with 16dp rounded corners: drag vertically for volume with IPC step throttling, tap to toggle vertical panel, and hold (320 ms) to move anywhere.
* **User Intention (Non-Negotiable):** Touch-friendly 48dp target for comfortable one-handed thumb use that never lags the phone and eliminates accidental Android "Back" gesture conflicts.
* **Visual / UI Behavior:**
  * Filled gradient orange squircle with subtle up/down arrows and centered speaker icon.
  * Direct vertical drag: dragging UP increases volume; dragging DOWN decreases volume, synced to live hardware steps.
  * Zero-lag IPC throttling: dispatches Binder IPC calls to `AudioService` only when the discrete hardware integer step changes, preventing 90Hz/120Hz touch loop flooding.
  * Native touch slop: calibrated via `ViewConfiguration.scaledTouchSlop` so taps reliably toggle the vertical panel without being swallowed as moves.
  * Single tap: toggles the vertical volume panel.
  * Long-press (320 ms): pulses blue, fires a haptic tick, and enters Reposition Mode for free movement.
  * Idle dimming: smoothly fades to 35% alpha after 3.5 seconds of inactivity.
  * Gesture exclusion: registers `setSystemGestureExclusionRects()` on Android 10+ so edge swipes never trigger system Back navigation.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/overlay/OverlayController.kt` -> `show`, `attachHandleTouch`, `snapToEdge`, `updateGestureExclusion`, `setPercentDuringDrag`, `applyHardwareStep`
  * `app/src/main/res/layout/floating_handle.xml` -> `handle_root`, `handle_arrow_up`, `handle_icon`, `handle_arrow_down`
  * `app/src/main/res/values/dimens.xml` -> `handle_width`, `handle_height`, `handle_corner_radius`

### [FEATURE: Wide Vertical Volume Panel & 5-Stream Popover] | Aliases: vertical slider, stream popover, volume panel, smart auto, call stream
> **1-Line Summary:** An 80×228 dp vertical volume panel with a 48×124 dp touch track, stream badge pill, 6-option popover (`Auto` + `Media`/`Ring`/`Notify`/`Call`/`Alarm`), and live OS volume read-back.
* **User Intention (Non-Negotiable):** Wide thumb-friendly vertical scrubbing where dragging UP increases volume, dragging DOWN decreases volume, smart detection tracks calls/ringtones/media automatically, and manual overrides stick reliably for 15 seconds.
* **Visual / UI Behavior:**
  * Full-surface touch capture: touching or dragging anywhere vertically across the panel scrubs the volume smoothly.
  * Smart detection: checks `audioManager.mode`, API 26+ `AudioPlaybackCallback` / `activePlaybackConfigurations`, and `isMusicActive`, defaulting to `STREAM_MUSIC` when idle/paused/buffering.
  * 15-second manual lock: picking a stream manually locks that choice for 15 seconds of inactivity (extended on continued volume interactions), unless an active call or ringtone takes priority.
  * Top stream badge (`btn_stream_icon` showing icon + stream name) opens the `164 dp` popup with `44 dp` rows for `Auto (Smart)`, `Media`, `Ring`, `Notify`, `Call`, and `Alarm`, highlighting the active selection.
  * Theme-adaptive contrast across Light and Dark modes (`overlay_surface_bg`, `overlay_option_bg`, `overlay_option_active_bg`, `text_primary`).
  * Auto-dismisses after 6 seconds of inactivity or on outside tap (`FLAG_WATCH_OUTSIDE_TOUCH`).
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/overlay/OverlayController.kt` -> `openPanel`, `openPanelFromNotification`, `closePanel`, `positionPanel`, `togglePopup`, `openPopup`, `syncPopupHighlight`, `detectSmartStreamIndex`, `selectStreamManual`, `enableAutoMode`, `stepVolume`, `applyHardwareStep`
  * `app/src/main/res/layout/volume_vertical_panel.xml` -> `panel_root`, `btn_stream_icon`, `text_stream_icon`, `text_stream_label`, `vertical_track_frame`, `vertical_progress_fill`, `text_percent`
  * `app/src/main/res/layout/stream_popup.xml` -> `stream_popup_root`, `pop_opt_auto`, `pop_opt_media`, `pop_opt_ring`, `pop_opt_notify`, `pop_opt_call`, `pop_opt_alarm`

### [FEATURE: Hardware Rocker & System Audio Sync] | Aliases: hardware keys sync, system volume listener, VOLUME_CHANGED_ACTION
> **1-Line Summary:** Dynamic broadcast receiver for `android.media.VOLUME_CHANGED_ACTION` and API 26+ `AudioPlaybackCallback` keeping the overlay and notification in sync with OS audio state.
* **User Intention (Non-Negotiable):** External volume changes (headsets, Bluetooth, OS UI) and playback state transitions (calls, media, alarms) must update the active UI immediately without hijacking to background notification streams.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/overlay/OverlayController.kt` -> `volumeChangeReceiver`, `registerReceivers`, `unregisterReceivers`, `registerPlaybackCallbackO`, `unregisterPlaybackCallbackO`

### [FEATURE: Multi-Window, Split-Screen & PiP Overlay Resilience] | Aliases: split screen, multi window, youtube pip, 3-finger swipe up
> **1-Line Summary:** Fully resilient window positioning and lifecycle management that survives Realme 3-finger split-screen and floating PiP overlays without crashing.
* **User Intention (Non-Negotiable):** Volume overlay must stay alive and draggable when running split-screen multitasking or watching floating YouTube videos, and never crash when opening the app during multi-window.
* **Visual / UI Behavior:**
  * Uses `FLAG_LAYOUT_IN_SCREEN` so overlay coordinates remain global across multi-window frames.
  * Robust `DisplayMetrics` resolution with zero-crash fallback prevents Android 11 service `currentWindowMetrics` crashes.
  * Strict boundary clamping keeps handle, vertical panel, popup, and HUD inside visible screen bounds.
  * Declares `resizeableActivity="true"` and `configChanges` on `MainActivity` so orientation and split-screen mode changes never destroy or kill the activity.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/overlay/OverlayController.kt` -> `screenSize`, `overlayParams`, `configReceiver`, `positionPanel`, `positionPopup`, `snapToEdge`
  * `app/src/main/AndroidManifest.xml` -> `MainActivity` (`resizeableActivity`, `configChanges`)

### [FEATURE: Dual-State Decorated Notification & Shade Collapse Launch] | Aliases: notification volume, shade buttons, compact expanded notification, 5 profiles
> **1-Line Summary:** A `DecoratedCustomViewStyle` notification with a compact (`<= 48 dp`) bar and a rich expanded card featuring 5 direct profile chips, `44×44 dp` `[−]`/`[+]` step buttons, and clean shade-collapse launch.
* **User Intention (Non-Negotiable):** Fit cleanly in both collapsed and expanded shade slots with zero clipping, allow 1-tap switching across all 5 audio profiles without fighting Auto-Detect, guarantee every `[−]`/`[+]` tap moves volume by at least 1 hardware step, and collapse the notification shade before opening the floating panel.
* **Visual / UI Behavior:**
  * Collapsed view (`notification_volume_compact.xml`): stream cycle pill, `36×36 dp` `[−]`/`[+]` 1-step buttons, progress bar, and `[🎚️]` panel launcher.
  * Expanded view (`notification_volume_expanded.xml`): top status pill (`🎵 Media • 60% (Auto)` — tap to return to Auto) + `[🎚️ Panel]` launcher, 5 direct stream selector chips (`Media`, `Ring`, `Notify`, `Call`, `Alarm`) with highlighted active chip, and `44×44 dp` `[−]`/`[+]` step buttons around a `14 dp` progress bar.
  * Strict `@RemoteView` compliance: uses only `LinearLayout`, `RelativeLayout`, `FrameLayout`, `TextView`, and `ProgressBar` (no raw `<View>` elements).
  * Panel launcher uses `PanelLaunchActivity` (`Theme.VolumeManager.Transparent`, empty `taskAffinity`) so the shade collapses natively without pulling `MainActivity` over foreground apps or using restricted `ACTION_CLOSE_SYSTEM_DIALOGS`.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/OverlayVolumeService.kt` -> `safeBuildNotification`, `buildCustomNotification`, `onStartCommand`, `panelActivityPendingIntent`, `servicePendingIntent`
  * `app/src/main/java/com/volumemanager/PanelLaunchActivity.kt` -> `onCreate`, `suppressTransition`, `showSystemVolumeUi`
  * `app/src/main/res/layout/notification_volume_compact.xml`, `app/src/main/res/layout/notification_volume_expanded.xml`

### [FEATURE: Quick Settings Volume Tile & Native System Slider Backup] | Aliases: quick settings tile, VolumeTileService, FLAG_SHOW_UI, system slider
> **1-Line Summary:** Provides a Quick Settings `Volume` tile and an in-app `Open System Volume Slider` button that summon Realme UI's native multi-stream volume dialog on demand, even when the overlay service is stopped.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/VolumeTileService.kt` -> `onStartListening`, `onClick`
  * `app/src/main/java/com/volumemanager/PanelLaunchActivity.kt` -> `onCreate`, `showSystemVolumeUi`
  * `app/src/main/java/com/volumemanager/MainActivity.kt` -> `onCreate` (`btnSystemVolume`)
  * `app/src/main/AndroidManifest.xml` -> `VolumeTileService` (`BIND_QUICK_SETTINGS_TILE`)

### [FEATURE: Boot Auto-Start, DND Policy Access & Honest Service Status] | Aliases: reboot survival, BOOT_COMPLETED, DND safe, service status
> **1-Line Summary:** Restarts service safely after unlock on boot, offers optional Notification Policy Access for Ring/Notify in DND, reports honest DND blocks, and reflects real running state in `MainActivity`.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/BootReceiver.kt` -> `onReceive`, `startSafely`
  * `app/src/main/java/com/volumemanager/MainActivity.kt` -> `onStart`, `onStop`, `updateStatus`, `startOverlayService`, `stopOverlayService`
  * `app/src/main/java/com/volumemanager/util/Permissions.kt` -> `hasNotificationPolicyAccess`, `notificationPolicyAccessIntent`
  * `app/src/main/java/com/volumemanager/util/RealmeSettings.kt` -> `openAutoLaunch`, `openAppBatteryManagement`
  * `app/src/main/java/com/volumemanager/OverlayVolumeService.kt` -> `isRunning`, `onRunningStateChanged`

### [FEATURE: Offline Crash Reporter, Pre-Flight RemoteViews Guard & Bug Report Export] | Aliases: crash log, bug report, RemoteViews fallback, diagnostics
> **1-Line Summary:** Captures uncaught exceptions and runtime errors offline in app-private storage, pre-validates custom notifications with stock fallback, and provides Copy/Share bug report controls in `MainActivity`.
* **User Intention (Non-Negotiable):** Never crash-loop silently on OEM SystemUI quirks; surface exact stack traces and live audio state offline so issues can be copied and diagnosed immediately.
* **Code Anchors:**
  * `app/src/main/java/com/volumemanager/VolumeManagerApp.kt` -> `onCreate`
  * `app/src/main/java/com/volumemanager/util/CrashReporter.kt` -> `install`, `recordError`, `logEvent`, `readCrashSummary`, `clearCrashReport`, `buildFullBugReport`
  * `app/src/main/java/com/volumemanager/OverlayVolumeService.kt` -> `safeBuildNotification`, `validateRemoteViews`, `buildStockFallbackNotification`, `usingStockNotificationFallback`
  * `app/src/main/java/com/volumemanager/MainActivity.kt` -> `copyTextToClipboard`, `shareBugReport`, `updateStatus`
  * `app/src/main/res/layout/activity_main.xml` -> `card_crash_alert`, `card_diagnostics`, `btn_copy_crash`, `btn_clear_crash`, `btn_copy_report`, `btn_share_report`

## 3. Planned Features & App-Merge Wishlist

### [WISHLIST: LoudnessEnhancer Fine Attenuation] | Aliases: sub-step precision, decibel trim
> **1-Line Summary:** Use `LoudnessEnhancer` on session 0 to attenuate between the 15 hardware steps for finer media control.
* **Target Integration Point:** `OverlayController -> applyHardwareStep`, behind a user setting.
