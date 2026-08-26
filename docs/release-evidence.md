# FlowTube Task 1 Build & Release Verification Evidence

## Verification Overview

- **Execution Date:** 2026-08-24
- **Scope / Task:** Task 1 Build Gate & Toolchain Verification Closure

## Environment Configuration

- **JDK Distribution:** Eclipse Temurin `17.0.14+7` (session-scoped)
- **Android SDK:** API 35 (Android 15)
- **Build System:** Gradle Wrapper `8.11.1` with Android Gradle Plugin `8.8.2` and Kotlin `2.1.20`

## Build & Test Execution Evidence

- **Command:** `./gradlew.bat clean test assembleDebug`
- **Outcome:** `BUILD SUCCESSFUL in 32s`
- **Task Summary:** 73 actionable tasks executed
- **Unit Test Verification:** Unit test suites for both debug and release variants executed and passed.

## Artifact Deliverable Evidence

- **Target Artifact:** Debug Application Package (`app-debug.apk`)
- **Artifact Path:** `app/build/outputs/apk/debug/app-debug.apk`
- **File Size:** 24,431,283 bytes
- **SHA-256 Hash:** `99E54025A14AC29EAF2A0B8E4940701284072C58156725CD332CFB48987C20EA`

## Extractor Upgrade & Stream Accessibility Evidence (Task 3 Gate)

- **Previous Baseline (v0.24.5):** When executing connected upstream smoke testing on `emulator-5554` with query `Kotlin`, NewPipeExtractor `v0.24.5` encountered upstream YouTube changes causing extraction failure (`ContentUnavailable`) for all 5 evaluated video candidates.
- **Upgraded Runtime (v0.26.5):** Upgrading extractor dependency to `v0.26.5` resolved metadata and stream info extraction.
- **Stream Accessibility Verification:** Bounded privacy-safe HTTP range probing (`Range: bytes=0-1023` for direct streams with 206 Content-Range verification or bounded 200 responses <=1024 bytes, and max 16KiB body read with recognized markers `#EXTM3U` or `<MPD` for manifests) evaluates in-memory stream URLs. Accessibility is only claimed after the HTTP probe verifies bounded stream accessibility without HTML or bot-block interception. Task 3 verifies bounded stream accessibility via range/manifest probe, not media playback.
- **Scope Limitation:** Task 3 remains strictly an extractor and network gate. No ExoPlayer/Media3 playback is instantiated or claimed before Task 5.

## Scope Boundary & Claims Limitation

This evidence confirms local dependency resolution, toolchain compatibility, unit test execution, and APK generation for this local execution environment. It does not prove upstream runtime playback or legal clearance.

## Task 5C Live Rendered Playback Gate

- **Execution Date:** 2026-08-25
- **Environment:** Attached API 35 emulator (`emulator-5554`, `FlowTubeApi35(AVD) - 15`); portable Temurin `17.0.14+7` JDK from the recorded toolchain distribution.
- **Build & Verification Order:** Executed `clean test assembleDebug` FIRST, recorded build duration (30s), APK SHA-256 hash (`99E54025A14AC29EAF2A0B8E4940701284072C58156725CD332CFB48987C20EA`), and LastWriteTime timestamp (`apkBuiltUtc`: `2026-08-25T12:55:09Z`). Executed connected live verification LAST with literal arguments (`liveStartedUtc`: `2026-08-25T12:55:36Z`, `liveFinishedUtc`: `2026-08-25T12:57:22Z`), guaranteeing `apkBuiltUtc <= liveStartedUtc`. Pulled fact artifacts immediately.
- **Invocation:** `gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<provided> -Pandroid.testInstrumentationRunnerArguments.flowtubeLivePlayback=<provided> -Pandroid.testInstrumentationRunnerArguments.flowtubeSmokeQuery=<provided>`
- **Outcome:** `BUILD SUCCESSFUL`. 1 test, 0 failures, 0 errors, 0 skipped, test duration 85.365s (suite duration 86.177s).
- **Persistent Evidence Artifacts & Cryptographic Hashes:**
  1. **Build & Execution Manifest:** `docs/evidence/task5c-live-playback-android35-build.json`
     - **SHA-256 Hash:** `5A235538CE0D0ACD4FF7A867D15C3CA625BD30F7658115E9B4B155AB0F42F0EB`
  2. **Live Playback Auditable Facts:** `docs/evidence/task5c-live-playback-android35-facts.json`
     - **SHA-256 Hash:** `1E1F8C2E301AAD7215CDA2954E6DCC2D99A3DECEDC9EBF9E2593795A445484FA`
  3. **JUnit XML Test Suite Report:** `docs/evidence/task5c-live-playback-android35.xml`
     - **SHA-256 Hash:** `29522F40E566B562333D5E29310B113F125FEEB98D7534FFF1C5B185B71B09EA`
