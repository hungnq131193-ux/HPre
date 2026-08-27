# HPre Android UI Polish and Shorts Removal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove HPre's dedicated Shorts mode and deliver a polished, fully Vietnamese, inset-safe Material 3 interface for Android phones without changing playback or data behavior.

**Architecture:** Keep `RootScaffold` as the owner of top-level bars and system insets, while nested destinations own only their local app bars and IME handling. Centralize copy in Android resources and visual constants in the existing design-system package; remove only dedicated Shorts feature code while retaining provider compatibility for `/shorts/` URLs and channel data.

**Tech Stack:** Kotlin 2.1, Jetpack Compose Material 3, Navigation Compose, AndroidX Activity edge-to-edge APIs, Coil Compose, JUnit 4, Compose UI Test, Gradle on Java 17

**Spec:** `docs/superpowers/specs/2026-08-26-android-ui-polish-and-shorts-removal-design.md`

## Global Constraints

- Keep `minSdk = 26`, `compileSdk = 35`, `targetSdk = 35`, and Java/Kotlin target 17 unchanged.
- Add no new Gradle dependency and no new font dependency.
- Keep the red HPre identity; do not add dynamic wallpaper colors.
- Remove only the dedicated Shorts mode. Preserve `/shorts/<id>` URL parsing, `supportsShorts`, channel short-form model fields, and ordinary Watch playback.
- Keep Home, Search, Watch, Channel, subscriptions, library, playlists, Settings, MiniPlayer, PiP, MediaSession, and playback behavior intact.
- All new user-visible copy and accessibility descriptions are Vietnamese Android string resources.
- Retain surviving routes and test tags unless a task explicitly removes a Shorts-only tag.
- Interactive controls expose at least a 48dp touch target.
- Do not commit during execution unless the user explicitly authorizes commits.

## File Map

- `navigation/Routes.kt`, `navigation/HPreNavHost.kt`, `navigation/RootScaffold.kt`: route graph, root chrome, top-level inset ownership.
- `di/AppContainer.kt`: application dependencies; dedicated Shorts wiring is removed here.
- `ui/shorts/*`, `repository/ShortsFeedRepository.kt`: deleted dedicated Shorts implementation.
- `res/values/strings.xml`: single source of Vietnamese UI copy and accessibility descriptions.
- `res/values/themes.xml`, `AndroidManifest.xml`, `MainActivity.kt`: Compose host theme and edge-to-edge setup.
- `core/designsystem/Color.kt`, `Type.kt`, `HPreTheme.kt`, new `Layout.kt`: shared Material 3 color, typography, spacing, shape, and touch-target values.
- `ui/common/VideoCard.kt`, `ui/player/MiniPlayer.kt`: shared browsing and playback chrome.
- `ui/home`, `ui/search`, `ui/channel`, `ui/library`: browsing and collection screen normalization.
- `ui/watch`, `settings`: playback/detail controls, nested app bars, resource migration, and inset normalization.

---

### Task 1: Remove the Dedicated Shorts Feature

**Files:**
- Modify: `app/src/androidTest/java/com/hpre/app/navigation/NavigationFlowTest.kt:141-168`
- Modify: `app/src/main/java/com/hpre/app/navigation/Routes.kt:75-80`
- Modify: `app/src/main/java/com/hpre/app/navigation/RootScaffold.kt:53-110`
- Modify: `app/src/main/java/com/hpre/app/navigation/HPreNavHost.kt:88-98`
- Modify: `app/src/main/java/com/hpre/app/di/AppContainer.kt`
- Delete: `app/src/main/java/com/hpre/app/ui/shorts/ShortsScreen.kt`
- Delete: `app/src/main/java/com/hpre/app/ui/shorts/ShortsViewModel.kt`
- Delete: `app/src/main/java/com/hpre/app/repository/ShortsFeedRepository.kt`
- Delete: `app/src/test/java/com/hpre/app/ui/shorts/ShortsViewModelTest.kt`
- Delete: `app/src/test/java/com/hpre/app/repository/ShortsFeedRepositoryTest.kt`
- Delete: `app/src/androidTest/java/com/hpre/app/ui/shorts/ShortsScreenTest.kt`
- Test: `app/src/test/java/com/hpre/app/navigation/RoutesTest.kt`

