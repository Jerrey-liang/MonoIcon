# HyperOS 4 原生桌面适配

HyperOS 4 兼容性由外部 JSON Profile 定义。应用负责导入、环境识别、选择和 Root 发布；LSPosed `native_init` 在无 ART／无 DEX 的桌面进程中加载 Profile，验证实际映射的 ELF，再执行已有的 AArch64 补丁事务。普通桌面的 Java Hook 与 SystemUI 图标功能沿用原有链路。

## 使用

1. 在 LSPosed 启用 MonoIcon，作用域包含 `com.miui.home`、`com.android.systemui`。
2. 为 MonoIcon 授予 Root，打开「配置管理」页面中的 HyperOS 4 Profile 区域。
3. 使用自动匹配，或导入 JSON 后选择与当前环境兼容的 Profile。自动模式仅采用唯一匹配项，有多个候选时手动选择；手动选择不能跳过版本和二进制验证。
4. 等待 Profile、图标和 Palette 发布完成，以及后续图标刷新请求的状态。首次启用、模块升级或切换 Profile 后，使用「重启作用域」。更改造型后如果刷新请求超时，也可用此按钮重试。

不支持的环境会报告原因，例如桌面版本、Android SDK、系统属性、ELF 文件摘要不符，或找不到唯一兼容的 Profile。没有兼容配置时不会将某一版本的补丁尝试安装到其他二进制上。

Profile 格式、编写流程和离线验证见 [自定义 Profile 指南](hyperos4-profile.md)。首个官方文件是 [hyperos4-6144.json](../app/src/main/assets/hyperos4/profiles/hyperos4-6144.json)。

## 开机首次加载

启动所需的已发布 Profile、Palette 和 PNG 放在桌面的设备加密目录 `/data/user_de/<user>/com.miui.home/files/`，首次解锁前也可读取。升级后先打开 MonoIcon 发布一次配置，再重启手机加载新的 `native_init`；这是本次原生模块升级和存储迁移步骤，后续开机使用已发布资源。

桌面子进程加载 `libapp.so` 时，加载回调先执行一轮完整的 `PaletteStep`：校验 Profile／ELF／函数快照，确认 PNG 批次就绪，安装文件 Hook 和补丁，选择图标目录，再释放后台线程并返回加载回调。后台线程继承该轮状态，继续处理后续配置变化。早于 Dart 图像库的普通库回调不抢先启动安装；对已经运行的应用线程发现的图像则仍使用后台安装。首轮和后台共用原有线程暂停、私有映射检查与失败恢复，未修改 spawner 中的应用指令，也没有通过自动重启桌面实现首启生效。

继承预加载图像而没有新 `dlopen` 回调时，LSPosed 的 `pthread_create` Hook 补上启动边界：只有进程名精确为 `com.miui.home`、已脱离 root 身份且两个启动图像均已映射时才触发。主线程创建 Flutter 线程前执行首轮安装；其他线程上的迟到发现使用后台安装。原线程创建参数和返回值直接转交原函数，spawner 与其他应用只透传。引擎按 PID 领取一次所有权，安装线程创建自身时不会递归进入安装。

SystemUI 唤醒的桌面启动 Job 优先重投已发布的有效 DE 批次；它不会仅因 MonoIcon 后台进程刚创建就重新提取壁纸颜色、导出整套图标。界面进入、用户设置变化及独立的壁纸／应用事件仍会正常发布。只有旧 CE 批次、没有可复用批次或开关／Profile 已变化时才重新发布。配置不完整或安全校验失败时仍保留系统行为并记录原因，不绕过校验强行主题化。

## 6144 官方 Profile

| 项目 | 约束 |
| --- | --- |
| Profile ID | `hyperos4-6144`，revision `7` |
| 包名 | `com.miui.home` |
| versionCode | `801026144` |
| versionName | `RELEASE-8.01.02.6144-260818-08251107-R` |
| 架构和形态 | AArch64，原生桌面，无 DEX |
| Android SDK | `37`（Android 17） |
| 系统属性 | `ro.build.version.incremental` 以 `OS4.` 开头 |
| ELF | 两个库的精确 SHA-256、段布局和身份字节 |

