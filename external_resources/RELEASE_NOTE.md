# MonoIcon 2.3.0

**versionCode 5** · 上一个版本 / Previous release: **2.2.0**

## 中文

本次新增始终开启的桌面启动保护，收紧 HyperOS 4 Profile 的系统匹配，并修复大图标尺寸读取与配置发布问题。

### 新增与改进

- **自动启动保护**：HyperOS 3 与 HyperOS 4 共用安全模式，无需开启，也没有独立关闭开关。模块启用后，桌面若在 30 秒启动观察期内退出，下次启动将跳过 MonoIcon 的图标 Hook，并保持停用，避免持续短命重启。保护在桌面进程内执行，不依赖 MonoIcon 界面保持运行；已触发的保护不会因手机重启而解除。
- **Profile 系统版本匹配**：新增可选字段 `match.systemVersions`，按完整系统版本精确匹配。没有该字段的旧配置仍可导入，但必须手动确认启用；确认只对当前配置内容和当前系统有效，替换配置或系统更新后需要重新确认。应用和原生运行时都会检查，旧的已发布配置也不能绕过检查。
- **更新官方配置**：内置 `hyperos4-6144` 更新至 **revision 9**，声明适配系统 **OS4.0.0.3.XAACNXM**，继续核对桌面版本、Android SDK 和 ELF 身份。其他系统版本不会仅因桌面版本相同而自动启用此配置。
- **修复 HyperOS 4 大图标尺寸读取**：尺寸桥接改用桌面实际绑定的系统接口，修正此前系统库定位不正确、接口无法读取的问题，并补充尺寸读取失败阶段的日志。
- **改进配置发布与重试**：桌面刚启动、尺寸暂不可用时，保留已有的大图标资源，避免被不完整的临时资源覆盖；改进桌面延迟确认时的重试、重复导出和重启前等待。读取配置失败或超过限制时，不再把部分结果当作完整输入。
- **完善使用与适配文档**：整理 Profile 编写指南与 AI 适配任务模板，补充系统匹配、验证、故障排查和恢复说明。

### 升级与使用

**从 2.2.0 升级后，请先打开一次 MonoIcon，检查配置与发布状态，再重启手机，让原生模块随新的桌面启动环境重新加载。**

- 需要 Root 与 LSPosed，并勾选「系统桌面」和「系统界面」两个作用域；为 MonoIcon 授予 Root 权限。
- 使用无系统版本字段的旧 Profile 时，请在「配置管理」中手动确认。切换 Profile 后需要重启桌面。
- 安全模式触发后，可在「模块设置」重新开启主开关，再通过电源菜单重启作用域进行重试；恢复操作需要 Root 权限。模块内发起的正常重启有一次性记录，正常重启手机也不会被当作短命重启。
- 更换大图标造型后可能仍需重启桌面。日历和时钟继续使用静态主题图案。

### 兼容性与验证范围

官方配置对应仓库中已有的测试环境：**Redmi K50（22041211AC）、HyperOS 4 OS4.0.0.3.XAACNXM、Android 17、系统桌面 RELEASE-8.01.02.6144-260818-08251107-R**。其他系统版本、桌面版本、平板和折叠屏仍需独立适配与验证。

相同桌面版本并不能保证系统接口与启动环境相同。本次源码调查确认存在这些跨系统依赖，因此增加版本限制与启动保护；**尚未依据反馈设备的崩溃日志定位具体根因，本次新增安全模式也尚未完成 HyperOS 3／4 实机验证。**

## English

This release adds always-on launcher startup protection, system-version matching for HyperOS 4 Profiles, and fixes for large-icon geometry and configuration publication.

- **Startup safety:** if the launcher exits during the 30-second observation period after activation, the next launch skips MonoIcon's icon hooks and keeps the module disabled across reboots. Protection runs inside the launcher on both launcher generations, even while the settings app is closed. There is no separate safety switch.
- **System-aware Profiles:** optional `match.systemVersions` entries match exact system versions. Legacy Profiles without the field require manual activation, bound to the current Profile contents and system identity. Both the app and native runtime enforce the check, including for previously published Profiles.
- **Official Profile revision 9:** `hyperos4-6144` declares **OS4.0.0.3.XAACNXM** and retains launcher-version, SDK and ELF verification. Matching the launcher version alone no longer enables it on another system build.
- **Large-icon fixes:** use the system interfaces already bound by the launcher to obtain geometry, preserve existing large-icon resources while geometry is temporarily unavailable, improve retries and publication waiting, and report clearer failure stages. Incomplete configuration reads are rejected.
- **Documentation:** updated Profile authoring guidance, an AI adaptation task template, and troubleshooting and recovery instructions.

**After upgrading from 2.2.0, open MonoIcon once, check the Profile and publication status, then reboot the phone to load the updated native module.** Root access and both LSPosed scopes—Launcher and SystemUI—are required. Activate legacy Profiles manually when prompted. To retry after safety protection trips, enable the existing master switch and restart the scope; recovery requires Root access. MonoIcon's intentional restarts are exempted once, and a normal phone reboot is not treated as a short-lived launcher restart.

The official Profile targets the previously recorded **Redmi K50, HyperOS 4 OS4.0.0.3.XAACNXM, Android 17, launcher RELEASE-8.01.02.6144-260818-08251107-R** environment. Other builds and device types require separate validation. The reported cross-system crash has not been traced to a specific cause using affected-device crash logs, and the new safety mode has not yet completed HyperOS 3/4 device validation.
