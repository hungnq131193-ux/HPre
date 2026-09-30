# HPre

[![Android CI](https://github.com/hungnq131193-ux/HPre/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/hungnq131193-ux/HPre/actions/workflows/android.yml)
[![Latest Release](https://img.shields.io/github/v/release/hungnq131193-ux/HPre)](https://github.com/hungnq131193-ux/HPre/releases/latest)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)
[![Android API](https://img.shields.io/badge/Android-API%2026%2B-3DDC84.svg?logo=android)](https://developer.android.com)

HPre là trình xem video độc lập cho Android, tập trung vào trải nghiệm nhanh, gọn và riêng tư. Ứng dụng dùng [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) để truy cập dữ liệu công khai (không qua YouTube Data API) và phát nội dung bằng AndroidX Media3/ExoPlayer.

## Tính năng

- **Khám phá & tìm kiếm**: trang chủ theo khu vực, tìm video, kênh và danh sách phát.
- **Phát linh hoạt**: video hoặc chỉ âm thanh, chọn chất lượng và tốc độ, toàn màn hình, thu nhỏ, phát nền và Picture-in-Picture.
- **Tương tác đầy đủ**: bình luận mở rộng, thông tin kênh, nội dung liên quan.
- **Thư viện cá nhân**: lịch sử xem, danh sách phát và kênh theo dõi — lưu hoàn toàn trên máy.
- **Giao diện**: Material 3, sáng/tối/theo hệ thống, tiếng Việt và tiếng Anh.
- **Cập nhật**: kiểm tra bản mới ngay trong Cài đặt, tải trực tiếp từ GitHub Releases.

## Ảnh màn hình

Sẽ được bổ sung sau.

## Tải xuống & cài đặt

1. Mở trang [Releases](https://github.com/hungnq131193-ux/HPre/releases) và tải APK mới nhất (tên file bắt đầu bằng `HPre-`).
2. Mở APK trên thiết bị Android 8.0 (API 26) trở lên.
3. Cho phép "cài ứng dụng không rõ nguồn" nếu hệ thống yêu cầu.
4. Các bản sau có thể cài đè trực tiếp (cùng signing key), dữ liệu được giữ nguyên.

> Chỉ tải APK từ GitHub Releases của repository này.

## Build từ source

Yêu cầu: JDK 17, Android SDK với Platform API 35.

```bash
git clone https://github.com/hungnq131193-ux/HPre.git
cd HPre
./gradlew assembleDebug      # Windows: gradlew.bat assembleDebug
```

APK debug nằm tại `app/build/outputs/apk/debug/`.

### Kiểm thử

```bash
./gradlew testDebugUnitTest                 # unit tests
./gradlew compileDebugAndroidTestKotlin     # biên dịch instrumentation tests
```

## Kiến trúc

```text
Compose UI / Navigation
        ↓
ViewModel (Flow)
        ↓
Repository (Room, DataStore)
        ↓
VideoService adapter
        ↓
NewPipeExtractor ─── OkHttp

Playback UI
        ↓
MediaSession / Media3 ExoPlayer
```

- Extractor được bọc sau adapter `VideoService` trung lập với nhà cung cấp; UI không phụ thuộc trực tiếp vào NewPipe.
- Dữ liệu thư viện và cài đặt lưu cục bộ bằng Room/DataStore — không có tài khoản, không đồng bộ đám mây.
- Extractor dùng fork `hungnq131193-ux/NewPipeExtractor` vá thêm việc đọc số rút gọn địa phương hoá (N/Tr/T) cho locale tiếng Việt.

## Công nghệ chính

| Thành phần | Dùng cho |
| --- | --- |
| Kotlin, Coroutines/Flow | Toàn bộ ứng dụng |
| Jetpack Compose, Material 3 | UI |
| AndroidX Media3/ExoPlayer | Phát video/âm thanh |
| NewPipeExtractor (fork) | Trích xuất dữ liệu công khai |
| Room, DataStore | Lưu trữ cục bộ |
| OkHttp, Coil | Mạng và tải ảnh |

## Phát hành

Release build dùng signing key riêng của người phát hành; keystore không nằm trong repository. Trước mỗi bản phát hành phải tăng cả `versionName` và `versionCode`, và bản mới phải cài đè thành công (`adb install -r`) lên APK đã phát hành trước khi tạo tag và GitHub Release. Quy trình chi tiết nằm trong [`scripts/release`](scripts/release/README.md).

## Giấy phép

[GPL-3.0-or-later](LICENSE). Thông tin các thành phần bên thứ ba: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Miễn trừ trách nhiệm

HPre là dự án độc lập, không được phát triển, tài trợ hay xác nhận bởi YouTube, Google hay NewPipe. Thương hiệu và nội dung bên thứ ba thuộc về chủ sở hữu tương ứng.
