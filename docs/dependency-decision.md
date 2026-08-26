# FlowTube Dependency Decisions & Provenance

This document records the exact coordinates, repositories, licenses, release dates, provenance sources, and compatibility rationale for all build toolchain components and runtime dependencies locked for FlowTube V1 (Task 1 Build Gate).

> **Verification Status (Updated 2026-08-24):** Local dependency resolution, toolchain compatibility, unit test suites, and APK assembly have been verified via manager verification build gate on 2026-08-24 using session-scoped Eclipse Temurin `17.0.14+7` and Android SDK API 35. Command `./gradlew.bat clean test assembleDebug` completed with `BUILD SUCCESSFUL in 12s` (73 actionable tasks executed, debug/release unit test variants passed, producing `app-debug.apk` of 23,437,345 bytes, SHA-256 `331FEAD8F061F5D513E24E81380C1B8955FD3054180AEE99664E988380613827`).
>
> *Scope Boundary:* This verification proves local dependency resolution and build generation for this local environment. It does not prove upstream runtime playback or legal clearance.

---

## 1. Toolchain & Core Platform

| Component | Selected Version | Release Date | Repository / Distribution | Provenance Source URL | License | Compatibility & Decision Rationale |
|---|---|---|---|---|---|---|
| **JDK** | `17.0.14+7` (LTS) | 2025-01-23 | Eclipse Adoptium (Temurin) | `https://github.com/adoptium/temurin17-binaries/releases/tag/jdk-17.0.14%2B7` | GPL v2 with Classpath Exception | Required by Task 1 specification (`minSdk = 26`, Java/Kotlin target 17). Android Gradle Plugin 8.8.2 / Gradle 8.11.1 requires JDK 17 minimum. Locked to single distribution build Eclipse Temurin 17.0.14+7. Verified in local environment. |
| **Gradle** | `8.11.1` | 2024-11-20 | `https://services.gradle.org/distributions/gradle-8.11.1-bin.zip` | `https://docs.gradle.org/8.11.1/release-notes.html` | Apache-2.0 | Selected Gradle distribution paired with AGP 8.8.2 and Kotlin 2.1.20. Supports Java 17 and Kotlin DSL scripts. Verified in local environment. |
| **Android Gradle Plugin (AGP)** | `8.8.2` | 2025-02-20 | `google()` | `https://developer.android.com/studio/releases/gradle-plugin` | Apache-2.0 | Selected Android Gradle Plugin release paired with Gradle 8.11.1, Kotlin 2.1.20, and Compose compiler Gradle plugin. Verified in local environment. |
| **Kotlin** | `2.1.20` | 2025-03-20 | `mavenCentral()` | `https://github.com/JetBrains/kotlin/releases/tag/v2.1.20` | Apache-2.0 | Selected Kotlin release with official Compose Compiler Gradle Plugin built-in (`org.jetbrains.kotlin.plugin.compose`). Paired with KSP `2.1.20-1.0.31`. Verified in local environment. |
| **KSP (Kotlin Symbol Processing)** | `2.1.20-1.0.31` | 2025-03-21 | `mavenCentral()` | `https://github.com/google/ksp/releases/tag/2.1.20-1.0.31` | Apache-2.0 | Exact matching release for Kotlin `2.1.20`. Used for Room code generation without KAPT overhead. Verified in local environment. |
| **compileSdk** | `35` | 2024-09-03 | Android SDK | `https://developer.android.com/about/versions/15` | Android Software Development Kit License | Android 15 (API 35) target SDK baseline. Verified in local environment. |
| **targetSdk** | `35` | 2024-09-03 | Android SDK | `https://developer.android.com/about/versions/15` | Android Software Development Kit License | Targets platform standards API 35. Verified in local environment. |
| **minSdk** | `26` | 2017-08-21 | Android SDK | `https://developer.android.com/about/versions/oreo` | Android Software Development Kit License | Android 8.0 (Oreo) minSdk required by spec. Eliminates need for heavy desugaring. |

---

## 2. AndroidX & Jetpack Libraries

