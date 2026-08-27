# HPre Android UI Polish and Shorts Removal Design

**Date:** 2026-08-26
**Status:** Approved
**Scope:** Android phone layout, shared visual system, Vietnamese UI, and complete removal of the Shorts feature

## Goal

Make HPre feel deliberate and comfortable on Android phones without replacing its
existing identity or changing playback behavior. The app will keep its red HPre
accent, adopt consistent Material 3 spacing and surfaces, handle system insets
correctly, use Vietnamese throughout the user interface, and remove the Shorts
mode and its dead dependencies completely.

This design supersedes the Shorts sections of
`2026-08-26-hpre-improvements-design.md`. It does not remove support for parsing
YouTube `/shorts/` URLs or short videos received as ordinary provider content;
those remain playable through the standard Watch flow.

## Success Criteria

- No Shorts destination, navigation item, screen, view model, feed repository,
  container dependency, or Shorts-only test remains.
- Bottom navigation contains exactly three destinations: Trang chủ, Kênh đăng
  ký, and Thư viện.
- Content does not overlap the status bar, display cutout, gesture navigation
  area, or on-screen keyboard on supported Android phones.
- Shared browsing UI uses one spacing, shape, color, and type hierarchy instead
  of screen-specific approximations.
- User-visible application labels and accessibility descriptions are Vietnamese
  and are stored in Android string resources.
- Existing Home, Search, Watch, Channel, subscription, library, playlist,
  settings, MiniPlayer, PiP, and playback behavior remain functional.
- Unit tests, lint, and the debug build pass. Device-only behavior is reported as
  unverified when no emulator or device is available.

## Non-Goals

- A new product identity, logo, font dependency, or dynamic-color system.
- Replacing the red HPre palette with Material You wallpaper colors.
- A redesign of data flow, repositories, playback, recommendations, or
  extraction.
- New animations, image preloading, video preloading, accounts, or remote
  features.
- Removing upstream model fields such as channel `shorts`, provider capability
  declarations such as `supportsShorts`, or URL parsing needed to handle content
  supplied by external services. These are provider compatibility concerns, not
  the removed app mode.
- Tablet or desktop-specific navigation. The layout must remain usable on wider
  configurations, but this slice is optimized for Android phones.

## 1. Complete Shorts Mode Removal

### Product surface

Remove `BottomNavItem.Shorts` and `Screen.Shorts`. The top-level destination
set contains only Home, Subscriptions, and Library. Remove the Shorts composable
from `HPreNavHost`; no hidden route is retained for backward compatibility
because routes are internal and no persisted navigation contract requires it.

The three remaining navigation items use Vietnamese labels:

| Route | Label |
|---|---|
| `home` | Trang chủ |
| `subscriptions` | Kênh đăng ký |
| `library` | Thư viện |

### Feature code and wiring

Delete the dedicated `ui/shorts` package and `ShortsFeedRepository`. Remove
`shortsFeedRepository` from `AppContainer` and its implementation. Delete tests
whose only subject is that screen, view model, repository, or navigation tab,
and update broader navigation tests to assert the three-tab structure instead.

Do not broadly search-and-delete every occurrence of the word `shorts`.
`NewPipeMappers` must continue accepting `/shorts/<id>` URLs, and provider
models may continue exposing short-form items to Channel or Watch as ordinary
videos. This preserves valid input compatibility while removing only the
standalone mode.

## 2. Android Window and Insets

`MainActivity` enables edge-to-edge before Compose content is installed. System
bar icon appearance follows the active light or dark theme. The Android theme
uses a project style suitable for a Compose activity rather than relying on the
raw platform `Theme.Material.Light.NoActionBar` declaration.

`RootScaffold` is the single owner of top-level system-bar insets:

- The top app bar consumes status-bar and display-cutout insets.
- The bottom navigation consumes navigation-bar insets.
- The MiniPlayer sits directly above bottom navigation without adding a second
  navigation-bar inset.
- `HPreNavHost` receives only Scaffold content padding; destinations must not
  reapply the same system-bar padding.