**Interfaces:**
- Consumes: existing `Screen`, `BottomNavItem`, `AppContainer`, and Navigation Compose graph.
- Produces: a graph with exactly `Home`, `Subscriptions`, and `Library` as top-level destinations; no dedicated Shorts classes or dependency.

- [ ] **Step 1: Change the navigation test first**

Replace the Shorts branch in `bottom_nav_switches_between_home_and_tabs` with an absence assertion and keep traversal of all surviving tabs:

```kotlin
composeTestRule.onNodeWithTag("home_screen").assertIsDisplayed()
composeTestRule.onNodeWithTag("bottom_nav_shorts").assertDoesNotExist()

composeTestRule.onNodeWithTag("bottom_nav_subscriptions").performClick()
composeTestRule.onNodeWithTag("subscriptions_screen").assertIsDisplayed()

composeTestRule.onNodeWithTag("bottom_nav_library").performClick()
composeTestRule.onNodeWithTag("library_screen").assertIsDisplayed()

composeTestRule.onNodeWithTag("bottom_nav_home").performClick()
composeTestRule.onNodeWithTag("home_screen").assertIsDisplayed()
```

Add the required `assertDoesNotExist` import. Do not remove the route test proving that `NewPipeMappers` accepts `https://www.youtube.com/shorts/abc12345678`.

- [ ] **Step 2: Run the changed test and record the expected failure**

Run:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.hpre.app.navigation.NavigationFlowTest
```

Expected before implementation: FAIL because `bottom_nav_shorts` still exists. If no device is connected, record the test as not run and continue with the compile-time red phase in Step 3.

- [ ] **Step 3: Remove the route and root navigation item**

Delete `Screen.Shorts`, `BottomNavItem.Shorts`, its icon imports, its entry in `bottomNavItems`, and its entry in `isTopLevelDestination`. The resulting collections must be:

```kotlin
private val bottomNavItems = listOf(
    BottomNavItem.Home,
    BottomNavItem.Subscriptions,
    BottomNavItem.Library
)

val isTopLevelDestination = currentRoute in listOf(
    Screen.Home.route,
    Screen.Subscriptions.route,
    Screen.Library.route
)
```

Delete the Shorts `composable` block from `HPreNavHost`.

- [ ] **Step 4: Remove dedicated dependency wiring and implementation**

Delete the `ShortsFeedRepository` import, interface property, implementation property, and constructor calls from `AppContainer.kt`. Delete both files under `ui/shorts`, the repository, and their three dedicated test files listed above.

Do not remove any of these provider-level interfaces or mappings:

```kotlin
val supportsShorts: Boolean
path.startsWith("/shorts/")
ChannelDetails.shorts
```

- [ ] **Step 5: Verify compilation, unit tests, and source boundaries**

Run:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

Expected: PASS. Then search `app/src` for `ShortsViewModel`, `ShortsScreen`, `ShortsFeedRepository`, `Screen.Shorts`, and `bottom_nav_shorts`; expected: no remaining references except the intentional absence assertion in `NavigationFlowTest`.

- [ ] **Step 6: Commit only when explicitly authorized**

Stage only the files listed in this task and use:

```powershell
git commit -m "refactor: remove dedicated shorts mode"
```

Otherwise leave the verified changes uncommitted.

---

### Task 2: Establish Vietnamese Resources and Root Chrome

**Files:**
- Create: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/AndroidManifest.xml:11-24`
- Modify: `app/src/main/java/com/hpre/app/navigation/RootScaffold.kt`
- Modify: `app/src/main/java/com/hpre/app/ui/home/HomeScreen.kt`
- Modify: `app/src/androidTest/java/com/hpre/app/navigation/NavigationFlowTest.kt`
- Test: `app/src/test/java/com/hpre/app/BrandIdentityTest.kt`

