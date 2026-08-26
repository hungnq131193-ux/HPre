# HPre YouTube-Familiar Interface Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Home immediately familiar to YouTube users with a dark-first theme, Vietnamese topic chips that load real results, and rounded video cards with complete Vietnamese metadata.

**Architecture:** Keep presentation changes inside the existing Compose design system and common UI package. Preserve `HomeUiState` for feed loading while exposing independent chip selection state from `HomeViewModel`; isolate topic retrieval behind a narrow `TopicFeedSource` implemented over the existing cached `CatalogRepository`.

**Tech Stack:** Kotlin 2.1.20, Jetpack Compose Material 3 1.3.1 via BOM 2025.02.00, coroutines 1.10.1, DataStore Preferences 1.1.3, JUnit 4, Compose UI tests.

**Spec:** `docs/superpowers/specs/2026-08-26-youtube-familiar-ui-design.md`

## Global Constraints

- Keep namespace and application ID `com.hpre.app`.
- Keep `minSdk = 26`, `compileSdk = 35`, `targetSdk = 35`, and Java/Kotlin target 17.
- Add no Gradle dependency and make no provider or playback change.
- Keep the HPre name, icon, and `HPreRed`; do not copy the YouTube wordmark, logo, or `#FF0000` brand color.
- Preserve `SYSTEM`, `LIGHT`, and `DARK` as selectable settings; only a missing or invalid stored preference defaults to `DARK`.
- Keep the existing Home and VideoCard test tags unchanged. There are 21 instrumentation references across four test classes.
- Topic chips must load real provider search results; do not add inert UI controls.
- Use `Locale("vi", "VN")` for abbreviated view-count formatting.
- Do not commit unless the user explicitly authorizes commits. Review task changes with `git diff` instead.
- Do not modify or revert unrelated worktree changes, including the pending icon and VN extractor localization changes.

---

## File Structure

- `settings/AppSettings.kt`: default value for newly created in-memory settings.
- `settings/SettingsRepository.kt`: persisted-setting fallback for missing or invalid theme values.
- `core/designsystem/Color.kt`: dark surface token values.
- `core/designsystem/HPreTheme.kt`: maps dark surface tokens into Material 3 roles.
- `ui/common/VideoFormat.kt`: pure Vietnamese duration, view-count, and relative-age formatting.
- `ui/common/VideoCard.kt`: YouTube-familiar card composition only; no data fetching.
- `ui/home/TopicFeedSource.kt`: narrow topic-query boundary and catalog-backed implementation.
- `ui/home/HomeViewModel.kt`: selected chip state, cancellation, and routing between personalized and topic feeds.
- `ui/home/HomeScreen.kt`: pinned horizontal chip row and feed-state rendering.
- `di/AppContainer.kt`, `navigation/HPreNavHost.kt`: dependency construction and ViewModel wiring.

### Task 1: Make Dark the First-Run Default

**Files:**
- Modify: `app/src/main/java/com/hpre/app/settings/AppSettings.kt`
- Modify: `app/src/main/java/com/hpre/app/settings/SettingsRepository.kt`
- Modify: `app/src/main/java/com/hpre/app/core/designsystem/Color.kt`
- Modify: `app/src/main/java/com/hpre/app/core/designsystem/HPreTheme.kt`
- Test: `app/src/test/java/com/hpre/app/settings/SettingsRepositoryTest.kt`

**Interfaces:**
- Consumes: existing `AppTheme.SYSTEM`, `AppTheme.LIGHT`, and `AppTheme.DARK` enum values.
- Produces: `AppSettings().theme == AppTheme.DARK`; missing and invalid DataStore values emit `DARK`; Material roles `surfaceContainer` and `surfaceContainerHighest` use the HPre dark control surfaces.

- [ ] **Step 1: Change the default-setting test first**

In `SettingsRepositoryTest.default_settings_match_specification`, change the expected theme:

```kotlin
assertEquals(AppTheme.DARK, settings.theme)
```