Full-screen Watch playback remains responsible for its own overlay-safe
controls. Search and text-entry destinations apply IME insets so the keyboard
does not cover focused fields or actions. Insets are handled at the owning
container, never with fixed status-bar or navigation-bar spacer heights.

PiP remains full-bleed and does not render `RootScaffold`, so edge-to-edge
changes must not introduce padding around `PlayerSurface` in PiP.

## 3. Material 3 Visual System

### Color

Keep `HPreRed` as the primary accent. Red is reserved for the brand mark,
selected emphasis, playback progress, and destructive/error states where
appropriate. Large backgrounds remain neutral so the interface does not feel
visually noisy.

Complete both light and dark Material 3 color schemes with coherent surface
container roles. Components use `MaterialTheme.colorScheme` instead of ad hoc
black, white, or gray values except where video overlays require a translucent
black scrim for contrast.

### Spacing and shape

Use a small shared token set instead of unrelated values:

- 4dp for compact internal gaps.
- 8dp for related control spacing.
- 12dp for compact horizontal content padding and card corners.
- 16dp for normal screen margins and section separation.
- 24dp for major section separation.

Interactive targets are at least 48dp even when their visible icon is smaller.
Equivalent elements use equivalent shapes: thumbnails and standard cards use
12dp corners, compact badges use 4dp, and circular avatars remain circular.
Elevation is limited to elements that actually float, principally the
MiniPlayer; ordinary feed cards are not stacked inside unnecessary raised
containers.

### Typography

Retain the Android default font and existing dependency set. Expand the theme's
typography mapping only where needed so equivalent roles are consistent:

- Screen and section titles use title styles.
- Video titles use `titleSmall` or `titleMedium` according to layout density.
- Metadata and secondary state use body or label styles with
  `onSurfaceVariant`.
- Bottom navigation labels remain legible at normal Android font scaling and
  are not manually shrunk to fit.

Text truncation is explicit. Video titles may use two lines in feeds; metadata
and MiniPlayer titles use one line with ellipsis. The design must remain usable
with increased system font scale, favoring wrapping or scrolling over clipped
controls.

## 4. Shared Chrome and Browsing Components

### Top app bar

Keep a compact HPre identity on top-level screens, with Search and Settings as
the two actions. Use Material 3 tonal hierarchy rather than a visibly separate
opaque strip. Icons have Vietnamese accessibility descriptions and 48dp touch
targets.

### Bottom navigation

Use the Material 3 navigation bar with three evenly distributed items. The
selected indicator carries the primary accent without tinting the entire bar.
The bar must respect gesture and three-button navigation insets. Existing test
tags are retained for surviving destinations, and the Shorts tag is removed.

### Home filters

Keep the horizontally scrollable topic chips and their current behavior. Align
their horizontal inset with feed content, reduce unnecessary vertical bulk, and
use Material 3 selected/unselected container roles. The row remains visible
while feed content is loading, empty, or failed.

### Video cards

Keep the established 16:9 thumbnail, channel avatar, two-line title, metadata,
duration, and live badge. Polish is limited to consistent margins, typography,
shape, and color. Cards keep stable lazy-list keys and existing test tags.

Image loading must expose an unobtrusive themed placeholder and error state;
failures must not leave a blank or layout-shifting region. The card's entire
content remains one clear touch target.

`VideoFormat.age` continues to accept an explicit `now` argument for tests. A
single current timestamp is captured for a rendered list or remembered at the
card boundary rather than repeatedly creating inconsistent values during one
composition pass.

### MiniPlayer

Render the MiniPlayer as a compact elevated surface above bottom navigation,
with a thin progress line, thumbnail, one-line title, Vietnamese playback state,
and play/pause and close actions. Each action retains a 48dp touch target. Its
corners, colors, and spacing follow the shared tokens, and it must not obscure
the last feed item because Scaffold accounts for its occupied height.

## 5. Screen Consistency

Apply the shared spacing, surface, typography, touch-target, and string-resource
rules to existing Home, Search, Watch, Channel, Subscriptions, Library, History,
Playlists, Playlist Detail, and Settings screens. This is a normalization pass,
not a new information architecture.

