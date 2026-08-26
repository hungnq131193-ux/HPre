# HPre YouTube-Familiar Interface Design

**Date:** 2026-08-26
**Status:** Approved for planning
**Scope:** Theme, Home feed chrome, and video card presentation

## Goal

Make HPre's browsing surface feel familiar to someone arriving from YouTube, by
matching the interaction patterns and visual rhythm users already know: a
dark-first surface, a pinned topic filter row above the feed, and a video card
with rounded thumbnail and full metadata line.

## Non-Goals

This design deliberately excludes:

- The YouTube wordmark, logo, and brand red (`#FF0000`). HPre keeps its own
  `HPreRed` and its own name in the logo position. Familiarity comes from
  layout, surface tone, spacing, and typography, not from appropriating a
  protected mark.
- Watch screen, Shorts overlay, Subscriptions, Library, Search, Channel,
  MiniPlayer, and bottom navigation restructuring. Those are separate slices.
- A per-card overflow (three-dot) menu. Adding the affordance would require
  threading save/share callbacks from Home through `VideoCard`, which widens
  scope beyond this slice. Deferred rather than faked.
- Any new dependency, provider capability, or playback change.

## Constraints

- No new Gradle dependency. Compose Material 3 from BOM 2025.02.00 supplies
  `FilterChip`, `LazyRow`, and the `surfaceContainer` color roles.
- `minSdk = 26`, Java/Kotlin target 17 unchanged.
- Existing instrumentation test tags stay stable: `home_screen`, `home_loading`,
  `home_empty`, `home_error`, `home_video_list`, `video_card_<nativeId>`,
  `video_thumbnail`. 21 instrumentation references across four test classes
  depend on them: `HomeToWatchNavigationTest` (5), `NavigationFlowTest` (3),
  `SearchScreenTest` (9), `WatchRecreationTest` (4).
- The three-way theme preference (`SYSTEM`/`LIGHT`/`DARK`) stays user-selectable.
  Only the default changes.
- Number and date formatting uses `Locale("vi", "VN")`, consistent with the
  provider localization already set in `ExtractorLocalization`.

## Component 1: Dark-First Theme

### Current state

`HPreTheme` selects between `LightColorScheme` and `DarkColorScheme` from a
`darkTheme` boolean that `MainActivity` derives from `AppSettings.theme`. The
default is `AppTheme.SYSTEM`, so a device in light mode shows a white feed.

The existing dark palette is already close to the target: `HPreDarkBackground`
is `#0F0F0F`.

### Changes

Default theme becomes `AppTheme.DARK`. This requires two edits, not one:

- `AppSettings.theme` default value.
- `DataStoreSettingsRepository.settings`, which independently falls back to
  `AppTheme.SYSTEM` in two places when the stored key is absent or unparseable.

Missing an unparseable-value fallback would let a corrupt preference silently
resurrect the light default.

Palette adjustments in `Color.kt`:

| Token | From | To | Role |
|---|---|---|---|
| `HPreDarkSurface` | `#1E1E1E` | `#212121` | raised surface |
| `HPreDarkSurfaceContainer` | absent | `#272727` | chip and control background |

`HPreTheme` must map `surfaceContainer` and `surfaceContainerHighest`. It
currently sets only `surfaceVariant`, so unstyled `FilterChip` containers would
fall back to a Material default that does not match the surrounding surface.

The light palette is unchanged; `#FFFFFF` / `#F8F8F8` / `#606060` already match
the light target.

`Typography` is unchanged. `FontFamily.Default` resolves to Roboto on Android,
and the existing `titleSmall` (14sp Medium) and `bodySmall` (12sp) already match.

## Component 2: Video Card

### Current state

`VideoCard` renders a square-cornered, edge-to-edge 16:9 thumbnail, then an
avatar plus a two-line title and a single metadata line joining channel name and
view count. Duration formatting is `private` and untested, and formats with the
default locale.

### Changes

Presentation:

- Thumbnail clipped to a 12dp rounded corner with 12dp horizontal margin, so the
  card reads as a distinct surface rather than a full-bleed band.
- Metadata line carries three facts: `Kênh • 1,2 Tr lượt xem • 3 ngày trước`.
  Video age is currently absent even though `VideoSummary.publishedTimestamp`
  is populated.
- When `VideoSummary.isLive` is true, the duration badge is replaced by a
  `TRỰC TIẾP` badge tinted with the error color. Both inputs already exist on
  the model.
- Title weight drops from `SemiBold` to `Medium`, matching the reference and the
  `titleSmall` style already defined in `Typography`.

