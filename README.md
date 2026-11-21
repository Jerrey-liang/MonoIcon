# MonoIcon

让 HyperOS 的应用图标拥有统一、简洁的单色外观。

MonoIcon 会根据壁纸搭配图标颜色，也可以按你的喜好切换配色和形状，无需另外安装图标包或主题。

[下载最新版](https://github.com/Jerrey-liang/MonoIcon/releases/latest) · [2.4.0 发布说明](external_resources/RELEASE_NOTE.md) · [配置指南](<docs/HyperOS4 profile编写指南.md>) · [反馈问题](https://github.com/Jerrey-liang/MonoIcon/issues)

## 功能

- **统一图标风格**：为桌面应用提供单色主题图标，并支持文件夹、最近任务和通知中的图标适配。
- **随心选择配色**：可选择 Material 2025 或 Material Design 3 引擎，提供九种配色方案，与壁纸和系统明暗模式搭配。
- **调整图标圆角与大小**：从系统圆角矩形过渡到圆形，从 MonoIcon 大小放大到系统默认大小；横向、纵向大图标在圆形端点保持胶囊形。
- **调整图案圆角**：MonoIcon 生成图标的内层图案可从无新增圆角连续调节至圆形，也可在「图标圆角」中开启「图案跟随图标圆角」。支持默认设置和单应用定制。
- **调整外部白边**：固定图标外轮廓，根据每个应用的图案轮廓等比调节内部大小；无白边时完整容纳图案，避免圆角裁掉图案边缘；保护内部白色细节，改善暗色模式下的负片观感。
- **默认图标风格**：选择优先使用应用原生主题化图标、Lawnicons，或始终由 MonoIcon 处理原始图标。所选资源缺失时自动回退。
- **实时预览**：「图标样式」主页面及「图标圆角」「图案圆角」「图标大小」「白边大小」独立调节页均将预览固定在标题下方，并保留对应桌面壁纸背景。
- **应用图标定制**：列表只显示桌面可启动的应用，以 40dp 展示保留系统形状的原始图标。点击进入独立调节页，顶部固定显示该应用的主题化预览。图标风格只列出可用资源，白边只在实际使用生成图标时显示。各项参数可分别跟随默认或自定义，支持名称/包名搜索和组合筛选。
- **管理兼容配置与运行状态**：在应用内导入、选择和分享适合自己桌面版本的配置，查看桌面与 SystemUI 的逐点 Hook 报告；HyperOS 4 还显示 Native Hook、Profile 补丁及相关地址。
- **自动启动保护**：HyperOS 3 与 HyperOS 4 共用始终开启的安全模式，桌面短命重启后自动停用模块。
- **KernelSU 风格界面**：支持中英文与主题设置；Monet 配色、模糊、悬浮底栏和液态玻璃效果始终开启，更新检查也始终开启。「启用模块」主开关位于主页。

## 使用前确认

MonoIcon 需要已获取 Root 权限、能够正常使用 LSPosed 的手机。

仓库此前记录的验证环境为：

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
3. 打开 MonoIcon，按提示授予 Root 权限，并在主页确认「启用模块」已开启。
4. 在「配置管理」中确认有适合当前桌面版本的配置，再到「图标样式」选择配色和形状。
5. 使用右上角电源菜单中的重启选项应用设置。升级到 2.4.0 后，先打开一次 MonoIcon，让设置迁移和壁纸读取完成，再重启手机。

图标没有变化时，先检查 LSPosed 中的两个作用域和 Root 授权，再检查所选配置是否匹配。更换配置后需要重启桌面。

在「图标样式」选择「默认图标风格」：「应用原生」优先使用应用自带的主题化图标，缺失时使用 Lawnicons，再缺失时由 MonoIcon 生成，也是首次使用的默认策略；「原生 → MonoIcon」保留原生缺失时直接生成的策略；「Lawnicons」优先使用内置资源，缺失时依次使用应用原生、MonoIcon 生成；「MonoIcon 生成」始终处理原始图标。「应用图标定制」可独立覆盖各项参数，也可单项或全部恢复默认。列表只包括桌面可启动的应用，不再限定为有 Lawnicons 资源的应用。图标风格菜单仅列出该应用支持的来源；“跟随默认”显示实际使用的风格，并省略相同风格的独立选项。白边及图案圆角控件根据实际生成的预览来源显示。旧版 Lawnicons 开关及应用选择会保留兼容行为。「图标配色引擎」可切换 Material 2025（默认）和 Material Design 3，两者均支持原有九种配色风格。

进入「图标圆角」「图案圆角」「图标大小」或「白边大小」子页面，拖动时更新顶部预览，松手后保存。「应用图标定制」列表中点击应用卡片进入三级页面，使用同一预览方式，只显示所选应用。预览区域固定在标题下方；横屏或分屏高度不足时，可独立滚动预览区域。各调节页和每应用列表均可通过右上角电源菜单重启作用域，完整应用桌面缓存和动画的变化。旧版圆形开关会自动对应圆角滑块的左右端点，大小默认为 MonoIcon 大小。

「图案圆角」调整 MonoIcon 生成图标的内层图案：0% 保留原始图案边缘，100% 将图案的实际占用矩形裁成圆形，长方形图案则成为胶囊形，中间值连续调节。透明边距不计入图案范围，图案的内部透明细节和色调保持原样。在「图标圆角」开启「图案跟随图标圆角」后，图案使用与外框相同的相对圆角，独立图案圆角暂停生效并保留原值；关闭后可继续独立调节。默认不开启跟随，也不新增图案圆角。每个应用可分别覆盖图案圆角和跟随开关；使用原生主题化或 Lawnicons 图案时隐藏这些控件。白边容纳上限按圆角后的图案轮廓重新计算。

「白边大小」固定图标外轮廓及整体大小，通过等比缩放内部图案调节留白：100% 以外板短边约 56.5% 的图案范围作为统一默认白边基准；0% 根据每个应用原始图案的实际透明度轮廓，计算当前圆角底板能完整容纳的最大尺寸。所有白边值均受此容纳上限约束，中间值连续调整。方形图案放入圆形底板时会保留必要的空白，避免图案角落被裁掉；长方形大图标保持图案的长宽比例、原有偏移和内部透明细节。整体尺寸仍由独立的「图标大小」控制。实际由 MonoIcon 生成的图标均可调节白边，包括轮廓和稀疏图案。实际使用 Lawnicons 或应用原生单色图层时，定制页面隐藏白边控件并保留图案比例。内部白色内容不会被算法挖空。拖动时预览更新，松手后保存。

图标配色和应用默认的壁纸主题均从同一张桌面壁纸图片自行提取种子。普通读取受限时，MonoIcon 会通过 Root 读取当前 Android 用户的壁纸文件，必要时尝试捕获壁纸图层；读取仍不可用时，保留上次壁纸或使用默认背景，并在预览下方提示。不会再使用系统提供的壁纸颜色作为取色种子。动态壁纸只能获取系统允许捕获的可见帧，无法读取时会使用上述回退。

安全模式没有独立开关。模块启用后，桌面若在启动保护的 30 秒观察期内退出，下次启动将跳过模块的图标 Hook，并保持停用；手机重启不会解除已触发的保护。模块内主动发起的重启有一次性记录，正常重启手机也不会被当作短命重启。需要重试时，在主页重新开启「启用模块」，再重启作用域；恢复操作需要 Root 权限。保护在桌面进程内执行，不依赖 MonoIcon 界面保持运行。

HyperOS 4 配置可以声明 `match.systemVersions`，例如 `["OS4.0.0.3.XAACNXM"]`，与系统完整版本精确匹配。没有该字段的旧配置仍能导入，但必须在「配置管理」中手动确认启用。确认只对当前配置内容与当前系统有效，替换配置或系统更新后需要重新确认。仅有相同桌面版本不能保证系统接口兼容。

「配置管理」中的「刷新运行信息」请求桌面与 SystemUI 的实际进程报告。HyperOS 3 显示各 Java Hook 点位的安装状态与首次触发情况；HyperOS 4 还显示 Native Hook 与 Profile 补丁的状态、库基址、目标地址、原值和替换值。没有收到当前进程报告时，界面会明确标记历史记录或未知状态，历史地址不能当作当前地址使用。升级后先重启作用域，才能取得新版本的逐点报告。

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

MonoIcon gives HyperOS app icons a consistent monochrome look. Match your icons to your wallpaper using Material 2025 or Material Design 3, choose from nine colour schemes, and adjust corners and size without installing a separate icon pack or theme.

It supports large icons, app icon customization with live previews, compatibility configuration management, and Chinese and English interfaces following the KernelSU style. Monet colours, blur, the floating glass bottom bar and update checks are always enabled. The **Enable module** switch is on the home page. **Default icon style** chooses app-native themed artwork, Lawnicons, or MonoIcon generation. App native is the initial default: native themed artwork, then Lawnicons, then MonoIcon generation. Native → MonoIcon retains the direct native-to-generated fallback. Lawnicons priority falls back to native themed artwork and then generation; MonoIcon generation always processes the original icon. **App icon customization** lists desktop-launchable apps with their original icons at 40dp, preserving the system shape. The list supports search and filters. Tap an app to open its settings page, with one pinned themed preview and independent default/custom choices for source, icon and artwork roundness, size and border. Source choices only include available artwork. Follow default names the actual resolved style, which is omitted from the separate custom choices; the border control appears when the actual preview uses generated artwork. The main style tab and each appearance slider also have pinned previews; short windows can scroll the preview independently. The app list and detail pages share the same restart menu. Existing Lawnicons selections retain their compatibility behavior. Icon styling also extends to folders, recents and notifications, although visual coverage for these surfaces and return animations remains limited.

**Icon corners** runs from the system rounded rectangle to a circle; rectangular large icons become capsules. **Icon size** runs from MonoIcon size to the system default. Six examples below the page title update while dragging, over the corresponding region of your home wallpaper. Values save when you release the slider; restart the scope to refresh all launcher caches and transitions. Old circle preferences migrate to the slider endpoints.

**Border size** keeps the icon's outer shape and overall size fixed while scaling the artwork proportionally. At 100%, generated artwork uses a common maximum extent of about 56.5% of the plate's shorter side, giving different icon sources the same default padding. At 0%, each app's actual alpha footprint determines the largest proportional scale that fits completely inside the current rounded rectangle or circle. All values respect this fitting limit, with continuous adjustment in between. Square artwork inside a circular plate retains the space needed to keep its corners intact. Its aspect ratio, authored offset and transparent details are preserved. Overall dimensions remain controlled by the separate **Icon size** setting. All icons actually generated by MonoIcon support border adjustment, including silhouettes and sparse artwork. When an app uses Lawnicons or native monochrome artwork, its customization page hides the border control and preserves the authored proportions. The generated mask no longer reverses white motifs into holes in dark mode.

**Artwork roundness** continuously rounds the inner artwork of MonoIcon generated icons, from its original edge at 0% to a circle at 100% (a capsule for rectangular artwork). Transparent bitmap padding is excluded. **Artwork follows icon roundness**, inside **Icon corners**, uses the outer icon's relative corner radius and pauses the independent value without discarding it. The initial defaults add no artwork rounding and leave following off. Both settings can be customized per app, with live previews. Native themed and Lawnicons artwork retain their authored shapes. Border fitting uses the rounded artwork footprint to keep it inside the outer icon.

Both icon palettes and the app's default wallpaper theme derive their seed from the retrieved wallpaper image. When ordinary access is restricted, MonoIcon uses Root to read the current user's wallpaper file or capture the wallpaper layer. It retains the last image or a stable fallback with a visible preview status if retrieval fails. Live wallpaper capture requires a visible frame that the system permits capturing. System-provided wallpaper colour seeds are no longer used.

### Compatibility

You need a rooted phone with LSPosed. The previously recorded environment is **Redmi K50 (22041211AC), HyperOS 4 OS4.0.0.3.XAACNXM, Android 17**, with launcher **RELEASE-8.01.02.6144-260818-08251107-R**.

Other launcher versions, tablets and foldables have not completed validation. Check compatibility again after system or launcher updates.

### Getting started

1. Install MonoIcon from the [release page](https://github.com/Jerrey-liang/MonoIcon/releases/latest).
2. Enable it in LSPosed and select both the system launcher and SystemUI scopes.
3. Open MonoIcon, grant Root access, and confirm **Enable module** is enabled on the home page.
4. Select a compatible configuration under **Configurations**, then choose your colours and shape under **Icon style**.
5. Apply changes using the restart options in the top-right power menu. After upgrading to 2.4.0, open MonoIcon once to let preference migration and wallpaper retrieval complete, then reboot the phone.

Calendar and Clock use static artwork; calendar numbers do not show the current date. Large-icon appearance changes may require a launcher restart. Disabling icon theming automatically restarts the launcher once to restore the original icons. Restart the launcher after switching compatibility configurations.

Startup safety is always enabled for both launcher generations. If the launcher exits during the 30-second observation period after activation, the next launch skips MonoIcon and latches it off across reboots. MonoIcon's intentional restarts are exempted once. To retry, turn on **Enable module** on the home page and restart the scope; recovery needs Root access. Protection runs inside the launcher even while the settings app is closed.

HyperOS 4 Profiles may declare exact system versions in `match.systemVersions`. Legacy Profiles without this field require explicit manual activation. Approval is tied to the Profile contents and current system identity, and must be renewed after replacement or a system update.

**Refresh runtime information** under **Configurations** requests reports from the actual Launcher and SystemUI processes. HyperOS 3 shows installation and first-hit status for individual Java hooks. HyperOS 4 also reports Native hooks and Profile patches, including library bases, target addresses, original values and replacements. Historical or unknown reports are clearly marked when no current process replies; old addresses are not current addresses. Restart the scopes after upgrading to receive the new point-by-point reports.

See the [configuration guide](<docs/HyperOS4 profile编写指南.md>) and [AI task template](docs/Agent_example_prompt.md) for more help (Chinese), or [report an issue](https://github.com/Jerrey-liang/MonoIcon/issues). Licence terms and attribution are collected in [Third-party notices](docs/THIRD_PARTY_NOTICES.md).
