# HPre Playback Continuity and Seek Gesture Design

**Date:** 2026-08-27
**Status:** Draft, awaiting user review
**Phase:** 1 of 4 (bug fixes before optimization)

## 1. Goals

Fix two confirmed defects in the Watch playback experience without restructuring the existing
Media3, Compose, or navigation architecture.

The change must:

- make double-tap-to-seek on the left/right edge of the video surface dispatch a 10 s seek on a
  real device, not only inside a single synthetic touch-injection scope;
- continue the in-progress video when the user returns to Watch from the mini player, with no
  re-extraction, no re-prepare, and no restart from position zero;
- resume from the persisted position when the same video is opened in a genuinely new playback
  session (cold start, history, deep link);
- stop `prepare` from overwriting a good persisted position with zero;
- record watch history while playback advances, so resume survives process death;
- verify whether the Watch back stack grows when the mini player is expanded repeatedly, and fix it
  only if a test proves it does;
- prove each fix with a test that fails before the fix.

## 2. Non-goals

This phase deliberately excludes the other three reported problems, each of which gets its own
spec:

- reducing loading time and duplicate extractor fetches (Phase 2);
- replacing the mini player with a freely draggable floating window (Phase 3);
- personalizing recommendations, including any Room schema change (Phase 4).

Also out of scope:

- adding horizontal swipe-to-scrub on the video surface (`PlayerDragDecision.HORIZONTAL` stays a
  no-op in this phase);
- changing the auto-hide delay, the seek step, or the edge-zone fraction;
- changing system PiP behaviour, fullscreen behaviour, or the surface-lease protocol;
- increasing the 500 ms progress tick or interpolating position for smoother thumb motion.

## 3. Existing System

Single-module Android app: Kotlin, Compose, Media3, `MediaSessionService`, NewPipeExtractor.

The parts relevant here are already sound and must be preserved:

- `ExoPlayer` lives in `HPrePlaybackService`; `SessionPlayerController` is an app-scoped
  `MediaController` client. Leaving Watch does not stop or release playback.
- `PlaybackUiCoordinator` owns a monotonic surface lease so Watch, mini player, and PiP cannot
  fight over the surface.
- `MiniPlayer` already renders a real `PlayerSurface`, not a thumbnail.
- `PlaybackSnapshotStore` persists position to DataStore with a monotonic `snapshotVersion`.
- `PlayerGesturePolicy` is pure and correct: `gestureForTap`, `isSeekAllowed`, `classifyDrag`,
  and `shouldMinimize` all behave as their unit tests assert.

Both defects are in the layers above these primitives.

## 4. Defect 1: Double-tap seek never fires on device

### 4.1 Root cause

`ui/watch/PlayerControls.kt:210` keys the gesture coordinator on `controlsVisible`:

```kotlin
.pointerInput(isMinimizeAllowed, currentDurationMs, controlsVisible) {
    var lastUpUptime = 0L                    // :220
    var lastUpPosition = Offset.Zero         // :221
    awaitEachGesture { ... }
}
```

The double-tap chain lives in `lastUpUptime` and `lastUpPosition`, declared **inside** the
`pointerInput` lambda. When any key changes, Compose cancels the coroutine and re-runs the block,
so both variables reset to their initial values.

Every single tap changes `controlsVisible`:

1. Tap 1 records `lastUpUptime` at `:357`, then sets `controlsVisible = false` at `:360`. The key
   changes, the block restarts, `lastUpUptime` becomes `0L`.
2. Tap 2 computes `timeSinceLastUp = downUptime - 0L` at `:244`, an arbitrarily large value that
   cannot fall inside `doubleTapMinTimeMs..doubleTapTimeoutMs` at `:245`. `isDoubleTap` stays
   `false`, so the gesture takes the single-tap branch at `:362` and sets
   `controlsVisible = true`, restarting the block again.

The chain can never accumulate two taps. `isDoubleTap` is always `false` in real use, which makes
the seek branch at `:340-354` unreachable on device.

`currentDurationMs` is a second, independent restart source: it changes as the stream loads and
can therefore cancel the coordinator mid-gesture.

### 4.2 Why the existing test passes

`androidTest/.../WatchScreenTest.kt:1443` uses `performTouchInput { doubleClick(...) }`. Both taps
are injected inside one `performTouchInput` scope, and `waitForIdle()` is only called afterwards at
`:1445`. No recomposition occurs between the two taps, so `pointerInput` is never restarted and the
chain survives. The test is green while the feature is broken. This is a test-fidelity gap, not a
policy error.

### 4.3 Design

Hoist the double-tap chain so it outlives recomposition, and remove the keys that restart the
coordinator:

