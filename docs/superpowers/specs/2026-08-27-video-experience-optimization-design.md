# HPre Video Experience Optimization Design

**Date:** 2026-08-27
**Status:** Approved by user

## 1. Goals

Improve HPre's perceived smoothness and playback reliability while preserving its current Android/Kotlin/Compose and Media3 architecture.

The change must:

- provide adaptive playback when the upstream source exposes a genuinely adaptive HLS/DASH manifest;
- respect the user's maximum selected video quality;
- show up to 100 videos on Home and in Watch recommendations without eagerly composing every row;
- make a refresh exclude every video in the immediately previous batch, returning fewer than 100 when clean candidates are exhausted;
- minimize a portrait Watch player to the in-app mini-player and navigate explicitly to Home when the user swipes down on the video;
- preserve system Picture-in-Picture when the user leaves the app;
- remove the portrait back overlay while preserving system Back and fullscreen exit behavior;
- add targeted correctness and performance verification without an unrelated architectural rewrite.

## 2. Non-goals

- Replacing the repository layer with Paging 3.
- Building a YouTube-style animated player shared-element transition in this iteration.
- Claiming continuous adaptive bitrate switching for progressive URLs.
- Guaranteeing exactly 100 results when providers do not expose enough clean candidates.
- Persisting refresh-exclusion history across process death.
- Changing system Back behavior in portrait mode.
- Reworking unrelated screens or repositories.

## 3. Existing System

HPre is a single-module Android app using Kotlin, Jetpack Compose, Media3/ExoPlayer, a `MediaSessionService`, and NewPipeExtractor. `SessionPlayerController` and the playback session are app-scoped. `RootScaffold` already displays a mini-player outside the Watch route, and `MainActivity` already supports system PiP.

The relevant constraints are:

- `StreamSelector` currently prefers progressive sources before HLS/DASH.
- `SessionPlayerController` combines a local progress tick with a service probe request every 500 ms.
- Home uses a lazy list, but Watch eagerly composes metadata, comments, and recommendations inside `Column.verticalScroll`.
- recommendation ranking currently stops at 30 items;
- only Search exposes continuation tokens through the current `VideoService` contract; related and trending return a single batch;
- portrait Watch contains a back button overlay;
- Watch has no swipe-to-minimize gesture.

## 4. Chosen Architecture

Retain the existing Media3, Compose, repository, and navigation architecture. Add narrowly scoped policies and state coordination at existing boundaries:

1. Playback service owns adaptive track selection and the user's quality policy.
2. Recommendation repository aggregates capability-aware sources under a bounded request budget.
3. ViewModels own refresh generations and snapshots of the currently displayed batch.
4. Watch uses lazy composition while keeping the player surface outside recyclable list content.
5. A playback UI coordinator owns minimize intent and surface ownership; navigation handles explicit top-level Home routing.

This avoids a Paging 3 rewrite, a custom adaptive media source, and uncontrolled provider fan-out.

## 5. Playback and Quality Policy

### 5.1 Source selection

For automatic quality, source selection prefers a valid adaptive manifest before fixed progressive sources. Preserve the provider's existing manifest preference when both HLS and DASH are present; if no preference is encoded today, try HLS first and DASH second because the current Android compatibility path already includes Media3 HLS support. A failed prepare advances once to the other adaptive manifest, then enters the existing bounded progressive recovery path. Recovery must track attempted source types so it cannot loop between manifests.

The source modes are explicit:

- **Adaptive:** HLS/DASH manifest with multiple usable video representations. Media3 may move down or up as bandwidth and buffer conditions change.
- **Single-track manifest:** playable through HLS/DASH but not represented to the user as actively adaptive.
- **Progressive fallback:** fixed stream URL. It does not support continuous ABR. Recovery may reselect and reprepare a lower fixed stream while preserving position, but the UI must not describe that as seamless adaptive switching.

Audio-only behavior remains unchanged.

### 5.2 Quality cap

`HPrePlaybackService` owns the `DefaultTrackSelector` used by its ExoPlayer instance. A quality-policy command is added to the existing MediaSession protocol so `SessionPlayerController` can send the user's maximum height/bitrate policy to the service.

The service applies the cap through Media3 track-selection parameters. Adaptive playback may choose any compatible representation at or below the cap. The policy is reapplied after prepare, media-source recovery, controller reconnection, and snapshot restoration.

