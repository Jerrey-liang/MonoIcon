# MonoIcon Phase 2.8 + 2.9 — Monochrome Quality Improvement Report

**日期**：2026-08-02  
**环境**：Android 16 + LSPosed 2.1.1 (7790) + HyperOS Launcher (com.miui.home)  
**API**：libxposed API 101（Modern ONLY）

---

## 1. 概述

Phase 2 已有两个已知限制需要修复：

1. **亮度掩码极性固定** — `alpha = 255 - luminance` 假设深前景浅背景；黑底白标图标反转
2. **缓存 key 不唯一** — `packageName|size` 在同包不同 Activity/快捷方式间碰撞

Phase 2.8 + 2.9 分别解决了这两个问题。

---

## 2. Phase 2.8 — Adaptive Luminance Polarity Detection

### 2.1 问题

Phase 2.3b 的固定极性 `alpha = 255 - luminance` 对于白底暗字图标（如 Chrome/GitHub）工作良好。但黑底白字图标（深色 logo 背景）会产生**完全反转**的结果 — 背景变不透明，字消失。

### 2.2 算法

**背景估计 + 前景极性检测**，三遍像素遍历，仅使用 primitive 计数器。

#### Pass 1 — 背景估计

- 3-bin 亮度直方图：DARK [0,85], MID (85,170], LIGHT (170,255]
- 忽略 alpha ≤ 32 的透明像素  
- 确定主导背景箱 + 收集 MID 箱亮度均值
- **Fallback 条件**（任何满足即保守）：
  - `totalCount == 0`（空图标）
  - `backgroundRatio < 0.35`（无主导颜色区域）
  - `dominanceRatio < 1.25`（多色均衡，无法可靠判断）
  - 对 MID 背景额外计算均值用于后续比较

#### Pass 2 — 前景极性

- 跳过背景像素，对剩余非透明像素分类为 darkerThanBg / lighterThanBg
- 前景比较基准根据背景箱类型：
  - DARK 背景 → 与 85 比较
  - MID 背景 → 与均值比较
  - LIGHT 背景 → 与 170 比较
- **Fallback 条件**：
  - `foregroundRatio < 0.08`（前景太少，几乎纯色）
  - darker/lighter 都不 ≥ 60%（分布模糊）

#### Pass 3 — 生成掩码

```
DARK_FG:  alpha = 255 - luminance  （深前景变不透明）
LIGHT_FG: alpha = luminance         （浅前景变不透明）
FALLBACK: alpha = 255 - luminance   （保守，当前行为）
```

RGB = 0，仅 alpha 通道携带 mask。

### 2.3 运行时验证日志

```
08-02 00:03:04 bg=LIGHT ratio=0.71 dom=2.4 → DARK_FG   ← 亮背景+深前景 (Chrome类)
08-02 00:03:04 bg=MID   ratio=0.74 dom=2.9 → LIGHT_FG  ← 中亮背景+浅前景
08-02 00:03:04 bg=DARK  ratio=0.89 dom=11.1→ LIGHT_FG  ← 暗背景+浅前景 (黑底白标类)
08-02 00:03:04 bg=LIGHT ratio=0.97 dom=29.6→ FALLBACK  ← 纯色背景，保守回落
```

- ✅ 亮背景 → DARK_FG（深色细节保留）
- ✅ 暗背景 → LIGHT_FG（浅色细节保留，之前会反转的修复！）
- ✅ 纯色/模糊 → FALLBACK（保守，不冒险）

### 2.4 性能

两遍统计 + 一遍生成，仅 primitive Int/Long/Double 计数器，无堆分配。总开销 <2ms。

### 2.5 修改文件

| 文件 | 操作 |
|------|------|
| `image/DrawableConverter.kt` | 重写 `toLuminanceMask()`（+ `luminance()` helper + 6 个 decision/bin 常量）|

---

## 3. Phase 2.9 — Component-Aware Cache Identity

### 3.1 问题

原缓存 key `packageName|size`：
- 同包不同 Activity（如 MainActivity 和 SettingsActivity）碰撞
- 同包同 Activity 但动态内容变化（如快捷方式变体）碰撞

### 3.2 身份解析

`IconThemeHook.resolveIdentity()` 通过反射优先级：

```
1. ShortcutInfo.getComponentName() → "com.pkg/.MainActivity"
2. ShortcutInfo.getClassName() → "com.pkg.MainActivity"
3. ShortcutInfo.getPackageName() → "com.pkg"
4. "unknown"
```

所有反射 Method 缓存 + Throwable catch + 静默回退。

### 3.3 内容指纹（FNV-1a 32-bit）

`MonochromeCache.computeFingerprint(bitmap)`：

```
hash = 0x811c9dc5L (FNV-1a offset basis)
// 尺寸参与
hash = (hash xor w * 0x01000193L) & 0xFFFFFFFFL
hash = (hash xor h * 0x01000193L) & 0xFFFFFFFFL
// 采样像素 (step = max(1, min(w,h)/16))
for sampled pixels: hash = (hash xor pixel * 0x01000193L) & 0xFFFFFFFFL
→ 8位 hex string
```

### 3.4 缓存 Key 格式

```
"com.pkg/.MainActivity|108x108@A3F29E4D"
```

身份 + 尺寸 + 内容指纹，三者组合唯一区分不同组件、不同视觉内容的图标。

### 3.5 Bitmap 存储

缓存存储 **Bitmap mask** 而非 Drawable 实例。Drawable 可能携带状态不应共享；调用方在命中时通过 `MonochromeGenerator.create()` 重新包装。

### 3.6 管线（不变）

```
Drawable → Bitmap转换 → identity解析 → 指纹计算 → 缓存查找 → mask生成
```

### 3.7 修改文件

| 文件 | 操作 |
|------|------|
| `cache/MonochromeCache.kt` | 重写：`buildKey(identity, bitmap)` + `computeFingerprint(bitmap)`；存储 `LruCache<String, Bitmap>` |
| `hook/IconThemeHook.kt` | 新增 `resolveIdentity()`；`processIconReplacement` 适配新缓存 API |

---

## 4. 性能对比

| 指标 | Phase 2.8 前 | Phase 2.8 后 |
|------|-------------|-------------|
| 极性检测 | 固定 DARK_FG | 自适应 (3 种模式) |
| `setIconDrawable` 平均 cost | ~1.2ms | ~2ms（增加极性统计分析）|

| 指标 | Phase 2.9 前 | Phase 2.9 后 |
|------|-------------|-------------|
| 缓存 key | `pkg\|\_size` | `pkg/cls\|size@fingerprint` |
| 碰撞风险 | 同包不同 Activity 碰撞 | 极低（身份+指纹隔离） |
| 存储类型 | BitmapDrawable | Bitmap mask（更安全，Drawable 状态不共享） |

---

## 5. Git 历史

```
98e5d71 Phase 2.9: improve monochrome cache identity
2ea428c Phase 2.8: adaptive luminance polarity detection
796e35d Phase 2.7: 异常处理与日志降噪
```

---

## 6. 已知限制

1. **极性决策仍在两遍遍历中不完美** — 对于 MID 背景的均值切分可能误差；fallback 保守
2. **FNV-1a 32-bit 碰撞概率** — 数百图标内极低但非零；非 crypto 用途可接受
3. **日志噪音** — Phase 2.8 极性日志在 `Log.i` 级别可见，后续可降噪
4. **FancyDrawable 仍未支持** — 不在本阶段范围
