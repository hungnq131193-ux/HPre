# HPre Rebrand and Playback Improvements Design

**Date:** 2026-08-26
**Status:** Approved

## Goal

Rename the Android application and all internal identifiers from FlowTube to HPre, complete useful Shorts playback, personalize Home recommendations from local activity, make the Watch screen fit phones, reduce perceived playback startup time, and render a thinner seek bar.

## Delivery Strategy

Implement the work as independently verifiable vertical slices:

1. Rename the complete application from FlowTube to HPre.
2. Improve Watch layout, controls, and playback startup behavior.
3. Add local recommendation ranking to Home.
4. Complete Shorts discovery and playback.

Each slice must leave the application buildable and tested. This avoids a long-lived state where repositories, navigation, player code, and UI use incompatible models.

## Complete Rebrand

The rename intentionally changes the Android identity and does not preserve upgrade compatibility with an installed FlowTube build.

- Change namespace and application ID from `com.flowtube.app` to `com.hpre.app`.
- Move production, unit-test, and instrumentation-test packages to `com.hpre.app`.
- Rename `FlowTube*` classes and symbols to `HPre*`, including the application, playback service, database, test runner, theme, and related test fixtures.
- Change visible labels, notification/channel names, accessibility text, test tags where brand-specific, and documentation to HPre.
- Rename Room schema paths and schema identity to match `HPreDatabase` while preserving the current schema version for the new application identity.
- Update manifest component names, Gradle configuration, package declarations, imports, ProGuard rules, test commands, evidence templates, and design/plan documentation.
- Remove every case-insensitive occurrence of `flowtube` from tracked source, tests, configuration, schemas, and documentation.

Generated build output and persisted brainstorming/session artifacts are not product source and should be removed or excluded rather than manually rewritten. The final repository scan applies to tracked project files after generated output is cleaned.

The repository working-directory name is not tracked project content and is out of scope.

### Release Evidence Artifacts

The recorded release evidence under `docs/evidence/`, `docs/release-evidence.md`, and `ReleaseEvidenceAuditTest` describes a FlowTube build verified on one specific emulator run. That evidence is self-verifying: the audit test asserts exact FlowTube identifiers and the SHA-256 hashes of the evidence files themselves. Rewriting those identifiers invalidates the hashes, while recomputing the hashes would fabricate provenance for a run that never happened under the HPre identity.

The rebrand therefore deletes the old release evidence artifacts and their audit test. HPre release evidence must be generated from a real verification run before distribution; no historical evidence value is edited, recomputed, or invented.

## Watch Screen and Controls

The portrait Watch screen uses the approved mobile-first layout:

- Place the 16:9 player at the top without the current `Watch` top app bar. Back navigation remains available as an overlay control on the player.
- Show title and view metadata immediately below the player.
- Render supported actions as horizontally scrollable chips. Keep local save and share behavior; do not present unsupported remote mutations as working actions.
- Combine channel information and collapsed description in a compact card.
- Place comments and personalized/related videos earlier in the vertical flow.
- Retain loading, retry, fullscreen, picture-in-picture, speed, quality, seek by ten seconds, and MediaSession behavior.
- Support system insets and narrow phone widths without clipped controls or horizontal layout overflow.

The visible seek track becomes thin, replacing the current visually heavy `24.dp` slider treatment. Its touch target remains at least 48 dp high, and progress semantics continue to support accessibility and Compose tests.

## Playback Startup

Playback favors time-to-first-frame over initial maximum quality:

- Prefer a valid progressive stream containing both audio and video at a moderate quality.
- Use a deterministic moderate-quality ceiling from the existing available stream set; the initial target is 720p or the closest lower progressive stream.
- Preserve manual quality selection, including higher qualities after playback starts.
- Fall back to compatible separately encoded video and audio only when no suitable progressive stream is available.
- Start player preparation as soon as stream information is ready. Related videos and comments load concurrently and never block player preparation.
- Reuse the pooled network client, Media3 data-source factory, metadata cache, and single MediaSession. Do not add preloading infrastructure or another player.

Success is measured comparatively on the same device and network by instrumenting request/preparation milestones before and after the change. No absolute startup-time guarantee is made because extractor and upstream response times are outside application control.