User policy and runtime track are separate concepts:

- `UserQualityPolicy`: Auto with an optional maximum, or a fixed/fallback choice.
- `EffectiveTrack`: runtime-only information reported by Media3.

Only user policy is persisted. An expired runtime URL or the representation selected before process death is not restored as a preference.

### 5.3 Progress and service state

The local position ticker remains because Media3 listeners do not emit continuous position updates. It reads controller-local position/duration and updates UI only while useful.

The service probe command is removed from that periodic loop. Playback state transitions, errors, track changes, and readiness use `Player.Listener` and MediaSession events. Diagnostic snapshot retrieval remains on demand for tests and diagnostics. Existing snapshot persistence and recovery behavior must be preserved and tested before deleting any polling-dependent path.

### 5.4 Extractor work

Metadata, stream, and related extraction currently can fetch the same video page independently. First reuse existing request coalescing/cache boundaries where their current result types allow it. Do not add a new shared extraction cache in the core delivery unless profiling proves duplicate extraction remains a material contributor to startup latency. Any later cache requires a separate bounded design because metadata and expiring stream URLs need different lifetimes.

Extractor-cache work is not an acceptance prerequisite for this delivery.

## 6. Recommendation Collection

### 6.1 Source capabilities

The aggregator must not assume all providers are paged:

| Source | Current capability | Use |
|---|---|---|
| Related | Single batch | Highest-priority Watch candidates |
| Trending | Single batch | General fallback candidates |
| Search | Continuation token | Bounded fill source |

If NewPipeExtractor 0.26.5 is later proven by a focused adapter test to expose safe continuation for related or kiosk results, that capability may be added behind the service interface. It is not required by this design.

### 6.2 Bounded collection

Both Home and Watch request `limit = 100`. Collection proceeds in phases:

1. Fetch the highest-value single-batch sources and first pages of a bounded set of topic searches.
2. Merge and deduplicate by full `ContentKey`.
3. Apply the refresh exclusion snapshot.
4. Rank candidates, preserving direct-related priority on Watch and channel diversity.
5. If fewer than 100 remain, fetch additional Search continuations under a fixed budget.
6. Stop as soon as 100 clean candidates are available or the request/page/deadline budget is exhausted.

Initial implementation constants must be centralized and testable. Use at most six concurrent topic queries, at most two pages per query, at most six continuation requests in total, and a ten-second collection deadline. These values may be reduced after baseline measurement but may not be increased without revisiting provider-load risk. Additional invariants are:

- no more than the current maximum topic-query concurrency;
- a finite per-query page count and finite total continuation count;
- cancellation or result discard when a newer generation starts;
- partial success when individual sources fail;
- no retry loop when a source has no continuation;
- no reintroduction of excluded items to fill the quota.

Privacy/history settings remain authoritative. When personalization/history is disabled, history-derived topics are not sent to provider search.

### 6.3 Refresh semantics

Home and Watch recommendation state track:

- request generation;
- current displayed batch;
- refreshing status;
- source/error metadata needed by existing UI.

When refresh starts, the ViewModel snapshots all `ContentKey` values in the currently displayed batch, including rows not yet viewed. That immutable set is passed to the repository as `excludedKeys`. Filtering occurs after source merge/deduplication and before publishing the ranked result. Cache hits must pass through the same exclusion filter.

Only the latest generation may publish. Older non-cancellable extractor responses are discarded. The existing batch remains visible with a refresh indicator rather than being replaced by a full-screen loading state.

Exclusion applies only to the immediately previous batch:

- A refreshes to B while excluding A.
- The next refresh excludes B; items from A may be eligible again.

The first load has an empty exclusion set. Exclusion is session-scoped and is not restored after process death. If no clean candidates remain, the result is an empty or partial successful batch rather than an infinite retry or reuse of excluded videos.

## 7. Watch Layout and Rendering

The portrait player remains outside recyclable lazy content so scrolling recommendations cannot release or recreate its `PlayerView`.

The content below it becomes one `LazyColumn` with stable keys:

- metadata and channel/action sections use stable section keys;
- comments use comment IDs;
- recommendations use full `ContentKey`;
- no vertically scrolling lazy list is nested inside another vertical lazy list.

