# HPre Update Checker, Screenshots, and Upgrade Gate Design

**Date:** 2026-08-28
**Status:** Approved in chat; implementation pending

## 1. Goal

Prepare HPre for trustworthy future updates without publishing a new version in this delivery. Add a manual GitHub Releases update check, real emulator screenshots, repository badges, and a mandatory release-upgrade verification tool that will prove a future APK can update the published `v1.0.0` installation with the same signing identity.

## 2. Confirmed Baseline

- Android CI is fully green for commit `00c9c683dccf86b0c22c099e9f5f7912097d878a`.
- GitHub Actions run `33142291110` completed with `success`; checkout, Java 17 setup, Gradle setup, unit tests, lint, and `assembleDebug` all passed.
- Public repository: `https://github.com/hungnq131193-ux/HPre`.
- Published baseline: tag/release `v1.0.0`, application ID `com.hpre.app`, version name `1.0.0`, version code `1`.
- Published APK: `HPre-v1.0.0-release.apk`.
- HPre release certificate SHA-256: `40:D6:29:54:4B:6C:12:01:4A:D0:A0:3B:ED:C2:AF:FE:81:E6:DF:8E:F8:8F:69:3F:76:36:5A:6E:95:D4:9D:47`.
- A local Android API 35 AVD is available; no emulator is currently running.
- No real screenshots are currently tracked.
- Settings is an existing Compose screen and `DefaultAppContainer` already owns the shared OkHttp client.

## 3. Scope and Non-goals

### In scope

- Confirm and preserve evidence that Android CI is green.
- Add four repository badges under the README title: Android CI, latest release, GPL-3.0-or-later, and Android API 26+.
- Capture genuine HPre screens from the local Android emulator and add only usable real captures to `docs/screenshots/`.
- Add a manual update check to Settings using the official GitHub Releases API.
- Open the official GitHub Release page when a stable newer version is available.
- Add deterministic unit tests for release parsing, version comparison, network/error behavior, and Settings state transitions.
- Add a local release verification script and documentation for a future `v1.0.0 -> v1.0.1` install-over test.
- Require both `versionName` and `versionCode` to increase before a future release.

### Out of scope

- Do not change `versionName = "1.0.0"` or `versionCode = 1` in this delivery.
- Do not create or push tag `v1.0.1`.
- Do not build, upload, or publish a `v1.0.1` GitHub Release.
- Do not replace or modify the published `v1.0.0` APK or tag.
- Do not auto-check for updates at startup or on a timer.
- Do not download APKs in the background, invoke PackageInstaller, request unknown-app installation permission, or silently install updates.
- Do not invent screenshots, edit app content into captures, or show screens that did not render on the emulator.

## 4. Update Architecture

### 4.1 Components and boundaries

Add an update package with focused units:

```text
Settings UI
    -> SettingsViewModel
        -> AppUpdateChecker
            -> GitHubReleaseUpdateChecker
                -> shared OkHttpClient
                -> GitHub Releases REST API

Settings UI
    -> Open release action
        -> Android HTTPS URI handler
        -> github.com/hungnq131193-ux/HPre/releases/tag/vMAJOR.MINOR.PATCH
```

`AppUpdateChecker` is a provider-neutral interface. The GitHub-specific implementation is isolated behind it so the ViewModel and Compose UI do not parse JSON, construct API URLs, or depend on OkHttp response types.

The checker is application-scoped through `AppContainer`. Test containers receive a fake checker or a safe default implementation so existing navigation and instrumentation seams continue to compile.

### 4.2 API request

The implementation performs an unauthenticated `GET` request to:

```text
https://api.github.com/repos/hungnq131193-ux/HPre/releases/latest
```

Request requirements:

- explicit GitHub API `Accept` header;
- bounded connect/read/call behavior inherited from or derived from the shared OkHttp configuration;
- a HPre-specific user agent that does not include credentials or device identifiers;
- no GitHub token, cookies, analytics identifier, or persistent response containing expiring/private data;
- response bodies always closed.

GitHub API rate limiting, offline state, malformed JSON, timeout, and non-2xx status produce a recoverable `Unavailable` result. They do not crash Settings or alter playback/network state.

### 4.3 Accepted release

A candidate is accepted only when all are true:

- it is not draft;
- it is not prerelease;
- `tag_name` is exactly `vMAJOR.MINOR.PATCH` with non-negative integer components;
- `html_url` is HTTPS and hosted by `github.com` for `hungnq131193-ux/HPre`;
- at least one asset name starts with `HPre-` and ends with `.apk` case-insensitively;
- the candidate semantic version is greater than the installed app semantic version.

