# HPre Public Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish HPre 1.0.0 source and a locally signed, independently verified `HPre-v1.0.0-release.apk` through a standard public GitHub repository and GitHub Release.

**Architecture:** Preserve the single-module Android application and its current functional changes. Separate public source concerns from local release credentials: Gradle accepts lazy project-property or environment-variable signing input, CI builds only debug, and the local release pipeline owns signing and artifact verification. Publication is a gated sequence in which secret, test, signature, metadata, license, Git, and remote-asset checks must all pass.

**Tech Stack:** Kotlin 2.1.20, Android Gradle Plugin 8.8.2, Gradle 8.11.1, Java 17, Jetpack Compose, Media3 1.5.1, Room 2.6.1, NewPipeExtractor v0.26.5, PowerShell 5.1, Android SDK build-tools, Git, GitHub CLI.

**Spec:** `docs/superpowers/specs/2026-08-28-hpre-public-release-design.md`

## Global Constraints

- Product name is exactly `HPre`; do not change namespace or application ID `com.hpre.app`.
- Keep `versionName = "1.0.0"`, `versionCode = 1`, and release tag `v1.0.0`.
- Keep min SDK 26, target SDK 35, compile SDK 35, Java target 17, AGP 8.8.2, Gradle 8.11.1, and Kotlin 2.1.20.
- Preserve all pre-existing worktree changes; do not reset, discard, or overwrite them.
- Use GPL-3.0-or-later for HPre because the distributed APK contains NewPipeExtractor v0.26.5.
- Never print, commit, upload, or include in reports a keystore password, private key, OAuth credential, API key, or environment secret.
- Never commit an APK/AAB or keystore. The signed APK is a GitHub Release asset only.
- Do not create or overwrite a signing identity until candidate HPre keys have been checked.
- Do not force-push, rewrite history, amend commits, or overwrite an existing release tag.
- Do not claim completion unless `apksigner` verifies the final renamed APK and GitHub reports a non-empty asset with the exact expected filename.

## File Structure

**Modify**

- `.gitignore` — complete Android, credential, signing, environment, and artifact exclusions.
- `app/build.gradle.kts` — lazy external signing inputs that do not break unsigned debug/CI configuration.

**Create**

- `README.md` — Vietnamese public overview, implemented features, build/download/install guidance, architecture, screenshots status, and disclaimer.
- `LICENSE` — canonical GNU GPL v3 license text; project declaration is GPL-3.0-or-later.
- `THIRD_PARTY_NOTICES.md` — material dependency versions, upstream links, and licenses.
- `CONTRIBUTING.md` — contribution/build/test and secret-handling policy.
- `SECURITY.md` — private vulnerability reporting and credential disclosure rules without an invented email.
- `.github/ISSUE_TEMPLATE/bug_report.yml` — structured bug report form.
- `.github/ISSUE_TEMPLATE/feature_request.yml` — structured feature request form.
- `.github/ISSUE_TEMPLATE/config.yml` — disable blank issues and expose no fabricated contact endpoint.
- `.github/workflows/android.yml` — pinned-action Java 17 debug test/build CI with no signing material.

**Local only; never create inside the repository**

- `C:\Users\HUNG\secure\hpre\hpre-release.jks` — stable release signing identity if no valid HPre key exists.
- `C:\Users\HUNG\secure\hpre\README-KEY-BACKUP.txt` — backup warning without credentials.
- `C:\Users\HUNG\secure\hpre\signing-info.txt` — alias, path, and certificate SHA-256 only.
- `C:\Users\HUNG\secure\hpre\signing-credentials.xml` — DPAPI-encrypted `PSCredential`, readable only by the same Windows user on the same machine; never committed or reported.
- `C:\Users\HUNG\HPre-release\HPre-v1.0.0-release.apk` — final immutable release asset.
- `C:\Users\HUNG\HPre-release\release-notes-v1.0.0.md` — local GitHub Release notes without secrets.

---

### Task 1: Harden Source and Signing Configuration

**Files:**
- Modify: `.gitignore`
- Modify: `app/build.gradle.kts:35-48`

