# MonoIcon

将应用图标统一为单色 Material You 风格的 LSPosed 模块，无需额外安装图标包或主题。

当前版本：**2.2.0**。

## 兼容性说明

> **本版本仅适配并验证了当前测试环境。其他版本的系统桌面没有经过测试，不保证可用。请在安装前核对系统桌面版本。**

| 项目 | 当前测试环境 |
| --- | --- |
| 设备型号 | 22041211AC |
| 系统 | HyperOS 4，OS4.0.0.3.XAACNXM |
| Android 版本 | Android 17 |
| 系统桌面版本 | RELEASE-8.01.02.6144-260818-08251107-R |

相同的 HyperOS 大版本不代表系统桌面版本相同。系统或桌面更新后，也需要重新确认兼容性。

HyperOS 4 适配现由外部 JSON Profile 驱动，可在「配置管理」中导入并选择自定义配置。内置首个官方配置为 6144（revision 7），只有环境匹配且 ELF 校验通过后才会安装补丁。Profile 重构已完成一次限定范围的真机链路验证；后续 revision、界面与大图标改动仅通过静态检查和编译验证，仍需真机视觉验收。详见 [原生适配说明](docs/hyperos4.md) 和 [自定义 Profile 指南](docs/hyperos4-profile.md)。

## 功能

- 桌面、文件夹预览和最近任务中的单色主题图标。
- 9 种取色方案，可按喜好选择。
- 内置 Lawnicons 字形（77 个高清图案），可在设置中开启或关闭。
- 圆形图标与系统默认形状切换。
- 大图标（2×1、1×2、2×2）：圆形开启时 2×1／1×2 为胶囊形、2×2 为圆形，关闭时为圆角矩形，图案保持等比。
- 通知应用图标使用统一样式。
- 一键重启作用域，方便应用新设置。
- HyperOS 4 Profile 导入、自动匹配、手动选择与导出；切换 Profile 后重启桌面。
- 全新四页界面（主页、图标样式、配置管理、模块设置），支持主题设置、模糊与液态玻璃底栏、页面缩放、更新检查、日志保存与分享。

启用后，日历、时钟等动态图标会使用静态主题图标。日历图案中的数字不表示当天日期。

## 安装与使用

1. 安装 MonoIcon 发布版 APK。
2. 在 LSPosed 中启用模块，并勾选「系统桌面」和「系统界面」作用域。
3. 为 MonoIcon 授予 root 权限。当前 HyperOS 4 适配需要此权限。
4. 打开 MonoIcon，选择喜欢的配色和图标形状；HyperOS 4 请在「配置管理」中确认已选择兼容的 Profile。
5. 安装或更新模块后，使用右上角电源菜单中的「重启作用域」。修改设置后若显示未刷新，也可使用此按钮。

## 2.2.0 更新

- HyperOS 4 适配重构为外部 JSON Profile 驱动：内置官方 6144 配置（revision 7），支持导入、自动匹配、手动选择、导出和删除；切换后需重启桌面。
- 新增大图标支持：HyperOS 3 与 HyperOS 4 的 2×1、1×2、2×2 图标按桌面实际尺寸导出；圆形开启时为胶囊形／圆形，关闭时为圆角矩形，图案保持等比。
- Lawnicons 清晰度提升：77 个图案按原始 SVG 重制为 768×768，普通图标 PNG 导出提升到 512 像素。
- 修复刷新链路：开机首次进入桌面即应用主题图标，无需点击图标；按应用清理缓存并刷新大文件夹、日历和时钟预览；圆形图标的返回过渡动画保持圆形。
- 重做界面：主页、图标样式、配置管理、模块设置四页导航；主页显示设备与模块信息，新增主题设置、关于页、模糊与液态玻璃底栏、页面缩放、预测性返回、更新检查、日志保存与分享。
- 移除通知中心图标开关，通知图标随模块启用。
- 更新应用图标与单色主题图标。

## 已知问题

- HyperOS 4 圆形图标与返回动画的修复仅通过静态验证，尚未完成真机视觉验收。
- 大图标（含 HyperOS 3）尚待真机视觉验收，支持范围与验证说明见[大图标说明](docs/large-icons.md)。
- HyperOS 4 Profile 重构后，除一次限定范围的真机链路验证外，其余改动均未在设备上验证。

