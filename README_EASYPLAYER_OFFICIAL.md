# TamronDebugApp - EasyPlayer 官方库接入说明

本工程不再包含任何“伪 EasyPlayer”实现。`easyplayer-android` 必须接入 EasyDarwin 官方 `EasyPlayer-RTSP-Android/library/src/main`。

## 一键导入

Windows PowerShell：

```powershell
./tools/import_easyplayer_official.ps1
```

macOS / Linux：

```bash
./tools/import_easyplayer_official.sh
```

脚本会：

1. 下载 `https://github.com/EasyDarwin/EasyPlayer-RTSP-Android/archive/refs/heads/dev.zip`
2. 复制官方 `library/src/main/java`、`library/src/main/jniLibs`、`library/src/main/res` 到本工程 `easyplayer-android/src/main`
3. 将官方旧包名 `android.support.annotation` 替换为 `androidx.annotation`
4. 校验核心 Java 文件和 so 文件是否完整

## 必须存在的 so

每个 ABI 目录建议包含：

```text
libEasyRTSPClient.so
libAudioCodecer.so
libVideoCodecer.so
libproffmpeg.so
libyuv_android.so
```

官方仓库目前包含 `arm64-v8a`、`armeabi-v7a`、`x86` 三类 ABI。

## 如果公司网络无法访问 GitHub

手动下载官方仓库 ZIP 后，把其中：

```text
EasyPlayer-RTSP-Android-dev/library/src/main/*
```

复制覆盖到：

```text
easyplayer-android/src/main/
```

然后执行：

```bash
python tools/verify_easyplayer_official.py
```

通过后再 Android Studio Sync / Build。
