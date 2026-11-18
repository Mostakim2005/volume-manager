# Volume Manager (Realme 6 / RMX2001)

A persistent, edge-docking volume utility for the Realme 6 running Realme UI 1.0/2.0
(Android 10/11). A draggable pill snaps to either screen edge; tapping it expands a
card with discrete, stepped sliders for Media, Ring, Alarm and Notification streams.

## Build & verify

Requires **JDK 17** and the **Android SDK** (compileSdk 35). The Gradle wrapper is included.

```powershell
.\gradlew assembleDebug        # build the APK
.\gradlew lintDebug            # Android lint
```

JDK/SDK are not installed in this scaffold's authoring environment, so the APK has
**not** been compiled here. Run the two commands above in Android Studio or a
machine with the toolchain before distributing.

APK output: `app\build\outputs\apk\debug\app-debug.apk`

## CI / release

* **CI** (`.github/workflows/ci.yml`) runs `assembleDebug` + `lintDebug` on every
  push and PR to `main`.
* **Releases** (`.github/workflows/release.yml`) run when you push an annotated
  version tag, e.g.:

  ```powershell
  git tag -a v1.0.0 -m "Release 1.0.0"
  git push origin v1.0.0
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
5. Tap **Auto-launch apps (Realme)** and switch your app **ON** — without this,
   Realme UI blocks the boot receiver after a restart.
6. Tap **App Battery Management (Realme)** and allow both *background activity*
   and *foreground activity*.
7. Tap **Start overlay service**.
8. Optional but recommended: open Recent Apps, use the app card's menu, and
   choose **Lock** so Realme UI keeps the process in memory.

## Interaction

- Drag the pill vertically — the Y position moves in discrete ~16 dp steps.
- Release — the pill animates to the nearest screen edge.
- Tap the pill — the volume card opens beside the docked edge.
- Drag a slider — it snaps to one of 10 discrete positions (0, 10, … 100%).
- Notification actions: media −, media +, toggle panel, stop service.

## Project layout

```
app/src/main/java/com/volumemanager/
  MainActivity.kt                 permission/setup launcher + service controls
  BootReceiver.kt                 BOOT_COMPLETED / locked-boot deferral
  OverlayVolumeService.kt         foreground service + notification
  overlay/OverlayController.kt    WindowManager windows, drag/snap, sliders
  util/Permissions.kt             runtime/special permission helpers
  util/RealmeSettings.kt          OEM deep-links with stock fallbacks
app/src/main/res/                 layouts, drawables, values, launcher icons
docs/                             PRODUCT / ARCHITECTURE / DECISIONS_AND_TRAPS
```

## Known limitations

- 10% slider steps do not map 1:1 onto Android's 15 hardware volume steps;
  some steps advance the hardware volume by 1, others by 2.
- Realme UI component names for Auto-launch / App Battery Management differ
  between builds. `RealmeSettings` tries several candidates and falls back to
  App Details, but you may need to navigate manually on your firmware version.
- This is licensed under PolyForm Noncommercial 1.0.0 — no commercial use.