Each destination has exactly one app bar owner. Top-level screens use
`RootScaffold`; nested screens own their back-navigation app bar. Nested
Scaffolds consume their own content padding once and do not duplicate padding
already supplied by the root.

Loading, empty, and error panes use consistent placement and typography. The
work does not add a new skeleton-loading framework; existing progress states
remain, styled consistently to avoid expanding scope.

Watch keeps the current mobile-first player hierarchy, controls, full-screen
behavior, related content, comments, and PiP policy. This slice may normalize
spacing, strings, and inset handling but must not alter stream selection or
playback lifecycle behavior.

## 6. Vietnamese Resources and Accessibility

Create `res/values/strings.xml` as the source of user-visible product strings.
Move navigation labels, top-bar actions, headings, loading/empty/error text,
buttons, playback states, settings labels, and accessibility descriptions out
of composables. Parameterized messages use Android format arguments.

The target UI language for this slice is Vietnamese. Proper names, technical
quality labels such as `1080p`, and the HPre brand remain unchanged. Internal
test tags, route names, enum names, logs, and developer-facing exception text do
not need translation.

Meaningful images and controls receive localized descriptions. Decorative icons
remain `contentDescription = null` to avoid duplicate TalkBack output. Selected
navigation and controls rely on Material semantics rather than encoding state
only through color.

Only the Vietnamese default resource file is required in this slice. Adding an
English locale is out of scope; centralizing strings makes that a future option.

## 7. Error Handling and Behavioral Safety

- Removing Shorts must not change normal video URL parsing or standard Watch
  navigation.
- Insets use Compose/Android APIs available at `minSdk = 26`; newer behavior is
  guarded where required.
- Image failures show stable placeholders while preserving card dimensions.
- Existing `AppResult` and retry behavior remains unchanged.
- Large fonts and narrow screens must not cause action rows to overlap; existing
  horizontal action groups scroll where necessary.
- No new runtime dependency is introduced.

## 8. Testing and Verification

### Unit tests

- Update route tests to remove `Screen.Shorts` assumptions while preserving URL
  parsing tests for `/shorts/` content links.
- Remove Shorts repository and ViewModel tests with their deleted production
  subjects.
- Keep formatting, settings, repository, and playback unit tests unchanged
  unless resource extraction requires a focused update.

### Compose instrumentation tests

- Delete the dedicated Shorts screen test.
- Update navigation tests to assert the three surviving tabs and confirm no
  Shorts navigation item is present.
- Preserve existing test tags for Home, Search, VideoCard, MiniPlayer, Library,
  and Watch behavior.
- Add focused assertions for Vietnamese navigation labels and minimum touch
  targets where Compose semantics can verify them reliably.
- Verify Search remains usable with the IME open and top/bottom chrome remains
  visible without overlap on a phone-sized configuration.

### Build checks

Run:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Run connected instrumentation tests when an emulator or Android device is
available:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

Also scan production source and tests for references to the deleted dedicated
Shorts classes and route. Remaining provider-level `shorts` references are
reviewed individually rather than treated as failures.

### Manual device checks

On at least one narrow Android phone configuration, verify:

- Light and dark themes have readable system-bar icons.
- Content clears the cutout/status bar and gesture or three-button navigation.
- The keyboard does not cover Search input or results interactions.
- Bottom navigation labels fit at default and increased font scale.
- MiniPlayer and bottom navigation do not obscure feed content.
- Watch, full-screen playback, rotation, and PiP still behave as before.

If no device is connected, these checks are explicitly reported as not run.

## Implementation Boundaries

The implementation should proceed in independently buildable slices:

1. Remove the dedicated Shorts mode and update its tests.
2. Add string resources and Vietnamese shared chrome labels.
3. Establish edge-to-edge and inset ownership.
4. Normalize shared theme tokens, navigation, VideoCard, and MiniPlayer.
5. Apply the same rules to remaining screens and run full verification.

Each slice must compile before proceeding. Unrelated repository, extractor,
database, and playback refactors are excluded even if encountered during the UI
work.
