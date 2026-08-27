# Search History and Expanded Recommendations Design

## 1. Purpose and scope

HPre will persist local search history and let users delete one query or clear all queries in a familiar video-app flow. The Home and Watch screens will use bounded, local interest signals to broaden recommendations from recent searches, watch history, familiar channels, similar topics, provider-related videos, and trending content.

All history and ranking data remains on the device. This feature adds no backend, account integration, telemetry, recommendation AI service, new Room table, or third-party dependency.

## 2. Existing foundation

The implementation extends existing components rather than creating a parallel subsystem:

- `search_history` and `watch_history` already exist in Room.
- `SearchHistoryRepository` already exposes observed recent queries plus record, delete, and clear operations.
- `RecommendationRepository` already combines recent searches, watch-history topics, watched-channel affinity, and trending videos for Home.
- `RecommendationRanker` already scores local interest signals, deduplicates candidates, and excludes watched videos when alternatives exist.
- `WatchViewModel` already retrieves provider-related videos.
- `CatalogRepository` already provides cached search, trending, and metadata operations.

The current gap is that `SearchViewModel` keeps recent queries only in memory, so existing Room persistence is not connected to the search UI. Watch recommendations also use only the provider-related response and do not use the broader local recommendation pipeline.

## 3. Architecture

### 3.1 Search history

`SearchViewModel` will depend on `SearchHistoryRepository` and the existing settings source that exposes whether history is enabled. It will observe recent Room queries as a lifecycle-independent flow and expose them in search UI state.

A query is recorded only when the user explicitly submits it or selects a suggestion/history row. Debounced searches triggered while typing are not recorded. The repository continues to normalize, deduplicate, timestamp, and trim queries.

Deleting one query calls `SearchHistoryRepository.deleteQuery`. Clearing all queries calls `SearchHistoryRepository.clearHistory`. Room remains the source of truth, so successful changes propagate through its `Flow`; the ViewModel does not maintain a second optimistic list.

### 3.2 Shared history setting

The existing history setting controls both watch-history and search-history collection. When disabled:

- new searches are not recorded;
- new watch events remain governed by the existing disabled-history behavior;
- retained search and watch history is not used for personalization;
- Home falls back to non-personalized trending content;
- Watch uses provider-related content and non-personalized fallback content only.

Disabling history does not silently delete existing data. Users retain explicit delete-one and clear-all controls. Re-enabling history makes retained data eligible for personalization again.

### 3.3 Recommendation sources

`RecommendationRepository` remains the single orchestration boundary for Home recommendations. A focused Watch recommendation entry point will share its signal collection, candidate merging, and ranking policy instead of duplicating that logic in `WatchViewModel`.

Home candidates may come from:

- up to three recent explicit search queries;
- topics inferred from watched-video titles;
- frequently watched channel names;
- provider trending content.

Watch candidates may come from:

- the provider's `related(currentVideo)` response;
- the current video's title/topic signals;
- the current channel;
- recent explicit search queries;
- topics and channels inferred from watch history;
- trending content as the final fallback.

Searching familiar channel names favors channels the user already watches. Searching interest topics also discovers videos from new channels with similar content, satisfying both familiarity and exploration without requiring a remote user profile.

## 4. Data flow

### 4.1 Search

1. The search screen opens and observes persisted recent queries.
2. Typing still triggers the existing debounced search and cancellation behavior but does not write history.
3. Submit, suggestion selection, or history-row selection normalizes the query and starts an explicit search.
4. If history is enabled, the query is recorded on the repository's IO dispatcher.
5. Room emits the updated ordered list.
6. Delete-one and clear-all mutate Room; the emitted list updates the screen.

Search execution must not wait for a successful history write. A storage failure does not block the network search, but the UI exposes a short, user-safe history-operation error and leaves Room-derived history unchanged.

### 4.2 Home recommendations

1. Read the current history-enabled setting.
2. If disabled, fetch trending without reading local interest signals for ranking.
3. If enabled, read bounded recent search and watch history snapshots.
4. Build normalized topics with explicit searches first, followed by title topics and frequent channels.
5. Fetch bounded search candidates and trending concurrently under structured cancellation.
6. Rank, diversify, deduplicate, and cap the merged result.

Clearing history affects the next Home load or refresh. The design does not require Home to interrupt an active feed and reload while the user is viewing it.

### 4.3 Watch recommendations

1. Load provider-related videos for the current key.
2. Once current video metadata is available, derive a small set of current-title and current-channel topics.
3. When history is enabled, add bounded local search, watched-topic, and familiar-channel signals.
4. Fetch supplemental candidates concurrently with partial-failure isolation.
5. Rank and merge provider-related and supplemental candidates.
6. Exclude the current video, deduplicate by `ContentKey`, diversify channels/topics, and cap the visible result.

Provider-related videos receive a strong base priority because they carry direct current-video context. Supplemental candidates fill and broaden the list rather than replacing high-quality related results.

## 5. Ranking and diversity policy

Ranking remains deterministic and explainable. Candidate scores include:

- recent explicit-query match, weighted by recency;
- contiguous topic-phrase and token matches;
- same-channel match with the current Watch video;
- affinity for channels watched frequently;
- provider-related source priority on Watch;
- freshness when provider metadata supplies a publication timestamp;
- a penalty for previously watched videos.

