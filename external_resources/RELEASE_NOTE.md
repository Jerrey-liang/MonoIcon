# MonoIcon 2.4.0

**versionCode 6** · 上一个版本 / Previous release: **2.3.0**

## 中文

本次加入更完整的图标样式调节与单应用定制，统一壁纸取色来源，并按 KernelSU 风格整理界面与运行状态信息。

### 新增与改进

- **图标配色引擎**：可切换 Material 2025（默认）与 Material 3（MD3），两种引擎均支持原有九种配色风格，并跟随系统明暗模式。
- **从实际壁纸取色**：图标配色、应用默认的壁纸主题和预览共用同一张桌面壁纸图片。普通读取受限时，通过 Root 读取当前 Android 用户的壁纸文件，必要时尝试捕获壁纸图层。读取失败时保留上次图片或使用默认背景，并在预览下方显示状态；不再使用系统提供的壁纸颜色作为取色种子。
- **默认图标风格**：「应用原生」作为首次使用的默认策略，依次使用应用原生主题化图标、Lawnicons、MonoIcon 生成图标。同时保留「原生 → MonoIcon」直接生成的回退策略；「Lawnicons」优先使用内置资源，缺失时依次回退到原生图标和生成图标；「MonoIcon 生成」始终处理原始图标。
- **连续调节图标圆角与大小**：外框从系统圆角矩形连续过渡到圆形，大小从 MonoIcon 大小调节到系统默认大小；横向、纵向大图标在圆形端点保持胶囊形。
- **图案圆角**：为 MonoIcon 生成图标的内层图案新增独立圆角调节。0% 保留原始边缘，100% 将图案的实际占用范围裁成圆形，长方形图案则成为胶囊形；透明边距不计入范围。「图标圆角」中的「图案跟随图标圆角」可让内层图案使用外框的相对圆角，暂停独立值并保留原设置。默认图案圆角为 0%，跟随关闭；两项均支持单应用定制。
- **白边与生成图案改进**：白边调节保持外框和整体大小固定，等比缩放内部图案。100% 使用外板短边约 56.5% 的图案范围作为默认留白基准；0% 根据每个应用的实际图案轮廓和当前圆角，计算能完整容纳图案的最大尺寸。所有白边值均受容纳上限约束，结合图标圆角、图案圆角与大小避免溢出或裁切。保留图案比例、原有偏移、内部透明和白色细节，改善暗色模式下的负片观感。
- **应用图标定制**：列表扩展为所有桌面可启动的应用，以 40dp 原始图标保留系统形状，支持名称／包名搜索和组合筛选。点击应用进入独立调节页，各项参数可分别跟随默认或自定义，也可全部恢复默认。风格菜单只列出可用来源，「跟随默认」显示实际使用的风格并去除重复选项；白边和图案圆角仅在实际使用生成图标时显示。
- **固定实时预览**：「图标样式」主页面、图标圆角、图案圆角、图标大小和白边大小调节页均在标题下方固定预览，保留对应桌面壁纸背景。拖动时更新，松手后保存；单应用页面只预览所选应用。横屏或分屏高度不足时可独立滚动预览区域，各页面均提供作用域重启菜单。
- **KernelSU 风格界面**：「启用模块」主开关移至主页，统一搜索框、底栏图标和页面细节。Monet 配色、模糊、悬浮底栏、液态玻璃及更新检查始终开启，并迁移旧版关闭设置。
- **逐点运行报告**：「配置管理」新增来自桌面与 SystemUI 实际进程的 Hook 状态。HyperOS 3 显示各 Java Hook 点位的安装状态与首次触发情况；HyperOS 4 还显示 Native Hook、Profile 补丁状态、库基址、目标地址、原值和替换值。历史报告与未知状态会明确标记，避免把旧进程地址当作当前地址。

### 升级与使用

**从 2.3.0 或更早版本升级后，请先打开一次 MonoIcon，让设置迁移和壁纸读取完成，再重启手机，使作用域加载新版本。**

- 需要 Root 与 LSPosed，并勾选「系统桌面」和「系统界面」两个作用域；为 MonoIcon 授予 Root 权限，在主页确认「启用模块」已开启。
- 旧版圆形开关迁移到圆角滑块端点，旧版 Lawnicons 开关及应用选择保留兼容行为。新安装默认使用「应用原生」策略。
- 安全模式继续始终开启。触发后，在主页重新开启「启用模块」，再通过电源菜单重启作用域进行重试；恢复需要 Root 权限。
- 使用没有 `match.systemVersions` 的旧 HyperOS 4 Profile 时，仍需在「配置管理」中手动确认。切换配置或调整大图标造型后，重启桌面以完整刷新缓存和动画。升级后先重启作用域，再刷新运行信息以取得新版本报告。

### 兼容性与已知限制

官方配置沿用仓库此前记录的环境：**Redmi K50（22041211AC）、HyperOS 4 OS4.0.0.3.XAACNXM、Android 17、系统桌面 RELEASE-8.01.02.6144-260818-08251107-R**。其他系统与桌面版本、平板和折叠屏仍需独立适配与验证。