**Interfaces:**
- Consumes: Android `stringResource`, existing route and test-tag names.
- Produces: stable resource names including `app_name`, `nav_home`, `nav_subscriptions`, `nav_library`, `action_search`, `action_settings`, `home_empty`, `status_playing`, and `status_paused` for later tasks.

- [ ] **Step 1: Add failing resource and navigation-label assertions**

Add resource checks to `BrandIdentityTest` by reading `app/src/main/res/values/strings.xml` in the same manner as its existing project-file assertions:

```kotlin
assertTrue(stringsXml.contains("<string name=\"app_name\">HPre</string>"))
assertTrue(stringsXml.contains("<string name=\"nav_home\">Trang chủ</string>"))
assertTrue(stringsXml.contains("<string name=\"nav_subscriptions\">Kênh đăng ký</string>"))
assertTrue(stringsXml.contains("<string name=\"nav_library\">Thư viện</string>"))
```

In `NavigationFlowTest`, assert the three labels after the root loads:

```kotlin
composeTestRule.onNodeWithText("Trang chủ").assertIsDisplayed()
composeTestRule.onNodeWithText("Kênh đăng ký").assertIsDisplayed()
composeTestRule.onNodeWithText("Thư viện").assertIsDisplayed()
```

- [ ] **Step 2: Run the unit test and verify it fails**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.hpre.app.BrandIdentityTest
```

Expected: FAIL because `strings.xml` and the required resource entries do not exist.

- [ ] **Step 3: Create the initial Vietnamese resource contract**

Create `strings.xml` with the complete set needed by this and later tasks. At minimum include:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">HPre</string>
    <string name="nav_home">Trang chủ</string>
    <string name="nav_subscriptions">Kênh đăng ký</string>
    <string name="nav_library">Thư viện</string>
    <string name="action_search">Tìm kiếm</string>
    <string name="action_settings">Cài đặt</string>
    <string name="action_back">Quay lại</string>
    <string name="action_retry">Thử lại</string>
    <string name="action_close">Đóng</string>
    <string name="action_play">Phát</string>
    <string name="action_pause">Tạm dừng</string>
    <string name="action_cancel">Hủy</string>
    <string name="action_create">Tạo</string>
    <string name="action_see_all">Xem tất cả</string>
    <string name="home_empty">Không có video phù hợp</string>
    <string name="status_playing">Đang phát</string>
    <string name="status_paused">Đã tạm dừng</string>
    <string name="video_fallback_title">Video HPre</string>
    <string name="video_live">TRỰC TIẾP</string>
</resources>
```

Add screen-specific strings in Tasks 5 and 6 to this same file; do not create constants in Kotlin for visible copy.

- [ ] **Step 4: Convert root chrome and Home copy to resources**

Change `BottomNavItem.title` to `@StringRes val titleRes: Int`, map the three items to `R.string.nav_*`, and resolve labels in composition:

```kotlin
val title = stringResource(item.titleRes)
Icon(
    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
    contentDescription = title
)
Text(text = title)
```

Use `R.string.action_search` and `R.string.action_settings` for root actions and `R.string.home_empty` in `HomeScreen`. Change the manifest label to `android:label="@string/app_name"`.

