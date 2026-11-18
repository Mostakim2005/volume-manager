# Project Operating Instructions (`AGENTS.md`)
> **Notice:** This project inherits the user persona, confirmation gates, and documentation blueprints from the global master `AGENTS.md`. Do not duplicate those rules here.

## 1. Project Tech Stack & Environment
* **Language / Build:** Kotlin 1.9.24, Gradle 8.7 (wrapper included), Android Gradle Plugin 8.5.2.
* **SDK levels:** `compileSdk = 35`, `minSdk = 23`, `targetSdk = 33` (deliberately below 34 — see `docs/DECISIONS_AND_TRAPS.md`).
* **UI:** XML layouts + ViewBinding, Material Components 1.12.0, AppCompat 1.7.0.
* **Target device:** Realme 6 (RMX2001), Realme UI 1.0/2.0 on Android 10/11.
* **Toolchain requirement:** JDK 17 + Android SDK platform 35. Not available in the authoring environment.

## 2. Mandatory Verification & Build Commands
* **Build:** `.\gradlew assembleDebug`
* **Lint:** `.\gradlew lintDebug`
* **Verification gate:** Do not tag or distribute until both commands pass on a machine with the toolchain.

## 3. Repository Directory Structure & Conventions
* `app/src/main/java/com/volumemanager/` — activity, boot receiver, foreground service.
* `app/src/main/java/com/volumemanager/overlay/` — all `WindowManager` and touch logic.
* `app/src/main/java/com/volumemanager/util/` — permission and OEM deep-link helpers.
* `app/src/main/res/` — layouts, drawables, values, launcher icons.
* `docs/` — `PRODUCT.md`, `ARCHITECTURE.md`, `DECISIONS_AND_TRAPS.md` (native Mermaid, no HTML guides).
* Single-responsibility source files; no new XML-free monolithic service.

## 4. Repository-Specific Engineering Traps
* **TRAP 1:** Realme UI blocks `BOOT_COMPLETED` without manual Auto-launch; never remove the deep-link button in `RealmeSettings -> openAutoLaunch`.
* **TRAP 2:** `BOOT_COMPLETED` is post-unlock; `LOCKED_BOOT_COMPLETED` must defer to `ACTION_USER_UNLOCKED` because the service is not `directBootAware`.
* **TRAP 3:** Every overlay entry point (`show`, `togglePanel`) must re-check `Settings.canDrawOverlays` or a revoked permission crashes the service.
* **TRAP 4:** Dynamic `registerReceiver` calls need `Context.RECEIVER_EXPORTED` on API 33+ for system broadcasts.
* **TRAP 5:** Do not reintroduce the legacy `package` attribute in `AndroidManifest.xml`; AGP 8 uses `namespace` in Gradle.
