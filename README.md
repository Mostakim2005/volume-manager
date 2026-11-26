# Volume Manager (Realme 6 / RMX2001)

An ultra-lightweight native volume-slider launcher for phones with broken physical volume buttons (built for Realme 6 running Realme UI 1.0/2.0 on Android 10/11). Uses an Android `AccessibilityService` (`TYPE_ACCESSIBILITY_OVERLAY`) with **zero persistent notifications** and **zero `"displaying over other apps"` banners**: tap the floating edge squircle anywhere to pop open Realme UI's own native system volume slider.

> **Looking for the full custom vertical slider & 5-profile notification edition?**
> That version (`v1.4.0`) is preserved on the [`archive/full-custom-panel`](../../tree/archive/full-custom-panel) branch.

## Features

- **Zero-notification floating button:** Hosted via `TYPE_ACCESSIBILITY_OVERLAY` with `canRetrieveWindowContent="false"` (reads no screen content, collects no data, and requires zero `INTERNET` permission).
- **One-tap system slider:** Tapping the button anywhere calls `AudioManager.adjustVolume(ADJUST_SAME, FLAG_SHOW_UI)` to open Realme's native multi-stream slider (`Media`, `Ring`, `Notify`, `Call`, `Alarm`).
- **Drag or hold to reposition:** Dragging or holding (~280 ms) moves the button freely and snaps smoothly to either screen edge, remembering its docked side and height across restarts.
- **Customizable & toggleable:** Instant in-app On/Off switch (`Show floating edge button`), 3 button sizes (`Compact 36dp`, `Standard 48dp`, `Large 56dp`), and live idle-opacity slider (`15%`–`100%`).
- **Quick Settings `Volume` tile:** Optional shade tile (`VolumeTileService`) for zero-battery slider access even when the floating button is hidden.
- **Offline diagnostics:** Built-in `CrashReporter` lets you copy or share an offline diagnostics log if Realme UI hits an issue.

## Build & verify

Requires **JDK 17** and the **Android SDK** (`compileSdk = 35`). The Gradle wrapper is included.

```powershell
.\gradlew assembleDebug        # build the APK
.\gradlew lintDebug            # Android lint
```

GitHub Actions CI (`.github/workflows/ci.yml`) runs `assembleDebug` + `lintDebug` on every push to `main`. Tagging a version (`v*`) triggers `.github/workflows/release.yml` to build a signed `arm64-v8a` APK.

## Install & Realme UI setup

1. Install the APK and open **Volume Manager**.
2. Tap **Enable Accessibility Overlay** -> find **Volume Manager** -> switch it **ON**.
3. Tap **Ignore battery optimizations** -> allow exemption.
4. *(Recommended for reboot survival on Realme UI)* Tap **Auto-Launch** and switch Volume Manager **ON**, and open **Battery Mgmt** to allow background activity.
5. *(Optional backup)* Edit Quick Settings in the shade and add the **Volume** tile.

## Project layout

```
app/src/main/java/com/volumemanager/
  VolumeManagerApp.kt                 Application entry installing offline crash logger
  MainActivity.kt                     Launcher switch, size/opacity controls, setup & diagnostics
  VolumeAccessibilityService.kt       OS-bound AccessibilityService hosting the floating button
  PanelLaunchActivity.kt              Translucent Quick Settings shade-collapse helper
  VolumeTileService.kt                Quick Settings tile triggering the native system slider
  overlay/FloatingButtonOverlay.kt    TYPE_ACCESSIBILITY_OVERLAY squircle, drag/snap, gesture exclusion
  util/SystemVolumeHelper.kt          Shared AudioManager FLAG_SHOW_UI trigger
  util/OverlayPrefs.kt                SharedPreferences for toggle, size, opacity, and dock edge
  util/Permissions.kt                 Accessibility service & battery optimization helpers
  util/RealmeSettings.kt              Accessibility & Realme ColorOS deep-links
  util/CrashReporter.kt               Offline crash log + diagnostics.log + bug report builder
app/src/main/res/                     DayNight XML layouts, drawables, accessibility XML, launcher icons
docs/                                 PRODUCT / ARCHITECTURE / DECISIONS_AND_TRAPS
```

## License

Licensed under PolyForm Noncommercial 1.0.0 (`LICENSE`). See `THIRD_PARTY_NOTICES.md` for AndroidX / Material dependency notices.