- [ ] **Step 5: Run focused tests and build**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.hpre.app.BrandIdentityTest
.\gradlew.bat assembleDebug
```

Expected: PASS. Run the focused `NavigationFlowTest` when a device exists; expected: three Vietnamese labels are displayed and Shorts is absent.

- [ ] **Step 6: Commit only when explicitly authorized**

```powershell
git commit -m "feat: add Vietnamese UI resources"
```

Otherwise leave changes uncommitted.

---

### Task 3: Implement Edge-to-Edge and Explicit Inset Ownership

**Files:**
- Create: `app/src/main/res/values/themes.xml`
- Modify: `app/src/main/AndroidManifest.xml:18-24`
- Modify: `app/src/main/java/com/hpre/app/MainActivity.kt:29-79`
- Modify: `app/src/main/java/com/hpre/app/navigation/RootScaffold.kt:136-241`
- Modify: `app/src/main/java/com/hpre/app/ui/search/SearchScreen.kt`
- Test: `app/src/test/java/com/hpre/app/BuildConfigurationTest.kt`
- Test: `app/src/androidTest/java/com/hpre/app/ui/search/SearchScreenTest.kt`

**Interfaces:**
- Consumes: `enableEdgeToEdge`, `SystemBarStyle`, Compose `WindowInsets`, and the active `darkTheme` boolean.
- Produces: project theme `Theme.HPre`, root-owned status/navigation bar insets, and IME-safe Search content.

- [ ] **Step 1: Add failing host-configuration assertions**

Extend `BuildConfigurationTest` using its existing source-file reading pattern:

```kotlin
assertTrue(mainActivitySource.contains("enableEdgeToEdge"))
assertTrue(manifestSource.contains("@style/Theme.HPre"))
assertTrue(themesSource.contains("name=\"Theme.HPre\""))
```

Add an instrumentation assertion to `SearchScreenTest` that focuses the query input, opens the keyboard, and verifies the search input and result-list container remain displayed:

```kotlin
composeRule.onNodeWithTag("search_input").performClick()
composeRule.onNodeWithTag("search_input").performTextInput("nhạc")
composeRule.onNodeWithTag("search_input").assertIsDisplayed()
composeRule.onNodeWithTag("search_screen").assertIsDisplayed()
```

- [ ] **Step 2: Run the unit red phase**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.hpre.app.BuildConfigurationTest
```

Expected: FAIL because edge-to-edge and `Theme.HPre` do not exist.

- [ ] **Step 3: Add the Compose host theme**

Create:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.HPre" parent="android:style/Theme.Material.Light.NoActionBar">
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:windowLightNavigationBar">false</item>
        <item name="android:windowActionModeOverlay">true</item>
        <item name="android:windowNoTitle">true</item>
    </style>
</resources>
```

Point both application and activity themes in the manifest to `@style/Theme.HPre`.

- [ ] **Step 4: Enable edge-to-edge and update icon contrast**

Call `enableEdgeToEdge()` before `setContent`. Inside Compose, update system-bar styles when `darkTheme` changes:

```kotlin
SideEffect {
    enableEdgeToEdge(
        statusBarStyle = if (darkTheme) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        },
        navigationBarStyle = if (darkTheme) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
    )
}
```

Alias `android.graphics.Color` so it does not conflict with Compose color imports. Keep the PiP `PlayerSurface(Modifier.fillMaxSize())` branch free of padding.

- [ ] **Step 5: Assign inset ownership in `RootScaffold`**

Set the root scaffold content insets to zero and assign bar insets to their owners:

```kotlin
Scaffold(
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    topBar = {
        TopAppBar(
            windowInsets = WindowInsets.statusBars,
            // existing title and actions
        )
    },
    bottomBar = {
        Column {
            if (!isWatchScreen && hasActiveMedia) MiniPlayer(/* existing args */)
            if (isTopLevelDestination) {
                NavigationBar(windowInsets = WindowInsets.navigationBars) {
                    // existing three items
                }
            }
        }
    }
) { innerPadding ->
    HPreNavHost(modifier = Modifier.padding(innerPadding), /* existing args */)
}
```

Use the actual Material 3 overload available in the pinned Compose BOM. If `NavigationBar` does not expose `windowInsets`, apply `windowInsetsPadding(WindowInsets.navigationBars)` to it instead; use exactly one approach, not both.

- [ ] **Step 6: Make Search IME-safe**

Apply `Modifier.imePadding()` to the Search screen's content owner below its app bar. Do not add `systemBarsPadding()` because the root or nested scaffold already handles system bars.

- [ ] **Step 7: Verify host, lint, build, and device behavior**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.hpre.app.BuildConfigurationTest
.\gradlew.bat lintDebug assembleDebug
```

Expected: PASS. If a device exists, run `SearchScreenTest` and manually inspect light/dark status and navigation bar icon contrast, gesture navigation, and PiP full-bleed rendering.

- [ ] **Step 8: Commit only when explicitly authorized**