Change the test fixture from a local DataStore variable to a field so the invalid-value test can write raw preferences:

```kotlin
private lateinit var dataStore: DataStore<Preferences>

@Before
fun setUp() {
    tempFile = File(System.getProperty("java.io.tmpdir"), "test_settings_${System.currentTimeMillis()}.preferences_pb")
    testScope = CoroutineScope(Dispatchers.Unconfined + Job())
    dataStore = PreferenceDataStoreFactory.create(
        scope = testScope,
        produceFile = { tempFile }
    )
    repository = DataStoreSettingsRepository(dataStore)
}
```

Add imports for `androidx.datastore.core.DataStore`, `androidx.datastore.preferences.core.Preferences`, and `androidx.datastore.preferences.core.edit`.

Then add a test that writes an invalid raw value and asserts the fallback:

```kotlin
@Test
fun invalid_stored_theme_falls_back_to_dark() = runTest {
    dataStore.edit { preferences ->
        preferences[DataStoreSettingsRepository.KEY_THEME] = "NOT_A_THEME"
    }

    assertEquals(AppTheme.DARK, repository.settings.first().theme)
}
```

- [ ] **Step 2: Run the settings test and verify it fails**

Run:

```powershell
.\gradlew.bat --offline testDebugUnitTest --tests "com.hpre.app.settings.SettingsRepositoryTest"
```

Expected: `default_settings_match_specification` and `invalid_stored_theme_falls_back_to_dark` fail because both repository fallbacks still return `SYSTEM`.

- [ ] **Step 3: Implement the two dark defaults**

In `AppSettings.kt`:

```kotlin
data class AppSettings(
    val theme: AppTheme = AppTheme.DARK,
    // existing fields unchanged
)
```

In `SettingsRepository.kt`, use one named default for both missing and malformed values:

```kotlin
companion object {
    val DEFAULT_THEME = AppTheme.DARK
    // existing keys and constants unchanged
}

val theme = preferences[KEY_THEME]?.let {
    try {
        AppTheme.valueOf(it)
    } catch (_: IllegalArgumentException) {
        DEFAULT_THEME
    }
} ?: DEFAULT_THEME
```

Do not change `setTheme`; explicit stored `SYSTEM` and `LIGHT` values must continue to round-trip.

- [ ] **Step 4: Add and map the dark surface token**

In `Color.kt`:

```kotlin
val HPreDarkSurface = Color(0xFF212121)
val HPreDarkSurfaceContainer = Color(0xFF272727)
```

Keep `HPreDarkBackground = Color(0xFF0F0F0F)` and `HPreRed` unchanged.

In `HPreTheme.kt`, add these named arguments to `DarkColorScheme`:

```kotlin
surfaceContainer = HPreDarkSurfaceContainer,
surfaceContainerHighest = HPreDarkSurfaceVariant,
```

Map equivalent existing light tokens to the light scheme so components do not fall back to unrelated Material defaults:

```kotlin
surfaceContainer = HPreLightSurface,
surfaceContainerHighest = HPreLightSurfaceVariant,
```

- [ ] **Step 5: Run settings tests and compile the theme**

Run:

```powershell
.\gradlew.bat --offline testDebugUnitTest --tests "com.hpre.app.settings.SettingsRepositoryTest" compileDebugKotlin
```

Expected: PASS. Existing `updating_theme_persists_and_emits` proves explicit `DARK`, `LIGHT`, and `SYSTEM` choices still work.

- [ ] **Step 6: Review only Task 1 changes**

Run `git diff -- app/src/main/java/com/hpre/app/settings app/src/main/java/com/hpre/app/core/designsystem app/src/test/java/com/hpre/app/settings/SettingsRepositoryTest.kt` and confirm no unrelated preference or palette behavior changed. Do not commit without user authorization.

### Task 2: Add Pure Vietnamese Video Formatting

**Files:**
- Create: `app/src/main/java/com/hpre/app/ui/common/VideoFormat.kt`
- Create: `app/src/test/java/com/hpre/app/ui/common/VideoFormatTest.kt`