**Interfaces:**
- Consumes: existing Gradle properties `hpreSigning.storeFile`, `hpreSigning.storePassword`, `hpreSigning.keyAlias`, `hpreSigning.keyPassword`.
- Produces: equivalent environment inputs `HPRE_SIGNING_STORE_FILE`, `HPRE_SIGNING_STORE_PASSWORD`, `HPRE_SIGNING_KEY_ALIAS`, `HPRE_SIGNING_KEY_PASSWORD`; debug tasks configure without any signing inputs; release tasks fail with a concise missing-signing message.

- [ ] **Step 1: Record the pre-existing worktree boundary**

Run:

```powershell
git status --short --branch
git diff --stat
git diff -- app/build.gradle.kts
```

Expected: the nine pre-existing functional paths remain present, including the untracked `WatchStateCache.kt`; `app/build.gradle.kts` contains the pending debug-to-release signing change. Save only path-level evidence, not signing values.

- [ ] **Step 2: Expand `.gitignore` with exact release exclusions**

Replace duplicate rules with this grouped content while retaining `.superpowers/` and `.worktrees/` exclusions:

```gitignore
# Android Studio and Gradle
*.iml
.gradle/
.idea/
.kotlin/
local.properties
.android/
captures/

# Build output
build/
*/build/
.externalNativeBuild/
.cxx/

# Signing and local credentials
*.jks
*.keystore
keystore.properties
signing.properties
.env
.env.*
google-services.json

# Release artifacts and logs
*.apk
*.aab
*.log

# OS metadata
.DS_Store
Thumbs.db

# Local agent/worktree state
.superpowers/
.worktrees/
```

- [ ] **Step 3: Make signing lookup lazy and dual-source**

In `app/build.gradle.kts`, define a helper above `android {}`:

```kotlin
fun signingValue(propertyName: String, environmentName: String): Provider<String> =
    providers.gradleProperty(propertyName)
        .orElse(providers.environmentVariable(environmentName))
```

Import `org.gradle.api.provider.Provider`, then resolve values only inside the release signing block with clear messages:

```kotlin
signingConfigs {
    create("release") {
        storeFile = file(
            signingValue("hpreSigning.storeFile", "HPRE_SIGNING_STORE_FILE")
                .orNull ?: "missing-hpre-release-keystore"
        )
        storePassword = signingValue(
            "hpreSigning.storePassword",
            "HPRE_SIGNING_STORE_PASSWORD"
        ).orNull
        keyAlias = signingValue(
            "hpreSigning.keyAlias",
            "HPRE_SIGNING_KEY_ALIAS"
        ).orNull
        keyPassword = signingValue(
            "hpreSigning.keyPassword",
            "HPRE_SIGNING_KEY_PASSWORD"
        ).orNull
    }
}
```

Keep `release.signingConfig = signingConfigs.getByName("release")`. Do not add secrets or defaults. Android signing validation will reject a release task when credentials are absent, while configuration and debug tasks remain available.

- [ ] **Step 4: Verify debug configuration works without signing credentials**

Locate the known JDK and SDK from `local.properties` and installed directories without printing local property contents. Set only process-scoped `JAVA_HOME` and SDK paths, then run:

```powershell
./gradlew.bat help --no-daemon
./gradlew.bat tasks --all --no-daemon
```

Expected: both commands exit 0 with all `HPRE_SIGNING_*` variables unset.

- [ ] **Step 5: Verify release refuses absent credentials**

Run with all `HPRE_SIGNING_*` variables removed from the process:

```powershell
./gradlew.bat signingReport --no-daemon
```

Expected: debug identity is reported; release is absent/invalid and no private release material is disclosed. If `signingReport` itself succeeds, later `assembleRelease` without credentials must fail rather than emit a signed-looking artifact.

- [ ] **Step 6: Audit ignored high-risk file types**

Run:

```powershell
git check-ignore -v local.properties test.jks test.keystore keystore.properties signing.properties .env sample.apk sample.aab
git ls-files | rg -i '(\.jks$|\.keystore$|(^|/)local\.properties$|(^|/)\.env($|\.)|\.apk$|\.aab$)'
```

Expected: every synthetic path is ignored; the tracked-file command returns no matches.

- [ ] **Step 7: Commit the isolated release configuration**

```powershell
git add -- .gitignore app/build.gradle.kts
git diff --cached --check
git diff --cached --name-status
git commit -m "build: secure HPre release signing"
```

Expected: only `.gitignore` and `app/build.gradle.kts` are committed.

