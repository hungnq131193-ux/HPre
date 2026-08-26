# HPre V1 — Approved Design Specification

## 1. Purpose and scope

HPre is a native Android video client with a modern, familiar video-viewing experience. Its application ID is `com.hpre.app`. It uses Kotlin, Jetpack Compose and AndroidX Media3, runs without Google Play Services for its core functions, and produces an installable debug APK.

V1 retrieves public content through NewPipeExtractor behind a provider-neutral adapter. The app does not use YouTube Data API, YouTube Android Player API, WebView playback, Google login, a downloader, cloud sync, analytics/tracking, or an advertising system. It must not use source-platform branding, logos, names, or artwork in its UI.

“Supported content” means content that the configured extractor can lawfully and technically resolve at runtime. This is not a guarantee that every public URL is searchable or playable. The app must not bypass login, CAPTCHA, DRM, paywalls, age gates, geographical controls, or other access controls.

Minimum supported Android version is API 26. The final `compileSdk`, `targetSdk`, JDK, Gradle, AGP, Kotlin, Compose, Media3, Room, KSP, Coil, and extractor versions are explicit build-gate decisions; all Media3 artifacts use exactly one version.

## 2. Architectural boundaries

V1 uses one Android app module with strict package boundaries rather than premature Gradle modularization:

```text
com.hpre.app
├── core/          # dispatchers, result, domain errors, design system, utility
├── model/         # Kotlin-only domain models and stable content keys
├── extractor/     # NewPipe adapter, blocking downloader, extractor mappers
├── repository/    # catalog/local repositories, cache and request policies
├── database/      # Room entities, DAOs, database and migrations
├── player/        # MediaSessionService, controller, source selection/recovery
├── settings/      # DataStore preferences and settings repository
├── ui/            # Compose screens, components and ViewModels
└── navigation/    # routes, navigation graph and root mini-player host
```

Dependency direction is fixed:

```text
Compose UI -> ViewModel -> Repository -> VideoService interface
                                           -> NewPipeVideoService -> NewPipeExtractor
```

`extractor/` is the sole package permitted to import NewPipeExtractor classes. Domain models, repositories, UI, navigation, database, and player packages must not expose or depend on extractor types. `model/` must not depend on Compose, Room, Media3, or NewPipeExtractor.

`VideoService` is provider-neutral and owns capabilities such as search, suggestions, feed/trending, metadata, channel details/videos, comments, related content, playlists, and stream information. Unsupported capability responses are explicit; they are never replaced by URL-string heuristics. `NewPipeVideoService` is the only V1 implementation.

All blocking extractor calls run using structured coroutines on `Dispatchers.IO`, with operation-specific timeouts and cancellation propagation. Composables never invoke extractors or networking directly.

## 3. Content and error contract

Every extractor response is mapped into internal models before leaving the adapter. A stable content identity is `(serviceId, nativeVideoId)` or `(serviceId, nativeChannelId)`, with a canonical URL retained as metadata; mutable URL parameters are not identifiers. Direct stream URLs are session-only and never persisted to Room.

The domain error taxonomy is:

- `NetworkError`
- `RateLimited`
- `ContentUnavailable`
- `AgeRestricted`
- `GeoRestricted`
- `LoginRequired`
- `StreamExpired`
- `UnsupportedFormat`
- `ExtractionFailed`
- `Unknown`

The UI receives only user-safe states: loading, content, empty, and a mapped error with an appropriate action. Raw extractor exceptions, stream URLs, request cookies/tokens, and stack traces must not reach the UI or normal logs.

Retry policy is bounded and cancellable:

- transient network failures: at most two retries with backoff and jitter;
- rate limiting/restrictions/DRM/unsupported content: no automatic retry;
- expired or HTTP-403 stream indication: resolve fresh stream info once for the affected session, select an equivalent source, seek to the saved position, and resume only if previously playing;
- all retries stop when the user closes playback or the request coroutine is cancelled.

Search debounces for 400 ms and uses latest-query cancellation. Pagination tracks a request key, cursor and in-flight lock to prevent duplicate loads and stale-result overwrite.

## 4. Playback architecture

`HPrePlaybackService`, a Media3 `MediaSessionService`, exclusively owns one `ExoPlayer` and one `MediaSession`. It is the only owner that creates and releases the player. UI communicates through a Media3 `MediaController` exposed by a lifecycle-aware `PlayerController`; it observes immutable playback state and never owns a player singleton.

