# Thiết kế Kỹ thuật Tối ưu hóa Hiệu năng và Trải nghiệm Người dùng - HPre 1.0.29

**Ngày tạo:** 2026-09-03  
**Phiên bản mục tiêu:** HPre 1.0.29 (Version Code 30)  
**Baseline:** HPre 1.0.28 (commit `56fc369`)

---

## 1. Mục tiêu và Nguyên tắc Chỉ đạo

### 1.1 Mục tiêu Cốt lõi
Người dùng phải thấy ứng dụng phản hồi ngay lập tức:
1. Chạm (tap) vào video là chuyển màn hình sang Watch ngay lập tức (<100 ms).
2. Thumbnail preview và khung player xuất hiện đồng thời ngay tức thì.
3. First frame đến sớm nhưng tuyệt đối không đánh đổi bằng hiện tượng khựng/rebuffer đầu video (giữ mức đệm an toàn `BUFFER_FOR_PLAYBACK_MS = 1000 ms`).
4. Không tạo ra các luồng request ngầm tranh chấp CPU/IO/băng thông mạng.

### 1.2 Chỉ số KPI Đo lường (Key Performance Indicators)
- **Cold start đến Home:** Giữ nguyên hoặc giảm so với 1.0.28 (dưới 600–800 ms).
- **Tap → Watch UI chuyển màn:** Dưới 100 ms.
- **Cache-hit Tap → First Frame:** 300–500 ms.
- **Cache-miss Tap → First Frame:** Dưới 1.0–1.5 s trên mạng ổn định.
- **Rebuffer rate:** 0 lần rebuffer trong 10 giây đầu phát ở điều kiện bình thường.

---

## 2. Kiến trúc Chi tiết

### 2.1 Khởi động Ứng dụng (Cold Start & Deferred Prewarm)
- **PlayerController Lazy:** Giữ nguyên nguyên tắc `PlayerController` hoàn toàn lazy trong `AppContainer`. Tuyệt đối không khởi tạo Media3 ExoPlayer, NewPipeExtractor instance hoặc cache media trong lúc khởi động app trước khi Home render frame đầu tiên.
- **Deferred Prewarm:** Khi `HomeScreen` hoàn tất render và main thread idle (`onContentIdle`), kích hoạt luồng IO nhẹ để pre-warm kết nối mạng nhẹ (DNS/TLS) và chuẩn bị `MediaSourceFactory` / `MediaSession` connection. Không tải trước manifest hay stream URL.

### 2.2 Điều hướng Tức thì (Home → Watch Instant Navigation)
- **Truyền trước Thumbnail & Metadata:**
  - Chuẩn hóa các điểm điều hướng tới Watch (`HPreNavHost`, `RootScaffold`, `SubscriptionsScreen`, `LibraryScreen`, `WatchScreen` related list) luôn gọi `Screen.Watch.createRoute(key, video.thumbnailUrl)`.
  - Lưu trước snapshot sơ bộ `WatchStateSnapshot` vào `WatchStateCache` ngay lúc tap dựa trên `VideoSummary` sẵn có.
- **Render Ngay Lập Tức:**
  - `WatchScreen` mở ngay lập tức, hiển thị `PlayerSurface` với ảnh preview thumbnail lấy từ route hoặc cache.
  - Quá trình trích xuất (`videoService.streamInfo(key)`) diễn ra song song ngầm.
- **Delayed Loading Spinner:**
  - Thêm khoảng delay 150 ms trước khi hiện `CircularProgressIndicator` trên player surface. Khi video mở cực nhanh (cache-hit hoặc kết nối sẵn), spinner sẽ không nhấp nháy (loại bỏ visual flicker).
- **Giữ Thumbnail đến First Frame:**
  - Thumbnail preview chỉ được ẩn đi khi `hasRenderedFirstFrame == true` (hoặc timeout fallback nếu audio-only). Không bao giờ tắt thumbnail chỉ dựa trên trạng thái `STATE_READY`.

### 2.3 Quản lý Luồng Phát (Pipeline Isolation & Cancellation)
- **Độc quyền Luồng Phát:**
  - Mỗi video tại một thời điểm chỉ có duy nhất 1 chuỗi: `extract -> resolve -> prepare`.
  - Tác vụ phụ trợ không nằm trên critical path:
    - `comments`: Tải trì hoãn sau khi video đã bắt đầu phát hoặc khi người dùng mở panel bình luận.
    - `related`: Chỉ bắt đầu tải sau khi playback đã bắt đầu (`onRenderedFirstFrame` hoặc sau `prepare`).
    - `resume position lookup`: Giới hạn với `withTimeoutOrNull(200 ms)`. Nếu chậm, phát ngay từ 0s thay vì giữ player chờ đợi.