## Local Recommendations

Home recommendations use only existing local Room data and ordinary provider searches. No AI service, backend, analytics, remote profile, or new tracking identifier is added.

### Inputs

- Recent normalized search-history queries.
- Recent watch-history titles and channel names.
- Repeatedly watched channels.

### Flow

1. Select a small bounded set of recent search terms and watch-derived terms.
2. Query through `CatalogRepository`, retaining its cache and in-flight request deduplication.
3. Merge results by stable content key.
4. Exclude recently watched items when alternatives exist.
5. Rank candidates with a deterministic local score based on search recency, title-term match, and watched-channel frequency.
6. Fill insufficient results with trending content so Home remains useful for new users and partial provider failures.

Recommendation work is bounded to the Home feed. Clearing local search and watch history resets personalization. Individual query failures do not fail the whole feed when cached, other-query, or trending content is available.

## Shorts

Shorts discovery derives a bounded candidate feed from recent searches and watch-history topics because the current provider does not reliably expose a semantic Shorts feed.

- Search recent local topics and merge the results through existing repositories.
- For users without history, use a small fixed set of general short-video queries.
- Keep videos with known duration of at most three minutes.
- Prefer portrait thumbnails when reliable dimensions are available; duration remains the required filter.
- Deduplicate by stable content key and retain an honest empty/error state if no valid candidates exist.
- Use the existing vertical pager with one page before and after the active page retained.
- Reuse the application's single MediaSession/player. Page activation hands the selected content to that player; no player is created per page.
- Autoplay the active page. Leaving Shorts follows the existing navigation/playback policy rather than introducing a second lifecycle policy.
- Show title, channel, duration, play/pause, share, and local save. Do not display fake remote likes or unsupported source mutations.

## Data and Privacy Boundaries

- Personalization data stays in the existing local database.
- The provider receives only bounded individual search queries equivalent to normal searches, not a bulk history payload or user profile.
- Existing history deletion is the reset mechanism; no additional profile store is introduced.
- Existing privacy, access-control, DRM, age, and geographic restrictions remain unchanged.

## Error Handling

- Rebrand failures are compile-time or scan failures and block completion.
- Recommendation and Shorts requests preserve cancellation and map provider errors through existing `AppResult`/`AppError` paths.
- Partial recommendation request failures degrade to remaining candidates or trending.
- Shorts with no qualifying candidates show Empty. Temporary network or provider failures show Error with Retry. A provider response that cannot supply the required candidate data shows Unavailable.
- Playback keeps current stream recovery and retry behavior. Progressive preference must not remove the separate audio/video fallback.

## Testing and Acceptance

- Build and run the full unit-test suite after the package rename.
- Scan tracked source, tests, manifests, Gradle files, Room schemas, and documentation case-insensitively; no `FlowTube`, `flowtube`, or `com.flowtube.app` remains.
- Test rebranded application ID, manifest components, Room schema location, MediaSession service, notification identity, and navigation routes.
- Test recommendation ranking for recency, title matching, channel affinity, deduplication, recently watched exclusion, partial failure, and trending fallback.
- Test Shorts filtering at the three-minute boundary, deduplication, empty fallback, active-page handoff, and single-player ownership.
- Test stream selection preference for a progressive stream at or below 720p and separate audio/video fallback when progressive playback is unavailable.
- Add phone-width Compose coverage for the approved Watch hierarchy, back overlay, chip actions, and absence of horizontal clipping.
- Verify the seek bar has a thin visible track, a minimum 48 dp interaction area, and working progress semantics.
- Run existing Watch, Shorts, fullscreen, picture-in-picture, MediaSession, and playback instrumentation tests when an emulator is available.
- Record comparable playback preparation milestones on the same device/network; treat improvement as evidence, not a hard service-level promise.

## Out of Scope

- Migration or shared data with the old `com.flowtube.app` installation.
- Remote accounts, cloud synchronization, analytics, recommendation AI, or a backend.
- Guaranteed semantic Shorts classification from an upstream source that does not expose it reliably.
- Multiple concurrent players, per-card players, or speculative video preloading.
- Absolute playback startup latency guarantees.
