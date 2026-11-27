# HyperOS 4 Profile 技术参考

配置面向 `com.miui.home` 的 ARM64 原生桌面。打包的桌面若含 Java 代码，MonoIcon 使用 Java 路径。原生引擎支持单 APK 桌面。离线工具可读取完整 APK 或一组原始 ELF。导入、兼容范围和设备恢复步骤见[普通用户指南](<HyperOS4 profile编写指南.md>)。

## 基线与输入

随附的 `hyperos4-6144` 使用 schema 1、revision 9。完整系统版本为 `OS4.0.0.3.XAACNXM`，桌面版本号为 `801026144`，版本名称为 `RELEASE-8.01.02.6144-260818-08251107-R`。原有设备证据来自 Redmi K50（22041211AC，Android 17）。当前文件与 2.4.0 标签使用同一份 Profile。[官方配置](../app/src/main/assets/hyperos4/profiles/hyperos4-6144.json) · [6144 二进制身份档案](../external_resources/HyperOS4/builds/6144/manifest.json)

可通过 ADB 读取测试手机的系统和桌面身份。连接多台设备时使用 `adb -s <序列号>`。根据 `pm path` 返回的实际路径提取桌面 APK。

```powershell
adb devices
adb shell getprop ro.product.model
adb shell getprop ro.build.version.incremental
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abilist
adb shell dumpsys package com.miui.home
adb shell pm path com.miui.home
```

保留未修改的输入。下面的命令将采集结果放进 Git 忽略的 `external_resources/HyperOS4/builds/` 候选目录。重跑时使用新的输出子目录。以 `--help` 查看当前命令参数。[采集工具](../tools/launcher_inspector.py)

```powershell
python tools/launcher_inspector.py inspect launcher.apk --out-dir external_resources/HyperOS4/builds/candidate-01/intake
python tools/launcher_inspector.py compare external_resources/HyperOS4/builds/6144/manifest.json external_resources/HyperOS4/builds/candidate-01/intake/manifest.json --out external_resources/HyperOS4/builds/candidate-01/compare.md
```

`inspect` 也接受直接包含 `libapp.so` 与 `libapp_launcher.so` 的目录。此时 APK 摘要为空。记录包名、版本和设备实测属性的独立来源。二进制匹配只说明所比较的文件一致，系统依赖和设备布局仍须核实。采集生成的 `manifest.json` 用于比对，模块导入的是 Profile JSON。

## Schema 1

以当前 Kotlin 导入和 C++ 解析器为准。使用 UTF-8 无 BOM 的 JSON，文件不超过 2 MiB。每个自定义配置使用独立 ID，长度 1–80，只允许小写字母、数字、`-` 和 `_`。同一 ID 更新时可递增 `revision`。JSON 的字节变化会改变摘要，包括格式化。[配置模型](../app/src/main/java/com/jerrey/monoicon/profile/CompatibilityProfile.kt) · [C++ 解析器](../app/src/main/cpp/hyperos4_profile.cpp)

| 字段 | 用途与约束 |
| --- | --- |
| `schema`、`id`、`name`、`revision` | 格式版本、唯一 ID、显示名称及正整数修订号。 |
| `match` | 包名、精确桌面版本、包含两端的 SDK 范围、只读系统属性及可选的完整系统版本。桌面版本号使用十进制字符串。多个属性条件同时生效，同一属性的多个字面前缀满足其一即可。 |
| `images`、`functions`、`patches` | 分别记录原始 ELF 身份、与映射范围一致的完整函数快照，以及对应图标模式的改动。三组都不能为空。 |
| `icons` | 图案查找目录、组件级命名策略及别名。包名可作为回退。大图标另有资源根与尺寸桥接，只有证据完备时才声明。 |
| `settingsKey`、`refresh` | 目标桌面已有的通知键。可选的逐包刷新声明包含真实接收器的 `action` 和 `packageExtra`。 |
| `veneer`、`x-*` | 可选标量模式配置及来源、摘要和验收范围等附加元数据。 |

`match.systemVersions` 中的值与 `ro.build.version.incremental` 区分大小写、完整匹配。如果字段缺失，应用只能在手动确认后启用。确认与文件摘要和系统身份绑定。自动匹配需要一份已声明适配当前系统的唯一配置。匹配与导入逻辑见 [ProfileRepository](../app/src/main/java/com/jerrey/monoicon/profile/ProfileRepository.kt)。

文件摘要、加载段和身份字节应与目标文件一致。受修改函数需要完整的快照和函数边界证据。记录每项补丁的原始值、模式、关闭恢复和证据位置。格式合法和原始值匹配只能证明这些检查所覆盖的事实。跨版本重新核对目标函数、调用约定和资源契约。

## 验证

| 检查 | 记录 |
| --- | --- |
| 引擎解析 | 使用当前 C++ `monoicon::profile::Parse` 检查最终 JSON。应用导入也走该解析器，导入后会尝试选择并发布配置。 |
| 二进制证据 | 对最终 JSON 和原始 APK 或 ELF 目录运行下方验证命令，保存文件摘要、输出和退出码。 |
| 官方基线回归 | `python tools/run_profile_tests.py` 使用随附的 6144 fixture。命令接受 `--cxx` 和 `--sanitize`。 |
| 目标手机 | 记录系统、桌面和模块版本，以及 Profile 摘要。分别确认发布、当前桌面进程接收、画面和关闭恢复。 |

```powershell
python tools/verify_hyperos4_profile.py my-profile.json --apk launcher.apk
python tools/verify_hyperos4_profile.py my-profile.json --lib-dir libs
```

按输入选择其中一条命令。可添加 `--system-version '完整系统版本'` 比较已声明的系统版本。二进制验证检查文件、段、函数快照和原始值。还需复核模式语义及运行行为。[验证工具](../tools/verify_hyperos4_profile.py) · [主机回归](../tools/run_profile_tests.py)

手机端逐项记录通过、失败或未测。观察首次进入桌面、换色、圆角端点、图案来源、大图标、文件夹、动画以及关闭和重新启用。编译 Android 测试代码只覆盖构建，设备结果以实际执行与观察为准。交付候选、检查记录和可复现生成脚本。公开更新记录适配环境、验证范围与已知限制，原件、日志与设备证据留在本地。

## 实现入口

- 导入、选择和显示运行状态。[配置界面](../app/src/main/java/com/jerrey/monoicon/ui/screens/ProfileSettings.kt) · [运行报告](../app/src/main/java/com/jerrey/monoicon/ui/screens/ConfigurationRuntimeContent.kt)
- 发布、启动匹配与刷新。HyperOS 4 在设备加密存储中发布配置，按内容摘要引用文件。检查发布状态时，对照本次运行报告与当前进程。[配置发布](../app/src/main/java/com/jerrey/monoicon/hook/HyperOs4PalettePublisher.kt) · [原生运行时](../app/src/main/cpp/hyperos4_profile_runtime.cpp) · [图标刷新](../app/src/main/java/com/jerrey/monoicon/hook/HyperOs4IconRefresh.kt)
- 大图标独立导出。横竖网格数是布局信息，实际像素尺寸由桌面网格确定。改变大图标资源或形状后，重启桌面可刷新已有 Widget。[尺寸计算](../app/src/main/java/com/jerrey/monoicon/hook/HyperOs4LargeIconGeometry.kt) · [资源归属](../app/src/main/java/com/jerrey/monoicon/hook/HyperOs4LargeIconInputs.kt)
- 图案、第三方授权及离线回归。[第三方声明](THIRD_PARTY_NOTICES.md) · [图标回放](../tools/icon_regression/README.md)
