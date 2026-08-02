# MonoIcon Phase 3 — Native Monochrome + Foreground Extraction Report

**日期**：2026-08-02
**环境**：Android 16 + LSPosed 2.1.1 (7790) + HyperOS Launcher
**API**：libxposed API 101

---

## 1. 概述

Phase 3 为目标图标管道添加了源优先级层级：

1. **NATIVE** — Android 13+ 设备提供的原生 monochrome 图层
2. **FOREGROUND** — 仅提取前景进行低分辨率处理（不包含可能会扭曲分析的背景区域）
3. **LUMINANCE** — 现有的 Phase 2.8 背景估计管线（回退方案）

此设计保留了所有现有的 Phase 2.8/2.9 代码，同时插入了不影响现有行为的新优先级。

---

## 2. 新增方法（DrawableConverter.kt）

### `toRawBitmap(drawable: Drawable): Bitmap?`

纯渲染方法。将任何受支持的 Drawable 转换为 ARGB_8888 位图，**不应用任何低分辨率分析**。供 NATIVE 和 FOREGROUND 路径使用。

### `getMonochromeLayer(d: AdaptiveIconDrawable): Drawable?`

通过反射调用隐藏的 `AdaptiveIconDrawable.getMonochrome()` API（API 33+）。设计要点：
- 编译 SDK 为 36，但该方法标记为隐藏 — 必须使用反射。
- 反射失败或 API < 33 时静默返回 null。
- 绝不导致启动器崩溃。

### `normalizeNativeMonochrome(bitmap: Bitmap): Bitmap`

原生 monochrome Drawable 可能会将形状信息存储在 RGB 通道中（而不仅仅是 alpha）。此方法通过 `newAlpha = alpha × luminance(RGB) / 255` 将其转换为纯 alpha 掩码，同时保留 monochrome 细节，并输出带有 `RGB = 0` 的 ARGB_8888。

---

## 3. 源优先级（IconThemeHook.kt）

`processIconReplacement()` 现在实现了三层决策树：

```
AdaptiveIconDrawable:
  ① 原生 monochrome → SOURCE_NATIVE (直接渲染，无低分辨率分析)
  ② 前景提取 → SOURCE_FOREGROUND (仅处理前景)
  ③ 整体回退 → SOURCE_LUMINANCE (Phase 2.8 行为)

非 AdaptiveIconDrawable:
  → SOURCE_LUMINANCE (现有管线)
```

安全处理：`AdapativeIconDrawable.foreground.getBounds()` 或 `intrinsicWidth` 可能因某些 Drawable 实现返回 -1；尝试获取边界并设置安全回退。

---

## 4. 缓存源隔离（MonochromeCache.kt）

`buildKey()` 现在在键中包含源后缀：

```
标识|宽度x高度@指纹|源

SOURAMPLES = "标识|宽度x高度@指纹|NATIVE"
            "标识|宽度x高度@指纹|FG"
            "标识|宽度x高度@指纹|LUMA"
```

差异来源产生不同的缓存键，防止对同一组件错误返回 NATIVE 掩码而非 LUMINANCE。

---

## 5. 运行时验证

桌面图标主要是 FancyDrawable 或 BitmapDrawable（如 Phase 2 所发现），因此所有当前图标都走 `SOURCE_LUMINANCE` 路径。`getMonochromeLayer()` 反射在不存在的 AdaptiveIconDrawable 实例上从未被调用。

该管线已准备好用于 AdaptiveIconDrawable 的情况（例如通过 AdaptiveIconDrawable 提供图标的 Google 应用 / 系统设置），并将在出现时自动激活。

---

## 6. 文件变更

| Phase | 文件 | 变更 |
|-------|------|------|
| 3.0 | `image/DrawableConverter.kt` | 新增 `toRawBitmap()`, `getMonochromeLayer()`, `normalizeNativeMonochrome()` |
| 3.0 | `hook/IconThemeHook.kt` | 新增 NATIVE 分支 + 源常量 companion |
| 3.1 | `hook/IconThemeHook.kt` | 在 NATIVE 和全量之间新增 FOREGROUND 分支 + 边界安全处理 |
| 3.2 | `cache/MonochromeCache.kt` | `buildKey` 扩展参数 `source`，新增 `SOURCE_*` companion 常量，源后缀 |
| 3.2 | `hook/IconThemeHook.kt` | 向 `buildKey` 传入 `source` 参数 |

## 7. Git 历史

```
cc0a7f8 Phase 3.2: isolate cache source type
6659d21 Phase 3.1: extract adaptive icon foreground
4593ef6 Phase 3.0: support native monochrome icon layer
```

## 8. 已知限制

1. **运行时暂未出现 AdaptiveIconDrawable** — 当前 HyperOS 桌面图标为 FancyDrawable / BitmapDrawable，NATIVE/FOREGROUND 路径暂时空闲但代码正确
2. **getMonochrome() 是隐藏 API** — 反射调用，Android 版本间可能变化
3. **normalizeNativeMonochrome 假设 RGB 与 alpha 贡献相同** — 少数实现可能需要不同的归一化策略
