# DotCtrl · Android Auto Clicker

<p align="center">
  <img src="./docs/logo.svg" height="112" alt="DotCtrl logo" />
</p>

<p align="center">An offline Android clicker and coordinate-script tool for Android 8.0+</p>

<p align="center">
  <a href="./README.md">中文</a> · English
</p>

<p align="center">
  <a href="./LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0--only-147562.svg" alt="License: AGPL-3.0-only" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-147562.svg" alt="Android 8.0+" />
  <img src="https://img.shields.io/badge/Kotlin-Compose-147562.svg" alt="Kotlin and Jetpack Compose" />
</p>

DotCtrl (点控) is a native Android auto-clicking tool. It uses Android AccessibilityService to dispatch explicitly configured taps, long presses, and swipes. A floating panel handles point selection, script editing, and run controls.

## Features

- Single-point tapping with intervals, counts, long press, continuous mode, and a safety timeout.
- Coordinate scripts with taps, long presses, swipes, step delays, ordering, and loops.
- Normalized coordinates with orientation tracking; execution pauses when the orientation changes.
- Local presets and scripts with JSON import/export.
- Compact and expanded floating controls with immediate stop and quick preset switching.
- Automatic stop on lock screen, restricted surfaces, revoked permissions, or failed gestures.

## Quick start

Requirements: JDK 17, Android SDK Platform 35, and Build Tools 35.

Set `JAVA_HOME` to JDK 17 and specify the Android SDK path using `ANDROID_HOME` or `sdk.dir` in your local `local.properties` file.

```bash
./gradlew --no-daemon --no-configuration-cache :app:assembleDebug
./gradlew --no-daemon --no-configuration-cache :core:test :app:lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Minimum supported version is Android 8.0 (API 26). Release builds require a separate signing configuration.

On first launch, enable the DotCtrl accessibility service and overlay permission, configure a point or script, then open the floating panel from the bottom of the workbench. The accessibility service dispatches configured gestures, and the overlay permission allows point selection and run controls above other apps.

Before sharing an APK, verify permission setup, single-point and script execution, clicking in ordinary third-party apps, immediate stop, orientation changes, app switching, and permission revocation on an Android 8.0+ emulator and a physical device.

## Safety notes

- For learning, research, and testing only. Follow applicable laws and platform rules.
- The maintainers do not operate application backend servers.
- The accessibility service dispatches taps, long presses, and swipes explicitly configured by the user. It does not read target-app text, accessibility nodes, or passwords, or record real touches or screens in other apps.
- Configuration is stored only in app-private storage. Exported JSON contains coordinates and script timing; treat it as private configuration.
- Use the app only in authorized environments. Do not use it for deception, cheating, privacy violations, or bypassing security mechanisms. Lock screens, secure surfaces, vendor restrictions, or target-app policies may prevent gesture dispatch; the project does not promise to bypass these restrictions.

## License

DotCtrl is released under the [GNU AGPL v3.0](./LICENSE) (`AGPL-3.0-only`).
