# HPre Video Experience Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver smoother adaptive playback, 100-item non-repeating recommendation refreshes, portrait swipe-to-Home mini-player, and removal of the portrait back overlay without regressing PiP, seeking, recovery, or system Back.

**Architecture:** Keep the existing Compose, repository, Media3 `MediaSessionService`, and app-scoped controller architecture. Add bounded recommendation requests and explicit refresh generations, a single player gesture coordinator, explicit Home navigation, generation-safe surface ownership, and service-owned adaptive track selection. Keep the player outside lazy Watch content and keep local progress ticking while removing periodic diagnostic IPC.

**Tech Stack:** Kotlin 2.1.20, Jetpack Compose/Navigation, AndroidX Media3 1.5.1, coroutines/StateFlow, NewPipeExtractor 0.26.5, JUnit, Robolectric, Compose UI tests.

**Spec:** `docs/superpowers/specs/2026-08-27-video-experience-optimization-design.md`

## Global Constraints

- Android `minSdk 26`, `targetSdk 35`; do not add dependencies.
- Home and Watch recommendations return at most 100 unique `ContentKey` values.
- Refresh excludes the complete immediately previous batch; undersupply returns fewer than 100.
- Related and trending remain single-batch sources; only Search continuation is used to fill.
- Collection budget: at most six concurrent topic queries, two pages per query, six continuation requests total, ten-second deadline.
- Adaptive behavior is claimed only for usable multi-track HLS/DASH; progressive remains a fixed-stream fallback.
- Portrait system Back and fullscreen Back semantics remain unchanged.
- Swipe-down navigates explicitly to Home; it never enters system PiP.
- Keep one ExoPlayer and one MediaSession; do not add Paging 3 or an extractor stream cache.
- Use TDD for each task. Do not commit unless the user explicitly requests it.

## File/Boundary Map

- `repository/RecommendationRepository.kt`: capability-aware collection, limits, exclusion, request budget.
- `repository/RecommendationRanker.kt`: deterministic rank/diversity with caller-supplied limit.
- `ui/home/HomeViewModel.kt`, `HomeScreen.kt`: batch-preserving refresh state.
- `ui/watch/WatchViewModel.kt`, `WatchScreen.kt`: recommendation refresh state and lazy content.
- `ui/watch/PlayerGesturePolicy.kt` (new): pure gesture classification.
- `ui/watch/PlayerControls.kt`: one coordinated pointer boundary.
- `navigation/HPreNavHost.kt`, `RootScaffold.kt`: `MinimizeToHome` and explicit top-level Home routing.
- `player/PlaybackUiCoordinator.kt`: minimize state and surface owner/generation state machine.
- `ui/watch/PlayerSurface.kt`, `ui/player/MiniPlayer.kt`, `player/PlayerController.kt`, `SessionPlayerController.kt`: owner-aware surface handoff.
- `player/PlaybackModels.kt`, `StreamSelector.kt`, `StartupStreamSelector.kt`: user quality policy and adaptive-first Auto selection.
- `player/HPrePlaybackService.kt`, `PlaybackSnapshotStore.kt`, `StreamRecoveryCoordinator.kt`: service-owned cap, persistence, bounded fallback.

---

### Task 1: Bounded 100-Item Recommendation Collection

**Files:**
- Modify: `app/src/main/java/com/hpre/app/repository/RecommendationRepository.kt:17-265`
- Modify: `app/src/main/java/com/hpre/app/repository/RecommendationRanker.kt:7-108`
- Test: `app/src/test/java/com/hpre/app/repository/RecommendationRepositoryTest.kt`
- Test: `app/src/test/java/com/hpre/app/repository/RecommendationRankerTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class RecommendationRequest(
      val forceRefresh: Boolean = false,
      val limit: Int = 100,
      val excludedKeys: Set<ContentKey> = emptySet()
  )

  fun interface HomeRecommendationSource {
      suspend fun home(request: RecommendationRequest): AppResult<List<VideoSummary>>
  }

  fun interface WatchRecommendationSource {
      suspend fun recommendations(
          key: ContentKey,
          details: VideoDetails,
          request: RecommendationRequest
      ): AppResult<List<VideoSummary>>
  }
  ```
