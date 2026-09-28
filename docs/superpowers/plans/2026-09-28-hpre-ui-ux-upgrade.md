# HPre UI/UX Upgrade — Implementation Plan cho SWE

**Trạng thái:** Đề xuất để duyệt và bàn giao, CHƯA triển khai.
**Ngày khảo sát:** 2026-09-28.
**Baseline source:** `/opt/HPre`, commit `0685f7d7f0811ae0fecf5dc376c6a2c462679778`, `versionName=1.0.38`, `versionCode=39`.
**Goal:** HPre đẹp, nhất quán, dễ dùng hơn; thao tác có phản hồi rõ ràng và không làm giảm chất lượng phát video.
**Architecture:** Nâng cấp dần Compose UI trên ViewModel/Repository/MediaSession hiện có. Không viết lại ứng dụng, không đổi engine phát video, không dựng thêm lớp UI framework.
**Tech Stack:** Kotlin 2.1.20, Jetpack Compose BOM 2025.02.00, Material 3, Navigation Compose 2.8.8, Media3 1.5.1, Coil 2.7.0, Coroutines/Flow, Room, DataStore.
**Spec:** Phần 2–4 của chính tài liệu này là đặc tả thiết kế đề xuất; SWE cần đọc cùng các task ở phần 5.

> Dành cho SWE/agent thực hiện sau khi được giao sửa: làm lần lượt theo checkbox; test hành vi trước khi sửa logic. Không tự chạy implementation, commit, push, tạo PR/tag/release từ yêu cầu lập plan này. Không dùng subagent nếu chưa được người dùng yêu cầu.

## 1. Phạm vi, bằng chứng và ràng buộc

### 1.1. Đã kiểm tra gì

Đã đọc source theme, navigation, Home, Search, Watch, controls, MiniPlayer, Channel, Library, Subscriptions, History, Settings; các ViewModel liên quan; cấu hình ảnh, metrics và hạ tầng test.

Chưa xem giao diện app đang chạy trên thiết bị. Không tìm thấy ảnh screenshot trong repo; README vẫn ghi sẽ bổ sung. `adb` và `emulator` không có trên PATH của phiên khảo sát. Không suy ra rằng máy chắc chắn chưa cài Android SDK.

**Vì vậy:** các nhận xét dưới đây là phát hiện từ source, không phải kết luận đã đo trên máy thật. Màu sắc/kích thước là thiết kế đề xuất, cần duyệt qua ảnh so sánh trước khi triển khai đồng loạt. Không xác nhận source này trùng APK người dùng đang cài; Task A1 phải đối chiếu.

### 1.2. Những nền tảng đang có — giữ lại, không xây lại

1. Material 3; chế độ sáng/tối/theo hệ thống; tiếng Việt/Anh; spacing và touch target 48dp.
2. Navigation ba tab có save/restore state; Home giữ dữ liệu khi refresh và có cache theo chip; Search đã debounce 200ms cho gợi ý, 400ms cho kết quả và có generation guard.
3. Một hệ thống phát dùng MediaSession; Watch, mini-player, fullscreen, PiP, phát nền, đổi chất lượng/tốc độ, double-tap tua và vuốt thu nhỏ đã tồn tại.
4. Coil dùng chung ImageLoader/OkHttp, memory cache theo thiết bị, disk cache 150MB, tắt crossfade. Không mặc định tăng cache hoặc bật crossfade toàn app.
5. Unit tests, Compose instrumentation tests, FakeVideoService, `VideoOpenMetrics`, quy trình đo first-frame đã có. Tận dụng thay vì thêm framework.

### 1.3. Các khoảng trống đã thấy từ source

Các đường dẫn Kotlin trong bảng tương đối với `app/src/main/java/com/hpre/app/`.

| Nhóm | Bằng chứng | Hệ quả cần xử lý |
|---|---|---|
| Nhất quán thị giác | `core/designsystem/HPreTheme.kt:10–46` chưa gán đầy đủ các surface/container role đang được dùng; `RootScaffold.kt:149–164` dùng logo 36dp và chữ ExtraBold; `VideoCard.kt:133–159` dùng title 14sp, metadata dồn một dòng | Dễ lẫn màu mặc định Material với palette HPre; header nổi hơn nội dung, metadata bị cắt. Cần xem ảnh thực tế để duyệt mức thay đổi. |
| Chức năng có hình nhưng thiếu đường đi | `LibraryScreen.kt:400–439` avatar kênh không có callback/clickable; `WatchScreen.kt:716–751` hàng tên kênh chưa có callback điều hướng; `ChannelScreen.kt:79–84` chưa hiển thị avatar/banner dù model có | Chạm vào thành phần nhìn như mở được kênh nhưng không có hành động; trang kênh sơ sài. |
| Tải lại và báo lỗi | `SearchViewModel.kt:332–335` bỏ qua thông tin lỗi pagination; `SubscriptionFeedViewModel.kt:32–46` thay Content bằng Loading; Home dùng ErrorPane fullscreen cho lỗi refresh, trong khi `ErrorPane.kt:156–159` luôn fillMaxSize | Thiếu nút khôi phục tại chỗ, video biến mất khi refresh, lỗi phụ có thể chiếm quá nhiều diện tích. |
| Bố cục màn phụ | Subscriptions liệt kê toàn bộ kênh trước feed; nút refresh luôn ghi “Thử lại”; Library có các section nhưng nhiều số đếm/chữ nhỏ; Watch đặt bình luận trước related và dùng nhiều kiểu dialog/menu | Người dùng phải cuộn nhiều, nhãn chưa đúng ngữ cảnh, các thao tác không đồng nhất. |
| Phản hồi thao tác | `LibraryViewModel.kt:86–141` nhiều mutation không xử lý AppResult; `LibraryScreen.kt:237–243` đóng dialog tạo playlist ngay khi gửi yêu cầu | Khi ghi dữ liệu thất bại, UI không thể hiện đủ rõ; có nguy cơ người dùng tưởng đã lưu. |

### 1.4. Global Constraints

1. Chỉ thay đổi phục vụ UI/UX; giữ minSdk 26, compile/targetSdk 35, JDK 17, package/app identity, database và dữ liệu người dùng. Không sửa CI, signing hoặc version trong đợt UI này.
2. Giữ mọi chức năng hiện có; không bỏ menu, lịch sử, thao tác playlist, phát nền, PiP, fullscreen, chất lượng, tốc độ hoặc khả năng tiếp cận. Không tự thay đổi chính sách Shorts/nội dung.
3. Không thêm runtime dependency cho theme, skeleton, animation, ảnh hoặc bottom sheet. Dùng Compose/Material 3/Coil đã có. Nếu API chưa tồn tại trong BOM hiện tại, dùng API tương thích, không nâng cả stack để làm đẹp.
4. Không tải font qua mạng, không dùng dữ liệu giả như số người đăng ký/lượt xem thật, không tạo nút chưa hoạt động. Cache video/feed là dữ liệu đã tải trước, không được diễn đạt là vừa cập nhật.
5. Không fetch/push GitHub, không commit/PR/release; không cài đè APK hay xóa app data trên máy người dùng khi chưa được duyệt riêng. Chỉ tài liệu này được tạo trong phiên lập plan.