---

### Task 2: Create the Public Project Profile

**Files:**
- Create: `README.md`
- Create: `LICENSE`
- Create: `THIRD_PARTY_NOTICES.md`
- Create: `CONTRIBUTING.md`
- Create: `SECURITY.md`
- Create: `.github/ISSUE_TEMPLATE/bug_report.yml`
- Create: `.github/ISSUE_TEMPLATE/feature_request.yml`
- Create: `.github/ISSUE_TEMPLATE/config.yml`
- Create: `.github/workflows/android.yml`

**Interfaces:**
- Consumes: confirmed project metadata and `docs/dependency-decision.md` provenance inventory.
- Produces: a GPL-3.0-or-later public repository profile and unsigned debug CI.

- [ ] **Step 1: Write `README.md` from verified features**

Use Vietnamese sections in this order: `HPre`, `Giới thiệu`, `Tính năng`, `Screenshots`, `Công nghệ`, `Kiến trúc`, `Build`, `Phát hành bản release`, `Tải xuống`, `Cài đặt`, `Giấy phép`, `Tuyên bố miễn trừ`.

The feature list must be limited to: home feed, video/channel/playlist search, video and audio-only playback, quality/speed controls, fullscreen, mini-player, background playback, Picture-in-Picture, comments/related content, local history, local playlists, local subscriptions, and system/light/dark themes. Use this architecture block:

```text
Compose UI / Navigation
        ↓
ViewModel
        ↓
Repository
        ↓
VideoService adapter
        ↓
NewPipeExtractor

Playback UI
        ↓
MediaSession / Media3 ExoPlayer
```

Use `Sẽ được bổ sung sau.` under Screenshots because `docs/screenshots/` is absent. State that HPre uses NewPipeExtractor for public stream/data extraction and does not use the official YouTube Data API for that path. Do not use “YouTube Premium miễn phí” or “Official YouTube client”. Include the exact disclaimer:

```text
HPre là một dự án độc lập và không được phát triển, tài trợ hoặc xác nhận bởi YouTube hoặc Google. Các thương hiệu và nội dung của bên thứ ba thuộc về chủ sở hữu tương ứng.
```

- [ ] **Step 2: Add GPL-3.0-or-later licensing**

Create `LICENSE` from the canonical GNU General Public License version 3 text at `https://www.gnu.org/licenses/gpl-3.0.txt`, preserving the complete text verbatim. In `README.md`, state `HPre được phát hành theo GPL-3.0-or-later` and link `LICENSE`. Do not relabel dependency licenses as HPre copyright.

- [ ] **Step 3: Write third-party notices from pinned dependencies**

Create a Markdown table with name, version, upstream, and license for at least:

```text
NewPipeExtractor | v0.26.5 | https://github.com/TeamNewPipe/NewPipeExtractor | GPL-3.0-or-later
AndroidX / Jetpack Compose | BOM 2025.02.00 | https://developer.android.com/jetpack/androidx | Apache-2.0
AndroidX Media3 | 1.5.1 | https://github.com/androidx/media | Apache-2.0
AndroidX Room | 2.6.1 | https://developer.android.com/jetpack/androidx/releases/room | Apache-2.0
OkHttp | 4.12.0 | https://github.com/square/okhttp | Apache-2.0
Coil | 2.7.0 | https://github.com/coil-kt/coil | Apache-2.0
Kotlin Coroutines | 1.10.1 | https://github.com/Kotlin/kotlinx.coroutines | Apache-2.0
JUnit 4 | 4.13.2 | https://github.com/junit-team/junit4 | EPL-1.0
```

Explain that notices are attribution, not a change to upstream licenses, and direct readers to upstream license files for exact terms.

- [ ] **Step 4: Write contribution and security policies**

`CONTRIBUTING.md` must require a fork/topic branch, Java 17, `./gradlew.bat testDebugUnitTest lintDebug assembleDebug`, focused PRs, and no secrets, keystores, local properties, APK/AAB files, or copyrighted media fixtures.

`SECURITY.md` must direct reporters to the repository's GitHub **Security → Report a vulnerability** path when enabled, otherwise ask maintainers to enable private vulnerability reporting before sharing details. Explicitly prohibit public issue disclosure of credentials, signing keys, exploit details, or private user data. Do not add an email address.