**Interfaces:**
- Produces:

```kotlin
object VideoFormat {
    fun duration(seconds: Long?): String
    fun viewCount(views: Long?): String
    fun age(publishedTimestamp: Long?, now: Long): String
}
```

- `publishedTimestamp` and `now` are epoch milliseconds. NewPipe mapping uses `Instant.toEpochMilli()`.

- [ ] **Step 1: Write failing duration tests**

Create `VideoFormatTest.kt` with:

```kotlin
package com.hpre.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFormatTest {
    @Test
    fun duration_formats_minutes_and_hours() {
        assertEquals("0:00", VideoFormat.duration(0))
        assertEquals("2:05", VideoFormat.duration(125))
        assertEquals("1:02:03", VideoFormat.duration(3723))
    }

    @Test
    fun duration_omits_unknown_or_invalid_values() {
        assertEquals("", VideoFormat.duration(null))
        assertEquals("", VideoFormat.duration(-1))
    }
}
```

- [ ] **Step 2: Write failing Vietnamese view-count tests**

Add:

```kotlin
@Test
fun view_count_uses_vietnamese_units_and_decimal_comma() {
    assertEquals("999 lượt xem", VideoFormat.viewCount(999))
    assertEquals("1 N lượt xem", VideoFormat.viewCount(1_000))
    assertEquals("1,2 N lượt xem", VideoFormat.viewCount(1_250))
    assertEquals("1 Tr lượt xem", VideoFormat.viewCount(1_000_000))
    assertEquals("1,5 Tr lượt xem", VideoFormat.viewCount(1_500_000))
    assertEquals("2 T lượt xem", VideoFormat.viewCount(2_000_000_000))
}

@Test
fun view_count_omits_unknown_or_invalid_values() {
    assertEquals("", VideoFormat.viewCount(null))
    assertEquals("", VideoFormat.viewCount(-1))
}
```

- [ ] **Step 3: Write failing relative-age tests**

Add fixed-millisecond tests:

```kotlin
@Test
fun age_formats_vietnamese_relative_time() {
    val now = 2_000_000_000_000L
    assertEquals("vừa xong", VideoFormat.age(now, now))
    assertEquals("5 phút trước", VideoFormat.age(now - 5 * 60_000L, now))
    assertEquals("3 giờ trước", VideoFormat.age(now - 3 * 3_600_000L, now))
    assertEquals("6 ngày trước", VideoFormat.age(now - 6 * 86_400_000L, now))
    assertEquals("2 tuần trước", VideoFormat.age(now - 14 * 86_400_000L, now))
    assertEquals("3 tháng trước", VideoFormat.age(now - 90 * 86_400_000L, now))
    assertEquals("2 năm trước", VideoFormat.age(now - 730 * 86_400_000L, now))
}

@Test
fun age_omits_unknown_or_future_values() {
    val now = 2_000_000_000_000L
    assertEquals("", VideoFormat.age(null, now))
    assertEquals("", VideoFormat.age(now + 1, now))
}
```

- [ ] **Step 4: Run the formatter test and verify missing-symbol failure**

Run:

```powershell
.\gradlew.bat --offline testDebugUnitTest --tests "com.hpre.app.ui.common.VideoFormatTest"
```

Expected: compilation fails with `Unresolved reference 'VideoFormat'`.

- [ ] **Step 5: Implement `VideoFormat` minimally**

Create `VideoFormat.kt`:

```kotlin
package com.hpre.app.ui.common

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object VideoFormat {
    private val viSymbols = DecimalFormatSymbols(Locale("vi", "VN"))
    private val oneDecimal = DecimalFormat("0.#", viSymbols)

    fun duration(seconds: Long?): String {
        if (seconds == null || seconds < 0) return ""
        val hours = seconds / 3_600
        val minutes = (seconds % 3_600) / 60
        val remaining = seconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remaining)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, remaining)
        }
    }

    fun viewCount(views: Long?): String {
        if (views == null || views < 0) return ""
        val (value, unit) = when {
            views >= 1_000_000_000 -> views / 1_000_000_000.0 to "T"
            views >= 1_000_000 -> views / 1_000_000.0 to "Tr"
            views >= 1_000 -> views / 1_000.0 to "N"
            else -> return "$views lượt xem"
        }
        return "${oneDecimal.format(value)} $unit lượt xem"
    }

    fun age(publishedTimestamp: Long?, now: Long): String {
        if (publishedTimestamp == null || publishedTimestamp > now) return ""
        val elapsed = now - publishedTimestamp
        val minutes = elapsed / 60_000L
        val hours = elapsed / 3_600_000L
        val days = elapsed / 86_400_000L
        return when {
            minutes < 1 -> "vừa xong"
            hours < 1 -> "$minutes phút trước"
            days < 1 -> "$hours giờ trước"
            days < 7 -> "$days ngày trước"
            days < 30 -> "${days / 7} tuần trước"
            days < 365 -> "${days / 30} tháng trước"
            else -> "${days / 365} năm trước"
        }
    }
}
```

- [ ] **Step 6: Run formatter tests and lint the file**

Run:

```powershell
.\gradlew.bat --offline testDebugUnitTest --tests "com.hpre.app.ui.common.VideoFormatTest" lintDebug
```

Expected: PASS; no `DefaultLocale` warning originates from `VideoFormat.kt`.

- [ ] **Step 7: Review Task 2 changes**

Run `git diff -- app/src/main/java/com/hpre/app/ui/common/VideoFormat.kt app/src/test/java/com/hpre/app/ui/common/VideoFormatTest.kt`. Confirm no Android API is used, so tests remain plain JVM tests. Do not commit without user authorization.

### Task 3: Restyle the Video Card Without Changing Its Contract

**Files:**
- Modify: `app/src/main/java/com/hpre/app/ui/common/VideoCard.kt`
- Test: `app/src/androidTest/java/com/hpre/app/ui/search/SearchScreenTest.kt`

**Interfaces:**
- Consumes: `VideoFormat.duration`, `VideoFormat.viewCount`, and `VideoFormat.age` from Task 2.
- Preserves: `VideoCard(video, onClick, modifier)`, `video_card_<nativeId>`, and `video_thumbnail`.
- Produces: 12dp rounded thumbnail, 12dp horizontal margin, Medium title, Vietnamese metadata, and truthful live badge.

- [ ] **Step 1: Add Compose assertions for the visible metadata**

In `SearchScreenTest`, extend the existing fixture/card test with one normal video whose `viewCount` and `publishedTimestamp` are fixed, and assert the semantic text nodes generated by the formatter. Supply `publishedTimestamp` relative to the device clock far enough in the past to avoid a boundary (for example 90 days).

Add a live fixture and assert:

```kotlin
composeTestRule.onNodeWithText("TRỰC TIẾP").assertIsDisplayed()
```

Keep the existing `video_card_*` click assertions unchanged.

- [ ] **Step 2: Compile the instrumentation test before UI changes**

Run:

```powershell
.\gradlew.bat --offline compileDebugAndroidTestKotlin
```

Expected: compilation succeeds. Runtime assertions are expected to fail on a device because current cards render English view text and no live badge. If no device is attached, record that runtime red-state confirmation is unavailable.

- [ ] **Step 3: Replace private formatters with `VideoFormat`**

Delete the private `formatDuration` and `formatViews` functions from `VideoCard.kt`. At composition time:

```kotlin
val durationText = VideoFormat.duration(video.durationSeconds)
val viewsText = VideoFormat.viewCount(video.viewCount)
val ageText = VideoFormat.age(video.publishedTimestamp, System.currentTimeMillis())
```

Build metadata by omitting blank segments:

```kotlin
val metaParts = listOfNotNull(
    video.channelName?.takeIf(String::isNotBlank),
    viewsText.takeIf(String::isNotBlank),
    ageText.takeIf(String::isNotBlank)
)
```

