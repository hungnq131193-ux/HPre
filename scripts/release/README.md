# Kiểm tra nâng cấp APK HPre

`verify-android-upgrade.ps1` là cổng bắt buộc trước khi tạo tag hoặc GitHub Release mới. Script kiểm tra package, hai trường version, chữ ký APK, cài đè bằng `adb install -r`, dữ liệu DataStore và smoke test Settings.

## Yêu cầu

- Windows PowerShell 5.1 hoặc mới hơn.
- Android SDK với `apksigner`, `aapt` và `adb`.
- `ANDROID_SDK_ROOT` hoặc `ANDROID_HOME` trỏ đúng SDK.
- Emulator/thiết bị thử nghiệm riêng, không chứa dữ liệu cần giữ.
- APK baseline chính thức và APK candidate đã ký bằng release key HPre.

## Kiểm tra tĩnh

```powershell
.\scripts\release\verify-android-upgrade.ps1 `
  -BaselineApk "$env:USERPROFILE\HPre-release\HPre-v1.0.0-release.apk" `
  -CandidateApk "$env:USERPROFILE\HPre-release\HPre-v1.0.1-release.apk" `
  -StaticOnly
```

## Kiểm tra cài đè đầy đủ

```powershell
.\scripts\release\verify-android-upgrade.ps1 `
  -BaselineApk "$env:USERPROFILE\HPre-release\HPre-v1.0.0-release.apk" `
  -CandidateApk "$env:USERPROFILE\HPre-release\HPre-v1.0.1-release.apk"
```

Nếu có nhiều thiết bị:

```powershell
.\scripts\release\verify-android-upgrade.ps1 `
  -BaselineApk "$env:USERPROFILE\HPre-release\HPre-v1.0.0-release.apk" `
  -CandidateApk "$env:USERPROFILE\HPre-release\HPre-v1.0.1-release.apk" `
  -DeviceSerial "emulator-5554"
```

## Quy tắc cho v1.0.1

1. Tăng `versionName` từ `1.0.0` lên `1.0.1`.
2. Tăng `versionCode` từ `1` lên `2` hoặc một mã chưa dùng cao hơn.
3. Build bằng đúng release key/certificate HPre đã ký v1.0.0.
4. Verify APK và chạy script này với APK v1.0.0 đã phát hành làm baseline.
5. Chỉ tạo tag, GitHub Release và upload APK sau khi install-over gate PASS.

Không uninstall app giữa lúc tạo marker **Phát trong nền** và lệnh `adb install -r`; nếu uninstall thì không còn là bằng chứng nâng cấp giữ dữ liệu.
