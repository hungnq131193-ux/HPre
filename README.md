# HPre

[![Android CI](https://github.com/hungnq131193-ux/HPre/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/hungnq131193-ux/HPre/actions/workflows/android.yml)
[![Latest Release](https://img.shields.io/github/v/release/hungnq131193-ux/HPre)](https://github.com/hungnq131193-ux/HPre/releases/latest)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)
[![Android API](https://img.shields.io/badge/Android-API%2026%2B-3DDC84.svg?logo=android)](https://developer.android.com/about/versions/oreo)

## Giới thiệu

HPre là ứng dụng khách video độc lập dành cho Android. Ứng dụng sử dụng NewPipeExtractor để xử lý dữ liệu và luồng công khai, không sử dụng YouTube Data API chính thức cho chức năng này. Việc phát nội dung được thực hiện bằng AndroidX Media3/ExoPlayer.

## Tính năng

- Duyệt nội dung tại trang chủ.
- Tìm kiếm video, kênh và danh sách phát.
- Phát video hoặc chỉ âm thanh, chọn chất lượng và tốc độ phát.
- Toàn màn hình, trình phát thu nhỏ, phát nền và Picture-in-Picture.
- Mở/thu gọn phần bình luận; mở rộng từng bình luận dài và xem nội dung liên quan.
- Lưu cục bộ lịch sử xem, danh sách phát và kênh theo dõi.
- Giao diện theo hệ thống, sáng hoặc tối.
- Hỗ trợ tiếng Việt và tiếng Anh.
- Kiểm tra cập nhật thủ công trong Cài đặt và mở trang GitHub Release chính thức.

## Screenshots

Sẽ được bổ sung sau.

## Công nghệ

- Kotlin và Coroutines/Flow
- Jetpack Compose và Material 3
- AndroidX Media3/ExoPlayer
- NewPipeExtractor
- Room và DataStore
- OkHttp và Coil

## Kiến trúc

```text
Compose UI / Navigation
        ↓
ViewModel
        ↓
Repository
        ↓
VideoService adapter
        ↓
NewPipeExtractor

Playback UI
        ↓
MediaSession / Media3 ExoPlayer
```

Dữ liệu thư viện và cài đặt người dùng được lưu cục bộ bằng Room và DataStore.

## Build

Yêu cầu JDK 17 và Android SDK API 35.

```bash
# Sau khi clone repository HPre:
cd HPre
./gradlew assembleDebug
```

Trên Windows có thể dùng `gradlew.bat assembleDebug`.

## Phát hành bản release

Release build phải dùng signing key riêng của người phát hành. Keystore và mật khẩu không thuộc source repository và không được chia sẻ công khai. Mọi bản cập nhật phải dùng cùng signing certificate để có thể cài đè an toàn.

Trước mỗi bản phát hành mới, phải tăng cả `versionName` và `versionCode`, sau đó chạy cổng kiểm tra cài đè trong [`scripts/release`](scripts/release/README.md). Tag và GitHub Release chỉ được tạo sau khi APK mới cài đè thành công lên APK đã phát hành bằng `adb install -r`, giữ nguyên dữ liệu cục bộ và có cùng signing certificate.

## Tải xuống

Tải APK đã ký từ mục **Releases** của repository GitHub. Không tải APK từ nhánh source.

## Cài đặt

1. Tải file APK release bắt đầu bằng `HPre-` từ GitHub Releases.
2. Mở APK trên thiết bị Android.
3. Cho phép cài ứng dụng không rõ nguồn nếu Android yêu cầu.
4. Xác nhận cài đặt.
5. Các phiên bản sau có thể cài đè khi được ký bằng cùng signing key.

## Giấy phép

HPre được phát hành theo [GPL-3.0-or-later](LICENSE). Thông tin về các thành phần bên thứ ba nằm trong [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Tuyên bố miễn trừ

HPre là một dự án độc lập và không được phát triển, tài trợ hoặc xác nhận bởi YouTube hoặc Google. Các thương hiệu và nội dung của bên thứ ba thuộc về chủ sở hữu tương ứng.
