# MonoIcon

将应用图标统一为单色 Material You 风格的 LSPosed 模块，无需额外安装图标包或主题。

当前版本：**2.1.0**。

## 兼容性说明

> **本版本仅适配并验证了当前测试环境。其他版本的系统桌面没有经过测试，不保证可用。请在安装前核对系统桌面版本。**

| 项目 | 当前测试环境 |
| --- | --- |
| 设备型号 | 22041211AC |
| 系统 | HyperOS 4，OS4.0.0.3.XAACNXM |
| Android 版本 | Android 17 |
| 系统桌面版本 | RELEASE-8.01.02.6144-260818-08251107-R |

相同的 HyperOS 大版本不代表系统桌面版本相同。系统或桌面更新后，也需要重新确认兼容性。

## 功能

- 桌面、文件夹预览和最近任务中的单色主题图标。
- 9 种取色方案，可按喜好选择。
- 内置 Lawnicons 字形，可在设置中开启或关闭。
- 圆形图标与系统默认形状切换。
- 通知应用图标使用统一样式。
- 一键重启作用域，方便应用新设置。

启用后，日历、时钟等动态图标会使用静态主题图标。日历图案中的数字不表示当天日期。

## 安装与使用

1. 安装 MonoIcon 发布版 APK。
2. 在 LSPosed 中启用模块，并勾选「系统桌面」和「系统界面」作用域。
3. 为 MonoIcon 授予 root 权限。当前 HyperOS 4 适配需要此权限。
4. 打开 MonoIcon，选择喜欢的配色和图标形状。
5. 安装或更新模块后，使用应用中的「重启作用域」。修改设置后若显示未刷新，也可使用此按钮。

## 2.1.0 更新

- 新增对上述 HyperOS 4 测试环境的适配。
- 修复部分图标不变色、显示为纯色块或仍保留原图的问题。
- 完善电话、日历、时钟等系统应用的图标显示。
- 改善圆形图标大小，以及配色、Lawnicons 和总开关的切换与恢复。

## 已知问题

- HyperOS 4环境下圆形图标尚未完全适配
- Monoicon现在还没有添加对大图标的支持，请等待后续版本更新

## 许可证

[Apache License 2.0](LICENSE)。二次分发时请同时遵守所使用第三方组件的许可证。

---

# MonoIcon

An LSPosed module that gives app icons a consistent monochrome Material You style, without a separate icon pack or theme.

Current version: **2.1.0**.

## Compatibility

> **This release is adapted and verified only for the test environment below. Other system launcher versions have not been tested and are not guaranteed to work. Check your launcher version before installing.**

| Item | Tested environment |
| --- | --- |
| Device model | 22041211AC |
| System | HyperOS 4, OS4.0.0.3.XAACNXM |
| Android version | Android 17 |
| System launcher | RELEASE-8.01.02.6144-260818-08251107-R |

Devices with the same major HyperOS version may have different launcher versions. Compatibility must be checked again after system or launcher updates.

## Features

- Monochrome icons on the home screen, in folder previews and in recents.
- Nine colour variants.
- Bundled Lawnicons glyphs with an optional toggle.
- Circular icons or the system default shape.
- Consistent notification app icons.
- One-tap scope restart to apply changes.

Dynamic icons such as Calendar and Clock use static themed icons while enabled. Numbers in the calendar artwork do not indicate the current date.

## Installation

1. Install the MonoIcon release APK.
2. Enable it in LSPosed and select the system launcher and SystemUI scopes.
3. Grant root access to MonoIcon; this HyperOS 4 adaptation requires it.
4. Open MonoIcon and choose your colours and icon shape.
5. Use **Restart scope** after installing or updating the module, or if a setting change has not appeared yet.

## What's new in 2.1.0

- Support for the HyperOS 4 test environment listed above.
- Fixes for unchanged icons, solid-colour blocks and original icons overriding the selected style.
- Improved Phone, Calendar and Clock icons.
- Improved circular icon sizing and switching between colours, Lawnicons and the master toggle.

## License

[Apache License 2.0](LICENSE). Redistribution must also comply with the licences of the included third-party components.
