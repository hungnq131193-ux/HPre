# HPre Playback Continuity and Seek Gesture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore reliable double-tap seek and make returning from the mini player continue the active video without stream extraction, re-prepare, or position loss.

**Architecture:** Keep the service-owned Media3 player and existing surface-lease protocol. Persist gesture-chain state across Compose recompositions, branch `WatchViewModel.load` before requesting stream data when the app-scoped player already owns the requested key, pass a bounded Room resume position into first prepare, and periodically persist current playback into history while the service is actively playing.

**Tech Stack:** Kotlin 2.1.20, Jetpack Compose, Navigation Compose 2.8.8, Media3 1.5.1, Room 2.6.1, kotlinx-coroutines 1.10.1, JUnit 4, Compose instrumentation tests.

**Spec:** `docs/superpowers/specs/2026-08-27-playback-continuity-and-seek-gesture-design.md`

## Global Constraints

- Preserve the service-owned `ExoPlayer`, app-scoped `SessionPlayerController`, and generation-based surface lease.
- Do not change Room schema or add dependencies in this phase.
- Do not add horizontal swipe-to-scrub, change the 10,000 ms seek step, change edge zones, or change the 3,500 ms control auto-hide delay.
- Do not change system PiP, fullscreen, quality selection, stream recovery, or background playback behavior.
- Back-stack production code changes are allowed only if a regression test first proves growth.
- Before Gradle commands, set `$env:JAVA_HOME = Join-Path $env:LOCALAPPDATA (("Flow" + "TubeToolchain") + "\temurin-17.0.14_7\jdk-17.0.14+7")` in this environment.
- Use AVD `("Flow" + "TubeApi35")` for instrumentation when no device is attached.

## File Map

- Modify `app/src/main/java/com/hpre/app/ui/watch/PlayerControls.kt`: retain the double-tap chain across recomposition and read changing control/duration values without restarting `pointerInput`.
- Modify `app/src/androidTest/java/com/hpre/app/ui/watch/WatchScreenTest.kt`: reproduce two physical taps separated by recomposition.
- Modify `app/src/main/java/com/hpre/app/ui/watch/WatchViewModel.kt`: skip stream extraction and prepare for the active key; calculate bounded Room resume position before first prepare.
- Modify `app/src/test/java/com/hpre/app/ui/watch/WatchViewModelTest.kt`: track prepare/seek calls and cover active-session, resume, timeout, completion, and recovery branches.
- Modify `app/src/main/java/com/hpre/app/player/PlaybackPolicy.kt`: own pure prepare-snapshot and periodic-history decisions.
- Modify `app/src/test/java/com/hpre/app/player/PlaybackPolicyTest.kt`: test the extracted decisions without Android dependencies.
- Modify `app/src/main/java/com/hpre/app/player/SessionPlayerController.kt`: prevent same-video `prepare(0)` from lowering a valid snapshot while still replacing snapshots for a different key.
- Create `app/src/main/java/com/hpre/app/player/PlaybackHistoryScheduler.kt`: own the single playing-only 10-second coroutine and cancellation semantics independently of Media3.
- Create `app/src/test/java/com/hpre/app/player/PlaybackHistorySchedulerTest.kt`: verify cadence, idempotent start, pause cancellation, and terminal stop with coroutine virtual time.
- Modify `app/src/main/java/com/hpre/app/player/HPrePlaybackService.kt`: adapt Media3 playing state to the scheduler and share history-record construction.
- Modify `app/src/androidTest/java/com/hpre/app/navigation/HomeToWatchNavigationTest.kt`: verify active playback reuse and measure Watch back-stack entries across repeated cycles.
- Modify `app/src/main/java/com/hpre/app/navigation/RootScaffold.kt` only if the navigation regression test fails by proving stale Watch entries accumulate.

---

### Task 1: Double-Tap Gesture Survives Recomposition

**Files:**
- Modify: `app/src/androidTest/java/com/hpre/app/ui/watch/WatchScreenTest.kt:1413-1448`
- Modify: `app/src/main/java/com/hpre/app/ui/watch/PlayerControls.kt:124-370`