Comments pagination uses `LazyListState` proximity to the relevant section/end and retains its existing in-flight/token guards. Description expansion, loading, retry, and late-arriving recommendation state must not reset list position unexpectedly.

Home remains a `LazyColumn` and renders up to 100 rows lazily.

## 8. Swipe-to-Minimize and Navigation

### 8.1 Gesture policy

Swipe-to-minimize is enabled only when:

- orientation is portrait;
- Watch is not fullscreen;
- the pointer starts inside the player region;
- system PiP transition is not active.

A gesture coordinator distinguishes vertical movement from taps and horizontal seeking using touch slop, dominant-axis detection, a downward distance threshold, and velocity. Exact thresholds are centralized and adjusted through device testing rather than embedded in multiple composables.

Behavior:

- tap and double-tap remain available to controls;
- horizontal seek gestures and seek-bar dragging retain priority once classified as horizontal/control interaction;
- upward or sub-threshold drags settle without navigation;
- a qualifying downward gesture emits `MinimizeToHome` once;
- controls do not receive a click after a consumed minimize gesture.

Implement one gesture coordinator at the player/controls boundary. Controls register protected interactive regions such as the seek bar; the coordinator observes the remaining player region and consumes input only after classifying a qualifying vertical drag. Do not add a second independent vertical detector beneath the controls overlay.

### 8.2 Navigation semantics

`MinimizeToHome` is not implemented as `popBackStack()`. It is handled as a playback UI transition plus explicit top-level navigation:

1. mark playback UI as minimizing/minimized;
2. navigate to the Home route using `popUpTo(Home)` with Home retained, `launchSingleTop = true`, and state restoration enabled; if no Home entry exists, navigate to a new Home entry and remove the current Watch entry;
3. display the in-app mini-player against the existing playback session.

This guarantees Home whether Watch was entered from Home, Search, Channel, Library, Subscriptions, or another Watch route.

System Back in portrait keeps its current navigation semantics. Fullscreen Back continues to exit fullscreen before navigation.

## 9. Surface Ownership and PiP

Playback remains in the existing app-scoped session and must not be prepared again during minimize.

Surface coordination gains an explicit owner:

```text
NONE | WATCH | MINI_PLAYER | SYSTEM_PIP
```

Attach/detach requests carry a monotonically increasing handoff generation. A stale detach callback from Watch must not detach a newer mini-player or PiP surface. The target host attaches first; only its successful current-generation attach permits the previous host to detach. If target attach fails or times out, playback continues on the previous host and navigation reports a recoverable minimize failure.

Use the generation-based handoff with the existing `AndroidView` hosts in the core implementation. Record the interval between old-host last frame and new-host first frame during device verification. If a visible black frame occurs in three consecutive handoffs on a reference device, stop and upgrade the design to a stable root-level surface host before declaring the feature complete; do not duplicate ExoPlayer or the MediaSession.

System PiP behavior remains separate:

- swipe-down inside the app never opens system PiP;
- leaving the app follows the current eligibility policy;
- PiP surface ownership supersedes in-app hosts during entry;
- returning from PiP restores the appropriate in-app owner without losing playback position.

## 10. Back Controls

Remove only the portrait overlay back button from `WatchScreen` and its portrait-only semantics/test tag.

Preserve:

- Android system Back in portrait;
- fullscreen `BackHandler` behavior;
- fullscreen exit/back control;
- navigation accessibility through system controls.

## 11. Error Handling

- Adaptive manifest prepare failure enters the existing bounded recovery/fallback path.
- Unsupported or single-track adaptive manifests do not produce false adaptive UI claims.
- Recommendation source failures are isolated; successful sources can produce a partial batch.
- A completely failed initial load shows the existing retry/error state.
- A failed refresh keeps the old batch visible and exposes a retryable refresh error.
- Stale generations never replace current content or surface ownership.
- Empty clean results after exclusion are represented as a valid empty refresh result with explanatory UI, not a provider failure.

## 12. Expected Code Areas

Likely production changes include:

