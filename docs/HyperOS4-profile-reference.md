# HyperOS 4 大图标技术参考

内置配置用于 `com.miui.home` 的 ARM64 单 APK 原生桌面。大图标通过文件读取桥接加载资源。普通应用图标通过系统主题归档提供。 [使用与兼容性](hyperos4.md)

## 基线与输入

内置 `hyperos4-6144` 匹配系统 `OS4.0.0.3.XAACNXM`、桌面版本号 `801026144`、版本名称 `RELEASE-8.01.02.6144-260818-08251107-R`。原有设备记录来自 Redmi K50（22041211AC，Android 17）。 [内置配置](../app/src/main/assets/hyperos4/profiles/hyperos4-6144.json) [6144 二进制身份档案](../external_resources/HyperOS4/builds/6144/manifest.json)

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

`inspect` 也接受直接包含 `libapp.so` 与 `libapp_launcher.so` 的目录。此时 APK 摘要为空。记录包名、版本和设备实测属性的独立来源。二进制匹配只说明所比较的文件一致，系统依赖和设备布局仍须核实。采集生成的 `manifest.json` 用于比对。

## Schema 1

以当前 Kotlin 加载代码和 C++ 解析器为准。配置采用 UTF-8 无 BOM 的 JSON，文件不超过 2 MiB。唯一 ID 的长度是 1–80，只允许小写字母、数字、`-` 和 `_`。JSON 的字节变化会改变摘要，包括格式化。 [配置模型](../app/src/main/java/com/jerrey/monoicon/profile/CompatibilityProfile.kt) [C++ 解析器](../app/src/main/cpp/hyperos4_profile.cpp)

| 字段 | 用途与约束 |
| --- | --- |
| `schema`、`id`、`name`、`revision` | 格式版本、唯一 ID、显示名称及正整数修订号。 |
| `match` | 包名、精确桌面版本、包含两端的 SDK 范围、只读系统属性及完整系统版本。桌面版本号使用十进制字符串。多个属性条件同时生效，同一属性的多个字面前缀满足其一即可。 |
| `images` | 桌面原始 ELF 身份与内存校验依据。 |
| `icons` | 图案查找规则、组件名及别名，以及大图标资源根与尺寸桥接。 |

`match.systemVersions` 中的值与 `ro.build.version.incremental` 区分大小写、完整匹配。应用只匹配随 APK 提供且声明支持当前系统的唯一配置。 [配置加载与匹配](../app/src/main/java/com/jerrey/monoicon/profile/ProfileRepository.kt)

Schema 1 仍保留供旧版工具核对的函数快照、指令原值和模式字段。现在的模块以二进制身份校验和大图标文件读取为主，运行诊断对应启动与文件读取点。换用新桌面版本时，重新核对二进制身份、资源布局和实际尺寸。

## 验证

| 检查 | 记录 |
| --- | --- |
| 引擎解析 | 使用当前 C++ `monoicon::profile::Parse` 检查最终 JSON。应用加载内置配置时也使用该解析器。 |
| 二进制证据 | 对最终 JSON 和原始 APK 或 ELF 目录运行下方验证命令，保存文件摘要、输出和退出码。 |
| 官方基线回归 | `python tools/run_profile_tests.py` 使用随附的 6144 fixture。命令接受 `--cxx` 和 `--sanitize`。 |
| 目标手机 | 记录系统、桌面和模块版本，以及内置配置摘要。分别确认资源发布、当前桌面进程接收、画面和关闭恢复。 |

```powershell
python tools/verify_hyperos4_profile.py my-profile.json --apk launcher.apk
python tools/verify_hyperos4_profile.py my-profile.json --lib-dir libs
```

按输入选择其中一条命令。可添加 `--system-version '完整系统版本'` 比较已声明的系统版本。验证工具会检查文件、段、函数快照和原始值。设备结果还需确认托管资源与网格尺寸是否正确。 [验证工具](../tools/verify_hyperos4_profile.py) [主机回归](../tools/run_profile_tests.py)

手机端逐项记录通过、失败或未测。分别观察普通图标和大图标，再验证关闭和重新启用。设备结果以实际执行与观察为准。

## 实现入口

- 「运行诊断」显示主题归档同步、内置匹配和进程报告。 [运行报告](../app/src/main/java/com/jerrey/monoicon/ui/screens/ConfigurationRuntimeContent.kt)
- 大图标资源发布到设备加密存储，激活标记包含内置配置摘要。检查发布状态时，对照本次运行报告与当前进程。 [大图标发布](../app/src/main/java/com/jerrey/monoicon/hook/HyperOs4LargeIconPublisher.kt) [原生运行时](../app/src/main/cpp/hyperos4_profile_runtime.cpp)
- 大图标独立导出。横竖网格数是布局信息，实际像素尺寸由桌面网格确定。改变大图标资源或形状后，重启桌面可刷新已有 Widget。[尺寸计算](../app/src/main/java/com/jerrey/monoicon/hook/HyperOs4LargeIconGeometry.kt) · [资源归属](../app/src/main/java/com/jerrey/monoicon/hook/HyperOs4LargeIconInputs.kt)
- 图案、第三方授权及离线回归。[第三方声明](THIRD_PARTY_NOTICES.md) · [图标回放](../tools/icon_regression/README.md)