```powershell
git commit -m "feat: support edge-to-edge Android layouts"
```

Otherwise leave changes uncommitted.

---

### Task 4: Normalize the Shared Design System, Video Cards, and MiniPlayer

**Files:**
- Create: `app/src/main/java/com/hpre/app/core/designsystem/Layout.kt`
- Modify: `app/src/main/java/com/hpre/app/core/designsystem/Color.kt`
- Modify: `app/src/main/java/com/hpre/app/core/designsystem/HPreTheme.kt`
- Modify: `app/src/main/java/com/hpre/app/core/designsystem/Type.kt`
- Modify: `app/src/main/java/com/hpre/app/ui/common/VideoCard.kt`
- Modify: `app/src/main/java/com/hpre/app/ui/player/MiniPlayer.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/androidTest/java/com/hpre/app/ui/player/MiniPlayerTest.kt`
- Test: `app/src/androidTest/java/com/hpre/app/navigation/HomeToWatchNavigationTest.kt`

**Interfaces:**
- Consumes: Material theme roles, existing `VideoSummary`, `VideoFormat`, and `PlayerController` state.
- Produces: `HPreSpacing`, `HPreShapes`, `MinimumTouchTarget`, stable VideoCard placeholder/error visuals, and localized MiniPlayer semantics.

- [ ] **Step 1: Add failing MiniPlayer UI assertions**

Update `MiniPlayerTest` to assert localized state and 48dp actions:

```kotlin
composeRule.onNodeWithText("Đang phát").assertIsDisplayed()
composeRule.onNodeWithContentDescription("Tạm dừng").assertTouchHeightIsAtLeast(48.dp)
composeRule.onNodeWithContentDescription("Đóng trình phát thu nhỏ").assertTouchWidthIsAtLeast(48.dp)
```

Add `mini_player_close` to `strings.xml` only during implementation so the first run is red.

- [ ] **Step 2: Run the focused red phase**

Run on a connected device:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.hpre.app.ui.player.MiniPlayerTest
```

Expected before implementation: FAIL on English state text and 36dp action modifiers. If no device exists, retain the failing test and use `assembleDebug` as the available validation.

- [ ] **Step 3: Add focused layout tokens**

Create `Layout.kt`:

```kotlin
package com.hpre.app.core.designsystem

import androidx.compose.ui.unit.dp

object HPreSpacing {
    val Compact = 4.dp
    val Small = 8.dp
    val Medium = 12.dp
    val Large = 16.dp
    val Section = 24.dp
}

object HPreShapes {
    val Badge = 4.dp
    val Card = 12.dp
}

val MinimumTouchTarget = 48.dp
```

Do not introduce a broad token framework or replace every `dp` value. Use these only for equivalent shared roles touched by this plan.

- [ ] **Step 4: Complete Material 3 theme roles**

Keep `HPreRed` and neutral backgrounds. Ensure light and dark schemes explicitly map `surfaceContainer`, `surfaceContainerHigh`, `surfaceContainerHighest`, `outline`, and `outlineVariant` from coherent neutral colors. Add `labelMedium` and `labelLarge` to `Typography` only if touched screens currently fall back to Material defaults; preserve the Android default font.

- [ ] **Step 5: Polish `VideoCard` without changing its contract**

Keep the signature and test tags. Use shared spacing/shapes, a stable 16:9 region, and themed placeholder/error visuals:

```kotlin
AsyncImage(
    model = ImageRequest.Builder(LocalContext.current)
        .data(video.thumbnailUrl)
        .crossfade(true)
        .build(),
    contentDescription = video.title,
    modifier = Modifier.fillMaxSize().testTag("video_thumbnail"),
    contentScale = ContentScale.Crop,
    placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest),
    error = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest)
)
```

Capture one timestamp per composition boundary:

```kotlin
val now = remember(video.key, video.publishedTimestamp) { System.currentTimeMillis() }
val ageText = VideoFormat.age(video.publishedTimestamp, now)
```

Use `R.string.video_live` for the live badge. Do not change navigation callbacks or model types.

- [ ] **Step 6: Polish and localize `MiniPlayer`**

Use a compact tonal `Surface` or `Card` with only the floating MiniPlayer elevation. Keep the progress line at 3dp. Remove `.size(36.dp)` from action `IconButton`s and use:

```kotlin
IconButton(
    onClick = { playerController.playPause() },
    modifier = Modifier
        .size(MinimumTouchTarget)
        .testTag("mini_player_play_pause_button")
) {
    Icon(
        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
        contentDescription = stringResource(
            if (state.isPlaying) R.string.action_pause else R.string.action_play
        )
    )
}
```

Add and use:

```xml
<string name="mini_player_close">Đóng trình phát thu nhỏ</string>
```

Resolve title fallback and playing/paused state from resources.

- [ ] **Step 7: Verify shared components**

Run:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug assembleDebug
```

