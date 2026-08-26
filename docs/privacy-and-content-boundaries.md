# HPre Privacy & Content Boundaries

## 1. Non-Official Extractor & Technical Scope
- HPre V1 targets **NewPipeExtractor** (`com.github.TeamNewPipe:NewPipeExtractor:v0.26.5`) as a non-official client extractor library behind an isolated provider-neutral adapter.
- HPre is an independent client and is **not** endorsed by, affiliated with, or an official product of YouTube, Google LLC, or NewPipe.
- Extraction only accesses content that is technically and lawfully viewable by the public without access restriction.

## 2. Strict Content & Access Boundaries
- **No Access Control Bypass:** HPre **never** bypasses, defeats, or evades login barriers, paywalls, DRM (Digital Rights Management), CAPTCHA challenges, age verifications, or geographic geo-fencing.
- **Explicit Domain Errors:** Content restricted by age, geo-blocking, DRM, or authentication immediately fails with safe domain errors (`AgeRestricted`, `GeoRestricted`, `LoginRequired`, `ContentUnavailable`) rather than exposing crash logs or raw platform tokens.
- **No Downloader:** V1 strictly excludes any offline video/audio media file downloading mechanism.

## 3. Privacy & Zero-Telemetry Contract
- **No Tracking or Analytics:** HPre contains **no** telemetry, ads, tracking pixels, crash reporters (e.g. Firebase Analytics, Crashlytics), or remote user identifier beacons.
- **No User Account / Source Mutation:** HPre does not offer source-platform login and does not mutate remote source-platform account states (likes, subscriptions, comments).
- **Ephemeral Stream Data:** Direct stream URLs (CDN links) are transient, session-only playback tokens. They are **never** persisted to Room database or local files.
- **Local Storage Exclusivity & Backup Policy:** All local subscriptions, watch history, and local playlists are stored exclusively on the user's local device (`Room` SQLite DB and `DataStore` preferences) and are never synchronized to any cloud server or third-party service. Furthermore, `android:allowBackup="false"` is deliberately configured on the application to enforce this local-only privacy contract.
- **User-Facing Tradeoff:** Setting `android:allowBackup="false"` significantly improves local-data privacy by preventing unintended remote exposure, but introduces a direct user-facing tradeoff: it disables Android Auto Backup/Google Cloud restore and device migration convenience for history, settings, and local playlists. Users transferring to a new device or restoring from cloud backups will not have their local data automatically migrated.
- **Redacted Diagnostics:** Any network or debug logs explicitly redact query tokens, signature parameters, authentication headers, cookies, and full media stream URLs.

## 4. UI & Branding Integrity
- HPre presents an original Jetpack Compose Material 3 interface.
- It does **not** copy or imitate proprietary source-platform trademark logos, icons, exact color palettes, or brand assets.
- User actions such as subscriptions are explicitly labelled as local actions (e.g., "Follow locally").
