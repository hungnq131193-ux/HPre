# HPre Update Checker, Screenshots, and Upgrade Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a manual, safe GitHub Releases update check to HPre, document the project with real emulator screenshots and repository badges, and prepare a mandatory same-signature install-over gate for future releases without publishing `v1.0.1` now.

**Architecture:** A provider-neutral update domain sits between Settings and a GitHub-specific OkHttp adapter. Settings initiates checks only on user action and opens only a validated official release page. Release-upgrade verification remains a local PowerShell/ADB tool, while real PNG captures and badges document only verified project state.

**Tech Stack:** Kotlin 2.1.20, Android/Compose, Coroutines/StateFlow, OkHttp 4.12.0, Moshi core 1.15.2 streaming JSON reader, MockWebServer 4.12.0, JUnit 4, PowerShell 5.1, Android SDK build-tools, ADB, UIAutomator, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-08-28-update-checker-screenshots-and-upgrade-gate-design.md`

## Global Constraints

- Android CI baseline is green at run `33142291110`; every pushed implementation commit must return Android CI to green.
- Keep application ID/namespace `com.hpre.app` and product name `HPre`.
- Keep `versionName = "1.0.0"` and `versionCode = 1` throughout this delivery.
- Do not create, move, or publish tag/release `v1.0.1`.
- Do not modify or replace the published `v1.0.0` APK/tag/release.
- Update checks are manual from Settings only; no startup, periodic, background-worker, or playback-triggered checks.
- Never add a GitHub token, update telemetry, background APK download, PackageInstaller flow, or unknown-app installation permission.
- Open only validated HTTPS release pages under `github.com/hungnq131193-ux/HPre/releases/tag/`.
- Screenshots must be genuine emulator captures. Never fabricate app content or commit failed/empty/misleading screens.
- Never commit APK/AAB files, keystores, credentials, emulator userdata, UIAutomator dumps, or generated build output.
- The future release gate must require both version fields to increase, matching signing certificates, `adb install -r` success, preserved DataStore state, and post-upgrade smoke launch.

## File Structure

**Create**

- `app/src/main/java/com/hpre/app/update/SemanticVersion.kt` — strict three-component version parsing and ordering.
- `app/src/main/java/com/hpre/app/update/AppUpdateChecker.kt` — update-check interface, domain result, unavailable categories, and safe official release URL value.
- `app/src/main/java/com/hpre/app/update/GitHubReleaseUpdateChecker.kt` — bounded OkHttp request, Moshi streaming parsing, release validation, and result mapping.
- `app/src/test/java/com/hpre/app/update/SemanticVersionTest.kt` — strict parser/ordering tests.
- `app/src/test/java/com/hpre/app/update/GitHubReleaseUpdateCheckerTest.kt` — MockWebServer request/response/error tests.
- `scripts/release/verify-android-upgrade.ps1` — static APK checks and install-over verification.
- `scripts/release/README.md` — exact future release procedure and examples.
- `docs/screenshots/*.png` — only qualifying emulator captures.

**Modify**

- `gradle/libs.versions.toml` — pin Moshi `1.15.2`.
- `app/build.gradle.kts` — add Moshi runtime dependency.
- `THIRD_PARTY_NOTICES.md` — add Moshi Apache-2.0 attribution.
- `app/src/main/java/com/hpre/app/di/AppContainer.kt` — expose application-scoped `AppUpdateChecker`.
- Three instrumentation `AppContainer` implementations — provide fake/safe checker.
- `app/src/main/java/com/hpre/app/settings/SettingsViewModel.kt` — state machine and manual check action.
- `app/src/main/java/com/hpre/app/settings/SettingsScreen.kt` — update section, status, retry, and release-page action.
- `app/src/main/java/com/hpre/app/navigation/HPreNavHost.kt` — inject checker and release-page opener.
- `app/src/main/res/values/strings.xml` — Vietnamese update strings.
- `app/src/test/java/com/hpre/app/settings/SettingsViewModelTest.kt` — update state transitions/concurrency.
- `app/src/androidTest/java/com/hpre/app/ui/library/LibraryScreenTest.kt` — Settings update UI assertions.
- `README.md` — badges, updater behavior, real screenshots, and future upgrade gate.

---

### Task 1: Strict Update Domain and Semantic Versioning

**Files:**
- Create: `app/src/main/java/com/hpre/app/update/SemanticVersion.kt`
- Create: `app/src/main/java/com/hpre/app/update/AppUpdateChecker.kt`
- Create: `app/src/test/java/com/hpre/app/update/SemanticVersionTest.kt`

**Interfaces:**
- Produces: `SemanticVersion`, `OfficialReleasePage`, `UpdateCheckResult`, `UpdateUnavailableReason`, and `AppUpdateChecker.check(installedVersion: String): UpdateCheckResult`.
- Consumes: no Android UI, OkHttp, JSON, or GitHub types.

- [ ] **Step 1: Write failing strict SemVer tests**

Create `SemanticVersionTest.kt` with tests equivalent to:

```kotlin
class SemanticVersionTest {
    @Test fun parses_installed_version_and_release_tag() {
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parseInstalled("1.2.3"))
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parseTag("v1.2.3"))
    }

    @Test fun orders_major_minor_and_patch_numerically() {
        assertTrue(SemanticVersion(2, 0, 0) > SemanticVersion(1, 99, 99))
        assertTrue(SemanticVersion(1, 3, 0) > SemanticVersion(1, 2, 99))
        assertTrue(SemanticVersion(1, 2, 4) > SemanticVersion(1, 2, 3))
    }

    @Test fun rejects_noncanonical_versions() {
        listOf("1", "1.2", "1.2.3.4", " 1.2.3", "1.2.3 ", "1.2.3-beta", "-1.2.3", "1.02.3", "2147483648.0.0")
            .forEach { assertNull(SemanticVersion.parseInstalled(it)) }
        listOf("1.2.3", "V1.2.3", "v1.2", "v1.2.3-beta")
            .forEach { assertNull(SemanticVersion.parseTag(it)) }
    }
}
```

- [ ] **Step 2: Run the new test and verify RED**

Run:

```powershell
./gradlew.bat testDebugUnitTest --tests "com.hpre.app.update.SemanticVersionTest" --no-daemon
```

Expected: compilation fails because `SemanticVersion` does not exist.

- [ ] **Step 3: Implement strict semantic versions**

Create:

```kotlin
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val installedPattern = Regex("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)")
        private val tagPattern = Regex("v(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)")

        fun parseInstalled(value: String): SemanticVersion? = parse(value, installedPattern)
        fun parseTag(value: String): SemanticVersion? = parse(value, tagPattern)

        private fun parse(value: String, pattern: Regex): SemanticVersion? {
            val match = pattern.matchEntire(value) ?: return null
            val components = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            return SemanticVersion(components[0], components[1], components[2])
        }
    }
}
```

- [ ] **Step 4: Add provider-neutral update types**

Create `AppUpdateChecker.kt` with exact public shapes:

```kotlin
@JvmInline
value class OfficialReleasePage private constructor(val url: String) {
    companion object {
        private const val prefix = "https://github.com/hungnq131193-ux/HPre/releases/tag/"
        fun parse(value: String): OfficialReleasePage? =
            value.takeIf { it.startsWith(prefix) && SemanticVersion.parseTag(it.removePrefix(prefix)) != null }
                ?.let(::OfficialReleasePage)
    }
}

sealed interface UpdateCheckResult {
    data class UpToDate(val installedVersion: SemanticVersion) : UpdateCheckResult
    data class UpdateAvailable(
        val installedVersion: SemanticVersion,
        val latestVersion: SemanticVersion,
        val releasePage: OfficialReleasePage
    ) : UpdateCheckResult
    data class Unavailable(val reason: UpdateUnavailableReason) : UpdateCheckResult
}

enum class UpdateUnavailableReason { NETWORK, RATE_LIMITED, SERVER, INVALID_RESPONSE }

fun interface AppUpdateChecker {
    suspend fun check(installedVersion: String): UpdateCheckResult
}
```

Add tests proving wrong repository, HTTP, query-string, partial tag, and arbitrary path are rejected by `OfficialReleasePage.parse`.

- [ ] **Step 5: Run domain tests GREEN**

```powershell
./gradlew.bat testDebugUnitTest --tests "com.hpre.app.update.*" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit the update domain**

```powershell
git add app/src/main/java/com/hpre/app/update/SemanticVersion.kt app/src/main/java/com/hpre/app/update/AppUpdateChecker.kt app/src/test/java/com/hpre/app/update/SemanticVersionTest.kt
git diff --cached --check
git commit -m "feat: add update version domain"
```

---

### Task 2: GitHub Releases Adapter and Dependency Injection

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `THIRD_PARTY_NOTICES.md`
- Create: `app/src/main/java/com/hpre/app/update/GitHubReleaseUpdateChecker.kt`
- Create: `app/src/test/java/com/hpre/app/update/GitHubReleaseUpdateCheckerTest.kt`
- Modify: `app/src/main/java/com/hpre/app/di/AppContainer.kt`
- Modify: `app/src/androidTest/java/com/hpre/app/navigation/HomeToWatchNavigationTest.kt`
- Modify: `app/src/androidTest/java/com/hpre/app/navigation/NavigationFlowTest.kt`
- Modify: `app/src/androidTest/java/com/hpre/app/testing/TestHPreApplication.kt`

**Interfaces:**
- Consumes: Task 1 domain and the shared `OkHttpClient`.
- Produces: `GitHubReleaseUpdateChecker(client, endpoint)` and `AppContainer.appUpdateChecker`.

- [ ] **Step 1: Add pinned Moshi dependency**

In `libs.versions.toml` add:

```toml
moshi = "1.15.2"
moshi = { module = "com.squareup.moshi:moshi", version.ref = "moshi" }
```

In `app/build.gradle.kts` add `implementation(libs.moshi)`. Add Moshi `1.15.2`, upstream `https://github.com/square/moshi`, license Apache-2.0 to `THIRD_PARTY_NOTICES.md`.

- [ ] **Step 2: Write failing MockWebServer tests**

Create tests with a real `MockWebServer`, an `OkHttpClient` with short test timeouts, and `endpoint = server.url("/repos/hungnq131193-ux/HPre/releases/latest")`. Cover exact cases:

```kotlin
@Test fun newer_stable_release_with_hpre_apk_returns_update_available()
@Test fun equal_or_older_release_returns_up_to_date()
@Test fun request_uses_expected_path_accept_and_safe_user_agent()
@Test fun draft_or_prerelease_is_invalid_response()
@Test fun missing_hpre_apk_asset_is_invalid_response()
@Test fun wrong_repository_or_http_release_url_is_invalid_response()
@Test fun malformed_json_is_invalid_response()
@Test fun http_403_is_rate_limited()
@Test fun http_404_is_invalid_response_and_5xx_is_server()
@Test fun connection_failure_is_network()
@Test fun invalid_installed_version_is_invalid_response_without_request()
```

Use JSON fixture fields `tag_name`, `html_url`, `draft`, `prerelease`, and `assets[].name`; never include tokens.

- [ ] **Step 3: Run adapter tests RED**

```powershell
./gradlew.bat testDebugUnitTest --tests "com.hpre.app.update.GitHubReleaseUpdateCheckerTest" --no-daemon
```

Expected: compilation fails because the adapter does not exist.

- [ ] **Step 4: Implement the GitHub adapter**

Use `Moshi.Builder().build().newJsonReader(body.source())` and parse only `tag_name`, `html_url`, `draft`, `prerelease`, and `assets[].name`. Call `skipValue()` for every unknown field. Store the parsed values in one private internal value:

```kotlin
private data class ParsedRelease(
    val tagName: String?,
    val htmlUrl: String?,
    val draft: Boolean?,
    val prerelease: Boolean?,
    val assetNames: List<String>
)
```

Do not use reflection adapters, `moshi-kotlin`, KSP/codegen, maps of arbitrary JSON values, or Android `org.json`. Missing/wrong-typed required fields map to `INVALID_RESPONSE`.

`GitHubReleaseUpdateChecker` must:

- default endpoint to the exact public API URL;
- build `GET` with `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28`, and `User-Agent: HPre-Android-UpdateChecker`;
- execute with `withContext(Dispatchers.IO)`;
- close response/body with `use`;
- map 403 to `RATE_LIMITED`, 500–599 to `SERVER`, other non-2xx to `INVALID_RESPONSE`, and `IOException` to `NETWORK`;
- parse installed/tag SemVer, validate stable release, official page, and `HPre-*.apk` asset;
- return `UpdateAvailable` only when latest is greater, otherwise `UpToDate`.

- [ ] **Step 5: Run adapter tests GREEN**

```powershell
./gradlew.bat testDebugUnitTest --tests "com.hpre.app.update.*" --no-daemon
```

Expected: PASS with no external network.

- [ ] **Step 6: Wire application-scoped checker**

Add to `AppContainer`:

```kotlin
val appUpdateChecker: AppUpdateChecker
    get() = AppUpdateChecker { UpdateCheckResult.Unavailable(UpdateUnavailableReason.NETWORK) }
```

The safe default prevents all existing test containers from requiring boilerplate and performs no network. Override in `DefaultAppContainer`:

```kotlin
override val appUpdateChecker: AppUpdateChecker by lazy {
    GitHubReleaseUpdateChecker(okHttpClient)
}
```

Optionally override with a fake in the three test containers only where update-specific navigation behavior is asserted. Do not weaken existing test container seams.

- [ ] **Step 7: Compile all source sets and run tests**

```powershell
./gradlew.bat testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug --no-daemon
```

Expected: PASS.

- [ ] **Step 8: Commit adapter and DI**

Stage only the files in this task and commit:

```powershell
git commit -m "feat: check official GitHub releases"
```

---

### Task 3: Manual Settings Update Experience

**Files:**
- Modify: `app/src/main/java/com/hpre/app/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/hpre/app/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/hpre/app/navigation/HPreNavHost.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/test/java/com/hpre/app/settings/SettingsViewModelTest.kt`
- Modify: `app/src/androidTest/java/com/hpre/app/ui/library/LibraryScreenTest.kt`

**Interfaces:**
- Consumes: `AppUpdateChecker` and `BuildConfig.VERSION_NAME`.
- Produces: `UpdateUiState`, `checkForUpdates()`, `releasePageToOpen()`, and `reportReleasePageOpenFailure()`.

- [ ] **Step 1: Write failing ViewModel update-state tests**

Add a fake checker with call count and a controllable deferred result. Add tests:

```kotlin
@Test fun update_check_is_idle_and_makes_no_request_on_construction()
@Test fun check_transitions_from_checking_to_up_to_date()
@Test fun check_exposes_latest_version_and_release_page()
@Test fun repeated_taps_during_active_check_call_checker_once()
@Test fun unavailable_result_maps_to_retryable_error()
@Test fun release_open_failure_preserves_available_release()
```

Expected exact state type:

```kotlin
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val installedVersion: String) : UpdateUiState
    data class UpdateAvailable(
        val installedVersion: String,
        val latestVersion: String,
        val releasePage: OfficialReleasePage,
        val openError: Boolean = false
    ) : UpdateUiState
    data class Error(val reason: UpdateUnavailableReason) : UpdateUiState
}
```

- [ ] **Step 2: Run ViewModel tests RED**

```powershell
./gradlew.bat testDebugUnitTest --tests "com.hpre.app.settings.SettingsViewModelTest" --no-daemon
```

Expected: compilation fails on the missing state/actions.

- [ ] **Step 3: Implement ViewModel state machine**

Change constructor/factory to receive:

```kotlin
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val appUpdateChecker: AppUpdateChecker,
    private val installedVersion: String
)
```

Expose `StateFlow<UpdateUiState>` backed by `MutableStateFlow(Idle)`. `checkForUpdates()` returns immediately while `Checking`, sets `Checking`, calls the checker once in `viewModelScope`, then maps all domain results. `releasePageToOpen()` returns the page only for `UpdateAvailable`. `reportReleasePageOpenFailure()` copies `openError = true` without discarding version/page.

- [ ] **Step 4: Update navigation injection and safe URI opening**

At Settings route, provide `container.settingsRepository`, `container.appUpdateChecker`, and `BuildConfig.VERSION_NAME` to the factory. Pass `onOpenReleasePage: (OfficialReleasePage) -> Unit` to SettingsScreen.

Use `LocalUriHandler.current.openUri(page.url)` at the composable boundary inside `runCatching`; on failure call `viewModel.reportReleasePageOpenFailure()`. Do not accept a raw arbitrary string from UI state.

- [ ] **Step 5: Add Vietnamese strings and Settings UI**

Add exact concepts:

```text
Cập nhật ứng dụng
Phiên bản hiện tại: %1$s
Kiểm tra cập nhật
Đang kiểm tra…
Bạn đang dùng phiên bản mới nhất.
Có phiên bản mới: %1$s
Xem bản phát hành
Không thể kiểm tra cập nhật. Hãy thử lại.
Không thể mở trang phát hành.
Thử lại
```

Add stable tags: `settings_update_section`, `setting_check_update_item`, `settings_update_progress`, `settings_update_current`, `settings_update_available`, `settings_open_release_button`, and `settings_update_error`.

- [ ] **Step 6: Add/update Compose UI tests**

Construct `SettingsViewModel(fakeRepo, fakeChecker, "1.0.0")`. Verify current version and manual check item render, no fake call before click, click transitions to up-to-date, and a fake newer result renders the release button. Keep existing toggle/theme assertions.

- [ ] **Step 7: Run Settings and full gates**

```powershell
./gradlew.bat testDebugUnitTest --tests "com.hpre.app.settings.SettingsViewModelTest" --no-daemon
./gradlew.bat testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin --no-daemon
```

Expected: PASS.

- [ ] **Step 8: Commit manual updater UI**

Stage only Settings/navigation/resources/tests and commit:

```powershell
git commit -m "feat: add manual update check"
```

---

### Task 4: Future Release Install-Over Gate

**Files:**
- Create: `scripts/release/verify-android-upgrade.ps1`
- Create: `scripts/release/README.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: `-BaselineApk`, `-CandidateApk`, optional `-DeviceSerial`, default package `com.hpre.app`.
- Produces: non-zero exit on any failed invariant and sanitized evidence on success.

- [ ] **Step 1: Implement strict script parameters and tool discovery**

Use PowerShell 5.1-compatible syntax:

```powershell
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$BaselineApk,
    [Parameter(Mandatory=$true)][string]$CandidateApk,
    [string]$ExpectedApplicationId = 'com.hpre.app',
    [string]$DeviceSerial,
    [switch]$StaticOnly
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
```

Resolve `ANDROID_SDK_ROOT`/`ANDROID_HOME`, newest installed build-tools containing `apksigner` and `aapt`, and `platform-tools/adb`. Verify both APK paths are files and non-empty. Never inspect or print keystore credentials.

- [ ] **Step 2: Add static metadata/signature parsing**

Run `apksigner verify --verbose --print-certs` and `aapt dump badging` for both APKs. Parse:

- package name;
- versionCode as `[long]`;
- strict three-part versionName;
- signer certificate SHA-256;
- candidate filename.

Fail unless both package IDs equal the expected ID, candidate version name is SemVer-greater, candidate version code is greater, fingerprints match, candidate filename matches `^HPre-.*\.apk$`, and filename does not contain `debug` or `unsigned`.

- [ ] **Step 3: Add deterministic device selection and baseline install**

When not `-StaticOnly`, parse `adb devices` for `device` entries. Require exactly one device unless `-DeviceSerial` is supplied. Use `adb -s SERIAL` for every command. Begin with `adb uninstall com.hpre.app`, tolerating only “not installed” status, then require `adb install BASELINE` to print `Success`.

Verify baseline version via:

```powershell
adb -s SERIAL shell dumpsys package com.hpre.app
```

- [ ] **Step 4: Create and verify DataStore marker with UIAutomator**

Launch `com.hpre.app/.MainActivity`, navigate to Settings with stable content descriptions/test-visible text, dump hierarchy to `/sdcard/hpre-upgrade-before.xml`, pull to a temp path, and delete the device copy. Click **Phát trong nền** only if currently enabled; dump again and require the associated switch node reports `checked="false"`.

The script must use a temporary directory under `$env:TEMP`, delete dumps in `finally`, and never commit them. If the node cannot be identified unambiguously, fail with a safe diagnostic.

- [ ] **Step 5: Install over and prove persistence**

Require:

```powershell
adb -s SERIAL install -r CANDIDATE
```

to return `Success`. Confirm candidate version in dumpsys, relaunch Settings, dump UI, and require **Phát trong nền** remains unchecked. Launch the app once more and require the package process/activity is present. Do not uninstall between marker creation and `install -r`.

- [ ] **Step 6: Add script documentation and safe current validation**

Document the future command using concrete paths:

```powershell
.\scripts\release\verify-android-upgrade.ps1 `
  -BaselineApk "$env:USERPROFILE\HPre-release\HPre-v1.0.0-release.apk" `
  -CandidateApk "$env:USERPROFILE\HPre-release\HPre-v1.0.1-release.apk"
```

Document required source bump to `versionName = "1.0.1"`, `versionCode = 2`, same HPre certificate, and that tag/upload are forbidden before script success.

Validate the current script safely by passing the v1.0.0 APK as both baseline and candidate with `-StaticOnly`; expected result is failure specifically because versionName/versionCode did not increase. This proves the release guard rejects an unbumped candidate without altering a device.

- [ ] **Step 7: Commit upgrade tooling**

```powershell
git add scripts/release README.md
git diff --cached --check
git commit -m "build: add Android upgrade verification gate"
```

---

### Task 5: Capture Real Emulator Screenshots and Add README Badges

**Files:**
- Create conditionally: `docs/screenshots/home.png`
- Create conditionally: `docs/screenshots/search.png`
- Create conditionally: `docs/screenshots/player.png`
- Create: `docs/screenshots/settings.png`
- Modify: `README.md`

**Interfaces:**
- Consumes: current debug APK, API 35 AVD, ADB, and completed Settings updater UI.
- Produces: only real PNG captures and four accurate README badges.

- [ ] **Step 1: Verify CI and source state before capture**

Query GitHub Actions and require latest `main` Android CI conclusion `success`. Confirm Git clean, `versionName 1.0.0`, `versionCode 1`, and no `v1.0.1` tag/release.

- [ ] **Step 2: Build and launch the emulator**

Build:

```powershell
./gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon
```

Start the existing API 35 AVD with a writable normal boot, wait for `adb wait-for-device`, and poll `getprop sys.boot_completed` until `1`. Set portrait orientation, dismiss keyguard, and install with:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am force-stop com.hpre.app
adb shell monkey -p com.hpre.app -c android.intent.category.LAUNCHER 1
```

- [ ] **Step 3: Capture and qualify Settings**

Navigate to Settings, wait for idle, and ensure the update section/current version is visible. Capture:

```powershell
adb exec-out screencap -p > docs\screenshots\settings.png
```

Because PowerShell redirection can corrupt binary bytes in 5.1, execute capture through `cmd.exe /c` or capture on-device then `adb pull`. Verify PNG signature bytes `89 50 4E 47`, dimensions greater than 0, file size greater than 10 KB, and visually inspect with the image reader.

- [ ] **Step 4: Attempt Home, Search, and Player captures**

For each target, navigate through actual HPre UI, wait for stable public content, use UIAutomator hierarchy to confirm the named screen, capture PNG, and inspect visually. Delete a capture if it shows an error-only state, blank feed, keyboard, system dialog, unrelated app, loading-only state, or misleading content. Settings is mandatory; the other three are conditional on genuine usable rendering.

- [ ] **Step 5: Add accurate badges and screenshot table**

Directly under `# HPre`, add:

```markdown
[![Android CI](https://github.com/hungnq131193-ux/HPre/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/hungnq131193-ux/HPre/actions/workflows/android.yml)
[![Latest Release](https://img.shields.io/github/v/release/hungnq131193-ux/HPre)](https://github.com/hungnq131193-ux/HPre/releases/latest)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)
[![Android API](https://img.shields.io/badge/Android-API%2026%2B-3DDC84.svg?logo=android)](https://developer.android.com/about/versions/oreo)
```

Replace the current “Sẽ được bổ sung sau.” screenshot text with a two-column HTML table containing only committed image files, each `width="320"`, with Vietnamese captions/alt text. Add the manual update-check feature to the real feature list and state that it opens official GitHub Releases.

- [ ] **Step 6: Verify documentation assets**

Check every README image path exists and each file has a PNG signature/non-zero dimensions. Run BrandIdentityTest to ensure no old brand appears in tracked paths/content. Confirm `.apk`, emulator images, UI dumps, and temporary capture files are not staged.

- [ ] **Step 7: Run full local gate and commit**

```powershell
./gradlew.bat testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin --no-daemon
git diff --check
git add README.md docs/screenshots
git commit -m "docs: add real HPre screenshots"
```

---

### Task 6: Final Validation, Push, and CI Confirmation

**Files:**
- Inspect all implementation files; no release artifact changes.

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces: synchronized green `main` while retaining v1.0.0 source version and release baseline.

- [ ] **Step 1: Run complete local validation from clean outputs**

```powershell
./gradlew.bat --stop
./gradlew.bat clean testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin --no-daemon
```

Run updater network tests separately and confirm no external network is used. Run upgrade script static negative test and require the expected version-gate failure.

- [ ] **Step 2: Audit scope and release invariants**

Require:

- source version remains `1.0.0`/`1`;
- `v1.0.1` does not exist locally, remotely, or as a GitHub Release;
- published `v1.0.0` asset name/size/digest remain unchanged;
- no APK/AAB/keystore/local config/UI dump is tracked;
- updater endpoint and release URL are the official repository only;
- no startup/background invocation of `AppUpdateChecker.check` exists;
- README badges and image paths resolve.

- [ ] **Step 3: Review commits and push main**

Inspect `git status`, `git diff`, `git log --oneline -10`, and remote tracking. Push without force:

```powershell
git push origin main
```

- [ ] **Step 4: Wait for Android CI and verify every step**

Find the run by exact HEAD SHA, wait with `gh run watch --exit-status`, then query JSON. Require job conclusion `success`, including **Test, lint and build debug APK**. The Node-action deprecation warning may be reported as a limitation but does not replace a green conclusion.

- [ ] **Step 5: Report prepared-not-released status**

Report:

- CI URL/conclusion;
- updater architecture and tests;
- committed real screenshot filenames;
- badge URLs;
- upgrade script path and negative guard evidence;
- source version remains `1.0.0`/`1`;
- no `v1.0.1` release was created;
- exact future command required to prove install-over before publication.