- **Safe Evidence Facts:**
  - `completion: true`, `schemaVersion: 1`, positive snapshot duration (`actualDurationMs: 141308`).
  - Surface attachment and layout verified (`surfaceAttached: true`, `playerViewAttached: true`, `playerViewLaidOut: true`, `playerViewGlobalVisible: true`, `playerViewHasPlayer: true`).
  - Initial playback: generation 1, 1st frame rendered (`initialRenderCount: 1`), `initialPlaybackState: STATE_READY`, `initialIsPlaying: true`, advance delta verified (`advanceDeltaMs: 778`).
  - Seek execution: duration > 5s non-live from probe snapshot without metadata fallback, seek target `seekTargetMs: 47102`, target delta `seekActualDeltaMs: 0`, post-seek advance `postSeekDeltaMs: 468`.
  - Quality switch: restricted strictly to valid video-capable candidates (`MERGED_AV`), new generation 2 confirmed, 1st frame rendered in new generation (`postSwitchRenderDelta: 1`), position delta `postSwitchPositionDeltaMs: 0`, post-switch advance `postSwitchAdvanceDeltaMs: 570`.
- **Privacy Boundary:** The persistent XML test report, assertion messages, instrumentation status, facts file, and build JSON contain only safe phase and playback facts. They do not contain the input query, content identifiers, stream URLs, request tokens, or response bodies. Build JSON never records query/content values, persisting only `liveQueryProvided=true`, the live test class name, and command templates with `<provided>` placeholders. Both the test runner and fact model validate the absence of prohibited patterns before writing.

## Tasks 10-12 Release Closure

- **Execution Date:** 2026-08-26
- **Environment:** Temurin `17.0.14+7`, Android SDK API 35, attached `emulator-5554` (`FlowTubeApi35(AVD) - 15`).
- **Clean command:** `gradlew.bat clean test assembleDebug assembleRelease connectedDebugAndroidTest`
- **Outcome:** `BUILD SUCCESSFUL in 2m 40s`; 131 actionable tasks, 129 executed and 2 up-to-date.
- **Unit tests:** Debug and release unit suites passed.
- **Device tests:** 77 instrumentation executions completed with 0 failures; the externally configured upstream smoke test was the only skipped test because no public query was supplied.
- **Release shrinker:** Minified `assembleRelease` and `lintVitalRelease` passed with the existing narrow rules. No keep-all rule was added. Android packaging retained `libandroidx.graphics.path.so` and `libdatastore_shared_counter.so` unstripped after reporting they could not be stripped; this was non-fatal.
- **Debug APK:** `app/build/outputs/apk/debug/app-debug.apk`, 24,857,739 bytes, built `2026-08-26T02:38:55.6320604Z`, SHA-256 `9EA7BD01325CE7AB394FF03614FD445D5A2850460405A526F723A3EFD2D9A494`.
- **Development-signed minified release APK:** `app/build/outputs/apk/release/app-release.apk`, 3,853,290 bytes, built `2026-08-26T03:13:41.8201422Z`, SHA-256 `28EF09E8DD2C11D013F254A42BBAEF85EF46E7F40E8C3571C290A4D9A87EB927`.
- **Signing certificate:** Android Debug certificate, SHA-256 `5ED535C308BB54CC962BFFCD3C8C594F6ACDCB3C6ABA5EB34BCD7189C270A898`, APK Signature Scheme v2. This artifact is installable for development/testing only and is not suitable for store distribution.
- **Install:** `adb install -r app/build/outputs/apk/debug/app-debug.apk` returned `Success`.
- **Launch:** `adb shell am start -W -n com.flowtube.app/.MainActivity` returned `Status: ok`, `LaunchState: COLD`, `TotalTime: 2856` ms.
- **Runtime observation:** PID `5471`; `topResumedActivity=... com.flowtube.app/.MainActivity`.
- **Signed release install/open:** `adb install -r app/build/outputs/apk/release/app-release.apk` returned `Success`; cold launch returned `Status: ok`, `Activity: com.flowtube.app/.MainActivity`, `TotalTime: 426` ms.
- **Deterministic E2E:** Search result to Watch to Back to mini-player passed in `EndToEndNavigationTest`.
- **Live playback scope:** No new upstream query or live playback argument was supplied in this closure run. Real remote render/seek/quality evidence remains the successful 2026-08-25 Task 5C record above; provider behavior can regress independently and must be re-run before distribution.
- **Coverage limits:** API 26 and a physical current-device acceptance pass were not executed in this session. Do not claim those matrix columns as passed.