The asset URL is validated as evidence that the release includes an APK, but the UI opens the release `html_url`, not the asset download URL. This lets users inspect release notes and checksum before downloading.

### 4.4 Version model

Use a small immutable semantic-version value type with `major`, `minor`, and `patch` integers and total ordering. It accepts installed version `1.0.0` and release tag `v1.0.1`; it rejects missing components, suffixes, negative values, integer overflow, whitespace variants, and arbitrary tag text.

Update availability is based on SemVer ordering. Future release validation separately requires `versionCode` to increase; the GitHub public response does not determine Android version code.

### 4.5 Results and UI state

Domain results:

```text
UpToDate(installedVersion)
UpdateAvailable(installedVersion, latestVersion, releasePageUrl)
Unavailable(reasonCategory)
```

Settings UI state:

```text
Idle
Checking
UpToDate
UpdateAvailable
Error
```

`Unavailable` exposes a user-safe Vietnamese message and retry action. It never displays raw response bodies, stack traces, tokens, device paths, or private network details.

## 5. Settings Experience

Add a section named **Cập nhật ứng dụng** below the existing privacy/history settings. It displays the installed HPre version from `BuildConfig.VERSION_NAME` and a manually activated **Kiểm tra cập nhật** item.

Behavior:

1. Initial state makes no network request.
2. A tap starts one check and shows progress; repeated taps while active do not launch duplicate requests.
3. If current, show that the user is running the latest version.
4. If newer, show the latest version and a **Xem bản phát hành** action.
5. The action opens the validated HTTPS GitHub release page through Android's URI handling.
6. If no browser/handler exists, preserve the update result and show a recoverable message.
7. A failed check can be retried manually.
8. Leaving/re-entering Settings may retain ViewModel state for the current navigation lifetime, but results need not persist across process death.

No update check runs from `Application`, `MainActivity`, playback service, Home, or any background worker.

## 6. Test Strategy for Updater

### Pure tests

- SemVer parses valid installed versions and `v` tags.
- Ordering handles major, minor, and patch transitions.
- Invalid, overflow, suffixed, partial, and whitespace-altered versions are rejected.
- Release selection rejects draft, prerelease, invalid host/path, malformed tags, and missing HPre APK assets.

### Network adapter tests

Use MockWebServer for:

- newer stable release;
- equal/older release;
- malformed JSON;
- HTTP 403 rate limit;
- HTTP 404 and 5xx;
- timeout/connection failure;
- draft/prerelease fields;
- missing/misnamed APK asset;
- unsafe or wrong-repository `html_url`.

Tests assert request path and safe headers without requiring external network access.

### ViewModel/UI tests

- No request before user action.
- One in-flight request despite repeated taps.
- Idle -> Checking -> UpToDate.
- Idle -> Checking -> UpdateAvailable and release action.
- Idle -> Checking -> Error -> retry.
- Browser-open failure is user-visible and does not discard update metadata.
- Settings shows installed version and stable test tags.

## 7. Real Screenshots

### 7.1 Capture source

Use the available local Android API 35 AVD. Build/install a debug APK from the current source, launch HPre, and capture screens with ADB `screencap`. The image must come directly from the emulator display.

Before capture:

- use a consistent portrait emulator resolution and scale;
- wait for animations/loading to settle;
- remove system dialogs, notifications, keyboard, debug overlays, and unrelated apps from view;
- do not expose local credentials or personal account data;
- use only public/provider data already rendered by HPre;
- do not modify screenshots to fabricate content. Lossless cropping of irrelevant emulator chrome is allowed only if it does not alter the app pixels.

### 7.2 Target set

Attempt these captures:

- `docs/screenshots/home.png`
- `docs/screenshots/search.png`
- `docs/screenshots/player.png`
- `docs/screenshots/settings.png`

Only commit captures that visibly show the named HPre screen and are not empty, failed, blocked, or misleading. Network-dependent Home/Search/Player images may be omitted if provider data cannot be rendered reliably. Settings should always be capturable. README lists only committed images and does not retain a fake four-image layout if fewer images qualify.

### 7.3 README presentation

Use a compact two-column HTML table with image width bounded for GitHub rendering. Every image has Vietnamese alt text/caption. The files remain PNG and are source documentation assets, not app resources.

## 8. README Badges

Place these directly under `# HPre`:

- Android CI badge for `.github/workflows/android.yml` on `main`, linked to Actions;
- latest GitHub release badge, linked to Releases;
- GPL-3.0-or-later badge, linked to `LICENSE`;
- Android API 26+ badge.

Use stable URLs for `hungnq131193-ux/HPre`; badges must not expose secrets or claim Play Store availability. The CI badge is accepted only while the latest `main` run is green.

## 9. Future Release Upgrade Gate

