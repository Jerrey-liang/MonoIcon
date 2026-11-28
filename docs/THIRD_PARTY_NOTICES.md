# 许可证与第三方声明 / Licences and third-party notices

MonoIcon 采用 **GNU GPL 第 3 版或任何后续版本（GPL-3.0-or-later）**。第三方代码与素材保留各自的许可证和署名。分发组合构建时须遵守 GPL-3.0-or-later，包括提供对应源代码。[项目许可声明](LICENSE) · [许可证全文](../LICENSE)

MonoIcon is licensed under the **GNU General Public License, version 3 or (at your option) any later version (GPL-3.0-or-later)**. Third-party components retain their own licence terms and attributions. Distributing a combined build requires complying with GPL-3.0-or-later, including providing the corresponding source. [Project licence notice](LICENSE) · [Full licence text](../LICENSE)

## KernelSU 界面 / KernelSU interface adaptations

MonoIcon adapts its interface from KernelSU Manager at revision `2fe188310309bd7a7c9b5a80beed6f14469c6cd2`. [KernelSU source](https://github.com/tiann/KernelSU/tree/2fe188310309bd7a7c9b5a80beed6f14469c6cd2)

KernelSU's README licenses its manager and other non-kernel code under **GPL-3.0-or-later**. The adapted navigation, home, settings, theme, About, background effects, dialogs and blur helpers retain that licence and their upstream copyright notices. MonoIcon adapted these components for its LSPosed status, runtime diagnostics, release feed, logs and project links. [KernelSU GPL terms](licenses/KernelSU-GPL-3.0.txt)

KernelSU's `FloatingBottomBar.kt` attributes the compose-miuix-ui code to Apache-2.0. MonoIcon retains that notice in `LiquidGlassNavigationBar.kt`.

### 源码对应关系 / Source map

MonoIcon paths are relative to its Kotlin source directory. [Browse MonoIcon source](../app/src/main/java/com/jerrey/monoicon/)

| MonoIcon | KernelSU manager source |
| --- | --- |
| `ui/screens/SettingsScreen.kt` | `ui/MainActivity.kt`, `ui/component/bottombar/BottomBar.kt`, `BottomBarMiuix.kt` |
| `ui/screens/OverviewScreen.kt`, `HomeSystemInfo.kt`, `HomeTopBarActions.kt` | `ui/screen/home/HomeMiuix.kt`, home view model, reboot popup |
| `ui/screens/ModuleSettingsScreen.kt` | `ui/screen/settings/SettingsMiuix.kt`, dialog components |
| `ui/screens/ConfigurationRuntimeContent.kt` | `ui/screen/settings/SettingsMiuix.kt` card and preference layout |
| `ui/screens/ThemeSettingsScreen.kt`, `ui/theme/Theme.kt` | `ui/screen/colorpalette/ColorPaletteScreenMiuix.kt`, `ui/theme/MiuixTheme.kt` |
| `ui/screens/LawniconsAppsScreen.kt`, `AppIconSettingsScreen.kt`, `IconStyleScreen.kt`, `IconStyleParameterScreen.kt`, `IconStylePreview.kt` | `ui/screen/superuser/SuperUserMiuix.kt`, `ui/screen/settings/SettingsMiuix.kt`, `ui/screen/colorpalette/ColorPaletteScreenMiuix.kt`, Miuix preference components |
| `ui/screens/KernelSearch.kt` | `ui/component/miuix/SuperSearchBar.kt`, `ui/component/SearchStatus.kt` |
| `ui/screens/AboutScreen.kt`, `ui/settings/effect/` | `ui/screen/about/AboutMiuix.kt`, background effect components |
| `ui/settings/SendLogDialog.kt` | `ui/component/miuix/SendLogDialog.kt` |
| `ui/liquid/BlurredBar.kt` | `ui/util/BlurExt.kt` |
| `ui/liquid/LiquidGlassNavigationBar.kt` | `ui/component/FloatingBottomBar.kt` |

## Hyper Icon Pack

MonoIcon 的 HyperOS 4 主题图标实现参考了 Hyper Icon Pack 的原生主题图标归档格式和系统读取流程。APK 内附 Hyper Icon Pack 的版权声明和 MIT 许可证全文。 [参考源码](https://github.com/yungui314/HyperIconPack/tree/546e79b9781f5127285e19d5b7edf1d01b4ece2d) [MIT 许可证](../app/src/main/assets/licenses/HyperIconPack-MIT.txt)

MonoIcon's HyperOS 4 theme icon implementation references Hyper Icon Pack for the native theme icon archive format and system loading behavior. The APK includes Hyper Icon Pack's copyright notice and the full MIT License text. [Reference source](https://github.com/yungui314/HyperIconPack/tree/546e79b9781f5127285e19d5b7edf1d01b4ece2d) [MIT License](../app/src/main/assets/licenses/HyperIconPack-MIT.txt)

## Lawnicons 图案 / Lawnicons artwork

The bundled 77 PNG masks are derived from the original SVG artwork in Lawnicons v2.18.0 at revision `74b428b747004046686cbe5e24218e958afe2eb9`. [Lawnicons artwork source](https://github.com/LawnchairLauncher/lawnicons/tree/74b428b747004046686cbe5e24218e958afe2eb9/svgs)

Copyright remains with the Lawnicons contributors. The artwork is licensed under Apache-2.0. MonoIcon rasterizes the artwork at 768×768 while retaining the asset names, component aliases and proportional layout. [Source and image hashes](../app/src/main/assets/lawnicons/raster-sources.json)

The APK includes the upstream licence and attribution alongside the artwork. [Bundled licence](../app/src/main/assets/lawnicons/LICENSE.txt) · [Bundled attribution](../app/src/main/assets/lawnicons/NOTICE.txt)

## Material Color Utilities

The Material 2025 and Material Design 3 colour implementations derive from Google's Material Color Utilities (2025 and 2021 specifications) and retain its Apache-2.0 copyright notices in the source files. [Upstream project](https://github.com/material-foundation/material-color-utilities) · [Apache-2.0 licence](licenses/Apache-2.0.txt)

## Miuix 与 Material 图标 / Miuix and Material icons

Miuix libraries, adapted Miuix example liquid-glass helpers and Material icons are distributed under their upstream Apache-2.0 terms. The adapted source files retain their attributions. [Miuix](https://github.com/compose-miuix-ui/miuix) · [Material icons](https://github.com/google/material-design-icons) · [Apache-2.0 licence](licenses/Apache-2.0.txt)
