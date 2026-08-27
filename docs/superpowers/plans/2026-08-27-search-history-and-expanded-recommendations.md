# Search History and Expanded Recommendations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist and manage local search history, then use bounded local signals to expand recommendations on Home and Watch.

**Architecture:** Connect `SearchViewModel` to the existing Room-backed `SearchHistoryRepository` and gate collection with `PlaybackPreferences.isHistoryEnabled`. Extend `RecommendationRepository` into the shared Home/Watch recommendation boundary, using deterministic local ranking and bounded provider searches while preserving partial-failure isolation.

**Tech Stack:** Kotlin, coroutines and Flow, Jetpack ViewModel, Jetpack Compose Material 3, Room, DataStore-backed `PlaybackPreferences`, JUnit 4, Compose UI tests, Gradle.

**Spec:** `docs/superpowers/specs/2026-08-27-search-history-and-expanded-recommendations-design.md`

## Global Constraints

- Keep all history and ranking data local; add no backend, account integration, telemetry, AI service, or third-party dependency.
- Do not add a Room table or schema migration.
- The existing history setting controls both search and watch history collection and personalization.
- Disabling history retains existing data but excludes it from personalization and prevents new writes.
- At most 3 recent explicit queries and 6 supplemental searches contribute to one recommendation load.
- Return at most 30 recommendation videos, deduplicated by `ContentKey`.
- Preserve structured cancellation and isolate partial source failures.
- Preserve existing Home, Search, and Watch visual language and navigation.

---

## File Structure

- Modify `app/src/main/java/com/hpre/app/ui/search/SearchViewModel.kt`: replace in-memory recent queries with Room state, history gating, mutation state, and safe errors.
- Modify `app/src/main/java/com/hpre/app/ui/search/SearchScreen.kt`: render persisted history header, delete actions, confirmation dialog, and Snackbar feedback.
- Modify `app/src/main/res/values/strings.xml`: add localized search-history actions, confirmation, and failure copy.
- Modify `app/src/main/java/com/hpre/app/repository/RecommendationRanker.kt`: add explicit ranking context and deterministic channel diversification.
- Modify `app/src/main/java/com/hpre/app/repository/RecommendationRepository.kt`: gate Home personalization and provide the shared bounded Watch recommendation source.
- Modify `app/src/main/java/com/hpre/app/di/AppContainer.kt`: inject playback preferences and expose the shared recommendation implementation.
- Modify `app/src/main/java/com/hpre/app/navigation/HPreNavHost.kt`: pass history dependencies to Search and the Watch recommendation source to Watch.
- Modify `app/src/main/java/com/hpre/app/ui/watch/WatchViewModel.kt`: load merged Watch recommendations after metadata is available while preserving independent section state.
- Modify repository, ViewModel, and Compose tests alongside each production change.

### Task 1: Persisted Search History ViewModel

**Files:**
- Modify: `app/src/main/java/com/hpre/app/ui/search/SearchViewModel.kt:42-60,133-151,274-301`
- Modify: `app/src/test/java/com/hpre/app/ui/search/SearchViewModelTest.kt:280-298`

**Interfaces:**
- Consumes: `SearchHistoryRepository.observeRecentQueries(limit: Int): Flow<List<LocalSearchHistoryItem>>`, `recordQuery(rawQuery: String, timestamp: Long): AppResult<Unit>`, `deleteQuery(rawQuery: String): AppResult<Unit>`, `clearHistory(): AppResult<Unit>`, and `PlaybackPreferences.isHistoryEnabled: Flow<Boolean>`.
- Produces: `SearchHistoryUiState`, `SearchViewModel.historyState: StateFlow<SearchHistoryUiState>`, `removeRecentQuery(String)`, `clearRecentQueries()`, and `consumeHistoryError()`.

- [ ] **Step 1: Replace the in-memory-history test with Room-source behavior tests**

Add a mutable fake repository and preferences to `SearchViewModelTest.kt`:

