# HPre Public Release Design

**Date:** 2026-08-28
**Status:** Approved in chat; implementation pending

## 1. Goal

Prepare and publish HPre 1.0.0 as a standard public Android project. The source repository and signed APK release must be reproducible, free of exposed credentials, correctly licensed, and independently verified before publication is reported as successful.

The release sequence is:

```text
inspect source and history
  -> scan secrets
  -> establish one stable signing identity
  -> test and build release
  -> verify final APK and metadata
  -> calculate SHA-256
  -> publish source and tag
  -> create GitHub Release and upload signed APK
  -> verify remote asset
```

## 2. Confirmed Baseline

- Project root: thư mục gốc của Git checkout hiện tại
- Main module: `:app`
- Public product name: `HPre`
- Namespace/application ID: `com.hpre.app`
- Version: `versionName 1.0.0`, `versionCode 1`
- Release tag: `v1.0.0`
- SDK: min 26, target 35, compile 35
- Toolchain: AGP 8.8.2, Gradle 8.11.1, Kotlin 2.1.20, Java 17
- Key libraries: Compose BOM 2025.02.00, Media3 1.5.1, NewPipeExtractor v0.26.5, Room 2.6.1, OkHttp 4.12.0, Coil 2.7.0
- Git branch: `main`; no remote is currently configured
- The worktree contains existing uncommitted functional changes. They must be preserved and reviewed, not discarded or silently overwritten.
- Release signing is already modeled with external Gradle properties named `hpreSigning.*`; no password is hardcoded in the build script.
- A previous `app-release.apk` exists, but it is not accepted as the publication artifact without a clean rebuild and signature verification.
- GitHub CLI and Java/Android build tools are not currently available through `PATH`; known local installations may be used explicitly if valid. Missing authentication or tooling is a publication blocker, not a reason to weaken verification.

## 3. Scope and Non-goals

### In scope

- Preserve the exact app name `HPre` and existing package identity.
- Secure local release signing and document key backup requirements.
- Expand `.gitignore` for Android output, credentials, signing files, environment files, and binary artifacts.
- Scan tracked files, current worktree, and reachable Git history for secrets without printing values.
- Run tests, lint where stable, and a signed release build.
- Verify signature, certificate, package metadata, version metadata, filename, size, and SHA-256.
- Add public-project documentation, licensing, notices, contribution/security guidance, issue forms, and unsigned debug CI.
- Publish source only to `main`, tag `v1.0.0`, and attach the signed APK only to GitHub Releases.

### Out of scope

- Functional refactoring unrelated to release readiness.
- Changing `com.hpre.app`, SDK levels, or version solely for publication.
- Publishing a debug or unsigned APK.
- Storing a release keystore, passwords, or base64 keystore in GitHub.
- Rewriting Git history or force-pushing without a newly discovered, confirmed secret exposure and explicit approval.
- Inventing screenshots, a security email, features, ownership claims, or upstream provenance.

## 4. Safety Gates

Publication stops if any of these conditions is unresolved:

1. A real secret, private key, keystore, signing password, OAuth credential, or local configuration is tracked or present in reachable history.
2. The intended HPre signing identity cannot be established. An existing candidate key must not be assumed to belong to HPre without evidence or user confirmation.
3. Tests or release assembly fail.
4. The output is unsigned, signature verification fails, or the certificate differs from a known prior HPre release certificate.
5. APK package/version metadata differs from the confirmed baseline.
6. Licensing obligations cannot be met.
7. GitHub authentication, remote creation/push, tag publication, release creation, or asset upload fails.
8. The remote release cannot be queried to prove that a non-empty `HPre-v1.0.0-release.apk` asset exists.

No partial state is described as complete. If blocked, the report states the last verified stage and exact remaining blocker without exposing sensitive values.

## 5. Signing Design

### 5.1 Reuse before creation

Search project-safe locations and the user profile for `*.jks` and `*.keystore`, while reporting only candidate paths. Inspect existing signing-property references and available certificate metadata. If a key is confirmed as the intended HPre release identity, reuse it.

Unrelated files found during a broad profile search are not read or reported. A noisy recursive search must be narrowed to likely keystore locations and filename extensions.

### 5.2 New identity when necessary

If no HPre release identity exists, create one once outside the repository under:

```text
C:\Users\HUNG\secure\hpre\hpre-release.jks
```

Use alias `hpre`, RSA 4096, and 10,000-day validity. Generate strong credentials locally without echoing or storing them in repository files or reports. If secure non-interactive credential generation cannot be completed without exposing credentials through process arguments or logs, pause for local user entry instead of choosing a weak/default password.

Create local, untracked backup guidance and signing identity metadata in the same secure directory. The metadata may contain app name, alias, keystore path, and certificate SHA-256 fingerprint, but never passwords.

### 5.3 Gradle integration

Retain external `hpreSigning.*` Gradle properties. Values may come from the user's global Gradle properties or another local ignored mechanism. The build must fail clearly when release signing values are unavailable; debug/CI tasks should remain usable without release credentials.

## 6. Repository and Secret Hygiene

Expand `.gitignore` to cover at minimum:

```text
.gradle/  .idea/  .kotlin/  local.properties  *.iml
build/  */build/  .externalNativeBuild/  .cxx/
*.jks  *.keystore  keystore.properties  signing.properties
.env  .env.*  *.apk  *.aab  *.log  .DS_Store  Thumbs.db
```

Also retain project-specific ignores for local worktrees and planning state where already intended.