- `RecommendationRanker.rank(..., limit: Int)` remains the final ranking boundary; callers pass `request.limit`.

- [ ] **Step 1: Write failing repository tests for limit, exclusion, and full-key deduplication**

Add tests that construct at least 130 candidates, include excluded keys, and include identical native IDs under different service IDs:

```kotlin
@Test
fun `home returns at most 100 clean full content keys`() = runTest {
    val excluded = (0 until 20).map(::video).map { it.key }.toSet()
    val result = repositoryWithSearchPages(140).home(
        RecommendationRequest(limit = 100, excludedKeys = excluded)
    ).valueOrThrow()

    assertEquals(100, result.size)
    assertEquals(result.size, result.map { it.key }.toSet().size)
    assertTrue(result.none { it.key in excluded })
}

@Test
fun `undersupply never refills with excluded videos`() = runTest {
    val oldBatch = (0 until 100).map(::video)
    val fresh = (100 until 117).map(::video)
    val result = repositoryWithCandidates(oldBatch + fresh).home(
        RecommendationRequest(excludedKeys = oldBatch.map { it.key }.toSet())
    ).valueOrThrow()

    assertEquals(fresh.map { it.key }, result.map { it.key })
}
```

- [ ] **Step 2: Run the focused tests and verify contract failures**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hpre.app.repository.RecommendationRepositoryTest" --tests "com.hpre.app.repository.RecommendationRankerTest"
```

Expected: FAIL because `RecommendationRequest` and request-based source signatures do not exist and the limit is still 30.

- [ ] **Step 3: Add the request contract and apply exclusion before ranking**

In `RecommendationRepository.kt`, add the request model and change both source interfaces. Normalize limits defensively:

```kotlin
private const val MAX_FEED_LIMIT = 100

private fun RecommendationRequest.safeLimit(): Int = limit.coerceIn(0, MAX_FEED_LIMIT)

private fun cleanCandidates(
    candidates: List<VideoSummary>,
    excludedKeys: Set<ContentKey>
): List<VideoSummary> = candidates
    .distinctBy(VideoSummary::key)
    .filterNot { it.key in excludedKeys }
