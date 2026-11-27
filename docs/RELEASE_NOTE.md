# MonoIcon 发布说明 / Release notes

## 未发布 / Unreleased

- **HyperOS 4 配置导入**。在「配置管理」中，「导入 Profile」位于「运行环境」卡片下方、桌面和 SystemUI 的运行报告之前。应用识别运行环境后就会显示导入入口，诊断继续在后台加载。
- **HyperOS 4 profile import** now appears in **Configurations**, directly below **Runtime environment** and above the Launcher and SystemUI reports. The action appears as soon as MonoIcon detects the environment, while diagnostics load in the background.

## 2.4.0

versionCode 6 · [GitHub Release](https://github.com/Jerrey-liang/MonoIcon/releases/tag/v2.4.0)

### 中文

- **壁纸与配色**。图标、应用默认的壁纸主题和预览共用实际桌面壁纸。可选 Material 2025（默认）或 Material 3，两者都支持九种配色和系统明暗模式。壁纸读取失败时，预览会提示并使用上次的图片或默认背景。
- **图标来源**。新安装默认按应用原生主题化图标 → Lawnicons → MonoIcon 生成的顺序选择。还可选跳过 Lawnicons、优先使用 Lawnicons，或始终由 MonoIcon 生成。
- **形状、大小与白边**。外框可从系统圆角矩形调到圆形，大小可调至系统默认值。圆形设置下，长方形大图标呈胶囊形。生成图标的内层图案可单独调圆角或跟随外框，也可调整白边，缩放时保留完整图案。新版生成方式改善了暗色模式下的白色细节。
- **单应用定制与预览**。可按名称或包名搜索和筛选桌面应用，逐项跟随默认或自定义，也可一键恢复默认。图标样式页和调节页提供带壁纸的实时预览，单应用页面预览所选应用。
- **KernelSU 风格界面**。「启用模块」移到主页。更新了搜索框与底栏图标，Monet 配色、模糊、悬浮底栏、液态玻璃与更新检查始终开启。
- **运行报告**。「配置管理」可查看桌面和 SystemUI 的实际运行状态。可查看图标处理功能是否已启动，界面会标记历史记录与未知状态。

#### 升级与使用

从 2.3.0 或更早版本升级后，先打开一次 MonoIcon，等设置迁移和壁纸读取完成，再重启手机。旧版的圆形设置和 Lawnicons 选择会保留。

1. 需要 Root 和 LSPosed。在 LSPosed 中勾选「系统桌面」和「系统界面」，授予 MonoIcon Root 权限，并在主页确认「启用模块」已开启。
2. 旧版 HyperOS 4 配置未注明适配系统时，需要在「配置管理」手动确认。切换配置或调整大图标造型后，重启桌面。升级后重启作用域，再刷新运行报告。
3. 安全模式始终开启。如果触发，在主页重新开启「启用模块」，再通过电源菜单重启作用域。恢复需要 Root 权限。

#### 兼容性

内置 HyperOS 4 配置的验证环境为 Redmi K50（22041211AC）、HyperOS 4 OS4.0.0.3.XAACNXM、Android 17、系统桌面 RELEASE-8.01.02.6144-260818-08251107-R。其他系统或桌面版本、平板及折叠屏需单独验证。日历和时钟使用静态图案，动态壁纸取色取决于系统能否捕获可见帧。文件夹、最近任务、通知入口和返回动画的验证范围有限。[配置指南](<HyperOS4 profile编写指南.md>)

### English

- **Wallpaper and colours.** Icons, the app's default wallpaper theme and previews share the actual home wallpaper. Material 2025 (default) and Material 3 both offer nine colour styles and follow system light and dark modes. If the wallpaper cannot be read, the preview shows a status and uses the last image or the default background.
- **Icon sources.** New installs try native themed artwork, then Lawnicons, then MonoIcon generation. You can also skip Lawnicons, prioritise it, or always generate artwork with MonoIcon.
- **Shape, size and border.** Adjust the outer shape from the system rounded rectangle to a circle and the size up to the system default. Rectangular large icons become capsules at the circular setting. Generated artwork can have its own roundness or follow the outer shape. Border controls resize the artwork while keeping it fully visible, and generation preserves white details better in dark mode.
- **Per-app controls and previews.** Find launchable apps by name or package and combine filters. Each setting can follow the default or be customised, and you can reset all settings for an app. Style and adjustment pages show live previews over the wallpaper. Per-app pages preview the selected app.
- **KernelSU-style interface.** **Enable module** moves to the home page. Search and navigation icons are updated. Monet colours, blur, the floating bottom bar, liquid glass and update checks are always on.
- **Runtime reports.** **Configurations** shows reports from Launcher and SystemUI. Check whether individual icon features have started. The app labels historical reports and unknown states.

#### Upgrading and use

After upgrading from 2.3.0 or earlier, open MonoIcon once, wait for settings migration and wallpaper loading, then reboot the phone. Previous circle settings and Lawnicons selections carry over.

1. Root and LSPosed are required. Select the Launcher and SystemUI scopes, grant MonoIcon root access and check **Enable module** on the home page.
2. If a legacy HyperOS 4 profile does not list a supported system, confirm it manually under **Configurations**. Restart the launcher after switching Profiles or changing large-icon shapes. After an upgrade, restart the scopes before refreshing runtime reports.
3. Startup protection is always on. If it trips, turn **Enable module** back on and use the power menu to restart the scopes. Recovery requires root access.

#### Compatibility

The bundled HyperOS 4 Profile was verified on Redmi K50 (22041211AC), HyperOS 4 OS4.0.0.3.XAACNXM, Android 17 and launcher RELEASE-8.01.02.6144-260818-08251107-R. Other system or launcher builds, tablets and foldables need separate validation. Calendar and Clock use static artwork. Live wallpaper colours depend on the system allowing a visible frame to be captured. Folders, recents, notification entry points and return animations have limited visual coverage. [Configuration guide](<HyperOS4 profile编写指南.md>)

## 项目许可 / Project licence

仓库当前采用 GPL-3.0-or-later。第三方代码与素材保留各自的许可证和署名。[项目许可](LICENSE) · [第三方声明](THIRD_PARTY_NOTICES.md)

The repository is licensed under GPL-3.0-or-later. Third-party code and artwork retain their own licence terms and attributions. [Project licence](LICENSE) · [Third-party notices](THIRD_PARTY_NOTICES.md)