Secret scanning has two layers:

1. Pattern scan for high-confidence credential formats and private-key markers.
2. Context review for keyword matches such as `token`, `secret`, `password`, `Authorization`, and `Bearer` so normal code, test fixtures, and signing property names are not misclassified.

The final staged-file review explicitly rejects keystores, local properties, environment files, APK/AAB files, and generated build trees. Only filenames and classifications are reported; secret values are never displayed.

## 7. Licensing and Public Documentation

HPre will be released under **GPL-3.0-or-later**. This is the conservative compatible choice because the distributed APK includes NewPipeExtractor v0.26.5, which is GPL-3.0-or-later. Dependency use does not automatically settle every legal classification, but distributing the combined APK under a permissive-only app license would create avoidable compliance risk.

Add:

- `LICENSE` containing GPL version 3 and an explicit “or later” project statement where appropriate.
- `THIRD_PARTY_NOTICES.md` listing material dependencies, repository/homepage, version where known, and license.
- `README.md` in Vietnamese describing only implemented behavior: independent Android video client, home/search, playback, fullscreen, mini-player, background playback, PiP, local history/playlists/subscriptions, and themes. It documents NewPipeExtractor and Media3 accurately, includes no official-affiliation claim, uses a screenshot placeholder if no real screenshots exist, points downloads to GitHub Releases, and includes the required Google/YouTube independence disclaimer.
- `CONTRIBUTING.md` with fork/branch/build/test/PR and secret/signing exclusions.
- `SECURITY.md` directing private vulnerability reporting through GitHub's private vulnerability reporting feature when available; it does not invent an email address.
- GitHub issue forms for bugs and feature requests that prohibit posting credentials.

Corresponding source for the released binary is the tagged public source at `v1.0.0`. Release notes link to that tag and preserve third-party notices.

## 8. CI Design

Add a GitHub Actions workflow that checks out source, installs the required Java version, validates the Gradle wrapper where practical, and runs unit tests plus `assembleDebug`. It does not sign releases and does not receive a release keystore or signing password. Release signing remains a controlled local operation for this publication.

## 9. Build and Artifact Pipeline

Use the Gradle wrapper from the project root with a verified Java 17 environment:

1. `clean`
2. unit tests
3. lint, unless a demonstrated toolchain-only issue makes it unavailable; application-impacting lint errors remain blockers
4. `assembleRelease` with the established HPre signing identity

Accept only the newly generated release output associated with this build. Reject any `*-unsigned.apk`, debug artifact, stale pre-clean artifact, or output whose timestamp/build metadata cannot be tied to the current run.

Copy the accepted APK outside the source tree as:

```text
C:\Users\HUNG\HPre-release\HPre-v1.0.0-release.apk
```

The final copy is immutable for the remaining pipeline. Any rebuild or binary modification restarts signature, metadata, size, and checksum verification.

## 10. Verification

Use the matching Android SDK build-tools `apksigner` as the primary verifier, first on the Gradle output and again on the renamed final artifact. Capture only non-sensitive evidence:

- verification success and supported signature schemes;
- signer certificate SHA-256 fingerprint;
- artifact path and byte size.

Use `aapt dump badging` or `apkanalyzer` to confirm:

- package `com.hpre.app`;
- version code `1`;
- version name `1.0.0`;
- min SDK `26`;
- target SDK `35`;
- app label `HPre` where the tool exposes it.

Calculate SHA-256 only after final verification. Save the checksum for release notes and do not alter the APK afterward.

If an older official HPre APK exists, compare its certificate SHA-256 fingerprint. A mismatch blocks an update release. If this is the first release, record the new fingerprint locally for all future releases.

## 11. Git and GitHub Publication

Before committing, inspect status, diff, recent commits, ignored files, tracked files, and staged files. Preserve and include existing functional work only after it passes the same review and tests; do not erase or split it silently. Stage explicit intended paths, not generated or sensitive files.

Create a normal commit consistent with repository history. Do not amend or force-push. Configure or create a public GitHub repository named `HPre` only after secret and licensing gates pass. Use authenticated GitHub CLI from its known local installation if it is valid; otherwise stop and request local authentication.

Publish in this order:

1. Push `main` and verify its upstream state.
2. Confirm `v1.0.0` does not already represent another release.
3. Create and push `v1.0.0` at the tested release commit.
4. Create non-draft, non-prerelease `HPre v1.0.0`.
5. Upload only `HPre-v1.0.0-release.apk` as the binary asset.
6. Include package, version code/name, minimum Android, SHA-256, signed-release statement, GPL/source link, and independence disclaimer in Vietnamese release notes.
7. Query the remote release and asset JSON to verify tag, title, state, exact filename, and size greater than zero.

The APK remains ignored and absent from every source commit.

## 12. Validation and Final Evidence

Completion requires all of the following evidence:

- clean final Git status and synchronized `main`;
- exact commit hash and remote URL;
- app identity and version from source and APK metadata;
- passing unit-test, lint disposition, and release-build results;
- two successful `apksigner` checks, including the final renamed artifact;
- certificate SHA-256 fingerprint, APK SHA-256, and size;
- secret scan and staged-file audit results;
- selected GPL-3.0-or-later license and third-party notices;
- pushed `v1.0.0` tag;
- GitHub Release URL and remotely verified non-empty APK asset.

The final Vietnamese report follows the requested 20-field format. Passwords, private key material, and credential values are excluded. If any gate remains unresolved, the report is explicitly partial and does not say `DONE`.