- [ ] **Step 5: Add issue forms**

`bug_report.yml` must require HPre version, Android version, device model, reproduction steps, expected behavior, actual behavior, and optional redacted logs. Include a visible warning not to post tokens, cookies, credentials, personal data, or signing material.

`feature_request.yml` must require problem, proposed behavior, alternatives, and scope. `config.yml` must set `blank_issues_enabled: false` and `contact_links: []`.

- [ ] **Step 6: Add unsigned debug CI with pinned versions**

Create `.github/workflows/android.yml` for pushes and pull requests to `main`, with read-only contents permission and concurrency cancellation. Use exact action releases:

```yaml
- uses: actions/checkout@v4.2.2
- uses: actions/setup-java@v4.7.1
  with:
    distribution: temurin
    java-version: '17'
    cache: gradle
- uses: gradle/actions/setup-gradle@v4.4.2
- run: ./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon
```

Set `gradlew` executable permission in CI before the build on non-Windows runners. Do not add release assembly, credential secrets, artifact signing, or a base64 keystore.

- [ ] **Step 7: Validate public files**

Run:

```powershell
rg -n -i 'youtube premium miễn phí|official youtube client|storePassword\s*=\s*"|keyPassword\s*=\s*"|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY' README.md LICENSE THIRD_PARTY_NOTICES.md CONTRIBUTING.md SECURITY.md .github
git diff --check
./gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon
```

Expected: prohibited-content search has no matches; formatting check exits 0; all Gradle tasks pass without release signing credentials.

- [ ] **Step 8: Commit the public project profile**

```powershell
git add -- README.md LICENSE THIRD_PARTY_NOTICES.md CONTRIBUTING.md SECURITY.md .github
git diff --cached --check
git diff --cached --name-status
git commit -m "docs: prepare HPre public project"
```

Expected: only public profile and CI files are committed.

---

### Task 3: Validate and Commit Existing Functional Work

**Files:**
- Existing modified: `app/src/main/java/com/hpre/app/di/AppContainer.kt`
- Existing modified: `app/src/main/java/com/hpre/app/extractor/OkHttpDownloader.kt`
- Existing modified: `app/src/main/java/com/hpre/app/navigation/HPreNavHost.kt`
- Existing modified: `app/src/main/java/com/hpre/app/navigation/RootScaffold.kt`
- Existing modified: `app/src/main/java/com/hpre/app/player/SessionPlayerController.kt`
- Existing modified: `app/src/main/java/com/hpre/app/ui/watch/WatchViewModel.kt`
- Existing modified: `app/src/test/java/com/hpre/app/player/SessionPlayerProtocolTest.kt`
- Existing untracked: `app/src/main/java/com/hpre/app/repository/WatchStateCache.kt`

**Interfaces:**
- Consumes: pre-existing uncommitted playback continuity changes; this task must not redesign them.
- Produces: reviewed, tested source state that can be tagged and distributed.

- [ ] **Step 1: Review every pre-existing diff for release relevance and safety**

Run:

```powershell
git diff -- app/src/main app/src/test
git diff --check -- app/src/main app/src/test
```

Confirm the changes implement watch/playback continuity and no file contains credentials, hardcoded cookies, authorization headers, private URLs, debug logging of stream URLs, or unrelated generated output. Do not modify behavior merely to simplify the release task.

- [ ] **Step 2: Run targeted tests for changed behavior**

Run the exact changed test class first:

```powershell
./gradlew.bat testDebugUnitTest --tests "com.hpre.app.player.SessionPlayerProtocolTest" --no-daemon
```

Then identify and run repository/watch/navigation tests by class names returned from:

```powershell
rg -n 'class .*Test' app/src/test/java/com/hpre/app/repository app/src/test/java/com/hpre/app/ui app/src/test/java/com/hpre/app/navigation app/src/test/java/com/hpre/app/player
```

Expected: all selected tests pass. If a test fails, invoke systematic debugging; do not suppress, delete, or weaken assertions.

- [ ] **Step 3: Run the complete source validation gate**

```powershell
./gradlew.bat clean testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin --no-daemon
```

Expected: all tasks pass and a debug APK is generated only under ignored build output.

- [ ] **Step 4: Commit only the reviewed functional paths**

