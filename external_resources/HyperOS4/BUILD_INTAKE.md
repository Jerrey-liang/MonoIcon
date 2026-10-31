# Launcher 离线收集与版本比较

`tools/launcher_inspector.py` 仅使用 Python 标准库，收集两个 ARM64 原生库的二进制身份并生成差异报告。`builds/6144/manifest.json` 是当前 6144 的离线基线档案，不是运行时配置；工具不会添加新 Launcher 支持，也不会修改 Hook、快照、补丁表或构建配置。

## 从 APK 收集

以下命令在 Windows PowerShell、仓库根目录执行。输入是已经保存到电脑的完整 Launcher APK；本流程不连接设备、不安装 APK。

```powershell
Set-Location 'D:\Projects\Android Project\MonoIcon'
python --version
python -B tools/launcher_inspector.py inspect 'C:\Launcher Intake\launcher.apk' --out-dir 'external_resources/HyperOS4/tmp/intake-candidate-01'
```

`inspect` 从 ZIP 中按完整条目名称读取：

- `lib/arm64-v8a/libapp.so`
- `lib/arm64-v8a/libapp_launcher.so`

它将文件写到输出目录下相同的固定相对路径，同时生成 `manifest.json` 和 `report.md`。不会根据 APK 中其他条目的路径解压，也不会改写原始 APK。两个目标必须同时存在；重复的目标条目、非 ELF、损坏文件或不支持的 ELF 格式会报错并返回非零退出码。

输出目标文件已存在时，`inspect` 拒绝覆盖。再次收集应选择新的输出目录，保留原始证据。输出包含 APK SHA256、两个 ELF 的 SHA256/大小/架构、完整 ELF 头和程序头/节头、GNU Build ID、Android Note、PT_LOAD 及其可执行段 SHA256。未知 Note 的原始名称字节、类型和描述字节也保存在 JSON 中。

## 从已提取的库收集

也可以输入已保存的本地库目录：

```powershell
python -B tools/launcher_inspector.py inspect 'C:\Launcher Intake\native-libs' --out-dir 'external_resources/HyperOS4/tmp/intake-extracted-01'
```

两个库必须处于同一个目录，支持以下三种布局：

```text
native-libs/libapp.so                       native-libs/libapp_launcher.so
native-libs/arm64-v8a/libapp.so              native-libs/arm64-v8a/libapp_launcher.so
native-libs/lib/arm64-v8a/libapp.so          native-libs/lib/arm64-v8a/libapp_launcher.so
```

目录输入也会复制两个 ELF 到新的输出目录。由于没有 APK，`apk.sha256` 和 `apk.size` 为 `null`；不能用 ELF 哈希代填 APK 哈希。

## 比较与核对 6144

```powershell
python -B tools/launcher_inspector.py compare 'external_resources/HyperOS4/builds/6144/manifest.json' 'external_resources/HyperOS4/tmp/intake-candidate-01/manifest.json' --out 'external_resources/HyperOS4/tmp/compare-6144-candidate-01.md'
```

本地历史证据目录若保留了 `MiuiHome_6144.apk`，可重做基线检查：

```powershell
python -B tools/launcher_inspector.py inspect 'external_resources/HyperOS4/MiuiHome_6144.apk' --out-dir 'external_resources/HyperOS4/tmp/intake-6144-check-01'
python -B tools/launcher_inspector.py compare 'external_resources/HyperOS4/builds/6144/manifest.json' 'external_resources/HyperOS4/tmp/intake-6144-check-01/manifest.json' --out 'external_resources/HyperOS4/tmp/compare-6144-check-01.md'
Get-FileHash -Algorithm SHA256 -LiteralPath 'external_resources/HyperOS4/MiuiHome_6144.apk'
```

两个 ELF 应显示 `EXACT_BINARY_MATCH`。新生成的 `environment` 和 `launcher` 元数据仍为 `null`，报告会单独列出它们与手工整理基线的差异。这不会被隐藏，也不影响两个 ELF 的精确匹配分类。APK 路径和文件名不构成版本证据。

| 分类 | 含义 |
| --- | --- |
| `EXACT_BINARY_MATCH` | 两份档案的目标 ELF SHA256 相同，且已记录的解析事实没有互相矛盾。总分类要求两个目标库都匹配；不表示 APK、设备或系统环境相同。 |
| `SAME_BUILD_ID_BUT_FILE_CHANGED` | ELF SHA256 不同，GNU Build ID 相同，且未优先确认执行段差异。需要继续查看完整文件、结构和 Note 差异；缺失执行段信息不能解释成代码相同。 |
| `EXECUTABLE_SEGMENTS_CHANGED` | 已记录的可执行 PT_LOAD 内容哈希或其映射、大小、标志等结构发生变化。该分类优先于 Build ID 相同。 |
| `UNKNOWN_OR_INCOMPLETE` | 缺少可判定身份、事实互相矛盾，或变化不满足以上条件。它既不表示兼容，也不表示已证明不兼容。 |

