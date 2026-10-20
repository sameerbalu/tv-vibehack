# TV Control

A companion app for the launcher: live RAM / storage / network, recent apps with Stop, Wi-Fi data per app,
cache per app, and shortcuts into the TV's settings. Sideload it like the launcher (APK from the
"Build TV Control APK" GitHub Action).

## One-time setup (run on a PC or in Termux with adb connected to the TV)

```
adb shell appops set com.gathin.tvcontrol GET_USAGE_STATS allow
adb shell pm grant com.gathin.tvcontrol android.permission.WRITE_SECURE_SETTINGS   # only for the animation-speed control
```

The first one unlocks per-app data usage, cache sizes and recent apps (you can also grant it in
Settings > Usage access). Without it the Overview, Tools and live-speed parts still work.

## What it can and can't do without root

- Stop background apps: yes (`killBackgroundProcesses`).
- Clear cache: "Clear all cache" frees what Android lets a normal app release; per-app clearing opens App info.
  A full wipe is `adb shell pm trim-caches 999G`.
- Per-app data: Wi-Fi only. Android does not expose per-app Ethernet usage.
