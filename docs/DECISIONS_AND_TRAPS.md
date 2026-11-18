# Decisions & Traps (`DECISIONS_AND_TRAPS.md`)
> **1-Line Summary:** Architectural pivots, rejected alternatives and non-obvious engineering landmines for the Realme 6 volume utility.

## 1. Decisions

### [DECISION: Layered XML Project Over Single Programmatic Service] | Aliases: XML vs programmatic, architecture choice
> **1-Line Summary:** Chose XML layouts plus a dedicated `OverlayController` instead of building all views inside the service.
* **What was rejected:** A single `OverlayVolumeService.kt` that inflates handle/panel with `GradientDrawable` and raw pixel sizes.
* **Why:** Compile-time layout validation, proper dp/theme/string resources, and a service that only handles lifecycle. The programmatic version is still the better teaching artifact.
* **Status:** `[DECIDED BY: HUMAN]` (chose "Go ahead with your approach" after the comparison).
* **Anchors:** `app/src/main/java/com/volumemanager/overlay/OverlayController.kt`, `app/src/main/res/layout/volume_chip.xml`.

### [DECISION: Namespace Only, No Manifest Package] | Aliases: AGP 8, namespace, build fix
> **1-Line Summary:** The module declares `namespace` in Gradle and omits the legacy `package` attribute from the manifest.
* **Why:** AGP 8 fails the build with "Setting the namespace via the package attribute is no longer supported". The evaluated reference code used the legacy attribute.
* **Status:** `[DECIDED BY: AGENT]`
* **Anchors:** `app/build.gradle.kts` -> `namespace`, `app/src/main/AndroidManifest.xml`.

### [DECISION: Defer Locked-Boot Start Until Unlock] | Aliases: LOCKED_BOOT_COMPLETED, direct boot, auto-start
> **1-Line Summary:** `LOCKED_BOOT_COMPLETED` defers service launch until the device is unlocked.
* **Why:** The service is not `directBootAware`, and `TYPE_APPLICATION_OVERLAY` cannot draw over the lock screen anyway. Immediate start attempts throw or are dropped by the OS.
* **Status:** `[DECIDED BY: AGENT]`
* **Anchors:** `BootReceiver -> onReceive`, `startSafely`.

### [DECISION: Guard Overlay Access Inside the Service Too] | Aliases: permission revoke, crash prevention
> **1-Line Summary:** `OverlayController -> show()` and `toggleChip()` both re-check `Settings.canDrawOverlays` before touching `WindowManager`.
* **Why:** The boot receiver and MainActivity check once, but a notification tap fired after the user revokes the permission would otherwise throw and kill the service.
* **Status:** `[DECIDED BY: AGENT]`
* **Anchors:** `OverlayController -> show`, `toggleChip`; `OverlayVolumeService -> onOverlayPermissionMissing`.

### [DECISION: Compact Bar-Only Overlay, Keep Both Surfaces] | Aliases: remove big panel, no plus/minus, sidebar + notification
> **1-Line Summary:** Replaced the large four-slider card with a small movable chip containing only a 10-step SeekBar, and kept both the sidebar overlay and the notification.
* **What was rejected:** Dropping `SYSTEM_ALERT_WINDOW` entirely (the user needs the sidebar overlay), and keeping the big card with −/+ buttons.
* **Why:** Device testing showed the big card obstructed app content and the tap-to-open path was unreliable. Plain framework views, guarded window calls and explicit gesture modes fix reliability.
* **Status:** `[DECIDED BY: HUMAN]`
* **Anchors:** `OverlayController -> attachHandleTouch`, `openChip`, `setPercent`; `app/src/main/res/layout/volume_chip.xml`.

## 2. Traps

```mermaid
flowchart TD
    T1[Raw px sizes on 440dpi] -->|fix| D1[dimens.xml + dp helper]
    T2[10% vs 15 hardware steps] -->|accept| D2[document uneven mapping]
    T3[Slider value feedback loop] -->|fix| D3[fromUser check in OnSeekBarChangeListener]
    T4[Receiver flag on API 33] -->|fix| D4[RECEIVER_EXPORTED]
    T5[Chip left behind after snap] -->|fix| D5[positionChip on every animator frame]
    T6[DND SecurityException crash] -->|fix| D6[try-catch around setStreamVolume]
    T7[Edge swipe triggers Back] -->|fix| D7[setSystemGestureExclusionRects]
    T8[Dark Mode notification invisible] -->|fix| D8[theme-adaptive text color]
    T9[Hardware keys out of sync] -->|fix| D9[VOLUME_CHANGED_ACTION receiver]
```

### [TRAP: Literal Percent Signs In Strings] | Aliases: StringFormatInvalid, lint, 10%
> **1-Line Summary:** A string containing `10%` or `0%` is treated as a format specifier by lint and fails the build.
* **Avoidance:** Mark such strings `formatted="false"` (or escape as `%%`); `notif_status` legitimately uses `%1$d%%` with an argument.
* **Anchors:** `app/src/main/res/values/strings.xml` -> `main_intro`, `zero_percent`, `notif_status`.