Expected: PASS with no new Compose or resource lint errors. On a connected device, run `MiniPlayerTest` and `HomeToWatchNavigationTest`; expected: PASS with unchanged navigation tags.

- [ ] **Step 8: Commit only when explicitly authorized**

```powershell
git commit -m "style: polish shared Android UI components"
```

Otherwise leave changes uncommitted.

---

### Task 5: Polish and Localize Browsing and Library Screens

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/hpre/app/ui/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/hpre/app/ui/search/SearchScreen.kt`
- Modify: `app/src/main/java/com/hpre/app/ui/channel/ChannelScreen.kt`
- Modify: `app/src/main/java/com/hpre/app/ui/common/ErrorPane.kt`
- Modify: `app/src/main/java/com/hpre/app/ui/library/LibraryScreen.kt`
- Modify: `app/src/main/java/com/hpre/app/ui/library/SubscriptionsScreen.kt`
- Modify: `app/src/main/java/com/hpre/app/ui/library/HistoryScreen.kt`
- Modify: `app/src/main/java/com/hpre/app/ui/library/PlaylistsScreen.kt`
- Modify: `app/src/main/java/com/hpre/app/ui/library/PlaylistDetailScreen.kt`
- Test: `app/src/androidTest/java/com/hpre/app/ui/search/SearchScreenTest.kt`
- Test: `app/src/androidTest/java/com/hpre/app/ui/library/LibraryScreenTest.kt`
- Test: `app/src/androidTest/java/com/hpre/app/ui/common/ErrorPaneTest.kt`

**Interfaces:**
- Consumes: `strings.xml`, `HPreSpacing`, `HPreShapes`, `MinimumTouchTarget`, root inset contract, and existing screen/ViewModel callbacks.
- Produces: Vietnamese, consistently spaced browsing and collection screens with one app-bar owner each.

- [ ] **Step 1: Add failing Vietnamese UI assertions**

Use existing fixtures in the three instrumentation test classes and add assertions for actual visible states:

```kotlin
composeRule.onNodeWithText("Tìm kiếm").assertIsDisplayed()
composeRule.onNodeWithText("Thư viện").assertIsDisplayed()
composeRule.onNodeWithText("Xem tất cả").assertIsDisplayed()
composeRule.onNodeWithText("Thử lại").assertIsDisplayed()
```

For dialogs covered by `LibraryScreenTest`, open the existing new-playlist action and assert `Danh sách phát mới`, `Tiêu đề`, `Tạo`, and `Hủy`.

- [ ] **Step 2: Run the focused tests and verify English-copy failures**

Run on a connected device:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.hpre.app.ui.search.SearchScreenTest,com.hpre.app.ui.library.LibraryScreenTest,com.hpre.app.ui.common.ErrorPaneTest
```

Expected before implementation: FAIL for English labels such as `See all`, `New Playlist`, `Create`, `Cancel`, or `Retry`. If no device is connected, retain the red tests and proceed to resource/build validation.

- [ ] **Step 3: Add exact screen resources**

Add resources for every visible literal in these files. Include at least:

```xml
<string name="screen_search">Tìm kiếm</string>
<string name="screen_library">Thư viện</string>
<string name="screen_subscriptions">Kênh đăng ký</string>
<string name="screen_history">Lịch sử xem</string>
<string name="screen_playlists">Danh sách phát</string>
<string name="playlist_new">Danh sách phát mới</string>
<string name="playlist_title">Tiêu đề</string>
<string name="history_clear">Xóa lịch sử</string>
<string name="subscription_local">Đang theo dõi trên thiết bị</string>
<string name="comments_load_more">Tải thêm</string>
```

Use parameter resources such as `<string name="channel_subscribers">%1$s người đăng ký</string>` rather than concatenating translated fragments.

- [ ] **Step 4: Migrate visible strings and accessibility descriptions**

Replace hardcoded composable literals with `stringResource`. Keep proper names and provider data unchanged. Decorative icons stay `contentDescription = null`; interactive back, add, clear, create, cancel, retry, and search actions use localized descriptions.

Do not translate test tags, routes, logs, quality labels, or data received from providers.

- [ ] **Step 5: Normalize app bars, padding, shape, and touch targets**

Apply these rules to every file in this task:

```kotlin
Modifier.padding(horizontal = HPreSpacing.Large)
```

for normal screen sections, `HPreSpacing.Medium` for dense feed content, `HPreShapes.Card` for card/thumbnail corners, and `MinimumTouchTarget` for clickable icon controls. Preserve intentionally horizontal `LazyRow`/chip scrolling.

Top-level Home, Subscriptions, and Library do not add another `TopAppBar` because `RootScaffold` owns it. Remove the local `Scaffold`/`TopAppBar` from `SubscriptionsScreen` and retain its `onNavigateBack` parameter only if another current caller needs it; otherwise remove that parameter and update all call sites/tests in this task. Nested Search, Channel, History, Playlists, and Playlist Detail keep exactly one back app bar. Apply each scaffold's `innerPadding` once.

Give every `LazyColumn.items` call a stable existing domain key (`video.key`, playlist ID, subscription key, or history key). Do not invent index keys when a domain key exists.

- [ ] **Step 6: Verify tests, lint, and build**

Run:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug assembleDebug
```

Expected: PASS. Search only the files in this task for quoted user-visible English literals; expected: none except proper names, test tags, data fallbacks explicitly justified in code, or developer-facing strings. Run the focused instrumentation classes when a device exists.

- [ ] **Step 7: Commit only when explicitly authorized**

```powershell
git commit -m "style: refine Vietnamese browsing screens"
```

Otherwise leave changes uncommitted.

---

### Task 6: Polish Watch and Settings, Then Run Full Verification

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/hpre/app/ui/watch/WatchScreen.kt`
- Modify: `app/src/main/java/com/hpre/app/ui/watch/PlayerControls.kt`
- Modify: `app/src/main/java/com/hpre/app/ui/watch/PlayerSurface.kt`
- Modify: `app/src/main/java/com/hpre/app/ui/watch/CommentsSection.kt`
- Modify: `app/src/main/java/com/hpre/app/ui/watch/RelatedVideosSection.kt`
- Modify: `app/src/main/java/com/hpre/app/settings/SettingsScreen.kt`
- Modify: `app/src/androidTest/java/com/hpre/app/ui/watch/WatchScreenTest.kt`
- Modify: `app/src/androidTest/java/com/hpre/app/ui/watch/WatchRecreationTest.kt`
- Test: `app/src/test/java/com/hpre/app/player/PlaybackPolicyTest.kt`
- Test: `app/src/test/java/com/hpre/app/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: shared resources/tokens, existing `WatchViewModel`, `PlayerController`, fullscreen handler, settings state, and root inset contract.
- Produces: localized and polished Watch/Settings UI with unchanged playback, fullscreen, PiP, and settings behavior.

- [ ] **Step 1: Add failing localized-control assertions**

Extend `WatchScreenTest` using its existing fake state:

```kotlin
composeRule.onNodeWithContentDescription("Tua lùi 10 giây").assertExists()
composeRule.onNodeWithContentDescription("Tua tới 10 giây").assertExists()
composeRule.onNodeWithContentDescription("Tạm dừng").assertExists()
```

When the quality and speed controls are visible, assert localized descriptions with values:

```kotlin
composeRule.onNodeWithContentDescription("Tốc độ phát (1.0x)").assertExists()
composeRule.onNodeWithContentDescription("Chất lượng (Tự động)").assertExists()
```

- [ ] **Step 2: Run the focused red phase**

Run on a connected device:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.hpre.app.ui.watch.WatchScreenTest
```

