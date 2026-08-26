# FlowTube V1 Manual Test Matrix

Record one row per device/build. Do not mark remote playback passed from metadata or stream-resolution evidence alone.

| Flow | API 26 | API 31+ | Current device | Build ID | Result | Notes |
|---|---|---|---|---|---|---|
| Install and launch | Not run | API 35 emulator | API 35 emulator | Local debug | Pass | Automated device suite installs and launches the test APK. |
| Home to real playback | Not run | Previously verified | API 35 emulator | 2026-08-25 evidence build | Pass | See `docs/evidence/task5c-live-playback-android35-facts.json`. |
| Search to Watch | Not run | API 35 emulator | API 35 emulator | Local debug | Pass | Deterministic `EndToEndNavigationTest`. |
| Seek +/-10 seconds | Not run | Previously verified | API 35 emulator | 2026-08-25 evidence build | Pass | Live playback evidence records seek and post-seek advance. |
| Speed and available quality | Not run | Previously verified | API 35 emulator | 2026-08-25 evidence build | Pass | Quality generation switch verified; speed controls covered deterministically. |
| Fullscreen and back | Not run | API 35 emulator | API 35 emulator | Local debug | Pass | Watch instrumentation suite. |
| Mini-player restore/dismiss | Not run | API 35 emulator | API 35 emulator | Local debug | Pass | Mini-player and E2E instrumentation tests. |
| Background notification/lock controls | Not run | API 35 emulator | API 35 emulator | Local debug | Pass | MediaSession service instrumentation; physical lock-screen observation still recommended. |
| PiP when supported | Not run | API 35 emulator | API 35 emulator | Local debug | Pass | Eligibility/lifecycle tests; visual OEM behavior remains device-specific. |
| History resume/clear | Not run | API 35 emulator | API 35 emulator | Local debug | Pass | DAO, repository and Library instrumentation tests. |
| Local follow and playlists | Not run | API 35 emulator | API 35 emulator | Local debug | Pass | Local-only labels and CRUD/reorder tests. |
| System/light/dark theme | Not run | API 35 emulator | API 35 emulator | Local debug | Pass | Settings instrumentation test. |
| Rotation/recreation | Not run | API 35 emulator | API 35 emulator | Local debug | Pass | Watch recreation and session tests. |
| Offline/mapped extractor error | Not run | API 35 emulator | API 35 emulator | Local debug | Pass | Fake-backed error and retry tests. |
| Expired stream refresh once | Not run | API 35 emulator | API 35 emulator | Local debug | Pass | Recovery unit/service tests. |

## Remaining Device Coverage

- Run install/launch and core playback on one API 26 device or emulator before claiming API 26 manual coverage.
- Repeat notification, Bluetooth/headset, audio focus, lock-screen and PiP checks on a physical current Android device before store distribution.
- Re-run the separately configured upstream smoke and live playback gates because provider behavior can change independently of the APK.