### 1.5. Review Focus

1. Đổi query/chip liên tục trong mạng chậm: phản hồi cũ không ghi đè lựa chọn mới; nội dung cũ không bị gắn nhãn sai. Chủ sở hữu: B3/C1.
2. Quay lại từ Watch hoặc xoay máy: giữ list/query/chip, không mở bàn phím vô cớ, không phát lại từ đầu. Chủ sở hữu: C1/C2/E1.
3. Font 200%, màn 320dp, TalkBack: không đè nút hoặc cắt mất hành động; swipe/gesture luôn có nút thay thế. Chủ sở hữu: B1/B2/C2/D1/D2.
4. Repository thất bại hoặc người dùng bấm lưu liên tục: không báo thành công giả, không tạo trùng playlist, không mất nội dung đang nhập. Chủ sở hữu: C2/D2.
5. Chuyển surface Watch/mini/PiP: không có hai player, không mất tiếng, không che video bằng thumbnail quá lâu; tách lỗi render và lỗi upstream. Chủ sở hữu: C2/E1/E2.

## 2. Hướng thiết kế đề xuất

### 2.1. Lựa chọn

| Phương án | Điểm được | Đánh đổi |
|---|---|---|
| **Khuyến nghị: giao diện video tối giản, giữ bản sắc HPre** | Cải thiện rõ qua typography, khoảng trắng, palette trung tính, hierarchy và controls; gần thói quen dùng app video | Phải chỉnh đồng bộ nhiều màn nhưng giữ được kiến trúc và navigation |
| Chỉ sửa theme/token | Diff nhỏ, rủi ro thấp | Không giải quyết các vùng không bấm được, phản hồi lỗi, layout Theo dõi và trải nghiệm player |
| Thiết kế lại toàn bộ với glass/gradient/animation lớn | Khác biệt mạnh | Chi phí render và rủi ro hồi quy cao; không phù hợp mục tiêu mượt, ít cần thiết |

Chọn phương án đầu làm cơ sở plan; đây không phải thiết kế đã được người dùng duyệt. Giữ đỏ là nhận diện, không biến toàn bộ nút/nhãn/trạng thái thành màu đỏ. Không sao chép logo hoặc wordmark của nền tảng khác.

### 2.2. Nguyên tắc thị giác

1. Video là điểm chính: thumbnail rõ, tiêu đề dễ đọc; chrome/header không cạnh tranh với nội dung.
2. Mỗi vùng có một hành động chính: xem video, tìm kiếm, theo dõi hoặc lưu; ít viền và ít bóng đổ.
3. Phân tầng bằng khoảng cách và sắc độ nền; tránh card lồng card, mảng nền nhiều màu, chữ đậm khắp nơi.
4. Nhất quán giữa Home/Search/Channel/Related; khác mật độ nhưng cùng typography, icon và trạng thái ảnh.
5. Sáng và tối đều là giao diện hoàn chỉnh. Giữ lựa chọn theme đang lưu; không ép người dùng sang dark mode.

### 2.3. Token đề xuất

**Màu** — màu khởi điểm để duyệt mockup, không bỏ qua kiểm tra contrast:

| Vai trò | Dark | Light |
|---|---|---|
| Background / surface cơ bản | `#101114` | `#FAFAFC` / `#FFFFFF` |
| Surface container low / container / high / highest | `#17191D` / `#1E2025` / `#25282E` / `#2D3038` | `#F5F5F8` / `#EEEEF3` / `#E8E8EE` / `#E1E1E8` |
| Chữ chính / phụ | `#F4F4F5` / `#B4B6BF` | `#18191D` / `#5F626D` |
| Primary tương tác / chữ trên primary | `#FFB4AB` / `#690005` | `#B32624` / `#FFFFFF` |
| Primary container / chữ trên container | `#8C1D18` / `#FFDAD6` | `#FFDAD6` / `#410002` |

Giữ `HPreRed=#E53935` cho dấu hiệu nhận diện, không bắt buộc dùng đỏ đó làm nền chữ trắng nhỏ. Gán rõ các color role thật sự được dùng: surface container, secondary container, outline/outlineVariant, error/errorContainer và màu chữ tương ứng. Chọn error theo semantic Material, không dùng error làm tất cả accent. `LIVE` dùng token trạng thái riêng với chữ tương phản.

**Typography và kích thước:**

| Thành phần | Quy cách |
|---|---|
| Tên màn / tiêu đề Watch | 22sp/28sp SemiBold; Watch 18sp/24sp SemiBold; không ép tất cả ExtraBold |
| Tiêu đề video / chữ nội dung | 16sp/22sp Medium / 14sp/20sp Regular; tiêu đề card tối đa 2 dòng ở font mặc định |
| Metadata / badge | 12–13sp/18sp; badge 11–12sp, không dùng 10sp cho thông tin cần đọc thường xuyên |
| Khoảng cách | Lưới 4dp; nội bộ 8/12dp; mép màn 16dp; giữa section 24dp |
| Shape / touch | Thumbnail 12dp; nhóm nội dung 16dp; sheet 24dp; icon 20–24dp nhưng hit area tối thiểu 48×48dp |

Dùng font hệ thống để hỗ trợ tiếng Việt và giảm kích thước app. Không cố định chiều cao text row làm hỏng font scaling. Contrast: chữ thường tối thiểu 4.5:1; chữ lớn và thành phần điều khiển thiết yếu tối thiểu 3:1.

### 2.4. Motion

1. Phản hồi bấm dùng ripple/pressed state sẵn có ngay lập tức; không chờ mạng.
2. Fade nhỏ 120–180ms, chuyển màn/sheet 180–240ms; không animate từng card lúc cuộn, không parallax/blur runtime.
3. Skeleton tĩnh đúng hình khối nội dung khi lần tải đầu vượt 180ms; không shimmer chạy liên tục. Refresh có dữ liệu dùng progress nhỏ, không skeleton che lại feed.
4. Không animate player surface bằng shared-element trong đợt đầu. Ưu tiên handoff liền mạch trước hiệu ứng thu nhỏ phức tạp.
5. Tôn trọng animation scale hệ thống và nhu cầu accessibility; UI vẫn dùng được khi duration scale bằng 0.

