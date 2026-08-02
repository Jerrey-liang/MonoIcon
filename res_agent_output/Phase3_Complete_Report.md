# MonoIcon Phase 3 — Complete Report

**日期**: 2026-08-02
**环境**: Android 16 + LSPosed 2.1.1 (7790) + HyperOS Launcher

---

## 1. Phase 3.0-3.2 — Native Monochrome + Foreground + Cache

| Phase | 功能 | 提交 |
|-------|------|------|
| 3.0 | 原生 `getMonochrome()` 隐藏 API 反射调用 | `4593ef6` |
| 3.1 | AdaptiveIcon foreground 提取 | `6659d21` |
| 3.2 | 缓存 source 类型隔离（NATIVE/FG/LUMA） | `cc0a7f8` |

---

## 2. Phase 3.3-3.4 — 文件夹预览初步修复

| Phase | 修复 | 提交 |
|-------|------|------|
| 3.3 | hook `refreshIconDrawable()` 替代 `setImageDrawable` | `3603738` |
| 3.4 | 移除 whiteFill，直接使用 raw mask (RGB=0) | `b0b8659` |

---

## 3. Phase 3.5-3.7 — 深度诊断

| Phase | 诊断内容 | 结论 | 提交 |
|-------|---------|------|------|
| 3.5 | Bitmap 内容诊断 | rgbNonZero=0，alpha正常。排除 Category A/C | `db93c14` |
| 3.6 | Drawable/ImageView 诊断 | view=0x0, scale=FIT_CENTER。排除 B → Category D | `46b3dc2` |
| 3.7 | Drawable 生命周期诊断 | 0x0 时不触发任何 draw()/setBounds()。0 个事件 | `c6a6f4b` |

---

## 4. Phase 3.8 — 延迟替换

**提交**: `505baff`

**设计**: view=0x0 时通过 `view.post()` 延迟替换。重启后所有 view 已 layout → `deferred=false` 立即执行。

**问题**: `com.xjs.ehviewer` 文件夹预览仍无变化。

---

## 5. Phase 3.9 — 替换路径身份诊断（当前）

**提交**: `87d8544`

### 日志分析

```
[FolderPreviewDeferred] width=0 height=0 deferred=true  ← 全部走延迟路径
[FolderPreviewFinalSet] class=LayerAdaptiveIconDrawable  ← ImageView 收到的是原始 drawable!
```

### 根因定位

1. **`[FolderPreviewBefore]` / `[FolderPreviewReplacement]` / `[FolderPreviewAfter]` 全部未出现** — 立即替换分支从未触发（view 始终 0x0）
2. **`[FolderPreviewFinalSet]` 全部是 `LayerAdaptiveIconDrawable`** — `chain.proceed()` 传入的是原始 drawable，不是我们的替换物
3. **延迟 runnable 从未执行** — `view.post(runnable)` 调度后 `apply` 日志从未出现。view 在 layout 前被回收或 post 回调不触发

### 修复方向

弃用延迟方案。直接通过 `chain.proceed(arrayOf(replacement))` 传入替换物。即使 view 是 0x0，`refreshIconDrawable` 内部的 `super.setImageDrawable()` 也会存储我们的 `BitmapDrawable`，等待 layout 后正常渲染。`chain.proceed()` 不应传原始 args——应始终传替换物。

---

## 6. Git 历史

```
87d8544 Phase 3.9: diagnose folder preview replacement path
505baff Phase 3.8: defer folder preview replacement until layout
c6a6f4b Phase 3.7: investigate folder preview drawable lifecycle
46b3dc2 Phase 3.6: add folder preview drawable diagnostics
db93c14 Phase 3.5: add folder preview rendering diagnostics
b0b8659 Phase 3.4: fix folder preview monochrome rendering target
3603738 Phase 3.3: fix folder preview monochrome rendering
cc0a7f8 Phase 3.2: isolate cache source type
6659d21 Phase 3.1: extract adaptive icon foreground
4593ef6 Phase 3.0: support native monochrome icon layer
```

## 7. 下一步 (Phase 3.10)

1. 弃用延迟/doRefer 方案
2. folder preview hook 中**始终**传 `chain.proceed(arrayOf(replacement))`——无论是否 layout
3. 移除 `WeakHashMap` pending 管理、`DiagnosticDrawable`、诊断日志
4. 提交 `Phase 3.10: always replace folder preview drawable regardless of view layout state`