```kotlin
private class FakeSearchHistoryRepository(
    initial: List<LocalSearchHistoryItem> = emptyList()
) : SearchHistoryRepository {
    val items = MutableStateFlow(initial)
    val recorded = mutableListOf<String>()
    var deleteResult: AppResult<Unit> = AppResult.Success(Unit)
    var clearResult: AppResult<Unit> = AppResult.Success(Unit)

    override fun observeRecentQueries(limit: Int) = items.map { it.take(limit) }
    override suspend fun recordQuery(rawQuery: String, timestamp: Long): AppResult<Unit> {
        recorded += rawQuery
        items.value = listOf(LocalSearchHistoryItem(rawQuery, timestamp)) +
            items.value.filterNot { it.query == rawQuery }
        return AppResult.Success(Unit)
    }
    override suspend fun deleteQuery(rawQuery: String): AppResult<Unit> {
        if (deleteResult is AppResult.Success) {
            items.value = items.value.filterNot { it.query == rawQuery }
        }
        return deleteResult
    }
    override suspend fun clearHistory(): AppResult<Unit> {
        if (clearResult is AppResult.Success) items.value = emptyList()
        return clearResult
    }
}

private class FakePlaybackPreferences(enabled: Boolean) : PlaybackPreferences {
    override val isHistoryEnabled = MutableStateFlow(enabled)
    override val isBackgroundPlaybackEnabled = flowOf(false)
    override val isPipEnabled = flowOf(false)
    override suspend fun setHistoryEnabled(enabled: Boolean) { isHistoryEnabled.value = enabled }
    override suspend fun setBackgroundPlaybackEnabled(enabled: Boolean) = Unit
    override suspend fun setPipEnabled(enabled: Boolean) = Unit
}
```

Add tests asserting that a new ViewModel immediately observes existing items, explicit submission records exactly once when enabled, 400 ms typed search records nothing, and submission records nothing when disabled:

```kotlin
@Test fun persisted_queries_are_observed_and_explicit_submit_records_once() = runTest(testDispatcher) {
    val history = FakeSearchHistoryRepository(listOf(LocalSearchHistoryItem("saved", 1L)))
    val model = searchViewModel(history, FakePlaybackPreferences(true))
    advanceUntilIdle()
    assertEquals(listOf("saved"), model.historyState.value.items.map { it.query })

    model.onQuerySubmitted("compose")
    advanceUntilIdle()
    assertEquals(listOf("compose"), history.recorded)
}

@Test fun debounced_typing_does_not_record_history() = runTest(testDispatcher) {
    val history = FakeSearchHistoryRepository()
    val model = searchViewModel(history, FakePlaybackPreferences(true))
    model.onQueryChanged("compose")
    advanceTimeBy(500)
    advanceUntilIdle()
    assertTrue(history.recorded.isEmpty())
}

@Test fun disabled_history_does_not_record_explicit_search() = runTest(testDispatcher) {
    val history = FakeSearchHistoryRepository()
    val model = searchViewModel(history, FakePlaybackPreferences(false))
    model.onQuerySubmitted("compose")
    advanceUntilIdle()
    assertTrue(history.recorded.isEmpty())
}
```

- [ ] **Step 2: Run the focused tests and verify the old implementation fails**

Run: `./gradlew testDebugUnitTest --tests "com.hpre.app.ui.search.SearchViewModelTest"`

Expected: FAIL because the constructor lacks history dependencies and `historyState`.

- [ ] **Step 3: Add Room-backed history state and gated writes**

In `SearchViewModel.kt`, define:

```kotlin
data class SearchHistoryUiState(
    val items: List<LocalSearchHistoryItem> = emptyList(),
    val isMutationInFlight: Boolean = false,
    val error: AppError? = null
)
```

Add constructor dependencies:

```kotlin
private val searchHistoryRepository: SearchHistoryRepository,
private val playbackPreferences: PlaybackPreferences
```

Build state from Room and keep mutation fields local:

```kotlin
private val historyMutation = MutableStateFlow(SearchHistoryUiState())
val historyState = combine(
    searchHistoryRepository.observeRecentQueries(20),
    historyMutation
) { items, mutation -> mutation.copy(items = items) }
    .stateIn(viewModelScope, SharingStarted.Eagerly, SearchHistoryUiState())
```

Replace `recordRecentQuery(normalized)` with a non-blocking gated write while starting network search immediately:

```kotlin
viewModelScope.launch {
    if (playbackPreferences.isHistoryEnabled.first()) {
        when (val result = searchHistoryRepository.recordQuery(normalized)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> historyMutation.update { it.copy(error = result.error) }
        }
    }
}
```

Delete the `_recentQueries`, `recordRecentQuery`, and direct list mutation code. Update `provideFactory` to require both new dependencies.

- [ ] **Step 4: Add mutation success/failure tests and minimal implementation**

Add tests asserting delete and clear update from fake repository emissions, failure retains items, `isMutationInFlight` returns to false, and `consumeHistoryError()` clears only the error:

```kotlin
@Test fun failed_delete_retains_room_items_and_exposes_safe_error() = runTest(testDispatcher) {
    val history = FakeSearchHistoryRepository(listOf(LocalSearchHistoryItem("saved", 1L))).apply {
        deleteResult = AppResult.Failure(AppError.Unknown)
    }
    val model = searchViewModel(history, FakePlaybackPreferences(true))
    model.removeRecentQuery("saved")
    advanceUntilIdle()
    assertEquals(listOf("saved"), model.historyState.value.items.map { it.query })
    assertEquals(AppError.Unknown, model.historyState.value.error)
    assertFalse(model.historyState.value.isMutationInFlight)
}
```

Implement both mutations through one private suspending helper that sets `isMutationInFlight`, executes the repository call, stores only failures, and never edits `items` directly.

- [ ] **Step 5: Run Search ViewModel tests**

Run: `./gradlew testDebugUnitTest --tests "com.hpre.app.ui.search.SearchViewModelTest"`

Expected: PASS, including existing debounce, pagination, stale-result, and error tests updated to construct the ViewModel with fakes.

- [ ] **Step 6: Commit the ViewModel slice**

```bash
git add app/src/main/java/com/hpre/app/ui/search/SearchViewModel.kt app/src/test/java/com/hpre/app/ui/search/SearchViewModelTest.kt
git commit -m "feat: persist search history in view model"
```

### Task 2: Search History Management UI

**Files:**
- Modify: `app/src/main/java/com/hpre/app/ui/search/SearchScreen.kt:89-103,190-215,282-322`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/androidTest/java/com/hpre/app/ui/search/SearchScreenTest.kt`
- Modify: `app/src/main/java/com/hpre/app/navigation/HPreNavHost.kt:68-74`

**Interfaces:**
- Consumes: `SearchViewModel.historyState`, `removeRecentQuery`, `clearRecentQueries`, and `consumeHistoryError` from Task 1.
- Produces: test tags `recent_queries_header`, `clear_search_history`, `confirm_clear_search_history`, `cancel_clear_search_history`, and existing `recent_item_<query>` rows.

- [ ] **Step 1: Add failing Compose tests for delete-one and clear-all confirmation**

Create a ViewModel using the Task 1 fake repository, render `SearchScreen`, and assert:

```kotlin
composeTestRule.onNodeWithTag("recent_queries_header").assertIsDisplayed()
composeTestRule.onNodeWithTag("recent_item_saved").assertIsDisplayed()
composeTestRule.onNodeWithTag("remove_recent_saved").performClick()
composeTestRule.onNodeWithTag("recent_item_saved").assertDoesNotExist()
```

For clear-all, click `clear_search_history`, verify the dialog, cancel once and confirm data remains, then reopen and confirm deletion:

```kotlin
composeTestRule.onNodeWithTag("clear_search_history").performClick()
composeTestRule.onNodeWithTag("cancel_clear_search_history").performClick()
composeTestRule.onNodeWithTag("recent_item_saved").assertIsDisplayed()
composeTestRule.onNodeWithTag("clear_search_history").performClick()
composeTestRule.onNodeWithTag("confirm_clear_search_history").performClick()
composeTestRule.onNodeWithTag("recent_item_saved").assertDoesNotExist()
```

- [ ] **Step 2: Run focused instrumentation test and verify failure**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.hpre.app.ui.search.SearchScreenTest`

Expected: FAIL because the header, dialog, and tags do not exist. If no device is connected, record that constraint and continue with compilation plus unit tests.

- [ ] **Step 3: Implement the history header and confirmation dialog**