```powershell
git add -- app/src/main/java/com/hpre/app/di/AppContainer.kt app/src/main/java/com/hpre/app/extractor/OkHttpDownloader.kt app/src/main/java/com/hpre/app/navigation/HPreNavHost.kt app/src/main/java/com/hpre/app/navigation/RootScaffold.kt app/src/main/java/com/hpre/app/player/SessionPlayerController.kt app/src/main/java/com/hpre/app/ui/watch/WatchViewModel.kt app/src/main/java/com/hpre/app/repository/WatchStateCache.kt app/src/test/java/com/hpre/app/player/SessionPlayerProtocolTest.kt
git diff --cached --check
git diff --cached --name-status
git commit -m "fix: preserve HPre playback state"
```

Expected: the worktree contains no remaining functional edits after commit.

---

### Task 4: Run the Final Secret and Provenance Gate

**Files:**
- Inspect only; modify files only if a confirmed finding requires remediation.

**Interfaces:**
- Consumes: complete intended source tree and Git history.
- Produces: a path/classification-only scan report and an explicit pass/block decision.

- [ ] **Step 1: List tracked and ignored release-sensitive files**

```powershell
git status --short --branch
git ls-files
git status --ignored --short
```

Expected: build output, `local.properties`, APK files, and any signing files are ignored and absent from tracked files.

- [ ] **Step 2: Scan tracked content without printing matching values**

Use a PowerShell loop that records only file path and pattern category. Categories are: private-key marker, high-confidence token format, password assignment, authorization header, signing binary, environment file, APK/AAB. Do not emit matching lines. Review keyword-only hits (`token`, `secret`, `password`, `Authorization`, `Bearer`, `apiKey`) manually as code/test fixtures versus credentials.

Expected: no confirmed credential. Property names in `app/build.gradle.kts` and synthetic test fixtures are documented as false positives by category only.

- [ ] **Step 3: Scan reachable history and staged content**

Enumerate commits with `git rev-list --all`, inspect each tree for sensitive filenames and high-confidence patterns, and report only commit hash, path, and category. Then run the same scan over `git diff --cached --binary` without printing content.

Expected: no confirmed secret or signing binary in reachable history. If one is found, stop before remote creation; revoke/rotate it first and request explicit approval before any history rewrite.

- [ ] **Step 4: Verify project identity and license consistency**

```powershell
rg -n 'HPre|com\.hpre\.app|versionCode|versionName|minSdk|targetSdk|compileSdk' app settings.gradle.kts README.md
rg -n 'GPL-3\.0-or-later|NewPipeExtractor' README.md THIRD_PARTY_NOTICES.md docs/dependency-decision.md
```

Expected: public name is `HPre`, package/version remain unchanged, and NewPipeExtractor/GPL statements are consistent.

- [ ] **Step 5: Record the gate result**

Record `PASS` only when current files, tracked files, staged files, and reachable history contain no confirmed secret. Keep the evidence in the execution report, not in a file that could expose local paths unnecessarily.

---

### Task 5: Establish the Stable Local Signing Identity

**Files:**
- Local create if needed: `C:\Users\HUNG\secure\hpre\hpre-release.jks`
- Local create: `C:\Users\HUNG\secure\hpre\README-KEY-BACKUP.txt`
- Local create: `C:\Users\HUNG\secure\hpre\signing-info.txt`
- Local create if a new key is needed: `C:\Users\HUNG\secure\hpre\signing-credentials.xml`

**Interfaces:**
- Consumes: `keytool` from a verified Java 17 installation and candidate keystore paths.
- Produces: a stable key, DPAPI-protected local credentials, process-scoped `HPRE_SIGNING_*` values, and one certificate SHA-256 fingerprint; no repository file.

- [ ] **Step 1: Locate tooling and candidate keys narrowly**

Search `C:\Users\HUNG\secure`, `C:\Users\HUNG\.gradle`, `C:\Users\HUNG\Desktop\Flowtube`, and filenames containing `hpre` under the user profile for `*.jks`/`*.keystore`. Do not recursively enumerate unrelated files or print file contents.

Locate `keytool.exe`, Java 17, Android SDK `apksigner.bat`, `aapt.exe`, and optionally `apkanalyzer.bat`. Verify versions explicitly.

- [ ] **Step 2: Evaluate any HPre key candidate**

