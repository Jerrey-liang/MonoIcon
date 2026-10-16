# HyperOS 4 原生桌面适配

适配对象：`RELEASE-8.01.02.6144-260818-08251107-R`，versionCode `801026144`，arm64，无 DEX。2026-10-16 在 Redmi 22041211AC / OS4.0.0.3.XAACNXM 上验证。其他二进制版本不安装这些补丁。

## 使用

1. 在 LSPosed 启用 MonoIcon，作用域包含 `com.miui.home`、`com.android.systemui`。
2. 为 MonoIcon 授予 root，打开一次设置页面，使图标和配置发布完成。
3. 安装新模块后重启作用域。重启按钮会等待当前图标导出落盘。更改造型后可用此按钮刷新桌面缓存。

HyperOS 4 的 root 用于发布图标；日志页面本身不依赖 root。日历、时钟等动态主题资源在启用时使用静态 MonoIcon 字形，关闭后恢复系统路径。Lawnicons 日历字形中的数字是资源图案，不表示当前日期。

## 图标与配置链路

- 应用读取原始 APK 图标，通过现有 `ThemedIconBuilder` 生成 PNG：有效原生 monochrome → Lawnicons（可关闭）→ AOSP 回退。空白/全不透明 monochrome 占位层不能阻止回退。
- 每次导出创建唯一的 `g-<32 位十六进制>` 目录。root 以 `su -M` 进入全局挂载环境，复制完整 PNG、设置 owner/权限/SELinux 上下文，最后原子替换配置文件。
- `MONOICON 2` 同时包含启用、前景/背景、圆形、Lawnicons 和图标版本。原生端验证 `.ready` 后选择该目录，成功应用后写入字节完全一致的 `.applied`。旧确认不能确认新一批同色图标。
- 原生只重定向主题图标目录内的有效 PNG 候选，文件不存在则回退。动态、主题归档、AI 高清和分层图标不能抢先盖过 MonoIcon；圆形 PNG 不再二次执行系统造型，默认形状仍采用桌面的系统遮罩。
- 所有指令替换均校验 6144 函数快照。更新支持重复线程暂停、关闭恢复、私有代码页保护和原始代码页丢失后的重装。未知指令变化会停止安装。
- SystemUI 监听壁纸、系统配置和应用包变化，通过受 `STATUS_BAR_SERVICE` 权限保护的显式广播请求后台导出 Job。运行时间仍受系统后台任务调度影响。

文件位于当前用户的 `/data/user/<user>/com.miui.home/files/`：

| 文件 | 含义 |
| --- | --- |
| `monoicon_palette.txt` | 最新提交的配置及图标版本 |
| `monoicon_palette.applied` | 原生已应用的完整配置 |
| `monoicon_hyos_palette.txt` | 安装/恢复结果、PID、校验和写入计数 |
| `monoicon_hyos_icon_files.txt` | 文件 hook 和实际重定向计数 |
| `monoicon_icons/g-*/` | PNG 与 `.ready`；确认后保留当前和上一代 |

重定向计数证明发生了文件读取，不代替截图检查；配置确认也不等于所有屏幕缓存已重绘。

## 验证

标准构建：`:app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:testDebugUnitTest`。

真机联测 `HyperOs4DeviceIntegrationTest` 必须显式传入 `-e hyperos4Device true`。它改变圆形、Lawnicons、取色方案和总开关，校验应用自身的 root 发布、原生确认、PNG alpha、颜色变化与关闭恢复，最后恢复原设置。其余渲染、通知及配置桥接测试不需要此参数。

截图需单独确认当前前台是桌面。仪器测试过程中系统或人工切换前台，可能让截图捕获设置页；这种截图不能作为桌面造型证据。

已验证的场景为当前主用户桌面、文件夹预览、最近任务以及 PNG/配置开关链路。通知渲染由现有 SystemUI Java hook 提供。工作资料、应用分身及其他桌面版本需要额外验证。