**Interfaces:**
- Consumes: `PlayerGesturePolicy.gestureForTap`, `PlayerGesturePolicy.isSeekAllowed`, `PlayerGesturePolicy.SEEK_STEP_MS`.
- Produces: unchanged `PlayerControlsOverlay` public signature; persistent internal `lastUpUptime` and `lastUpPosition` state.

- [ ] **Step 1: Add a failing real-recomposition test**

Add a second test next to `taps_and_double_taps_still_work_with_single_pointer_coordinator`. It must issue two separate touch injections, allowing the first tap's `controlsVisible` mutation to recompose before the second tap:

```kotlin
@Test
fun double_tap_seek_survives_recomposition_between_physical_taps() {
    val fakeService = FakeVideoService(
        videoHandler = { AppResult.Success(testDetails(it)) },
        streamInfoHandler = {
            AppResult.Success(StreamInfo(it, "Title", hlsManifestUrl = "https://manifest.m3u8"))
        }
    )
    val fakePlayer = FakePlayerController().apply {
        _state.value = PlaybackState(
            key = testKey,
            durationMs = 120_000L,
            isReady = true,
            isPlaying = true
        )
    }
    val viewModel = WatchViewModel(
        videoService = fakeService,
        playerController = fakePlayer,
        savedStateHandle = SavedStateHandle()
    )

    composeTestRule.setContent {
        HPreTheme {
            WatchScreen(contentKey = testKey, viewModel = viewModel, onNavigateBack = {})
        }
    }
    composeTestRule.waitUntil(5_000) {
        composeTestRule.onAllNodes(hasTestTag("player_controls_overlay"))
            .fetchSemanticsNodes().isNotEmpty()
    }

    composeTestRule.onNodeWithTag("player_controls_overlay").performTouchInput {
        click(Offset(width * 0.85f, height * 0.5f))
    }
    composeTestRule.waitForIdle() // forces controlsVisible recomposition
    composeTestRule.onNodeWithTag("player_controls_overlay").performTouchInput {
        click(Offset(width * 0.85f, height * 0.5f))
    }
    composeTestRule.waitForIdle()

    assertEquals(10_000L, fakePlayer.seekDeltaCalled)
}
```

Use the imports already present for `Offset`, `click`, `PlaybackState`, and `SavedStateHandle`; add only missing imports.

- [ ] **Step 2: Run the test to verify it fails on current code**

Boot the existing AVD if needed:

```powershell
& "C:\Users\HUNG\AppData\Local\Android\Sdk\emulator\emulator.exe" -avd ("Flow" + "TubeApi35") -no-snapshot-save
& "C:\Users\HUNG\AppData\Local\Android\Sdk\platform-tools\adb.exe" wait-for-device
```

Run:

```powershell
$env:JAVA_HOME = Join-Path $env:LOCALAPPDATA (("Flow" + "TubeToolchain") + "\temurin-17.0.14_7\jdk-17.0.14+7")
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.hpre.app.ui.watch.WatchScreenTest#double_tap_seek_survives_recomposition_between_physical_taps --console=plain
```

Expected: FAIL because the first tap changes `controlsVisible`, restarts `pointerInput`, and resets the local chain before the second tap.

- [ ] **Step 3: Hoist gesture-chain state and remove volatile keys**

In `PlayerControlsOverlay`, add remembered state next to the other state declarations:

```kotlin
var lastUpUptime by remember { mutableLongStateOf(0L) }
var lastUpPosition by remember { mutableStateOf(Offset.Zero) }
val currentControlsVisible = rememberUpdatedState(controlsVisible)
val currentDurationMs = rememberUpdatedState(playbackState.durationMs)
```

Add imports for `androidx.compose.runtime.mutableLongStateOf` and `androidx.compose.ui.geometry.Offset`. Remove the plain `val currentDurationMs = playbackState.durationMs` and the two local declarations inside `pointerInput`.

Change the modifier key and dynamic reads:

```kotlin
.pointerInput(isMinimizeAllowed) {
    // existing coordinator body
}
```

Inside the coordinator:

- replace protected-region checks of `controlsVisible` with `currentControlsVisible.value`;
- replace `PlayerGesturePolicy.isSeekAllowed(currentDurationMs)` with `PlayerGesturePolicy.isSeekAllowed(currentDurationMs.value)`;
- retain every existing reset of `lastUpUptime` and `lastUpPosition` for protected controls, multi-touch, cancellation, completed drags, and consumed double taps;
- retain single-tap visibility behavior and all protected-region semantics unchanged.

- [ ] **Step 4: Run focused gesture tests**

Run the new instrumentation test and the pure gesture suite:

```powershell
$env:JAVA_HOME = Join-Path $env:LOCALAPPDATA (("Flow" + "TubeToolchain") + "\temurin-17.0.14_7\jdk-17.0.14+7")
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.hpre.app.ui.watch.WatchScreenTest#double_tap_seek_survives_recomposition_between_physical_taps --console=plain
.\gradlew.bat testDebugUnitTest --tests com.hpre.app.ui.watch.PlayerGesturePolicyTest --tests com.hpre.app.ui.watch.PlayerControlsPolicyTest --console=plain
```

Expected: PASS. Also run the existing `taps_and_double_taps_still_work_with_single_pointer_coordinator` test to preserve the lower-fidelity case.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/hpre/app/ui/watch/PlayerControls.kt app/src/androidTest/java/com/hpre/app/ui/watch/WatchScreenTest.kt
git commit -m "fix: preserve double tap seek across recomposition"
```

---

### Task 2: Reuse Active Playback and Resume New Sessions

**Files:**
- Modify: `app/src/test/java/com/hpre/app/ui/watch/WatchViewModelTest.kt:61-149,183-310`
- Modify: `app/src/main/java/com/hpre/app/ui/watch/WatchViewModel.kt:121-184,232-329`

**Interfaces:**
- Consumes: `PlayerController.state`, `HistoryRepository.getHistoryItem`, `HistoryRepository.shouldOfferResume`.
- Produces: private `loadResumePosition(key: ContentKey): Long`; `RESUME_LOOKUP_TIMEOUT_MS = 1_500L`.

- [ ] **Step 1: Extend the existing fake with observable calls**

In `WatchViewModelTest.FakePlayerController`, add:

```kotlin
var prepareCount = 0
val seekToPositions = mutableListOf<Long>()
```

Increment `prepareCount` at the start of `prepare`, and append in `seekTo`:

```kotlin
override fun seekTo(positionMs: Long) {
    seekToPositions += positionMs
    _state.value = _state.value.copy(currentPositionMs = positionMs)
}
```

- [ ] **Step 2: Add failing active-session tests**

Add tests that pre-seed `fakePlayer._state` before `load`:

```kotlin
@Test
fun load_active_key_skips_stream_extraction_prepare_and_seek() = runTest(testDispatcher) {
    val service = FakeVideoService(
        videoHandler = { AppResult.Success(testDetails(it)) },
        streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
    )
    val player = FakePlayerController().apply {
        _state.value = PlaybackState(
            key = testKey,
            isPlaying = true,
            isReady = true,
            currentPositionMs = 42_000L,
            durationMs = 120_000L
        )
    }
    val model = WatchViewModel(
        videoService = service,
        playerController = player,
        savedStateHandle = SavedStateHandle(),
        ioDispatcher = testDispatcher
    )

    model.load(testKey)
    advanceUntilIdle()

    assertEquals(0, service.streamInfoCallCount)
    assertEquals(0, player.prepareCount)
    assertTrue(player.seekToPositions.isEmpty())
    assertEquals(42_000L, player.state.value.currentPositionMs)
    assertEquals(testDetails(testKey), model.uiState.value.details)
}
```

Add a sibling test with the same key plus `error = AppError.StreamExpired`; assert one stream call and one prepare so recovery is not skipped.

- [ ] **Step 3: Replace the old post-prepare-resume test with failing prepare-position tests**

Rewrite `load_prepares_before_slow_history_then_applies_resume_seek` into these cases:

1. history returns a 50,000 ms resumable row: assert `startPositionMs == 50_000L`, `prepareCount == 1`, and `seekToPositions.isEmpty()`;
2. history position is 96,000 ms of a 100-second video: assert `startPositionMs == 0L`;
3. history returns failure or null: assert `startPositionMs == 0L`;
4. history suspends forever: advance virtual time by `RESUME_LOOKUP_TIMEOUT_MS`, assert prepare occurs at `0L` and does not remain blocked.

Use the existing anonymous `HistoryRepository` fixture, but remove the old assertions that require prepare at zero before history completes and a later seek.

- [ ] **Step 4: Run focused ViewModel tests and observe failure**

```powershell
$env:JAVA_HOME = Join-Path $env:LOCALAPPDATA (("Flow" + "TubeToolchain") + "\temurin-17.0.14_7\jdk-17.0.14+7")
.\gradlew.bat testDebugUnitTest --tests com.hpre.app.ui.watch.WatchViewModelTest --console=plain
```

Expected: new tests FAIL because current code always requests `streamInfo`, prepares at `0L`, then calls `seekTo` after history resolves.

- [ ] **Step 5: Implement bounded resume lookup and active-player branch**

Add to the companion object:

```kotlin
internal const val RESUME_LOOKUP_TIMEOUT_MS = 1_500L
```

Add a private suspend helper:

```kotlin
private suspend fun loadResumePosition(key: ContentKey): Long {
    val repository = historyRepository ?: return 0L
    return withTimeoutOrNull(RESUME_LOOKUP_TIMEOUT_MS) {
        val item = (repository.getHistoryItem(key) as? AppResult.Success)?.value
        item?.takeIf {
            HistoryRepository.shouldOfferResume(it.playbackPositionMs, it.durationSeconds)
        }?.playbackPositionMs ?: 0L
    } ?: 0L
}
```

Import `HistoryRepository` and `withTimeoutOrNull` instead of fully qualifying the policy repeatedly.

Restructure the beginning of `loadJob` so details always load, but stream and resume work only run for a new/recovery session:

```kotlin
val detailsDeferred = async {
    catalogRepository?.video(key, forceRefresh = forceRefresh) ?: videoService.video(key)
}
val active = playbackState.value
val reuseActivePlayer = active.key == key && active.error == null

