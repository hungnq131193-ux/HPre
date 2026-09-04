# Video-Open Performance Benchmark Collection Procedure

This document specifies the exact procedure for collecting and evaluating paired video-open latency samples between HPre baseline (`v1.0.30` with measurement instrumentation) and candidate (`1.0.31`).

## Requirements

1. **Device:** `FlowTubeApi35` emulator AVD. If unavailable, use `HPreApi35Docs` (API 35, x86_64, Google Play or Google APIs).
2. **Fixed Video:** `dQw4w9WgXcQ` (Never Gonna Give You Up). If blocked, select a fixed ordinary VOD and use the identical ID for both baseline and candidate.
3. **App State:**
   - Both builds must be signed release APKs (`HPre-v1.0.30-instrumented.apk` and `HPre-v1.0.31-release.apk`).
   - Clean app install before each run (`adb uninstall com.hpre.app`).
   - Same network environment (Wi-Fi or unthrottled loopback), animation settings (animations disabled: `window_animation_scale=0`, `transition_animation_scale=0`, `animator_duration_scale=0`).
   - Default auto quality setting (360p / progressive preference).
4. **Sampling:**
   - 1 unrecorded warm-up video tap.
   - At least 10 recorded video taps per build.
   - Clear logcat before test: `adb logcat -c`
   - Capture logs: `adb logcat -d -s HPrePerformance:D *:S > <build>.log`
   - Prepend the mandatory procedure metadata line to the log file:
     ```text
     # METADATA avd=FlowTubeApi35 video=dQw4w9WgXcQ network=wifi appState=clean cache=reset quality=auto animations=off
     ```
   - Never discard failed opens, errors, or incomplete sessions.

## Execution

Run the comparator script:
```powershell
& .\scripts\performance\compare-video-open.ps1 `
    -BaselineLog path\to\v1.0.30.log `
    -CandidateLog path\to\v1.0.31.log `
    -MinimumSamples 10 `
    -RequiredImprovementPercent 15
```

The release gate passes only if the candidate median total open time is at least 15% faster than the baseline median with at least 10 valid samples each.