## 许可证

[Apache License 2.0](LICENSE)。二次分发时请同时遵守所使用第三方组件的许可证，详见[第三方声明](THIRD_PARTY_NOTICES.md)。

---

# MonoIcon

An LSPosed module that gives app icons a consistent monochrome Material You style, without a separate icon pack or theme.

Current version: **2.2.0**.

## Compatibility

> **This release is adapted and verified only for the test environment below. Other system launcher versions have not been tested and are not guaranteed to work. Check your launcher version before installing.**

| Item | Tested environment |
| --- | --- |
| Device model | 22041211AC |
| System | HyperOS 4, OS4.0.0.3.XAACNXM |
| Android version | Android 17 |
| System launcher | RELEASE-8.01.02.6144-260818-08251107-R |

Devices with the same major HyperOS version may have different launcher versions. Compatibility must be checked again after system or launcher updates.

HyperOS 4 compatibility is now driven by external JSON Profiles that can be imported and selected under Configurations. The first bundled Profile covers 6144 (revision 7); patches are installed only after environment matching and ELF verification. The Profile refactor has completed one limited on-device link test; later revisions, the new interface and the large-icon work have only passed static checks and compilation and still need visual acceptance on a device. See the [native compatibility notes](docs/hyperos4.md) and [custom Profile guide](docs/hyperos4-profile.md) (Chinese).

## Features

- Monochrome icons on the home screen, in folder previews and in recents.
- Nine colour variants.
- Bundled Lawnicons glyphs (77 high-resolution assets) with an optional toggle.
- Circular icons or the system default shape.
- Large icons (2×1, 1×2, 2×2): capsules or circles when circular icons are on, rounded rectangles when off, with proportional glyphs.
- Consistent notification app icons.
- One-tap scope restart to apply changes.
- Import, match, select and export HyperOS 4 Profiles; restart the launcher after switching.
- A new four-page interface (Home, Icon style, Configurations, Module settings) with theme options, blur and liquid-glass bars, page scaling, update checks and log saving/sharing.

Dynamic icons such as Calendar and Clock use static themed icons while enabled. Numbers in the calendar artwork do not indicate the current date.

## Installation

1. Install the MonoIcon release APK.
2. Enable it in LSPosed and select the system launcher and SystemUI scopes.
3. Grant root access to MonoIcon; this HyperOS 4 adaptation requires it.
4. Open MonoIcon and choose your colours and icon shape; on HyperOS 4, confirm a compatible Profile is selected under Configurations.
5. Use **Restart scope** from the power menu at the top right after installing or updating the module, or if a setting change has not appeared yet.

## What's new in 2.2.0

- HyperOS 4 compatibility is now driven by external JSON Profiles: the bundled official 6144 Profile (revision 7) plus import, automatic matching, manual selection, export and deletion; restart the launcher after switching.
- Large icons: 2×1, 1×2 and 2×2 icons on HyperOS 3 and HyperOS 4 are exported at the launcher's actual sizes; capsules or circles when circular icons are on, rounded rectangles when off, with proportional glyphs.
- Sharper Lawnicons: all 77 glyphs rebuilt from the original SVGs at 768×768, and regular icon PNGs exported at 512 px.
- Refresh fixes: themed icons appear on the first home-screen launch without tapping an icon; caches are cleared per package and large folders plus Calendar/Clock previews refresh; the circular-icon return animation keeps its circular shape.
- New interface: four-page navigation (Home, Icon style, Configurations, Module settings) with device/module info on Home, theme options, an About page, blur and liquid-glass bars, page scaling, predictive back, update checks and log saving/sharing.
- The notification-icon switch is removed; notification icons now follow the module.
- New app icon and monochrome icon.

## Known issues

- The HyperOS 4 circular-icon and return-animation fixes have only passed static checks and still need visual acceptance on a device.
- Large icons (including HyperOS 3) still need visual acceptance on a device; see [large icons](docs/large-icons.md) for the supported scope and verification notes.
- Since the Profile refactor, apart from one limited on-device link test, the changes have not been verified on a device.

## License

[Apache License 2.0](LICENSE). Redistribution must also comply with the licences of the included third-party components; see the [third-party notices](THIRD_PARTY_NOTICES.md).