For each plausible candidate, use `keytool -list -v` with password supplied via a process-scoped environment variable and `-storepass:env`. Capture alias, validity, subject, and SHA-256 certificate fingerprint only. Compare with any prior official HPre APK using `apksigner verify --print-certs`.

Expected: reuse only a key with evidence that it is the intended HPre identity. An unrelated Android debug keystore is rejected.

- [ ] **Step 3: Create a new key only when no HPre key exists**

Verify parent `C:\Users\HUNG\secure` before creating `hpre`. Generate a cryptographically random password in memory and save it outside the repository as a DPAPI-encrypted `PSCredential` using `Export-Clixml`. DPAPI binds the encrypted payload to the current Windows user and machine; it is not plaintext and must still be backed up separately because it is not portable. Convert the secure value to plaintext only inside the current PowerShell process, set process-scoped signing variables, call keytool with environment password modifiers, then clear the temporary plaintext variable:

```powershell
$credentialPath = "$env:USERPROFILE\secure\hpre\signing-credentials.xml"
$passwordBytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($passwordBytes)
$passwordText = [Convert]::ToBase64String($passwordBytes)
$securePassword = ConvertTo-SecureString $passwordText -AsPlainText -Force
$credential = New-Object System.Management.Automation.PSCredential('hpre', $securePassword)
$credential | Export-Clixml -LiteralPath $credentialPath
$env:HPRE_SIGNING_STORE_PASSWORD = $passwordText
$env:HPRE_SIGNING_KEY_PASSWORD = $passwordText
keytool -genkeypair -v -keystore "$env:USERPROFILE\secure\hpre\hpre-release.jks" -storepass:env HPRE_SIGNING_STORE_PASSWORD -keypass:env HPRE_SIGNING_KEY_PASSWORD -alias hpre -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=HPre, O=HPre, C=VN"
$passwordText = $null
[Array]::Clear($passwordBytes, 0, $passwordBytes.Length)
```

Expected: key generation succeeds once. Never rerun over an existing file.

- [ ] **Step 4: Verify the signing identity**

In a fresh PowerShell process, import the DPAPI credential, expose it only to that process, run verification, then clear the environment value:

```powershell
$credential = Import-Clixml -LiteralPath "$env:USERPROFILE\secure\hpre\signing-credentials.xml"
$plain = $credential.GetNetworkCredential().Password
$env:HPRE_SIGNING_STORE_PASSWORD = $plain
keytool -list -v -keystore "$env:USERPROFILE\secure\hpre\hpre-release.jks" -storepass:env HPRE_SIGNING_STORE_PASSWORD -alias hpre
$plain = $null
Remove-Item Env:HPRE_SIGNING_STORE_PASSWORD -ErrorAction SilentlyContinue
```

Expected: alias `hpre`, RSA 4096 certificate, valid dates, and SHA-256 fingerprint. Redact output to the allowed certificate fields in the report.

- [ ] **Step 5: Create password-free backup metadata**

Write `README-KEY-BACKUP.txt` explaining in Vietnamese that this is HPre's permanent release key, every update must use it, losing it prevents compatible updates, and it must never be uploaded publicly. Write `signing-info.txt` with only:

```text
App: HPre
Alias: hpre
Keystore: C:\Users\HUNG\secure\hpre\hpre-release.jks
Certificate SHA-256: $certificateFingerprint
```

Do not store passwords in either text file. `signing-credentials.xml` is encrypted, machine/user-bound local state rather than a portable backup. Tell the user to back up the keystore and its credential separately in secure offline storage; do not treat the DPAPI file alone as sufficient disaster recovery.

- [ ] **Step 6: Bind process-scoped Gradle signing inputs**

At the start of every signing/build PowerShell process, import the DPAPI credential and set `HPRE_SIGNING_STORE_FILE`, `HPRE_SIGNING_STORE_PASSWORD`, `HPRE_SIGNING_KEY_ALIAS=hpre`, and `HPRE_SIGNING_KEY_PASSWORD` only for that process. Run all dependent Gradle and verification commands in the same process, then remove the four environment variables and clear temporary plaintext references. Do not write them to project files, command history, release notes, or report output.

---

### Task 6: Build, Verify, and Freeze the Release APK

**Files:**
- Generated ignored: `app/build/outputs/apk/release/app-release.apk`
- Local create: `C:\Users\HUNG\HPre-release\HPre-v1.0.0-release.apk`