```

Apply `cleanCandidates` after all currently available source batches are merged and before `ranker.rank(..., limit = request.safeLimit())`. Keep provider-related scoring and history-disabled privacy behavior.

- [ ] **Step 4: Write failing continuation-budget tests**

Use a fake `CatalogRepository`/service that records query, token, concurrent calls, and returns deterministic `SearchPage.nextPage` values. Assert:

```kotlin
assertTrue(fake.maxConcurrentSearches <= 6)
assertTrue(fake.pageCountByQuery.values.all { it <= 2 })
assertTrue(fake.continuationCalls <= 6)
assertEquals(100, result.size)
```

Also test no continuation call when first pages produce 100 clean candidates, no loop on a null token, timeout returns partial success, and one failed topic does not discard successful sources.

- [ ] **Step 5: Implement phased Search continuation collection**

Centralize:

```kotlin
internal const val MAX_TOPIC_CONCURRENCY = 6
internal const val MAX_PAGES_PER_QUERY = 2
internal const val MAX_TOTAL_CONTINUATIONS = 6
internal const val COLLECTION_DEADLINE_MS = 10_000L
internal const val MAX_FEED_LIMIT = 100
```

Keep related/trending as first-page-only. Preserve each successful `SearchPage.nextPage`; fetch at most one continuation per query in deterministic topic-priority order until enough clean candidates exist or the total continuation budget/deadline is reached. Wrap the collection in `withTimeoutOrNull(COLLECTION_DEADLINE_MS)` and return accumulated clean candidates on timeout. Never retry a null token.

- [ ] **Step 6: Run recommendation tests**

Run the command from Step 2. Expected: PASS, including existing direct-related priority, channel diversity, source failure, and history/privacy tests.

---

### Task 2: Batch-Preserving Refresh State

**Files:**
- Modify: `app/src/main/java/com/hpre/app/ui/home/HomeViewModel.kt:16-79`
- Modify: `app/src/main/java/com/hpre/app/ui/home/HomeScreen.kt:71-109`
- Modify: `app/src/main/java/com/hpre/app/ui/watch/WatchViewModel.kt:132-168,276-322`
- Test: `app/src/test/java/com/hpre/app/ui/home/HomeViewModelTest.kt`
- Test: `app/src/test/java/com/hpre/app/ui/watch/WatchViewModelTest.kt`

**Interfaces:**
- Consumes: `RecommendationRequest` and request-based sources from Task 1.
- Produces:
  ```kotlin
  data class HomeContent(
      val videos: List<VideoSummary>,
      val isRefreshing: Boolean = false,
      val refreshError: AppError? = null
  )

  data class RefreshableAsyncState<T>(
      val value: T? = null,
      val isInitialLoading: Boolean = value == null,
      val isRefreshing: Boolean = false,
      val error: AppError? = null
  )
  ```

- [ ] **Step 1: Write failing Home refresh tests**

Add tests for old-batch retention, complete-batch exclusion, latest generation, failed refresh, and repeated A→B→A eligibility:

```kotlin
@Test
fun `refresh keeps A visible and excludes all A keys`() = runTest {
    source.enqueueSuccess(batch("a", 100))
    source.enqueueSuspendedSuccess(batch("b", 17))
    viewModel.load()
    viewModel.refresh()

    assertEquals(batch("a", 100), viewModel.content().videos)
    assertTrue(viewModel.content().isRefreshing)
    assertEquals(batch("a", 100).map { it.key }.toSet(), source.lastRequest.excludedKeys)
}
```

Assert a stale first refresh cannot publish after a second refresh has completed.

- [ ] **Step 2: Run Home tests and verify failures**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hpre.app.ui.home.HomeViewModelTest"
```

Expected: FAIL because refresh currently publishes `Loading` and sends no exclusion set.

- [ ] **Step 3: Implement Home initial-load versus refresh state**

Keep existing initial `Loading/Empty/Error` semantics, but represent loaded content as `HomeUiState.Content(HomeContent)`. On refresh, snapshot `content.videos.map { it.key }.toSet()`, keep videos visible, set `isRefreshing = true`, and call `home(RecommendationRequest(forceRefresh = true, excludedKeys = snapshot))`. Increment generation before launching; only current generation publishes. A refresh error restores `isRefreshing = false`, preserves videos, and fills `refreshError`.

Bind `PullToRefreshBox.isRefreshing` to state and call `viewModel.refresh()` rather than reusing initial load.

- [ ] **Step 4: Write failing Watch recommendation refresh tests**

Test the same immediate-previous-batch semantics independently of video-level generation. Assert empty clean results publish an empty successful value rather than an error, and a video route change invalidates all previous recommendation refreshes.

- [ ] **Step 5: Implement Watch refresh state and generation**

Replace the recommendation `AsyncState<List<VideoSummary>>` with `RefreshableAsyncState<List<VideoSummary>>`. Add `relatedGeneration`; snapshot all current related keys in `refreshRelated()`, keep the old value during refresh, and call the request-based Watch source. Keep comment state and generation unchanged.

