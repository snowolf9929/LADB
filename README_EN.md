<div align="center">

### [简体中文](README.md) | **English** | [Русский](README_RU.md)

</div>

# LADB

A local ADB shell for Android!

# About this fork

This is a fork of [tytydraco/LADB](https://github.com/tytydraco/LADB). The app works the same way; the changes are
listed below.

- The license check and the Google Play Services dependency are removed, along with unused permissions
- Russian translation alongside the original English, including the shell output
- The language can be switched in the app, and follows the system per-app language on Android 13 and up
- Material 3 interface, with dynamic colors on Android 12 and up and a themed icon on Android 13 and up
- Targets Android 17, including the local network permission that port discovery now requires
- Deprecated platform APIs replaced with current ones, with the same behavior down to Android 8
- More reliable first connection: discovery waits for the port, and the connection is verified and retried
- The pairing is named LADB in the wireless debugging settings
- Logging is stripped from release builds
- Signed builds for every ABI, plus a universal one, are produced by GitHub Actions with the key from this repository
- Remote devices: debug another phone on the same network over wireless debugging. The pairing is saved, so later connections need no pairing code

# How does it work?

LADB bundles an ADB server within the app libraries. Normally, this server cannot connect to the local device because it
requires an active USB connection. However, Android's Wireless ADB Debugging feature allows the server and the client to
speak to each other locally.

# Initial Setup

Use split-screen more or a pop-out window with LADB and Settings at the same time. This is because Android will
invalidate the pairing information if the dialog gets dismissed. Add a Wireless Debugging connection, and copy the
pairing code and port into LADB. Keep both windows open until the Settings dialog dismisses itself.


# Debugging another phone

Besides this device, LADB can drive another phone on the same network that has Wireless debugging turned on (Android 11
and up). No computer is involved.

## First connection (pairing once)

1. On the **other device**, open Settings → Developer options → Wireless debugging and leave it on;
2. Tap "Pair device with pairing code" and note the **IP address, pairing port and 6-digit pairing code**;
3. In LADB on **this device**, tap ＋ in the device bar at the top ("Add remote device") and fill in a name (optional),
   the IP address, the pairing port and the pairing code;
   - The connect port may be left empty: LADB finds it over mDNS. It can also be copied from the top of the Wireless
     debugging page on the other device;
4. Tap "Pair & connect". The other device may show "Allow USB debugging?"; tap "Allow" there once.

## Later connections (no pairing code)

Once paired, the pairing is kept in the app's private storage:

- `files/.android/adbkey` and `adbkey.pub` — this device's ADB key, which is what the pairing actually is;
- `files/.android/adb_known_hosts*` — the host key remembered during pairing;
- The device list (IP, name, last used port) is kept in the app settings.

So **the second connection is just**: open LADB → tap the device bar → pick the device → tap "Connect".

> Wireless debugging hands out a **new connect port** every time it is switched on, so the port is not permanent. LADB
> discovers the current one over mDNS; if that fails, set it by hand with "Connect port" in the device menu (⋮).

## Notes and limits

- Both devices have to be on the same local network (the same Wi-Fi, or one sharing its hotspot);
- This device needs no confirmation, but a remote device shows an authorization dialog that somebody has to accept;
- LADB's own ADB server still listens on `127.0.0.1:5037` only, so nothing else on the network can reach it;
- Device names: leave the name empty and LADB asks the device itself for `ro.product.brand:ro.product.model` once it is
  connected. A name you typed is never overwritten; clearing it hands the naming back to LADB.
- Clearing pairings: "Forget device" in the device menu drops one remote device, "Unpair" in the settings lets you pick
  this device, a single remote device or all of them, and "Reset ADB keys" also deletes the key pair. The key is shared
  by every device, so that last one is always global and everything has to be paired again.
- **The other device drops the session when its screen turns off.** Android switches wireless debugging off by itself
  after the screen turns off or the network changes: `AdbDebuggingManager` sets `adb_wifi_enabled` to 0 on a Wi-Fi
  disconnect or a BSSID change. What LADB does about it:
  - **Awake by default.** With "Settings → Keep remote devices awake" on, a `input keyevent KEYCODE_WAKEUP` is sent to
    the device every 15 seconds while it is connected, so its screen never gets the chance to sleep. A device that is
    already awake ignores it. The cost is that **that screen stays lit**; turn the switch off to let it sleep;
  - with that off, keep it awake another way:
  - plug it in and run `settings put global stay_on_while_plugged_in 7` from LADB, which is the same as
    Developer options → "Stay awake", and `settings put global stay_on_while_plugged_in 0` to put it back. Both are
    in the default bookmarks; or
  - set it by hand on that device: Developer options → "Stay awake", or a longer screen timeout.

  > Mind the namespace: `settings put global` only needs `WRITE_SECURE_SETTINGS`, which the shell user holds, while
  > `settings put system` needs `WRITE_SETTINGS` and is refused on many devices with
  > `SecurityException: Writing to settings requires:android.permission.WRITE_SETTINGS` — so do not reach for
  > `screen_off_timeout`.
- A dropped device is retried three times, four seconds apart, dropping the dead transport in the ADB server first and
  trying every port it knows. If its wireless debugging really was switched off the
  retries fail and the output says why (`adb: …; its wireless debugging: no longer announced, probably switched off`)
  along with what to do about it.
- "Settings → Keep this screen on" (on by default) stops this phone from sleeping while a remote device is connected,
  so Android cannot freeze LADB and the ADB server it started in the middle of a session.

# Issues

LADB is sadly incompatible with Shizuku at the current moment. That means that if you have Shiuzuku installed, LADB will
usually fail to connect properly. You must uninstall it and reboot to use LADB.

# Troubleshooting

Most errors can be fixed by clearing the app data for LADB, removing all Wireless Debugging connections from Settings,
and rebooting.

# License

The license is mostly permissive other than it does not allow unofficial builds to be released to the Google Play Store.

# Privacy Policy

LADB does not send any device data outside the app. Your data is not collected or processed.
