# MonoIcon 2.2.0

**versionCode 4** · 上一个版本 / Previous release: 2.1.0

## 中文

本次为功能更新：HyperOS 4 适配改为外部 JSON Profile 驱动，加入大图标支持，并重做界面。

- HyperOS 4 适配现由 JSON Profile 定义：应用内可导入、自动匹配、手动选择、导出和删除配置；内置官方 6144 配置（revision 7），环境与 ELF 校验通过后才安装。
- 新增大图标支持：HyperOS 3 与 HyperOS 4 的 2×1、1×2、2×2 图标按桌面实际尺寸导出；圆形开启时 2×1／1×2 为胶囊形、2×2 为圆形，关闭时为圆角矩形，图案保持等比。
- Lawnicons 清晰度提升：77 个图案按原始 SVG 重制为 768×768；普通图标的 PNG 导出提升到 512 像素。
- 修复刷新链路：开机首次进入桌面即应用主题图标，无需点击图标触发；按应用清理缓存并刷新大文件夹、日历和时钟预览；圆形图标的返回过渡动画保持圆形。
- 重做界面：主页、图标样式、配置管理、模块设置四页导航；新增主题设置、关于页、模糊与液态玻璃底栏、页面缩放、预测性返回、更新检查、日志保存与分享。
- 移除通知中心图标开关，通知图标随模块启用。
- 更新应用图标与单色主题图标。

**升级提示：从 2.1.0 升级后，先打开一次 MonoIcon 完成配置发布，再重启手机以加载新的原生模块；此后开机直接使用已发布资源。**

**兼容性说明：本版本仅适配并验证了当前测试环境（系统桌面 RELEASE-8.01.02.6144-260818-08251107-R），其他桌面版本未经测试，不保证可用；可通过自定义 Profile 尝试，但需自行完成分析与验证。系统或桌面更新后请重新确认。**

使用提示：需要为 MonoIcon 授予 root 权限，并在 LSPosed 中启用「系统桌面」和「系统界面」作用域；首次启用、模块更新或切换 Profile 后，请使用右上角电源菜单中的「重启作用域」。启用主题图标时，日历、时钟使用静态图案；发布或更换大图标造型后同样需要重启作用域。

## English

This feature release moves HyperOS 4 compatibility to external JSON Profiles, adds large-icon support and rebuilds the interface.

- HyperOS 4 compatibility is now defined by JSON Profiles that can be imported, matched, selected, exported and deleted in the app; the bundled official 6144 Profile (revision 7) is installed only after environment and ELF verification.
- Large icons: 2×1, 1×2 and 2×2 icons on HyperOS 3 and HyperOS 4 are exported at the launcher's actual sizes — capsules or circles when circular icons are on, rounded rectangles when off, with proportional glyphs.
- Sharper Lawnicons: all 77 glyphs rebuilt from the original SVGs at 768×768, and regular icon PNGs exported at 512 px.
- Refresh fixes: themed icons appear on the first home-screen launch without tapping an icon; caches are cleared per package and large folders plus Calendar/Clock previews refresh; the circular-icon return animation keeps its circular shape.
- New interface: four-page navigation (Home, Icon style, Configurations, Module settings) with theme options, an About page, blur and liquid-glass bars, page scaling, predictive back, update checks and log saving/sharing.
- The notification-icon switch is removed; notification icons now follow the module.
- New app icon and monochrome icon.

**Upgrade note: after updating from 2.1.0, open MonoIcon once to publish the configuration, then reboot the phone to load the new native module; later boots use the published resources.**

**Compatibility: this version is adapted and verified only for the current test environment (launcher RELEASE-8.01.02.6144-260818-08251107-R). Other launcher versions are untested and not guaranteed to work; custom Profiles can be tried at your own analysis and verification. Re-check after system or launcher updates.**

Usage: grant MonoIcon root access and enable both the Launcher and System UI scopes in LSPosed; after the first enablement, a module update or a Profile switch, use Restart scope from the power menu at the top right. Calendar and Clock use static artwork while themed icons are enabled; restart the scope after publishing or restyling large icons.