- Store `lastUpUptime` and `lastUpPosition` in `remember` outside `pointerInput`.
- Read `controlsVisible` and `playbackState.durationMs` through `rememberUpdatedState` inside the
  gesture block, so the coordinator observes current values without being keyed on them.
- Keep `isMinimizeAllowed` as the only `pointerInput` key. It derives from orientation, fullscreen,
  and PiP state, which change rarely and legitimately invalidate an in-flight gesture.

No change to `PlayerGesturePolicy`. No change to seek step, edge zones, or the badge.

The hoisted chain must still reset where the current code resets it: protected-region starts,
multi-touch, pointer loss, child consumption, completed drags, and a consumed double tap. Those
resets are correctness-relevant, not incidental.

### 4.4 Risk

Hoisted state persists across recompositions that previously cleared it, so a stale
`lastUpPosition` could in principle pair with a much later tap. The existing
`doubleTapTimeoutMillis` bound at `:245` already rejects this: a tap arriving after the timeout can
never be classified as a double tap regardless of how long the state has been retained.

## 5. Defect 2: Returning to Watch restarts the video

### 5.1 Root cause

Three faults compound.

**Navigation creates a fresh ViewModel.** `navigation/RootScaffold.kt:199-201` expands the mini
player with a bare `navigate()`, without `launchSingleTop` or `restoreState`. A new
`NavBackStackEntry` is pushed even for the same route, so `HPreNavHost.kt:279` builds a new
`WatchViewModel` whose `currentKey` is `null` and whose `details` is `null`.

**The reload guard cannot match.** `ui/watch/WatchViewModel.kt:233` returns early only when
`currentKey == key && details != null`. On a fresh ViewModel both are unset, so the guard never
fires and a full load proceeds.

**Prepare hard-codes position zero.** `ui/watch/WatchViewModel.kt:276-280`:

```kotlin
playerController.prepare(
    key,
    (streamResult as AppResult.Success).value,
    startPositionMs = 0L
)
```

`0L` is a literal. It reads neither `playbackState.value.currentPositionMs` nor the snapshot store.
`HPrePlaybackService` then calls `setMediaSource` and `prepare()` and skips its own
`if (startPositionMs > 0L) seekTo(...)`, so the video rebuffers from second zero.

### 5.2 Collateral damage

`player/SessionPlayerController.kt:544-553` writes a snapshot using the incoming
`startPositionMs`:

```kotlin
val snap = PlaybackSnapshot(key = key, positionMs = startPositionMs.coerceAtLeast(0L), ...)
snapshotStore.executeSave(snap, token)
```

With `startPositionMs = 0L` this overwrites the good persisted position with zero, destroying the
restore path that `HPrePlaybackService.kt:213` depends on after process death.

The history fallback cannot compensate. `HistoryRepository.recordHistory` has exactly one
production call site, `HPrePlaybackService.kt:751` inside `clearMediaInternal`, so history is
written only when the user dismisses the mini player or when playback is cleared on stop. Pressing
back to Home writes nothing, so the resume lookup at `WatchViewModel.kt:291-306` finds no row.

Even when a row does exist, that lookup seeks **after** `prepare(0L)` has already run, producing a
visible rebuffer from zero followed by a jump.

### 5.3 Design

The central decision: **when the player already holds the requested video, do not prepare at
all.**

This exploits the existing architecture rather than working around it. The service-owned player
keeps playing while the user is on Home, so on return the correct frame is already on screen at the
correct position. Re-preparing is not merely wasteful, it is what breaks continuity.

The check is evaluated **before** the stream request is launched, not after `streamDeferred.await()`.
This placement is the point of the fix: `videoService.streamInfo(key)` is the single most expensive
call in the load path, so skipping it is what makes the return instant.

```kotlin
// WatchViewModel.load(), inside loadJob
val active = playerController.state.value
val playerAlreadyHoldsVideo = active.key == key && active.error == null

// Metadata is always refreshed; it is cheap and cache-backed.
val detailsDeferred = async { catalogRepository?.video(key, forceRefresh) ?: videoService.video(key) }

if (playerAlreadyHoldsVideo) {
    // Never touch the player and never request the stream.
    // PlayerSurface re-attaches itself through the existing surface lease.
} else {
    val streamResult = videoService.streamInfo(key)      // only reached for a new session
    // ... existing failure handling and generation guard ...
    val resumeAt = persistedResumePosition(key)          // 0L when no valid resume point
    playerController.prepare(key, streamInfo, startPositionMs = resumeAt)
}

// Related and comments proceed identically in both branches.
```

The generation guard and `sessionGuard` synchronisation around `prepare` at
`WatchViewModel.kt:264-284` stay exactly as they are; only the branch and the position argument
change.

