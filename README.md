# MonoIcon

让 HyperOS 的应用图标拥有统一、简洁的单色外观。

MonoIcon 会根据壁纸搭配图标颜色，也可以按你的喜好切换配色和形状，无需另外安装图标包或主题。

[下载最新版](https://github.com/Jerrey-liang/MonoIcon/releases/latest) · [配置指南](<docs/HyperOS4 profile编写指南.md>) · [反馈问题](https://github.com/Jerrey-liang/MonoIcon/issues)

## 功能

- **统一图标风格**：为桌面应用提供单色主题图标，并支持文件夹、最近任务和通知中的图标适配。
- **随心选择配色**：提供九种配色方案，与壁纸和系统明暗模式搭配。
- **切换图标形状**：选择圆形或默认形状，也支持横向、纵向和方形大图标。
- **内置补充图案**：可开启 Lawnicons，让更多应用拥有统一的图案。
- **管理兼容配置**：在应用内导入、选择和分享适合自己桌面版本的配置。
- **自动启动保护**：HyperOS 3 与 HyperOS 4 共用始终开启的安全模式，桌面短命重启后自动停用模块。
- **调整应用外观**：支持中英文、主题设置、模糊效果和液态玻璃底栏。

## 使用前确认

MonoIcon 需要已获取 Root 权限、能够正常使用 LSPosed 的手机。

目前已验证的环境为：

| 项目 | 版本 |
| --- | --- |
| 手机 | Redmi K50（22041211AC） |
| 系统 | HyperOS 4 · OS4.0.0.3.XAACNXM |
| Android | 17 |
| 系统桌面 | RELEASE-8.01.02.6144-260818-08251107-R |

其他系统桌面版本、平板及折叠屏尚未完成验证。即使同为 HyperOS 4，也请核对完整的系统桌面版本；系统或桌面更新后可能需要新的兼容配置。

## 开始使用

1. 从发布页下载并安装 MonoIcon。
2. 在 LSPosed 中启用 MonoIcon，勾选「系统桌面」和「系统界面」。
3. 打开 MonoIcon，按提示授予 Root 权限。
4. 在「配置管理」中确认有适合当前桌面版本的配置，再到「图标样式」选择配色和形状。
5. 使用右上角电源菜单中的重启选项应用设置。升级到 2.3.0 后，先打开一次 MonoIcon，再重启手机。

图标没有变化时，先检查 LSPosed 中的两个作用域和 Root 授权，再检查所选配置是否匹配。更换配置后需要重启桌面。

安全模式没有独立开关。模块启用后，桌面若在启动保护的 30 秒观察期内退出，下次启动将跳过模块的图标 Hook，并保持停用；手机重启不会解除已触发的保护。模块内主动发起的重启有一次性记录，正常重启手机也不会被当作短命重启。需要重试时，在「模块设置」重新开启主开关，再重启作用域；恢复操作需要 Root 权限。保护在桌面进程内执行，不依赖 MonoIcon 界面保持运行。

HyperOS 4 配置可以声明 `match.systemVersions`，例如 `["OS4.0.0.3.XAACNXM"]`，与系统完整版本精确匹配。没有该字段的旧配置仍能导入，但必须在「配置管理」中手动确认启用。确认只对当前配置内容与当前系统有效，替换配置或系统更新后需要重新确认。仅有相同桌面版本不能保证系统接口兼容。

## 已知限制

- 日历和时钟会使用静态主题图案；日历图案中的数字不是当天日期。
- 更换大图标的造型后，可能需要重启桌面才能看到新外观。
- 关闭图标主题功能时，桌面会自动重启一次，恢复原来的图标。
- 文件夹、最近任务、通知入口和返回动画的完整外观验证仍有限。各项已确认的效果与适用范围见[配置指南](<docs/HyperOS4 profile编写指南.md#compatibility>)。

## 文档与许可

[配置编写指南](<docs/HyperOS4 profile编写指南.md>)包含配置使用、材料准备和编写步骤；[AI 任务模板](docs/Agent_example_prompt.md)可配合指南使用。

MonoIcon 和所包含的第三方组件遵循各自的许可证，详见[许可证与第三方声明](docs/THIRD_PARTY_NOTICES.md)。

---

## English

MonoIcon gives HyperOS app icons a consistent monochrome look. Match your icons to your wallpaper, choose from nine colour schemes, and switch between circular and default shapes without installing a separate icon pack or theme.

It supports large icons, optional Lawnicons artwork, compatibility configuration management, and Chinese and English interfaces with theme and blur options. Icon styling also extends to folders, recents and notifications, although visual coverage for these surfaces and return animations remains limited.

### Compatibility

You need a rooted phone with LSPosed. The verified environment is **Redmi K50 (22041211AC), HyperOS 4 OS4.0.0.3.XAACNXM, Android 17**, with launcher **RELEASE-8.01.02.6144-260818-08251107-R**.

Other launcher versions, HyperOS 3, tablets and foldables have not completed validation. Check compatibility again after system or launcher updates.

### Getting started

1. Install MonoIcon from the [release page](https://github.com/Jerrey-liang/MonoIcon/releases/latest).
2. Enable it in LSPosed and select both the system launcher and SystemUI scopes.
3. Open MonoIcon and grant Root access.
4. Select a compatible configuration under **Configurations**, then choose your colours and shape under **Icon style**.
5. Apply changes using the restart options in the top-right power menu. After upgrading to 2.3.0, open MonoIcon once and reboot the phone.

Calendar and Clock use static artwork; calendar numbers do not show the current date. Large-icon appearance changes may require a launcher restart. Disabling icon theming automatically restarts the launcher once to restore the original icons. Restart the launcher after switching compatibility configurations.

Startup safety is always enabled for both launcher generations. If the launcher exits during the 30-second observation period after activation, the next launch skips MonoIcon and latches it off across reboots. MonoIcon's intentional restarts are exempted once. To retry, enable the existing master switch and restart the scope; recovery needs Root access. Protection runs inside the launcher even while the settings app is closed.

HyperOS 4 Profiles may declare exact system versions in `match.systemVersions`. Legacy Profiles without this field require explicit manual activation. Approval is tied to the Profile contents and current system identity, and must be renewed after replacement or a system update.

See the [configuration guide](<docs/HyperOS4 profile编写指南.md>) and [AI task template](docs/Agent_example_prompt.md) for more help (Chinese), or [report an issue](https://github.com/Jerrey-liang/MonoIcon/issues). Licence terms and attribution are collected in [Third-party notices](docs/THIRD_PARTY_NOTICES.md).