Formatting is extracted from `VideoCard` into a testable `ui/common/VideoFormat.kt`:

```kotlin
object VideoFormat {
    fun duration(seconds: Long?): String
    fun viewCount(views: Long?): String   // "1,2 Tr lượt xem"
    fun age(publishedTimestamp: Long?, now: Long): String  // "3 ngày trước"
}
```

`age` takes `now` as a parameter rather than reading the clock, so the boundary
cases are testable without freezing time globally.

Extraction serves two purposes beyond testability: it resolves the four
`DefaultLocale` lint warnings at `VideoCard.kt:43,45,52,53`, and it stops the
app from rendering English `"1.2M views"` inside a `vi-VN` localized product.

Null and absent values render as an omitted segment, never as a placeholder or
a zero.

## Component 3: Home Filter Chips

### Design decision: chips live outside `HomeUiState`

The chip row must stay visible and interactive while the feed below it reloads.
Folding chips into `HomeUiState` would force every `Loading`/`Empty`/`Error`
branch to carry chip data, would make the row disappear on every reload, and
would break the existing `HomeViewModelTest` and instrumentation assertions that
match on `HomeUiState` shape.

Chips are therefore a separate `StateFlow`:

```kotlin
data class HomeChip(val label: String, val query: String?)  // null query = "Tất cả"
data class HomeChipsState(val chips: List<HomeChip>, val selectedIndex: Int)
```

Desync risk is low because chip state depends only on a fixed topic list plus
the selected index; it never depends on a network result.

### Behavior

- `Tất cả` (index 0, `query == null`) calls `repository.home(forceRefresh)`,
  preserving today's personalized feed exactly.
- A topic chip calls `catalogRepository.search(query, SearchFilter.VIDEOS)` and
  keeps `SearchResultItem.VideoItem` entries. Fixed Vietnamese topic list:
  Âm nhạc, Trò chơi, Phim ảnh, Thể thao, Tin tức, Học tập, Ẩm thực.
- Selecting a chip while a previous load is in flight reuses the existing
  `activeLoadJob?.cancel()` and `loadGeneration` guard in `HomeViewModel`, so a
  late response cannot overwrite newer state.
- A topic returning no videos yields `HomeUiState.Empty` while the chip row
  stays rendered, so the user can return to `Tất cả`.
- Pull-to-refresh re-runs the currently selected chip, not unconditionally
  `home()`.

`CatalogRepository` already caches search results for 60s, so toggling between
chips is served from cache without repeat network calls.

### Dependency wiring

`HomeViewModel` gains a topic-feed dependency alongside `HomeRecommendationSource`:

```kotlin
fun interface TopicFeedSource {
    suspend fun videos(query: String, forceRefresh: Boolean): AppResult<List<VideoSummary>>
}
```

A dedicated seam is used instead of passing `CatalogRepository` directly, so the
ViewModel depends on the one operation it needs and unit tests do not have to
construct a full catalog with a fake service.

The parameter is required, not defaulted to null. A null-defaulted dependency
would let chips compile and silently do nothing. The six existing
`HomeViewModel(repository = ...)` call sites in `HomeViewModelTest` are updated
accordingly.

`AppContainer` exposes the implementation backed by `catalogRepository`;
`HPreNavHost` passes it to `HomeViewModel.provideFactory`.

## Testing

Unit tests:

- `VideoFormat`: duration under and over an hour, zero, null, negative;
  view-count thresholds at nghìn/triệu/tỷ boundaries; age across minute, hour,
  day, month, and year boundaries; Vietnamese decimal comma output.
- Theme default: `AppSettings` default is `DARK`; `DataStoreSettingsRepository`
  returns `DARK` for absent and for unparseable stored values.
- `HomeViewModel`: chip selection switches source; selecting a second chip mid
  load discards the stale response; empty topic result yields `Empty` with chips
  retained; refresh re-runs the selected chip; `Tất cả` still calls `home()`.

Instrumentation tests (device required):

- Chip row is displayed and scrollable on a 360dp width.
- Chip row remains present while the feed shows its loading state.
- Card renders age and live badge.

Existing tags are asserted unchanged so the four dependent instrumentation
classes continue to pass.

## Verification

`.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug` must pass with
no new lint warnings. The four `DefaultLocale` warnings in `VideoCard` are
expected to disappear.

Instrumentation cannot be run in the current environment: `adb devices` reports
no attached device. Device-dependent rows are recorded as Not run in
`docs/manual-test-matrix.md` rather than assumed passing. Visual fidelity to the
reference, chip legibility, and themed-icon behavior all require a real device
to confirm.
