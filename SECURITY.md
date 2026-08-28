# Chính sách bảo mật

## Báo cáo lỗ hổng

Nếu repository đã bật GitHub Private Vulnerability Reporting, hãy mở tab **Security** và chọn **Report a vulnerability** để gửi báo cáo riêng tư.

Nếu tùy chọn này chưa xuất hiện, hãy yêu cầu maintainer bật Private Vulnerability Reporting trước khi chia sẻ chi tiết nhạy cảm. Không đăng công khai exploit, credential, signing key, token, cookie hoặc dữ liệu cá nhân trong issue.

Trong báo cáo riêng tư, vui lòng cung cấp phiên bản HPre, phiên bản Android, bước tái hiện, ảnh hưởng và phương án giảm thiểu nếu đã biết. Hãy xóa mọi secret và dữ liệu cá nhân khỏi log.

## Bảo vệ signing identity

Release keystore và mật khẩu của HPre không được lưu trong repository hoặc GitHub Actions. Mọi bản cập nhật chính thức phải được ký bằng cùng certificate đã dùng cho bản phát hành trước.