## 3. Đặc tả từng khu vực

### 3.1. Home và điều hướng gốc

1. Giữ ba tab: Trang chủ / Theo dõi / Thư viện. Header Home có dấu hiệu HPre 24–28dp và wordmark 20sp Medium/SemiBold; Search và Settings giữ vị trí dễ tìm. Màn Theo dõi/Thư viện hiển thị tên màn rõ hơn thay vì lặp logo lớn.
2. NavigationBar cùng palette surface, active indicator nhẹ, icon filled khi chọn/outlined khi chưa chọn; luôn có label, không dựa duy nhất vào màu.
3. Chip thống nhất với Search, padding ngang 16dp. Đổi chip: phản hồi selected ngay, giữ dữ liệu cũ có nhãn “Đang tải [chủ đề]…”, chỉ chuyển sang dataset mới khi thành công.
4. VideoCard: ảnh 16:9; title 2 dòng; tên kênh một dòng; lượt xem và thời gian một dòng riêng; ảnh lỗi vẫn giữ cùng kích thước. Avatar/tên kênh mở kênh khi có channelKey, thumbnail/title mở video. Hit target không chồng nhau.
5. Refresh cùng dataset giữ vị trí/anchor nếu item còn tồn tại; thay dataset mới reset đầu danh sách sau khi dữ liệu mới sẵn sàng. Khi quay lại Watch giữ vị trí cũ. Lỗi refresh là banner gọn, không thay cả màn.

### 3.2. Search

1. Ô tìm kiếm nền container, bo 12dp, nút Back và xóa rõ; keyboard Search đóng bàn phím, gửi request một lần. Chỉ tự focus lần vào Search mới, không focus lại khi từ Watch quay về.
2. Giữ live search 400ms và suggestions 200ms trong đợt này. Khi đang chỉnh query, gợi ý xuất hiện dưới ô nhập kể cả đang có kết quả cũ; không cần thay kiến trúc tìm kiếm sang submit-only.
3. Kết quả video dùng hàng gọn: thumbnail khoảng 128×72dp ở màn thường, nội dung cạnh phải; tại 320dp/font lớn chuyển sang layout dọc nếu không đủ chỗ. Kênh/playlist dùng cùng baseline spacing và icon.
4. Pagination lỗi: giữ mọi item đã tải, footer “Không tải được thêm kết quả” + “Thử lại”; chỉ retry token lỗi. Không reset query/list, không tự retry vô hạn. Cửa sổ giữ tối đa 300 kết quả vẫn được bảo toàn.
5. Dữ liệu cũ trong lúc đổi query có nhãn “Đang tìm…” và nguồn query trước nếu cần; không gọi chúng là kết quả query mới. Kết quả rỗng/lỗi có hướng tiếp tục; remote playlist chưa hỗ trợ phải ghi rõ, không giả vờ mở được playlist như local.

### 3.3. Watch, controls và mini-player

1. Player trên cùng; dưới là title tối đa 2 dòng + mở rộng, metadata, hàng kênh có avatar/tên và nút Theo dõi, tiếp đến Lưu/Chia sẻ. Mô tả gọn 2 dòng với mở rộng. Tên kênh mở Channel bằng ContentKey.
2. Bình luận hiển thị entry gọn; mở trong Material bottom sheet để video liên quan không bị đẩy xuống bởi hàng trăm bình luận. Chỉ thu thập/load khi mở theo policy đang có. Không thêm tổng số bình luận nếu provider không trả. Sheet có loading/empty/error/pagination/retry và giữ trạng thái mở rộng từng comment.
3. Player giữ Play/Pause, tua ±10s và thanh seek dễ bấm. Chất lượng/tốc độ hiện giá trị đang chọn bằng nút gọn; portrait mở bottom sheet, landscape thấp dùng menu/Surface cuộn có giới hạn chiều cao. Resize Fit/Fill/Zoom vẫn truy cập được ở fullscreen. Giữ auto-hide, bảo vệ vùng controls khỏi gesture và timeout accessibility đang có.
4. Lưu playlist dùng sheet chọn danh sách/tạo mới, có trạng thái đang lưu và lỗi ngay trong sheet; chỉ đóng khi thành công, không chồng AlertDialog lỗi lên dialog cũ. Chia sẻ tiếp tục dùng validator và Android share sheet hiện tại.
5. Mini-player gọn 64–72dp ở font mặc định nhưng được tăng chiều cao theo accessibility; preview 16:9 thay vì box 48×36dp hiện tại, title, trạng thái, play/pause, close, thanh tiến độ. Tái sử dụng PlayerSurface/SurfaceOwner, không tạo ExoPlayer mới. Buffering phải ghi “Đang tải”, không mặc định gắn “Tạm dừng”.

Back ưu tiên: đóng sheet/menu → thoát fullscreen → hành vi trở về nguồn/thu nhỏ hiện có. Không dùng Back để clearMedia. Nút Close mini-player vẫn phải dừng/xóa media theo hành vi hiện tại, không chỉ giấu UI.

### 3.4. Theo dõi, Channel và Thư viện

1. Theo dõi: hàng avatar kênh cuộn ngang ở đầu + “Quản lý”; bên dưới là feed video. Sheet Quản lý giữ danh sách đầy đủ và thao tác bỏ theo dõi, có xác nhận để tránh chạm nhầm. Chạm avatar mở kênh, không tự thêm bộ lọc network mới.
2. Theo dõi refresh bằng pull-to-refresh, có nút “Làm mới” thay vì luôn “Thử lại”. Nội dung cũ còn dùng được; một kênh lỗi không che feed các kênh còn lại. Không fan-out lại toàn bộ chỉ do recomposition.
3. Channel hiển thị banner/avatar từ model nếu có, tên kênh, subscriber text, mô tả thu gọn 3 dòng và video. Banner không có thì dùng nền theme đơn giản, không thêm ảnh giả. Giữ nguyên tập nội dung videos/shorts hiện có, không âm thầm bỏ nhóm nào.
4. Thư viện giữ ba nhóm lịch sử gần đây, playlist, kênh theo dõi; tiêu đề dễ đọc, “Xem tất cả” nhất quán. Card lịch sử có progress và nhãn “Xem tiếp” chỉ khi `HistoryRepository.shouldOfferResume` cho phép; phần còn lại vẫn là lịch sử gần đây, không gắn nhãn xem tiếp cho tất cả.
5. History/playlist detail dùng hàng compact đồng bộ; không bỏ phân trang lịch sử 50 item hoặc điều khiển sắp xếp playlist hiện tại. Xóa hàng loạt có xác nhận, thao tác ghi có lỗi cụ thể, không báo thành công trước kết quả repository.

