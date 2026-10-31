# KernelSU interface adaptations

The MonoIcon interface adapts source from [tiann/KernelSU](https://github.com/tiann/KernelSU/tree/2fe188310309bd7a7c9b5a80beed6f14469c6cd2), revision `2fe188310309bd7a7c9b5a80beed6f14469c6cd2`.

KernelSU's README licenses its manager and other non-kernel code under **GPL-3.0-or-later**. The adapted navigation, home, settings, theme, About, background effects, dialogs and blur helpers retain that license. The full license is included in [licenses/KernelSU-GPL-3.0.txt](licenses/KernelSU-GPL-3.0.txt). Copyright remains with KernelSU and its contributors. These files have been modified for MonoIcon, its LSPosed status, compatibility profiles, release feed, logs and project links. Distributing a combined build containing these adaptations requires complying with GPL-3.0-or-later, including providing the corresponding source. Existing independently authored MonoIcon sources retain their original notices.

KernelSU's `FloatingBottomBar.kt` explicitly identifies its compose-miuix-ui origin as Apache-2.0; that notice is retained in `LiquidGlassNavigationBar.kt`. Miuix libraries and Material icons retain their upstream Apache-2.0 terms.

## Source map

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
