# 许可证与第三方声明 / Licences and third-party notices

本文件汇总 MonoIcon 的许可证、第三方来源及署名。项目原有独立代码的 Apache-2.0 许可证全文见 [LICENSE](LICENSE)。第三方代码与素材遵循各自的许可证；包含 GPL 授权界面代码的组合分发还须满足下述 GPL 条件。

Original, independently authored MonoIcon code retains its Apache-2.0 terms; see the full [licence](LICENSE). Third-party components retain their own terms. A combined distribution containing the GPL interface adaptations must also satisfy the GPL requirements below.

## KernelSU interface adaptations

The MonoIcon interface adapts source from [tiann/KernelSU](https://github.com/tiann/KernelSU/tree/2fe188310309bd7a7c9b5a80beed6f14469c6cd2), revision `2fe188310309bd7a7c9b5a80beed6f14469c6cd2`.

KernelSU's README licenses its manager and other non-kernel code under **GPL-3.0-or-later**. The adapted navigation, home, settings, theme, About, background effects, dialogs and blur helpers retain that license. The full license is included in [licenses/KernelSU-GPL-3.0.txt](licenses/KernelSU-GPL-3.0.txt). Copyright remains with KernelSU and its contributors. These files have been modified for MonoIcon, its LSPosed status, compatibility profiles, release feed, logs and project links. Distributing a combined build containing these adaptations requires complying with GPL-3.0-or-later, including providing the corresponding source. Existing independently authored MonoIcon sources retain their original notices.

KernelSU's `FloatingBottomBar.kt` explicitly identifies its compose-miuix-ui origin as Apache-2.0; that notice is retained in `LiquidGlassNavigationBar.kt`. Miuix libraries and Material icons retain their upstream Apache-2.0 terms.

### Source map

| MonoIcon | KernelSU manager source |
| --- | --- |
| `ui/screens/SettingsScreen.kt` | `ui/MainActivity.kt`, `ui/component/bottombar/BottomBar.kt`, `BottomBarMiuix.kt` |
| `ui/screens/OverviewScreen.kt`, `HomeSystemInfo.kt`, `HomeTopBarActions.kt` | `ui/screen/home/HomeMiuix.kt`, home view model, reboot popup |
| `ui/screens/ModuleSettingsScreen.kt`, `ProfileSettings.kt` | `ui/screen/settings/SettingsMiuix.kt`, dialog components |
| `ui/screens/ThemeSettingsScreen.kt`, `ui/theme/Theme.kt` | `ui/screen/colorpalette/ColorPaletteScreenMiuix.kt`, `ui/theme/MiuixTheme.kt` |
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

The Material 2025 colour implementation derives from Google's Material Color Utilities and retains its Apache-2.0 copyright notices in the source files. See the [upstream project](https://github.com/material-foundation/material-color-utilities) and the [Apache-2.0 licence](LICENSE).

## Miuix and Material icons

Miuix libraries and Material icons are distributed under their upstream Apache-2.0 terms. Source attributions in adapted files are retained. See [Miuix](https://github.com/compose-miuix-ui/miuix) and [Material icons](https://github.com/google/material-design-icons).