### 3.5. Settings và trạng thái chung

1. Giữ các nhóm cài đặt hiện có, thống nhất section header, row icon, title, subtitle và switch. Chỉ dùng card grouping khi giúp phân nhóm; không mỗi row một card đổ bóng.
2. Hàng lựa chọn mở sheet có radio/check; thao tác nguy hiểm như xóa cache/lịch sử giữ dialog xác nhận. Không thêm cài đặt mới chỉ để trình diễn UI.
3. Initial loading/empty/full error dùng bố cục chuyên dụng; lỗi phụ/refresh/pagination dùng inline, không dùng component fillMaxSize trong list item hoặc dialog.
4. Empty state: icon vector đơn giản + một câu ngắn + một hành động có thật: Thư viện trống → tìm video; chưa theo dõi → tìm kênh; playlist trống → thêm video qua luồng hiện có. Nút mới phải nối được tới destination thật.
5. Tất cả chuỗi mới có tiếng Việt/Anh trong resources. Giữ RetryPolicy, không biến mọi lỗi thành retryable. Không hiện exception kỹ thuật hoặc thông báo thành công nếu API/DB thất bại.

## 4. Tiêu chí “mượt” và cách đo

Các con số dưới đây là **mục tiêu nghiệm thu đề xuất, chưa phải số đo hiện tại**. A1 đo baseline trước; thiết bị chuẩn và dataset phải cố định. Không so debug với release.

### 4.1. Bộ mục tiêu

| Nhóm | Mục tiêu | Phương pháp |
|---|---|---|
| Phản hồi thao tác | Feedback bấm/chọn p95 ≤100ms; nội dung cache khi đổi tab/quay lại p95 ≤300ms | Perfetto + quay màn để xác minh flow; không tính thời gian animation là thời gian request |
| Cuộn UI | Trên máy chuẩn 60Hz: janky frame ratio ≤5% trong các lượt cuộn lặp lại; không có frozen frame >700ms do UI trong kịch bản kiểm soát | Perfetto FrameTimeline khi hỗ trợ, `dumpsys gfxinfo` làm đối chiếu; ở 90/120Hz dùng deadline của thiết bị, không áp mốc 16.7ms máy móc |
| Mở video | Không tăng p50/p95 tap→FIRST_FRAME quá 10% so cùng build type/baseline; nếu tối ưu startup riêng thì mục tiêu median tốt hơn ≥15%, không đánh đổi tỷ lệ lỗi | `VideoOpenMetrics` có sẵn; tách cache warm/cold, media type, chất lượng; không tính ảnh thumbnail là first frame |
| Tài nguyên | Median PSS và CPU cùng kịch bản không tăng quá 10%; sau 20 vòng Watch↔mini không tăng giữ lại tuyến tính; request không trùng do recomposition | `dumpsys meminfo`, Perfetto và bộ đếm FakeVideoService; báo cả MB/% trước-sau, không chỉ nhận xét cảm tính |
| Tính liên tục | 20 vòng Watch↔mini↔fullscreen không tạo player thứ hai, không mất audio do UI, không reset vị trí; PiP kiểm riêng trên máy hỗ trợ | Trace/log + video quay; không kết luận handoff đạt chỉ vì metadata đúng |

Nếu baseline jank >5%, ticket performance phải giảm ít nhất 20% tương đối và ghi rõ khoảng cách còn lại; mức này không tự thay thế gate ≤5%. Nếu một ngưỡng không đạt do thiết bị/upstream, báo kết quả và xin chấp nhận ngoại lệ, không đổi ngưỡng âm thầm.

### 4.2. Kịch bản và phương pháp tái lập

1. Mỗi build: cùng thiết bị, nguồn dữ liệu, chất lượng, điều kiện nhiệt/pin, mạng và animation scale. UI motion đo với animation bình thường; animation-off chỉ là nhánh test chức năng bổ sung.
2. UI deterministic dùng dữ liệu giả rõ ràng trong test: 100 video, title dài, ảnh thiếu/lỗi, ba loại search result, 300+ kết quả phân trang, 100 bình luận. Fake không đưa vào app production như dữ liệu thật.
3. Startup cold/warm đo riêng ít nhất 10 lượt mỗi nhánh; tap→first-frame lấy tối thiểu 30 lượt mỗi build/nhánh dùng cho p95, báo toàn bộ lượt lỗi/hủy/timeout. Không bỏ failed opens để làm đẹp median.
4. Cuộn Home/Search/Watch mỗi màn 5 lượt × 30 giây; thêm lượt đang có mini-player. Playback stress 20 lần handoff và xem 10 phút trong profile mạng cố định.
5. Nguồn video live dùng nội dung công khai đã được người dùng cho phép, không đảm bảo ngưỡng tuyệt đối cho NewPipe/upstream. Tách lỗi upstream khỏi regression UI nhưng vẫn đưa vào báo cáo.

Không chạy nguyên xi quy trình cũ trong `scripts/performance/README.md`: tài liệu đó dùng baseline 1.0.30→1.0.31, tắt animation và có uninstall/clean data. Đợt này dùng baseline A1, thiết bị/profile thử nghiệm riêng; tuyệt đối không xóa dữ liệu máy người dùng để benchmark. Script PowerShell hiện có chỉ hỗ trợ comparator chuyên biệt; p95/jank/PSS phải có bằng chứng riêng.

## 5. Backlog thực thi

**Quy ước đường dẫn:** `SRC = app/src/main/java/com/hpre/app`, `UNIT = app/src/test/java/com/hpre/app`, `UI = app/src/androidTest/java/com/hpre/app`, `RES = app/src/main/res`. Đây là alias trong tài liệu, không phải thư mục cần tạo.

**Chu trình bắt buộc cho task có logic:** thêm test mô tả failure → chạy thấy fail đúng nguyên nhân → sửa tối thiểu → chạy test liên quan → xem ảnh/trace nếu là giao diện. Không tạo framework dùng chung chỉ phục vụ một trường hợp. Không xóa assertion/testTag cũ để làm test xanh; thay đổi UX có chủ đích phải cập nhật test tương ứng.

### Đợt A — Chốt baseline và thiết kế

#### A1 — Khóa phiên bản, bộ ảnh và baseline [P0]

**Đọc:** `app/build.gradle.kts`, `docs/manual-test-matrix.md`, `scripts/performance/README.md`, `SRC/core/performance/VideoOpenMetrics.kt`.
**Sửa code:** Không.
**Đầu ra:** Bộ ảnh trước thay đổi, trace/log baseline, checklist tính năng đang có.