**Interfaces:**
- Consumes: process-scoped signing environment and verified Java/Android SDK tools.
- Produces: immutable signed APK, APK SHA-256, certificate SHA-256, size, and verified metadata.

- [ ] **Step 1: Record tool and source identity**

```powershell
java -version
./gradlew.bat --version
git rev-parse HEAD
git status --short --branch
```

Expected: Java 17, Gradle 8.11.1, and a clean intended source state. Do not proceed from an unreviewed dirty tree.

- [ ] **Step 2: Run clean and all tests**

```powershell
./gradlew.bat clean --no-daemon
./gradlew.bat test --no-daemon
```

Expected: both pass.

- [ ] **Step 3: Run release lint and signed assembly**

```powershell
./gradlew.bat lint --no-daemon
./gradlew.bat assembleRelease --no-daemon
```

Expected: both pass. Do not use `lintVitalRelease` suppression or `abortOnError = false` to bypass findings.

- [ ] **Step 4: Identify only the fresh release output**

Enumerate `app\build\outputs\apk\release\*.apk` and `output-metadata.json`. Reject filenames containing `debug` or `unsigned`. Confirm exactly one intended fresh release APK and metadata variant `release`.

- [ ] **Step 5: Verify the Gradle output signature**

```powershell
apksigner verify --verbose --print-certs "app\build\outputs\apk\release\app-release.apk"
```

Expected: verification succeeds, at least one modern APK signature scheme is verified, and certificate SHA-256 equals the established signing identity. Any mismatch blocks publication.

- [ ] **Step 6: Copy and rename outside the source tree**

Verify parent `C:\Users\HUNG` before creating `HPre-release`, then copy without modifying bytes:

```powershell
Copy-Item -LiteralPath "app\build\outputs\apk\release\app-release.apk" -Destination "$env:USERPROFILE\HPre-release\HPre-v1.0.0-release.apk"
```

- [ ] **Step 7: Verify the final renamed artifact again**

```powershell
apksigner verify --verbose --print-certs "$env:USERPROFILE\HPre-release\HPre-v1.0.0-release.apk"
```

Expected: the same successful signature result and certificate fingerprint.

- [ ] **Step 8: Verify APK metadata**

Run `aapt dump badging` or `apkanalyzer manifest application-id/version-name/version-code/min-sdk/target-sdk` on the final file. Expected values:

```text
package: com.hpre.app
versionCode: 1
versionName: 1.0.0
minSdk: 26
targetSdk: 35
application label: HPre
```

- [ ] **Step 9: Compare source and destination bytes, then hash**

Compute SHA-256 for both Gradle output and final copy and require equality. Record final byte size and uppercase SHA-256. Do not alter the APK after this point.

- [ ] **Step 10: Confirm APK is absent from Git**

```powershell
git status --short --ignored
git ls-files | rg -i '\.(apk|aab)$'
```

Expected: no tracked APK/AAB and clean source status.

---

### Task 7: Publish Source and Tag to GitHub

**Files:**
- Git/GitHub state only.

**Interfaces:**
- Consumes: clean tested source commit, secret-scan PASS, signed/frozen artifact metadata.
- Produces: public `HPre` repository, synchronized `main`, and immutable `v1.0.0` tag.

- [ ] **Step 1: Inspect Git publication state**

```powershell
git status --short --branch
git diff
git log --oneline -10
git remote -v
git tag --list v1.0.0
```

Expected: clean tree, no remote or an explicitly correct HPre remote, and no conflicting `v1.0.0` tag.

- [ ] **Step 2: Locate and authenticate GitHub CLI**

Use the known local executable at `C:\Users\HUNG\Downloads\gh_2.97.0_windows_amd64\bin\gh.exe` if its version and integrity are acceptable. Run `gh auth status` without printing tokens.

Expected: authenticated account with repository/release permissions. If not authenticated, stop and ask the user to complete `gh auth login` interactively; never request a token in chat or source.

- [ ] **Step 3: Check repository-name availability and ownership**

Obtain the authenticated login with `gh api user --jq .login`, store it in `$owner`, and query `gh repo view "$owner/HPre"`. If absent, create public repository `HPre` without auto-README, license, or `.gitignore`. If present, verify ownership and that it is the intended empty/current HPre repository before adding it as `origin`. Obtain the exact clone URL with `gh repo view "$owner/HPre" --json url --jq .url` and store it in `$repositoryUrl`.