if (!reuseActivePlayer) {
    val resumeDeferred = async { loadResumePosition(key) }
    val streamResult = videoService.streamInfo(key)
    if (generation != currentGeneration || currentKey != key) {
        resumeDeferred.cancel()
        return@launch
    }
    if (streamResult is AppResult.Failure) {
        resumeDeferred.cancel()
        detailsDeferred.cancel()
        _uiState.update { it.copy(isLoading = false, error = streamResult.error) }
        return@launch
    }
    val resumePositionMs = resumeDeferred.await()
    val prepared = synchronized(sessionGuard) {
        if (generation != currentGeneration || currentKey != key) false else {
            playerController.prepare(
                key,
                (streamResult as AppResult.Success).value,
                startPositionMs = resumePositionMs
            )
            true
        }
    }
    if (!prepared) return@launch
}
```

Keep comments and related loading in both branches. Remove the entire post-prepare `launch { historyRepository?.getHistoryItem ... playerController.seekTo(...) }` block. Keep the generation guards and details completion handling unchanged.

Cancel `resumeDeferred` before every early return that occurs before `await()`. Because it is a child of `loadJob`, leaving it active would keep structured concurrency waiting until the 1,500 ms timeout even after a stale generation or stream failure.

- [ ] **Step 6: Run the full WatchViewModel test class**

Run the command from Step 4.

Expected: PASS, including existing tests proving stream prepare can precede slow details and that details failure does not tear down prepared playback.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/hpre/app/ui/watch/WatchViewModel.kt app/src/test/java/com/hpre/app/ui/watch/WatchViewModelTest.kt
git commit -m "fix: reuse active video playback on watch return"
```

---

### Task 3: Preserve Same-Video Snapshot During Prepare

**Files:**
- Modify: `app/src/test/java/com/hpre/app/player/PlaybackPolicyTest.kt`
- Modify: `app/src/main/java/com/hpre/app/player/PlaybackPolicy.kt`
- Modify: `app/src/main/java/com/hpre/app/player/SessionPlayerController.kt:500-560`

