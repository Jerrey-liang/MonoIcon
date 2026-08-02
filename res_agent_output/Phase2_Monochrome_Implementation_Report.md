# MonoIcon Phase 2 — Monochrome 图标生成实现报告

**日期**：2026-08-01
**环境**：Android 16 + LSPosed 2.1.1 (7790) + HyperOS Launcher (com.miui.home)
**API**：libxposed API 101（Modern ONLY）

---

## 1. 目标

用最小、最安全的改动，证明 HyperOS Launcher 接受我们生成的 Monochrome Drawable，并让桌面图标显示为 Material You 风格的单色剪影。

**核心原则**：正确性 → 运行时验证 → 兼容性 → 保持原始行为 → 性能优化。每阶段单 git commit + 验证报告，验证失败不自动继续。

---

## 2. 关键架构发现（Phase 2.3 运行时验证）

### 2.1 `getMonochrome()` 在 HyperOS 桌面从不被调用

按逆向分析计划 hook 了 `MonochromeUtils.getMonochrome()`，但运行时发现它**从未被调用**。

**原因**：`IconProvider.getActivityIcon()` (L61-75) 的图标加载优先级：
1. `MamlUtils.getIconDrawable()` → `FancyDrawable`（MAML 动态图标）— 桌面多数图标走这里
2. `launcherActivityInfo.getIcon(0)` → `BitmapDrawable`（176x176，已转换）
3. 仅当 `instanceof AdaptiveIconDrawable` 才走 `getLayerAdaptiveDrawable()` → `getMonochrome()`

桌面图标几乎都是 `FancyDrawable`/`BitmapDrawable`，`getMonochrome()` 路径基本不触发。

### 2.2 真正入口：`setIconDrawable(Drawable, Bitmap)`

`ShortcutIcon.setIconDrawable()` (L948) 是**每个图标显示时必定调用**的方法，直接 `applyCompoundDrawables()` 把 drawable 设置到 TextView。

**方案调整**（用户确认方案 A）：hook `setIconDrawable`，通过反射 `thisObject.getShortcutInfo().getPackageName()` 获取包名，替换 drawable 为生成的 monochrome 版本。

---

## 3. 掩码生成策略演进

### 3.1 阶段 1：纯 Alpha Mask（失败）

`toWhiteAlphaMask`：保留原图 alpha，RGB 填白。

**结果**：图标变成**全白方块，无图案**。

**根因**：
1. `setIconDrawable` 路径无 launcher 黑色 tint（与 getMonochrome 路径不同），白色 RGB 直接显示
2. 176x176 BitmapDrawable 图标**全不透明**（alpha=255 覆盖整个区域），alpha 通道不携带形状信息

### 3.2 阶段 2：亮度掩码（成功，用户确认）

`toLuminanceMask`：alpha 由像素亮度派生（深色→不透明，浅色→透明），RGB 填黑。

**结果**：图标显示为**黑色剪影，带灰度**（深色内容保留，浅色背景变透明）。

**关键点**：`setIconDrawable` 路径无 tint，掩码必须自带颜色 → RGB 填黑是自足的。

---

## 4. 图像处理流水线

```
Drawable (FancyDrawable / BitmapDrawable / AdaptiveIconDrawable)
    ↓
DrawableConverter.toBitmap(drawable)
    ├── BitmapDrawable → copy inner bitmap (ARGB_8888)
    ├── VectorDrawable → Canvas 渲染
    └── AdaptiveIconDrawable → Canvas 渲染整图（background+foreground+mask）
    ↓
toLuminanceMask(rendered)
    ├── 每像素: luminance = 0.299R + 0.587G + 0.114B
    ├── alpha = (255 - luminance) * 原alpha / 255  (深色→不透明)
    └── RGB = 0x000000 (黑)
    ↓
MonochromeGenerator.create(mask) → BitmapDrawable
    ↓
setIconDrawable interceptor 替换 drawable 参数
    ↓
Launcher 显示黑色单色剪影
```

---

## 5. 性能优化（Phase 2.5-2.6）

### 5.1 优化手段

