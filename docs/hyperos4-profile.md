# 自定义 HyperOS 4 Profile

Profile 是 UTF-8 JSON 文件。它将某一已分析桌面二进制的兼容性数据交给 MonoIcon 的通用原生引擎；新增符合当前操作集的版本不需要修改 Kotlin／C++ 或重新编译 APK。引擎不提供脚本解释器、动态库下载或 JNI／Dart 对象桥接；文件中的机器指令仍需作者分析并验证。

完整起点是 [官方 6144 Profile](../app/src/main/assets/hyperos4/profiles/hyperos4-6144.json)。直接复制并修改版本号不能形成新版本适配：ELF 摘要、段布局、所有受影响函数和跳转语义必须一并分析、验证。

官方文件当前为 revision 7：保留已有指令修复，并增加按实际尺寸导出大图标及资源目录重定向。revision 2 的无条件 widget 重载没有恢复；相同 drawable 的快速返回仍保留。历史真机验证范围见 [适配说明](hyperos4.md#验证)，大图标的新增验证范围见[大图标说明](large-icons.md)。

## 导入和选择

在 MonoIcon「配置管理」→「HyperOS 4 Profile」中使用「导入 Profile」选择 JSON。导入时使用与 `native_init` 相同的 C++ 解析器做结构和静态验证，成功后保存在应用私有目录。使用「自动匹配」或手动选择一个已导入的兼容配置；手动选择同样要满足系统、桌面和 ELF 条件。

自动模式只采用唯一匹配项；多个 Profile 同时兼容时，会提示手动选择。「刷新环境」重新读取当前环境与匹配结果。「导出」可保存配置用于备份或编辑，「删除」可移除导入的自定义配置。导入不能覆盖官方 ID；同一自定义 ID 的新文件通过原子写入更新。以官方文件为起点时，先换成自己的 ID 再导入。

导入通过只表示文件结构有效。是否适用于当前设备，由环境匹配和实际 ELF 验证决定；是否正确渲染，还需要对该版本进行运行验证。切换 Profile 后必须重启桌面。自定义 `id` 建议使用独立名称，例如 `my-hyperos4-launcher-build`，不要冒用官方配置的 ID。

## 顶层结构

| 字段 | 类型／含义 |
| --- | --- |
| `schema` | 当前为整数 `1`；不支持的版本直接拒绝 |
| `id` | 1～80 字符，仅小写字母、数字、`-`、`_` |
| `name` | 用户可见名称，非空，最多 256 字节 |
| `revision` | `1..2147483647` 的整数修订号 |
| `match` | 包版本、Android SDK 与系统属性约束 |
| `settingsKey` | 桌面原有的 `Settings.System` 刷新键；小写字母、数字、`_` |
| `refresh` | 可选，原生提交后的逐包图标更新广播契约：`action` 与 `packageExtra` |
| `images` | 需要验证的 ELF 文件 |
| `functions` | 完整函数快照 |
| `patches` | 由快照约束的单条 AArch64 指令替换 |
| `veneer` | 可选，标量 Palette 模式使用的代码模板 |
| `icons` | 图标目录、命名策略和别名 |
| `x-*` | 可放根级来源、分析说明等扩展元数据；引擎不将它们解释为操作 |

JSON 不能包含注释、重复键或结尾多余内容。未知普通字段及操作会报错，避免拼写错误被忽略。地址、偏移和指令可写为非负 JSON 整数或 `"0x1234"` 字符串；字节快照及 SHA-256 必须是连续的小写十六进制字符。JSON 文件上限 2 MiB。

Schema 1 的 ELF 虚拟地址及段末端限制在 `0xffffffff` 内，单个 ELF 文件不超过 1 GiB；这些是解析和资源预算上限，不是用来匹配具体桌面版本的常量。添加新的操作或扩大执行器能力时应升级 schema，旧引擎会明确拒绝未知 schema。

## 环境匹配

```json
{
  "package": "com.miui.home",
  "versionCodes": ["801026144"],
  "versionNames": ["RELEASE-8.01.02.6144-260818-08251107-R"],
  "sdkMin": 37,
  "sdkMax": 37,
  "properties": [
    {"name": "ro.build.version.incremental", "prefixes": ["OS4."]}
  ]
}
```

这是 `match` 的完整示例。`versionCodes` 使用标准十进制字符串；code 与 name 必须分别命中各自列表。SDK 范围包含两端。每一个 `properties` 条件都必须满足，同一属性的多个前缀则满足任意一个即可；前缀是字面值，不是正则表达式或通配符。只允许 `ro.*` 只读系统属性，缺失属性不会被视为匹配。

当前驱动限定 `com.miui.home` 的 AArch64 原生桌面。版本名称只用于环境筛选，不能代替 ELF 验证。应用读取包管理器信息，原生端则依据进程身份、系统属性及实际映射文件的精确 SHA-256 独立确认目标。Profile 不能强制绕过不匹配条件。

## ELF 与完整函数快照

每个 `images` 项包含唯一 `id`、库文件名 `library`、字节数 `size`、`sha256`、按 ELF 顺序列出的完整 `PT_LOAD` 段 `segments`，以及一组 `identity` 快照。

每个段使用 `offset`（文件偏移）、`address`（ELF 虚拟地址）、`fileSize`、`memorySize`、`flags` 和 `alignment`。`flags` 使用 ELF 的 `PF_R=4`、`PF_W=2`、`PF_X=1`；函数必须位于 RX 段，禁止将可写可执行段当作补丁目标。必须区分文件偏移和虚拟地址，例如 6144 的 `libapp_launcher.so` RX 段二者相差 `0x4000`。

`identity` 每项为 `{"address":"0x...","bytes":"..."}`，通常放 GNU Build ID note 或 Android note。没有 GNU Build ID 的 ELF 仍必须提供完整文件 SHA-256，不能仅凭 SDK／NDK 字符串识别二进制。引擎还验证完整 ELF 头和段布局。

`functions` 每项的字段：

| 字段 | 含义 |
| --- | --- |
| `id` | 全局唯一函数标识，供补丁引用 |
| `image` | 对应 `images.id` |
| `address` | 函数开始的 ELF 虚拟地址，4 字节对齐 |
| `bytes` | 完整函数的原始机器码，按文件中的字节顺序编码；长度为 4 的倍数 |

所有被修改的函数必须提供完整快照，不能只截取几条方便匹配的指令。引擎能检查范围和已有快照的一致性，无法自动证明用户声明的边界确实是完整函数；边界以及寄存器／控制流语义是 Profile 作者的分析责任。额外的只验证不修改函数也可以加入，例如官方 Profile 的 launcher observer 快照。

当前 schema 支持 1～8 个映像、1～256 个函数；单个函数快照不超过 256 KiB、总函数快照不超过 512 KiB。函数范围不能重叠，每个映像至少有一个函数快照。

## 指令替换与模式

每项补丁引用 `function`，并用函数内 `offset` 定位 4 字节指令。`expected` 是原始指令的 32 位数值，必须等于快照按 AArch64 小端序读出的值。例如原始字节 `04 02 91 9a` 对应 `"0x9a910204"`。

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

`palette` 用于旧标量颜色模式，`icons` 用于导出 PNG 的默认形状模式；可选 `circle` 用于圆形 PNG，省略时沿用 `icons`。模块关闭时，所有补丁恢复为 `expected` 对应的原始指令。三个模式都不隐含 6144 地址或补丁序号。

替换值的语法：

| 值 | 操作 |
| --- | --- |
| `"original"` | 保持原始指令 |
| `{"word":"0xd503201f"}` | 指定 32 位指令，例如 NOP |
| `{"value":"background-low","register":1}` | `MOVZ W1` 写入背景 ARGB 低 16 位 |
| `{"value":"background-high","register":1}` | `MOVK W1, LSL #16` 写入背景高 16 位 |
| `{"value":"foreground-low","register":2}` | 前景 ARGB 低 16 位 |
| `{"value":"foreground-high","register":2}` | 前景 ARGB 高 16 位 |
| `"veneer"` | 仅用于 `palette`，跳转到下述原生生成的邻近代码页 |

`register` 范围是 `0..30`，这些立即数操作写 W 寄存器，并零扩展到对应 X 寄存器。补丁最多 256 处，不允许重复地址。原始指令和快照验证不能证明任意替换指令的逻辑正确；跳转目标、Dart ABI、寄存器保存、栈布局和异步完成语义均需要作者根据目标版本确认。

## 标量 veneer

当前 schema 支持一个标量 veneer，必须恰好有一个 `palette: "veneer"` 引用。`veneer.words` 是 2～64 个替换值的数组：第一项必须是重放该位置原始指令的 `word`，最后一项必须是 `"return"`，其余可用上述 `word` 或颜色立即数操作。

引擎分配可达的私有代码页，生成末尾返回补丁后下一条指令的 AArch64 `B`，检查双向跳转范围，写完后设为 RX。官方模板保留既有的 ColorFilter 身份判断、对象池加载指令和前景 ARGB 替换；对象池偏移及其他版本相关指令全部在 JSON 中。若新版本需要当前 schema 不具备的语义，应扩展并验证引擎能力，再使用新的 schema，而不是假定未知字段已经生效。

## 图标资源规则

`icons.prefixes` 是允许重定向的绝对目录前缀，必须以 `/` 结束，不得包含 `..`、重复斜杠或反斜杠。它们应来自目标桌面的图标资源查找路径。只有这些目录中的有效、安全 PNG 候选文件才会被替换；不是任意文件 IO 规则。

可选 `icons.largeIconPrefixes` 声明大图标资源根目录，使用相同的路径校验。省略时保留旧 Profile 行为。启用此字段还要求目标桌面符合 6144 已确认的契约：根目录下 `<user>/apps/*/runtime_largeicon.config` 记录 `packageName`、`path`、`isPairApp`；资源加载器枚举 `<path>/<1x2|2x1|2x2>` 并读取 `res/drawable[-density]/<package>.png`；原生尺寸方法、Bundle 键和手机布局公式与[大图标说明](large-icons.md)一致。不能只凭目录名称相似就在其他版本或平板布局中启用。

`icons.geometryBridge` 是已验证函数的 ID，用于原生 `FuncBridge.invoke_method` 调用；要求同时声明大图标根目录，禁止引用 Dart `libapp.so` 入口或同时修改该函数的指令。6144 使用 `libapp_launcher.so @ 0x64ae9c`：借用 UTF-8 名称和长度、接收可空原生 Bundle、返回有所有权的 Bundle。`Bundle_get_f64/f32` 的结果布局与 `Bundle_drop` 均经原始调用点核对。调用只在独立线程中执行，配置安装和启动同步阶段不等待 Dart 响应；缺少库、符号、注册项或有效结果时保留原图。

模块只为成功导出的图案发布 `.large-icons` 目录映射，每个映射限定尺寸目录、批次内目标目录和一个 PNG 名称。原生端验证路径、文件及数量上限，再重定向目录枚举与文件读取。配对应用、路径所有者不明确及未生成有效 PNG 的条目继续使用系统资源。

`icons.names` 是文件名策略数组：

| 策略 | 导出的候选名称 |
| --- | --- |
| `qualified` | class 以 package 开头时为 `<class>.png`，否则为 `<package>#<class>.png` |
| `class` | `<class>.png` |
| `component` | `<package>#<class>.png` |
| `package` | `<package>.png` 包级回退 |

`icons.aliases` 为候选名到别名的映射，官方 Profile 含 13 项旧名称兼容规则。别名仅用于复制导出的 PNG，不会解析为路径、执行代码或改变应用身份。文件名不超过 255 字节，只允许字母、数字、`.`、`_`、`$`、`#`、`-`，以 `.png` 结尾且不能包含 `..`。

每个导出批次还包含 `.packages`：按实际桌面 Activity 的应用包名去重、排序，一行一个包名。它包含本次枚举到但可能跳过 PNG 导出的应用，供桌面清除该包的旧缓存后按正常路径回退。该清单与图标别名分离；例如名为 `com.android.gallery.png` 的兼容别名不能直接作为刷新时的真实应用身份。

## 可选的缓存刷新契约

单纯重新读取 PNG 或发送设置变更通知，可能仍让已有 widget／模型使用旧的图像缓存。`refresh` 允许 Profile 声明目标版本已经实现的逐包更新广播。例如官方 6144：

```json
"refresh": {
  "action": "com.xiaomi.market.ACTION_HD_ICON_UPDATE",
  "packageExtra": "packageName"
}
```

| 字段 | 约束与含义 |
| --- | --- |
| `action` | 1～256 字节，字母、数字、`_`、`.`，至少含一个 `.`；目标桌面既有动态接收器的 action |
| `packageExtra` | 1～80 字节，仅字母、数字、`_`；接收器读取真实包名的字符串 extra 名称 |

这两个字段都必须提供。它们不是任意命令模板：worker 固定向当前用户的 `com.miui.home` 发送广播，将 `.packages` 中经过包名检查的值放入指定字符串 extra。不能在其中写 shell 参数、组件路径或替代包名的 PNG 文件名。

`HyperOs4IconRefresh` 在应用内独立调度 Root 异步 worker。worker 先等待同一 Palette 完整字节的原生确认，检查原生状态的 PID 为当前桌面 PID，再确认该 PID 已注册指定 action 的动态接收器；启用时只有满足这些条件才逐包投递。等待和每次投递前都核对提交与 PID，避免迟到的旧确认触发新进程或新批次的刷新。Root worker 的等待可以在应用回到后台后继续；「重启作用域」会为新 PID 再安排当前批次。

SystemUI 在 Application 启动时注册 `HyperOs4LauncherMonitor`，在独立线程每两秒检查同 UID 的桌面进程。首次看到桌面或 PID 改变时，通过受 `STATUS_BAR_SERVICE` 权限保护的接收器启动 Job，不依赖点击图标、通知渲染或 MonoIcon Activity 存活。Job 在配置未变化时重投当前批次，并等待 Root worker 结束；超时／失败交给 JobScheduler 重试。桌面启动 Job 和壁纸／应用变更 Job 使用不同 ID，避免覆盖待处理的导出。该能力使用项目原有的 SystemUI 作用域，升级后需重启作用域以加载新观察器。

成功投递后，`monoicon_refresh.applied` 记录 `pid|generation|digest`。相同组合不会重复发送，换 PID 则需要新的投递。标记表示请求已发送，不能证明每个包的回调或画面重绘均已完成。若没有 `refresh`，引擎仍可应用兼容补丁和图标，但应用仅发送 `settingsKey` 提示并要求手动重启以刷新已有缓存。

声明一个广播 action 不能自动解决缓存依赖。Profile 作者需要确认接收器什么时候注册、读取哪个 extra、更新哪些缓存和模型，并为必要的原生调用替换提供完整快照。官方 revision 3 的依据为：

| 项目 | ELF 虚拟地址／内容 |
| --- | --- |
| 接收器回调 | `HdIconUpdateReceiver._onReceive @ 0x1a6e398`，384 字节 |
| 调用位置 | `0x1a6e424`，即函数内偏移 `0x8c` |
| 原始指令与目标 | `0x97b6f0b3`，无条件 void 日志调用 `_LoggerImpl.logWithTag @ 0x82a6f0` |
| PNG 模式替换 | `0x97ea0905`，`BL IconCacheV2.clear @ 0x14f0838` |
| 新目标快照 | `IconCacheV2.clear`，188 字节 |
| 其他模式 | `palette: "original"`；关闭后恢复原始指令 |

该替换在桌面原有回调内先调用静态 `IconCacheV2.clear`，随后继续原 action／package 校验、旧 `IconCache` 清理、模型重载和 controller 更新。代码保留在原生／Dart 调用上下文中，不由 Kotlin 跨 ABI 直接调用。Profile 的 `x-evidence.hdIconUpdateRefresh` 记录两个函数的摘要和调用约定依据；运行时两份完整快照参与校验。此处地址只适用于精确匹配的 6144 ELF，其他版本必须重新分析。

## 图标控件与大文件夹（revision 5）

用户反馈 revision 4 仍需点击，而且只有被点的图标刷新。静态分析进一步发现：`ShortcutInfoModel.iconDrawableHandle @ 0x17d0980` 在初次异步加载时创建 `FutureDrawableHandleProxy`（class `0x72c`）；`_IconDrawableWidgetState.didUpdateWidget @ 0x12c83ac` 在旧 drawable 属于该类型时直接返回。HD 广播虽然清理缓存、等待模型重载并通知普通图标 controller，已有控件仍可能持有旧图像。

revision 5 只在 PNG 模式将 `0x12c847c` 的代理类型提前返回改为 NOP，让**已经变化**的 drawable 继续执行原有 dispose、evict、`_renderAndCache` 与 `State.setState`。此前 `0x12c846c` 的相同 drawable 判断保持原样，避免正常重建或动画每帧都重新加载。

HD 更新原本只通知 `shortcut_icon_controller_icon_id`，而文件夹预览使用 `folder_preview_container_id`。`HdIconUpdateReceiver._refreshPackageIcons` 在完成该包的模型重载后，将末尾 `0x1a6e994` 的 void 日志调用替换为 `BL MonochromeController._refreshFolderPreviewIcons @ 0x194aa98`，通知现有文件夹预览重建。目标方法的完整快照证明它不使用传入的 receiver，从全局模型查找文件夹 controller；原日志返回值也不被使用，调用仍在原 Dart 上下文执行。

同时，`FolderPreviewIcon._buildDefaultIconWidget` 在 PNG 模式由 `0x13d23b8` 跳至 `0x13d24b0`，采用普通 drawable 图标路径，避免文件夹残留的系统 monochrome 标志将导出图标再次送入内置单色控件。三个改动均保留标量模式原指令，模块关闭时由原有事务恢复。新增四份完整快照及调用依据见 Profile 的 `x-evidence.widgetReload`、`x-evidence.folderPreviewReload`。

## 预览条目重载与动态预览（revision 6）

用户反馈 revision 5 的大文件夹预览仍需交互，且日历、时钟的预览与已主题化的应用图标不一致。进一步反汇编确认两个缺口：外层 `folder_preview_container_id` 更新没有重建已缓存的预览条目；`_buildDefaultIconWidget` 生成普通控件后，MAML 分派仍会选用动态控件或 MAML 快照。

PNG 模式在 `MonochromeController._refreshFolderPreviewIcons + 0x198`（`0x194ac30`）将 `GetxController.update` 改为 `FolderIconGetxController.updateFolderUI @ 0x93cad8`。闭合文件夹依次刷新排序、`refreshPreviewIcons`、`loadItemIcons`、`runLoadAction`；后者从 adapter 创建新的预览条目，并在 `0x90ffa8` 调用 `ListExtension.assignAll` 更新 RxList。已打开的文件夹则使用原有 `refreshGridView`。调用前 `x1` 已是对应文件夹 controller，原 ids 参数不被新目标读取，返回后循环重新加载迭代器。

`FolderPreviewIcon._buildMamlIcon @ 0x13d5970` 的入口在 PNG 模式替换为 `mov x0, x6; ret`。这里的 `x6` 是调用者已创建好的普通预览控件：`_buildIconItem` 在 `0x13d1444` 构建它、在 `0x13d144c` 保存到闭包 context 的 `0x2b` 字段；分派闭包在 `0x13d4590`／`0x13d4594` 将它装入 `x6`，于 `0x13d45b0` 调用 MAML 入口。直接返回该对象保留预览尺寸、key、模型绑定和主题化 drawable，同时跳过旧 MAML 快照和动态渲染分支；无需创建 Dart 对象或跨 ABI 调用。

新目标、重载链及 fallback 调用者均有完整快照。匿名分派闭包的范围为 `0x13d4534..0x13d4668`，覆盖其栈帧入口与全部返回分支，并结束于下一个已命名函数 `_buildNormalIcon`。新增三处补丁仅在默认 PNG／圆形 PNG 模式生效；标量模式和禁用时恢复原始调用与 MAML 函数序言。非 MAML 的大图标分支保持原样。证据见 `x-evidence.folderEntryReload` 和 `x-evidence.folderMamlFallback`。

## 圆形返回动画（revision 4）

静态反汇编确认，动画位图有两条补边路径：`IconCaptureUtil.fromImageWithRoundCornerFill @ 0x1927940` 服务于原生浮动图层；`BitmapUtils.processBitmap @ 0x19ccc28` 服务于 Flutter 的拉伸图层。它们调用 `fillTopEdge`／`fillBottomEdge`，沿像素列将不透明颜色复制到透明边缘。圆形 PNG 经此处理后会丢失透明轮廓，再被系统蒙版裁成圆角矩形。

圆形模式在 `0x1927acc`、`0x1927ae0`、`0x19cce14`、`0x19cce28` 将四个补边调用替换为 NOP，保留源图像 alpha。原有转换、异步帧、内存分配位置、图像释放与尺寸处理均沿用；默认 PNG、标量模式和模块关闭时恢复原始调用。两份完整函数快照参与事务校验，摘要及调用者记录在 `x-evidence.circleAnimationAlpha`。这里只证明静态调用关系和指令匹配，不代表动画已在设备上验收。

禁用不使用仍被改写的刷新回调：worker 先等待当前 PID 确认原始指令恢复（`result=12`），如果该 PID 曾写入主题补丁，则仅结束精确匹配的桌面子进程，清除仍在内存中的主题图标缓存。它不会删除用户数据、操作 spawner 或主动启动 HOME；未写入补丁的 PID 不会被结束。

## 编写和离线核对

1. 从允许分析的桌面 APK 提取 AArch64 ELF，记录版本、系统属性、SHA-256 和完整 `PT_LOAD`。
2. 确认当前原生执行模型适用，分析实际图标、配色、资源回退及刷新函数，包括已有缓存与模型如何重建。以 6144 为语义参考，不沿用未经确认的地址。
3. 创建新 `id`，填写匹配条件、完整函数快照、每处原始指令及替换模式；确认跳转和寄存器语义。存在合适接收器时再声明 `refresh`，并提供新增调用所需的原调用者／目标函数证据。
4. 使用只读离线工具核对 Profile 中所有二进制证据：

```text
python tools/verify_hyperos4_profile.py my-profile.json --apk MiuiHome.apk
python tools/verify_hyperos4_profile.py my-profile.json --lib-dir extracted/lib/arm64-v8a
```

工具检查 ELF 文件 SHA-256、AArch64 ELF 头、全部 `PT_LOAD`、身份字节、完整函数快照和 `expected` 指令。它不会自动发现 Hook 点、生成新版本补丁或修改 Profile，也不能代替应用内的原生 schema 验证。离线工具可读取压缩 APK 条目；原生安装需要实际映射的未压缩 APK 条目或系统提取后的库文件。

5. 导入 MonoIcon，检查当前环境匹配结果，选择后等待发布，再重启桌面。受控运行验证应覆盖启用、关闭、重启、换色、圆形、Lawnicons、动态资源回退和失败恢复；单独检查缓存重建后的实际图标。Palette 确认、PNG 重定向计数或刷新投递标记都不能代替最终画面验证。保留来源、验证环境和结果说明，可放在 `x-evidence` 中。

## 发布与升级

应用内自定义文件保存在 `files/hyperos4-profiles/<id>.json`。发布到桌面时，文件名变为完整内容 SHA-256：`monoicon_profiles/<sha256>.json`；`MONOICON 3` 的 Palette 引用该摘要，最后一次 Palette 原子重命名提交两者的关联。更改空白也会改变摘要，因此应把每次发布视作不可变文件。

桌面侧发布根目录为 `/data/user_de/<user>/com.miui.home/files/`，供开机首次解锁前的原生启动读取。Profile、PNG 和 `.ready` 全部准备完成后，Palette 的原子重命名才切换到新批次。原生加载回调的首轮校验／安装与后台更新共用事务状态；应用后台启动只复用已有批次，避免导出时序覆盖开机已加载的配置。旧 CE 目录仅作为迁移期回退，DE 提交存在后不再回退。此改动属于执行引擎和传输，不需要改变 Profile schema 或官方 r6 的指令表。

同步首轮仅用于补丁全部落在 Dart 图像 `libapp.so` 的 Profile（官方 r6 符合）。若自定义 Profile 修改原生启动调用者所在的其他库，则保留原有后台安装路径，避免在当前 native 启动栈仍使用该函数时修改它。失败恢复与运行期未知指令检查不变。

只更改 Palette 颜色或图标选项不会改变 Profile。切换或更新 Profile 会要求桌面重启，已安装的旧事务只可使用旧快照恢复。旧 `MONOICON 1` 标量格式和 `MONOICON 2` PNG 格式必须有 `monoicon_profile.active` 明确指向已发布 Profile；没有这个指针时不存在内置 6144 补丁回退。