Expected before implementation: FAIL because control descriptions are English. If no device exists, retain the red assertions and use compile/lint validation below.

- [ ] **Step 3: Add Watch and Settings resources**

Add every visible string from the files in this task. Required examples:

```xml
<string name="screen_settings">Cài đặt</string>
<string name="screen_comments">Bình luận</string>
<string name="action_rewind_10">Tua lùi 10 giây</string>
<string name="action_forward_10">Tua tới 10 giây</string>
<string name="action_enter_fullscreen">Mở toàn màn hình</string>
<string name="action_exit_fullscreen">Thoát toàn màn hình</string>
<string name="playback_speed">Tốc độ phát (%1$sx)</string>
<string name="playback_quality">Chất lượng (%1$s)</string>
<string name="quality_auto">Tự động</string>
```

Add Vietnamese resources for all Settings section names, choices, summaries, dialogs, and actions. Do not translate persisted enum values; map them to resource labels at rendering time.

- [ ] **Step 4: Migrate Watch copy and normalize controls**

Replace literals in PlayerControls, CommentsSection, RelatedVideosSection, and WatchScreen with resources. Ensure every `IconButton` has at least a 48dp modifier or relies on the default Material minimum without overriding it smaller. Keep the thin visual seek track inside its existing 48dp interaction region.

Do not change calls to `playPause`, seek methods, speed/quality selection, fullscreen handler, related-video navigation, share/save actions, or ViewModel loading.

- [ ] **Step 5: Normalize Watch insets without changing PiP/fullscreen**

Keep the 16:9 player full-width. Back and fullscreen overlays account for display cutouts in full-screen mode using `WindowInsets.displayCutout` or safe-drawing padding at the fullscreen overlay owner. Do not apply root content padding to `PlayerSurface`; PiP and fullscreen video remain full-bleed.

Apply normal horizontal screen margins only below the player to metadata, action chips, channel information, comments, and related content.

- [ ] **Step 6: Migrate and polish Settings**

Keep Settings as a nested screen with one back app bar. Apply scaffold `innerPadding` once, use shared section spacing and typography, and keep each row's full width clickable with a minimum 48dp height. Preserve all existing setting keys, defaults, callbacks, and dialogs.

- [ ] **Step 7: Run complete automated verification**

Run exactly:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Expected: all tasks complete successfully with no new lint errors. If a device or emulator exists, run:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

Expected: PASS. If unavailable, report connected tests as not run; do not infer success.

- [ ] **Step 8: Run targeted regression and source scans**

Confirm unit coverage for playback and settings still passes:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.hpre.app.player.PlaybackPolicyTest --tests com.hpre.app.settings.SettingsViewModelTest
```

Search production and test source for deleted dedicated Shorts symbols; expected: none except the negative navigation assertion. Search modified composables for hardcoded English visible strings; review every match rather than deleting provider data, technical labels, test tags, or developer-facing text.

- [ ] **Step 9: Perform manual phone checks when hardware is available**

Verify on a narrow phone configuration:

1. Light and dark system-bar icons have correct contrast.
2. Status bar, cutout, gesture bar, and three-button navigation do not cover content.
3. Search remains usable while the IME is open.
4. The three bottom labels fit at default and increased font scale.
5. MiniPlayer and bottom navigation do not hide the final list item.
6. Watch portrait, fullscreen rotation, playback controls, and PiP behave as before.

Record unavailable checks as `Not run` with the reason.

- [ ] **Step 10: Commit only when explicitly authorized**

```powershell
git commit -m "style: complete Android phone UI polish"
```

Otherwise leave the fully verified worktree uncommitted.