| Component / Artifact | Selected Version | Release Date | Repository | Provenance Source URL | License | Compatibility & Decision Rationale |
|---|---|---|---|---|---|---|
| **Compose BOM** (`androidx.compose:compose-bom`) | `2025.02.00` | 2025-02-12 | `google()` | `https://developer.android.com/jetpack/androidx/releases/compose` | Apache-2.0 | Curated BOM aligning Material 3, Foundation, UI, and Tooling with Kotlin 2.1.20 Compose plugin. Verified in local environment. |
| **Compose Material 3** (`androidx.compose.material3:material3`) | *BOM-managed* | 2025-02-12 | `google()` | `https://developer.android.com/jetpack/androidx/releases/compose-material3` | Apache-2.0 | Modern Material Design 3 UI components. |
| **Compose Material Icons Extended** (`androidx.compose.material:material-icons-extended`) | *BOM-managed* | 2025-02-12 | `google()` | `https://developer.android.com/jetpack/androidx/releases/compose-material` | Apache-2.0 | Full extended icon set for player and navigation controls. |
| **Compose UI Tooling Preview** (`androidx.compose.ui:ui-tooling-preview`) | *BOM-managed* | 2025-02-12 | `google()` | `https://developer.android.com/jetpack/androidx/releases/compose-ui` | Apache-2.0 | For Compose previews and UI rendering. |
| **Compose UI Tooling** (`androidx.compose.ui:ui-tooling`) | *BOM-managed* | 2025-02-12 | `google()` | `https://developer.android.com/jetpack/androidx/releases/compose-ui` | Apache-2.0 | Debug-only inspection tooling for Compose. |
| **Compose UI Test Manifest** (`androidx.compose.ui:ui-test-manifest`) | *BOM-managed* | 2025-02-12 | `google()` | `https://developer.android.com/jetpack/androidx/releases/compose-ui` | Apache-2.0 | Debug manifest for Compose UI instrumentation testing. |
| **Activity Compose** (`androidx.activity:activity-compose`) | `1.10.1` | 2025-02-26 | `google()` | `https://developer.android.com/jetpack/androidx/releases/activity` | Apache-2.0 | Integrates `ComponentActivity.setContent` with Compose lifecycle. Verified in local environment. |
| **Navigation Compose** (`androidx.navigation:navigation-compose`) | `2.8.8` | 2025-02-26 | `google()` | `https://developer.android.com/jetpack/androidx/releases/navigation` | Apache-2.0 | Type-safe declarative navigation graph for Compose. Verified in local environment. |
| **Lifecycle Runtime Compose & ViewModel** (`androidx.lifecycle:lifecycle-runtime-compose`, `lifecycle-viewmodel-compose`) | `2.8.7` | 2024-10-30 | `google()` | `https://developer.android.com/jetpack/androidx/releases/lifecycle` | Apache-2.0 | Lifecycle-aware StateFlow collection and ViewModel injection. Verified in local environment. |
| **AndroidX Core KTX** (`androidx.core:core-ktx`) | `1.15.0` | 2024-10-30 | `google()` | `https://developer.android.com/jetpack/androidx/releases/core` | Apache-2.0 | Kotlin extensions for core Android APIs. Verified in local environment. |
| **DataStore Preferences** (`androidx.datastore:datastore-preferences`) | `1.1.3` | 2025-02-26 | `google()` | `https://developer.android.com/jetpack/androidx/releases/datastore` | Apache-2.0 | Coroutines-based asynchronous key-value storage for settings. Verified in local environment. |
| **Room Runtime & KTX** (`androidx.room:room-runtime`, `androidx.room:room-ktx`, `androidx.room:room-compiler`) | `2.6.1` | 2023-11-29 | `google()` | `https://developer.android.com/jetpack/androidx/releases/room` | Apache-2.0 | SQLite persistence layer with Coroutine/Flow support and KSP code generation. Verified in local environment. |

---

## 3. Media & Playback

| Component / Artifact | Selected Version | Release Date | Repository | Provenance Source URL | License | Compatibility & Decision Rationale |
|---|---|---|---|---|---|---|
| **AndroidX Media3 ExoPlayer** (`androidx.media3:media3-exoplayer`) | `1.5.1` | 2024-12-19 | `google()` | `https://developer.android.com/jetpack/androidx/releases/media3` | Apache-2.0 | Core playback engine. All Media3 artifacts strictly use exact version `1.5.1`. Verified in local environment. |
| **AndroidX Media3 Session** (`androidx.media3:media3-session`) | `1.5.1` | 2024-12-19 | `google()` | `https://developer.android.com/jetpack/androidx/releases/media3` | Apache-2.0 | Provides `MediaSessionService`, `MediaSession`, `MediaController` for background audio and system controls. Verified in local environment. |
| **AndroidX Media3 UI** (`androidx.media3:media3-ui`) | `1.5.1` | 2024-12-19 | `google()` | `https://developer.android.com/jetpack/androidx/releases/media3` | Apache-2.0 | `PlayerView` integration for Compose `AndroidView`. Verified in local environment. |
| **AndroidX Media3 OkHttp DataSource** (`androidx.media3:media3-datasource-okhttp`) | `1.5.1` | 2024-12-19 | `google()` | `https://developer.android.com/jetpack/androidx/releases/media3` | Apache-2.0 | Streams media over pooled OkHttp network client. Verified in local environment. |

---

## 4. Networking, Image Loading & Utilities

