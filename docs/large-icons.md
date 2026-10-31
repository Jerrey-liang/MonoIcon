# 大图标与 Lawnicons 清晰度

## 形状约定

| 桌面尺寸 | 圆形图标开启 | 圆形图标关闭 |
| --- | --- | --- |
| 2×1、1×2 | 胶囊形，圆角半径为可见短边的一半 | 圆角矩形 |
| 2×2 | 圆形 | 圆角矩形 |

底板按目标宽高绘制，前景图案居中并等比缩放。圆形模式沿用原来的 AOSP 透明留白。普通 1×1 图标保留原有形状流程。

## HyperOS 3

以 `external_resources/launcher_2300_enh` 的桌面反编译代码为依据：`BigShortcutIcon.updateIconDrawableSize()` 将网格实际尺寸写入继承的 `mLauncherIconWidth`、`mLauncherIconHeight`；`ShortcutIcon.setIconDrawable()` 仍是公共入口。不能把格数直接当作像素宽高比。

`LauncherLargeIconHooks` 在绑定时传递实际宽高，同时覆盖正方形 2×2 以及关闭原生裁剪的大图标。`BigShortcutIcon.roundedIcon()` 原本会再次生成位图，模块对自己的 drawable 跳过这一步，保留实时配色和动画识别。卸载预览根据 `ShortcutInfo.spanX/spanY` 和预览尺寸处理。

大图标加载会绕过普通 `getActivityIcon()`。`LauncherLargeIconSource` 因此通过 `LauncherApps` 解析同一用户的准确组件，直接读取 APK 原始资源，沿用原生 monochrome → Lawnicons → AOSP 的优先级。解析失败时保留桌面图标。

旧实现使用 `ScaleToFit.FILL` 将单位圆拉成长方形，同时把方形前景铺满长方形边界。现在大图标直接绘制独立的圆角底板和等比前景，不再依赖矩形 AdaptiveIconDrawable 的缩放遮罩。

## HyperOS 4

原生桌面的大图标从 `large_icons` 中的尺寸资源目录加载，不能仅替换普通图标目录中的方形 PNG。普通 PNG 的导出分辨率也从 192 提高到 512。

尺寸来源是桌面原生 `FuncBridge` 已注册的网格方法。6144 的手机布局由当前 cell width、cell height 和 icon scale 推导，横竖屏以桌面返回的数值为准。读取失败、数据变化或未分析的平板/折叠屏布局保留系统大图标，不猜测固定 2:1 比例。

6144 的 `getCellWidth`、`getCellHeight` 返回 Bundle 的 double `result`；`getIconScales` 返回 float `portrait_icon_scale`、`landscape_icon_scale`。反汇编中 `IconConfigHandler.calIconSize @ 0x14e2ae0` 使用 `s = cellWidth × iconScale`，`GridController.getMiuiWidgetSizeSpec @ 0x88c428` 对应宽 `(spanX−2)×cellWidth + cellHeight+s`、高 `(spanY−1)×cellHeight+s`。这里只使用只读接口，未调用会修改图标尺寸的 `convertIconSize`。

这些方法不能直接通过公开 ContentProvider 调用。Profile 的 `icons.geometryBridge` 引用 `libapp_launcher.so` 中经过完整快照验证的原生入口；独立且唯一的工作线程通过现有 native Bundle ABI 请求标量，不访问 Dart 堆对象。应用与线程通过桌面 DE 目录中的请求/响应交换数据，绑定 PID、Profile 摘要和一次性 nonce，并检查两次采样一致。首次启用会在原生确认后有限重试，避免尺寸线程尚未启动时永久停留在普通图标批次。

每个可用的大图标按实际宽高单独导出。仅有成功导出的资源才参与目录及 PNG 重定向；桌面原有资源类型选择和加载流程继续工作。具体的 Profile 字段及原生接入约束见 [HyperOS 4 说明](hyperos4.md)。

HyperOS 4 会缓存整个大图标 Widget，现有 HD 图标刷新广播不会清除这层缓存。因此发布大图标或修改其造型后，需要使用应用中的「重启作用域」可靠加载新资源，发布状态也会提示这一点。批次内保存 `.large-signature`，重启复用前比较当前网格及资源配置；发生变化时重新导出，暂时无法读取桌面信息时保留此前的有效批次。

## Lawnicons

77 个内置图案均来自 Lawnicons v2.18.0 的原始 SVG，固定到提交 `74b428b747004046686cbe5e24218e958afe2eb9`，重新栅格化为 768×768。没有放大旧的 215×215 PNG，也没有更换图案或别名。

运行时保留 640～1024 像素的掩码和原有 `215/320` 布局比例，缩放开启过滤及抗锯齿。单通道 alpha 掩码继续以单通道保存到绘制缓存，缓存仍有字节上限。原始资源哈希、生成参数、许可证见 `app/src/main/assets/lawnicons/`；可用以下命令核对所有文件：

```powershell
node tools/render_lawnicons_masks.cjs --verify
```

## 验证范围

回归用例覆盖横向与纵向胶囊直边、透明留白、图案纵横比、2×2 默认圆角、drawable 克隆及边界变化、单通道抗锯齿边缘、原始图标的用户隔离和高清 Lawnicons 缓存。

按本次要求，仅做本机静态检查、编译及本机单元检查，Android 绘图测试只编译。没有安装模块、重启桌面或操作设备设置，编译成功不代表真机视觉验收通过。

2026-10-31 本机结果：53 项 JVM 测试、485 项 C++ 检查通过；Debug、Release 和 Android 测试 APK 编译通过。6144 的两个 ELF、38 份完整快照（28,464 字节）和 28 处原始指令匹配。两个 APK 内的 r7 Profile 与全部 77 个 Lawnicons PNG 均与源文件逐字节/哈希一致。