- `player/HPrePlaybackService.kt`
- `player/SessionPlayerController.kt`
- `player/StreamSelector.kt`
- `player/MediaSourceFactory.kt`
- MediaSession command/protocol and playback snapshot models
- `player/PlaybackUiCoordinator.kt`
- `ui/watch/WatchScreen.kt`
- `ui/watch/PlayerControls.kt` or a focused gesture-policy component
- `ui/watch/WatchViewModel.kt`
- `ui/home/HomeViewModel.kt`
- `ui/home/HomeScreen.kt`
- `repository/RecommendationRepository.kt`
- `repository/RecommendationRanker.kt`
- navigation helpers in `HPreNavHost.kt`/`RootScaffold.kt`
- corresponding unit and instrumentation tests

The implementation plan must verify exact files and avoid unrelated refactors.

## 13. Testing Strategy

### 13.1 Unit tests

- recommendation limit is at most 100;
- deduplication uses full `ContentKey`;
- refresh excludes every key from the prior batch, including off-screen rows;
- partial/empty clean results never reuse excluded keys;
- repeated and concurrent refreshes obey immediate-previous-batch and latest-generation semantics;
- cache hits obey exclusion;
- bounded Search continuation stops at limit or budget;
- related/trending are treated as single-batch unless capability tests prove otherwise;
- partial source failure returns available candidates;
- adaptive source is preferred when applicable;
- quality caps are translated into service-owned track-selection parameters and survive recovery;
- progressive fallback is represented as non-adaptive;
- local progress continues without periodic probe IPC;
- diagnostic snapshots and playback restoration remain valid;
- gesture policy classifies tap, horizontal drag, downward drag, cancellation, distance, and velocity correctly;
- surface handoff generations reject stale detach operations.

### 13.2 Compose/navigation/instrumentation tests

- Home and Watch render a 100-item fake dataset lazily with stable item identity;
- pull-to-refresh retains current content while refreshing;
- portrait back overlay is absent;
- system Back still navigates according to existing behavior;
- fullscreen Back still exits fullscreen first;
- swipe is enabled only on portrait, non-fullscreen player;
- tap, double-tap, controls, and seek bar continue working;
- swipe from Watch opened through Home, Search, Channel, Library, Subscriptions, and related Watch reaches one Home destination;
- playback position/play state survive Watch-to-mini-player transition;
- stale surface detach does not detach the new owner;
- activity recreation and PiP entry/exit do not create competing surface owners.

### 13.3 Live/device checks

Adaptive behavior cannot be proven solely with unit tests. On representative devices and a controlled network profile, record:

- time to first rendered frame;
- rebuffer count and total rebuffer duration;
- effective track movement under bandwidth reduction/recovery for a known multi-variant HLS/DASH fixture;
- confirmation that effective quality never exceeds the user cap;
- Watch-to-mini-player visual gap/black frame duration;
- scroll frame timing and memory while browsing 100 Home and Watch rows;
- PiP regression behavior.

Measure a before/after baseline before assigning pass thresholds. A new benchmark module is added only if repeatable instrumentation cannot measure the selected metric within the existing project.

## 14. Acceptance Criteria

The work is accepted when:

1. Home and Watch recommendations each return and lazily render no more than 100 unique videos.
2. Refresh results contain none of the immediately previous batch's keys; undersupply returns fewer items.
3. Newer refresh generations cannot be overwritten by older responses or cache entries.
4. Multi-variant HLS/DASH can adapt below the configured maximum quality; progressive fallback is labeled and behaves non-adaptively.
5. Playback progress, state, diagnostics, recovery, and restoration remain correct after periodic probe IPC is removed from the UI ticker.
6. A qualifying portrait swipe on the player always navigates to Home and continues playback in the in-app mini-player without reprepare.
7. Tap, double-tap, seek, fullscreen, system Back, activity recreation, and system PiP retain expected behavior.
8. The portrait back overlay is removed while fullscreen exit remains.
9. No stale surface callback can detach the active host.
10. Relevant unit and instrumentation suites pass, and device measurements are reported without unsupported performance claims.

## 15. Delivery Sequence

The implementation plan should stage work so regressions are isolated:

1. Recommendation contracts, exclusion/generation state, and lazy rendering.
2. Back-overlay removal and gesture/navigation state machine.
3. Surface handoff and PiP regression hardening.
4. Adaptive source preference, service-owned quality cap, and quality-policy persistence.
5. Progress/probe separation; measure duplicate extractor cost without adding a new cache.
6. Full automated verification and device performance matrix.

Each stage must begin with failing tests for the intended behavior and end with targeted validation before the next stage.