### [TRAP: Raw Pixel Dimensions] | Aliases: px vs dp, tiny handle, density
> **1-Line Summary:** Hardcoded `36 px` width on a ~440 dpi Realme 6 is about 6 dp — effectively invisible.
* **Avoidance:** All sizes come from `dimens.xml` or `OverlayController -> dp()`.

### [TRAP: Percentage Steps Are Not Hardware Steps] | Aliases: 10 percent, 15 steps, rounding
> **1-Line Summary:** Mapping 0–100% in 10% increments onto a 15-step stream makes some steps advance volume by 1 and others by 2.
* **Avoidance:** Documented in `README.md`; finer control is a wishlist item using `LoudnessEnhancer`.

### [TRAP: Slider Feedback Loop] | Aliases: onChangeListener, programmatic value
> **1-Line Summary:** Updating SeekBar progress programmatically re-triggers listeners and rewrites system volume if uncheck.
* **Avoidance:** Verify `fromUser == true` in `SeekBar.OnSeekBarChangeListener.onProgressChanged()`.

### [TRAP: Dynamic Receiver Flags on API 33+] | Aliases: RECEIVER_EXPORTED, SecurityException
> **1-Line Summary:** Registering a receiver for a system broadcast without an export flag throws on API 33+ when targeting 33.
* **Avoidance:** Use the three-argument `registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)` on TIRAMISU+.

### [TRAP: Chip Does Not Follow the Handle] | Aliases: snap animation, stale chip position
> **1-Line Summary:** Animating only the handle's X leaves the chip stranded if it is open when the pill snaps to the other edge.
* **Avoidance:** Call `positionChip()` from the `ValueAnimator` update listener.

### [TRAP: Realme UI Component Names Vary] | Aliases: ColorOS, StartupAppListActivity, deep link
> **1-Line Summary:** Auto-launch / battery components differ between Realme UI builds; a single hard-coded component often fails.
* **Avoidance:** `RealmeSettings` tries several candidates and falls back to `ACTION_APPLICATION_DETAILS_SETTINGS`.

### [TRAP: Do Not Disturb SecurityException Crash] | Aliases: DND, Zen mode, mute crash
> **1-Line Summary:** Scrubbing ring or notification streams while DND or Silent mode is active throws a SecurityException without NotificationPolicyAccess.
* **Avoidance:** Wrap `audioManager.setStreamVolume()` in a `try-catch` block catching `SecurityException` and log a warning without killing the service.

### [TRAP: Android 10+ Back Gesture Edge Collision] | Aliases: gesture navigation, back swipe, exclusion rect
> **1-Line Summary:** Swiping on an edge-docked handle on Android 10+ triggers the system "Back" gesture unless explicitly excluded.
* **Avoidance:** Call `View.setSystemGestureExclusionRects()` on the handle view with its current window bounds on API 29+.

### [TRAP: Notification Dark Mode Contrast Failure] | Aliases: dark mode invisible text, #1A1A24
> **1-Line Summary:** Hardcoding text colors to `#1A1A24` makes custom RemoteViews unreadable against dark notification shade backgrounds.
* **Avoidance:** Use dynamic theme-aware styling or provide night-qualified color resources (`values-night/colors.xml`).

### [TRAP: Hardware Volume Rocker Desync] | Aliases: physical keys, bluetooth volume, desync
> **1-Line Summary:** Adjusting volume via hardware buttons or headsets leaves the overlay chip and notification out of sync.
* **Avoidance:** Register a dynamic receiver for `android.media.VOLUME_CHANGED_ACTION` to refresh UI levels whenever external audio changes occur.

## 3. Verification Gate

### [VERIFY: CI And Release Verified On GitHub] | Aliases: assembleDebug, lintDebug, tag release, arm64
> **1-Line Summary:** The authoring machine had no JDK/SDK, so verification ran in GitHub Actions; CI passed and the `v1.0.0` tag produced a signed arm64-v8a APK.
* **Local limitation:** No JDK 17 / Android SDK / Gradle toolchain here, so `.\gradlew` was not run locally.
* **CI result:** `assembleDebug` + `lintDebug` passed on commit `6c773df` (run `44284243727`) after fixing the `DecelerateInterpolator` import and replacing the platform `Notification.Builder` with `NotificationCompat.Builder` for API 23 lint compliance.
* **Release result:** Tag `v1.0.0` triggered workflow run `44284766559`, which published `VolumeManager-arm64-v8a-v1.0.0.apk` (4,889,062 bytes) on the GitHub Release; the APK contains `META-INF/CERT.SF` and `META-INF/CERT.RSA` and is therefore signed.
* **Ongoing:** CI still gates every push and PR to `main`. Re-run `.\gradlew assembleDebug lintDebug` locally if the toolchain becomes available.
