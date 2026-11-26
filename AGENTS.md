# Project Operating Instructions (`AGENTS.md`)
> **Notice:** This project inherits the user persona, confirmation gates, and documentation blueprints from the global master `AGENTS.md`. Do not duplicate those rules here.

## 1. Project Tech Stack & Environment
* **Language / Build:** Kotlin 1.9.24, Gradle 8.7 (wrapper included), Android Gradle Plugin 8.5.2.
* **SDK levels:** `compileSdk = 35`, `minSdk = 23`, `targetSdk = 33` (tuned for Realme 6 Android 10/11).
* **UI:** XML layouts + ViewBinding, Material Components 1.12.0, AppCompat 1.7.0.
* **Target device:** Realme 6 (RMX2001), Realme UI 1.0/2.0 on Android 10/11.
* **Toolchain requirement:** JDK 17 + Android SDK platform 35. Not available in the authoring environment; GitHub Actions (`ci.yml`) is the verification runner.

## 2. Mandatory Verification & Build Commands
* **Build:** `.\gradlew assembleDebug`
* **Lint:** `.\gradlew lintDebug`
* **Verification gate:** Do not tag or distribute until both commands pass in CI or on a machine with the toolchain.

## 3. Repository Directory Structure & Conventions
* `app/src/main/java/com/volumemanager/` — `MainActivity`, `VolumeAccessibilityService`, `VolumeTileService`, `PanelLaunchActivity`, `VolumeManagerApp`.
* `app/src/main/java/com/volumemanager/overlay/` — `FloatingButtonOverlay` (`TYPE_ACCESSIBILITY_OVERLAY` window & touch handling).
* `app/src/main/java/com/volumemanager/util/` — `SystemVolumeHelper`, `OverlayPrefs`, `Permissions`, `RealmeSettings`, `CrashReporter`.
* `app/src/main/res/` — DayNight XML layouts, drawables, `accessibility_service_config.xml`, values, launcher icons.
* `docs/` — `PRODUCT.md`, `ARCHITECTURE.md`, `DECISIONS_AND_TRAPS.md` (native Mermaid, no HTML guides).
* **Legacy branch:** Full custom vertical slider edition (`v1.4.0`) lives on `archive/full-custom-panel`.

## 4. Repository-Specific Trap Pointers
* `[TRAP: SharedPreferences WeakReference Drops Anonymous Listeners]` — always keep `prefListener` as a strong member field.
* `[TRAP: AudioManager.adjustVolume Cannot Programmatically Dismiss SystemUI Slider]` — tap opens Realme's slider; outside tap/timeout closes it.
* `[TRAP: Service Status Pill Inferring Running State From Settings Only]` — combine `isAccessibilityServiceEnabled` and `VolumeAccessibilityService.isConnected`.
* `[TRAP: Android 11 Service WindowMetrics Crash In Multi-Window / PiP]` — resolve `DisplayMetrics` first in `screenSize()`.
* `[TRAP: Dynamic Receiver Flags on API 33+]` — always pass `Context.RECEIVER_EXPORTED` for system broadcasts on API 33+.
* `[TRAP: Google Play Protect Warning & Offline Trust]` — never add `INTERNET` or `canRetrieveWindowContent="true"`.
