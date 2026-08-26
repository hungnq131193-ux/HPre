# HPre Rebrand and Playback Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fully rename the Android app to HPre, improve phone playback and startup latency, personalize Home from local activity, and complete a single-player Shorts feed.

**Architecture:** Deliver four independently testable vertical slices: atomic identity migration, Watch/player improvements, local recommendations, and Shorts. Keep provider access behind `VideoService`/`CatalogRepository`, local behavior behind Room repositories, and all playback on the existing app-scoped Media3 session.

**Tech Stack:** Kotlin 2.1.20, Jetpack Compose BOM 2025.02.00, AndroidX Media3 1.5.1, Room 2.6.1, coroutines 1.10.1, OkHttp 4.12.0, NewPipeExtractor v0.26.5, JUnit 4, Compose UI tests.

**Spec:** `docs/superpowers/specs/2026-08-26-hpre-improvements-design.md`

## Global Constraints

- Namespace and application ID are exactly `com.hpre.app`.
- Ensure all tracked product identifiers, symbols, strings, tests, schemas, and documentation use HPre.
- The workspace directory name is outside tracked product content and is not renamed.
- Do not preserve upgrade or data compatibility with the legacy application ID.
- Keep `minSdk = 26`, `compileSdk = 35`, `targetSdk = 35`, and Java/Kotlin target 17.
- Add no dependency, backend, analytics, account, cloud sync, or additional player.
- Keep Room schema version 1 under the new application identity.
- Keep `QualityPreference.Auto` semantics unchanged; startup optimization is a separate selector path.
- Initial playback prefers progressive audio/video at or below 720p and falls back safely when unavailable.
- Shorts require a known duration from 1 through 180 seconds and reuse the single app-scoped player.
- Historical release evidence is deleted, not rewritten; HPre evidence is generated only by a future real verification run.
- Do not commit unless the user explicitly authorizes commits.

---

## File Structure

- `app/src/**/java/com/hpre/app/**`: post-migration production and test package tree.
- `app/src/main/java/com/hpre/app/player/StartupStreamSelector.kt`: startup-only quality policy; leaves normal quality selection unchanged.
- `app/src/main/java/com/hpre/app/repository/LocalInterestSignals.kt`: immutable local personalization input.
- `app/src/main/java/com/hpre/app/repository/RecommendationRanker.kt`: pure deterministic ranking and deduplication.
- `app/src/main/java/com/hpre/app/repository/RecommendationRepository.kt`: bounded provider queries and trending fallback.
- `app/src/main/java/com/hpre/app/repository/ShortsFeedRepository.kt`: topic-derived short-video retrieval and filtering.
- Existing `WatchScreen.kt`, `PlayerControls.kt`, `HomeViewModel.kt`, `ShortsViewModel.kt`, and navigation files remain feature owners.

### Task 1: Retire Invalid Legacy Release Evidence

**Files:**
- Delete: `docs/evidence/task5c-live-playback-android35.xml`
- Delete: `docs/evidence/task5c-live-playback-android35-facts.json`
- Delete: `docs/evidence/task5c-live-playback-android35-build.json`
- Delete: `docs/release-evidence.md`
- Delete: the legacy `ReleaseEvidenceAuditTest.kt` from its pre-migration test package.
- Modify: `docs/manual-test-matrix.md`

**Interfaces:**
- Consumes: approved spec decision that historical hashes must not be rewritten.
- Produces: no test or document claiming that an HPre build passed the legacy verification run.

- [ ] **Step 1: Record the missing HPre release gate**

Add this row to `docs/manual-test-matrix.md` before deleting evidence:

```markdown
| HPre signed release evidence | Not run | API 35 emulator or physical device | Fresh HPre APK | Regenerate facts, XML, hashes, and release notes from one real run before distribution. |
```

- [ ] **Step 2: Delete only the stale artifacts and audit test**

Use file deletes for the five exact files above. Keep `LivePlaybackFacts.kt`, `LivePlaybackFactsTest.kt`, `LivePlaybackGateTest.kt`, and `EvidenceProvenanceValidatorTest.kt`; they remain useful for a future real HPre gate.