Collect `historyState` instead of `recentQueries`. Add `var showClearConfirmation by rememberSaveable { mutableStateOf(false) }`. Pass `historyState.items.map(LocalSearchHistoryItem::query)` to `RecentQueriesList`.

Add a header above rows containing localized `Recent searches` and a text action tagged `clear_search_history`. Disable delete and clear actions while `isMutationInFlight` is true. Add a Material 3 `AlertDialog` with explicit confirm/cancel tags; confirmation closes the dialog and calls `clearRecentQueries()`.

Give every row-delete button `Modifier.testTag("remove_recent_$q")` and retain a localized content description.

- [ ] **Step 4: Add safe failure feedback**

Add a `SnackbarHostState`, and on each non-null `historyState.error`, show localized `Search history could not be updated` before calling `consumeHistoryError()`. Place the screen content in a `Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) })` without changing navigation or result layout.

Add string resources for recent searches, clear all, confirmation title/body, confirm, cancel, and failure. Follow the language already used by `values/strings.xml`.

- [ ] **Step 5: Wire production dependencies**

Update `HPreNavHost.kt`:

```kotlin
SearchViewModel.provideFactory(
    repository = container.catalogRepository,
    videoService = container.videoService,
    searchHistoryRepository = container.searchHistoryRepository,
    playbackPreferences = container.playbackPreferences
)
```

- [ ] **Step 6: Verify Search UI and compile resources**

Run: `./gradlew testDebugUnitTest --tests "com.hpre.app.ui.search.SearchViewModelTest" assembleDebug`

Expected: PASS and APK assembly succeeds. If available, rerun the focused `connectedDebugAndroidTest` command and expect PASS.

- [ ] **Step 7: Commit the Search UI slice**

```bash
git add app/src/main/java/com/hpre/app/ui/search/SearchScreen.kt app/src/main/res/values/strings.xml app/src/androidTest/java/com/hpre/app/ui/search/SearchScreenTest.kt app/src/main/java/com/hpre/app/navigation/HPreNavHost.kt
git commit -m "feat: add search history controls"
```

### Task 3: Bounded Personalized Recommendation Core

**Files:**
- Modify: `app/src/main/java/com/hpre/app/repository/RecommendationRanker.kt`
- Modify: `app/src/main/java/com/hpre/app/repository/RecommendationRepository.kt`
- Modify: `app/src/test/java/com/hpre/app/repository/RecommendationRankerTest.kt`
- Modify: `app/src/test/java/com/hpre/app/repository/RecommendationRepositoryTest.kt`
- Modify: `app/src/main/java/com/hpre/app/di/AppContainer.kt:39-56,126-132`

**Interfaces:**
- Consumes: `PlaybackPreferences.isHistoryEnabled`, `VideoService.related(ContentKey)`, current `CatalogRepository`, search history, and watch history.
- Produces: `WatchRecommendationSource.recommendations(key: ContentKey, details: VideoDetails, forceRefresh: Boolean): AppResult<List<VideoSummary>>`; enhanced `RecommendationRanker.rank(candidates, signals, context, limit)`.

- [ ] **Step 1: Write failing ranking tests for Watch context and diversity**

Introduce these public repository-domain types in the expected test API:

```kotlin
data class RecommendationContext(
    val currentKey: ContentKey? = null,
    val currentChannelName: String? = null,
    val providerRelatedKeys: Set<ContentKey> = emptySet(),
    val nowEpochSeconds: Long? = null
)
```

Add tests that assert the current video is excluded, an otherwise equal provider-related candidate outranks a supplemental candidate, current-channel affinity applies, newer publication timestamps break otherwise equal scores when `nowEpochSeconds` is supplied, and the first six results do not contain more than two consecutive videos from one normalized channel when alternatives exist.

- [ ] **Step 2: Run ranker tests and verify failure**

Run: `./gradlew testDebugUnitTest --tests "com.hpre.app.repository.RecommendationRankerTest"`

Expected: FAIL because `RecommendationContext` and context-aware ranking do not exist.

- [ ] **Step 3: Implement deterministic context scoring and diversification**

Extend `rank` with a default context so existing callers remain simple:

