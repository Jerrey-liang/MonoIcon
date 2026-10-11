# MonoIcon 2.0.1

**versionCode 2** · 上一个版本 / Previous release: 2.0.0

---

## 中文

### 新增

- **日志导出** — 日志页新增「导出日志」，通过系统文件选择器把完整日志保存为带时间戳的 `.txt`。使用 Android 官方存储访问框架，不需要任何存储权限。

### 改进

- **查看日志不再需要 root** — 日志页改为显示 MonoIcon 自身记录的日志，不再依赖 root 去读取系统日志。离开页面、旋转屏幕、Activity 重建都不会再清空日志；退出应用前的内容会持久保留。
- **应用抽屉滑动更流畅** — 同一个图标在重复绑定时不再重新生成，图标生成结果按请求缓存；同时消除了绑定热路径上的重复反射查找与主线程上重复的配色计算。
- **启动日志明确版本** — 模块加载时打印的版本号已同步为 2.0.1。

### 修复

- **缓存失效不完整** — 应用更新/卸载后，部分图标缓存此前不会被清除（缓存键解析错误），现已修正。
- **图标替换结果一致性** — 不再把渲染器内部的共享遮罩位图交给桌面进程，避免潜在的显示异常。

### 兼容性

> **HyperOS 4 尚未完全支持。**
>
> 本版本已加入针对 HyperOS 4 桌面的适配代码，但**尚不完整**：HyperOS 4 的桌面与旧版差异较大，图标主题化可能不生效或表现异常。HyperOS 4 的完整支持仍在开发中，请等待后续版本。
>
> MonoIcon 目前面向 **HyperOS 3 及更早版本**。

- 运行环境不变：Android 15（API 35）及以上、LSPosed（libxposed API 101+）
- root 仍为可选，且**仅「重启作用域」需要**；不授予时图标主题化与日志功能均正常工作

### 构建说明

本版本引入了 native 构建，自行编译需要额外安装 **NDK 28.2.13676358** 与 **CMake 3.22.1**，否则 `assembleRelease` 会失败。

---

## English

### Added

- **Log export** — the logs page gains an "Export logs" action that saves the full log as a timestamped `.txt` through the system file picker. It uses the Android Storage Access Framework, so it needs no storage permission.

### Improved

- **Viewing logs no longer needs root** — the logs page now shows the log MonoIcon records for itself instead of reading the system log through root. Leaving the page, rotating the screen or recreating the Activity no longer clears it, and entries are persisted so they survive the app being closed.
- **Smoother app-drawer scrolling** — an icon is no longer regenerated when it is rebound; generated results are cached per request, and the bind path no longer repeats reflection lookups or recomputes the colour palette on the main thread.
- **Version shown at startup** — the version the module logs on load is now 2.0.1.

### Fixed

- **Incomplete cache invalidation** — after an app was updated or uninstalled, some icon cache entries were not cleared (the cache key was being parsed incorrectly). This is corrected.
- **Icon replacement consistency** — the renderer's internal shared mask bitmap is no longer handed to the launcher process, avoiding a possible display glitch.

### Compatibility

> **HyperOS 4 is not fully supported yet.**
>
> This release adds code targeting the HyperOS 4 launcher, but it is **not complete**: HyperOS 4 differs substantially from earlier versions, and icon theming may not take effect or may behave incorrectly there. Full HyperOS 4 support is still in development; please wait for a later release.
>
> MonoIcon currently targets **HyperOS 3 and earlier**.

- Runtime requirements are unchanged: Android 15 (API 35) or newer, LSPosed (libxposed API 101+)
- Root remains optional and is now needed **only for "Restart scope"**; icon theming and the logs page work without it

### Build note

This release introduces a native build. Compiling it yourself additionally requires **NDK 28.2.13676358** and **CMake 3.22.1**; without them `assembleRelease` will fail.
