# HPre V1 Manual Test Matrix

Record one row per device/build. Do not mark remote playback passed from metadata or stream-resolution evidence alone.

| Flow | API 26 | API 31+ | Current device | Build ID | Result | Notes |
|---|---|---|---|---|---|---|
| HPre signed release evidence | Not run | API 35 emulator or physical device | API 35 emulator or physical device | Fresh HPre APK | Not run | Regenerate facts, XML, hashes, and release notes from one real run before distribution. |
| Deterministic build gate | N/A | N/A | Local Windows build | Debug APK | Pass | 452 unit tests across 63 classes passed with 0 failures, lint passed with 0 errors, AndroidTest sources compiled, and debug APK assembled on 2026-08-26. |
| Install and launch | Not run | Not run | No device attached | Debug APK | Not run | `adb devices` returned no attached device in this session. |
| Launcher icon rendering | Not run | Not run | No device attached | Debug APK | Not run | Adaptive icon packaged as `res/mipmap-anydpi-v21/ic_launcher{,_round}.xml` with background/foreground/monochrome layers; lint reports no icon warnings. Verify mask shapes and themed-icon tint on an API 33+ launcher. |
| Home to real playback | Not run | Not run | API 35 emulator | Fresh HPre APK | Not run | Requires new live verification run for HPre build. |
| Search to Watch | Not run | Not run | No device attached | Debug APK | Not run | Instrumentation source compiled; runtime device test not run. |
| Seek +/-10 seconds | Not run | Not run | API 35 emulator | Fresh HPre APK | Not run | Requires new live verification run for HPre build. |
| Speed and available quality | Not run | Not run | API 35 emulator | Fresh HPre APK | Not run | Requires new live verification run for HPre build; speed controls covered deterministically. |
| Fullscreen and back | Not run | Not run | No device attached | Debug APK | Not run | Unit coverage passed; instrumentation runtime not run. |
| Mini-player restore/dismiss | Not run | Not run | No device attached | Debug APK | Not run | Instrumentation source compiled; runtime not run. |
| Background notification/lock controls | Not run | Not run | No device attached | Debug APK | Not run | Physical lock-screen and notification behavior remains unverified. |
| PiP when supported | Not run | Not run | No device attached | Debug APK | Not run | Eligibility unit tests passed; visual OEM behavior remains unverified. |
| History resume/clear | Not run | Not run | No device attached | Debug APK | Not run | DAO/repository unit tests passed; device persistence flow not run. |
| Local follow and playlists | Not run | Not run | No device attached | Debug APK | Not run | Repository unit tests passed; device UI flow not run. |
| System/light/dark theme | Not run | Not run | No device attached | Debug APK | Not run | Instrumentation source compiled; runtime not run. |
| Rotation/recreation | Not run | Not run | No device attached | Debug APK | Not run | Session unit tests passed; device recreation flow not run. |
| Offline/mapped extractor error | Not run | Not run | No device attached | Debug APK | Not run | Fake-backed unit coverage passed; device offline flow not run. |
| Expired stream refresh once | Not run | Not run | No device attached | Debug APK | Not run | Recovery unit tests passed; live service behavior not run. |
| Upstream smoke | Not run | Not run | No device/query supplied | Debug APK | Not run | Requires an attached device and an explicitly approved public query. |
| Playback startup comparison | Not run | Not run | No device/baseline | Debug APK | Not run | No same-device/network before-and-after baseline was available. |
| Vietnam trending fallback | Not run | Not run | No device attached | Debug APK | Not run | Provider is initialized with `vi-VN` localization and `VN` content country, and the trending kiosk is force-localized to VN. Confirm against a live provider that a signal-free Home feed returns Vietnam trending; region correctness cannot be asserted offline. |
| YouTube-familiar Home UI | Not run | Not run | No device attached | Debug APK | Not run | Unit formatting/topic-state tests passed and instrumentation sources compiled; 360dp chip scrolling, dark/light contrast, rounded cards, Vietnamese metadata, and live badge require device verification. |

## Remaining Device Coverage

- Run install/launch and core playback on one API 26 device or emulator before claiming API 26 manual coverage.
- Repeat notification, Bluetooth/headset, audio focus, lock-screen and PiP checks on a physical current Android device before store distribution.
- Re-run the separately configured upstream smoke and live playback gates because provider behavior can change independently of the APK.