- [ ] **Step 6: Run focused ViewModel and repository tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hpre.app.repository.RecommendationRepositoryTest" --tests "com.hpre.app.ui.home.HomeViewModelTest" --tests "com.hpre.app.ui.watch.WatchViewModelTest"
```

Expected: PASS.

---

### Task 3: Lazy Watch Content and Stable Identity

**Files:**
- Modify: `app/src/main/java/com/hpre/app/ui/watch/WatchScreen.kt:401-624`
- Modify: `app/src/main/java/com/hpre/app/ui/watch/CommentsSection.kt:22-54`
- Modify: `app/src/main/java/com/hpre/app/ui/watch/RelatedVideosSection.kt:19-40`
- Modify: `app/src/main/java/com/hpre/app/ui/home/HomeScreen.kt:94-107`
- Test: `app/src/androidTest/java/com/hpre/app/ui/watch/WatchScreenTest.kt`
- Test: `app/src/test/java/com/hpre/app/ui/watch/WatchViewModelTest.kt`

**Interfaces:**
- Consumes: `RefreshableAsyncState<List<VideoSummary>>` from Task 2.
- Produces lazy emitters that are called only inside one `LazyColumn`:
  ```kotlin
  fun LazyListScope.commentsItems(...)
  fun LazyListScope.relatedVideoItems(...)
  ```

- [ ] **Step 1: Add failing 100-row and lazy-composition UI tests**

Supply 100 recommendations with stable keys, assert an off-screen row does not exist before scrolling, scroll to key 99, and assert it exists. Refresh/reorder the list and assert a keyed row still displays the matching title. Add a comments load-more test that fires once near the comments boundary.

- [ ] **Step 2: Compile instrumentation tests and confirm the old eager structure fails the lazy assertion**

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin
```

Expected: compilation succeeds after test fixture adaptation; targeted test fails on an emulator because eager `forEach` composes off-screen rows.

- [ ] **Step 3: Convert content below the fixed player to one LazyColumn**

Keep both portrait/fullscreen `PlayerSurface` branches outside the list. Replace `Column.verticalScroll` with a remembered `LazyListState` and a single `LazyColumn`. Emit metadata sections with constant keys, comments with `comment.commentId`, and recommendations with `video.key.toString()`.

Convert section composables into `LazyListScope` extensions; do not nest another vertical lazy list. Use a `snapshotFlow` over `layoutInfo.visibleItemsInfo` to call `onLoadMoreComments` only near the comment pagination sentinel; rely on the existing ViewModel in-flight/token guard.

- [ ] **Step 4: Run unit tests and compile Android tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hpre.app.ui.watch.WatchViewModelTest"; if ($?) { .\gradlew.bat :app:compileDebugAndroidTestKotlin }
```

Expected: PASS.

---

### Task 4: Portrait Back Removal and Gesture Classification

**Files:**
- Create: `app/src/main/java/com/hpre/app/ui/watch/PlayerGesturePolicy.kt`
- Modify: `app/src/main/java/com/hpre/app/ui/watch/PlayerControls.kt:76-216`
- Modify: `app/src/main/java/com/hpre/app/ui/watch/WatchScreen.kt:345-399`
- Test: `app/src/test/java/com/hpre/app/ui/watch/PlayerGesturePolicyTest.kt`
- Test: `app/src/test/java/com/hpre/app/ui/watch/PlayerControlsPolicyTest.kt`
- Test: `app/src/androidTest/java/com/hpre/app/ui/watch/WatchScreenTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class PlayerDragDecision { UNDECIDED, HORIZONTAL, VERTICAL_DOWN, REJECTED }

  data class PlayerGestureConfig(
      val touchSlopPx: Float,
      val minimizeDistancePx: Float,
      val minimizeVelocityPxPerSecond: Float
  )

  object PlayerGesturePolicy {
      fun classifyDrag(totalX: Float, totalY: Float, touchSlopPx: Float): PlayerDragDecision
      fun shouldMinimize(
          totalY: Float,
          velocityY: Float,
          config: PlayerGestureConfig,
          enabled: Boolean,
          startedInProtectedRegion: Boolean
      ): Boolean
  }
  ```
- `PlayerControlsOverlay` adds `onMinimizeToHome: () -> Unit`, `minimizeEnabled: Boolean`, and `isInPip: Boolean`; the Watch caller derives these from portrait/fullscreen/PiP state.

- [ ] **Step 1: Write failing pure policy tests**

Cover dominant horizontal, dominant downward, upward, below-slop, distance threshold, velocity threshold, disabled state, protected region, and one-shot dispatch. Use explicit values so density does not affect unit tests.

- [ ] **Step 2: Run policy tests and verify missing API failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hpre.app.ui.watch.PlayerGesturePolicyTest" --tests "com.hpre.app.ui.watch.PlayerControlsPolicyTest"
```

