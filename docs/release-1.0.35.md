# HPre 1.0.35

Version: 1.0.35 (36). Branch: fix/hpre-1.0.35-reliability.
Reliability release on a dedicated branch; no main merge.

## Changes

- Service-owned playback recovery, observer generation updates, asynchronous prepare results and terminal failures.
- Quality-result request guards, adaptive Auto cap reset, autoplay transport invalidation and deferred queue commit.
- Source fallback preserves pause and speed; stale restore failures do not clear a newer request's snapshot.
- Search pagination uses committed result identity; late metadata cannot restore the startup thumbnail.
- Accessibility action restores controls; recommended timeout and lifecycle-aware progress polling.
- Same-key channel loads are retained; bounded playlist picker; subscription-aware library detail collection.
- Playlist ordering, aggregate list counts and transactional duplicate checks.
- Subscription-key refresh and manual refresh; serialized refreshes; Home and playlist-save errors are visible.
- Continuation URL and ID round-trip against pinned NewPipeExtractor v0.26.5.
- Serialized history mutations, extraction subscriber cleanup, settings readiness fallback, bounded HTTP bodies and cancellable update requests.

## Verification

2026-09-06, Windows, JDK 17, Android API 35 x86_64 emulator.

`gradlew.bat --no-daemon :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease`

- 836 debug and 836 release unit tests, zero failures, zero ignored in each variant.
- Lint: zero errors, 82 warnings. Media3 transport callback also produces deprecation compiler warnings.
- Debug, instrumentation and signed/minified release APK builds succeeded.
- Full standard connected report: 114 tests, zero failures, one skipped upstream smoke (no query in standard run).
- Separate LivePlaybackGateTest and UpstreamSmokeTest run: two tests passed with an explicit query. Live gate verifies rendered video, VOD seeking, quality switching and continued progress.
- Unsupported asynchronous selection terminates with an error in the device regression test.
- Existing emulator installs were preserved. A dedicated HPre1035Verification AVD was created for testing.
- Final upgrade script passed on emulator-5558: official root 1.0.34/code35 to candidate 1.0.35/code36.
- Baseline and candidate actual certificate SHA-256: `40D629544B6C12014AD0A03BEDC2AFFE81E6DF8EF88F693F76365A6E95D49D47`.
- Background playback disabled marker survived `adb install -r`; Settings smoke passed. No uninstall between baseline marker and candidate install.
- APK: `HPre-v1.0.35-release.apk` in the workspace root.
- APK SHA-256: `3AA7460E54F7049E072D7663FF5C3A5C4A121AF03EBB159EBE8F69FD746011DE`.

## Limits And Review Focus

No measured startup-speed claims. Live network results reflect this test run, not a guarantee of future upstream availability.
Upgrade preservation evidence covers the settings marker, not exhaustive history/playlist database contents.
Manager review findings addressed: release-report brand violation, continuation-ID request identity, READY-gated autoplay commit, and shared-flow persistence assertions. Prepare/quality command success acknowledges source acceptance, not first-frame rendering; player events report subsequent playback errors.
Successful early stream subscribers intentionally allow the existing metadata loader to finish for cache reuse; cancelled remaining subscribers release ownership and cancel upstream work. No new prefetch or cache capacity was added.
Not every new behavior has a dedicated red-before-green regression. Existing device and unit coverage does not establish every concurrency interleaving.