设备预检确认：`ro.build.version.incremental` 为实际的 `OS4.0.0.3.XAACNXM`；`ro.mi.os.version.name`／`ro.mi.os.version.incremental` 仍保留 OS3 信息，因此官方 Profile 使用前者匹配系统版本。

原生产链路的 **17 处指令补丁、14 份完整函数快照（9,920 字节）** 保留在该文件中。revision 3～5 增加缓存、圆形动画及控件重载修复；revision 6 增加文件夹预览条目重载与动态预览 fallback，当时合计 **28 处补丁、29 份函数快照（22,928 字节）**。revision 7 增加大图标的原生桥接及网格语义校验，当前为 **28 处补丁、38 份函数快照（28,464 字节）**。此前 revision 2 的无条件 widget 强制重载没有恢复，相同 drawable 的快速返回仍保留。

动态／主题归档／AI 高清／分层资源回退、圆形导出跳过二次造型、标量 Palette 的前景 veneer，以及图标目录前缀、命名规则和 13 组别名均保留。revision 3 的新增补丁只在 PNG 模式生效，标量模式保留该调用的原始指令。

revision 4 在圆形模式下跳过动画位图的上下边缘填充，保留 PNG 的透明圆形轮廓，同时覆盖原生浮动图标和 Flutter 拉伸图层。默认形状、标量模式及关闭模块时保留原始补边指令。完整静态依据见 [圆形返回动画](hyperos4-profile.md#圆形返回动画revision-4)。

revision 5 修正旧 `FutureDrawableHandleProxy` 造成的控件重载提前返回，使广播更新后的新图像进入原有 `State.setState` 路径。逐包模型更新完成后同步通知文件夹预览 controller，并让文件夹预览采用导出的 PNG 图标路径。依据与模式范围见 [图标控件与大文件夹](hyperos4-profile.md#图标控件与大文件夹revision-5)。

revision 6 针对大文件夹预览仍需交互的问题，改用桌面完整的文件夹更新流程，重新构建预览条目并更新其响应式列表。日历、时钟等 MAML 预览直接复用已创建的普通主题图标控件，避免再显示动态原图或旧快照。此处只改动 PNG 模式的预览路径，既有普通图标与圆形动画修复保留。静态调用依据见 [预览条目与动态预览](hyperos4-profile.md#预览条目重载与动态预览revision-6)。

revision 7 新增可选 `icons.largeIconPrefixes` 和 `icons.geometryBridge`，分别声明大图标资源根目录与经过完整快照验证的原生尺寸入口。28 处指令补丁保持不变，新增桥接及网格函数快照。独立线程通过桌面现有 FuncBridge 的 native Bundle ABI 读取尺寸；应用结合 `runtime_largeicon.config` 按实际比例生成 2×1、1×2、2×2 PNG，并将成功导出的目录映射写入同一批次的 `.large-icons`。原生文件 Hook 同时处理目录枚举与 PNG 读取，让原有加载器选择这些 `res` 资源；缺少几何信息、图案、文件或出现路径歧义时保留原资源。详见[大图标与清晰度](large-icons.md)。

大图标 Widget 的缓存不受现有 HD 广播完整清理，因此发布状态会提示使用「重启作用域」加载新的大图标。此要求也适用于后续切换大图标造型；普通图标和文件夹继续使用已有刷新流程。`.large-signature` 记录批次使用的网格与资源配置，避免重启时复用已过期的比例。

2026-10-16 在 Redmi 22041211AC / OS4.0.0.3.XAACNXM 上的验证属于重构前实现，覆盖主用户桌面、文件夹预览、最近任务与 PNG／配置开关链路。本次重构在 2026-10-30 完成一次限定范围的真机链路验证，结果见下文；它只证明配置传输、补丁事务和部分 PNG 读取。后续用户反馈仍需点击图标触发刷新，以及圆形返回动画短暂变方。2026-10-31 的 revision 4 与桌面启动观察器针对这两点修复；测试环境已下线，本轮仅静态检查和编译，尚无新的真机视觉验收结论。

HyperOS 4 的 root 用于发布图标；日志页面本身不依赖 root。日历、时钟等动态主题资源在启用时使用静态 MonoIcon 字形，关闭后恢复系统路径。Lawnicons 日历字形中的数字是资源图案，不表示当前日期。

## Profile 与 Palette 的职责

Profile 只描述已分析二进制的兼容性数据：匹配条件、ELF 身份、函数快照、指令操作、图标查找规则、刷新设置键，以及可选的图标更新广播契约。它不保存用户当前的配色、圆形或 Lawnicons 开关。

Palette 保存启用、前景／背景 ARGB、圆形、Lawnicons 和本次图标生成标识。`MONOICON 3` 还携带所引用 Profile 文件的 SHA-256，用于原子提交绑定；它不复制版本相关补丁表。

```text
MONOICON 3 <enabled> <background> <foreground> <circle> <lawnicons> <g-32位十六进制> <profile-sha256>
```

- 应用读取原始 APK 图标，通过现有 `ThemedIconBuilder` 生成 PNG：有效原生 monochrome → Lawnicons（可关闭）→ AOSP 回退。空白/全不透明 monochrome 占位层不能阻止回退。
- 每次导出创建唯一的 `g-<32 位十六进制>` 目录，包含 PNG、`.ready` 和记录真实应用包名的 `.packages`。Root 以 `su -M` 进入全局挂载环境，复制完整图标批次和按摘要命名的 Profile，设置 owner／权限／SELinux 上下文，最后原子替换 Palette。
- 原生端验证 Profile 内容摘要及图标 `.ready` 后选择该目录，成功应用后写入与 Palette 字节完全一致的 `.applied`。旧确认不能确认新一批同色图标。
- 原生只重定向主题图标目录内的有效 PNG 候选，文件不存在则回退。动态、主题归档、AI 高清和分层图标不能抢先盖过 MonoIcon；圆形 PNG 不再二次执行系统造型，默认形状仍采用桌面的系统遮罩。
- 所有指令替换均校验当前 Profile 中的完整函数快照。更新支持重复线程暂停、关闭恢复、私有代码页保护和原始代码页丢失后的重装。未知指令变化会停止安装。
- SystemUI 监听壁纸、系统配置和应用包变化，通过受 `STATUS_BAR_SERVICE` 权限保护的显式广播请求后台导出 Job。运行时间仍受系统后台任务调度影响。

Profile 内容在一个桌面进程内保持不变。收到引用不同 Profile 的提交时，原生端使用旧 Profile 验证并恢复旧补丁，报告需要重启；新 Profile 在重启后的进程中采用。不会在一次事务中混用两套偏移。

## 原生提交后的缓存刷新

PNG 文件已经被读取，并不意味着桌面的 `IconCacheV2`、原有图标缓存及模型持有的图像都已更新。revision 3 为此增加独立的 `HyperOs4IconRefresh`：Root 异步 worker 等待同一批次提交的完整 Palette 确认，核对原生状态属于当前桌面 PID，并等待该 PID 注册 Profile 指定的动态广播接收器，之后才逐包发送刷新请求。应用返回桌面后即使被系统冻结，Root 子进程仍可继续等待和投递。

桌面自行重启由 SystemUI 启动观察器识别，自动唤醒受权限保护的后台 Job。即使配置没有变化也会向新 PID 重投当前图标批次，Job 等待刷新结束并在失败时重试。观察器从 SystemUI Application 启动注册，不依赖通知或图标点击；启用模块但关闭通知图标主题化时也可工作。

官方 `refresh` 使用 action `com.xiaomi.market.ACTION_HD_ICON_UPDATE` 和字符串 extra `packageName`，目标限定当前用户的 `com.miui.home`。包名来自当前图标批次的 `.packages`，不用 PNG 别名推导。每次投递前继续检查提交与 PID；如果新提交或新进程替代了原任务，旧任务停止。独立 worker 可处理迟到的原生确认，「重启作用域」也会为新桌面 PID 重新安排同一批次的刷新。

对应的静态调用证据如下，地址均为该 Profile 所验证 `libapp.so` 的 ELF 虚拟地址：

| 项目 | 静态证据 |
| --- | --- |
| 回调 | `HdIconUpdateReceiver._onReceive`，`0x1a6e398`，完整快照 384 字节 |
| 替换点 | `0x1a6e424`（函数内 `0x8c`），原指令 `0x97b6f0b3` |
| 原调用 | 无条件的 void 日志调用 `_LoggerImpl.logWithTag @ 0x82a6f0` |
| PNG 模式替换 | `0x97ea0905`，调用静态 `IconCacheV2.clear @ 0x14f0838` |
| 新目标验证 | `IconCacheV2.clear` 完整快照 188 字节 |

这样，回调先清理 V2 缓存，再继续其原有 action／package 校验、旧 `IconCache` 清理、模型重载和 controller 更新路径。两份函数快照、调用点原指令及 ELF 身份都随 Profile 校验；不是从 Kotlin 跨 ABI 直接调用 Dart 函数。静态调用约定和指令证据说明了实现依据，最终缓存重建和画面效果仍需运行验证。

投递完成后写入 `monoicon_refresh.applied`，内容为 `pid|generation|digest`，用于避免对同一进程与批次重复投递。该标记表示刷新请求已发送，不是逐个图标完成重绘的确认。没有 `refresh` 的自定义 Profile 会给出手动重启提示，并保留 `settingsKey` 通知作为刷新提示。

禁用时先等待当前 PID 确认原始指令已恢复（`result=12`）。如果原生状态表明该 PID 曾写入主题补丁，刷新 worker 仅结束精确匹配的 `com.miui.home` 子进程，以移除该进程仍持有的主题图标缓存；没有写入过补丁的 PID 不执行这一步。此流程不清除用户数据、不操作 HYOS spawner，也不强行启动 HOME。

## 安装与恢复保护

- 应用检查真实包版本、原生桌面形态、系统条件与 ELF 摘要；原生端独立检查所加载的实际 ELF 文件、段布局、身份字节和完整函数快照。
- 补丁地址必须落在对应函数快照和可执行段内，`expected` 必须与快照中的原始指令一致。重复键、未知操作、重复地址、越界和路径穿越会被拒绝。
- 写入前暂停其他桌面线程，排除正在执行的受影响函数，再次检查文件映射、私有代码页权限和函数字节。保留代码页保护、指令缓存维护、写后读回及失败回滚。
- 设备拒绝读取 `/proc/self/mem` 时，使用预先创建的私有非阻塞管道，通过内核 `copy_from_user` 安全读取自身内存。短写或读取错误使读取器失效并停止安装；不会改为直接解引用未经保护的目标，也不会调用可能被厂商 seccomp 终止的 `process_vm_readv`。该兼容路径仍执行全部 ELF、映射及快照校验。
- 运行期间仅认可上一事务写入值或 Profile 的原始值；未知指令变化不会被当作普通代码页丢失恢复。
- 文件 Hook 只重定向 Profile 目录内的安全 PNG 文件名。导出不存在或 PNG 无效时沿用系统路径。

## 文件与诊断

官方 Profile 位于 APK 的 `assets/hyperos4/profiles/`；导入文件保存在 MonoIcon 私有目录 `files/hyperos4-profiles/`。桌面侧文件位于当前用户的 `/data/user_de/<user>/com.miui.home/files/`。仅在 DE 提交不存在时兼容读取旧 `/data/user/<user>/com.miui.home/files/`，首次新发布会迁入 DE；DE 已有但损坏／不可读时不会倒退使用旧提交。Root 发布仍设置桌面 UID、私有权限及 SELinux 标签。

该目录中的文件：

| 文件 | 含义 |
| --- | --- |
| `monoicon_profiles/<sha256>.json` | 按内容摘要发布的 Profile |
| `monoicon_profile.active` | 已选 Profile 摘要，供旧标量／PNG Palette 格式显式复用 |
| `monoicon_palette.txt` | 最新 Palette、图标生成标识及 Profile 引用 |
| `monoicon_palette.applied` | 原生已应用的完整配置 |
| `monoicon_refresh.applied` | 已投递刷新请求的 `pid\|generation\|digest` 去重标记 |
| `monoicon_hyos_palette.txt` | Profile ID／摘要、原因、安装／恢复结果、PID、验证和写入计数；`startup-applied` 表示同步首轮安装并确认完成 |
| `monoicon_hyos_icon_files.txt` | 文件 hook 和实际重定向计数 |
| `monoicon_icons/g-*/` | PNG、`.ready` 和真实包名清单 `.packages`；确认后保留当前和上一代 |

Root 用于发布配置和图标；日志页面本身不依赖 Root。`result=13` 表示 Profile 缺失、无效或系统条件不符；`result=14` 表示 Profile 已切换、需要重启。具体原因以状态文件的 `reason`／`phase` 和应用显示为准。重定向计数证明发生了文件读取，不代替截图检查；配置确认也不等于所有屏幕缓存已重绘。

## 验证

2026-10-31 开机首次进入修复：新增设备加密存储、同步首轮事务及预加载线程启动入口，后台唤醒复用现有批次。Debug／Release（含 arm64 原生库）、AndroidTest APK、Kotlin 测试源码编译与 Lint 均通过（0 errors、81 warnings、1 hint）。两个安装包已核对包含新启动代码；官方 r6 Profile 的 SHA-256 仍为 `469116b4f7d56830144c34e4951f51a562c52230f4b455497c8e7c226260e5c5`，此前图标／文件夹／动画指令保持不变。未运行测试程序、连接或操作设备；首次开机视觉效果尚未实测。

2026-10-31 后续 revision 6：针对大文件夹仍需交互刷新、日历／时钟预览仍为原图的反馈，补上原生文件夹条目重载和 MAML 预览 fallback。Debug／Release、Kotlin 测试源码编译、Lint（0 errors、82 warnings、1 hint）及 C++17 严格语法检查通过。离线核对了两个 ELF、29 份完整快照、28 处原始指令，并反汇编确认新跳转目标及 `mov x0, x6; ret`；两个 APK 中的 r6 Profile 与源文件逐字节一致。r5 的 25 处补丁和 22 份快照完整保留。本轮未运行测试程序或连接设备，实际预览效果尚待用户验收。

2026-10-31 后续 revision 5：根据用户“只有被点的图标刷新”和“大文件夹预览未主题化”的反馈，加入代理控件重载、文件夹预览通知和 PNG 路径选择。Debug／Release 构建、Kotlin 测试源码编译、Lint（0 errors、82 warnings、1 hint）与 C++17 严格语法检查通过；两个 APK 内的 Profile 与源文件逐字节一致。离线原始 APK 核对覆盖 22 份完整函数快照、25 处原指令；revision 4 的 22 处补丁全部保留。本轮仍未运行测试程序或连接设备，实际刷新和大文件夹画面尚待验收。

2026-10-31 本轮离线检查：`:app:assembleDebug`、`:app:assembleRelease`、`:app:compileDebugUnitTestKotlin`、`:app:lintDebug` 全部通过。Lint 为 0 errors、82 warnings、1 hint，其中启动钩子的 `Application.attach` 反射产生一条非阻塞的隐藏 API 警告。原生 Profile 解析器及对应测试源码通过 C++17／`-Wall -Wextra -Wpedantic -Werror -fsyntax-only`；6144 原始 APK 的两个 ELF 摘要、18 份完整函数快照、22 处原指令通过离线核对，新增四处补丁只在 `circle` 模式替换为 NOP。按用户要求，未运行单元测试、原生测试程序或设备测试，也未连接或安装到设备；动画视觉效果和启动刷新仍待设备恢复后验收。

标准构建：`:app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:testDebugUnitTest`。

原生 Profile 测试由 `python tools/run_profile_tests.py` 运行。离线二进制验证见自定义指南。这些检查不会安装应用或改变设备。

初次 Profile 重构的历史开发检查：Debug／Release APK、AndroidTest APK 构建通过；当时涉及的 24 项 Kotlin 单元测试与 322 项原生 host 检查通过。6144 的 14 份基线快照与迁移前代码逐字节一致；离线工具正确拒绝了 8565 二进制。真机预检发现的系统属性及内存读取兼容问题修复后，相关 APK 已重新编译。这些记录不覆盖后续 revision 3 缓存刷新改动；新一轮结果应另行记录。

2026-10-30 初次重构的正式真机测试运行 **1 次**：`HyperOs4ProfileDeviceIntegrationTest`，结果 `OK (1 test)`，耗时 3.401 秒。环境为 Redmi 22041211AC、OS4.0.0.3.XAACNXM、SDK 37、6144 桌面、LSPosed 2.2.0-it (7912) / API 102。以下保留当时传输与补丁证据，不作为 revision 3 或最终视觉效果的通过记录。

| 本次证据 | 结果 |
| --- | --- |
| 当时的 Profile | `hyperos4-6144` revision 1，摘要 `bfc31c5411110f974c7210f5140a0df82968d4272d2a2af1362c337a85ffaaf8` |
| 新发布批次 | `g-56d3d264f1b247a69c543b8985ab13eb`，区别于测试前批次 |
| 配置确认 | `MONOICON 3` 与 `.applied` 字节一致，Profile 文件 SHA-256 相符 |
| 当前进程 | 状态 PID 与当前桌面 PID `12571` 一致，进程映射来自当前安装的模块 APK |
| 原生事务 | `result=1`，完整函数校验 9,920 字节，17 处写入及读回验证通过 |
| 文件 Hook | 当前进程观察到 12 次 PNG 重定向 |

验证使用用户当时配置（启用、圆形关闭、Lawnicons 开启），只为装载修复后的模块重启了一次桌面，没有重启整机或切换主题。未验证其他 Profile、切换／关闭恢复、所有动态图标及最终视觉效果。后续“图标仍未刷新”的用户反馈与该测试的有限通过并不矛盾：测试没有覆盖缓存重建和最终显示。revision 3 已改变 Profile 内容和摘要；旧摘要仅用于追溯上述历史证据。当前 `x-evidence.hdIconUpdateRefresh` 记录新增调用的静态依据，视觉验证仍待完成。

复现时只需运行一次 `HyperOs4ProfileDeviceIntegrationTest`，显式传入 `-e hyperos4ProfileDevice true`。先启用 MonoIcon、完成 Profile 发布并重启桌面；该测试强制发布一个新批次，并检查环境匹配、Root 发布文件摘要、当前桌面 PID／模块映射和原生 Palette 确认，不修改主题、配色或其他设置，也不主动重启设备／桌面。

原有 `HyperOs4DeviceIntegrationTest` 是较完整的渲染回归，需要 `-e hyperos4Device true`，会切换圆形、Lawnicons、取色方案和总开关。本轮不运行这套无关回归。

截图需单独确认当前前台是桌面。仪器测试过程中系统或人工切换前台，可能让截图捕获设置页；这种截图不能作为桌面造型证据。

通知渲染由现有 SystemUI Java Hook 提供。真机测试只有在用户明确要求时才运行。
