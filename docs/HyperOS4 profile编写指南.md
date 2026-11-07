# HyperOS4 profile 编写指南

适合：完全不懂 Android 开发，但会向 AI 提问、提供文件和复制命令的读者。

覆盖环境：**Codex desktop、Claude Code、DeepSeek Harness**。

适用基线：MonoIcon 2.3.0，官方 6144 Profile 的 schema 1 / revision 9。源码和应用升级后，应重新核对字段支持与匹配条件。

本文统一承担 HyperOS 4 的使用说明、原生架构说明、自定义 Profile 规范、验证和排错说明。配套文件是 [Agent_example_prompt.md](Agent_example_prompt.md)：把它交给 AI，再填写任务信息，即可开始工作。

**你的工作是提供真实材料、检查 AI 的证据和观察手机画面。二进制分析、生成 JSON 和执行电脑上的检查交给 AI。** 新桌面版本可能需要复杂分析；没有足够证据时，正确产出是研究报告和缺口清单，而不是一个看似完整的“可用配置”。

## 阅读路线

- 第一次做：依次阅读 [基本概念](#basics)、[准备材料](#materials)、[三种智能体环境](#environments)、[分阶段指挥 AI](#workflow)。
- AI 已交文件：阅读 [验证](#validation)、[导入和手机验收](#device)、[排错](#troubleshooting)。
- AI 问技术问题：让它阅读 [字段规范](#schema)、[运行机制](#runtime)、[归档与交接](#handoff)。你无需自己记住地址和指令。
- 下一次系统更新：从材料收集重新开始，复用已有证据目录和提示词，不直接沿用旧地址。

<a id="basics"></a>
## 1. 先弄清楚你要做什么

### 1.1 Profile 不是配色预设

MonoIcon 给应用图标生成单色主题外观。部分 HyperOS 4 系统桌面使用原生 Flutter/Dart 代码，普通 Java 图标 Hook 无法接入它们。Profile 告诉 MonoIcon：**这是哪一个精确版本的桌面、哪些原始代码应当出现、可以怎样替换、去哪里寻找图标，以及如何刷新。**

Profile 是一个 UTF-8 JSON 文本文件，其中包含真实机器指令。虽然扩展名是 `.json`，它仍可能改变桌面进程的执行行为。不能只让 AI“照着旧文件把版本号换一下”。

| 词语 | 你只需要知道的含义 |
| --- | --- |
| 系统桌面 / Launcher | 显示桌面和应用图标的程序，本项目目标包名是 `com.miui.home`。 |
| APK | Android 安装包。要收集的是**目标手机实际使用的系统桌面 APK**，不是 MonoIcon APK。 |
| `.so` / ELF | 安装包中的原生程序文件。本项目基线分析 `libapp.so` 和 `libapp_launcher.so`。 |
| AArch64 / arm64-v8a | 目标程序的 64 位 ARM 架构。当前 Profile 驱动面向这种架构。 |
| SHA-256 / 摘要 | 文件内容的指纹。改一个字节甚至 JSON 空格，摘要都会改变。 |
| 函数快照 | 从原始库中读取的一整个函数的机器码，用来验证修改前的代码。 |
| 地址 / 偏移 | 代码的位置。有文件位置、ELF 虚拟地址、函数内位置，三者不能混用。 |
| 补丁 / patch | 把指定位置的一条 4 字节指令换成另一条指令或受支持的颜色操作。 |
| ABI / 调用约定 | 函数如何接收参数、使用寄存器和返回结果。位置找对了，调用方式错了仍会出问题。 |
| schema | Profile 格式版本。当前是 `1`，由 MonoIcon 引擎支持。 |
| revision | 同一个 Profile 的修订号。建议每次发布递增；不是桌面版本号。 |
| Palette | 当前颜色、总开关、圆形和 Lawnicons 等用户选项；由应用生成，不是 Profile。 |
| root | 手机上的高权限，当前HyperOS 4链路用它发布配置和图标；不等于电脑管理员权限。 |
| LSPosed | 在目标程序运行时加载模块Hook的框架；MonoIcon是其中的模块。 |
| Hook / 作用域 | Hook是在程序运行路径中接入修改；作用域是允许模块影响的程序范围，例如系统桌面。 |
| manifest.json | 收集工具生成的二进制身份档案。**不能作为 Profile 导入。** |
| 静态检查 / 真机验收 | 前者在电脑上分析和核对，后者在目标手机上观察实际行为。两者不能相互代替。 |

### 1.2 哪些任务需要写 Profile

| 你的目的 | 正确做法 |
| --- | --- |
| 改颜色、开关圆形、开关 Lawnicons | 使用 MonoIcon“图标样式”，通常不需要改 Profile。 |
| 当前系统桌面已被官方 Profile 精确匹配 | 先使用官方配置。学习时可以制作单独 ID 的副本，但副本仍只适配原二进制。 |
| 更新系统或桌面后显示不匹配 | 收集更新后的真实 APK、元数据和系统属性，评估是否需要新 Profile。 |
| 同版本号却 ELF 摘要不同 | 作为不同输入重新分析，不能删除摘要检查。 |
| 自定义图标别名或查找路径 | 仍需确认目标加载规则，并重新进行相关验证。 |
| 引擎缺少新版本所需的操作 | 单靠 Profile 无法解决。让 AI 说明需改引擎的原因，另行确定开发范围。 |

当前原生驱动要求目标为 `com.miui.home`、arm64、无 Java 代码的原生桌面；带 Java 代码的桌面走旧链路，split APK 当前不受此驱动支持。**不要为了解除“不匹配”而伪造环境或放宽判断。**

电脑上可以先做完整的离线研究，不要求此时就有root手机；真正应用候选时，需要测试手机已具备root、LSPosed和MonoIcon。手机root、电脑管理员、AI文件夹权限是三件不同的事。本指南不包含解锁/刷机/root安装教程。

### 1.3 当前官方基线

<a id="baseline"></a>
| 项目 | 已记录的值 |
| --- | --- |
| 模块 | MonoIcon 2.3.0 |
| 官方文件 | [hyperos4-6144.json](../app/src/main/assets/hyperos4/profiles/hyperos4-6144.json) |
| ID / 格式 / 修订 | `hyperos4-6144` / schema `1` / revision `9` |
| 当前文件 SHA-256 | `c0cfdef43f254221ca0e2e3480dc6e803773d3319a6c5962becf5a2c52a41c07` |
| 桌面 versionCode | `801026144` |
| 桌面 versionName | `RELEASE-8.01.02.6144-260818-08251107-R` |
| 当前实测设备 / 系统 | `22041211AC` / `OS4.0.0.3.XAACNXM` / Android 17（SDK 37） |
| 官方系统条件 | `match.systemVersions` 精确包含 `OS4.0.0.3.XAACNXM`，并保留 `ro.build.version.incremental` 字面前缀 `OS4.` 条件 |
| 当前二进制证据 | 2 个 ELF，38 份完整函数快照，共 28,464 字节；28 处补丁 |
| 验证边界 | 已验证配置发布、原生确认、绘图与界面回归、功能开关及恢复；大图标已确认部分形状的实际画面。完整范围与未覆盖场景见[当前验证范围](#validated-scope)。 |

这些数字是**现有模板的事实，不是新 Profile 的规定数量**。新版本应由实际分析决定函数和补丁数量。只有随当前版本提供且精确匹配目标的 Profile，才能作为可用基线；其他桌面版本需要独立评估。

<a id="materials"></a>
## 2. 准备材料：给 AI 什么，它才有可能做好

### 2.1 建议的文件摆放

下载或克隆 [MonoIcon 仓库](https://github.com/Jerrey-liang/MonoIcon)，保留完整目录。不会使用Git时，在仓库网页选择 **Code → Download ZIP**，解压后选择包含 `app/`、`tools/`、`docs/` 的那一级文件夹。已有源码时直接使用，不要覆盖旧项目中未提交的文件。

ZIP下载通常没有 `.git`，AI应记录下载网址、标签/分支和时间；无法确认commit就写未知，仍可继续分析。Windows资源管理器可点击地址栏复制当前文件夹完整路径；给AI明确路径，不要只说“在桌面上”。

下面是建议组织方式；`candidate-01` 是本次采集编号，不代表已确认的版本：

```text
MonoIcon/
  docs/
    HyperOS4 profile编写指南.md
    Agent_example_prompt.md
  external_resources/HyperOS4/builds/candidate-01/
    input/          原始 APK、环境信息、来源说明
    intake/         inspect 自动生成的 manifest、report、两个 ELF
    evidence/       函数边界、反汇编、逐补丁证据、生成脚本
    output/
      my-profile.json       候选 Profile
      PROFILE_REPORT.md     单份本地报告：证据、验证、限制与交接
      generate_profile.py   可复现生成脚本
      checks/               原始检查日志和可读会话导出
```

`external_resources` 大部分被 Git 忽略：**本地能看到，不代表别人下载仓库也能看到**。切换电脑或智能体时，明确提供所需材料；不要让 AI 假定旧二进制和聊天已随 Git 到达。未知版本可以使用采集号或哈希前缀命名，不从 APK 文件名猜版本。

### 2.2 必要材料清单

| 材料 | 你怎么准备 | 缺失时 AI 应怎样处理 |
| --- | --- | --- |
| MonoIcon 完整源码及版本 | 提供项目文件夹，要求 AI 记录 Git commit 和未提交差异。 | 先获取/定位项目，不能只看一张 JSON 截图开始。 |
| 系统桌面完整 APK | 从目标设备备份/导出；或给出可信来源，并注明是否来自目标手机。 | 只有 `.so` 时可先分析，但 APK 身份和实际安装关系仍未确认。 |
| 两个 ARM64 库 | 通常由收集工具从 APK 提取。 | 缺库时说明缺口，不拿旧库补齐。 |
| 桌面包名、versionCode、versionName | 设备包管理器记录，或可靠的 APK manifest 工具输出。 | 保持未知，不能信 APK 文件名。 |
| 系统完整版本、SDK、ABI、相关 `ro.*` 属性 | 提供设备读取结果；不会操作就让 AI 逐条指导。 | 未读取的属性不能从别的手机抄。 |
| 设备型号、手机/平板/折叠屏、用户空间 | 在任务信息中填写。 | 大图标布局未经分析时保留原图。 |
| MonoIcon、LSPosed 版本及启用状态 | 应用版本、作用域页面或文字记录。 | 真机阶段开始前补齐。 |
| 已有分析和交接 | 目标版本已有的分析报告、失败原因和已完成步骤。 | 说明缺少哪些依据，不凭记忆声称已经核对。 |
| 手机测试条件 | 写“仅离线”或“有测试手机”，以及愿意执行的操作。 | 默认为离线工作；候选文件不能标记为真机通过。 |

常规 Profile 编写不要求你会 Android Studio，也不一定要编译 MonoIcon。电脑至少要能运行 Python；反汇编工具和主机 C++ 编译器由 AI 检查是否存在，缺什么再解释为什么需要。仅当确实修改引擎或需要构建测试 APK 时，才准备 Android SDK/JDK/NDK 等完整开发环境。

### 2.3 获取手机信息：可选的 ADB 路线

已有 APK 和可靠环境记录时，可以先跳过本节。ADB 是电脑与 Android 调试连接的工具；它不是 Profile 编写器。使用官方 [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools)，在手机开发者选项中开启 USB 调试，连接自己的测试手机并在手机端确认连接。

以下在 **Windows PowerShell** 执行。若工具未加入 PATH，让 AI 改用真实 `adb.exe` 路径；多台设备连接时，要求 AI 为后续每条命令加入正确的 `-s 设备序列号`。先执行第一条，确认设备状态是 `device`；没有设备、显示 `unauthorized` 或 `offline` 时，把完整输出交给AI排查，解决后再执行后续命令：

```powershell
adb devices
adb shell getprop ro.product.model
adb shell getprop ro.build.version.incremental
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abilist
adb shell dumpsys package com.miui.home
adb shell pm path com.miui.home
```

让 AI 保存有关包版本、`HAS_CODE`、split、路径和系统属性的原始输出，写清采集时间。公开分享时可以遮去设备序列号。不同系统属性可能报告不同层面的版本信息，必须读取并交叉核对，不能仅凭属性名推断系统版本。

`pm path` 通常返回 `package:/某个真实路径/base.apk`。请让 AI 根据本次输出生成 `adb pull` 命令，将去掉 `package:` 后的路径作为源，保存到本次 `input/`。不要把别人手机的路径原样复制过来。如果返回多个 APK 路径，先确认是否 split；当前原生驱动不支持 split，不应擅自合包继续。

这组命令读取信息；安装 APK、重启桌面、切换设置是后面的手机操作。不要为了取得材料执行清除桌面数据、替换系统桌面 APK 或修改系统分区。

### 2.4 第一条消息附上这张任务卡

```text
我已提供 Agent_example_prompt.md，请以它作为本次任务说明。
智能体环境：Codex desktop / Claude Code / DeepSeek Harness（三选一）
MonoIcon 项目路径：填写电脑上的完整路径
目标桌面 APK 路径：填写；没有则写“只有两个 ELF”及其路径
材料/输出根目录：填写本次 candidate 目录
设备型号和形态：填写；未知就写未知
系统完整版本、Android SDK：填写或指向原始采集文件
桌面 versionCode / versionName：填写或指向原始采集文件
已有本地报告和证据路径：填写
目标：普通图标、文件夹、静态日历/时钟、圆形动画、Lawnicons；大图标是否要求：填写
设备条件：仅离线 / 有测试手机
本轮授权：读取材料、离线分析、生成候选和证据、运行本机检查
本轮设备操作：无；需要上机时另行明确具体操作
先完成材料和工具盘点，再持续推进有证据支持的工作；不要猜测缺失地址。
```

如果你已经明确授权某项设备操作，AI 应沿用该授权，不必每一步重复询问。默认“仅离线”是为了准确划定初次任务范围，不是让 AI 停在写计划。

<a id="environments"></a>
## 3. 在三种智能体环境里怎么操作

三种环境使用**同一份提示词、同一套证据和验收标准**。工具名字不同，不会改变 Profile 格式。普通网页聊天如果只能读上传文本、不能运行本地程序，最多辅助阅读和规划，不能代替具备文件与终端能力的智能体。

### 3.1 Codex desktop

1. 在桌面应用里进入 Codex，打开或添加本地 MonoIcon 项目，选择真实源码目录作为主要文件夹。不要只新建一个没有本地文件来源的普通聊天。
2. 如果 APK 放在另一个目录，把它作为项目可访问文件夹添加，或复制到本次 `input/`。检查当前会话实际工作目录；不要仅因为侧栏标题叫 MonoIcon 就认为文件可读。
3. 在该本地项目中建立本次适配聊天。优先使用当前本地目录；如果使用 worktree，提醒 AI：被 Git 忽略的 APK、反汇编报告不会自动复制进去，必须明确指向原材料目录。
4. 附加提示词文件，或直接发送：

   ```text
   请完整读取 docs/Agent_example_prompt.md 和 docs/HyperOS4 profile编写指南.md，
   把前者作为本次任务要求。先输出实际工作目录、源码版本、目标材料是否可读，
   再按我填写的任务卡开始离线适配。
   ```

5. 要求第一轮实际列出材料和工具检查结果。若文件访问被环境限制，按应用提示授予所需目录权限；无需为了方便直接开放整个电脑。
6. 继续已有适配时，提供上一阶段的 `PROFILE_REPORT.md`、候选和证据文件。需要聊天中的额外信息时，提供可访问的导出文本；新聊天不会自动继承其他工具中的完整上下文。
7. 回到同一个聊天继续工作。换聊天或换电脑前，让 AI 更新 `PROFILE_REPORT.md` 的交接部分，新聊天明确读取它、候选 JSON 和证据目录。

阅读或预览 Markdown 不等于已经把它作为任务要求。上面的明确读取指令和任务卡很重要。无需把整份提示词覆盖进仓库现有 `AGENTS.md`，也不必先创建永久自定义智能体。

桌面界面名称可能随版本变化，关键是选择 Codex，并使用能访问源码文件夹的本地项目；普通云端项目不等于已连接本机文件夹。参考：[桌面入口](https://learn.chatgpt.com/docs/app)、[本地项目与文件夹](https://learn.chatgpt.com/docs/projects)、[Windows](https://learn.chatgpt.com/docs/windows/windows-app)、[提示词写法](https://learn.chatgpt.com/docs/prompting)。

### 3.2 Claude Code

本节使用终端版 Claude Code。首次安装和登录按 [官方快速开始](https://code.claude.com/docs/en/quickstart) 操作；已经能运行时无需重复安装。Windows 的官方 PowerShell 安装命令是 `irm https://claude.ai/install.ps1 | iex`；它会下载并执行官方安装脚本，完成后重新打开终端。

在 Windows 开始菜单打开 PowerShell，进入**你自己的项目路径**：

```powershell
Set-Location 'C:\Work\MonoIcon'
claude --version
Test-Path -LiteralPath '.\docs\Agent_example_prompt.md'
claude --permission-mode default
```

`Set-Location` 就是“进入文件夹”。示例路径含空格，所以加引号。`Test-Path` 应返回 `True`。若提示找不到 `claude`，说明安装/PATH 尚未完成，先修正环境，不是 Profile 有问题。这里显式选择 `default`（Manual）权限模式；不是要求跳过检查，也不假定所有版本的默认模式都一样。

进入交互会话后，可先输入 `/rename monoicon-hyperos4` 命名，再发送以下内容。**这些输入给 Claude Code，不是输入给 PowerShell：**

```text
请完整读取 @docs/Agent_example_prompt.md，把它作为本次任务要求；
再读取 docs/HyperOS4 profile编写指南.md。项目就是当前工作目录。
我会提供任务卡。请实际盘点 APK、源码、历史和可用工具，然后推进离线适配。
```

随后粘贴任务卡。Claude Code 可以读取文件、编辑和运行终端命令；遇到权限提示时，核对目标目录和命令用途。没有必要使用跳过所有权限检查的启动参数。

关闭后，在相同项目目录用 `claude --continue --permission-mode default` 继续最近会话，或 `claude --resume monoicon-hyperos4 --permission-mode default` 恢复已命名会话。找不到时用 `claude --resume` 打开选择器。

在Claude对话框输入 `/export external_resources/HyperOS4/builds/candidate-01/output/checks/claude-session.txt` 可导出可读历史；先让AI确认该目录存在。这份TXT是给另一个AI阅读的资料，不是原生会话恢复文件。它不会自动获得Codex或DeepSeek的历史：跨工具继续时提供 `PROFILE_REPORT.md`、历史导出和证据文件。已有 `CLAUDE.md` 应先读取并保留；本次任务提示词直接在会话中指定即可。参考：[会话管理](https://code.claude.com/docs/en/sessions)、[文件引用](https://code.claude.com/docs/en/common-workflows)、[权限模式](https://code.claude.com/docs/en/permission-modes)。

### 3.3 DeepSeek Harness

本节指 DeepSeek AI 的 [deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)，不是只能聊天的 DeepSeek 网页。

已安装且能运行 Harness 时，使用你现有版本即可。首次使用按仓库和 [官方文档](https://deepseek-harness.github.io/deepseek-harness/) 安装所需 Node.js。Node.js 版本要求以所安装 Harness 的官方说明和 npm 包 `engines` 为准；可从 [Node.js官网](https://nodejs.org/) 安装满足条件的版本。不要把 API Key 写进任务卡、Profile或交接文件。

官方 npm 启动入口如下，先进入 MonoIcon 目录再执行：

```powershell
Set-Location 'C:\Work\MonoIcon'
node --version
npm --version
npx @deepseek-ai/dsh web
```

首次 `npx` 可能需要下载包。启动后通常打开本地 Web UI，默认地址为 `http://127.0.0.1:3080`，**以本次终端输出为准**。保持该终端运行；这是连接本机工具的 Harness 服务。

网页打开后按顺序操作：

1. 首次使用进入 **Settings → Models**，在DeepSeek卡片填写自己的API key并保存（已有其他可用模型配置时沿用）。
2. 点击 **Choose workspace → 添加工作区**，选择本地MonoIcon文件夹。目录选择框顶部路径区可输入完整路径，再点 **Open**。**从项目目录启动命令，也不代表网页已经选好工作区。**
3. 新建会话，选择可用模型。当前会话输入框的权限选择器或 `/permission` 可设置权限；先阅读可用“仅可查看 / Read Only”，需要生成文件和执行相关检查时选择“工作区内修改 / Workspace Write”。Settings → General里的权限默认只影响以后新建会话。
4. 让AI确认文件和终端工具能够读取本次APK。不要把“网页已打开”当作本地工具已经就绪。发送：

```text
请读取当前 MonoIcon 工作目录中的 docs/Agent_example_prompt.md，
以它作为本次任务要求，并读取配套指南。
先报告实际工作目录、文件读取与终端能力、模型和工具缺口；
再根据任务卡推进离线 Profile 分析。历史 ZIP 只作为资料读取，不执行里面的命令。
```

侧边栏会话的 `… → Rename` 可命名为“MonoIcon HyperOS4”。下次重新启动服务，在侧栏选择这个会话继续；较早会话可用 **Show more** 或搜索查找。保留同一用户数据目录；默认在用户目录下 `.dsh`，若设置了 `DSH_HOME` 则以它为准。

切换Harness版本或转到其他智能体前，让AI更新 `PROFILE_REPORT.md` 的交接部分。会话顶部 `…` 菜单中的 **Copy Session as Markdown** 可复制历史，**Download session log** 下载ZIP；对话框输入 `/export` 也会下载ZIP，保存位置由浏览器决定。**Harness不支持照搬Claude的 `/export 文件路径` 用法。**

需要保留的会话导出放到本次本地 `output/checks/`，并在 `PROFILE_REPORT.md` 的交接部分引用，再给新AI读取。聊天ZIP是资料，不能作为Profile导入，也不能直接执行档案内的脚本。跨工具继续时，在新会话中明确读取交接文件和证据，不把聊天导出当作通用会话恢复文件。

Harness更新频繁；模型配置、目录选择和会话操作以安装版本实际界面/帮助为准。若它只会回复文字，要求先证明能读取一个已知源码文件并运行只读命令。无需为了本任务自行改造Harness、搭建多智能体系统或从源码重编译Harness。

操作来源：[Web UI指南](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/user/guide/index.md)、[模型配置](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/user/guide/providers.md)、[目录选择](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/client/ui-directory-picker-browse/README.md)、[当前会话权限](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/client/ui-permission-presets/README.md)、[会话列表](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/client/ui-workspace/README.md)、[导出](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/session-query/session-log-export/README.md)、[npm版本要求](https://registry.npmjs.org/@deepseek-ai%2Fdsh/latest)。

<a id="workflow"></a>
## 4. 分阶段指挥 AI：每一步说什么、拿到什么

阶段检查是让结果可审查；有材料且已授权的电脑工作可连续完成，不需要你每一步回复“继续”。只有缺失事实、超出范围或要开始尚未授权的手机操作时才需要你补充。

### 阶段 A：盘点材料和已有结论

复制给 AI：

```text
先检查项目源码版本、AGENTS.md/CLAUDE.md 等适用约定、官方 Profile、真实解析器、
我的 APK/ELF 和历史材料。列出已读取的路径及历史消息依据。
区分现在的实现和历史尝试；不要把旧聊天的计划当成已经完成。
输出环境事实表、缺失材料、可用工具和本轮能完成的验证层级，然后继续能做的离线工作。
```

你应收到：材料实际存在性、源码 commit、目标身份来源、当前工具版本、是否能读历史，以及未知项。AI 只说“已了解项目”，却不给路径和具体事实，不算完成。

### 阶段 B：收集身份并与基线比较

复制给 AI：

```text
对我的原始桌面 APK 运行 launcher_inspector inspect，输出到新的 intake 目录。
保留原始文件和原始报告。与仓库 6144 manifest 比较，记录每个 ELF 的变化和完整哈希。
不要把 compare 成功或 Build ID 相同解释为兼容。补充包版本/系统资料时逐项写来源，
没有证据的值保留 null。根据实际输入决定是否继续新版本二进制分析。
```

#### B1. 从完整 APK 收集

以下命令在仓库根目录的 PowerShell 执行，只处理已经保存在电脑上的文件，不连接手机、不安装 APK。`launcher_inspector.py` 只依赖 Python 标准库。

```powershell
python --version
python -B tools/launcher_inspector.py inspect 'external_resources/HyperOS4/builds/candidate-01/input/launcher.apk' --out-dir 'external_resources/HyperOS4/builds/candidate-01/intake'
```

工具只读取 APK 中两个固定条目：

- `lib/arm64-v8a/libapp.so`
- `lib/arm64-v8a/libapp_launcher.so`

它将两份 ELF 复制到输出目录的相同相对路径，并生成 `manifest.json`、`report.md`。它不按其他 ZIP 条目的路径解压、不修改原 APK，也不添加新桌面支持或修改 Profile。两个目标库必须同时存在；目标重复、非 ELF、损坏或不支持的 ELF 格式都会使命令报错并返回非零退出码。

输出目录可以是空目录，但将要写入的目标文件不能已经存在。再次采集使用新的编号目录，不覆盖第一次输出。

#### B2. 只有两个库时怎样输入

将两个库放在同一个布局中，支持以下三种形式，任选一种：

```text
native-libs/
  libapp.so
  libapp_launcher.so

native-libs/arm64-v8a/
  libapp.so
  libapp_launcher.so

native-libs/lib/arm64-v8a/
  libapp.so
  libapp_launcher.so
```

```powershell
python -B tools/launcher_inspector.py inspect 'external_resources/HyperOS4/builds/candidate-01/input/native-libs' --out-dir 'external_resources/HyperOS4/builds/candidate-01/intake-libs'
```

目录输入也会复制两份库。没有完整 APK 时，档案中的 `apk.sha256` 和 `apk.size` 为 `null`；不能用 ELF 哈希代填，也不能声称已确认与手机安装的 APK 一致。

#### B3. 报告里有什么，哪些仍需补充

自动档案包含 APK 的 SHA-256、两个 ELF 的 SHA-256/大小/架构、完整 ELF 头、程序头和节头、GNU Build ID、Android Note、全部 PT_LOAD 信息及可执行段哈希。未知 Note 的原始名称字节、类型和描述字节也保留在 JSON 中。

`inspect` 不解析 APK 的二进制 AndroidManifest，也不根据文件名或 Note 猜测包名、版本、设备和系统。自动生成的 `environment`、`launcher` 可能为 `null`，应保留原始输出；后续只在归档副本中用可靠资料补充，并记录来源、采集方法和时间。没有证据的值继续保留 `null`。

Android Note 是编译元数据。例如 `abi_api=35` 表示原生编译信息，不能据此把手机 Android SDK 填成35。没有 GNU Build ID 时保持 `null`，不要用 Android Note 代替。APK 的文件名和保存路径也不是版本证据。

#### B4. 与已知基线比较

```powershell
python -B tools/launcher_inspector.py compare 'external_resources/HyperOS4/builds/6144/manifest.json' 'external_resources/HyperOS4/builds/candidate-01/intake/manifest.json' --out 'external_resources/HyperOS4/builds/candidate-01/compare-6144.md'
```

| compare 分类 | 含义与下一步 |
| --- | --- |
| `EXACT_BINARY_MATCH` | 对应 ELF 的完整 SHA-256 相同，已记录的解析事实也无矛盾。总体精确匹配要求两个目标库都匹配，仍不表示 APK、系统环境或设备相同。继续核对 Profile 的环境条件和所需功能。 |
| `SAME_BUILD_ID_BUT_FILE_CHANGED` | 完整文件不同，GNU Build ID 相同，且未优先确认执行段变化。继续检查文件、结构和 Note 差异；缺少执行段信息不能解释为代码相同。 |
| `EXECUTABLE_SEGMENTS_CHANGED` | 可执行 PT_LOAD 的内容哈希，或映射、大小、标志等结构变化。该分类优先于 Build ID 相同，必须重新分析受影响路径。 |
| `UNKNOWN_OR_INCOMPLETE` | 目标缺失、身份不足、事实互相矛盾，或无法归入其他类别。它既不表示兼容，也不等于已证明不兼容；先核对来源和输入。 |

报告先分别判断两个库，再汇总：执行段变化优先，其次是不完整；只有全部目标 ELF 精确匹配才给出总体精确匹配。报告也保留 APK、环境和桌面元数据差异，不会因为 ELF 一致而隐藏这些差异。命令返回成功只说明比较报告生成成功，报告中可以存在不匹配结论。

可执行段指带 `PF_X` 的 PT_LOAD，哈希覆盖 `p_offset` 开始的 `p_filesz` 个文件字节，不包含 `p_memsz` 多出的零填充内存，也不是只算 `.text`。RVA、ELF 虚拟地址和文件偏移必须明确区分，换算见[映像字段](#images)。

**相同 Build ID、Android Note 或执行段哈希，均不能单独证明 Hook ABI 兼容。** 工具始终把 `compatibility` 标为 `NOT_ASSESSED`，不能据此启用未知版本。

如需复核6144基线，先从可信存储取得与基线档案对应的原始 APK，再对它运行 `inspect` 到新的目录，与 `builds/6144/manifest.json` 比较，并用 `Get-FileHash -Algorithm SHA256 -LiteralPath '实际APK路径'` 复核原文件。两个 ELF 应精确匹配；新输出尚未补充的环境元数据与基线不同可以如实保留。拿不到原始文件时只能读取已有档案，不能宣称完成复验。

#### B5. 什么时候继续反汇编

发现 ELF 不同、执行段变化、Build ID 复用、库缺失或元数据矛盾时，先确认拿到的是正确输入。若任务确实需要适配新桌面，再用 Blutter 定位 Dart AOT 的函数和对象池，用 Ghidra/LLVM 等核对 ARM64 指令、调用链和原生边界。已有精确匹配且证据充分的部分可复用，不必无目的地重做。

比较只回答“哪些文件和事实相同或不同”。可用 Profile 还需要下一阶段的语义证据、真实解析器检查和目标手机验收。归档方法见[交付与交接](#handoff)。

### 阶段 C：让 AI 提交逐功能和逐补丁证据

复制给 AI：

```text
先按功能建立候选清单：普通图标、资源回退、配色、圆形、文件夹、刷新、返回动画、大图标。
以6144为语义参考，对目标 ELF 独立定位。每处补丁给出完整函数范围和原字节、
ELF虚拟地址与文件偏移映射、expected、各模式替换、原/新调用目标、寄存器/栈/返回值证据。
异步函数必须说明成功、null、异常和Future完成路径。只写已证实结论；缺证据就标未知。
把反汇编和生成脚本保存为文件，不只在聊天里描述，也不要先生成一个空数组“可导入模板”。
```

你要检查两张表，而不是读汇编：

| 功能 | 目标函数/加载路径 | 证据文件 | 静态结论 | 真机状态 |
| --- | --- | --- | --- | --- |
| 例如：换色无需点击 | AI 填写目标实际刷新链路 | AI 填路径与定位 | 已证明 / 部分 / 未知 | 未测 / 通过 / 失败 |

| patch ID | ELF 与函数范围 | 函数内 offset / expected | palette / icons / circle | 替换原因与调用约定 | 证据定位 |
| --- | --- | --- | --- | --- | --- |
| AI 逐条填写 | 不允许只列函数名 | 不允许使用占位值 | 包括恢复原指令 | 包括仍未知的部分 | 文件 + 地址/行号 |

Blutter 常用于 Dart AOT `libapp.so`，Ghidra/LLVM 等用于核对 AArch64 指令及原生边界。没有 DEX 时，反复运行 jadx 不会凭空得到 Dart 函数。工具失败时让 AI 先检查版本、`--help`、目标 snapshot 和错误日志；不要求你自己猜 Dart 版本，也不能因为缺 `libflutter.so` 就直接宣布所有分析不可行。

**新地址必须来自本次目标。** 同名函数在新版本中可能缩短、移动或改变调用约定；旧偏移可能已经越界。不能采用“找到同名函数后加旧偏移”的做法。

### 阶段 D：生成候选 Profile 和证据包

复制给 AI：

```text
只用已有证据生成 UTF-8 无 BOM JSON。使用独立自定义 id，保留官方文件，revision从1开始。
以实际 C++ Parse 为格式权威，不放猜测值、不扩宽系统条件来凑匹配。
将原始字节从目标 ELF 自动提取；记录生成命令和 x-evidence 来源。
无证据的必需项会阻止可测试候选完成，请交付缺口报告；可选功能可以明确暂不支持并保留系统行为。
输出候选文件、完整文件 SHA-256、逐补丁表、生成脚本、验证报告和后续验收步骤。
```

只复制官方配置、换 ID 的学习副本，也仍然只适用于官方两个 ELF。它与官方文件会同时匹配，应用自动模式可能因此要求手动选择。不要将这种副本命名成“全 HyperOS4 通用”。

### 阶段 E：在电脑上验证并交付

复制给 AI：

```text
对最终文件同时做二进制证据检查和真实 C++ 解析器检查，分别记录命令、输入哈希、退出码。
检查替换指令/分支和模式语义的证据。官方fixture回归不能代替我的候选验证。
不能执行的检查写“未执行+原因”，不能把编译测试APK写成设备测试通过。
最后确认检查的文件和交付的文件字节一致。未上机则标“离线候选，未完成真机验收”。
```

合格的本地交付至少包含：候选 JSON（证据不足时可以不提供）、单份 `PROFILE_REPORT.md`、可复现生成脚本与原始检查日志。将证据、验证结果、限制和交接状态集中写入这份报告，不再拆成多份手写文档。检查范围与未支持功能必须随候选一起交付；公开文档如何更新见[共享与发布](#sharing)。

### 阶段 F：需要手机验证时再发起明确任务

```text
我准备在以下测试手机上验证本候选：填写设备和环境。
允许的操作：填写，例如导入指定文件、选择该Profile、重启作用域、切换圆形/Lawnicons。
请先确认最终Profile摘要和回退办法，再按指南逐项验收。
按每个场景记录通过/失败/未测；截图必须确认为桌面，不能以日志代替画面。
```

若暂时没有手机，停在“离线候选/研究报告”就是符合事实的结果。AI 不应把缺少真机的事实藏在结尾，然后把文件称为“完整适配成功”。

<a id="validation"></a>
## 5. “验证通过”到底通过了哪一层

| 层级 | 方法 | 能证明 | 不能证明 |
| --- | --- | --- | --- |
| JSON 语法 | 文本/JSON 工具 | 文本可被该工具读取 | MonoIcon 接受格式、字节和指令正确；普通解析器还可能容忍重复键。 |
| 引擎结构和静态约束 | 实际 C++ `Parse`；应用导入也调用它 | 字段、引用、范围、快照与 expected 等符合当前规则 | 声明边界确实是完整函数、替换逻辑安全。 |
| 二进制证据 | `verify_hyperos4_profile.py` | 哈希、ELF、段、身份、函数字节和原指令与输入一致 | 全部 schema 字段、替换语义、运行效果。 |
| 语义审查 | 反汇编、调用者/目标、ABI和控制流证据 | 对改动为什么成立给出可复查依据 | 所有运行场景一定正确。 |
| Android仪器检查 | 在手机上运行指定的绘图、界面和集成用例 | 被执行断言所覆盖的行为 | 未执行/跳过的用例、未观察的视觉入口。 |
| 设备链路 | 当前环境、原生状态、PID、确认文件 | 实际目标匹配、发布/安装/读取达到指定阶段 | 最终画面和所有缓存已刷新。 |
| 视觉及恢复 | 手机逐场景观察和记录 | 被测试场景下显示、刷新、恢复行为 | 其他桌面版本、其他设备布局或未测场景。 |

### 5.1 二进制证据检查的真实命令

选择其中一种输入。`--lib-dir` 必须直接包含所需 `.so`：

```powershell
python tools/verify_hyperos4_profile.py 'external_resources/HyperOS4/builds/candidate-01/output/my-profile.json' --apk 'external_resources/HyperOS4/builds/candidate-01/input/launcher.apk'
```

或：

```powershell
python tools/verify_hyperos4_profile.py 'external_resources/HyperOS4/builds/candidate-01/output/my-profile.json' --lib-dir 'external_resources/HyperOS4/builds/candidate-01/intake/lib/arm64-v8a'
```

成功会显示 `binary evidence verified` 及匹配数量；失败应检查具体原因。这个工具不寻找 Hook 点、不生成或修改 Profile，也不替代真实解析器。它可读取压缩 APK 条目；实际运行时还要求系统提取后的库，或真实映射的未压缩 APK 条目。

已采集目标手机的完整 `ro.build.version.incremental` 时，可追加 `--system-version '目标完整系统版本'` 核对 `match.systemVersions`。未提供该参数时，工具只展示声明，不宣称系统匹配；旧配置缺少该字段时会提示需要手动确认。

### 5.2 自定义候选的真实解析器检查

当前仓库没有单独 `.schema.json`，也没有接受任意候选参数的现成 schema CLI。要求 AI 读取：

- [hyperos4_profile.h](../app/src/main/cpp/hyperos4_profile.h)：`monoicon::profile::Parse(const std::string&, Profile&, std::string&)`。
- [hyperos4_profile.cpp](../app/src/main/cpp/hyperos4_profile.cpp)：真正的字段和约束实现。

有主机 C++17 编译器时，让 AI 在本次 `output/` 生成一个小型检查入口：以二进制方式读入指定 JSON、调用上述 `Parse`、成功返回 0、失败输出 `error` 并返回非零；与项目的真实 `hyperos4_profile.cpp` 一起编译。要求它同时用一个有效文件和一个明显无效文件确认该入口确实会拒绝错误输入，保存源码、编译命令和候选检查日志。**不得修改正式解析器来让候选通过。**

没有主机编译器时，AI 应说明这个检查尚未执行，给出安装/使用现有编译器的办法，或把实际应用导入列为后续设备步骤。**导入不是无副作用的“在线 JSON 校验”：它会触发重新选择和发布。**

### 5.3 项目回归与编译的正确用途

```powershell
python tools/run_profile_tests.py
```

这条命令编译并运行项目原生回归，内部固定使用官方 6144 fixture。仅支持 `--cxx`、`--sanitize`，例如：

```powershell
python tools/run_profile_tests.py --cxx 'C:\Tools\mingw64\bin\g++.exe'
```

**不存在 `python tools/run_profile_tests.py my-profile.json` 这种候选校验用法。** 也不要直接把带官方数量断言的回归程序当通用候选验证器。

只改外部 JSON 通常不用重新构建 APK。若另外进行了引擎开发或要构建仪器测试，按实际变更运行相应检查；Windows 的项目构建形式为：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:testDebugUnitTest
```

前提是本机 JDK/Android SDK/NDK/构建配置已经满足项目要求。编译器路径只是示例，必须换成已安装工具的真实路径。缺少发布签名时如实报告，不能读取或泄露他人的签名密码。构建测试 APK 不会自动运行手机测试；设备测试需单独执行并记录。

收集工具本身的回归可单独运行，使用生成的最小 ELF/APK 样本，不依赖真实桌面二进制：

```powershell
python -B -m unittest discover -s tools/tests -p 'test_launcher_inspector.py' -v
```

<a id="validated-scope"></a>
<a id="compatibility"></a>
### 5.4 当前基线已经验证到哪里

下列结论只适用于[官方基线](#baseline)所列的精确输入和已实测手机环境。源码回归、Android绘图与界面检查、Profile传输、功能开关及恢复的最终检查已通过；无法在测试进程提供的MIUI内部类检查和未执行的QQ专项检查不计入通过范围。不要据此扩大系统版本、设备形态或应用覆盖范围。

| 已有证据覆盖的范围 | 可以据此确认 |
| --- | --- |
| Profile解析、原始二进制与原生事务 | 官方配置与目标库一致，实际发布能获得对应进程和摘要的确认。 |
| 绘图与图标资源回归 | 胶囊和圆角底板、透明留白、前景比例、边界变化、Alpha掩码与高清缓存的已执行断言成立。 |
| 设置界面 | 页面导航、触摸/键盘交互、布局方向、滚动恢复及返回行为的已执行界面断言成立。 |
| 配色、圆形、Lawnicons和总开关往返 | 相应配置、资源批次及恢复链路可用；禁用后原指令恢复，自动重启后的进程未写入主题补丁，重新启用与原设置恢复获得确认。 |
| 大图标资源 | 原生尺寸响应、真实资源归属、2×1/1×2/2×2导出及其网格比例已检查。 |
| 大图标实际画面 | 2×1和1×2的圆形/默认形状，以及2×2默认形状已有目视验证；2×2圆形仍需独立视觉确认。 |

仍需单独完成的视觉场景包括：手机重启后的首次桌面、无需点击的实时换色、完整普通/大文件夹预览刷新、动态资源、应用返回动画、最近任务、通知入口以及Profile更新/切换。仪器检查中保存的资源或进程确认不能替代这些画面。

若要让AI再次运行设备检查，先说明具体目标与影响。`HyperOs4ProfileDeviceIntegrationTest` 使用 `-e hyperos4ProfileDevice true`，会发布新批次并验证当前进程的传输与确认；`HyperOs4DeviceIntegrationTest` 使用 `-e hyperos4Device true`，会改变配色、圆形、Lawnicons和总开关并恢复设置，包含桌面重启。前者不能代替后者，二者都不等同于完整视觉验收。命令应按已安装测试包、runner和设备生成，不照抄别人的设备序列号或包路径。

测试前确认LSPosed仍授权系统桌面；已运行的旧进程可能保留先前加载的模块，不能以它仍显示主题作为作用域有效的证明。禁用时等待应用自身完成恢复和自动重启，不叠加循环强杀。

<a id="device"></a>
## 6. 导入、选择、手机验收和回退

### 6.1 正常使用步骤

1. 确认手机上的 MonoIcon 版本支持所用字段；旧引擎可能拒绝新字段。安装模块与 Profile 是两件事，通常只需导入 JSON。
2. LSPosed 启用 MonoIcon，作用域勾选“系统桌面”和“系统界面”（`com.miui.home`、`com.android.systemui`）；为 MonoIcon 授予 root，供配置和图标发布。
3. 先导出/保留当前可用自定义配置，记录原设置，保证能重新进入MonoIcon或LSPosed。把AI交付的确切JSON复制到手机 `Download`（下载）目录。
4. 打开 **配置管理 → HyperOS 4 Profile → 导入 Profile**，在文件选择器的“下载”中选择该JSON。不要选intake的 `manifest.json`、聊天ZIP或提示词Markdown。
5. 查看环境匹配与发布状态。自动模式只选择唯一匹配且声明适配系统的 Profile；有多个匹配项时手动选择。缺少 `match.systemVersions` 的旧配置仍可导入，但必须手动确认当前系统未经声明验证后才启用。手动确认不能绕过已声明的系统、SDK、桌面版本或 ELF 验证。
6. 等待发布完成及相应提示，首次启用模块、模块APK升级或切换/更新Profile后，使用右上角电源菜单中的“重启作用域”加载相应Hook和配置。大图标发布或形状改变也需要重启作用域以可靠刷新其Widget缓存。
7. 不点击任何桌面图标先观察画面，再进行下表测试。新模块涉及开机存储/原生启动升级时，先发布一次配置，再按该升级说明重启手机；日常换颜色不应一律要求重启整机。

导入不能覆盖官方 ID；同一自定义 ID 会原子更新，建议 revision 递增。自动匹配不按 revision 选最高版本。自定义文件最多 64 个，官方文件不能删除；删除当前手动选择的自定义项会回到自动匹配。旧配置的手动确认绑定完整文件摘要和当前系统身份；重新导入、替换文件或系统升级后需要重新确认，切回自动模式也不会自动启用旧配置。

日常取色、圆形、Lawnicons开关位于“图标样式”。下文“关闭模块功能”是指 **模块设置 → 启用模块** 总开关；不是卸载APK，也不是在LSPosed中停用。**“重启作用域”会重启相应桌面/SystemUI进程**，不只是让页面重新绘制。

### 6.2 给零基础读者的验收表

每项记录“通过 / 失败 / 未测”，附设备、系统、桌面版本、MonoIcon 版本、Profile ID/revision/完整 SHA-256 和测试时间。AI 收集日志时同时记录当前桌面 PID，防止拿上一个进程的结果当新结果。

| 场景 | 操作与观察 | 通过标准 |
| --- | --- | --- |
| 首次进入桌面 | 完成必要发布，重启测试手机后首次进入；拍摄未点击图标的画面。 | 不靠点击或再次重启桌面触发主题化。 |
| 桌面单独重启 | 使用应用“重启作用域”，等待加载。 | 普通图标正确显示，桌面不反复退出。 |
| 换色 | 修改一种配色，返回桌面且不点图标。 | 相关图标自动变色，不只更新被点的那一个。 |
| 圆形与默认形状 | 分别开启、关闭圆形。 | 轮廓对应设置，透明边缘正确。 |
| 返回动画 | 圆形开启，打开应用后返回桌面，可录屏查看。 | 过渡中不会先变默认圆角矩形再恢复圆形。 |
| 普通与大文件夹 | 比较关闭时预览、打开后图标、换色后的预览。 | 预览随更新重建，不需要单独交互才变。 |
| 日历/时钟等动态资源 | 比较应用图标与文件夹预览。 | 启用时使用静态主题图案；不要把日历字形中的数字当当天日期。 |
| Lawnicons | 开启→关闭→开启，观察相同应用。 | 来源和回退正常，重新开启可恢复。 |
| 2×1 / 1×2 / 2×2 | 对已支持大图标应用逐尺寸检查，更改后按提示重启作用域。 | 圆形开启：长方形是两端半圆的胶囊，2×2为圆；关闭：圆角矩形；前景不拉伸。 |
| 缺失图案或未知布局 | 使用未生成有效资源的条目/未支持布局。 | 保留系统资源，无空白或错误覆盖；标记该功能未覆盖。 |
| 关闭恢复 | 关闭模块功能，等待恢复状态。 | 系统图标和动态路径恢复；无持续主题缓存。 |
| Profile 更新/切换 | 只在具备另一个有效候选且允许测试时操作。 | 明确提示重启；不混用新旧补丁。没有第二个有效候选就标未测。 |
| 最近任务/通知 | 分开记录表现和前台截图。 | 所测入口外观符合目标；通知由 SystemUI 链路处理，异常不应直接归因于 Profile。 |

如果只能完成一部分，就列出通过的具体场景。截图必须真的拍到桌面；测试期间前台停在设置页的截图不构成桌面视觉证据。

<a id="large-icons"></a>
### 6.3 大图标与 Lawnicons 的使用说明

| 桌面尺寸 | 圆形开启 | 圆形关闭 |
| --- | --- | --- |
| 2×1、1×2 | 胶囊形，两端半圆，圆角半径为可见短边的一半 | 圆角矩形 |
| 2×2 | 圆形 | 圆角矩形 |

底板按目标实际宽高绘制，前景图案居中等比缩放；圆形模式保留AOSP透明留白。普通1×1图标沿用普通图标流程。这里的格数不是像素比例：2×1占两个横向网格，也不意味着输出PNG恰好是2:1。

HyperOS 4的大图标从尺寸资源目录读取，不能仅替换普通目录中的方形PNG。模块读取原生网格标量并为每个可用尺寸单独导出；只有成功生成且资源归属明确的条目才参与重定向。未分析的平板/折叠屏布局、读取失败、配对应用或归属不明的条目保留系统资源。实测手机的纵向布局不等于横屏、平板和折叠屏都已验证。

桌面会缓存整个大图标Widget，普通图标刷新广播不一定清除这层缓存。发布大图标资源或改变形状后，按应用提示使用“重启作用域”。重新使用旧批次前会核对网格和资源配置；信息暂时不可读时保留此前有效批次，不用猜测尺寸重绘。

Lawnicons内置图案来自对应许可证标注的原始SVG，以768×768重新栅格化；运行时保留640～1024像素掩码和原有 `215/320` 图案布局比例，开启过滤和抗锯齿。普通HyperOS 4图标导出为512×512；大图标按实际宽高比生成，不能以“像素越多”代替比例和边缘检查。单通道Alpha缓存保留覆盖率并受字节上限约束。图案来源、生成参数、哈希和许可证在 `app/src/main/assets/lawnicons/`，授权汇总见[第三方声明](THIRD_PARTY_NOTICES.md)。资源核对命令为：

```powershell
node tools/render_lawnicons_masks.cjs --verify
```

HyperOS 3的大图标使用桌面控件的实际尺寸和同一用户的准确应用组件读取原始APK资源，遵循相同的前景等比与失败回退原则；它走Java Hook链路，不使用本文的HyperOS 4原生Profile。源码入口见[附录](#source-index)。

### 6.4 出错后怎样回退

安全模式始终开启，没有独立开关，覆盖 HyperOS 3 Java 桌面和载入 Profile 后的 HyperOS 4 原生桌面。模块介入前记录受保护进程，连续存活30秒后记录本轮启动健康；若桌面未完成健康检查就短命退出，同一次开机中的下一次启动会锁定停用模块，先让桌面恢复。检测和阻断不依赖设置应用在后台运行。正常整机重启不会因上一轮的未完成记录误判；从 MonoIcon 发起的预期作用域重启也有单次豁免记录。

触发后的停用状态会保留，修改配色或切换 Profile 不会自动解除。检查日志并改用适配配置后，通过已有的模块总开关手动重试；下一轮仍接受同样的保护。安全模式降低连续重启风险，不能证明任意 Profile 适配成功。

- 能打开 MonoIcon：记录失败表现和日志，关闭功能或恢复此前已验证的配置，按应用提示重启作用域。
- 桌面反复崩溃且应用难以进入：使用预先可访问的 LSPosed 管理入口停用 MonoIcon 对系统桌面的作用，必要时使用自己 root/模块框架已了解的恢复方式。不要临时照抄与设备不符的救援命令。
- 不以 `pm clear com.miui.home`、删除桌面数据库、替换系统 APK、关闭 SELinux、循环强杀或禁用二进制验证作为常规回退。它们不能证明 Profile 正确，还可能丢失桌面布局。

让 AI 一次修一个已定位问题，保存失败版本与证据。若日志显示未知指令或摘要不匹配，先停止使用该候选并核对原始输入。

<a id="troubleshooting"></a>
## 7. 出现这些情况，怎样向 AI 反馈

先保存本次日志：打开 **MonoIcon → 模块设置 → 发送日志 → 保存日志**，将保存的TXT交给AI；也可从该对话框进入“运行日志 → 导出日志”。“发送日志”是菜单名称，保存到本地后再自行选择是否分享。附上操作步骤、Profile摘要和对应截图，避免只有一句“不生效”。

| 现象 | 优先让 AI 查什么 | 不能用什么糊弄过去 |
| --- | --- | --- |
| JSON 导入失败 | 编码/BOM、重复键、未知字段、缺项、真实 C++ 错误。 | 删掉校验器不喜欢的证据字段或直接改解析器。 |
| 导入成功但不兼容 | 当前包版本、SDK、属性、HAS_CODE、split、两个 ELF。 | 把版本列表扩大、抄官方系统属性或改摘要。 |
| 自动模式没有选中 | 是否有多个匹配的自定义副本、系统版本不在声明列表中，或配置没有 `match.systemVersions`。 | 声称会自动取最高 revision，或静默启用未声明适配系统的配置。 |
| `result=13` | Profile 缺失/无效/系统条件不符，连同 `reason`、`phase`。 | 只看数字盲改地址。 |
| `result=14` | Profile 已切换，需使用旧事务恢复后重启采用新配置。 | 强行热切换新旧偏移。 |
| 有 PNG、有 `.applied`，图标仍旧 | 同批次/同 PID、桌面是否可见文件、读取、V2/旧缓存、模型、widget。 | 把原生成功等同于画面成功。 |
| 只有点击的图标更新 | 单个控件持有旧 drawable、代理提前返回、重载链路。 | 反复增加广播次数。 |
| 文件夹或日历预览不更新 | 文件夹条目列表、MAML 分派、普通预览对象是否复用。 | 只检查桌面上普通应用图标。 |
| 圆形返回动画变方 | 透明边缘是否被填充、动画独立位图路径。 | 只换圆形遮罩。 |
| 大图标拉伸/不更新 | 实际网格几何、native Bundle ABI、资源目录、Widget 缓存。 | 固定用2:1比例或只放大普通方形PNG。 |
| 大图标尺寸一直无响应 | 本PID的 `monoicon_geometry.status`，区分库映射、导入符号、工作线程、调用和响应阶段。 | 只改库名、跳过符号身份检查，或把旧PID的响应当成本次成功。 |
| 只在每次开机第一次失败 | DE发布批次、预加载库、同步首轮安装、后台是否误覆盖。 | 让用户每次开机都手动再重启桌面。 |
| 工具显示找不到参数/文件 | 工具版本、实际 `--help`、当前目录、被Git忽略的材料是否存在。 | 编造执行成功输出。 |

通用反馈模板：

```text
Profile ID / revision / SHA-256：……
MonoIcon / 系统 / 桌面版本：……
步骤：我先……，然后……
预期：……
实际：……（明确是整页、单个图标、文件夹预览还是动画）
是否只在点击后更新：……
附件或路径：本地日志、截图/录屏、原始APK证据、PROFILE_REPORT.md中的验证部分
请先定位数据链路停在哪一层，再修改；不要把旧日志或静态检查当本次视觉通过。
```

### 7.1 同桌面版本、不同系统为什么仍可能短命重启

当前源码调查发现，旧官方配置只限制 SDK 37 与 `OS4.` 前缀，而归档里明确实测的系统是 `OS4.0.0.3.XAACNXM`。revision 9 增加完整系统版本限制，使自动选择范围与已有证据一致。桌面 versionCode/versionName 相同也可能对应不同 ELF；即使两个桌面 ELF 的 SHA-256 都相同，其系统依赖与启动环境仍可能不同。

以下是有源码依据的排查方向，**尚未取得反馈设备的崩溃 tombstone，不能视为该反馈的已确认根因**：

- [大图标尺寸桥接](../app/src/main/cpp/hyperos4_geometry_bridge.cpp) 使用固定的 native Bundle 结构和调用签名，并调用系统提供的 `libhyper_os_schema_public.so`。现有检查验证桌面导入符号及其来源，但没有把这份系统库的完整摘要纳入 Profile；系统更新改变 ABI 或返回对象布局时，桌面库相同也不能证明调用安全。
- [native_init](../app/src/main/cpp/hyperos4_native_init.cpp) 与[启动边界](../app/src/main/cpp/hyperos4_startup.cpp) 依赖 `hyos_spawner`、预加载库和线程建立顺序。ROM 改变这些时序后，同一补丁可能在不同阶段执行。
- [内存检查](../app/src/main/cpp/hyperos4_profile_runtime.cpp)、[线程暂停](../app/src/main/cpp/hyperos4_thread_pause.h) 和[补丁安装](../app/src/main/cpp/hyperos4_palette_install.cpp) 会受到厂商 SELinux/seccomp、信号处理与页权限策略影响。能返回错误的拒绝已有停止路径，但致命信号或 ABI 不兼容不能靠返回值检查完全覆盖。

定位时至少收集失败设备的 `ro.build.fingerprint`、`ro.build.version.incremental`、桌面包版本与两个 ELF 摘要、tombstone 的 signal/backtrace，以及同一 PID 的 `monoicon_hyos_palette.txt` 和 `monoicon_geometry.status`。先区分崩溃发生在补丁前、Bundle 调用还是线程暂停/写页期间，再决定新系统需要新增证据约束还是修改引擎。

<a id="schema"></a>
## 8. 给 AI 查阅的 Profile 字段规范

本节无需新手逐字背诵。格式权威是当前源码的 [C++ 解析器](../app/src/main/cpp/hyperos4_profile.cpp) 和 [JSON 读取器](../app/src/main/cpp/profile_json.h)，导入 JNI 共用它。升级源码后先重新核对；不得擅自生成一个格式近似但引擎不支持的 Profile。

### 8.1 文件、元数据和环境条件

使用 UTF-8 **无 BOM**，不包含注释、重复键、尾随逗号或 JSON 之后的额外文本；文件上限 2 MiB。SHA-256 和快照字节使用连续小写十六进制字符串，不带空格、`0x` 或省略号。地址、偏移、指令值可用非负整数或 `"0x1234"` 形式；`schema`、`revision`、SDK 统一使用 JSON 整数。

| 顶层字段 | 必需性与用途 |
| --- | --- |
| `schema` | 必需，当前填写整数 `1`。 |
| `id` | 必需，1～80字符，仅小写字母、数字、`-`、`_`；不要冒用官方 ID。 |
| `name` | 必需，非空，最多256字节，不含控制字符；说明实际目标。 |
| `revision` | 必需，1～2147483647；建议每次发布递增，程序不强制单调增长。 |
| `match` | 必需，真实包版本、SDK和只读系统属性。 |
| `settingsKey` | 必需，目标既有 `Settings.System` 通知键；最多128字节，小写字母/数字/下划线。 |
| `images` / `functions` / `patches` | 必需，且三者均不能是空数组。 |
| `icons` | 必需，查找目录、命名策略和别名对象。 |
| `refresh` | 可选，目标既有接收器的逐包刷新契约。 |
| `veneer` | 可选，仅用于受支持的标量模式代码模板。 |
| `x-evidence` 等 `x-*` | 建议在根级放来源、工具、验证范围等元数据；引擎不将其解释为操作。 |

未知普通字段/操作会被拒绝，不支持 `script`、下载动态库或随意执行命令。`x-evidence` 写“已经验证”不会自动产生验证效果。

下面是官方 `match` 的**片段示例，不是完整可导入文件**：

```json
{
  "package": "com.miui.home",
  "versionCodes": ["801026144"],
  "versionNames": ["RELEASE-8.01.02.6144-260818-08251107-R"],
  "sdkMin": 37,
  "sdkMax": 37,
  "systemVersions": ["OS4.0.0.3.XAACNXM"],
  "properties": [
    {"name": "ro.build.version.incremental", "prefixes": ["OS4."]}
  ]
}
```

code 与 name 分别命中各自列表；code 是标准十进制**字符串**，不能有前导零。SDK 为包含两端的范围。所有属性条件都须满足，同一属性的多个前缀满足其一即可；`OS4.*` 中的星号不会当通配符，前缀是字面文本。只允许 `ro.*` 属性，缺失不能视为匹配。

`match.systemVersions` 是可选的适配系统字段：存在时必须为1～128个唯一非空字符串，每项最多512字节、不含控制字符。每项与目标手机的 `ro.build.version.incremental` **区分大小写、完整相等**比较，不接受前缀或通配符匹配。填写已实际验证的完整系统版本；新系统只有在完成验收后才加入列表。该字段与其余 `match` 条件同时生效，手动选择不能跳过不匹配的声明。

字段缺失表示未声明系统适配范围，而不是“所有系统均兼容”；JSON 仍可被解析、导入，但只能由用户显式确认后手动启用。`null`、空数组和重复项均无效。确认依据是 Profile 内容摘要及系统指纹、完整增量版本、SDK；应用把对应凭据发布为 `monoicon_profiles/<摘要>.system`，原生引擎加载旧配置时再次验证。升级模块前遗留的无凭据配置不会在开机时自动继续生效，系统升级后旧凭据也会失效。

<a id="images"></a>
### 8.2 `images`：精确识别原始 ELF

每项为 `id`、`library`、`size`、`sha256`、`segments`、`identity`。当前支持1～8个映像，ID和库名唯一；库应为小端 AArch64 ET_DYN，大小64字节～1 GiB。ELF虚拟地址及段末端限制在 `0xffffffff` 内。

`segments` 必须按真实 ELF 顺序包含**全部 PT_LOAD**，每段记录 `offset`、`address`、`fileSize`、`memorySize`、`flags`、`alignment`，不是只填 `.text`。支持1～32段，范围/对齐必须有效且不重叠；`PF_R=4`、`PF_W=2`、`PF_X=1`，禁止可写可执行段作为目标。

```text
文件偏移 = PT_LOAD.offset + (ELF虚拟地址 - PT_LOAD.address)
```

必须先确认所取整个范围都在该段的文件数据内。`memorySize` 大于 `fileSize` 的零填充部分没有对应原始文件字节。6144 的 `libapp_launcher.so` RX 段中，文件偏移与虚拟地址相差 `0x4000`，不能直接把地址当 Python 切片下标。

`identity` 有1～32项，每项含 `address` 和1～4096字节的 `bytes`，须位于可读且有文件数据的映射范围。常取 GNU Build ID note 或 Android note 的实际字节。无 GNU Build ID 时保留事实，不能造一个；完整文件 SHA-256 始终需要。Android Note 中的 `abi_api=35` 是编译元数据，不是设备 Android SDK。

### 8.3 `functions`：必须是真正完整的函数

每项包含 `id`、`image`（引用映像ID）、`address`（起点ELF虚拟地址）、`bytes`（原始字节序）。1～256份，单份≤256 KiB，总计≤512 KiB；起点和字节长度为4的倍数；位于RX段，同一映像内不能重叠，每个映像至少有一份快照。

所有受修改函数须有完整快照；新增调用目标、原生桥接入口及关键调用者也应保存足够的完整证据。不要为了绕过大小/重叠检查，把真实大函数切成几段冒充独立函数。

解析器能检查你声明的范围和字节关系，**不能自动证明声明边界是完整函数**。AI 必须用函数入口、全部出口、相邻函数边界、调用关系等证据说明范围。

### 8.4 `patches`：单条指令与三种模式

1～256项，每项含 `id`、`function`、函数内 `offset`、原始 `expected`、必需的 `palette` 和 `icons`；可选 `circle`，省略则继承 `icons`。地址不能重复，偏移4字节对齐且整条指令在函数内。

原字节 `04 02 91 9a` 按小端解释为 `expected: "0x9a910204"`，不能直接写成 `0x0402919a`。以下是官方 `enable` 补丁的结构示例，**其值只适用于原来的精确函数**：

```json
{
  "id": "enable",
  "function": "settings",
  "offset": "0x9c",
  "expected": "0x9a910204",
  "palette": {"word": "0xaa1003e4"},
  "icons": {"word": "0xaa1103e4"}
}
```

| 模式 | 用途 |
| --- | --- |
| `palette` | 旧标量颜色模式。 |
| `icons` | 导出 PNG、默认形状。 |
| `circle` | 圆形 PNG；省略沿用 `icons`。 |
| 关闭 | 恢复 `expected` 原指令，不由候选另填一套关闭地址。 |

替换值支持：

| 写法 | 含义 |
| --- | --- |
| `"original"` | 保留原指令。 |
| `{"word":"0xd503201f"}` | 指定32位指令，此例为NOP。 |
| `{"value":"background-low","register":1}` | `MOVZ W1` 写背景ARGB低16位。 |
| `{"value":"background-high","register":1}` | `MOVK W1, LSL #16` 写背景高16位。 |
| `{"value":"foreground-low","register":2}` | 写前景ARGB低16位。 |
| `{"value":"foreground-high","register":2}` | 写前景ARGB高16位。 |
| `"veneer"` | 仅 `palette` 可用，跳转到引擎生成的邻近代码页。 |

寄存器编号0～30，颜色操作写W寄存器，会零扩展至对应X寄存器。`word` 可以容纳错误但格式合法的指令，因此必须审查分支范围、目标、寄存器存活、对象池、栈、返回值及异步完成语义。不能仅靠“expected匹配”判定替换安全。

### 8.5 可选标量 `veneer`

schema 1只支持一个，且恰好有一个 `palette: "veneer"` 引用。`veneer.words` 为2～64项：首项是重放被替换原指令的 `word`，末项为 `"return"`，中间为受支持指令/颜色操作。引擎生成回到补丁下一条指令的AArch64 `B`，检查往返范围，将代码页设为RX；继续点也必须在已验证函数内。

官方模板中 ColorFilter 判断、对象池加载和前景值注入都来自6144。新版本不能照抄对象池偏移。缺少所需执行能力时，要另行升级和验证引擎/schema，不得假设新 JSON 字段能生效。

### 8.6 `icons`：资源目录、名称和别名

`prefixes` 为1～32个绝对目录，以 `/` 结尾，无 `..`、重复斜杠或反斜杠；只允许重定向这些资源目录中的有效安全PNG。官方普通目录是 `/data/system/theme_magic/customized_icons/` 和 `/product/media/theme/miui_mod_icons/`；新目标要独立确认。

| `names` 策略 | 导出候选名称 |
| --- | --- |
| `qualified` | class以package开头时为 `<class>.png`，否则为 `<package>#<class>.png`。 |
| `class` | `<class>.png`。 |
| `component` | `<package>#<class>.png`。 |
| `package` | `<package>.png`，包级回退。 |

至少包含一种组件级策略，不能只有 `package`。`aliases` 是必需对象，可为 `{}`，最多4096项，表示候选PNG名到兼容别名的映射；不是路径，也不是刷新包名。

安全文件名最多255字节，以 `.png` 结尾，仅字母、数字、`.`、`_`、`$`、`#`、`-`，不含 `..`。官方13组别名不应盲目复制到规则不同的目标。

### 8.7 大图标可选字段与当前引擎限制

`icons.largeIconPrefixes` 若出现，须为1～16个有效根目录；`icons.geometryBridge` 为完整快照验证的函数ID，必须同时有大图标根，不能引用Dart `libapp.so`，也不能对这个桥接函数打补丁。

当前引擎只把根目录和桥接入口做成数据，**资源配置、Bundle键与手机布局算法并非任意可配置脚本**。迁移必须确认目标满足现有契约：

- 根目录下 `<user>/apps/*/runtime_largeicon.config` 的 `packageName`、`path`、`isPairApp` 语义；加载器枚举 `<path>/<1x2|2x1|2x2>` 和 `res/drawable[-density]/<package>.png`。
- 6144 的 `FuncBridge.invoke_method` 为 `libapp_launcher.so @ 0x64ae9c`，使用native Bundle ABI；UTF-8名称/长度、可空输入、返回Bundle所有权、读取和释放方式均需验证。
- 6144 的 `Bundle_get_f64`、`Bundle_get_f32`、`Bundle_drop` 实际来自 `libhyper_os_schema_public.so`。当前引擎从已完整哈希验证的桌面ELF按符号名称解析导入槽，通过安全内存读取取得已绑定指针，再用 `dladdr` 确认唯一已加载schema库中的准确符号；不从模块自己的链接器命名空间重新打开库。迁移到新桌面时必须重新核对这项契约。
- `getCellWidth`、`getCellHeight` 的double `result`，`getIconScales` 的float `portrait_icon_scale` / `landscape_icon_scale`。不能通过公开ContentProvider想当然调用这些入口。
- 6144手机布局：`s = cellWidth × iconScale`；宽为 `(spanX−2)×cellWidth + cellHeight+s`，高为 `(spanY−1)×cellHeight+s`。格数2×1不等于像素一定2:1。

独立唯一线程请求只读标量，不接触Dart堆对象；请求/响应绑定PID、Profile摘要、nonce并检查两次采样一致。未知平板/折叠屏布局、尺寸失败、配置变化或资源归属不明时保留系统大图标；配对应用等未成功生成条目不参与重定向。缺证据时省略可选能力并报告范围，不能填一个“看起来像”的地址。

大图标桥接同时依赖支持上述ABI的模块引擎和匹配的Profile。**只导入新JSON不能为旧引擎增加它尚未实现的桥接能力。** 应先确认模块版本，再按提示发布并重启作用域，检查实际画面。

仅成功导出的资源写入 `.large-icons`，`.large-signature` 记录网格和资源配置。大图标Widget缓存需要重启作用域。形状和清晰度说明见[大图标与Lawnicons](#large-icons)。

### 8.8 `settingsKey` 和可选 `refresh`

6144的设置键是 `key_monochrome_color`。刷新契约示例为：

```json
{
  "action": "com.xiaomi.market.ACTION_HD_ICON_UPDATE",
  "packageExtra": "packageName"
}
```

这是 `refresh` 的值片段；若声明，两项都需要。`action`为1～256字节，仅英文字母、数字、下划线、点号，至少有一个点；`packageExtra`为1～80字节，仅英文字母、数字、下划线。必须来自目标已有接收器，不能随便起广播名期待系统实现它。

要证明接收器何时注册、怎样清理V2/旧缓存、重建模型/控件，以及新增调用的ABI。没有 `refresh` 时应用会提示手动重启，仍可保留 `settingsKey` 通知；单纯变更设置不保证全部缓存刷新。刷新包名来自批次 `.packages` 的真实应用身份，不能从兼容PNG别名推导。

<a id="runtime"></a>
## 9. 运行机制与诊断文件

### 9.1 从导入到显示

```text
原始APK/ELF + 环境资料 + 分析证据
                  ↓
Profile JSON → 应用导入/匹配/选择
                  ↓
应用按用户设置生成Palette和PNG
                  ↓
Root发布完整Profile与PNG批次，最后原子提交Palette
                  ↓
LSPosed native_init → 实际ELF/完整快照验证 → 原生补丁事务与文件Hook
                  ↓
配置确认 → 对当前桌面PID投递刷新 → 模型/控件重建 → 实际画面
```

普通有Java代码的桌面和SystemUI仍使用原有Java Hook链路。Profile不提供脚本解释器、动态库下载、任意JNI/Dart对象桥接。大图标受验证的native Bundle桥接只读取既有标量接口。

Profile不保存用户当前的颜色/圆形/Lawnicons开关；Palette保存它们并引用Profile内容摘要：

```text
MONOICON 3 <enabled> <background> <foreground> <circle> <lawnicons> <g-32位十六进制> <profile-sha256>
```

应用图标来源优先级：有效原生monochrome → Lawnicons（可关闭）→ AOSP回退；空白或全不透明占位层不能阻断回退。每批次使用唯一 `g-<32位十六进制>` 目录，含PNG、`.ready`、真实包名清单 `.packages`，大图标还有相应映射/签名。`.packages`按真实桌面Activity包名去重排序，也包含已枚举但可能跳过PNG导出的应用，使它们能清旧缓存并走系统回退。

Root发布使用 `su -M` 的全局挂载环境，设置桌面UID、权限和SELinux上下文；完整资源就绪后才原子重命名Palette。发布成功和桌面实际可读是两项独立条件，需要分别核对。不要指导用户手工向旧路径复制文件来替代应用发布。

### 9.2 开机、切换与事务保护

当前发布根为 `/data/user_de/<user>/com.miui.home/files/`，设备加密（DE）目录在首次解锁前可读。只有DE提交不存在时才迁移回退旧 `/data/user/<user>/com.miui.home/files/`（CE）；DE存在但损坏不倒退读旧提交。

桌面加载 `libapp.so` 时，符合条件的加载回调先同步完成首轮 `PaletteStep`，与后台更新共用事务状态，然后返回给桌面。仅补丁全部位于Dart图像 `libapp.so` 的配置走此同步首轮；涉及其他原生启动调用者的自定义补丁保留后台安装，避免修改正在使用的启动函数。

继承预加载库而没有新加载回调时，`pthread_create` Hook补充启动边界：仅精确目标进程、已脱离root身份、启动图像均映射时触发，按PID领取一次所有权。spawner和其他应用透传。SystemUI观察桌面新PID、唤醒后台Job，优先复用已有有效DE批次；不因MonoIcon后台进程刚启动就重导出覆盖现有配置。

安装前重新检查映射、ELF、完整快照并暂停其他桌面线程，排除正在执行的受影响函数；保留私有代码页权限、指令缓存维护、写后读回和失败回滚。`/proc/self/mem`受限时使用受控私有非阻塞管道的内核读取回退，失败则停止；不直接盲读地址或绕过全部验证。运行中只认可原始值和上一事务值，未知指令变化会停止安装。

一个桌面进程内保持Profile不可变。换Profile时用**旧Profile**验证和恢复旧补丁，报告需要重启，新进程再采用新Profile，不混用两套偏移。旧 `MONOICON 1/2` 格式必须有 `monoicon_profile.active` 明确指向已发布配置；无指针时没有偷偷使用6144硬编码补丁的回退。

### 9.3 缓存刷新与关闭恢复

Root异步worker等待同一批次完整Palette确认、当前PID状态和该PID的动态接收器注册后，按 `.packages` 逐包投递。每次继续核对PID和批次；被新进程或新提交替代的任务停止。SystemUI通过受 `STATUS_BAR_SERVICE` 权限保护的显式广播请求后台导出Job，壁纸、系统配置和应用包变化也使用相应更新链路；后台Job运行仍受系统调度影响。

`monoicon_refresh.applied` 是投递去重标记，**不是重绘完成回执**。文件Hook读取计数同样不是最终画面证明。无刷新契约的Profile提示手动重启。

关闭时先等当前PID确认原指令恢复（`result=12`）。若该PID曾写入主题补丁，worker只结束精确桌面子进程以清掉内存主题缓存；不操作spawner、不清桌面数据、不主动启动HOME。没有写入补丁的PID不会因此被结束。

### 9.4 文件位置和含义

应用私有导入目录为 `files/hyperos4-profiles/<id>.json`；发布改为内容SHA-256文件名。JSON空白变化也会改变摘要，交付后不要再手工美化文件而沿用旧验证记录。

以下均在当前用户桌面的DE发布根下：

| 文件 | 用途 |
| --- | --- |
| `monoicon_profiles/<sha256>.json` | 实际发布的不可变Profile内容。 |
| `monoicon_profiles/<sha256>.system` | 无 `systemVersions` 的旧配置的手动确认凭据，绑定完整系统身份；更换系统后失效。 |
| `monoicon_safety_state.txt` | 跨桌面进程保留的启动健康与停用状态。 |
| `monoicon_safety_reset.txt` / `monoicon_safety_expected.txt` | 用户显式重试凭据 / MonoIcon 主动重启的单次进程身份凭据。 |
| `monoicon_profile.active` | 旧Palette格式显式复用的Profile摘要。 |
| `monoicon_palette.txt` | 最新选项、图标批次和Profile引用。 |
| `monoicon_palette.applied` | 原生已应用的完整配置；应与本次Palette字节相同。 |
| `monoicon_refresh.applied` | `pid\|generation\|digest`，表示刷新请求已投递。 |
| `monoicon_hyos_palette.txt` | ID/摘要、PID、`reason`/`phase`、安装恢复结果、验证写入计数；`startup-applied`表明同步首轮确认。 |
| `monoicon_hyos_icon_files.txt` | 文件Hook和重定向计数。 |
| `monoicon_geometry.request` / `monoicon_geometry.response` | 大图标网格请求和六个原生标量；必须匹配当前PID、Profile摘要和nonce。 |
| `monoicon_geometry.status` | 尺寸桥接诊断阶段；`response-written` 表示本次原生响应已写出，仍需核对导出资源和画面。 |
| `monoicon_icons/g-*/` | PNG、`.ready`、`.packages`和可选大图标映射；确认后保留当前及上一代。 |

root用于发布，日志页面本身不依赖root。日志应通过应用导出或由AI在已授权范围内读取，不让新手修改这些内部文件。

<a id="handoff"></a>
## 10. 交付、归档与跨工具继续

### 10.1 本地交付只维护一份报告

不要用聊天窗口代替交付目录。每个候选保留未经修改的原始APK/ELF和第一次 `inspect` 输出，后续分析和手工补充写到独立文件。版本来源未确认时继续使用采集编号或哈希前缀，不猜测版本号。

| 本地交付物 | 内容 |
| --- | --- |
| 候选Profile | 完整JSON、ID/revision、文件SHA-256、适用环境、已支持及未支持功能。证据不足时可以不提供候选。 |
| `PROFILE_REPORT.md` | 单份报告，包含身份与来源、功能覆盖、逐补丁证据和ABI依据、验证结果、限制及交接状态；区分已验证、推断和未知。 |
| 生成脚本 | 从本次原始输入生成候选的可复现步骤，避免手工抄写长快照。 |
| `checks/`或原始证据目录 | 工具原始输出、临时检查入口、日志、截图等；由报告准确引用，不另外维护重复的手写说明。 |

`PROFILE_REPORT.md` 的验证部分记录实际输入哈希、方法、命令、退出结果和验证层级，并分别列出失败、跳过和未测；交接部分说明当前已确认事实、剩余问题、下一步及所需材料和授权。新AI先读这一份报告，再按需读取原始证据。

这些是本地工作材料，可以保留定位问题所需的详细数据，**不作为公开阅读文档提交**。设备日志、截图、完整命令输出、失败实验和交接流水保持在本地证据目录；公开指南不收录这些内容。需要私下移交时，先核对接收者和材料范围，检查设备标识、私人文件路径、桌面内容及凭据。

### 10.2 给采集结果建立可追溯档案

下面将一次已完成的采集复制到新的本地档案目录，作为原始输出备份，不加入公开文档提交。目标目录必须不存在，避免覆盖已有证据；路径均相对仓库根目录，可按本地存储约定调整。工具自动生成的 `report.md` 属于原始证据，不需要改写成另一份交接报告。

```powershell
$intakeDir = 'external_resources/HyperOS4/builds/candidate-01/intake'
$archiveDir = 'external_resources/HyperOS4/archives/candidate-01'
New-Item -ItemType Directory -Path $archiveDir -ErrorAction Stop
Copy-Item -LiteralPath "$intakeDir/manifest.json" -Destination "$archiveDir/manifest.json"
Copy-Item -LiteralPath "$intakeDir/report.md" -Destination "$archiveDir/report.md"
New-Item -ItemType Directory -Path "$archiveDir/evidence" -ErrorAction Stop
```

只在档案副本中补充 `environment`、`launcher` 和 `evidence`。每个补充字段都要有可靠的包元数据导出或设备记录，并写明来源文件、采集方法、时间和验证状态。原始 `intake/manifest.json` 保持自动生成时的内容；没有证据的字段保留 `null`。不要把某台已知手机的系统信息填到来源未知的新APK上。

记录原始APK/ELF的保存位置和SHA-256、收集工具版本及命令。若原路径不再可用，注明新的存储位置，而不是修改哈希来适应另一份文件。二进制可以保存在受控本地证据库，文本档案通过路径和哈希引用；需要复验的人必须能取得相应输入。

后续反汇编证据至少包含：

- 旧版与新版函数的完整范围、原始字节和反汇编，调用者/被调用者及对象池引用。
- 明确标注的地址类型及PT_LOAD换算关系，不能把文件偏移和ELF虚拟地址混写。
- 寄存器、栈、返回值及异步成功/null/异常完成路径的直接依据。
- 每处候选补丁的原指令、预期替换、验证方法、生成脚本和报告。

为新版本建立独立档案，不覆盖官方6144快照、补丁表或已验证输入。档案中的 `artifacts` 可以引用这些资料；标为 `local_ignored` 的项目表示它们不随Git分发，新电脑应从可信存储恢复后再核对。

<a id="sharing"></a>
### 10.3 共享与发布：把读者信息放回现有文档

仓库对外阅读内容集中在README和 `docs/`。候选完成后，不新增独立验收日记、开发周记、调试报告或交接Markdown；也不强制把 `external_resources` 中的报告加入Git。

公开说明只将读者需要的**适用环境、验证范围和已知问题**并入 `docs/HyperOS4 profile编写指南.md` 的对应章节，必要时更新README入口。不要提交原始日志、截图、私人路径、进程编号、具体测试批次或交接流水。发布摘要必须区分已验证和未覆盖场景，不能把本地报告里的部分通过改写成“全面适配”。

机器数据与阅读文档按用途区分：

- 运行时随APK分发的官方Profile位于 `app/src/main/assets/hyperos4/profiles/`，是应用资源；本地候选不会因为完成分析就自动成为官方配置。
- 二进制身份档案如 `external_resources/HyperOS4/builds/6144/manifest.json` 是工具输入，不是使用指南，也不是可导入Profile。需要共享新的机器档案时，明确数据用途、消费工具和准确路径，清理私人存储位置及失效引用。
- APK/ELF原件、本地 `PROFILE_REPORT.md`、工具输出和设备证据保持在受控本地存储，不随公开文档发布。让AI检查拟提交清单，不把整个证据目录一并加入。

需要换电脑或智能体继续时，可以在明确范围内私下移交候选、单份本地报告和所需原始输入。公开仓库不会自动包含这些文件；接收者应确认材料可读后再工作。发送给新AI：

```text
请先读取本候选的 PROFILE_REPORT.md 和最终JSON，
重点查看报告中的证据、验证结果、限制和交接状态。
确认原始输入与记录的SHA-256一致，检查所需文件是否实际可读。
沿用已有证据支持的结论，只补当前缺口；不要把旧计划、未执行命令或其他设备的结果当作完成。
继续本轮已经授权的工作；公开说明只更新现有docs中的适用范围和已知限制。
```

<a id="source-index"></a>
### 10.4 源码与工具索引

这些入口用于AI核对实现，不要求读者先学会Android开发。源码是当前格式和行为的依据，应用或源码升级后应重新检查。

| 入口 | 用途 |
| --- | --- |
| [官方Profile](../app/src/main/assets/hyperos4/profiles/hyperos4-6144.json) | 完整可用示例和 `x-evidence`，核对官方目标，不能向新版本照抄地址。 |
| [ProfileRepository](../app/src/main/java/com/jerrey/monoicon/profile/ProfileRepository.kt)、[CompatibilityProfile](../app/src/main/java/com/jerrey/monoicon/profile/CompatibilityProfile.kt) | 导入、选择、环境匹配和本地目录。 |
| [ProfileSettings](../app/src/main/java/com/jerrey/monoicon/ui/screens/ProfileSettings.kt) | 应用内Profile界面及状态。 |
| [C++解析器](../app/src/main/cpp/hyperos4_profile.cpp)、[运行时](../app/src/main/cpp/hyperos4_profile_runtime.cpp) | 格式约束、二进制验证与运行期检查。 |
| [离线收集和比较](../tools/launcher_inspector.py)、[6144身份档案](../external_resources/HyperOS4/builds/6144/manifest.json) | 输入身份与版本差异；不提供兼容性结论。 |
| [二进制证据验证](../tools/verify_hyperos4_profile.py)、[主机回归](../tools/run_profile_tests.py) | 各自的真实参数和检查范围。 |
| [Palette发布](../app/src/main/java/com/jerrey/monoicon/hook/HyperOs4PalettePublisher.kt)、[刷新与恢复](../app/src/main/java/com/jerrey/monoicon/hook/HyperOs4IconRefresh.kt) | 配置/资源提交、当前PID确认、刷新与禁用自动重启。 |
| [大图标几何](../app/src/main/java/com/jerrey/monoicon/hook/HyperOs4LargeIconGeometry.kt)、[资源归属](../app/src/main/java/com/jerrey/monoicon/hook/HyperOs4LargeIconInputs.kt)、[原生桥接](../app/src/main/cpp/hyperos4_geometry_bridge.cpp) | HyperOS 4大图标的尺寸、归属和ABI契约。 |
| [HyperOS 3大图标](../app/src/main/java/com/jerrey/monoicon/hook/LauncherLargeIconHooks.kt)、[原始图标来源](../app/src/main/java/com/jerrey/monoicon/hook/LauncherLargeIconSource.kt) | Java桌面的大图标路径；不属于原生Profile。 |
| [Lawnicons资源](../app/src/main/assets/lawnicons/)、[资源生成与核对](../tools/render_lawnicons_masks.cjs) | 来源、许可证、生成参数和资源一致性。 |