Consequences:

- Returning from the mini player produces zero stream extraction, zero rebuffer, and no position
  loss, because neither the player nor the extractor is asked for anything.
- Genuinely new sessions resume at the persisted position, passed into `prepare` so ExoPlayer
  buffers the right region from the start.
- The post-prepare seek block at `WatchViewModel.kt:290-306` is removed. Its job moves into the
  `startPositionMs` argument, which eliminates the rebuffer-then-jump artifact.

`persistedResumePosition` runs only in the `else` branch, where the player does not hold the key, so
the live player position is irrelevant to it. It reads **Room history only**, through the
`historyRepository` the ViewModel already has at `WatchViewModel.kt:114`, and applies the existing
`HistoryRepository.shouldOfferResume` rule so a finished video starts over rather than resuming at
its final second. When there is no valid resume point it returns `0L`.

Room history is deliberately the only source here. `PlaybackSnapshotStore` exists to restore a
session when the service is recreated after process death, which already works at
`HPrePlaybackService.kt:213`; injecting it into the ViewModel would add a dependency that has to be
threaded through `AppContainer` and `HPreNavHost` for information Room already holds. Making history
accurate is the job of the periodic-write fix in §5.4, and the snapshot fix protects the
process-death path on its own. No new ViewModel constructor parameter is introduced.

**The lookup must not gate playback.** Moving the history read ahead of `prepare` introduces a risk
the old post-prepare seek did not have: a slow or hanging Room read would delay playback. The lookup
is therefore bounded by a short timeout, and any timeout, failure, or missing row yields `0L` and
prepares immediately. Correct-but-late resume is worse than starting at zero, so the timeout favours
starting playback. The existing behaviour of preparing before video details resolve
(`WatchViewModel.kt:257-262`) is preserved: details are still awaited only after prepare.

Note that `shouldOfferResume` interacts with a Phase 4 concern: `DefaultHistoryRepository.kt:58-62`
stores `playbackPositionMs = 0` once completion exceeds 95 %. For resume that behaviour is correct
and stays unchanged here. Phase 4 will add a separate completion signal rather than reinterpreting
this field.

### 5.4 Supporting fixes

**Snapshot preservation.** `SessionPlayerController.prepareWithSpeed` must not lower a persisted
position to zero. It writes the snapshot only when `startPositionMs > 0`, leaving any existing
persisted position untouched otherwise. The `snapshotVersion` token is still taken so ordering
guarantees are unaffected.

**Back stack: verify before changing.** `RootScaffold.kt:199-201` expands the mini player with a bare
`navigate()`, which suggests repeated expansion could pile up Watch entries. Tracing the exit paths
casts doubt on that: system back pops the Watch entry, and minimize calls
`navigateToHomeFromWatch()`, which pops up to Home with `saveState = true`
(`HPreNavHost.kt:318-326`). Both remove the Watch entry, so an expand-then-leave cycle should net
zero growth.

This is therefore a verification task, not a fix task. A test asserts the entry count after three
expand/leave cycles. If the stack is already bounded, no production change is made and the finding is
recorded; adding navigation options for a problem that does not exist would be unjustified. If the
test shows real growth, `launchSingleTop` plus a `popUpTo` of the stale Watch entry is the fix, and
reaching Watch from Home, Search, Channel, Library, Subscriptions, and a related video must all still
behave correctly afterwards.

**Periodic history writes.** History is recorded while playback advances, not only on clear. The
service already persists snapshots on state transitions at `HPrePlaybackService.kt:402-410`, so
history writes attach to the same lifecycle on a coarse interval (target 10 s of advancing
playback) plus the existing terminal write. This makes resume work across app restarts and supplies
the engagement data Phase 4 needs.

The write must be cheap and must not fire while paused, seeking, or buffering, so it cannot turn
into a Room write storm.

## 6. Expected Code Areas

Production:

- `ui/watch/PlayerControls.kt` — hoist double-tap chain, reduce `pointerInput` keys
- `ui/watch/WatchViewModel.kt` — reuse-active-player branch, resume position, remove post-prepare seek
- `player/SessionPlayerController.kt` — conditional snapshot write in `prepareWithSpeed`
- `player/HPrePlaybackService.kt` — periodic history recording
- `navigation/RootScaffold.kt` — mini-player expansion navigation options, only if the back-stack
  test proves growth

Tests:

- `test/.../ui/watch/WatchViewModelTest.kt`
- `test/.../player/SnapshotWriterTest.kt` or `SessionPlayerProtocolTest.kt`
- `androidTest/.../ui/watch/WatchScreenTest.kt`
- `androidTest/.../navigation/HomeToWatchNavigationTest.kt`