- [ ] Đối chiếu APK người dùng đang cài với source/version; nếu khác, xác định source cần làm trước khi viết code. Không fetch/pull khi chưa được cho phép.
- [ ] Chụp Home, Search input/results, Watch portrait/fullscreen/controls, mini-player, Library, Channel, Subscriptions và Settings trong light/dark; chụp thêm lỗi/rỗng/loading.
- [ ] Chạy baseline theo phần 4 trên ít nhất một máy Android thật; thêm API 26 và API 35 cho tương thích khi có thiết bị/emulator kiểm thử riêng.
- [ ] Đánh dấu chức năng vốn chưa hoạt động, các phát hiện chỉ từ source và những điểm đã tái hiện; không nhận toàn bộ lỗi cũ là lỗi của redesign.
- [ ] Nghiệm thu: có version/commit, thông tin thiết bị, ảnh và số liệu thô; không điền “PASS” từ các báo cáo release cũ.

#### A2 — Duyệt bộ thiết kế đại diện [P0, sau A1]

**Đầu ra:** Bốn frame Home, Search, Watch, Library ở light/dark, thêm player sheet và mini-player; annotations về spacing/type/state. Dùng công cụ thiết kế sẵn có, không cần build một web mockup riêng.

- [ ] Áp palette/token phần 2 cho các frame, đặt ảnh/nội dung giống baseline để thấy khác biệt thực sự.
- [ ] Thể hiện 360dp và một frame 320dp/font lớn; bảng trạng thái loading/empty/error/success dùng chung.
- [ ] Kiểm contrast và mật độ; so hai ảnh trước-sau cùng nội dung, không dùng thumbnail đẹp hơn để che chất lượng layout.
- [ ] Xin duyệt hướng thị giác trước khi nhân rộng; nếu chưa được duyệt, giữ trạng thái “đề xuất”, không tự coi tài liệu này là phê duyệt.

### Đợt B — Design system, thành phần và trạng thái

#### B1 — Chuẩn hóa theme, typography và app chrome [P1, sau A2]

**Sửa:** `SRC/core/designsystem/{Color,HPreTheme,Type,Layout}.kt`, `SRC/navigation/RootScaffold.kt`; chuỗi trong `RES/values/strings.xml`, `RES/values-en/strings.xml` khi cần.
**Test:** mở rộng `UI/navigation/NavigationFlowTest.kt`; mới `UI/core/designsystem/HPreThemeTest.kt`.
**Giao tiếp:** giữ `HPreTheme(darkTheme, content)` và navigation routes; bổ sung token vào các object sẵn có, không thêm ThemeManager.

- [ ] Viết test cho theme role đang dùng, label tab và kích thước vùng bấm; kiểm tra màu chữ/nền của primary/container đạt contrast.
- [ ] Gán đầy đủ semantic color roles và type/shape dùng chung; thay các giá trị hardcode UI nằm trong phạm vi task theo token đã duyệt.
- [ ] Chỉnh header/nav theo phần 3.1; không di chuyển controller/player construction vào Scaffold.
- [ ] Chạy test NavigationFlowTest; xác minh tab restore state và mini-player không bị che bởi navigation bar, cutout hoặc gesture inset.
- [ ] Nghiệm thu: sáng/tối đồng bộ, không trôi màu mặc định ở mini-player/chip; không crop nội dung ở font 200%; theme preference cũ còn nguyên.

#### B2 — VideoCard, compact row và trạng thái ảnh [P1, sau B1]

**Sửa:** `SRC/ui/common/VideoCard.kt`; các caller `ui/home/HomeScreen.kt`, `ui/search/SearchScreen.kt`, `ui/channel/ChannelScreen.kt`, `ui/library/SubscriptionsScreen.kt`, `ui/watch/RelatedVideosSection.kt`; callback navigation ở `SRC/navigation/HPreNavHost.kt` và `RootScaffold.kt` chỉ khi luồng yêu cầu.
**Test:** mới `UI/ui/common/VideoCardTest.kt`; mở rộng `UI/navigation/HomeToWatchNavigationTest.kt` và `UI/ui/search/SearchScreenTest.kt`.
**Giao tiếp đề xuất:** giữ nguyên ba tham số đầu của `VideoCard(video, onClick, modifier)`; thêm cuối `onChannelClick: ((ContentKey) -> Unit)? = null`, `compact: Boolean = false`, `horizontalPadding: Dp = HPreSpacing.Large`. Callback null hoặc channelKey null thì không công bố action mở kênh. Không dùng callback rỗng để giả tính tương tác.

- [ ] Test chạm thumbnail/title gọi video đúng một lần; chạm vùng kênh chỉ gọi channel; title dài/thiếu channel/ảnh lỗi/LIVE vẫn đúng semantics.
- [ ] Chỉnh hierarchy và thêm layout compact trong cùng file; dùng chung phần thumbnail/metadata nhỏ nếu thực sự lặp, không tạo renderer framework.
- [ ] Cho caller kiểm soát mép ngang để Related trong Watch không bị cộng dồn padding 16dp+16dp; Home/Channel/Subscriptions dùng card lớn, Search dùng compact.
- [ ] Placeholder/error của Coil giữ nguyên aspect ratio và fallback; giữ ImageLoader hiện có và không bật crossfade toàn cục. Thêm contentType cho list dị loại bên Search.
- [ ] Nghiệm thu: không đổi height khi ảnh về, key item vẫn ổn định, không tải ảnh lại chỉ do tick playback, 320dp/font 200% có layout đọc được.

#### B3 — Tải, lỗi và giữ dữ liệu khi refresh [P0, sau B1]

**Sửa:** `SRC/ui/common/ErrorPane.kt`, `SRC/ui/home/{HomeScreen,HomeViewModel}.kt`, `SRC/ui/library/{SubscriptionsScreen,SubscriptionFeedViewModel}.kt`, `SRC/ui/watch/{RelatedVideosSection,CommentsSection}.kt`.
**Test:** `UI/ui/common/ErrorPaneTest.kt`, `UNIT/ui/home/HomeViewModelTest.kt`; mới `UNIT/ui/library/SubscriptionFeedViewModelTest.kt` và `UI/ui/home/HomeScreenStateTest.kt`.
**Giao tiếp:** thêm trong ErrorPane.kt `InlineErrorPane(error: AppError, onRetry: () -> Unit, modifier: Modifier = Modifier, testTag: String = "inline_error")`; dùng chung ánh xạ error→message/RetryPolicy với ErrorPane. EmptyPane có thêm action label/callback nullable ở cuối, không đổi caller cũ. Skeleton video đặt cùng VideoCard.kt, delay dùng hằng 180ms hiện có.