| Component / Artifact | Selected Version | Release Date | Repository | Provenance Source URL | License | Compatibility & Decision Rationale |
|---|---|---|---|---|---|---|
| **OkHttp** (`com.squareup.okhttp3:okhttp`) | `4.12.0` | 2023-10-18 | `mavenCentral()` | `https://github.com/square/okhttp/releases/tag/parent-4.12.0` | Apache-2.0 | HTTP client for Media3 datasource and extractor HTTP calls. Logging interceptor excluded in Task 1 to avoid unredacted logging. Verified in local environment. |
| **Coil Compose** (`io.coil-kt:coil-compose`) | `2.7.0` | 2024-07-12 | `mavenCentral()` | `https://github.com/coil-kt/coil/releases/tag/2.7.0` | Apache-2.0 | Image loader with disk/memory caching for video thumbnails and channel avatars. Verified in local environment. |
| **Kotlinx Coroutines** (`org.jetbrains.kotlinx:kotlinx-coroutines-android`, `kotlinx-coroutines-test`) | `1.10.1` | 2025-01-10 | `mavenCentral()` | `https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.10.1` | Apache-2.0 | Asynchronous structured concurrency and test dispatchers. Verified in local environment. |

---

## 5. Extractor Artifact (Vertical Slice Boundary)

| Component / Artifact | Selected Version | Release Date | Repository | Provenance Source URL | License | Compatibility & Decision Rationale |
|---|---|---|---|---|---|---|
| **NewPipeExtractor** (`com.github.TeamNewPipe:NewPipeExtractor`) | `v0.26.5` | 2026-08-15 | `jitpack.io` (`https://jitpack.io`) | `https://github.com/TeamNewPipe/NewPipeExtractor/releases/tag/v0.26.5` | GPL-3.0-or-later | Upstream repository: `https://github.com/TeamNewPipe/NewPipeExtractor`. Upgraded from `v0.24.5` to `v0.26.5`. Note on upstream provenance: while `v0.26.5` release itself only contains a media.ccc hotfix, the selection is justified by the cumulative upstream compatibility rationale inherited from earlier versions (specifically `v0.25.0` modernized date parsing, `v0.25.1` player reload & stream fixes, and `v0.26.3` SABR enforcement workarounds) which resolve YouTube metadata extraction and `ContentUnavailable` errors. Verified JitPack artifact coordinates `com.github.TeamNewPipe:NewPipeExtractor:v0.26.5` and POM published under GNU General Public License v3.0 or later. Isolated exclusively within `com.flowtube.app.extractor` package behind provider-neutral adapter abstractions. JitPack content filter remains strictly narrowed to `includeGroupByRegex("com\\.github\\.TeamNewPipe(\\..*)?")`. Upstream limitation: NewPipeExtractor relies on reverse-engineered upstream YouTube endpoints without official API contracts; breaking changes on YouTube servers may require future upstream hotfixes. Task 3 verifies bounded stream accessibility via range/manifest probe, not media playback. Note: As licensed under GPL-3.0-or-later, distribution review and license compliance review are required prior to binary distribution. |

---

## 6. Testing Frameworks

| Component / Artifact | Selected Version | Release Date | Repository | Provenance Source URL | License | Compatibility & Decision Rationale |
|---|---|---|---|---|---|---|
| **JUnit 4** (`junit:junit`) | `4.13.2` | 2021-02-13 | `mavenCentral()` | `https://github.com/junit-team/junit4/releases/tag/r4.13.2` | EPL-1.0 | Unit test runner for Android Gradle plugin. Verified in local environment. |
| **AndroidX Test Ext JUnit** (`androidx.test.ext:junit`) | `1.2.1` | 2024-06-26 | `google()` | `https://developer.android.com/jetpack/androidx/releases/test#1.2.1` | Apache-2.0 | AndroidX JUnit extension for instrumentation and unit tests. Verified in local environment. |
| **AndroidX Test Runner** (`androidx.test:runner`) | `1.6.2` | 2024-08-01 | `google()` | `https://developer.android.com/jetpack/androidx/releases/test#1.6.2` | Apache-2.0 | Android instrumentation runner (`androidx.test.runner.AndroidJUnitRunner`). Verified in local environment. |
| **AndroidX Espresso Core** (`androidx.test.espresso:espresso-core`) | `3.6.1` | 2024-06-26 | `google()` | `https://developer.android.com/jetpack/androidx/releases/test#espresso-3.6.1` | Apache-2.0 | UI testing framework for Android views and interactions. Verified in local environment. |
| **Compose UI Test JUnit4** (`androidx.compose.ui:ui-test-junit4`) | *BOM-managed* | 2025-02-12 | `google()` | `https://developer.android.com/jetpack/androidx/releases/compose-ui` | Apache-2.0 | Compose UI testing and assertion framework. Verified in local environment. |
| **Room Testing** (`androidx.room:room-testing`) | `2.6.1` | 2023-11-29 | `google()` | `https://developer.android.com/jetpack/androidx/releases/room` | Apache-2.0 | Migration and in-memory DAO testing. Verified in local environment. |
| **OkHttp MockWebServer** (`com.squareup.okhttp3:mockwebserver`) | `4.12.0` | 2023-10-18 | `mavenCentral()` | `https://github.com/square/okhttp/releases/tag/parent-4.12.0` | Apache-2.0 | Deterministic local HTTP server for unit testing extractor probe network responses without network access. Verified in local environment. |