The implementation plan must confirm these files and avoid unrelated refactoring. No Room schema
change, no dependency change.

## 7. Testing Strategy

Every fix needs a test that fails first. For Defect 1 this is essential, because the existing test
passes against broken behaviour.

### 7.1 Double-tap seek

A new instrumented test issues two **separate** taps with `waitForIdle()` between them, forcing the
recomposition that the current `doubleClick()` test avoids. Asserting a 10 s forward seek on the
right edge, this test fails on today's code and passes after the hoist.

Also covered: left edge rewinds; the centre band still toggles controls instead of seeking; a tap
landing on a protected control does not seek; and `isSeekAllowed` still blocks seeking when
`durationMs` is zero.

### 7.2 Playback continuity

Unit tests use the existing `FakePlayerController` nested in `WatchViewModelTest.kt:61`. Its
`_state` is already publicly settable, so a test can pre-seed the player as holding a given key.
Two small additions are needed: a prepare counter (today only the last `preparedKey` is retained, so
"prepared once" and "prepared three times" are indistinguishable) and recording of `seekTo`
positions (today `seekTo` only mutates state at `:123-125`). Both are test-only changes.

Cases:

- player already holds the key → prepare count stays at zero and no `seekTo` is issued;
- player holds a different key → exactly one prepare, carrying the resume position;
- player holds the key but reports an error → prepare runs, so recovery still works;
- no resume point → prepare receives `0L`;
- completion above the resume threshold → prepare receives `0L`, not the final position.

Snapshot test: `prepare` with `startPositionMs = 0` leaves an existing persisted position intact;
`prepare` with a positive position writes it.

Instrumented: Watch → back to Home → expand mini player → Watch, asserting one prepare for the whole
journey and monotonically advancing position. A separate test measures Watch back-stack entries after
three expand/leave cycles, per §5.4.

### 7.3 Regression surface

Existing suites must stay green, in particular the surface-lease, PiP, fullscreen, minimize-gesture,
slider-drag, and navigation tests. `WatchScreenTest.kt:1443` is kept as a lower-fidelity case
alongside the new one rather than deleted.

One existing test asserts the behaviour this spec removes and must be rewritten, not deleted:
`WatchViewModelTest.kt:246-284`, `load_prepares_before_slow_history_then_applies_resume_seek`, checks
`startPositionMs == 0L` at `:272` and then a post-hoc seek to `50_000` at `:283`. Its underlying
intent is still valid and worth keeping: a slow history lookup must not delay prepare. The rewrite
preserves that intent while asserting the new mechanism, namely that the resume position arrives
through `prepare` rather than through a later `seekTo`. Because history is now consulted before
prepare, the rewritten test must also confirm that a history repository which never returns cannot
block playback indefinitely.

That last point is a real design constraint, not just a test detail: awaiting history before prepare
would make playback hostage to a Room read. The resume lookup is therefore bounded, and a slow or
failing lookup falls back to `0L` and prepares immediately rather than waiting.

`gradlew testDebugUnitTest` covers the unit layer. The instrumented layer needs an emulator or
device; if none is available, that gap is reported rather than papered over.

## 8. Acceptance Criteria

1. Two separate taps on the right edge, with recomposition in between, seek forward 10 s; the left
   edge rewinds 10 s.
2. A tap in the centre band toggles controls and does not seek.
3. Returning to Watch from the mini player issues no prepare and no stream extraction, and playback
   continues from the current position without a visible restart.
4. Opening a video in a new session resumes at the persisted position, with no rebuffer-from-zero
   followed by a jump.
5. A finished video starts from the beginning.
6. `prepare` never lowers a persisted position to zero.
7. History is written while playback advances, so resume survives process death.
8. Repeated expand/leave cycles do not accumulate Watch back-stack entries, and back behaviour is
   unchanged from every entry point. If the current code already satisfies this, no production change
   is made and the test stands as a regression guard.
9. Surface handoff, PiP, fullscreen, minimize gesture, and slider drag all still behave as before.
10. New tests fail before their fix and pass after; existing suites stay green.

## 9. Delivery Sequence

1. Double-tap fix with its failing test first. Self-contained, immediately visible.
2. Reuse-active-player branch plus resume position in `WatchViewModel`, removing the post-prepare
   seek.
3. Conditional snapshot write in `SessionPlayerController`.
4. Back-stack measurement test; navigation options in `RootScaffold` only if it proves growth.
5. Periodic history recording in the service.
6. Full verification and a report of what could not be verified.

Steps 1 and 2 deliver the user-visible fixes. Steps 3 to 5 close the paths that would otherwise let
the same symptoms reappear after process death.