日历和时钟仍使用静态主题图案。动态壁纸只能获取系统允许捕获的可见帧，无法读取时会使用缓存或默认背景。文件夹、最近任务、通知入口和返回动画的完整外观验证范围仍有限，详见[配置指南](https://github.com/Jerrey-liang/MonoIcon/blob/main/docs/HyperOS4%20profile%E7%BC%96%E5%86%99%E6%8C%87%E5%8D%97.md)。

## English

This release adds broader icon appearance controls and per-app customization, shares one wallpaper image across colour extraction and previews, and refines the interface and runtime reports in the KernelSU style.

### New and improved

- **Icon colour engines:** choose Material 2025 (default) or Material 3 (MD3). Both support the existing nine colour styles and follow system light and dark modes.
- **Actual wallpaper colours:** icon palettes, the app's default wallpaper theme and previews share the retrieved home wallpaper image. When normal access is restricted, Root can read the current Android user's wallpaper file or attempt a wallpaper-layer capture. Failed reads retain the last image or use the default background, with a visible preview status. System-provided wallpaper colour seeds are no longer used.
- **Default icon style:** the initial **App native** strategy uses native themed artwork, then Lawnicons, then MonoIcon generation. **Native → MonoIcon** retains the direct native-to-generated fallback. **Lawnicons** priority falls back to native artwork and then generation. **MonoIcon generated** always processes the original icon.
- **Continuous icon corners and size:** adjust the outer shape from the system rounded rectangle to a circle, and the size from MonoIcon size to the system default. Rectangular large icons become capsules at the circular endpoint.
- **Artwork roundness:** independently round the inner artwork of MonoIcon generated icons, from the original edge at 0% to a circle at 100%, or a capsule for rectangular artwork. Transparent padding is excluded. **Artwork follows icon roundness**, under **Icon corners**, applies the outer icon's relative roundness and pauses the independent value without discarding it. The defaults add no artwork rounding and leave following off. Both settings support per-app customization.
- **Border and generated artwork:** border adjustment keeps the outer shape and overall size fixed while scaling the artwork proportionally. At 100%, the default padding uses an artwork extent of about 56.5% of the plate's shorter side. At 0%, each app's actual artwork footprint and current corners determine the largest scale that fits completely inside the plate. Every border value respects that fitting limit, including changes to icon corners, artwork roundness and size. Proportions, authored offsets, transparent details and internal white content are preserved, improving artwork that looked inverted in dark mode.
- **Per-app customization:** the list includes all desktop-launchable apps, displays their original icons at 40dp with the system shape, and supports app-name/package search and combined filters. Each app has a dedicated settings page with independent inherited or custom values and a full reset. Source menus show only usable artwork; **Follow default** names the resolved style and omits the duplicate custom choice. Border and artwork-roundness controls appear only when the resolved icon is generated.
- **Pinned live previews:** the main style tab and the icon-corners, artwork-roundness, size and border pages pin their previews below the title over the corresponding home wallpaper region. Previews update while dragging, and values save on release. Per-app pages preview only the selected app. Short landscape or split-screen windows can scroll the preview independently, and the pages share the scope restart menu.
- **KernelSU-style interface:** **Enable module** moves to the home page, alongside refinements to search, navigation icons and page details. Monet colours, blur, the floating bottom bar, liquid glass and update checks are always enabled, including migration from older disabled preferences.
- **Point-by-point runtime reports:** **Configurations** displays reports from actual Launcher and SystemUI processes. HyperOS 3 reports installation and first-hit status for individual Java hooks. HyperOS 4 also shows Native hooks and Profile patches, with library bases, target addresses, original values and replacements. Historical and unknown reports are clearly marked so old process addresses are not treated as current ones.

### Upgrading and use

**After upgrading from 2.3.0 or earlier, open MonoIcon once to let preference migration and wallpaper retrieval complete, then reboot the phone to load the new version in both scopes.**

- Root and LSPosed are required. Select both Launcher and SystemUI scopes, grant MonoIcon Root access, and confirm **Enable module** is enabled on the home page.
- Old circle preferences migrate to the corner slider endpoints. Existing Lawnicons preferences and app selections retain their compatibility behaviour; new installs default to **App native**.
- Startup safety remains always enabled. To retry after it trips, turn on **Enable module** on the home page and restart the scope using the power menu. Recovery requires Root access.
- Legacy HyperOS 4 Profiles without `match.systemVersions` still require manual activation under **Configurations**. Restart the launcher after switching Profiles or changing large-icon shapes to fully refresh caches and transitions. After upgrading, restart the scopes before refreshing runtime information to receive the new reports.

### Compatibility and known limitations

The official Profile retains the previously recorded **Redmi K50 (22041211AC), HyperOS 4 OS4.0.0.3.XAACNXM, Android 17, launcher RELEASE-8.01.02.6144-260818-08251107-R** target. Other system and launcher builds, tablets and foldables require separate adaptation and validation.

Calendar and Clock continue to use static themed artwork. Live wallpaper capture depends on a visible frame that the system permits capturing; otherwise a cached image or the default background is used. Full visual coverage of folders, recents, notifications and return animations remains limited; see the [configuration guide](https://github.com/Jerrey-liang/MonoIcon/blob/main/docs/HyperOS4%20profile%E7%BC%96%E5%86%99%E6%8C%87%E5%8D%97.md).