**Interfaces:**
- Produces: `PlaybackPolicy.prepareSnapshotPosition(existing: PlaybackSnapshot?, key: ContentKey, requestedPositionMs: Long): Long`.
- Consumes: existing synchronous `PlaybackSnapshotStore.load`, `enqueueSave`, and `executeSave`.

- [ ] **Step 1: Add failing pure policy tests**

Add to `PlaybackPolicyTest`:

```kotlin
@Test
fun prepare_snapshot_preserves_positive_position_only_for_same_video() {
    val key = ContentKey(0, "same")
    val existing = PlaybackSnapshot(key, 42_000L, playWhenReady = true)

    assertEquals(42_000L, PlaybackPolicy.prepareSnapshotPosition(existing, key, 0L))
    assertEquals(12_000L, PlaybackPolicy.prepareSnapshotPosition(existing, key, 12_000L))
    assertEquals(
        0L,
        PlaybackPolicy.prepareSnapshotPosition(existing, ContentKey(0, "different"), 0L)
    )
    assertEquals(0L, PlaybackPolicy.prepareSnapshotPosition(null, key, 0L))
}
```

This explicitly avoids retaining another video's snapshot.

- [ ] **Step 2: Verify the policy test fails**

```powershell
$env:JAVA_HOME = Join-Path $env:LOCALAPPDATA (("Flow" + "TubeToolchain") + "\temurin-17.0.14_7\jdk-17.0.14+7")
.\gradlew.bat testDebugUnitTest --tests com.hpre.app.player.PlaybackPolicyTest --console=plain
```

Expected: FAIL because `prepareSnapshotPosition` does not exist.

- [ ] **Step 3: Implement the pure policy**

Add to `PlaybackPolicy`:

```kotlin
fun prepareSnapshotPosition(
    existing: PlaybackSnapshot?,
    key: ContentKey,
    requestedPositionMs: Long
): Long {
    val requested = requestedPositionMs.coerceAtLeast(0L)
    return if (requested == 0L && existing?.key == key && existing.positionMs > 0L) {
        existing.positionMs
    } else {
        requested
    }
}
```

Import `ContentKey`.

- [ ] **Step 4: Use the policy in `prepareWithSpeed`**

Immediately before constructing `PlaybackSnapshot`, load the existing snapshot and calculate the persisted position:

```kotlin
val snapshotPosition = PlaybackPolicy.prepareSnapshotPosition(
    existing = snapshotStore.load(),
    key = key,
    requestedPositionMs = startPositionMs
)
val snap = PlaybackSnapshot(
    key = key,
    positionMs = snapshotPosition,
    playWhenReady = playWhenReady,
    selectedQuality = initialQuality?.takeIf { option -> available.contains(option) },
    playbackSpeed = clampedSpeed,
    qualityPolicy = initialQuality?.let(UserQualityPolicy::Fixed) ?: existingPolicy
)
```

Do not change the live `_state.currentPositionMs`, `PendingPrepare.positionMs`, or service command position: they still use the requested position. This policy protects persistence only; it must not cause an explicit restart to jump to a stale live position.

- [ ] **Step 5: Run player policy and snapshot suites**

```powershell
$env:JAVA_HOME = Join-Path $env:LOCALAPPDATA (("Flow" + "TubeToolchain") + "\temurin-17.0.14_7\jdk-17.0.14+7")
.\gradlew.bat testDebugUnitTest --tests com.hpre.app.player.PlaybackPolicyTest --tests com.hpre.app.player.PlaybackSnapshotStoreTest --tests com.hpre.app.player.SnapshotWriterTest --console=plain
```

