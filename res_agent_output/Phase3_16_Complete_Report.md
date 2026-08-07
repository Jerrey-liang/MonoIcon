# Phase 3.16 — Default Theme Mask Quality & Folder Preview Fix

## Date

2026-08-07

## Objective

1. Investigate why monochrome mask quality differs between default HyperOS theme and PNG icon theme
2. Fix folder preview icons not matching desktop icons
3. Fix non-LUMA folder preview icons showing as black rectangles

## Phase 3.16 — Diagnostics & Root Cause

### Drawable Structure Comparison

| | Default Theme | PNG Theme |
|---|---|---|
| Drawable type | `LayerAdaptiveIconDrawable` | `BitmapDrawable` / `FancyDrawable` |
| bg layer | `ColorDrawable` (transparent) | N/A |
| fg layer | `BitmapDrawable` (HyperOS mask, R=G=B=0) | N/A |
| Mask source | `FOREGROUND` (extracts HyperOS mask) | `LUMA` (single pass) |

### Root Cause: Double Mask Processing

```
Default Theme Pipeline (BEFORE FIX):
  APK AdaptiveIcon (color)
    → HyperOS IconProvider → LayerAdaptiveIconDrawable
      ├─ bg = ColorDrawable(transparent)
      └─ fg = BitmapDrawable(HyperOS mask, R=G=B=0)
    → processIconReplacement(): d.foreground → HyperOS mask
    → DrawableConverter.toBitmap(fg) → toLuminanceMask()
    → ★ 对已生成的 mask 再次做 mask → 信息二次损失

PNG Theme Pipeline (CORRECT):
  APK icon → BitmapDrawable/FancyDrawable → bypasses LayerAdaptiveIconDrawable
    → DrawableConverter.toBitmap(d) → toLuminanceMask()
    → ★ 单次处理 → 正常
```

### Evidence

| App | Default nonZeroAlpha | PNG nonZeroAlpha | Ratio |
|-----|---------------------|------------------|-------|
| WhatsApp | 3/289 (1%) | N/A | — |
| Gmail | 33/289 (11%) | N/A | — |
| Google Photos | 30/289 (10%) | N/A | — |

Default theme masks had almost no valid alpha pixels.

## Phase 3.16-A — Raw APK Drawable for Desktop Icons

### Fix

Store raw APK `AdaptiveIconDrawable` from Phase 3.15 Hook 7 in `IconDrawableCache`. In `processIconReplacement()`, check cache first before extracting foreground from `LayerAdaptiveIconDrawable`.

### Key Implementation

```
IconProvider.getActivityIcon()
  → info.getIcon(0) → raw AdaptiveIconDrawable
  → IconDrawableCache.put(identity, isolated copy via mutate())
  → extractEarlyIconColor()

processIconReplacement():
  → IconDrawableCache.get(identity)
  → if found: toRawBitmap(rawDrawable) + toLuminanceMask() [FULL render]
  → else: current foreground extraction [fallback]
```

### Results

| App | Before nonZeroAlpha | After nonZeroAlpha | Improvement |
|-----|-------------------|-------------------|-------------|
| WhatsApp | 3/289 (1%) | 239/256 (93%) | **80x** |
| MonoIcon | 44/289 (15%) | 291/324 (90%) | **6x** |
| Gboard | 60/289 (21%) | 291/324 (90%) | **4x** |
| Gmail | 33/289 (11%) | 80/324 (25%) | **2.4x** |
| Google Photos | 30/289 (10%) | 64/324 (20%) | **2x** |

## Phase 3.16 — Folder Preview Fix

### Problem

Folder preview icons had two issues:
1. **Mismatch with desktop**: Folder hook didn't use IconDrawableCache → still used double-processed mask
2. **Black rectangles**: `DrawableConverter.toBitmap()` returned null for non-LUMA types (FancyDrawable) → fell back to original monochrome mask

### Fixes

#### Hook 6 Upgrade

```kotlin
// Before: simple toBitmap → MonochromeGenerator
val mask = DrawableConverter.toBitmap(d)
// After: cache-first + generic fallback
val rawCached = IconDrawableCache.get(identity)
val mask = if (rawCached != null) {
    // Full render from raw APK AdaptiveIcon
    toLuminanceMask(toRawBitmap(rawCached))
} else {
    // Fallback: toBitmap → Canvas render
    DrawableConverter.toBitmap(d) ?: renderGenericToMask(d)
}
```

#### Hook 8 New: Small Folder Grid Icons

```
Class: FolderIconPreviewContainer1X1$PreviewIconView
Method: refreshIconDrawable(Drawable)
```

Same cache-aware pipeline as Hook 6. Covers 1x1 container small preview icons.

#### New Helpers

| Method | Purpose |
|--------|---------|
| `resolveFolderIconIdentity(view)` | Reflect `getMBuddyInfo()` → get package/component for cache key |
| `renderGenericToMask(drawable)` | Canvas render + toLuminanceMask for unsupported drawable types |

### Results

```
[FolderPreviewReplace] original=LayerAdaptiveIconDrawable replacement=BitmapDrawable rawCached=true
```

All folder preview icons now use raw APK drawable cache (rawCached=true) — matching desktop icon quality. No black rectangle regressions.

## All Hook Summary

| # | Hook | Purpose |
|---|------|---------|
| 1 | `MonochromeUtils.getMonochrome` | Generate/retrieve monochrome layer |
| 2 | `MonochromeUtils.isSupportMonochrome` | Diagnostic pass-through |
| 3 | `MonochromeUtils.isMonoEnable` | Diagnostic pass-through |
| 4 | `MonochromeUtils.getColor` | Diagnostic pass-through |
| 5 | `ShortcutIcon.setIconDrawable` | Desktop icon replacement + color extraction |
| 6 | `FolderPreviewIconView.refreshIconDrawable` | Folder preview icon replacement |
| 7 | `IconProvider.getActivityIcon` | Raw APK drawable + color cache |
| 8 | `PreviewIconView.refreshIconDrawable` | Small folder grid icon replacement |

## Files Changed

```
新增:
  app/src/main/java/com/jerrey/monoicon/color/IconDrawableCache.kt
  res_phase 3.16/HyperOS Monet Launcher.mtz

修改:
  app/src/main/java/com/jerrey/monoicon/hook/IconThemeHook.kt
    + diagMaskInput() / diagMaskRender() — 诊断
    + resolveFolderIconIdentity() — 文件夹图标识别
    + renderGenericToMask() — 通用回退渲染
    + installFolderSmallIconDrawable() — Hook 8
    + installFolderSetImageDrawable() — 升级支持 cache
    + processIconReplacement() — RAW_APK_DRAWABLE 优先路径
    + installGetActivityIcon() — 缓存隔离 raw drawable
    + extractOriginalIconColor() — 诊断增强

报告:
  Phase3_16_Mask_Quality_Diagnostics_Report.md
  Phase3_16_Complete_Report.md
```