- [ ] **Step 4: Push source without force**

```powershell
git remote add origin $repositoryUrl
git push -u origin main
```

If `origin` already exists and is correct, omit the add command. Never use `--force`.

- [ ] **Step 5: Verify synchronized source**

```powershell
git status --short --branch
git rev-parse HEAD
git rev-parse origin/main
git remote -v
```

Expected: local HEAD equals `origin/main`, branch tracks `origin/main`, and status is clean.

- [ ] **Step 6: Create and push the release tag**

Confirm again that neither local nor remote `v1.0.0` exists. Create an annotated tag at the tested HEAD:

```powershell
git tag -a v1.0.0 -m "HPre v1.0.0"
git push origin v1.0.0
```

Expected: remote tag points to the exact tested source commit. Do not overwrite an existing tag.

---

### Task 8: Create and Verify the GitHub Release

**Files:**
- Local create: `C:\Users\HUNG\HPre-release\release-notes-v1.0.0.md`
- Upload: `C:\Users\HUNG\HPre-release\HPre-v1.0.0-release.apk`

**Interfaces:**
- Consumes: remote `v1.0.0`, frozen artifact filename/size/SHA-256, app metadata, certificate verification result.
- Produces: non-draft, non-prerelease GitHub Release with one signed APK asset.

- [ ] **Step 1: Write Vietnamese release notes**

Create notes with these populated fields and no local keystore path or credentials:

```text
# HPre v1.0.0

Bản phát hành công khai đầu tiên của HPre.

- APK: HPre-v1.0.0-release.apk
- Package: com.hpre.app
- Version Name: 1.0.0
- Version Code: 1
- Android tối thiểu: Android 8.0 (API 26)
- SHA-256: $apkSha256

APK đã được ký bằng release signing key của HPre. Source tương ứng nằm tại tag v1.0.0 và được phát hành theo GPL-3.0-or-later.

HPre là một dự án độc lập và không được phát triển, tài trợ hoặc xác nhận bởi YouTube hoặc Google. Các thương hiệu và nội dung của bên thứ ba thuộc về chủ sở hữu tương ứng.
```

- [ ] **Step 2: Create release and upload the exact artifact**

```powershell
gh release create v1.0.0 "$env:USERPROFILE\HPre-release\HPre-v1.0.0-release.apk" --title "HPre v1.0.0" --notes-file "$env:USERPROFILE\HPre-release\release-notes-v1.0.0.md" --verify-tag
```

Expected: command exits 0; release is neither draft nor prerelease.

- [ ] **Step 3: Verify remote release metadata and asset**

```powershell
gh release view v1.0.0
gh release view v1.0.0 --json url,tagName,name,isDraft,isPrerelease,assets
```

Expected: tag `v1.0.0`, title `HPre v1.0.0`, `isDraft=false`, `isPrerelease=false`, exact asset name `HPre-v1.0.0-release.apk`, and asset size greater than zero.

- [ ] **Step 4: Compare remote size with local size**

Read local byte size and compare it with the asset size returned by GitHub JSON. Require exact equality. If the API provides a digest, require it to match local SHA-256; otherwise size plus successful upload/query is the remote evidence, while local SHA-256 remains in release notes.

- [ ] **Step 5: Perform final repository audit**

```powershell
git status --short --branch
git log -1 --oneline
git rev-parse HEAD
git rev-parse v1.0.0^{}
git ls-files | rg -i '(\.jks$|\.keystore$|\.apk$|\.aab$|(^|/)local\.properties$|(^|/)\.env($|\.))'
git remote -v
```

Expected: clean/synchronized tree; HEAD and dereferenced tag commit match; no tracked sensitive/binary artifact.

- [ ] **Step 6: Produce the 20-field Vietnamese evidence report**

Report repository URL, branch, commit, application ID, version name/code, tag, release URL, APK filename/size/SHA-256, signature result, certificate SHA-256, build result, test result, changed/created repository files, secret-scan result, GPL-3.0-or-later rationale, local signing-key path without password, and remaining limitations. Quote concise command evidence. State completion only because the remote asset check passed.