Expected: FAIL because the policy/coordinator API is absent.

- [ ] **Step 3: Implement pure classification and one pointer coordinator**

Implement the policy exactly as declared. In `PlayerControlsOverlay`, replace competing full-overlay gesture ownership with one `awaitEachGesture` coordinator that waits for touch slop, classifies the dominant axis, leaves taps and protected controls untouched, consumes only a classified downward drag, calculates vertical velocity, and invokes `onMinimizeToHome` once on release.

Derive thresholds in the composable from density and central constants (56 dp distance and 600 dp/s initial velocity); unit-test the pixel policy. Disable the coordinator in fullscreen, landscape, or PiP.

- [ ] **Step 4: Remove only the portrait overlay Back button**

Delete the `watch_back_button` overlay block from portrait `WatchScreen`. Keep the fullscreen control tagged `control_fullscreen_back`, fullscreen `BackHandler`, and portrait system navigation callback wiring.

- [ ] **Step 5: Update and compile UI tests**

Replace old assertions/clicks on `watch_back_button` with `assertDoesNotExist()`. Add swipe threshold/conflict tests while retaining tap, double-tap, seek bar, play, fullscreen, and fullscreen Back tests.

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hpre.app.ui.watch.PlayerGesturePolicyTest" --tests "com.hpre.app.ui.watch.PlayerControlsPolicyTest"; if ($?) { .\gradlew.bat :app:compileDebugAndroidTestKotlin }
```

Expected: PASS.

---

### Task 5: Explicit Minimize-to-Home Navigation

**Files:**
- Modify: `app/src/main/java/com/hpre/app/player/PlaybackUiCoordinator.kt:7-33`
- Modify: `app/src/main/java/com/hpre/app/navigation/HPreNavHost.kt:256-292`
- Modify: `app/src/main/java/com/hpre/app/navigation/RootScaffold.kt:219-228`
- Modify: `app/src/main/java/com/hpre/app/ui/watch/WatchScreen.kt:246-255`
- Test: `app/src/test/java/com/hpre/app/player/SessionPlayerProtocolTest.kt`
- Test: `app/src/androidTest/java/com/hpre/app/navigation/NavigationFlowTest.kt`
- Test: `app/src/androidTest/java/com/hpre/app/navigation/HomeToWatchNavigationTest.kt`

**Interfaces:**
- Consumes: `onMinimizeToHome` from Task 4.
- Produces:
  ```kotlin
  enum class PlayerPresentation { WATCH, MINIMIZING, MINI_PLAYER, SYSTEM_PIP }
  fun PlaybackUiCoordinator.requestMinimizeToHome()
  fun NavHostController.navigateToHomeFromWatch()
  ```

- [ ] **Step 1: Add failing coordinator and navigation tests**

Assert a minimize request changes presentation once without modifying PiP eligibility. In instrumentation fixtures, open Watch from Home, Search, Channel, Library, Subscriptions, and another Watch; invoke minimize and assert Home is current and only one Home instance is present. Keep an explicit test that system Back still pops normally.

- [ ] **Step 2: Run focused unit test and compile navigation tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hpre.app.player.SessionPlayerProtocolTest"; if ($?) { .\gradlew.bat :app:compileDebugAndroidTestKotlin }
```

Expected: new tests fail because minimize presentation/helper does not exist.

- [ ] **Step 3: Implement minimize intent and Home helper**

Extend the existing coordinator state without removing `watchVisible`, settings, or PiP fields. `requestMinimizeToHome()` is idempotent while `MINIMIZING`.