Expected: PASS. `SnapshotWriterTest` must still prove generation invalidation and no resurrection after clear.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/hpre/app/player/PlaybackPolicy.kt app/src/main/java/com/hpre/app/player/SessionPlayerController.kt app/src/test/java/com/hpre/app/player/PlaybackPolicyTest.kt
git commit -m "fix: preserve same video playback snapshot"
```

---

### Task 4: Persist History While Playback Advances

**Files:**
- Create: `app/src/main/java/com/hpre/app/player/PlaybackHistoryScheduler.kt`
- Create: `app/src/test/java/com/hpre/app/player/PlaybackHistorySchedulerTest.kt`
- Modify: `app/src/main/java/com/hpre/app/player/HPrePlaybackService.kt:93-114,401-411,459-479,729-774,1050-1086`

**Interfaces:**
- Produces: `internal class PlaybackHistoryScheduler(scope: CoroutineScope, intervalMs: Long = 10_000L, onWrite: () -> Unit)` with `update(isPlaying: Boolean)` and `stop()`.
- Produces internally: one scheduler instance and `recordCurrentHistory` in the service.
- Consumes: `DefaultHistoryRepository.recordHistory`, which already checks `PlaybackPreferences.isHistoryEnabled` before writing.

- [ ] **Step 1: Write failing virtual-time scheduler tests**

Create `PlaybackHistorySchedulerTest.kt`:

```kotlin
package com.hpre.app.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackHistorySchedulerTest {
    @Test
    fun playing_ticks_once_per_interval_without_duplicate_jobs() = runTest {
        var writes = 0
        val scheduler = PlaybackHistoryScheduler(
            scope = backgroundScope,
            intervalMs = 10_000L,
            onWrite = { writes++ }
        )

        scheduler.update(isPlaying = true)
        scheduler.update(isPlaying = true)
        advanceTimeBy(9_999L)
        runCurrent()
        assertEquals(0, writes)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, writes)
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(2, writes)
    }

    @Test
    fun pause_and_stop_cancel_future_writes() = runTest {
        var writes = 0
        val scheduler = PlaybackHistoryScheduler(backgroundScope, 10_000L) { writes++ }

        scheduler.update(true)
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(1, writes)

        scheduler.update(false)
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(1, writes)

        scheduler.update(true)
        scheduler.stop()
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(1, writes)
    }
}
```

- [ ] **Step 2: Verify the new tests fail**

```powershell
$env:JAVA_HOME = Join-Path $env:LOCALAPPDATA (("Flow" + "TubeToolchain") + "\temurin-17.0.14_7\jdk-17.0.14+7")
.\gradlew.bat testDebugUnitTest --tests com.hpre.app.player.PlaybackHistorySchedulerTest --console=plain
```

Expected: test compilation FAILS because `PlaybackHistoryScheduler` does not exist.

- [ ] **Step 3: Implement the scheduler minimally**

Create `PlaybackHistoryScheduler.kt`:

```kotlin
package com.hpre.app.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class PlaybackHistoryScheduler(
    private val scope: CoroutineScope,
    private val intervalMs: Long = HISTORY_WRITE_INTERVAL_MS,
    private val onWrite: () -> Unit
) {
    private var job: Job? = null

    fun update(isPlaying: Boolean) {
        if (!isPlaying) {
            cancelJob()
            return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                onWrite()
            }
        }
    }

    fun stop() = cancelJob()

    private fun cancelJob() {
        job?.cancel()
        job = null
    }

    companion object {
        const val HISTORY_WRITE_INTERVAL_MS = 10_000L
    }
}
```

- [ ] **Step 4: Run scheduler tests green**

Run the command from Step 2.

Expected: PASS. This proves exact cadence, repeated `update(true)` idempotence, pause cancellation, resume with a fresh interval, and terminal stop without a device or wall-clock sleeps.

- [ ] **Step 5: Extract one history-recording function in the service**

Move the summary construction currently in `clearMediaInternal` into:

```kotlin
private fun recordCurrentHistory() {
    val key = currentKey ?: return
    val player = exoPlayer ?: return
    val repository = (application as? HPreApplication)?.container?.historyRepository ?: return
    val streamInfo = currentStreamInfo
    val positionMs = player.currentPosition.coerceAtLeast(0L)
    val summary = VideoSummary(
        key = key,
        title = streamInfo?.title ?: "Video",
        canonicalUrl = "https://hpre.test/watch?v=${key.nativeId}",
        channelKey = null,
        channelName = null,
        channelAvatarUrl = null,
        thumbnailUrl = null,
        durationSeconds = player.duration.takeIf { it > 0L }?.div(1_000L),
        viewCount = null,
        publishedTimestamp = null
    )
    serviceScope.launch(Dispatchers.IO) {
        repository.recordHistory(summary, positionMs)
    }
}
```

Replace the duplicated block in `clearMediaInternal` with `recordCurrentHistory()` before clearing key/player state. Preserve the existing terminal write.

- [ ] **Step 6: Adapt Media3 state to the scheduler**

Add a lazy scheduler field after `serviceScope`:

```kotlin
private val historyScheduler by lazy {
    PlaybackHistoryScheduler(serviceScope) {
        if (exoPlayer?.isPlaying == true) {
            recordCurrentHistory()
        }
    }
}
```

Call `historyScheduler.update(isPlaying)` from `onIsPlayingChanged` after `persistCurrentSnapshot()`. Call `historyScheduler.stop()` in `clearMediaInternal` before state is cleared and in `onDestroy` before `serviceScope.cancel()`. Rely on `DefaultHistoryRepository.recordHistory` for the existing history-enabled privacy gate; do not read settings in the service.

- [ ] **Step 7: Run focused and regression tests**

```powershell
$env:JAVA_HOME = Join-Path $env:LOCALAPPDATA (("Flow" + "TubeToolchain") + "\temurin-17.0.14_7\jdk-17.0.14+7")
.\gradlew.bat testDebugUnitTest --tests com.hpre.app.player.PlaybackHistorySchedulerTest --tests com.hpre.app.repository.HistoryRepositoryTest --tests com.hpre.app.repository.HistoryRepositoryPolicyTest --console=plain
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.hpre.app.player.PlaybackServiceTest --console=plain
```

Expected: PASS. Existing service clear/reconnect/lifecycle behavior remains green; scheduler unit tests prove periodic cadence and cancellation; repository tests prove disabling history rejects writes.

- [ ] **Step 8: Commit**

```powershell
git add app/src/main/java/com/hpre/app/player/PlaybackHistoryScheduler.kt app/src/main/java/com/hpre/app/player/HPrePlaybackService.kt app/src/test/java/com/hpre/app/player/PlaybackHistorySchedulerTest.kt
git commit -m "fix: persist playback progress to history"
```

---

### Task 5: End-to-End Continuity and Back-Stack Measurement

**Files:**
- Modify: `app/src/androidTest/java/com/hpre/app/navigation/HomeToWatchNavigationTest.kt:143-195,300-451`
- Conditionally modify: `app/src/main/java/com/hpre/app/navigation/RootScaffold.kt:187-209`

**Interfaces:**
- Consumes: `TestContainer.countingPlayer.prepareCount`, `FakeVideoService.streamInfoCallCount`, `NavHostController.currentBackStack`.
- Produces: regression coverage for Watch → Home → mini player → Watch and repeated cycles.

- [ ] **Step 1: Add the complete continuity test**

Extend `CountingPlayerController` with a helper that advances position without prepare:

```kotlin
fun advancePositionForTest(positionMs: Long) {
    _state.value = _state.value.copy(currentPositionMs = positionMs)
}
```

Add an instrumented test using the existing `TestContainer`, `RootScaffold`, and coordinator:

1. click `video_card_vid_1` and wait for `watch_screen`;
2. capture `prepareCount` and `streamInfoCallCount`, then set position to `42_000L`;
3. trigger system back with `hostNavController?.popBackStack()` and verify `mini-player` is displayed;
4. click `mini-player` and wait for `watch_screen`;
5. assert prepare count and stream call count did not increase, key is unchanged, and position remains `42_000L`.

This test must fail before Task 2 and pass after it.

- [ ] **Step 2: Add a back-stack measurement test**

Repeat the back/mini-player-expand sequence three times. After each expansion, count:

```kotlin
val watchCount = hostNavController?.currentBackStack?.value.orEmpty()
    .count { it.destination.route == Screen.Watch.route }