- **Hủy Ngay khi Đổi Video (A → B):**
  - Khi người dùng bấm video mới hoặc back/next nhanh: Hủy sạch các coroutines con của request trước, gọi `playerController.stopForTransition()`, và hủy request mạng cấp thấp của thread NewPipe thông qua `OkHttpDownloader.cancelActiveCallForThread()`.

### 2.4 Chiến lược Bộ nhớ Đệm (Stream Cache) & Mạng (Network Pooling)
- **StreamInfo Cache:**
  - `VideoExtractionCoordinator` duy trì TTL 5 phút (300.000 ms) với LRU 32 entries cho video vừa mở, vừa tìm hoặc vừa xem.
  - Trải nghiệm mở lại video gần đây đạt 100% cache-hit, bỏ qua hoàn toàn bước trích xuất mạng.
  - Không bật lại prefetch hàng loạt khi scroll feed (`prefetch = no-op`).
- **Hợp nhất OkHttpClient:**
  - Tái sử dụng cùng một instance `OkHttpClient` giữa `OkHttpDownloader` (NewPipe) và `MediaSourceFactory` (ExoPlayer).
  - Cấu hình connection pool dùng chung (`maxIdleConnections = 8`, `keepAliveDuration = 5 phút`), tối ưu hóa tái sử dụng socket, DNS cache và TLS handshake.
- **Cơ chế Phục hồi (Recovery):**
  - Khi gặp lỗi 403 / URL expired: Giới hạn tối đa 1 lần retry tự động.
  - Khi retry: Chủ động xóa đúng cache entry của key đó trong `VideoExtractionCoordinator` và `WatchStateCache` trước khi extract lại. Không tạo retry loop vô hạn gây treo spinner.

### 2.5 Tối ưu Compose Recomposition & SWR
- **Stale-While-Revalidate (SWR):**
  - Giữ lại nội dung cũ khi đổi chip hoặc tìm kiếm tiếp; chỉ hiển thị full-screen spinner khi danh sách hoàn toàn rỗng.
  - Khi back từ Watch về Home: Giữ nguyên scroll position (`LazyListState`) và danh sách video cũ; không tải lại feed trừ khi cache hết hạn.
- **Decoupling Playback Updates:**
  - Tách biệt hoàn toàn `currentPosition` và `bufferedPosition` (chu kỳ 250–500 ms) khỏi `WatchUiState` và `RootScaffold`.
  - Chỉ các composable hiển thị scrubber progress bar và label thời gian mới nhận recompose khi vị trí phát thay đổi.

### 2.6 Đo đạc Hiệu năng (Telemetry First)
- **VideoOpenMetrics Chi tiết:**
  - Mở rộng ghi nhận các mốc:
    - `VIDEO_OPEN_START`: Chạm vào card video.
    - `EXTRACTOR_START`: Bắt đầu trích xuất stream.
    - `EXTRACTOR_FINISH`: Hoàn thành trích xuất stream.
    - `STREAM_INFO_READY`: Resolved stream candidates.
    - `PLAYER_PREPARE`: ExoPlayer nhận MediaSource.
    - `PLAYER_READY`: ExoPlayer đạt `STATE_READY`.
    - `FIRST_FRAME`: Khung hình đầu tiên hiển thị trên màn hình.
  - Ghi nhận `streamType` (PROGRESSIVE vs HLS vs DASH) và phân loại Cache-Hit / Cache-Miss.

---

## 3. Kế hoạch Triển khai & Xác minh (Verification)

### 3.1 Kế hoạch Kiểm thử
- **Unit Tests:**
  - Kiểm tra `WatchViewModelTest`: Navigation tức thì với thumbnail ban đầu, delayed spinner, độc quyền chuỗi phát và hủy tác vụ cũ khi đổi video.
  - Kiểm tra `VideoExtractionCoordinatorTest`: TTL 5 phút, tái sử dụng bundle cho video gần đây, phục hồi khi 403.
  - Kiểm tra `AppContainerTest` & `NetworkPolicyTest`: Tái sử dụng cùng một instance `OkHttpClient` cho cả App, Downloader và MediaSourceFactory.
  - Kiểm tra `HomeViewModelTest` / `SearchViewModelTest`: Giữ SWR khi đổi chip/tìm kiếm, không hiện spinner full-screen khi có cache.
- **Build & Release:**
  - Chạy `./gradlew testDebugUnitTest` đảm bảo 100% tests pass.
  - Chạy `./gradlew assembleRelease` ký bằng release keystore chính thức.
  - Tạo bản phát hành Git tag `v1.0.29` và cập nhật changelog release doc.
