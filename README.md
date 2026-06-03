# TamronDebugApp

Compose + Kotlin 的腾龙机芯调试 App 骨架，包含：

- RTSP 预览：`SurfaceView + AndroidView`，内置 `:easyplayer-android` 兼容模块，默认走 Android 系统硬解。
- 腾龙 VISCA 控制：变倍、聚焦、白平衡、曝光、日夜模式、降噪、WDR、BLC、EIS、Defog 等。
- 编码板接口模板：分辨率、帧率、码率、CBR/VBR。实际接口需要按你的编码板 HTTP API 调整。

## 当前修复点

如果构建时报：

```text
Could not resolve org.ow2.asm:asm:9.6
Could not GET https://repo.maven.apache.org/... Received status code 403
```

这是 Gradle 插件依赖下载失败，不是 Kotlin/Compose 源码错误。工程已经在 `settings.gradle.kts` 中把阿里云、腾讯云、华为云 Maven 镜像放到官方仓库前面。

## 建议构建步骤

1. 关闭 Android Studio 中的 Gradle Offline Mode。
2. 删除当前工程下的 `.gradle`、`build`、`app/build`、`easyplayer-android/build`。
3. 删除或刷新失败缓存：
   - Windows: `C:\Users\你的用户名\.gradle\caches\modules-2\files-2.1\org.ow2.asm`
   - 也可以直接删除 `C:\Users\你的用户名\.gradle\caches` 后重新 Sync。
4. 重新打开工程执行 Sync。
5. 如果仍然访问 `repo.maven.apache.org` 报 403，可临时把 `settings.gradle.kts` 里 `google()`、`mavenCentral()`、`gradlePluginPortal()` 三个官方仓库注释掉，只保留镜像。
6. 命令行构建可使用：

```bash
gradle --init-script gradle/init-repositories.gradle :app:assembleDebug
```

## EasyPlayer 说明

本工程内置的是 EasyPlayer API 兼容层，保证工程结构可以直接编译，并使用 Android `MediaPlayer` 播放 RTSP。后续如果需要官方 EasyPlayer-RTSP-Android 的低延迟、录像、抓图、I420 回调、JNI so 等完整能力，可以用 `tools/fetch_easyplayer_from_github.*` 拉取官方仓库后替换 `:easyplayer-android` 模块。

## 关于 EasyPlayer

本版本不再使用模拟 EasyPlayer 代码。请先运行 `tools/import_easyplayer_official.ps1` 或 `tools/import_easyplayer_official.sh` 导入官方 EasyPlayer-RTSP-Android 的 `library/src/main`。

详细见 `README_EASYPLAYER_OFFICIAL.md`。