Persisted recovery data includes stable media identity, position, `playWhenReady`, and quality/speed preference. A recovered session resolves a fresh stream rather than restoring an expired URL. Service and controller design must handle activity recreation, service restart, process death, audio focus changes, route/noisy events, and network loss without creating duplicate players.

The portrait Watch screen shows the video surface, title/metadata, channel details, explicit local subscription action, share, description, comments, and related content where supported. It supports play/pause, seek, ±10 seconds, progress and duration, speed, available quality/audio/subtitle tracks, loading, retry, error, and fullscreen. High-quality separate A/V streams are merged only when compatible; the UI only presents streams supplied by the adapter.

Fullscreen is user-initiated landscape playback. Back returns to the portrait Watch screen with the same service/player session and position. Back from Watch returns to prior navigation content while playback stays active and the root scaffold renders a mini-player above bottom navigation. The mini-player supports restore, play/pause, and dismiss; dismissal stops and clears the session after saving history.

When enabled, background playback uses a properly declared media-playback foreground service, notification channel and MediaSession notification controls. It supports audio focus, Bluetooth/headset, lock-screen and notification controls. Without the setting, leaving the foreground pauses according to policy and does not retain an unnecessary foreground service.

PiP is optional per supported device/API and only for active video while Watch is visible and the setting is enabled. It is not entered for audio-only content. Android 12+ auto-enter is used only where valid; older compatible APIs use native PiP entry. PiP UI changes must not release the player surface/session. Unsupported or failed PiP falls back gracefully to the configured background/pause behavior.

## 5. UI and navigation

Compose Material 3 provides a clean HPre-specific dark/light/system theme. The UI uses generic original text/icon branding and must not copy platform-specific logos, colors, assets or names.

The root bottom navigation has Home, Shorts, Subscriptions and Library. A feature not supported by the current provider returns a truthful unavailable state rather than a fake action.

- **Home:** local HPre top bar, search, local settings/profile entry, placeholder-only cast affordance only if visibly disabled/omitted, horizontal filters, keyed lazy video feed, thumbnail/duration, channel presentation and overflow actions that are implemented.
- **Search:** search bar, local recent queries, suggestions where supported, debounced/cancellable results for video/channel/playlist, and loading/success/empty/error/retry states.
- **Watch:** described in the playback section. Local user actions are labelled as local; no behavior is presented as a source-account action.
- **Channel:** banner/avatar/name/subscriber text/description and supported videos, shorts and playlists. Subscription is local only.
- **Shorts:** vertical pager only if a semantic provider capability is available. It keeps current/previous/next items bounded and never creates one player per card.
- **Library:** local history, subscriptions and local playlists. Local playlist CRUD supports create, rename, delete, add, remove and transactional reorder.

## 6. Local persistence and settings

Room version 1 has indexed tables:

- `watch_history`, primary key `(serviceId, videoId)`: canonical URL, title, channel ID/name, thumbnail, nullable duration, last playback position and watched timestamp;
- `local_subscriptions`, primary key `(serviceId, channelId)`: canonical channel URL, name, avatar and subscribed timestamp;
- `local_playlists`: local ID, title, created/updated timestamp;
- `local_playlist_entries`, primary key `(playlistId, serviceId, videoId)`: content snapshot, added timestamp and `sortOrder`;
- `search_history`: normalized query and timestamp, bounded by a cleanup limit.

History is written on an IO dispatcher only if the history setting is enabled. Resume is suppressed at or beyond 95% completion. History clear and search-history clear delete only the relevant local tables. Future schema changes require explicit Room migrations and migration tests; release builds do not use destructive migration for user-owned data.

DataStore stores only functioning preferences: system/light/dark appearance, Wi-Fi/mobile quality preference, default speed, autoplay, background playback, PiP, history enabled, and region/language only when adapter support exists.

Subscription aggregation applies a small bounded concurrency limit, timeout, metadata cache TTL and finite retry. It must not refresh an unlimited number of channels concurrently.

## 7. Cache, privacy and resilience

Coil provides thumbnail/avatar memory and disk caching. Repositories provide short-lived, keyed metadata and search-result caching with TTL. Stream URLs are not durable cache data because they expire.

