# Volume Manager (Realme 6 / RMX2001)

A persistent, edge-docking volume utility for the Realme 6 running Realme UI 1.0/2.0
(Android 10/11). A 48×48 dp squircle handle snaps to either screen edge and expands into
a wide 80×228 dp vertical volume slider with Smart Auto-Detect and discrete hardware-verified
controls across **Media**, **Ring**, **Notify**, **Call**, and **Alarm** streams.

## Build & verify

Requires **JDK 17** and the **Android SDK** (compileSdk 35). The Gradle wrapper is included.

```powershell
.\gradlew assembleDebug        # build the APK
.\gradlew lintDebug            # Android lint
```

JDK/SDK are not installed in this scaffold's authoring environment, so local builds rely on
GitHub Actions CI (`assembleDebug` + `lintDebug`) on every push to `main`.

APK output: `app\build\outputs\apk\debug\app-debug.apk`

## CI / release

* **CI** (`.github/workflows/ci.yml`) runs `assembleDebug` + `lintDebug` on every
  push and PR to `main`.
* **Releases** (`.github/workflows/release.yml`) run when you push an annotated
  version tag, e.g.:

  ```powershell
  git tag -a v1.4.0 -m "Release 1.4.0"
  git push origin v1.4.0
  ```

  The workflow builds a **signed, arm64-v8a-only** APK and attaches it to the
  generated GitHub Release. By default it generates a fresh CI keystore for that
  run. For a stable signing identity, add repository secrets:
  `RELEASE_KEYSTORE_BASE64` (base64 of a JKS), `RELEASE_STORE_PASSWORD`,
  `RELEASE_KEY_PASSWORD`, `RELEASE_KEY_ALIAS`.

## Install & Realme UI setup

1. Install the APK and open the app.
2. Tap **Display over other apps** and enable it.
3. Tap **Notification permission** (Android 13+ only).
4. Tap **Ignore battery optimizations**.
5. Optional: Tap **Do Not Disturb access** if you want to adjust Ring/Notify volume while Silent or DND is active.
6. Tap **Auto-launch apps (Realme)** and switch your app **ON** — without this,
   Realme UI blocks the boot receiver after a restart.
7. Tap **App Battery Management (Realme)** and allow both *background activity*
   and *foreground activity*.
8. Tap **Start overlay service**.
9. Optional but recommended: open Recent Apps, use the app card's menu, and
   choose **Lock** so Realme UI keeps the process in memory.

## Interaction

- Drag the **orange squircle handle vertically** — adjusts the active stream in real OS hardware steps.
- Hold the **squircle handle (320 ms)** and drag anywhere — moves it and snaps to the nearest edge on release.
- **Tap** the squircle handle — toggles the `80×228 dp` vertical volume panel.
- Tap the **stream badge** at the top of the vertical panel — opens the 6-option stream selector (`Auto (Smart)`, `Media`, `Ring`, `Notify`, `Call`, `Alarm`).
- **Expanded notification:** tap any of the 5 profile buttons (`Media`, `Ring`, `Notify`, `Call`, `Alarm`) to lock onto that profile for 15 seconds, tap the status pill to return to `Auto`, use `[−]` / `[+]` for guaranteed `±1` hardware steps, or tap **🎚️ Panel** to collapse the shade and open the floating vertical panel.
- **Collapsed notification:** tap the stream pill to cycle streams, use `[−]` / `[+]` to step volume, or tap **🎚️** to open the floating panel.
- **Quick Settings Tile & Backup:** add the **Volume** Quick Settings tile from the shade (or tap **Open System Volume Slider** in the app) to summon Realme UI's native multi-stream volume dialog anytime, even if the overlay service is stopped.

## Project layout

```
app/src/main/java/com/volumemanager/
  VolumeManagerApp.kt             Application entry installing offline crash logger
  MainActivity.kt                 permission/setup launcher, status & bug report export
  BootReceiver.kt                 BOOT_COMPLETED / locked-boot deferral
  OverlayVolumeService.kt         foreground service + RemoteViews preflight + stock fallback
  PanelLaunchActivity.kt          translucent shade-collapse launcher (panel + system slider)
  VolumeTileService.kt            Quick Settings tile calling native system volume dialog
  overlay/OverlayController.kt    WindowManager overlays, smart detection, hardware read-back
  util/CrashReporter.kt           offline stack trace + diagnostics.log + bug report builder
  util/Permissions.kt             runtime/special permission helpers (incl. DND access)
  util/RealmeSettings.kt          OEM deep-links with stock fallbacks
app/src/main/res/                 DayNight layouts, drawables, values, launcher icons
docs/                             PRODUCT / ARCHITECTURE / DECISIONS_AND_TRAPS
```

## Notes & limitations

- Smart Auto-Detect checks `AudioManager.mode`, API 26+ active playback configurations, and `isMusicActive`, defaulting to **Media** whenever audio is idle/paused/buffering so YouTube volume works reliably. Manual profile picks stay locked for 15 seconds of inactivity (unless an incoming ringtone or active call takes priority).
- Adjusting **Ring** or **Notify** while Do Not Disturb is active requires granting optional **Do Not Disturb access**; otherwise Android blocks the change and the HUD displays `Blocked by DND` while keeping UI levels truthful.
- Realme UI component names for Auto-launch / App Battery Management differ between builds. `RealmeSettings` tries several candidates and falls back to App Details.
- This is licensed under PolyForm Noncommercial 1.0.0 — no commercial use.