Implement `navigateToHomeFromWatch()` using the existing top-level navigation pattern: retain an existing Home via `popUpTo(Screen.Home.route)`, `launchSingleTop = true`, and `restoreState = true`; if Home is absent, navigate Home and pop the current Watch route. Wire the gesture callback to request minimize before navigation. Do not route portrait system Back through this helper.

- [ ] **Step 4: Run unit tests and compile Android tests**

Run the command from Step 2. Expected: PASS.

---

### Task 6: Generation-Safe Surface Handoff

**Files:**
- Modify: `app/src/main/java/com/hpre/app/player/PlaybackUiCoordinator.kt`
- Modify: `app/src/main/java/com/hpre/app/player/PlayerController.kt:8-14`
- Modify: `app/src/main/java/com/hpre/app/player/SessionPlayerController.kt:52,358-382`
- Modify: `app/src/main/java/com/hpre/app/ui/watch/PlayerSurface.kt:14-42`
- Modify: `app/src/main/java/com/hpre/app/ui/player/MiniPlayer.kt:46-170`
- Modify: `app/src/main/java/com/hpre/app/navigation/RootScaffold.kt:183-207`
- Modify: `app/src/main/java/com/hpre/app/MainActivity.kt:95-103,245-260`
- Test: `app/src/test/java/com/hpre/app/player/SessionPlayerProtocolTest.kt`
- Test: `app/src/androidTest/java/com/hpre/app/ui/player/MiniPlayerTest.kt`
- Test: `app/src/androidTest/java/com/hpre/app/ui/watch/WatchRecreationTest.kt`
- Test: `app/src/androidTest/java/com/hpre/app/PipEligibilityTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class SurfaceOwner { NONE, WATCH, MINI_PLAYER, SYSTEM_PIP }
  data class SurfaceLease(val owner: SurfaceOwner, val generation: Long)

  fun beginSurfaceHandoff(target: SurfaceOwner): SurfaceLease
  fun confirmSurfaceAttached(lease: SurfaceLease): Boolean
  fun rejectSurfaceAttach(lease: SurfaceLease): Boolean

  fun PlayerController.attachSurface(playerView: PlayerView, lease: SurfaceLease): Boolean
  fun PlayerController.detachSurface(playerView: PlayerView, lease: SurfaceLease): Boolean
  ```

- [ ] **Step 1: Write failing owner/generation state-machine tests**

Test monotonic generations, attach-before-detach, stale Watch detach after mini attach, stale mini detach after PiP attach, and failed target attach retaining the previous owner. Assert no method creates a second player/session.

- [ ] **Step 2: Run protocol tests and verify failures**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hpre.app.player.SessionPlayerProtocolTest"
```

Expected: FAIL because owner-aware leases do not exist.

- [ ] **Step 3: Implement surface leases in coordinator and controller**

Maintain the active `SurfaceLease` and prior owner during a pending handoff. Accept attach/detach only for the current generation; a stale detach returns `false` and does not clear the active `PlayerView`. Confirming target attach changes active owner and allows the exact prior lease to release. Reject/timeout restores the prior owner and sets a recoverable minimize error.

- [ ] **Step 4: Wire Watch, mini-player, and PiP hosts**

Give each `PlayerSurface` an explicit owner and lease. Add an actual compact `PlayerSurface` to `MiniPlayer` while keeping controls/metadata accessible. During minimize, compose the mini host, attach it, confirm the handoff, then allow Watch disposal. `MainActivity` uses `SYSTEM_PIP`; PiP entry supersedes in-app owners and exit requests the currently visible in-app owner.

- [ ] **Step 5: Add and compile lifecycle regression tests**

Assert play state/position survives Watch→mini, activity recreation never has two active owners, and PiP enter/exit rejects stale in-app detach callbacks.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hpre.app.player.SessionPlayerProtocolTest"; if ($?) { .\gradlew.bat :app:compileDebugAndroidTestKotlin }
```

Expected: PASS. On a device, perform three consecutive handoffs; if all show a visible black frame, stop and redesign to a stable root-level surface host as required by the spec.

---

### Task 7: Adaptive-First Playback and Service-Owned Quality Cap

