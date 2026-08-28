# Đóng góp cho HPre

## Quy trình

1. Fork repository và tạo một topic branch từ `main`.
2. Dùng JDK 17 và Android SDK API 35.
3. Giữ thay đổi tập trung, có test phù hợp và không refactor ngoài phạm vi.
4. Chạy kiểm tra trước khi gửi pull request:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Trên Windows dùng `gradlew.bat`.

5. Mô tả mục tiêu, cách kiểm thử và ảnh hưởng của thay đổi trong pull request.

## An toàn repository

Không commit hoặc đính kèm:

- secret, token, cookie, API key hoặc OAuth credential;
- signing key, keystore hoặc mật khẩu;
- `local.properties`, `.env` hoặc cấu hình máy cá nhân;
- APK, AAB hoặc thư mục build;
- nội dung đa phương tiện có bản quyền dùng làm fixture.

Không đăng dữ liệu cá nhân hoặc credential trong issue, log hay pull request.