| 优化 | 说明 | 效果 |
|------|------|------|
| 反射 Method 缓存 | `resolvePackageName` 首次找到 `getShortcutInfo`/`getPackageName` 后缓存，避免每次 `javaClass.methods` 全量扫描 | 最大单项优化 |
| 静态 Canvas 复用 | `DrawableConverter` 用 `private val reusableCanvas` + synchronized | 减少每次分配 |
| packageName 缓存 | `MonochromeCache` 基于 `packageName|widthxheight` 的 LruCache(512) | 避免同一图标重复转换 |

### 5.2 性能数据

| 指标 | 优化前 (Phase 2.3b) | 优化后 (Phase 2.5) |
|------|---------------------|---------------------|
| 平均成本 | ~7ms | **1.2ms** |
| 最大成本 | 16ms | **3ms** |
| 最小成本 | 3ms | **1ms** |

**提升约 6 倍**，达到计划的 `<5ms` 目标。

---

## 6. 异常处理（Phase 2.7）

- `processIconReplacement()` 提取生成逻辑，整体 `try/catch(Throwable)`，任何异常回落原始 drawable
- 所有 interceptor 用 `XposedInterface.ExceptionMode.PROTECTIVE`
- 日志降噪：`setIconDrawable` 每次仅一行统计日志（之前两行 + 完整 drawable 描述）
- 删除无用的 `describeDrawable` helper

**验证**：注入错误场景无崩溃；logcat 不再刷屏。

---

## 7. 最终文件清单

**新增**：
- `image/DrawableConverter.kt` — Drawable→亮度掩码 Bitmap（3 类型）
- `image/MonochromeGenerator.kt` — Bitmap→BitmapDrawable 包装
- `cache/MonochromeCache.kt` — packageName|size 的 LruCache

**修改**：
- `hook/IconThemeHook.kt` — setIconDrawable 替换 + 反射包名 + 缓存 + 异常

**其他**：
- `.gitignore` — 增加 IDE 文件、日志目录忽略
- `res_agent_output/` — 报告目录（本文件所在）

---

## 8. Git 提交历史

```
796e35d  Phase 2.7: 异常处理与日志降噪
bc54849  Phase 2.6: 基于 packageName 的结果缓存
84a1506  Phase 2.5: 性能优化（反射缓存+静态Canvas复用）
c3ecdae  Phase 2.3b: 亮度掩码替代 alpha 掩码
b0b5700  Phase 2.3: getMonochrome 进入 monochrome 路径
382752e  Phase 2.2: Monochrome Drawable 包装
e4b3b09  Phase 2.1: Drawable→Bitmap（整图 ARGB_8888 白色填充）
3853ec7  fix: 修复 XposedProvider ClassNotFoundException 闪退
225ff4c  chore: 初始化 MonoIcon 项目 — LSPosed Hook 已打通
```

---

## 9. 已知限制

1. **FancyDrawable（MAML 图标）未转换** — 微信/QQ/相机等系统应用图标是 FancyDrawable，DrawableConverter 不支持 → 回落彩色
2. **`getMonochrome()`/MonochromeUtils 类完全绕过** — 4 个 hook 实际为惰性（保留以防将来路径变化）
3. **Material You 颜色尚未应用** — 当前是纯黑剪影，主题色着色是后续阶段
4. **缓存生命周期** — 进程重启缓存清空（内存缓存），未做持久化

---

## 10. 下一步建议

| 方向 | 说明 |
|------|------|
| **Phase 3** | 扩展 FancyDrawable/MAML 图标支持（覆盖系统应用） |
| **Phase 4** | Material You 主题色应用（getColor 或生成时着色） |
| **Phase 5** | 配置界面（启用开关、排除列表、图标风格） |
| **Phase 6** | 应用白名单/黑名单、自定义图标 |

---

## 11. 假设清单（运行时验证）

| 假设 | 状态 |
|------|------|
| 整图渲染 AdaptiveIconDrawable 足够（vs foreground-only） | 待 Phase 3 验证 |
| ARGB_8888 黑色填充 BitmapDrawable 被 Launcher 接受 | ✅ 已验证（黑色剪影） |
| 亮度掩码方向（深色→前景）对多数图标正确 | ✅ 已验证（个别深底浅标图标可能反转） |
| packageName 缓存 key 稳定且唯一 | ✅ 已验证（不同包不串） |
| LruCache 512 条目足以覆盖桌面 | 待验证（当前应用数未达上限） |
