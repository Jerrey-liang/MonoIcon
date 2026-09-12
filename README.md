# MonoIcon

把 HyperOS（小米澎湃 OS）的第三方应用图标重绘成**单色 Material You 主题图标**的 LSPosed 模块。

MonoIcon 不替换图标包、不需要 MTZ 主题：它在桌面进程里拦截图标加载管线，复用 **AOSP 的 monochrome（单色层）遮罩 + 配色管线**重新合成图标，因此图标形状、遮罩、取色都跟着系统主题走，壁纸换色时图标颜色一起变。

| | |
|---|---|
| 作用域 | `com.miui.home`（桌面）、`com.android.systemui`（通知栏 / 最近任务） |
| 运行环境 | Android 15（API 35）及以上，LSPosed（libxposed API 101 / 102） |
| 模块入口 | `com.jerrey.monoicon.hook.IconThemeHook`（`META-INF/xposed/java_init.list`） |
| 当前版本 | 1.5.1（versionCode 9） |
| 界面语言 | 跟随系统 / 简体中文 / English |

---

## 功能

| 功能 | 说明 |
|---|---|
| 单色图标重绘 | 拦截桌面的图标加载/缓存管线（6 个图标管线钩子 + 3 个启动/返回桌面动画钩子），把彩色图标重绘为单色主题图标 |
| Lawnicons 图标库 | 内置约百个品牌/通用遮罩（`assets/lawnicons`），命中时优先使用，覆盖没有 monochrome 图层的应用 |
| 圆形图标 | 无需 MTZ 主题，把所有图标统一裁成圆形 |
| 通知中心图标 | SystemUI 的通知小图标走同一套单色渲染 |
| 取色方案 | Material Dynamic Color **2025** 的 9 种变体：Monochrome / Neutral / Tonal Spot（默认）/ Vibrant / Expressive / Fidelity / Content / Rainbow / Fruit Salad |
| 重启作用域 | 一键重启作用域内的常驻应用（桌面 + 系统界面），让新配置/新钩子立刻生效（需要 root） |
| 运行日志 | 读取模块自己的 logcat 标签（需要 root），失败时回退到设置进程自己的事件记录 |
| 液态玻璃设置界面 | Miuix（HyperOS 风格）界面 + `miuix-blur` 玻璃底栏，与 LSPosed Manager 同一引擎与配方（模糊 + 半透明表面层，无折射） |

---

## 工作原理

```
com.miui.home（桌面进程）
└─ 图标加载管线（libxposed 拦截链）
   ├─ IdentityResolver          归一名 → 图标身份（包名 / Activity / 图标资源）
   ├─ MaskStrategy              遮罩来源：Lawnicons 资源 → AOSP monochrome → Lab 提取回退
   ├─ ColorStrategy             按当前 Material 2025 变体生成单色前景色
   └─ ThemedIconBuilder         合成 Drawable，并写入三级缓存
                                （IconDrawableCache / IconColorCache / MonochromeCache）

com.android.systemui（系统界面进程）
└─ 通知小图标钩子 → 同一套遮罩 + 配色渲染
```

- 设置进程只负责配置与界面；钩子在这些进程里独立实例化，双方通过模块的共享
  `SharedPreferences`（`monoicon_config`，经 `XposedInterface.getRemotePreferences`）交换配置。
- 配置项：启用总开关、取色变体、Lawnicons、圆形图标、通知图标、界面语言。

---

## 目录结构

```
app/src/main/
├─ java/com/jerrey/monoicon/
│  ├─ hook/          Xposed 入口与钩子（IconThemeHook 为唯一入口，DebugHooks 默认关闭）
│  ├─ theme/         图标主题管线：mask/（遮罩）· color/（取色）· render/（合成）
│  ├─ material2025/  Material Dynamic Color 2025（HCT/CAM16、量化、Scheme*、DynamicColor…）
│  ├─ config/        ConfigManager：设置读写 + 与钩子进程共享
│  ├─ cache/ color/  图标、配色缓存
│  ├─ identity/      图标身份解析
│  ├─ image/ logging/后处理与日志
│  ├─ ui/            Compose 设置界面（screens/、AppStrings 中英文字符串表、ScopeRestart 等）
│  └─ MainActivity.kt
├─ assets/lawnicons/ Lawnicons 遮罩资源（index.tsv / aliases.tsv / masks/*.png）
└─ resources/META-INF/xposed/  module.prop · java_init.list · scope.list
app/keepRules/rules.keep         release 的 R8 保留规则
```