- [ ] **Step 3: Verify no remaining test requires deleted files**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hpre.app.core.provenance.*" --tests "com.hpre.app.player.LivePlaybackFactsTest"
```

Expected: PASS without looking for `docs/evidence/*`.

### Task 2: Atomically Migrate Android Identity and Package Tree

**Files:**
- Move: the legacy production package tree to `app/src/main/java/com/hpre/app/`.
- Move: the legacy unit-test package tree to `app/src/test/java/com/hpre/app/`.
- Move: the legacy instrumentation-test package tree to `app/src/androidTest/java/com/hpre/app/`.
- Modify: `app/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/hpre/app/BuildConfigurationTest.kt`

**Interfaces:**
- Consumes: existing Kotlin source sets and manifest components.
- Produces: `BuildConfig.APPLICATION_ID == "com.hpre.app"` and package root `com.hpre.app`.

- [ ] **Step 1: Change the build test first**

```kotlin
package com.hpre.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildConfigurationTest {
    @Test fun application_id_is_hpre() {
        assertEquals("com.hpre.app", BuildConfig.APPLICATION_ID)
    }
}
```

- [ ] **Step 2: Run the test and confirm the old identity fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.hpre.app.BuildConfigurationTest"`

Expected: FAIL because the package tree/namespace has not moved yet.

- [ ] **Step 3: Move all three source trees and replace package references**

Move the directories as one mechanical operation and update exact package declarations and imports to `com.hpre.app`; do not perform broad substring replacement.

- [ ] **Step 4: Update build and manifest identity**

```kotlin
// app/build.gradle.kts
namespace = "com.hpre.app"
applicationId = "com.hpre.app"
testInstrumentationRunner = "com.hpre.app.testing.HPreTestRunner"
```

```kotlin
// settings.gradle.kts
rootProject.name = "HPre"
```

Set manifest application to `.HPreApplication`, service to `.player.HPrePlaybackService`, and label to `HPre`.

- [ ] **Step 5: Compile all source sets**

Run: `.\gradlew.bat compileDebugKotlin compileDebugUnitTestKotlin compileDebugAndroidTestKotlin`

Expected: compilation failures, if any, identify only legacy-prefixed symbols handled in Task 3.

### Task 3: Rename Brand Symbols, Storage, Runner, and Documentation

**Files:**
- Rename the legacy application class/file to `HPreApplication.kt`.
- Rename the legacy playback service class/file to `player/HPrePlaybackService.kt`.
- Rename the legacy database class/file to `database/HPreDatabase.kt`.
- Rename the legacy theme class/file to `core/designsystem/HPreTheme.kt`.
- Rename the legacy navigation host class/file to `navigation/HPreNavHost.kt`.
- Rename the legacy instrumentation runner class/file to `HPreTestRunner.kt`.
- Rename the legacy test application class/file to `TestHPreApplication.kt`.
- Modify: `di/AppContainer.kt`, `navigation/RootScaffold.kt`, all references/tests/docs
- Replace the legacy Room schema directory with generated `app/schemas/com.hpre.app.database.HPreDatabase/`.
- Test: `app/src/test/java/com/hpre/app/BrandIdentityTest.kt`

**Interfaces:**
- Consumes: package identity from Task 2.
- Produces: `HPreApplication`, `HPrePlaybackService`, `HPreDatabase`, `HPreTheme`, `HPreNavHost`, `HPreTestRunner`, database name `hpre.db`, and instrumentation keys `hpreSmokeQuery`/`hpreLivePlayback`.

- [ ] **Step 1: Add a brand guard that does not contain the banned literal**

```kotlin
class BrandIdentityTest {
    @Test fun tracked_product_files_do_not_contain_old_brand() {
        val banned = "flow" + "tube"
        val root = generateSequence(java.io.File(".").canonicalFile) { it.parentFile }
            .first { java.io.File(it, "settings.gradle.kts").exists() }
        val excluded = setOf(".git", ".gradle", ".idea", "build", ".superpowers")
        val violations = root.walkTopDown()
            .onEnter { it.name !in excluded }
            .filter { it.isFile }
            .filter { it.extension in setOf("kt", "kts", "md", "json", "xml", "toml", "properties") }
            .filter { it.readText().contains(banned, ignoreCase = true) }
            .map { it.relativeTo(root).path }
            .toList()
        org.junit.Assert.assertTrue(violations.joinToString(), violations.isEmpty())
    }
}
```

- [ ] **Step 2: Run it and confirm residual brand references fail**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.hpre.app.BrandIdentityTest"`

Expected: FAIL with remaining files.

- [ ] **Step 3: Rename exact symbols and local storage**

Rename the listed classes/files and update all imports/references. In `DefaultAppContainer` use:

```kotlin
override val database: HPreDatabase by lazy {
    Room.databaseBuilder(appContext, HPreDatabase::class.java, "hpre.db").build()
}
```

Use `https://hpre.test/...` for test-only canonical URLs.

- [ ] **Step 4: Rename instrumentation arguments**

Use the exact keys `hpreSmokeQuery` and `hpreLivePlayback` in test runners, live gates, smoke tests, commands, and docs.

- [ ] **Step 5: Regenerate Room schema under the new database class**

Delete the old schema directory, run `.\gradlew.bat kspDebugKotlin`, and confirm `app/schemas/com.hpre.app.database.HPreDatabase/1.json` exists with schema version 1.

- [ ] **Step 6: Rename historical design/plan filenames and all remaining text**

Rename files whose names contain the old brand, including the V1 spec/plan, then replace the remaining case variants in tracked product files. Do not scan `.superpowers/`, build outputs, or the workspace path.

- [ ] **Step 7: Verify identity and full unit suite**

Run:

```powershell
.\gradlew.bat clean testDebugUnitTest assembleDebug
```

Expected: PASS; `BrandIdentityTest` reports no tracked references.

### Task 4: Add Fast Startup Stream Selection

**Files:**
- Create: `app/src/main/java/com/hpre/app/player/StartupStreamSelector.kt`
- Modify: `player/SessionPlayerController.kt`
- Modify: `player/HPrePlaybackService.kt`
- Test: `app/src/test/java/com/hpre/app/player/StartupStreamSelectorTest.kt`

**Interfaces:**
- Consumes: `StreamSelector.selectStream(StreamInfo, QualityPreference)`.
- Produces: `StartupStreamSelector.select(info: StreamInfo, maxHeight: Int = 720): AppResult<SelectedStreams>`.

- [ ] **Step 1: Write failing startup-selection tests**

Cover: progressive 720 beats progressive 1080; progressive 1080 is used if it is the only progressive stream; compatible merged A/V remains fallback; invalid streams still fail.

```kotlin
@Test fun startup_prefers_progressive_at_or_below_720() {
    val result = StartupStreamSelector.select(streamInfo(progressive(1080), progressive(720)))
    val selected = (result as AppResult.Success).value
    assertEquals(PlaybackStreamType.PROGRESSIVE, selected.streamType)
    assertEquals(720, selected.videoStream?.height)
}
```

- [ ] **Step 2: Run and confirm missing selector failure**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.hpre.app.player.StartupStreamSelectorTest"`

- [ ] **Step 3: Implement a two-stage selector without changing Auto**

```kotlin
object StartupStreamSelector {
    const val DEFAULT_MAX_HEIGHT = 720

    fun select(info: StreamInfo, maxHeight: Int = DEFAULT_MAX_HEIGHT): AppResult<SelectedStreams> {
        val preferred = StreamSelector.selectStream(info, QualityPreference.ExactOrBelow(maxHeight))
        val selected = (preferred as? AppResult.Success)?.value
        if (selected?.streamType == PlaybackStreamType.PROGRESSIVE) return preferred

        val auto = StreamSelector.selectStream(info, QualityPreference.Auto)
        val autoSelected = (auto as? AppResult.Success)?.value
        return if (autoSelected?.streamType == PlaybackStreamType.PROGRESSIVE) auto else preferred
    }
}
```

If no progressive candidate exists, preserve the existing preferred merged/manifest/audio cascade; use `Auto` only to recover an otherwise excluded progressive candidate.

- [ ] **Step 4: Wire initial prepare only**

In `SessionPlayerController` and `HPrePlaybackService`, use `SpecificOption` for explicit user quality and `StartupStreamSelector.select(streamInfo)` when `initialQuality == null`. Quality switches and recovery keep existing behavior.

- [ ] **Step 5: Run player tests**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.hpre.app.player.*"`

### Task 5: Prepare Playback Without Waiting for Metadata Sections

**Files:**
- Modify: `ui/watch/WatchViewModel.kt`
- Test: `app/src/test/java/com/hpre/app/ui/watch/WatchViewModelTest.kt`

**Interfaces:**
- Consumes: `CatalogRepository.video`, `VideoService.streamInfo`, `HistoryRepository.getHistoryItem`.
- Produces: stream preparation independent of details, related videos, and comments.

- [ ] **Step 1: Add a timing-independent concurrency test**

Use two `CompletableDeferred` values. Complete `streamInfo` while details remains pending and assert `FakePlayerController.prepare` was called before completing details.

```kotlin
streamDeferred.complete(AppResult.Success(streamInfo))
advanceUntilIdle()
assertEquals(key, player.preparedKey)
assertTrue(viewModel.uiState.value.isLoading)
```

- [ ] **Step 2: Run and confirm sequential implementation fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.hpre.app.ui.watch.WatchViewModelTest"`

- [ ] **Step 3: Split load into concurrent jobs**

Within the generation-scoped load coroutine, start details, stream, and local history with `async`. Await stream first; on success compute resume from the local item and its own `durationSeconds`, then call `playerController.prepare`. Start related/comments immediately after valid key activation. Await details separately to populate `WatchUiState.details`.

- [ ] **Step 4: Preserve cancellation and partial errors**

Stream failure remains fatal. Details failure sets metadata error but must not tear down already prepared playback. All state writes check generation and key.

- [ ] **Step 5: Run Watch and player unit tests**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.hpre.app.ui.watch.*" --tests "com.hpre.app.player.*"`

### Task 6: Implement Thin Accessible Seek Bar

**Files:**
- Modify: `ui/watch/PlayerControls.kt`
- Test: `app/src/androidTest/java/com/hpre/app/ui/watch/WatchScreenTest.kt`

**Interfaces:**
- Consumes: `PlaybackState.currentPositionMs`, `durationMs`, and `onSeekTo`.
- Produces: tag `player_progress_slider`, visible track no thicker than 3 dp, interaction height at least 48 dp, and `setProgress` semantics.

- [ ] **Step 1: Add Compose assertions for size and semantics**

Keep existing enabled/disabled tests, add an assertion that the tagged interaction node has height at least 48 dp, and invoke `SemanticsActions.SetProgress` to verify seeking.

- [ ] **Step 2: Run the targeted instrumentation test and observe the 24 dp failure**

Run: `.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.hpre.app.ui.watch.WatchScreenTest`

- [ ] **Step 3: Separate visible thickness from touch target**

Wrap the slider in `Box(Modifier.fillMaxWidth().height(48.dp))`; center it vertically and use Material3 `Slider` custom `track`/`thumb` slots so the visible track is 3 dp and thumb is compact. Put the test tag and semantics on the 48 dp wrapper.

- [ ] **Step 4: Re-run Watch instrumentation tests**

Expected: current slider enablement and seek tests still pass.

### Task 7: Apply the Approved Mobile-First Watch Layout

**Files:**
- Modify: `ui/watch/WatchScreen.kt`
- Modify: `ui/watch/RelatedVideosSection.kt`
- Modify: `ui/watch/CommentsSection.kt`
- Test: `app/src/androidTest/java/com/hpre/app/ui/watch/WatchScreenTest.kt`

**Interfaces:**
- Consumes: existing Watch state/actions.
- Produces: no portrait `TopAppBar`; overlay back button still tagged `watch_back_button`; action chips; compact channel/description card; comments before related videos.

- [ ] **Step 1: Add phone-width layout tests**

At a 360 dp width assert `watch_back_button`, `watch_action_row`, `watch_channel_card`, `comments_section`, and `related_videos_section` exist and no node is horizontally clipped.

- [ ] **Step 2: Remove the portrait top app bar and preserve navigation**

Place an `IconButton` over the top-start of `player_container`, respect system insets, and retain the existing test tag and callback.

- [ ] **Step 3: Replace the metadata action row**

Use a horizontally scrollable `Row` of `AssistChip`/`FilterChip` actions for local follow, share, and save. Do not add a remote like action because it is unsupported.

- [ ] **Step 4: Combine channel and collapsed description**

Use one rounded `Surface` with channel identity at top and description preview below. Preserve current dialog, share validation, and expansion behavior.

- [ ] **Step 5: Reorder below-player sections**

Render comments before related videos and preserve independent loading/error/retry states.

- [ ] **Step 6: Run Watch, recreation, and navigation tests**

Run the three instrumentation classes for Watch screen, recreation, and Home-to-Watch navigation.

### Task 8: Add Pure Local Recommendation Ranking

**Files:**
- Create: `repository/LocalInterestSignals.kt`
- Create: `repository/RecommendationRanker.kt`
- Test: `app/src/test/java/com/hpre/app/repository/RecommendationRankerTest.kt`

**Interfaces:**
- Produces:

```kotlin
data class LocalInterestSignals(
    val recentQueries: List<String>,
    val watchedChannelFrequency: Map<String, Int>,
    val recentlyWatched: Set<ContentKey>
)

object RecommendationRanker {
    fun rank(
        candidates: List<VideoSummary>,
        signals: LocalInterestSignals,
        limit: Int = 30
    ): List<VideoSummary>
}
```

- [ ] **Step 1: Test recency, title match, channel affinity, dedupe, exclusion, and stable ties**

Use candidate lists with duplicate keys and fixed signals. Assert recent query title matches beat older matches; repeated channels add affinity; watched candidates are excluded when alternatives exist; input order breaks score ties.

- [ ] **Step 2: Implement deterministic scoring**

Normalize with `Locale.ROOT`; assign query weights `recentQueries.size - index`, add exact token-match points, add channel frequency, dedupe before scoring, and use original index as the final tie-breaker. If exclusion would empty the feed, permit watched candidates as fallback.

- [ ] **Step 3: Run ranker tests**

Run the single test class; expected PASS with no Android dependencies.

### Task 9: Build Bounded Recommendation Retrieval and Home Integration

**Files:**
- Create: `repository/RecommendationRepository.kt`
- Modify: `di/AppContainer.kt`
- Modify: `ui/home/HomeViewModel.kt`
- Modify: `ui/home/HomeScreen.kt`
- Modify: `navigation/HPreNavHost.kt`
- Test: `repository/RecommendationRepositoryTest.kt`
- Test: `ui/home/HomeViewModelTest.kt`

**Interfaces:**
- Produces:

```kotlin
class RecommendationRepository(
    private val catalogRepository: CatalogRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val historyRepository: HistoryRepository
) {
    suspend fun home(forceRefresh: Boolean = false): AppResult<List<VideoSummary>>
}
```

- [ ] **Step 1: Test empty-history trending fallback**

Use `flowOf(emptyList())` repositories and assert only `getTrending` contributes results.

- [ ] **Step 2: Test bounded personalized queries and partial failure**

Take at most 3 recent search queries and 2 watch-derived title/channel terms. Search with `SearchFilter.VIDEOS`, merge only `SearchResultItem.VideoItem`, and assert one failed query does not fail successful results.

- [ ] **Step 3: Implement one-shot local snapshots**

Use `observeRecentQueries(3).first()` and `observeHistory().first()`. Build signals, run bounded searches concurrently with `supervisorScope`, rank results, and fill to the feed limit with deduplicated trending items.

- [ ] **Step 4: Wire dependency injection and Home**

Add `val recommendationRepository: RecommendationRepository` to `AppContainer`. Change `HomeViewModel` to call `home(forceRefresh)` while keeping loading/content/empty/error state behavior. Update pull-to-refresh to call `load(forceRefresh = true)`.

- [ ] **Step 5: Run repository and Home tests**

Expected: old trending-only tests are adapted and personalization tests pass.

### Task 10: Build Topic-Derived Shorts Feed

**Files:**
- Create: `repository/ShortsFeedRepository.kt`
- Modify: `ui/shorts/ShortsViewModel.kt`
- Modify: `di/AppContainer.kt`
- Modify: `navigation/HPreNavHost.kt`
- Test: `repository/ShortsFeedRepositoryTest.kt`
- Test: `ui/shorts/ShortsViewModelTest.kt`

**Interfaces:**
- Produces:

```kotlin
class ShortsFeedRepository(
    private val catalogRepository: CatalogRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val historyRepository: HistoryRepository
) {
    suspend fun load(forceRefresh: Boolean = false): AppResult<List<VideoSummary>>

    companion object {
        const val MAX_DURATION_SECONDS = 180L
        fun filterCandidates(items: List<VideoSummary>): List<VideoSummary>
    }
}
```

- [ ] **Step 1: Test the exact duration and content boundary**

Assert 1 and 180 seconds are kept, 181/null/zero/live are removed, keys are deduplicated, and `isShort == true` candidates sort before duration-only candidates.

- [ ] **Step 2: Test local topics and new-user fallback**

Use recent query/watch topics when available. With no history, use exactly three fixed neutral queries: `short video`, `quick tutorial`, and `music short`.

- [ ] **Step 3: Implement bounded retrieval**

Search at most three unique normalized topics, merge video items, filter, and return Empty content as `AppResult.Success(emptyList())`. Return provider failure only when every request fails and no candidates exist.

- [ ] **Step 4: Update ShortsViewModel**

Remove the `supportsShorts` gate because this feature derives candidates from search. Constructor becomes:

```kotlin
class ShortsViewModel(
    private val feedRepository: ShortsFeedRepository,
    private val videoService: VideoService,
    private val playerController: PlayerController,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
)
```

Keep one activation job; cancel it when page changes so stale stream responses cannot prepare the wrong page. On success call `playerController.prepare(video.key, streamInfo, playWhenReady = true)`.

- [ ] **Step 5: Wire container/navigation and run unit tests**

Expected: no player creation per card and no semantic-provider Shorts requirement.

### Task 11: Complete Shorts Playback UI

**Files:**
- Modify: `ui/shorts/ShortsScreen.kt`
- Test: `app/src/androidTest/java/com/hpre/app/ui/shorts/ShortsScreenTest.kt`
- Test: `app/src/androidTest/java/com/hpre/app/ui/player/MiniPlayerTest.kt`

**Interfaces:**
- Consumes: `ShortsUiState.Content`, the shared `PlayerController`, share launcher, and local playlist action.
- Produces: active page player surface, title/channel/duration, play/pause, share, save; pager retains one adjacent page.

- [ ] **Step 1: Add UI tests for content and handoff**

Assert pager exists, active page has one `PlayerSurface`, swiping activates the next key once, and no additional controller/player instance is created.

- [ ] **Step 2: Render active-page media**

Keep `VerticalPager(beyondViewportPageCount = 1)`. Only the current page attaches `PlayerSurface`; adjacent pages render thumbnails to avoid surface/player duplication.

- [ ] **Step 3: Add truthful controls**

Overlay title, channel, duration, play/pause, share, and local save. Omit remote like/comment mutation. Preserve Empty, Error with Retry, and Unavailable only for missing required provider data.

- [ ] **Step 4: Verify navigation lifecycle**

Switching away follows existing app-scoped playback policy and MiniPlayer behavior; do not add a Shorts-specific player lifecycle.

- [ ] **Step 5: Run Shorts and MiniPlayer instrumentation tests**

Expected: PASS on API 35 emulator when available.

### Task 12: Final Verification and HPre Release Readiness Note

**Files:**
- Modify: `docs/manual-test-matrix.md`
- Modify: `docs/upstream-smoke-test.md`
- Modify: `docs/privacy-and-content-boundaries.md`
- Modify: `docs/dependency-decision.md`

**Interfaces:**
- Consumes: all prior tasks.
- Produces: reproducible build/test evidence and an explicit unrun release-evidence gate.

- [ ] **Step 1: Run the complete local build gate**

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug
```

Expected: all tasks SUCCESS.

- [ ] **Step 2: Scan tracked project content**

Use `git grep -in HPre -- ':!docs/superpowers/plans/2026-08-26-hpre-improvements.md' ':!docs/superpowers/specs/2026-08-26-hpre-improvements-design.md'` only during execution while those transition documents still describe the migration. Before completion, rewrite the transition wording so an unexcluded `git grep -in HPre` returns no matches. The workspace directory itself is not searched.

- [ ] **Step 3: Run Android instrumentation when an emulator is attached**

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

If unavailable, record the exact reason and leave HPre release evidence marked Not run.

- [ ] **Step 4: Run a real upstream smoke test only with an approved query**

```powershell
.\gradlew.bat connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.hpreSmokeQuery=<approved-query>'
```

Do not claim pass unless this command actually runs successfully.

- [ ] **Step 5: Compare playback startup on one device/network**

Record stream request start, player prepare, and first-frame milestones before/after where baseline data exists. Report observations without an absolute latency guarantee.

- [ ] **Step 6: Confirm distribution blocker**

The APK may be used for development after tests pass, but release documentation must continue to state that signed HPre release evidence has not been regenerated until a real release gate is performed.
