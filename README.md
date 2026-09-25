# MonoIcon

把 HyperOS 的第三方应用图标重绘成**单色 Material You 主题图标**的 LSPosed 模块。

无需图标包、无需 MTZ 主题：模块在桌面进程内接管图标加载，复用 AOSP 的单色遮罩与配色管线重新合成图标，因此图标形状与颜色跟随系统主题，换壁纸即换色。

## 功能

- **单色主题图标** — 在 `com.miui.home` 内接管桌面图标、文件夹预览与最近任务的图标管线，把彩色图标重绘为单色主题图标
- **9 种取色方案** — Material Dynamic Color 2025 的 Monochrome / Neutral / Tonal Spot / Vibrant / Expressive / Fidelity / Content / Rainbow / Fruit Salad
- **Lawnicons 遮罩** — 内置 Lawnicons 字形资源，为没有 monochrome 图层的应用提供遮罩（可在设置中关闭）
- **圆形图标** — 不需要 MTZ 主题即可把图标统一裁成圆形（默认关闭）
- **通知图标** — `com.android.systemui` 的通知应用图标使用同一套单色渲染（默认开启）
- **一键重启作用域** — 重启桌面与系统界面，使新配置与新钩子立即生效（需要 root）

## 环境要求

运行：

- Android 15（API 35）或更高版本
- LSPosed，libxposed API 101+（`minApiVersion=101`，`targetApiVersion=102`）
- 作用域：`com.miui.home`、`com.android.systemui`
- root 可选：仅「重启作用域」与读取运行日志需要；不授予时图标主题化照常工作

构建：

- JDK 17 或更高版本
- Android SDK：平台 `android-37.2`、构建工具 `37.0.0`（compileSdk 37.2）

## 安装

1. 从 Releases 安装 APK，或在本地构建后安装 `app/build/outputs/apk/debug/app-debug.apk`
2. 在 LSPosed Manager 中启用本模块，并勾选作用域 `com.miui.home` 与 `com.android.systemui`
3. 重启作用域内应用使钩子生效：使用设置页的「图标样式 → 重启作用域」（需要 root），或手动重启桌面与系统界面
4. 打开 MonoIcon 设置页，按需调整取色方案、圆形图标、Lawnicons 与通知图标

## 构建

```bash
# Windows PowerShell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"

./gradlew :app:assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # → app/build/outputs/apk/release/app-release.apk（已开启 R8）

# 测试
./gradlew :app:testDebugUnitTest            # JVM 单元测试
./gradlew :app:connectedDebugAndroidTest    # 仪器测试，需要已连接的设备
```

Gradle 与 AGP 由 wrapper 与 `gradle/libs.versions.toml` 固定（Gradle 9.7.1 / AGP 9.4.0 / Kotlin 2.4.20）。

## 许可证

[Apache License 2.0](LICENSE)。

二次分发时请同时遵守所使用第三方组件的许可：LSPosed 与 libxposed API、Miuix、Lawnicons、Material Color Utilities、AndroidX。

---

# MonoIcon

An LSPosed module that redraws HyperOS third-party app icons as **monochrome Material You themed icons**.

No icon pack and no MTZ theme required: the module takes over icon loading inside the launcher process and recomposes each icon through the AOSP monochrome mask and colour pipeline, so icon shape and colour follow the system theme and change with the wallpaper.

## Features

- **Monochrome themed icons** — takes over the icon pipeline for desktop icons, folder previews and recents inside `com.miui.home`, redrawing coloured icons as monochrome themed icons
- **9 colour variants** — Monochrome / Neutral / Tonal Spot / Vibrant / Expressive / Fidelity / Content / Rainbow / Fruit Salad from Material Dynamic Color 2025
- **Lawnicons masks** — a bundled Lawnicons glyph set supplies masks for apps that ship no monochrome layer (can be turned off in settings)
- **Circular icons** — clips every icon to a circle without an MTZ theme (off by default)
- **Notification icons** — notification app icons in `com.android.systemui` use the same monochrome rendering (on by default)
- **One-tap scope restart** — restarts the launcher and SystemUI so new settings and hooks take effect immediately (requires root)

## Requirements

Runtime:

- Android 15 (API 35) or newer
- LSPosed with libxposed API 101+ (`minApiVersion=101`, `targetApiVersion=102`)
- Scope: `com.miui.home`, `com.android.systemui`
- Root is optional: only the scope restart and reading runtime logs need it; icon theming works without it

Build:

- JDK 17 or newer
- Android SDK: platform `android-37.2`, build-tools `37.0.0` (compileSdk 37.2)

## Installation

1. Install the APK from Releases, or build it locally and install `app/build/outputs/apk/debug/app-debug.apk`
2. Enable the module in LSPosed Manager and tick the scope `com.miui.home` and `com.android.systemui`
3. Restart the scoped apps so the hooks load: use **Icon style → Restart scope** in the settings screen (requires root), or restart the launcher and SystemUI manually
4. Open the MonoIcon settings screen to adjust the colour variant, circular icons, Lawnicons and notification icons

## Build

```bash
# Windows PowerShell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"

./gradlew :app:assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # → app/build/outputs/apk/release/app-release.apk (R8 enabled)

# Tests
./gradlew :app:testDebugUnitTest            # JVM unit tests
./gradlew :app:connectedDebugAndroidTest    # instrumented tests, needs a connected device
```

Gradle and AGP are pinned by the wrapper and `gradle/libs.versions.toml` (Gradle 9.7.1 / AGP 9.4.0 / Kotlin 2.4.20).

## License

[Apache License 2.0](LICENSE).

When redistributing, also comply with the licences of the third-party components used: LSPosed and the libxposed API, Miuix, Lawnicons, Material Color Utilities and AndroidX.