> `outer_res/`、`res_*` 等目录是本地分析/取证用的临时目录，已在 `.gitignore` 中忽略。

---

## 构建

环境要求：

- JDK 17+（推荐用 Android Studio 自带的 JBR）
- Android SDK：`platforms;android-37.2`、`build-tools;37.0.0`（compileSdk 为 37.2，带 minor API level）

```bash
# Windows PowerShell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"

./gradlew :app:assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # → app/build/outputs/apk/release/app-release.apk（R8）
```

工具链版本（见 `gradle/libs.versions.toml`）：

| 组件 | 版本 |
|---|---|
| Gradle / AGP | 9.7.1 / 9.4.0 |
| Kotlin / Compose BOM | 2.4.20 / 2026.09.00 |
| compileSdk / minSdk / targetSdk | 37.2 / 35 / 36 |
| libxposed api & service | 102.0.0 |
| Miuix（ui / icons / blur） | 0.9.3 |

> **注意**：AGP 9.x 自带 Kotlin 支持，但 Kotlin **版本**由根 `build.gradle.kts` 的 buildscript 里 KGP 决定
> —— AGP 9.4 内置的 KGP 2.2.10 读不了 Kotlin 2.4 的元数据，升级 AGP 不会改变 Kotlin 版本。

## 安装与使用

1. 安装 APK（release 版即可），在 **LSPosed Manager 里启用模块**，并勾选作用域：`com.miui.home`、`com.android.systemui`；
2. 重启作用域让钩子加载：用设置页的 **图标样式 → 重启作用域**（需要 root），或手动重启桌面 / 系统界面；
3. 打开 MonoIcon 设置页调整取色方案、Lawnicons、圆形图标、通知图标等。**日志/重启** 功能需要 root（`su`）。

## 测试

```bash
./gradlew :app:testDebugUnitTest            # 单元测试
./gradlew :app:connectedDebugAndroidTest    # 仪器测试（需要已连接的设备）
```

仪器测试套件（`app/src/androidTest`）：`ConfigManagerInit`、`SystemUiIcon`、`RecentsIcon`、
`CircleIconShape`、`LauncherOverlayColor`、`PixelMaskPipeline`、`SettingsScreenComposeTest`。

> 已知情况：`PixelMaskPipelineInstrumentedTest` 有 5 个长期失败用例（历史遗留，非新回归）。

## 已知限制

- 仅支持 Android 15（API 35）及以上：玻璃底栏依赖 AGSL 运行时着色器，没有降级路径；
- 依赖 HyperOS 桌面 / SystemUI 的内部实现，系统大版本升级后钩子点可能需要重新适配；
- 重启作用域、读取系统日志需要 root；
- 图标没有 monochrome 图层时，会退回 Lawnicons 遮罩或 Lab 亮度提取，效果取决于图标本身；
- 设置页首次进入时玻璃底栏有一次性 AGSL 编译开销，首帧略慢。

## 致谢

- [LSPosed](https://github.com/LSPosed/LSPosed) 与 libxposed API —— 模块框架与 API；
- [Miuix](https://github.com/miuix-kotlin-multiplatform/miuix)（`top.yukonga.miuix.kmp`）—— HyperOS 风格 Compose 组件与 `miuix-blur` 玻璃引擎；
- [Lawnicons](https://github.com/LawnchairLauncher/lawnicons) —— 单色图标遮罩资源；
- Material Color Utilities（AOSP）—— `material2025/` 的配色算法来源；
- AndroidX Compose。

## 许可

本仓库目前没有 LICENSE 文件；如需分发或二次修改，请先补充许可证，并遵守上述第三方组件的许可条款。
