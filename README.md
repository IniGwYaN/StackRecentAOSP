# StackRecentAOSP

**StackRecentAOSP** is an LSPosed module that adds an iOS-inspired stacked-card feel to the Recent Apps screen on supported AOSP Launcher3 and Pixel Launcher builds. The settings screen follows Material You styling and uses Android's wallpaper-based dynamic colors on Android 12 and newer.

> **Status: debug preview for testers.** This repository currently builds a debug APK only. A signed release build and GitHub Release are intentionally out of scope until testing is complete.

## Preview

![StackRecentAOSP preview](Preview/preview.gif)

## Features

- Stacked-card Recent Apps behaviour for supported Launcher3 / Pixel Launcher implementations.
- LSPosed module settings entry, including when the launcher icon is hidden.
- Optional launcher icon toggle.
- Material You / dynamic colors on Android 12+; a Material 3 fallback theme is used on older supported versions.
- GitHub Actions workflow that builds and uploads a **debug APK** for tester feedback.

## Compatibility

- **Android:** Android 12 or newer (API 31+).
- **Framework:** LSPosed with the required modern Xposed API compatibility.
- **Supported target packages:**
  - AOSP Launcher3 — `com.android.launcher3`
  - Pixel Launcher — `com.google.android.apps.nexuslauncher`

Launcher internals differ by ROM, Android version, and launcher build. The module is a preview and is not guaranteed to work on every device or launcher fork.

## Install a tester build

1. Open the repository's [Actions page](https://github.com/IniGwYaN/StackRecentAOSP/actions).
2. Select the latest successful **Build Debug APK** workflow run.
3. In **Artifacts**, download `StackRecentAOSP-debug` and extract the ZIP if needed.
4. Install the APK on a test device. Since this is a debug build, Android may require allowing installation from the app you use to open it.
5. Open LSPosed Manager and enable **StackRecentAOSP**.
6. Set the module scope to the launcher installed on your device (`Launcher3` or `Pixel Launcher`).
7. Force-stop the launcher, then open Recent Apps and test. Reboot only if the launcher does not reload the module.

The launcher icon is hidden by default. Open the module's settings from the LSPosed module page, then enable **Show launcher icon** if you want a home-screen icon.

## Build locally

Requirements: JDK 21, Android SDK command-line tools, Android SDK Platform **36.1**, and Android Build Tools **36.0.0**.

```bash
git clone https://github.com/IniGwYaN/StackRecentAOSP.git
cd StackRecentAOSP
./gradlew assembleDebug
```

The APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

On Windows, run `gradlew.bat assembleDebug` instead. The first build needs network access to download Gradle and dependencies.

## Continuous integration

The workflow lives at `.github/workflows/build.yml` and runs on pushes to `main` / `master` or when manually triggered from the Actions page. It sets up JDK 21 and the Android SDK, then runs `assembleDebug` and uploads the APK as a short-lived Actions artifact.

This workflow does **not** create a Release or publish an APK to the Releases page. Keep tester feedback and compatibility results separate from a future release candidate.

## Reporting issues

When reporting a problem, include:

- Device model and Android / ROM version.
- Launcher name and version.
- LSPosed version and whether the module is enabled for the right scope.
- Steps to reproduce, expected behaviour, actual behaviour, and relevant LSPosed logs.
- Whether the issue occurs after force-stopping the launcher or after a reboot.

Please avoid attaching personal data or unrelated logs.

## Contributing

Issues and pull requests are welcome. For changes to launcher hooks, test on the exact launcher/Android combinations affected and describe the results in the pull request. UI-only changes should preserve the existing preference keys so they do not reset module settings.

## Acknowledgements

- [Android Open Source Project](https://source.android.com/)
- [LSPosed](https://github.com/LSPosed/LSPosed)
- The AndroidX and Material Components teams

---

Maintained by [YAN](https://github.com/IniGwYaN).