- [ ] Tái hiện test Home có dữ liệu rồi refresh fail: video vẫn bấm được, banner không fillMaxSize; test subscription Content→refresh→failure không mất item.
- [ ] Đổi lỗi phụ/loading trong section sang kích thước wrap-content; chỉ full-screen error khi không có nội dung. Không dùng spinner full-screen làm footer list.
- [ ] Bổ sung `isRefreshing`, `refreshError` mặc định vào `SubscriptionFeedUiState.Content`; giữ Content khi refresh, cập nhật thành công hoặc gắn lỗi; chặn kết quả cũ bằng generation khi cần. Giữ cancellation và không gọi lại do recomposition.
- [ ] Phân biệt chip đang chọn với chip tạo dữ liệu đang hiển thị trong HomeContent bằng query/key. Nếu đổi chip fail, giữ nhãn nguồn cũ và retry chip mới, không mô tả danh sách cũ là kết quả chip mới.
- [ ] Nghiệm thu: cache hit không nháy spinner; delay 180ms có phản hồi khi chậm; rỗng có CTA thật; RetryPolicy và dữ liệu cũ đều được giữ.

### Đợt C — Luồng Search và trải nghiệm xem

#### C1 — Search rõ trạng thái, back không mất ngữ cảnh [P0/P1, sau B2/B3]

**Sửa:** `SRC/ui/search/{SearchScreen,SearchViewModel,PaginationTriggerPolicy}.kt`, `SRC/navigation/HPreNavHost.kt` nếu cần truyền state/route.
**Test:** `UNIT/ui/search/{SearchViewModelTest,PaginationTriggerPolicyTest}.kt`, `UI/ui/search/SearchScreenTest.kt`, `UI/navigation/NavigationFlowTest.kt`.
**Giao tiếp:** thêm cuối `SearchUiState.Content` trường `paginationError: AppError? = null`; dùng `loadNextPage()` cho retry token còn giữ. State query hiển thị đã commit và focus chỉ thuộc Search, không đưa vào global player state.

- [ ] Test page 1 thành công, page 2 fail: page 1 còn nguyên, paginationError có giá trị, retry token page 2 đúng một request; đổi query trước khi retry không đưa item query cũ vào query mới.
- [ ] Thêm footer lỗi/retry và chặn auto-load khi đang lỗi; reset trigger policy có chủ đích khi retry thành công/generation thay đổi. Bảo toàn giới hạn 300 và thông báo items bị loại.
- [ ] Lưu trạng thái “đã focus lần đầu” bằng rememberSaveable; dùng SaveableState/hoisted listState để giữ query/filter/scroll qua Back. Khi query mới được commit reset list về đầu, không reset trong lúc còn gõ.
- [ ] Giữ debounce hiện tại, kiểm dedupe giữa IME submit và debounce. Hiển thị suggestions trong lúc chỉnh query dù còn Content; đóng khi submit hoặc chọn kết quả, không ép mở lại bàn phím từ Watch.
- [ ] Nghiệm thu: back từ video về đúng kết quả/vị trí, gõ tiếng Việt không giật bàn phím, pagination fail có đường phục hồi, không thêm request khi quay lại.

#### C2 — Watch và mini-player: thao tác rõ, phát liên tục [P1, sau B1–B3]

**Sửa:** `SRC/ui/watch/{WatchScreen,PlayerControls,CommentsSection,RelatedVideosSection}.kt`, `SRC/ui/player/MiniPlayer.kt`, `SRC/navigation/HPreNavHost.kt`.
**Test:** `UI/ui/watch/{WatchScreenTest,WatchRecreationTest}.kt`, `UI/ui/player/MiniPlayerTest.kt`, `UNIT/ui/watch/{PlayerControlsPolicyTest,PlayerGesturePolicyTest,WatchViewModelTest}.kt`, `UI/navigation/NavigationFlowTest.kt`.
**Giao tiếp:** thêm callback mở Channel có kiểu `(ContentKey) -> Unit` vào WatchScreen/WatchMetadataContent và truyền từ NavHost; không derive ID từ tên kênh. Giữ nguyên player controller/SurfaceOwner protocol. Dùng `commentsItems` trong LazyColumn của sheet thay vì viết lại pagination.

- [ ] Test trước: mở/đóng sheet không seek/recreate player; Back đóng sheet trước; chạm kênh mở route đúng; lưu playlist fail vẫn giữ sheet và text; nhấn lưu liên tiếp không ghi trùng.
- [ ] Sắp xếp lại metadata/channel/actions theo phần 3.3; chuyển comments và lưu playlist sang ModalBottomSheet. Đưa `snapshotFlow`/sentinel pagination hiện nằm trong WatchMetadataContent sang listState của sheet bình luận; giữ guard token/generation và chỉ chạy khi sheet mở. Test cuộn đến footer tải đúng một trang, đóng/mở không tải trùng. Sheet chỉ lấy data hiện có/luồng load có chủ đích, không prefetch toàn bộ comments.
- [ ] Chuẩn hóa menu quality/speed và trạng thái selected bằng semantics; menu không phát sinh gesture thu nhỏ/tua; không auto-hide controls khi thao tác; PiP không bị che bởi sheet khi rời app.
- [ ] Làm mini-player dùng đúng 16:9, status loading/error/paused/playing, padding/inset; vẫn giữ progress polling 500ms tách riêng, không đẩy tick lên root.
- [ ] Nghiệm thu: mọi nút cũ còn truy cập được ở portrait/landscape/font lớn; 20 lần chuyển surface không mất vị trí/âm thanh; không dùng ảnh cover để che lỗi handoff. Nếu phát hiện lỗi ở PlayerSurface/coordinator, mở ticket riêng với test tái hiện trước khi sửa phần engine.

### Đợt D — Hoàn thiện các màn còn lại

#### D1 — Theo dõi và Channel [P1, sau B2/B3 và D2]

**Sửa:** `SRC/ui/library/SubscriptionsScreen.kt`, `SRC/ui/channel/ChannelScreen.kt`, `SRC/navigation/HPreNavHost.kt`; `SRC/ui/library/LibraryViewModel.kt` chỉ cho state mutation được định nghĩa ở D2.
**Test:** mới `UI/ui/library/SubscriptionsScreenTest.kt`, `UI/ui/channel/ChannelScreenTest.kt`; mở rộng `UNIT/ui/channel/ChannelViewModelTest.kt` nếu thay luồng load.
**Giao tiếp:** dùng `Channel.avatarUrl/bannerUrl/subscriberCountText/description` sẵn có; dùng callback onChannelClick hiện có; sheet Quản lý gọi unsubscribe qua ViewModel, không gọi repository từ composable.

