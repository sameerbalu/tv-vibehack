# Performance changes for TV Home

This patch keeps the existing launcher features and visual choices while reducing work and retained artwork during normal use.

## Changes

- Launcher resume no longer synchronously queries the installed-app list and loads tile artwork on the UI thread. Changed-app detection and list construction run on a single background worker; stale results are discarded, and focus is restored after an updated list is applied.
- App tile artwork is loaded lazily and held in a byte-weighted LRU cache capped at up to 16 MiB (scaled down on smaller heaps). The dock and first app rows are warmed in the background. Existing banners, icon-pack icons, shaped icons, app colors, and Continue-watching tiles still use their original appearance.
- Unused artwork is not loaded when a higher-priority banner or icon-pack icon is already displayed.
- Existing glow and particle effects now pause when the launcher activity pauses and resume with the user's saved settings.

## Verification

- `assembleDebug` completes successfully using Android API 34 / Gradle 8.9.
- The resulting APK keeps package `com.gathin.tvhome`, version name `64.0`, and version code `60`.
- No Android TV device/emulator was available here, so runtime memory and Home-key latency still need a real-device check.
- `lintDebug` reports existing project lint failures in Bluetooth permission checks in `MainActivity`; the changed code paths compiled successfully.

## Signing

The uploaded APK and this sandbox-built debug APK have different signing-certificate fingerprints. Android will reject the new APK as an in-place update unless it is signed with the same key used for the existing installation. Keep the existing app installed until you have the original signing/debug keystore or intend to test on a separate device/install.