assertEquals(1, watchCount)
```

After each back, assert `watchCount == 0` and exactly one Home entry. This measures the suspected problem rather than assuming it.

- [ ] **Step 3: Run the two navigation tests**

```powershell
$env:JAVA_HOME = Join-Path $env:LOCALAPPDATA (("Flow" + "TubeToolchain") + "\temurin-17.0.14_7\jdk-17.0.14+7")
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.hpre.app.navigation.HomeToWatchNavigationTest --console=plain
```

Expected after Tasks 1-4: continuity test PASS. If back-stack measurement also PASSes, do not edit `RootScaffold`.

- [ ] **Step 4: Only if measurement fails, minimally fix expansion navigation**

If stale Watch entries are proven, change only `onExpandWatch`:

```kotlin
onExpandWatch = { key ->
    navController.navigate(Screen.Watch.createRoute(key)) {
        launchSingleTop = true
    }
}
```

Re-run the measurement. Add `popUpTo` only if `launchSingleTop` is insufficient and the test proves which stale entry remains. Do not speculate with broad graph-clearing navigation.

- [ ] **Step 5: Commit regression coverage and any proven fix**

If no production change was needed:

```powershell
git add app/src/androidTest/java/com/hpre/app/navigation/HomeToWatchNavigationTest.kt
git commit -m "test: cover watch playback continuity"
```

If `RootScaffold` changed, stage it explicitly in the same commit.

---

### Task 6: Full Verification

**Files:**
- Verify only; no production edits unless a failing test exposes a regression in this phase.

**Interfaces:**
- Consumes all deliverables from Tasks 1-5.
- Produces a verification report with exact commands and outcomes.

- [ ] **Step 1: Run the complete unit suite**

```powershell
$env:JAVA_HOME = Join-Path $env:LOCALAPPDATA (("Flow" + "TubeToolchain") + "\temurin-17.0.14_7\jdk-17.0.14+7")
.\gradlew.bat testDebugUnitTest --console=plain
```

Expected: BUILD SUCCESSFUL. Baseline before implementation was BUILD SUCCESSFUL on 2026-08-27.

- [ ] **Step 2: Run compile, lint, and release shrink checks**

```powershell
$env:JAVA_HOME = Join-Path $env:LOCALAPPDATA (("Flow" + "TubeToolchain") + "\temurin-17.0.14_7\jdk-17.0.14+7")
.\gradlew.bat assembleDebug lintDebug assembleRelease --console=plain
```

Expected: BUILD SUCCESSFUL. Confirm R8 still retains `MainActivity`, `HPreApplication`, and `HPrePlaybackService` in the release manifest.

- [ ] **Step 3: Run targeted instrumentation suites**

```powershell
$env:JAVA_HOME = Join-Path $env:LOCALAPPDATA (("Flow" + "TubeToolchain") + "\temurin-17.0.14_7\jdk-17.0.14+7")
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.hpre.app.ui.watch.WatchScreenTest,com.hpre.app.navigation.HomeToWatchNavigationTest,com.hpre.app.player.PlaybackServiceTest --console=plain
```

Expected: PASS. This covers double-tap, seek slider, minimize gesture, continuity, back-stack behavior, and service history recording.

- [ ] **Step 4: Run PiP and recreation regressions**

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.hpre.app.PipEligibilityTest,com.hpre.app.ui.watch.WatchRecreationTest --console=plain
```

Expected: PASS; no competing surface owner and no playback teardown on recreation.

- [ ] **Step 5: Inspect final diff and status**

```powershell
git status --short
git diff --stat HEAD~5..HEAD
git diff HEAD~5..HEAD -- app/src/main app/src/test app/src/androidTest
```

Confirm only Phase 1 files changed, no dependency or Room schema changes exist, and every commit contains its corresponding test.

- [ ] **Step 6: Report evidence**

Report:

- exact unit/instrumentation/build commands and pass/fail status;
- whether `RootScaffold` required a back-stack fix or the original code was already bounded;
- release APK size only as informational evidence, not as a functional guarantee;
- any device-only behavior that still needs manual confirmation (visual gap/black frame, real double-tap feel).