### 9.1 Script inputs and responsibility

Add a local verification script under `scripts/release/` that accepts:

- baseline APK path;
- candidate APK path;
- expected application ID;
- ADB executable or device selector when necessary.

The script uses Android SDK `apksigner`, `aapt` or `apkanalyzer`, and `adb`. It prints non-sensitive evidence and exits non-zero on every failed invariant.

### 9.2 Static pre-install checks

Before touching a device, require:

- baseline application ID and candidate application ID both equal `com.hpre.app`;
- baseline version name `1.0.0` and version code `1` for the first upgrade proof;
- candidate version name strictly greater by SemVer;
- candidate version code strictly greater than `1`;
- baseline and candidate `apksigner verify` both pass;
- signer certificate SHA-256 values match exactly;
- candidate filename begins with `HPre-` and is not debug/unsigned;
- both APK files have size greater than zero.

The generic rule for later releases is: both `versionName` and `versionCode` must increase relative to the installed baseline.

### 9.3 Install-over proof

Use a dedicated emulator or explicitly selected test device:

1. Confirm the target device is online and capture its serial.
2. Uninstall `com.hpre.app` only at the beginning of an isolated proof, never after baseline data is created.
3. Install the published baseline APK with `adb install`.
4. Confirm package/version through PackageManager/dumpsys.
5. Launch the baseline app, open Settings, and toggle **Phát trong nền** from its default enabled state to disabled. Use Android UIAutomator output to confirm the setting switch is unchecked. This DataStore-backed value is the persistence marker and requires no production backdoor or provider/network data.
6. Install the candidate with `adb install -r` and require `Success`.
7. Confirm package remains installed and version name/code are the candidate values.
8. Reopen Settings and use UIAutomator output to confirm **Phát trong nền** remains disabled. Failure to locate or verify the switch fails the proof rather than silently skipping persistence validation.
9. Launch the upgraded app and perform a smoke check for startup and Settings/update-check access.

An install after uninstalling the baseline is not proof of upgrade compatibility. `adb install -r` success plus certificate equality and preserved data is the required evidence.

### 9.4 Release ordering

For future `v1.0.1`:

1. Change source to `versionName = "1.0.1"` and `versionCode = 2` (or a higher unused code).
2. Build with the same HPre keystore/certificate used for `v1.0.0`.
3. Verify signature, metadata, and SHA-256.
4. Run the complete install-over proof from the published `v1.0.0` APK.
5. Commit/push tested source.
6. Create and push tag `v1.0.1` at the tested commit.
7. Create GitHub Release and upload only the verified signed artifact.
8. Verify remote asset filename, size, and digest.

Tag/release creation is blocked until the install-over test passes. This delivery only creates the gate; it does not execute the candidate half because no `v1.0.1` candidate is being produced.

## 10. CI and Security

Android CI remains unsigned and runs unit tests, lint, and `assembleDebug`. New updater tests are included in that suite. The release keystore and credentials remain local and are not added to GitHub Secrets merely for this feature.

The updater communicates only with GitHub's public API and opens only a validated official release page. It does not accept arbitrary redirect destinations as trusted update pages, execute downloaded content, or bypass Android install confirmation.

## 11. Expected Code and Documentation Areas

Likely additions/modifications:

- update domain/interface and GitHub adapter under `app/src/main/java/com/hpre/app/update/`;
- updater tests under `app/src/test/java/com/hpre/app/update/`;
- `AppContainer.kt` and all test container implementations;
- `SettingsViewModel.kt`, `SettingsScreen.kt`, and string resources;
- focused Settings/update UI tests;
- `scripts/release/` upgrade verification script and usage documentation;
- `README.md` badges, updater feature, screenshots, and release verification guidance;
- real PNG files under `docs/screenshots/` when captures qualify.

No unrelated playback, extractor, database, or navigation refactor is part of this delivery.

## 12. Acceptance Criteria

- Latest Android CI evidence is green before implementation begins.
- README has four correct badges and only real screenshots.
- Manual update check makes no request before user action.
- Newer stable GitHub release with a valid HPre APK produces an update prompt.
- Equal/older/invalid/unsafe releases do not produce a false update prompt.
- Release action opens only the validated official HPre GitHub Release page.
- Offline/rate-limit/server errors remain recoverable in Settings.
- Unit, lint, debug build, and instrumentation-source compilation pass locally and Android CI returns green.
- Source remains version `1.0.0` / code `1`; no `v1.0.1` tag or Release is created.
- Upgrade-gate tooling verifies package, both versions, certificate equality, `adb install -r`, post-install version, preserved marker, and smoke launch when a future candidate is supplied.
- No APK, keystore, credential, emulator userdata, or generated build output is committed.