- [ ] **Step 4: Apply the card geometry**

Keep the card root and click/test tag unchanged. Add horizontal padding before the thumbnail and clip the thumbnail container:

```kotlin
Box(
    modifier = Modifier
        .padding(horizontal = 12.dp)
        .fillMaxWidth()
        .aspectRatio(16f / 9f)
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
)
```

Make the `AsyncImage` use `Modifier.fillMaxSize().testTag("video_thumbnail")` so it inherits the clipped parent; do not apply a second aspect ratio.

Set the title to `FontWeight.Medium`. Preserve two title lines, 36dp avatar, and existing card bottom spacing.

- [ ] **Step 5: Add truthful live/duration badge rendering**

Use one badge branch:

```kotlin
when {
    video.isLive -> VideoBadge(
        text = "TRỰC TIẾP",
        background = MaterialTheme.colorScheme.error
    )
    durationText.isNotEmpty() -> VideoBadge(
        text = durationText,
        background = Color.Black.copy(alpha = 0.8f)
    )
}
```

Keep `VideoBadge` private in `VideoCard.kt`; it is only presentation and has no reuse requirement. Render white `labelSmall` text with a 4dp rounded shape.

- [ ] **Step 6: Run common tests, instrumentation compilation, and lint**

Run:

```powershell
.\gradlew.bat --offline testDebugUnitTest --tests "com.hpre.app.ui.common.*" compileDebugAndroidTestKotlin lintDebug
```

Expected: PASS. The four old `DefaultLocale` warnings from `VideoCard.kt` are absent. Existing card tags compile unchanged.

- [ ] **Step 7: Review Task 3 changes**

Run `git diff -- app/src/main/java/com/hpre/app/ui/common/VideoCard.kt app/src/androidTest/java/com/hpre/app/ui/search/SearchScreenTest.kt`. Confirm there is no inert overflow button and no callback signature change. Do not commit without user authorization.

### Task 4: Add Real Topic Feeds and ViewModel Chip State

**Files:**
- Create: `app/src/main/java/com/hpre/app/ui/home/TopicFeedSource.kt`
- Modify: `app/src/main/java/com/hpre/app/ui/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/hpre/app/di/AppContainer.kt`
- Modify: `app/src/main/java/com/hpre/app/navigation/HPreNavHost.kt`
- Test: `app/src/test/java/com/hpre/app/ui/home/HomeViewModelTest.kt`

**Interfaces:**
- Produces:

```kotlin
fun interface TopicFeedSource {
    suspend fun videos(query: String, forceRefresh: Boolean): AppResult<List<VideoSummary>>
}

data class HomeChip(val label: String, val query: String?)
data class HomeChipsState(val chips: List<HomeChip>, val selectedIndex: Int)
```

- `CatalogTopicFeedSource` maps only `SearchResultItem.VideoItem` from `CatalogRepository.search(query, SearchFilter.VIDEOS, forceRefresh = forceRefresh)`.
- `HomeViewModel.selectChip(index: Int)` is the UI entry point.

- [ ] **Step 1: Add ViewModel tests with fake sources**

In `HomeViewModelTest`, add a fake topic source:

```kotlin
private class FakeTopicFeedSource : TopicFeedSource {
    val calls = mutableListOf<Pair<String, Boolean>>()
    var handler: suspend (String, Boolean) -> AppResult<List<VideoSummary>> = { _, _ ->
        AppResult.Success(emptyList())
    }

    override suspend fun videos(query: String, forceRefresh: Boolean): AppResult<List<VideoSummary>> {
        calls += query to forceRefresh
        return handler(query, forceRefresh)
    }
}
```

Add tests:

```kotlin
@Test
fun selecting_topic_updates_chip_and_loads_topic_videos() = runTest(testDispatcher) {
    val topicSource = FakeTopicFeedSource().apply {
        handler = { _, _ -> AppResult.Success(listOf(summary("music"))) }
    }
    val viewModel = HomeViewModel(
        repository = HomeRecommendationSource { AppResult.Success(listOf(summary("all"))) },
        topicFeedSource = topicSource
    )
    advanceUntilIdle()

    viewModel.selectChip(1)
    advanceUntilIdle()

    assertEquals(1, viewModel.chipsState.value.selectedIndex)
    assertEquals("âm nhạc" to false, topicSource.calls.single())
    assertEquals("music", (viewModel.uiState.value as HomeUiState.Content).videos.single().key.nativeId)
}
```

Add separate tests for:

- selecting `Tất cả` after a topic calls `repository.home(false)` again;
- `retry()` sends `forceRefresh = true` to the currently selected topic;
- a topic success with an empty list yields `HomeUiState.Empty` while `selectedIndex` remains selected;
- a non-cooperative stale topic response cannot overwrite the newer chip result, using the existing `NonCancellable`/scheduler pattern.

Update the six existing constructors to pass `topicFeedSource = FakeTopicFeedSource()`.

- [ ] **Step 2: Run Home tests and verify missing-symbol failure**

Run:

```powershell
.\gradlew.bat --offline testDebugUnitTest --tests "com.hpre.app.ui.home.HomeViewModelTest"
```

Expected: compilation fails because `TopicFeedSource`, `chipsState`, and `selectChip` do not exist.

- [ ] **Step 3: Implement the topic feed boundary**

Create `TopicFeedSource.kt`:

```kotlin
package com.hpre.app.ui.home

import com.hpre.app.core.error.AppResult
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchResultItem
import com.hpre.app.model.VideoSummary
import com.hpre.app.repository.CatalogRepository

fun interface TopicFeedSource {
    suspend fun videos(query: String, forceRefresh: Boolean): AppResult<List<VideoSummary>>
}

class CatalogTopicFeedSource(
    private val catalogRepository: CatalogRepository
) : TopicFeedSource {
    override suspend fun videos(query: String, forceRefresh: Boolean): AppResult<List<VideoSummary>> =
        when (val result = catalogRepository.search(
            query = query,
            filter = SearchFilter.VIDEOS,
            forceRefresh = forceRefresh
        )) {
            is AppResult.Success -> AppResult.Success(
                result.value.items.mapNotNull { item ->
                    (item as? SearchResultItem.VideoItem)?.summary
                }
            )
            is AppResult.Failure -> result
        }
}
```

- [ ] **Step 4: Implement chips and routing in `HomeViewModel`**

Add:

```kotlin
data class HomeChip(val label: String, val query: String?)

data class HomeChipsState(
    val chips: List<HomeChip>,
    val selectedIndex: Int = 0
)
```

Use this fixed list in the companion object:

```kotlin
val DEFAULT_CHIPS = listOf(
    HomeChip("Tất cả", null),
    HomeChip("Âm nhạc", "âm nhạc"),
    HomeChip("Trò chơi", "trò chơi"),
    HomeChip("Phim ảnh", "phim ảnh"),
    HomeChip("Thể thao", "thể thao"),
    HomeChip("Tin tức", "tin tức"),
    HomeChip("Học tập", "học tập"),
    HomeChip("Ẩm thực", "ẩm thực")
)
```

Constructor and state:

```kotlin
class HomeViewModel(
    private val repository: HomeRecommendationSource,
    private val topicFeedSource: TopicFeedSource
) : ViewModel() {
    private val _chipsState = MutableStateFlow(HomeChipsState(DEFAULT_CHIPS))
    val chipsState: StateFlow<HomeChipsState> = _chipsState.asStateFlow()
```

Implement selection defensively:

```kotlin
fun selectChip(index: Int) {
    if (index !in _chipsState.value.chips.indices) return
    if (_chipsState.value.selectedIndex == index) return
    _chipsState.value = _chipsState.value.copy(selectedIndex = index)
    load(forceRefresh = false)
}
```

Inside the existing generation-guarded load coroutine, choose the source from the selected chip snapshot:

```kotlin
val selectedChip = _chipsState.value.chips[_chipsState.value.selectedIndex]
val result = try {
    selectedChip.query?.let { query ->
        topicFeedSource.videos(query, forceRefresh)
    } ?: repository.home(forceRefresh)
} catch (ce: CancellationException) {
    throw ce
} catch (_: Throwable) {
    AppResult.Failure(AppError.Unknown)
}
```

Keep the existing state mapping and generation check unchanged. `retry()` continues to call `load(true)`, which now refreshes the current chip.

Update `provideFactory` to require both dependencies.

- [ ] **Step 5: Wire the source in the container and navigation**

In `AppContainer` add:

```kotlin
val topicFeedSource: TopicFeedSource
    get() = CatalogTopicFeedSource(catalogRepository)
```

In `DefaultAppContainer`, add one lazy override so recomposition does not create wrappers repeatedly:

```kotlin
override val topicFeedSource: TopicFeedSource by lazy {
    CatalogTopicFeedSource(catalogRepository)
}
```

Import `CatalogTopicFeedSource` and `TopicFeedSource`.

Update `HPreNavHost`:

```kotlin
factory = HomeViewModel.provideFactory(
    repository = container.recommendationRepository,
    topicFeedSource = container.topicFeedSource
)
```

- [ ] **Step 6: Run Home, repository, and navigation compilation tests**

Run:

```powershell
.\gradlew.bat --offline testDebugUnitTest --tests "com.hpre.app.ui.home.*" --tests "com.hpre.app.repository.*" compileDebugAndroidTestKotlin
```

Expected: PASS. Existing personalized recommendations still use `HomeRecommendationSource`; topic searches use only `TopicFeedSource`.

- [ ] **Step 7: Review Task 4 changes**

Run `git diff -- app/src/main/java/com/hpre/app/ui/home app/src/main/java/com/hpre/app/di/AppContainer.kt app/src/main/java/com/hpre/app/navigation/HPreNavHost.kt app/src/test/java/com/hpre/app/ui/home/HomeViewModelTest.kt`. Confirm no topic chip bypasses `CatalogRepository`, and stale-response protection remains. Do not commit without user authorization.

### Task 5: Render the Pinned Home Chip Row and Verify the Slice

**Files:**
- Modify: `app/src/main/java/com/hpre/app/ui/home/HomeScreen.kt`
- Modify: `app/src/androidTest/java/com/hpre/app/navigation/HomeToWatchNavigationTest.kt`
- Modify: `docs/manual-test-matrix.md`

**Interfaces:**
- Consumes: `HomeViewModel.chipsState` and `HomeViewModel.selectChip(index)` from Task 4.
- Preserves all existing Home and VideoCard test tags.
- Produces: `home_filter_chips` and `home_filter_chip_<index>` tags.

- [ ] **Step 1: Add the Home chip UI assertions**

In `HomeToWatchNavigationTest`, after Home content appears, assert:

```kotlin
composeTestRule.onNodeWithTag("home_filter_chips").assertIsDisplayed()
composeTestRule.onNodeWithTag("home_filter_chip_0").assertIsSelected()
composeTestRule.onNodeWithText("Tất cả").assertIsDisplayed()
composeTestRule.onNodeWithText("Âm nhạc").assertIsDisplayed()
```

Add a fake topic response for `âm nhạc`, click `home_filter_chip_1`, and assert the topic card appears while `home_filter_chips` remains displayed.

- [ ] **Step 2: Compile the instrumentation test before implementing the row**

Run:

```powershell
.\gradlew.bat --offline compileDebugAndroidTestKotlin
```

Expected: compilation succeeds. Runtime assertions would fail because the chip tags do not yet exist; if no device is attached, record that the red runtime check is unavailable.

- [ ] **Step 3: Restructure `HomeScreen` so chips are independent of feed state**

Collect both flows:

```kotlin
val uiState by viewModel.uiState.collectAsStateWithLifecycle()
val chipsState by viewModel.chipsState.collectAsStateWithLifecycle()
```