**Files:**
- Modify: `app/src/main/java/com/hpre/app/player/PlaybackModels.kt:8-68`
- Modify: `app/src/main/java/com/hpre/app/player/PlayerController.kt:16-30`
- Modify: `app/src/main/java/com/hpre/app/player/StreamSelector.kt:194-403`
- Modify: `app/src/main/java/com/hpre/app/player/StartupStreamSelector.kt:6-39`
- Modify: `app/src/main/java/com/hpre/app/player/HPrePlaybackService.kt:49-956`
- Modify: `app/src/main/java/com/hpre/app/player/SessionPlayerController.kt:259-716`
- Modify: `app/src/main/java/com/hpre/app/player/PlaybackSnapshotStore.kt:30-505`
- Modify: `app/src/main/java/com/hpre/app/player/StreamRecoveryCoordinator.kt:33-139`
- Test: `app/src/test/java/com/hpre/app/player/StreamSelectorTest.kt`
- Test: `app/src/test/java/com/hpre/app/player/StartupStreamSelectorTest.kt`
- Test: `app/src/test/java/com/hpre/app/player/PlaybackSnapshotStoreTest.kt`
- Test: `app/src/test/java/com/hpre/app/player/StreamRecoveryCoordinatorTest.kt`
- Test: `app/src/test/java/com/hpre/app/player/SessionPlayerProtocolTest.kt`
- Test: `app/src/androidTest/java/com/hpre/app/player/PlaybackServiceTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  sealed interface UserQualityPolicy {
      data class Auto(val maxHeight: Int? = null, val maxBitrate: Int? = null) : UserQualityPolicy
      data class Fixed(val option: QualityOption) : UserQualityPolicy
  }

  data class EffectiveTrack(
      val height: Int?,
      val bitrate: Int?,
      val isAdaptive: Boolean
  )
  ```
- `PlaybackState` exposes `qualityPolicy` and runtime-only `effectiveTrack`; snapshots persist only `qualityPolicy`.

- [ ] **Step 1: Write failing adaptive-first selector tests**

For `QualityPreference.Auto`, assert HLS is selected before DASH and both before progressive. Assert a missing HLS URL selects DASH, missing manifests select existing progressive/merged fallback, explicit fixed options retain existing exact/fallback behavior, and audio-only remains last.

- [ ] **Step 2: Run selector tests and verify old progressive-first failures**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hpre.app.player.StreamSelectorTest" --tests "com.hpre.app.player.StartupStreamSelectorTest"
```

Expected: FAIL on current progressive-first order.

- [ ] **Step 3: Implement adaptive-first Auto selection**

Change only Auto/startup ordering to HLS → DASH → progressive/merged → audio. Keep explicit fixed-quality selection deterministic. Label manifest Auto options adaptive only after Media3 reports multiple supported video tracks; otherwise expose `EffectiveTrack.isAdaptive = false`.

- [ ] **Step 4: Write failing quality-policy protocol and persistence tests**

Assert `Auto(maxHeight = 720)` round-trips through command extras and `PlaybackSnapshotStore`, but `EffectiveTrack` does not persist. Assert service cap parameters use max video height/bitrate, and policy is reapplied after prepare, reconnect, recovery, and restore.

- [ ] **Step 5: Implement service-owned `DefaultTrackSelector` and policy command**

Create one `DefaultTrackSelector(this)` in `HPrePlaybackService` and pass it to `ExoPlayer.Builder.setTrackSelector`. Add a custom MediaSession command with nullable max-height/max-bitrate and fixed-option fields. For Auto, update `player.trackSelectionParameters` with maximum constraints without reprepare. For Fixed progressive fallback, keep bounded source reselection. Report effective track from `onTracksChanged` without overwriting user policy.

- [ ] **Step 6: Migrate snapshot persistence**

Replace `PlaybackSnapshot.selectedQuality` with `qualityPolicy`. Add backward-compatible reading of existing selected-quality keys, converting them to `UserQualityPolicy.Fixed`; write only the new policy keys. Restore policy after fetching fresh `StreamInfo`, never restore an expired URL/effective track.

- [ ] **Step 7: Add bounded source-attempt recovery**

Track attempted source types per content key/session generation. A failed HLS prepare may try DASH once; a failed DASH prepare may try a compatible progressive fallback once; then surface the error. Reset attempts only for a new playback session generation. Preserve position, speed, play intent, and quality cap.

- [ ] **Step 8: Run playback unit tests and compile service instrumentation tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hpre.app.player.StreamSelectorTest" --tests "com.hpre.app.player.StartupStreamSelectorTest" --tests "com.hpre.app.player.StreamRecoveryCoordinatorTest" --tests "com.hpre.app.player.PlaybackSnapshotStoreTest" --tests "com.hpre.app.player.SessionPlayerProtocolTest"; if ($?) { .\gradlew.bat :app:compileDebugAndroidTestKotlin }
```