Previously watched videos are omitted while unwatched alternatives exist. They may reappear only when exclusion would leave the feed empty or too sparse.

After scoring, a diversification pass prevents one query, topic, or channel from occupying the entire leading portion of the list. Stable input order breaks equal scores so results remain deterministic in tests.

All candidate collections are deduplicated by `ContentKey`. The current Watch video is always excluded from its own recommendation list.

## 6. Bounds, caching, and cancellation

The existing network-safety constraints remain in force:

- at most six supplemental topic searches per recommendation load;
- at most three recent explicit search queries contribute topics;
- at most 30 videos are returned for a feed;
- normal loads use existing `CatalogRepository` cache behavior;
- explicit refresh requests bypass eligible caches through existing `forceRefresh` behavior;
- all child requests use structured coroutines and propagate cancellation;
- one failed source does not cancel successful sibling sources;
- no unbounded request is launched per history entry or candidate channel.

The implementation should reuse existing constants where their semantics match. Any separate Watch limits must be explicit and no greater than the Home fan-out limit.

## 7. User interface

### 7.1 Search screen

When the input is blank, the screen shows persisted recent queries ordered newest first. Each row contains a history icon, the query text, and an accessible delete action. Selecting the row runs that query.

When at least one history item exists, a compact header shows the recent-search label and a `Clear all` action. `Clear all` opens a confirmation dialog; confirmation performs the deletion and cancellation leaves data unchanged.

Provider suggestions continue to appear while the user types. Suggestions do not erase or replace persisted history; returning to a blank query shows history again.

Delete failures preserve the visible Room-backed data and show a short localized message. Actions are disabled while the corresponding mutation is in flight to prevent duplicate submissions.

### 7.2 Home screen

The existing Home layout and topic chips remain unchanged. The `All` feed uses expanded personalized results when history is enabled. Explicit topic chips continue to load their selected topic rather than being silently personalized into a different topic.

### 7.3 Watch screen

The existing related-videos section remains in its current position and visual style. Its data source becomes the merged Watch recommendation result. No additional tab or screen is introduced.

Loading and retry behavior remain local to the related section. Supplemental-source failures are not shown when another source produced usable videos.

## 8. Error handling

History persistence errors map to existing user-safe application errors. Raw database exceptions are never shown. A record failure does not fail search results, while delete and clear failures leave source-of-truth data intact.

Recommendation source failures are isolated:

- return merged successful candidates when at least one source succeeds;
- return an empty state when sources succeed but produce no candidates;
- show an error only when all applicable sources fail and no cached or fallback candidates exist;
- rethrow coroutine cancellation immediately;
- do not retry restrictions or unsupported operations automatically.

If provider-related content is unsupported or empty, Watch may still show bounded supplemental recommendations. If personalization is disabled, supplemental queries must use only current-video context, current-channel context, and generic trending fallback.

## 9. Privacy and content boundaries

Search history, watch history, and ranking are local-only and are not synchronized or transmitted as a user profile. Provider search requests necessarily contain the selected bounded topic text, as ordinary content searches do; the app does not upload the raw history database or a persistent user identifier.

The feature preserves the existing zero-telemetry, no-account, no-access-control-bypass, no-stream-URL-persistence, and `android:allowBackup="false"` contracts.

Clearing search history deletes only `search_history`. Clearing watch history deletes only `watch_history`. Neither operation affects subscriptions, playlists, settings, caches, or the other history table.

## 10. Testing and acceptance

Unit tests will cover:

- persisted recent queries survive a new `SearchViewModel` instance;
- explicit submit and suggestion/history selection record once when enabled;
- debounced typing does not record a query;
- history disabled prevents writes and excludes retained data from personalization;
- deleting one query and clearing all queries use the repository and reflect Room emissions;
- storage failure does not block search and is exposed safely;
- Home prioritizes explicit searches, title topics, and familiar channels within fan-out bounds;
- Watch prioritizes provider-related and current-video context while adding familiar and new similar channels;
- current and watched videos are excluded or penalized according to policy;
- candidate deduplication, deterministic ranking, diversification, limits, partial failure, and cancellation.

Compose and ViewModel tests will cover:

- recent history rendering after reopening search;
- selecting and deleting an individual history row;
- clear-all confirmation, cancellation, success, and failure;
- accessibility labels and stable test tags for history actions;
- personalized Home content and non-personalized fallback when history is disabled;
- expanded Watch related content, empty state, partial failure, and retry.

Relevant verification commands are:

```text
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew assembleDebug
```

Instrumentation tests may require an available emulator or device. Unit tests and `assembleDebug` remain mandatory in the local implementation environment.

## 11. Non-goals

- No backend recommendation service or cloud synchronization.
- No machine-learning model, embeddings, behavioral tracking, or remote user profile.
- No new Room schema or migration for interest weights.
- No source-platform login or use of remote account history.
- No automatic deletion of retained history when the history setting is disabled.
- No separate recommendation screen or redesign of Home and Watch.
- No unbounded crawl of channels, pages, playlists, or search results.