```kotlin
fun rank(
    candidates: List<VideoSummary>,
    signals: LocalInterestSignals,
    context: RecommendationContext = RecommendationContext(),
    limit: Int = 30
): List<VideoSummary>
```

Exclude `context.currentKey` before watched filtering. Add integer score components with named constants: provider-related base priority, current-channel affinity, query score, watched-channel frequency, and a bounded freshness bucket. Preserve stable input index as the final tie breaker.

After sorting, perform a deterministic greedy pass: prefer the highest-scoring candidate that does not create a third consecutive normalized channel; if none qualifies, take the highest remaining candidate. Stop at `limit`.

- [ ] **Step 4: Write failing repository tests for disabled history and Watch merging**

Add `FakePlaybackPreferences` and tests asserting:

```kotlin
@Test fun disabled_history_home_uses_only_trending_without_reading_personal_topics()
@Test fun watch_merges_related_current_topic_familiar_channel_and_trending()
@Test fun watch_excludes_current_video_and_deduplicates_candidates()
@Test fun watch_partial_search_failure_keeps_related_results()
@Test fun watch_fan_out_never_exceeds_MAX_TOTAL_TOPICS()
@Test fun disabled_history_watch_uses_only_current_context_and_generic_fallback()
@Test fun watch_cancellation_is_propagated()
```

Use `FakeVideoService.searchHandler` to capture exact queries and `relatedHandler` to supply direct candidates. Assert retained historical queries and channels never appear in captured queries while disabled.

- [ ] **Step 5: Run repository tests and verify failure**

Run: `./gradlew testDebugUnitTest --tests "com.hpre.app.repository.RecommendationRepositoryTest"`

Expected: FAIL because history gating and Watch recommendations are absent.

- [ ] **Step 6: Implement shared Home/Watch recommendation orchestration**

Define:

```kotlin
fun interface WatchRecommendationSource {
    suspend fun recommendations(
        key: ContentKey,
        details: VideoDetails,
        forceRefresh: Boolean
    ): AppResult<List<VideoSummary>>
}
```

Make `RecommendationRepository` implement both `HomeRecommendationSource` and `WatchRecommendationSource`. Inject `VideoService` and `PlaybackPreferences` in addition to existing repositories.

Extract one private `interestSignals(enabled: Boolean)` snapshot function. In `home`, call `playbackPreferences.isHistoryEnabled.first()`; when false, return trending immediately without observing history repositories.

For Watch, launch provider `related(key)`, bounded topic searches, and trending under `supervisorScope`. Build current context from `details.title` and `details.channelName` in all modes. Add persisted search/title/channel topics only when history is enabled. Keep the combined topic list distinct and capped at `MAX_TOTAL_TOPICS`.

Merge candidates, call `RecommendationRanker.rank` with `RecommendationContext(currentKey, currentChannelName, relatedKeys)`, append any remaining trending candidates, deduplicate, and cap at `FEED_LIMIT`. Return useful partial results; return failure only when every applicable source failed and no candidates exist.

- [ ] **Step 7: Wire DI and run repository tests**

Update both `AppContainer.recommendationRepository` constructors:

```kotlin
RecommendationRepository(
    catalogRepository = catalogRepository,
    searchHistoryRepository = searchHistoryRepository,
    historyRepository = historyRepository,
    videoService = videoService,
    playbackPreferences = playbackPreferences
)
```

Run: `./gradlew testDebugUnitTest --tests "com.hpre.app.repository.RecommendationRankerTest" --tests "com.hpre.app.repository.RecommendationRepositoryTest" --tests "com.hpre.app.ui.home.HomeViewModelTest"`

Expected: PASS with no regression to Home behavior when history is enabled.

- [ ] **Step 8: Commit the recommendation core**

```bash
git add app/src/main/java/com/hpre/app/repository/RecommendationRanker.kt app/src/main/java/com/hpre/app/repository/RecommendationRepository.kt app/src/main/java/com/hpre/app/di/AppContainer.kt app/src/test/java/com/hpre/app/repository/RecommendationRankerTest.kt app/src/test/java/com/hpre/app/repository/RecommendationRepositoryTest.kt
git commit -m "feat: expand local recommendations"
```

### Task 4: Watch Integration and End-to-End Verification