- [ ] Test 30 kênh vẫn thấy feed gần đầu màn; mở quản lý có đủ kênh; hủy xác nhận bỏ theo dõi không thay dữ liệu; một kênh lỗi không làm biến mất video kênh khác.
- [ ] Chuyển danh sách kênh dài ở đầu sang LazyRow và sheet quản lý; thêm refresh đúng nhãn/ngữ cảnh, giữ hành động bỏ theo dõi đã có.
- [ ] Render header Channel với avatar/banner thật; tên/subscriber/mô tả có thứ bậc; thiếu ảnh không giữ khoảng trống banner quá lớn, mô tả có mở rộng.
- [ ] Nghiệm thu: tất cả kênh quản lý được, partial error gọn, không fetch theo từng frame/scroll; tập video/shorts vẫn giữ như baseline.

#### D2 — Thư viện, lịch sử và playlist đáng tin cậy [P0/P1, sau B2/B3]

**Sửa:** `SRC/ui/library/{LibraryScreen,LibraryViewModel,HistoryScreen,PlaylistsScreen,PlaylistDetailScreen}.kt`, `SRC/navigation/HPreNavHost.kt`.
**Test:** `UNIT/ui/library/LibraryViewModelTest.kt`, `UI/ui/library/LibraryScreenTest.kt`, `UI/navigation/NavigationFlowTest.kt`; reuse DAO/repository tests hiện có.
**Giao tiếp:** LibraryScreen thêm callback mở Channel bắt buộc từ NavHost; SubscriptionAvatarItem nhận onClick. Giữ chữ ký mutation hiện có để không làm hỏng caller; thêm `mutationState: StateFlow<LibraryMutationState>` với `operation: String?`, `inFlight: Boolean`, `error: AppError?`, `completed: Boolean`, và `consumeMutationResult(): Unit` trong LibraryViewModel. Chỉ một mutation thư viện tại một thời điểm trong đợt này; UI disable đúng các hành động ghi trong thời gian đó, không khóa cuộn/đọc. Không giữ trạng thái này sau process death.

- [ ] Test avatar kênh mở đúng Channel; create/rename/delete/reorder/unsubscribe repository fail được phản ánh, không báo thành công; double-tap create trong inFlight chỉ có một lời gọi.
- [ ] Nối callback avatar, đồng bộ header/rows/empty states; dùng shouldOfferResume cho nhãn xem tiếp, giữ giới hạn 8 lịch sử gần đây và 50 item/trang lịch sử.
- [ ] Xử lý AppResult của tất cả mutation nêu trên tại ViewModel; dialog tạo/sửa chỉ đóng khi success, error giữ nội dung nhập; snackbar lỗi không mất vì consume trước khi render. Tôn trọng CancellationException.
- [ ] Xác nhận trước xóa tất cả lịch sử/xóa playlist; bỏ theo dõi dùng xác nhận ở D1. Xóa một history item trong đợt này cũng dùng xác nhận gọn thay vì dựng hệ thống undo có thể khôi phục sai dữ liệu.
- [ ] Nghiệm thu: playlist thao tác được như cũ, reorder không mất entry, không thay schema, lỗi lưu có đường thử lại và không đóng form sớm; kiểm process recreation không lặp mutation đã hoàn thành.

#### D3 — Settings, copy và accessibility toàn app [P1, sau B1/C2/D1/D2]

**Sửa:** `SRC/settings/SettingsScreen.kt`, `RES/values/strings.xml`, `RES/values-en/strings.xml`; UI trong các task trước chỉ khi kiểm phát hiện thiếu semantics/overflow.
**Test:** mở rộng Settings cases trong `UI/ui/library/LibraryScreenTest.kt`; mới `UI/ui/common/AccessibilityLayoutTest.kt`.
**Giao tiếp:** giữ SettingsRepository/DataStore key; không đổi cách lưu preference hoặc mặc định theme.

- [ ] Chuẩn hóa section/row, show giá trị hiện tại, switch có label rõ; kiểm update/loading/cache-clear không báo thành công sớm hoặc gửi lặp.
- [ ] Đồng bộ từ ngữ: “Làm mới” cho yêu cầu cập nhật, “Thử lại” chỉ sau lỗi, “Đã lưu” chỉ sau success; localization đầy đủ cả vi/en.
- [ ] Test 320/360/411dp, font 1.0/1.3/2.0, sáng/tối; TalkBack không đọc trùng thumbnail/title, đọc được trạng thái selected/expanded/disabled và giá trị slider.
- [ ] Kiểm switch/row không toggle hai lần do nested click; mọi thao tác bằng gesture có nút thay thế; focus trở về đúng nơi khi đóng sheet.
- [ ] Nghiệm thu: không bị keyboard/system inset che CTA, không cắt mất nút ở font lớn, không mất cài đặt sau xoay máy/khởi động lại.

### Đợt E — Đo, sửa điểm nghẽn có bằng chứng, nghiệm thu

#### E1 — Performance và ổn định navigation [P0, sau các task UI]

**Đọc trước:** `SRC/HPreApplication.kt`, `SRC/navigation/RootScaffold.kt`, `SRC/ui/watch/{WatchScreen,PlayerControls,WatchViewModel}.kt`, `SRC/ui/player/MiniPlayer.kt`, `SRC/core/performance/VideoOpenMetrics.kt`.
**Sửa:** chỉ composable/ViewModel bị trace chỉ ra; không mặc định sửa hết các file kể trên.
**Test:** test regression tương ứng plus `UNIT/ImageCachePolicyTest.kt`, `UI/ui/home/HomeScreenIdlePrewarmTest.kt`, `UI/navigation/NavigationFlowTest.kt`.

- [ ] Chạy cùng bộ kịch bản A1, ghi p50/p95/jank/PSS/CPU/request trước-sau theo phần 4; không so số đo khác thiết bị/cache/build type.
- [ ] Dùng trace xác định recomposition/measure/decode/GC/main-thread I/O; kiểm structuralFlow và progress riêng không bị nhập lại sau redesign.
- [ ] Chỉ tối ưu nơi có bằng chứng: key/contentType, remember phép tính thật sự lặp, ảnh theo size render, collectAsStateWithLifecycle, job cancellation. Không rải @Stable/@Immutable thiếu căn cứ hoặc thêm cache thứ hai.
- [ ] Test back stack, rapid tap, process recreation, scroll state per dataset, polling khi màn ẩn; giữ cold-start idle prewarm, không khởi tạo player sớm chỉ để hiện UI.
- [ ] Nghiệm thu: đạt budget hoặc ghi rõ gate chưa đạt; không tuyên bố “mượt hơn” chỉ dựa vào số test pass. Cải thiện latency không được đánh đổi chất lượng/tỷ lệ lỗi phát.