Use a root `Column` tagged `home_screen`. Render a `LazyRow` first and a weighted feed-state `Box` second:

```kotlin
Column(modifier = modifier.fillMaxSize().testTag("home_screen")) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("home_filter_chips")
    ) {
        itemsIndexed(chipsState.chips) { index, chip ->
            FilterChip(
                selected = index == chipsState.selectedIndex,
                onClick = { viewModel.selectChip(index) },
                label = { Text(chip.label) },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("home_filter_chip_$index"),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                    selectedLabelColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    }

    Box(Modifier.weight(1f).fillMaxWidth()) {
        // existing HomeUiState when expression
    }
}
```

The chips stay outside the `when`, so Loading, Empty, and Error do not remove them.

- [ ] **Step 4: Preserve feed behavior and tags inside the weighted box**

Keep:

- `LoadingPane(testTag = "home_loading")`;
- `EmptyPane(..., testTag = "home_empty")`;
- `ErrorPane(..., testTag = "home_error")`;
- content list tag `home_video_list`;
- `VideoCard(video, onVideoClick)` call contract.

Change the empty copy to Vietnamese:

```kotlin
EmptyPane(message = "Không có video phù hợp", testTag = "home_empty")
```

Keep pull-to-refresh around the content list. Its existing `viewModel.load(forceRefresh = true)` now refreshes the selected chip by Task 4 design.

- [ ] **Step 5: Run all deterministic checks**

Set Java 17 in the shell if needed, then run:

```powershell
.\gradlew.bat --offline clean testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin
```

Expected:

- BUILD SUCCESSFUL;
- all unit tests pass;
- lint reports 0 errors;
- no `DefaultLocale` warning remains in `VideoCard.kt`;
- debug APK is assembled;
- Android instrumentation sources compile.

If `clean` fails because Gradle holds the lint cache, run `.\gradlew.bat --offline --stop` once and retry the exact gate. Do not delete source files or use destructive Git commands.

- [ ] **Step 6: Run device tests only when a device is attached**

Run `adb devices`. If a device is listed, run:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.hpre.app.navigation.HomeToWatchNavigationTest,com.hpre.app.ui.search.SearchScreenTest
```

Verify on a 360dp-wide viewport:

- chip row scrolls horizontally without clipping;
- chips remain visible during topic loading/error/empty states;
- selected chip contrast is readable in dark and light themes;
- thumbnail corners and margins match across Home and Search;
- normal cards show Vietnamese views and age;
- live cards show `TRỰC TIẾP` and no duration badge.

If no device is attached, do not claim these checks passed.

- [ ] **Step 7: Update the manual test matrix with exact evidence**

In `docs/manual-test-matrix.md`, update the deterministic gate test count from the actual XML reports. Add a row:

```markdown
| YouTube-familiar Home UI | Not run | Not run | No device attached | Debug APK | Not run | Unit formatting/topic-state tests passed and instrumentation sources compiled; 360dp chip scrolling, dark/light contrast, rounded cards, and live badge require device verification. |
```

Replace `No device attached` and `Not run` only if Step 6 actually ran successfully.

- [ ] **Step 8: Final scope and worktree review**

Run:

```powershell
git status --short
git diff -- app/src/main/java/com/hpre/app/settings app/src/main/java/com/hpre/app/core/designsystem app/src/main/java/com/hpre/app/ui/common app/src/main/java/com/hpre/app/ui/home app/src/main/java/com/hpre/app/di/AppContainer.kt app/src/main/java/com/hpre/app/navigation/HPreNavHost.kt app/src/test/java/com/hpre/app/settings app/src/test/java/com/hpre/app/ui/common app/src/test/java/com/hpre/app/ui/home app/src/androidTest/java/com/hpre/app docs/manual-test-matrix.md
```

Confirm:

- the work stays within Theme + Home + VideoCard;
- no YouTube mark or copied brand red is present;
- no inert overflow menu exists;
- no existing test tag was renamed;
- unrelated icon and extractor localization changes remain intact;
- no commit was created without explicit user permission.