报告会分别给出每个库的分类，再汇总：执行段变化优先，其次是不完整；只有全部目标 ELF 精确匹配，才给出总体精确匹配。比较也保留 APK、环境和 Launcher 元数据差异。命令成功表示报告已生成，非匹配分类不会成为执行错误。

可执行段是带 `PF_X` 的 `PT_LOAD`；哈希覆盖从 `p_offset` 开始的 `p_filesz` 个文件字节，不包含 `p_memsz` 多出的零填充内存，也不是只计算 `.text`。RVA、ELF 虚拟地址和文件偏移不可混用。

**相同 GNU Build ID、Android Note 或可执行段哈希，都不能单独证明 Hook ABI 兼容。** 即使目标 ELF 精确匹配，离线比较也不替代原有运行时验证及其他环境条件。所有比较结果的 `compatibility` 均为 `NOT_ASSESSED`，不得据此启用未知版本。

Android Note 是原生编译元数据。6144 的 `libapp_launcher.so` 记录 `abi_api=35`、NDK `r27d`、构建号 `13750724`；这不表示设备运行 Android 35，也不能替代 README 中的 Android 17 记录。该库没有已确认的 GNU Build ID，档案保持 `null`，不会把 Android Note 充作 GNU Build ID。

## 归档新版本

先保留未经修改的 APK 和第一次 `inspect` 输出。为新版本选择独立的归档目录；版本尚无可靠来源时用采集编号或哈希前缀命名，不猜测版本号。下面把已经生成的结果复制为一个档案：

```powershell
$intakeDir = 'external_resources/HyperOS4/tmp/intake-candidate-01'
$archiveDir = 'external_resources/HyperOS4/builds/candidate-01'
New-Item -ItemType Directory -Path $archiveDir -ErrorAction Stop
Copy-Item -LiteralPath "$intakeDir/manifest.json" -Destination "$archiveDir/manifest.json"
Copy-Item -LiteralPath "$intakeDir/report.md" -Destination "$archiveDir/report.md"
New-Item -ItemType Directory -Path "$archiveDir/evidence" -ErrorAction Stop
```

在归档副本中补充 `environment`、`launcher` 和 `evidence`。`inspect` 不解析 APK 的二进制 AndroidManifest，也不从库名、APK 文件名或 Note 猜测包名、版本、设备和系统。只用已有、可追溯的包元数据导出或设备记录填写，并为每个补充字段记录来源文件、采集时间、方法和验证状态。没有证据的值保留 JSON `null`。不要将历史记录的系统/设备信息复制到一个来源未知的新 APK 上。

记录原始 APK/ELF 的保存位置和 SHA256，保留采集命令及工具版本。若原始输入路径将不再可用，在归档中注明新的存储位置；不要修改哈希来适应另一个文件。原始二进制留在本地证据库，文本档案通过路径和哈希引用它们。

本仓库忽略 `external_resources` 的大部分内容，仅放行本次 intake 文档和 `builds/6144/manifest.json`。后续需提交的新版本文本可明确选择文件，不要强制添加整个目录：

```powershell
git add -f -- 'external_resources/HyperOS4/builds/candidate-01/manifest.json' 'external_resources/HyperOS4/builds/candidate-01/report.md'
git diff --cached --stat
```

本次 6144 基线的来源与验证状态列在 `evidence` 中；`artifacts` 引用了已存在的函数快照、补丁表、Canary、观察器和生成器。标记为 `local_ignored` 的历史证据并不随 Git 分发；新的 checkout 没有这些文件时，以档案内已记录的身份为参照，并从受控本地存储恢复原始输入再复验。

## 何时继续分析及如何保存证据

发现不同 ELF、执行段变化、Build ID 复用、库缺失或元数据矛盾时，先核对输入与采集来源。只有需要评估新版本 Hook 适配时，才继续对相关变化做 Blutter / Ghidra 分析。Blutter 可用于 Dart AOT 的 `libapp.so` 函数/对象池定位，Ghidra 可用于 ARM64 反汇编、调用路径和 `libapp_launcher.so` 的原生边界核对。不要因版本名接近或 Note 相同跳过分析，也不要重做已有 6144 结论。

将后续分析存到对应新版本的 `evidence/`，至少保存：

- 输入 APK/ELF 哈希、采集来源、工具版本、运行命令及输出日志。
- 旧版与新版的函数范围、完整原始字节和反汇编、调用者/被调用者及对象池引用；明确地址属于文件偏移还是 ELF 虚拟地址，并保留 PT_LOAD 映射。
- 寄存器、栈、返回值、异步完成路径等 ABI 结论的直接证据，区分已验证、推断和未知。
- 每个候选补丁点的原始指令、预期改动及验证依据；保存生成脚本和报告，不覆盖当前 6144 的函数快照或补丁表。

归档和比较只产生离线证据；新版本支持需要后续独立验证。本流程不执行手机上机测试。

## 单元测试

```powershell
python -B -m unittest discover -s tools/tests -p 'test_launcher_inspector.py' -v
```

测试使用生成的最小 ELF/APK 样本，不依赖真实 Launcher 二进制。