HTTP networking uses connection reuse, appropriate user-agent behavior, timeouts, cancellation and bounded requests. Logs redact stream URLs, tokens, cookies and potentially sensitive response data. The application has no analytics/tracking and retains only the local history, subscriptions, playlists, search history and bounded caches described above.

NewPipeExtractor is a non-official upstream dependency without an SLA. Upstream website/client changes, parser failures, bot detection, rate limits, absent streams and altered metadata are expected operational risks. The adapter boundary is the containment point: a dependency update or adapter change must not require UI/domain changes. V1 does not promise live playback, DRM content, access-controlled content, every URL, or all optional extractor capabilities.

## 8. Technical gates and phased implementation

Implementation is stopped and corrected at each gate before adding later scope.

1. **Build/dependency gate:** establish reproducible Gradle Kotlin DSL project; lock exact versions/repositories and inspect licenses/provenance; clean debug APK build succeeds.
2. **Extractor vertical-slice gate:** configure the provider adapter; verify public search, metadata mapping, stream resolution, and bounded direct range/manifest stream accessibility probe for permitted public smoke inputs; map failure categories; no UI/domain extractor dependency. It does not initialize or play Media3, and the accessibility probe does not guarantee codec decode/render.
3. **Foreground playback gate:** Media3 plays local/fake media and then resolved remote stream; seek, speed, stream selection, error/retry and fullscreen preserve session state.
4. **Session/background/PiP gate:** MediaSessionService, notification/audio controls, mini-player, background playback, activity recreation/rotation, and PiP fallback operate with exactly one player.
5. **Persistence gate:** Room history, local subscription and playlist behavior, DataStore settings, migration/index/transaction tests, and bounded subscription aggregation operate.
6. **Catalog-completion gate:** provider-supported channel, comments, related content, and semantic Shorts capability are surfaced with truthful fallback states.
7. **Hardening/release gate:** cache/resilience/expired-stream recovery, performance checks, automated tests, upstream smoke tests, manual matrix, clean test/build and device APK smoke test pass.

If the extractor gate cannot resolve public content and verify stream accessibility reliably enough, the work stops for diagnosis, dependency update or an approved adapter decision; the project must not claim extractor gate completion. Real remote Media3 playback/seek/fullscreen is validated at the Foreground Playback Gate and remains required for final release/completion. Downloader is excluded until a later, separately approved release.

## 9. Testing and verification

Deterministic automated tests use fakes, fixtures, local HTTP media or Media3 test components rather than live upstream streams:

- extractor-to-domain mappers and error mapping;
- `VideoService` and repository cancellation/cache/pagination policy;
- stream selection, quality fallback and expired-stream recovery policy;
- Room DAOs, history resume threshold, subscription operations, playlist CRUD/reorder transactions and migrations;
- ViewModel states and Compose UI paths including search debounce/cancel/retry, Home-to-Watch, mini-player and theme states;
- Media controller/service behavior with local/fake content where environment supports it.

An upstream smoke suite is separate from deterministic CI and uses externally configured public inputs, never hard-coded stream URLs. For extractor validation, it verifies public search, metadata mapping, stream resolution and bounded range/manifest accessibility probe. Real remote Media3 playback/seek/fullscreen is validated in Task 5 Foreground Playback Gate and required for final release.

Manual acceptance covers Home-to-play, Search-to-play, seek/speed/quality/fullscreen/back, mini-player restore/dismiss, background notification/lock screen, PiP when supported, local subscription, history/resume/clear, playlists, dark/light/system theme, rotation, offline/network error, extractor error and expired-stream simulation. Target testing includes API 26, API 31+ and a current Android device where possible.

Release evidence requires successful:

```text
./gradlew clean
./gradlew test
./gradlew assembleDebug
```

The APK path is reported from the actual Gradle output (normally `app/build/outputs/apk/debug/app-debug.apk`). Completion cannot be claimed before the APK exists, installs/opens, and a real remote playback smoke test is positively observed.

## 10. Explicit non-goals and release constraints

- No downloader in V1.
- No login, source-account mutation, cloud synchronization, backend, recommendation AI, ads, tracking or Google Play Services core dependency.
- No WebView playback or official source platform APIs/player SDK.
- No direct source-code modification of NewPipeExtractor unless an approved blocker proves it necessary.
- No infinite retries, main-thread blocking extraction/networking, `GlobalScope`, or an ExoPlayer instance per UI card.
- No claim that upstream/extractor behavior outside HPre's control is guaranteed.