**Files:**
- Modify: `app/src/main/java/com/hpre/app/ui/watch/WatchViewModel.kt:48-117,163-249,277-297`
- Modify: `app/src/main/java/com/hpre/app/navigation/HPreNavHost.kt:271-279`
- Modify: `app/src/test/java/com/hpre/app/ui/watch/WatchViewModelTest.kt:296-323,512-556`
- Modify: `app/src/androidTest/java/com/hpre/app/ui/watch/WatchScreenTest.kt`

**Interfaces:**
- Consumes: `WatchRecommendationSource.recommendations` from Task 3.
- Produces: unchanged `WatchViewModel.relatedState: StateFlow<AsyncState<List<VideoSummary>>>`, so `WatchScreen` needs no structural redesign.

- [ ] **Step 1: Write failing Watch ViewModel tests against the source boundary**

Add a fake source:

```kotlin
private class FakeWatchRecommendationSource(
    var result: AppResult<List<VideoSummary>>
) : WatchRecommendationSource {
    val calls = mutableListOf<Pair<ContentKey, VideoDetails>>()
    override suspend fun recommendations(
        key: ContentKey,
        details: VideoDetails,
        forceRefresh: Boolean
    ): AppResult<List<VideoSummary>> {
        calls += key to details
        return result
    }
}
```

Update tests to assert recommendations start after details are available, map success/empty/failure to the existing `AsyncState`, retry uses `forceRefresh = true`, and stale results from video A cannot overwrite video B.

- [ ] **Step 2: Run Watch ViewModel tests and verify failure**

Run: `./gradlew testDebugUnitTest --tests "com.hpre.app.ui.watch.WatchViewModelTest"`

Expected: FAIL because `WatchViewModel` still calls `videoService.related` directly.

- [ ] **Step 3: Inject and use `WatchRecommendationSource`**

Add the source to both `provideFactory` overloads and the constructor. Remove direct `loadRelated(key, generation)` immediately after stream preparation. After successful details resolution and state update, call:

```kotlin
loadRelated(key, detailsResult.value, generation, forceRefresh)
```

Implement `loadRelated` using `watchRecommendationSource.recommendations`. Preserve the current generation/key guards, cancellation behavior, and `AsyncState` mapping. Change `retryRelated()` to require current details and pass `forceRefresh = true`.

- [ ] **Step 4: Wire Watch navigation and update fakes**

Pass `container.recommendationRepository` as `watchRecommendationSource` in `HPreNavHost.kt`. Update Test application/container seams only where constructor compilation requires it; use the existing container recommendation repository rather than creating another instance.

- [ ] **Step 5: Add/adjust Compose assertion for expanded related content**

In `WatchScreenTest.kt`, supply a fake source returning two videos from different candidate origins and assert both cards appear under `related_videos_section`. Keep existing comments-before-related ordering and retry-state tests unchanged.

- [ ] **Step 6: Run focused and full deterministic tests**

Run:

```text
./gradlew testDebugUnitTest --tests "com.hpre.app.ui.watch.WatchViewModelTest"
./gradlew testDebugUnitTest
```

Expected: both commands PASS.

- [ ] **Step 7: Run Android and build validation**

Run: `./gradlew assembleDebug`

Expected: PASS and `app/build/outputs/apk/debug/app-debug.apk` exists.

When an emulator/device is available, run:

```text
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.hpre.app.ui.search.SearchScreenTest,com.hpre.app.ui.watch.WatchScreenTest
```

Expected: PASS. If no device is available, report instrumentation as not run rather than claiming success.

- [ ] **Step 8: Inspect final diff and commit the integration**

Run: `git diff --check` and `git status --short`.

Review that no stream URL, raw history database, or telemetry path was introduced, and that unrelated worktree changes remain untouched.

```bash
git add app/src/main/java/com/hpre/app/ui/watch/WatchViewModel.kt app/src/main/java/com/hpre/app/navigation/HPreNavHost.kt app/src/test/java/com/hpre/app/ui/watch/WatchViewModelTest.kt app/src/androidTest/java/com/hpre/app/ui/watch/WatchScreenTest.kt
git commit -m "feat: expand watch recommendations"
```