#### E2 — Cổng nghiệm thu và bàn giao [P0, cuối cùng]

**Đầu ra:** source diff khi được phép triển khai, ảnh trước-sau cùng dữ liệu, video luồng chính, báo cáo test, bảng performance, danh sách giới hạn còn lại. APK nội bộ chỉ tạo khi có yêu cầu và cấu hình ký hợp lệ; không publish GitHub.

- [ ] Chạy unit/lint/build/instrumentation trên môi trường test riêng bằng các lệnh ở phần 6.
- [ ] Chạy flow Home→Watch→mini→Search→Back; Watch→Channel→Back; follow→feed→unfollow; create/save/reorder/delete playlist; history resume; đổi theme/ngôn ngữ; fullscreen/PiP/phát nền.
- [ ] Kiểm mạng chậm/mất mạng, ảnh lỗi, upstream content unavailable, font lớn/TalkBack, gesture nav và 3-button nav; API 26 và 35, thêm máy Android người dùng đang dùng nếu khác.
- [ ] So ảnh đối chiếu với A2, kiểm mọi chức năng baseline vẫn tồn tại; manual QA xác nhận riêng, không suy từ unit pass ra PiP/first-frame đã đạt.
- [ ] Bàn giao từng gate PASS/FAIL/NOT RUN kèm bằng chứng; không tự sửa trạng thái thành PASS khi thiếu thiết bị. Không commit/push/PR/release nếu chưa có yêu cầu mới.

## 6. Lệnh kiểm tra để SWE dùng sau khi được giao triển khai

Chạy từ repository HPre, có JDK 17 và Android SDK 35. Các lệnh này **không được chạy trong phiên chỉ lập plan**.

### 6.1. Build và test tĩnh — lệnh hiện có trong CI

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --no-daemon
```

Đạt khi unit tests pass, lint không có lỗi mới và app/instrumentation APK compile thành công. Không chạy release signing bằng credentials giả.

### 6.2. Test có thiết bị

Chỉ chạy khi thiết bị/emulator đã được xác nhận là môi trường kiểm thử có thể cài app test. Debug applicationId hiện trùng `com.hpre.app`, không tùy tiện dùng máy chứa dữ liệu HPre thật.

```bash
./gradlew connectedDebugAndroidTest --no-daemon
```

Chạy targeted suites, ví dụ sau task C1/C2:

```bash
./gradlew connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.hpre.app.ui.search.SearchScreenTest,com.hpre.app.ui.watch.WatchScreenTest,com.hpre.app.ui.player.MiniPlayerTest,com.hpre.app.navigation.NavigationFlowTest' --no-daemon
```

HPreTestRunner loại LivePlaybackGateTest khỏi connected run mặc định. Vì vậy connected test xanh không chứng minh phát được video thật. Live playback/upstream test phải chạy riêng theo quy trình hiện có và có sự cho phép, không lấy placeholder/test fake làm bằng chứng.

### 6.3. Thu thập bằng chứng trên thiết bị test

```bash
adb shell dumpsys gfxinfo com.hpre.app
adb shell dumpsys meminfo com.hpre.app
adb logcat -d -s HPrePerformance:D '*:S'
```

Các lệnh chỉ là đầu vào, không tự tạo benchmark đủ chuẩn. Phải đánh dấu cửa sổ thời gian và kịch bản, lấy trace Perfetto ở API hỗ trợ, đối chiếu first-frame với hình thật. Tránh gộp toàn bộ startup/idle vào mẫu cuộn. Log bàn giao không chứa token hoặc URL stream có chữ ký.

## 7. Thứ tự ưu tiên và giới hạn đợt đầu

### 7.1. Thứ tự triển khai

1. **A1→A2:** đo và duyệt thiết kế. Không nhảy vào đổi màu toàn app khi chưa có ảnh baseline.
2. **B1→B2/B3:** nền thị giác, video components, state/loading/error. B2/B3 chỉ chia việc khi không cùng sửa file/callback mà chưa thống nhất.
3. **C1→C2:** Search và Watch/mini-player; những flow sử dụng thường xuyên nhất.
4. **D2→D1→D3:** Thư viện/playlist và mutation state trước; Theo dõi/Channel sử dụng state đó; sau cùng Settings/accessibility. Mã task giữ theo nhóm màn hình, thứ tự thực hiện là D2 trước D1.
5. **E1→E2:** đo lại, xử lý regression có bằng chứng và nghiệm thu. Mỗi đợt phải có app chạy được, không chờ cuối mới ghép UI.

### 7.2. Không làm trong đợt đầu

1. Không rewrite player, đổi extractor, thay kiến trúc DI/navigation/Room, nâng hàng loạt dependency hoặc đụng signing/release.
2. Không thêm đăng nhập, đồng bộ cloud, tải video offline, recommendation engine mới, remote playlist backend hoặc bộ lọc kênh có network pipeline mới.
3. Không làm glassmorphism, blur nền liên tục, animated background, autoplay preview trên mọi card hoặc bộ font tải từ mạng.
4. Không thêm gesture mới, shared-element video surface, app theme editor, dashboard benchmark thường trực hoặc hệ thống undo dữ liệu tổng quát.
5. Tablet hai cột/rail và baseline profiles là nhánh sau khi có nhu cầu/số đo. Đợt này vẫn phải kiểm không vỡ bố cục khi xoay hoặc cửa sổ rộng, nhưng không tự bật lại nhánh form-factor từng bị revert.

### 7.3. Định nghĩa hoàn thành

**Đẹp hơn:** bốn màn chính và toàn bộ màn phụ dùng cùng design system; ảnh trước-sau được duyệt, không chỉ khác màu.

**Dễ dùng hơn:** thao tác có phản hồi, đường mở kênh đầy đủ, refresh không mất nội dung, lỗi pagination/lưu dữ liệu có đường khôi phục, Back giữ ngữ cảnh.

**Mượt hơn:** có số đo cùng điều kiện, đạt budget đã thống nhất; playback/PiP/background không hồi quy, không tăng tài nguyên vô lý.

**Bàn giao an toàn:** không mất dữ liệu/tính năng, không có action giả, không thay quyền hạn hoặc chính sách; mọi mục chưa test được ghi NOT RUN. Không upload GitHub trong phạm vi yêu cầu này.
