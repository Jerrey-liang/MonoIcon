# 许可证与第三方声明 / Licences and third-party notices

本文件汇总 MonoIcon 的许可证、第三方来源及署名。MonoIcon 采用 **GNU GPL 第 3 版或任何后续版本（GPL-3.0-or-later）**，许可声明见 [LICENSE](LICENSE)，完整条款见根目录的 [LICENSE](../LICENSE)。第三方代码与素材保留各自的许可证和署名；分发组合构建时须遵守 GPL-3.0-or-later，包括提供对应源代码。

MonoIcon is licensed under the **GNU General Public License, version 3 or (at your option) any later version (GPL-3.0-or-later)**; see the [project licence notice](LICENSE) and the full [licence text](../LICENSE). Third-party components retain their own licence terms and attributions. Distributing a combined build requires complying with GPL-3.0-or-later, including providing the corresponding source.

## KernelSU interface adaptations

The MonoIcon interface adapts source from [tiann/KernelSU](https://github.com/tiann/KernelSU/tree/2fe188310309bd7a7c9b5a80beed6f14469c6cd2), revision `2fe188310309bd7a7c9b5a80beed6f14469c6cd2`.

KernelSU's README licenses its manager and other non-kernel code under **GPL-3.0-or-later**. The adapted navigation, home, settings, theme, About, background effects, dialogs and blur helpers retain that license. The full license is included in [licenses/KernelSU-GPL-3.0.txt](licenses/KernelSU-GPL-3.0.txt). Copyright remains with KernelSU and its contributors. These files have been modified for MonoIcon, its LSPosed status, compatibility profiles, release feed, logs and project links. Distributing a combined build containing these adaptations requires complying with GPL-3.0-or-later, including providing the corresponding source. Upstream copyright and licence notices are retained.

KernelSU's `FloatingBottomBar.kt` explicitly identifies its compose-miuix-ui origin as Apache-2.0; that notice is retained in `LiquidGlassNavigationBar.kt`. Miuix libraries and Material icons retain their upstream Apache-2.0 terms.

### Source map

| MonoIcon | KernelSU manager source |
| --- | --- |
| `ui/screens/SettingsScreen.kt` | `ui/MainActivity.kt`, `ui/component/bottombar/BottomBar.kt`, `BottomBarMiuix.kt` |
| `ui/screens/OverviewScreen.kt`, `HomeSystemInfo.kt`, `HomeTopBarActions.kt` | `ui/screen/home/HomeMiuix.kt`, home view model, reboot popup |
| `ui/screens/ModuleSettingsScreen.kt`, `ProfileSettings.kt` | `ui/screen/settings/SettingsMiuix.kt`, dialog components |
| `ui/screens/ConfigurationRuntimeContent.kt` | `ui/screen/settings/SettingsMiuix.kt` card and preference layout |
| `ui/screens/ThemeSettingsScreen.kt`, `ui/theme/Theme.kt` | `ui/screen/colorpalette/ColorPaletteScreenMiuix.kt`, `ui/theme/MiuixTheme.kt` |
| `ui/screens/LawniconsAppsScreen.kt`, `AppIconSettingsScreen.kt`, `IconStyleScreen.kt`, `IconStyleParameterScreen.kt`, `IconStylePreview.kt` | `ui/screen/superuser/SuperUserMiuix.kt`, `ui/screen/settings/SettingsMiuix.kt`, `ui/screen/colorpalette/ColorPaletteScreenMiuix.kt`, Miuix preference components |
| `ui/screens/KernelSearch.kt` | `ui/component/miuix/SuperSearchBar.kt`, `ui/component/SearchStatus.kt` |
| `ui/screens/AboutScreen.kt`, `ui/settings/effect/` | `ui/screen/about/AboutMiuix.kt`, background effect components |
| `ui/settings/SendLogDialog.kt` | `ui/component/miuix/SendLogDialog.kt` |
| `ui/liquid/BlurredBar.kt` | `ui/util/BlurExt.kt` |
| `ui/liquid/LiquidGlassNavigationBar.kt` | `ui/component/FloatingBottomBar.kt` |

KernelSU names, root operations, sponsorship and learning links are not presented as MonoIcon functionality.

## Lawnicons artwork

The bundled 77 PNG masks are derived from the original SVG artwork in [LawnchairLauncher/lawnicons v2.18.0](https://github.com/LawnchairLauncher/lawnicons/tree/74b428b747004046686cbe5e24218e958afe2eb9/svgs), revision `74b428b747004046686cbe5e24218e958afe2eb9`.

Copyright remains with the Lawnicons contributors. The artwork is licensed under Apache-2.0. MonoIcon rasterizes the artwork at 768×768 while retaining the asset names, component aliases and proportional layout. The source and image hashes are recorded in `app/src/main/assets/lawnicons/raster-sources.json`.

The APK includes the upstream licence and attribution alongside the artwork. These asset files are distribution resources; the public documentation is collected here.

## Material Color Utilities

The Material 2025 and Material Design 3 colour implementations derive from Google's Material Color Utilities (2025 and 2021 specifications) and retain its Apache-2.0 copyright notices in the source files. See the [upstream project](https://github.com/material-foundation/material-color-utilities) and the [Apache-2.0 licence](licenses/Apache-2.0.txt).

## Miuix and Material icons

Miuix libraries, adapted Miuix example liquid-glass helpers and Material icons are distributed under their upstream Apache-2.0 terms. Source attributions in adapted files are retained. See [Miuix](https://github.com/compose-miuix-ui/miuix), [Material icons](https://github.com/google/material-design-icons) and the [Apache-2.0 licence](licenses/Apache-2.0.txt).