Expected: PASS.

---

### Task 8: Remove Periodic Probe IPC and Verify the Complete Change

**Files:**
- Modify: `app/src/main/java/com/hpre/app/player/SessionPlayerController.kt:73-194,749-872`
- Modify: `app/src/main/java/com/hpre/app/player/HPrePlaybackService.kt:280-353,886-912`
- Test: `app/src/test/java/com/hpre/app/player/SessionPlayerProtocolTest.kt`
- Test: `app/src/androidTest/java/com/hpre/app/player/PlaybackServiceTest.kt`
- Modify: `docs/manual-test-matrix.md`

**Interfaces:**
- Consumes all prior task interfaces.
- Preserves `getTestingSnapshot()` as an on-demand diagnostic command.

- [ ] **Step 1: Write failing progress/probe separation tests**

Use a fake controller/session command recorder. Advance test time through multiple 500 ms ticks and assert position/duration update while probe command count remains zero. Invoke `getTestingSnapshot()` and assert exactly one probe command. Assert listener callbacks still update buffering, ready, ended, errors, tracks, and disconnect/reconnect generation.

- [ ] **Step 2: Run protocol tests and verify periodic probe failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hpre.app.player.SessionPlayerProtocolTest"
```

Expected: FAIL because each progress tick currently calls `CUSTOM_COMMAND_GET_PROBE_SNAPSHOT`.

- [ ] **Step 3: Separate the local ticker from diagnostics**

Keep the 500 ms ticker reading local `MediaController.currentPosition` and duration while playback/UI needs progress. Remove `pollAuthoritativeServiceState()` from the loop and delete it if unused. Push runtime service errors/track changes through existing listener or one bounded session event. Keep `CUSTOM_COMMAND_GET_PROBE_SNAPSHOT`, service handling, and `getTestingSnapshot()` unchanged for explicit diagnostics.

- [ ] **Step 4: Run the complete unit suite**

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Expected: all unit tests PASS.

- [ ] **Step 5: Run static/build validation**

```powershell
.\gradlew.bat :app:lintDebug; if ($?) { .\gradlew.bat :app:assembleDebug }; if ($?) { .\gradlew.bat :app:compileDebugAndroidTestKotlin }
```

Expected: all commands PASS.

- [ ] **Step 6: Run connected tests when an emulator/device is available**

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

Expected: all instrumentation tests PASS. If no device is available, record this explicitly and do not claim device/PiP/ABR verification.

- [ ] **Step 7: Execute and record the manual performance matrix**

Update `docs/manual-test-matrix.md` with device/API/network, time to first frame, rebuffer count/duration, adaptive track movement under throttling, cap enforcement, three Watch→mini handoffs, 100-row scrolling, activity recreation, and PiP enter/exit. Record before/after observations; do not invent thresholds or results.

- [ ] **Step 8: Inspect the final diff**

```powershell
git diff --check; git status --short; git diff --stat
```

Expected: no whitespace errors; only planned production/tests/docs files are changed. Do not commit unless explicitly requested.
