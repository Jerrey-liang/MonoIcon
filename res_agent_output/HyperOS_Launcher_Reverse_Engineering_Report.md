# HyperOS Launcher Reverse Engineering Report

**Target**: HyperOS Launcher `RELEASE-7.00.00.2259-04301440` (com.miui.home)
**Platform**: HyperOS 3 / Android 16
**Date**: 2026-07-28
**Source**: Decompiled via JADX, 6874 Java files total

---

## 1. Executive Summary

HyperOS Launcher has a **complete monochrome icon system** already built-in, exposed through `MonochromeUtils`. However, it has two major limitations:

1. **Monochrome icons only work when the app provides a native monochrome layer** (`AdaptiveIconDrawable.getMonochrome()` — Android 13+ hidden API). Apps without this layer get standard colored icons.
2. **Colors are static presets** (blue, green, purple, brown) — not dynamic Material You colors from wallpaper.

The optimal LSPosed hook strategy is to **hijack `MonochromeUtils`** at 4 static methods, supplying our own dynamically-generated monochrome masks and Material You colors. This requires **zero changes to the icon pipeline** — the launcher already handles all rendering, caching, and display correctly.

### Key Numbers

| Metric | Value |
|--------|-------|
| Total decompiled Java files | 6,874 |
| Launcher-specific files (com.miui.home) | ~2,000 |
| Files in `icon/` package | 31 |
| Files using `AdaptiveIconDrawable` | 17 |
| Files using `MonochromeUtils` | 6 |
| Material You / WallpaperColors references | **0** (not used!) |

---

## 2. Launcher Architecture

### 2.1 Key Packages

| Package | Files | Role |
|---------|-------|------|
| `com.miui.home.launcher` | 767 | Main launcher logic, BaseLauncher, views |
| `com.miui.home.recents` | 420 | Recent apps / overview |
| `com.miui.home.common` | 232 | Shared utilities, drawables, device config |
| `com.miui.home.model` | 137 | Data models, IconCache |
| `com.miui.home.folder` | 111 | Folder views and logic |
| `com.miui.home.settings` | 43 | Settings screens |
| `com.miui.home.icon` | **31** | **Icon loading & monochrome system** |

### 2.2 Key Classes

| Class | Location | Role |
|-------|----------|------|
| `BaseLauncher` | `launcher/BaseLauncher.java` | Main Activity, lifecycle, content observers |
| `IconCache` | `model/core/IconCache.java` | Central icon cache, calls IconProvider |
| `IconProvider` | `icon/IconProvider.java` | Icon loading pipeline, monochrome wrapping |
| `MonochromeUtils` | `icon/MonochromeUtils.java` | Monochrome state & color management |
| `LayerAdaptiveIconDrawable` | `common/drawable/LayerAdaptiveIconDrawable.java` | Custom Drawable wrapping AdaptiveIcon with layers |
| `IconPalette` | `launcher/graphics/IconPalette.java` | Icon color analysis using AndroidX Palette |
| `ShortcutIcon` | `launcher/ShortcutIcon.java` | View that displays icons on desktop |
| `IconCustomizeFragment` | `settings/IconCustomizeFragment.java` | Settings UI for monochrome colors |

---

## 3. Complete Icon Loading Pipeline

### 3.1 Pipeline Overview

```
[App install/update or Launcher start]
    │
    ▼
IconCache.updateIconsForPkg(packageName, user)
    │  LauncherAppsCompat.getActivityList() → List<LauncherActivityInfo>
    │
    ▼
IconCache.addIconToMemCache(LauncherActivityInfo)
    │  [Line 131-143, IconCache.java]
    │
    ▼
IconProvider.getActivityIcon(LauncherActivityInfo)
    │  [Line 52-90, IconProvider.java]
    │
    ├──(1) MamlUtils.getIconDrawable()          ← fancy XML/MAML icons
    ├──(2) IconCustomizer.getIcon() (reflect)    ← MIUI custom icons (pre-Android T)
    ├──(3) launcherActivityInfo.getIcon(0)      ← Native Android icon (FALLBACK)
    │
    ▼
LayerAdaptiveIconDrawableUtils.isSupport()
    │  Returns: true if API ≥ 26 (always true for minSdk=31)
    │
    ▼
IconProvider.getLayerAdaptiveDrawable(LauncherActivityInfo, AdaptiveIconDrawable, badge)
    │  [Line 92-101, IconProvider.java]
    │
    ├── MonochromeUtils.getMonochrome(adaptiveIconDrawable)
    │     ├── isSupportMonochrome()?   → Android 13+ AND International build
    │     ├── isMonoEnable()?           → User enabled in settings
    │     ├── getColor() != DEFAULT?    → Valid color selected
    │     └── ReflectUtils.invokeObject(aiDrawable, "getMonochrome")
    │           → Calls Android framework HIDDEN API AdaptiveIconDrawable.getMonochrome()
    │
    ├── IF monochrome != null (app HAS monochrome layer):
    │     ColorDrawable(MonochromeUtils.getColor())   ← background color
    │     monochrome.mutate().setTint(0xFF000000)      ← black foreground silhouette
    │     return LayerAdaptiveIconDrawable(colorBg, blackFg, badge, componentName)
    │
    └── IF monochrome == null (app has NO monochrome layer):
          return LayerAdaptiveIconDrawable(adaptiveIconDrawable, badge, componentName)
          ↑ Standard 2-layer adaptive icon (background + foreground)
    │
    ▼
IconCache.createBadgedIconBitmap(Drawable, UserHandle)
    │  Wraps Drawable in DrawableInfo (with badge if needed)
    │
    ▼
mCache.put(ComponentKey, DrawableInfo)       ← stored in HashMap-based DrawableCache
    │
    ▼
[Display] ShortcutIcon.setIconDrawable() → LauncherIconImageView → LayerAdaptiveIconDrawable.draw()
```

### 3.2 Pipeline Verification

Every edge confirmed from source code:

| Step | Caller | Callee | File:Line |
|------|--------|--------|-----------|
| 1 | `ModelApplicationScopeObjectProvider` | `IconCache(context, IconProvider)` | `ModelApplicationScopeObjectProvider.java:31` |
| 2 | `IconCache.updateIconsForPkg()` | `mLauncherApps.getActivityList()` | `IconCache.java:89` |
| 3 | `IconCache.addIconToMemCache()` | `mIconProvider.getActivityIcon()` | `IconCache.java:137` |
| 4 | `IconProvider.getActivityIcon()` | `launcherActivityInfo.getIcon(0)` | `IconProvider.java:72` |
| 5 | `IconProvider.getActivityIcon()` | `getLayerAdaptiveDrawable()` | `IconProvider.java:75` |
| 6 | `IconProvider.getLayerAdaptiveDrawable()` | `MonochromeUtils.getMonochrome()` | `IconProvider.java:93` |
| 7 | `IconProvider.getLayerAdaptiveDrawable()` | `MonochromeUtils.getColor()` | `IconProvider.java:97` |
| 8 | `MonochromeUtils.getMonochrome()` | `ReflectUtils.invokeObject(ai, "getMonochrome")` | `MonochromeUtils.java:26` |
| 9 | `IconCache.addIconToMemCache()` | `createBadgedIconBitmap()` | `IconCache.java:137` |
| 10 | `BaseLauncher.mMonochromeObserver.onChange()` | `forceReload()` | `BaseLauncher.java:838` |

### 3.3 LayerAdaptiveIconDrawable Rendering

`LayerAdaptiveIconDrawable` extends `AdaptiveIconDrawable` and provides:

```
draw(Canvas):
  ├── if (has animating layers):
  │     drawWithLayers():
  │       renderLayersToBitmap() → mLayersBitmap (ARGB_8888)
  │         ├── drawBackgroundLayer()  → mBackgroundLayer
  │         └── drawForegroundLayers() → mForegroundLayers (list)
  │       createLayersShader() → BitmapShader(mLayersBitmap)
  │       drawMaskedContent()  → canvas.drawPath(iconMask, paint with shader)
  │
  └── else:
        drawWithCache():
          if (mCachedBitmap dirty or null):
            refreshCachedBitmap() → draw to cached bitmap
          canvas.drawBitmap(mCachedBitmap, ...)
  
  drawBadgeIfNeeded() → if badge layer present, draw on top
```

---

## 4. Package Analysis: `com.miui.home.icon`

### 4.1 File Manifest (31 files)

| Category | Files |
|----------|-------|
| **R classes** (AAPT generated) | 7 (R$array, R$color, R$dimen, R$drawable, R$id, R$plurals, R$string) |
| **Interfaces** | 8 (DesktopIcon, ICloudShortcutIcon, IDeepShortcutInfo, IProgressShortcutIcon, IShortcutIcon, NewInstallAppIcon, UpdateIconSize, HotSeatsViewRebindInfo) |
| **Core implementations** | 9 (MonochromeUtils, IconProvider, IconUtils, LauncherIconImageView, TitleTextView, ItemIconTitleContainer, IconComponent, CheckedStateChangeReason, IconAnalyticalDataCollector) |
| **Data/model** | 2 (CustomIconParams, MarketCustomizeIconLocalCache) |
| **Sub-packages** | 5 (api/IIcon, api/ConvertSizeController, utils/MarketIconUtils, newinstallanim/) |

### 4.2 Dependency Graph (icon package)

```
IconProvider
  ├──→ MonochromeUtils          (monochrome state)
  ├──→ LayerAdaptiveIconDrawable (wrapping)
  ├──→ LayerAdaptiveIconDrawableUtils (support check)
  ├──→ AndroidVersionUtils      (API level check)
  ├──→ MamlUtils                (fancy XML icons)
  ├──→ IconCustomizer (reflect) (MIUI framework)
  ├──→ MarketIconUtils          (market icons)
  ├──→ BigIconUtils             (large icons)
  └──→ LauncherAppsCompat       (app listing)

MonochromeUtils
  ├──→ ContextProvider          (app context)
  ├──→ MiuiSettingsUtils        (settings read/write)
  ├──→ AndroidVersionUtils      (API check)
  ├──→ ReflectUtils             (hidden API calls)
  └──→ miui.os.Build            (IS_INTERNATIONAL_BUILD)
```

---

## 5. Class Analysis: Top 20 Most Important

| # | Class | Importance | Why |
|---|-------|------------|-----|
| 1 | **IconProvider** | ★★★★★ | THE icon loading pipeline. All icons flow through `getActivityIcon()`. |
| 2 | **MonochromeUtils** | ★★★★★ | Monochrome state, color, and framework bridge. Our primary hook target. |
| 3 | **IconCache** | ★★★★★ | Central cache. Owns IconProvider. `getIcon()` is public API. |
| 4 | **LayerAdaptiveIconDrawable** | ★★★★ | Renders layered icons with shader pipeline. Final display format. |
| 5 | **BaseLauncher** | ★★★★ | Lifecycle, content observers, `forceReload()`. |
| 6 | **IconPalette** | ★★★ | Color extraction from icons (AndroidX Palette). Not in icon pipeline. |
| 7 | **ShortcutIcon** | ★★★ | View binding, `setIconDrawable()`. |
| 8 | **IconCustomizeFragment** | ★★★ | Settings UI for monochrome colors. |
| 9 | **LauncherIconImageView** | ★★ | ImageView for icons. Thread-safe invalidation. |
| 10 | **LayerAdaptiveIconDrawableUtils** | ★★ | `isSupport()` = always true (API ≥ 26). |
| 11 | **LauncherAppsCompat** | ★★ | Wrapper around system LauncherApps. |
| 12 | **DeviceConfigs** | ★★ | Device-specific config (icon width/height, dark mode). |
| 13 | **MarketIconUtils** | ★ | Market custom icon detection. |
| 14 | **MiuiSettingsUtils** | ★★ | Settings keys (KEY_MONOCHROME, etc). |
| 15 | **IconUtils** | ★ | Utility: get all ShortcutIcons from ViewGroup. |
| 16 | **BigIconUtils** | ★ | Big/folder icon generation. |
| 17 | **MamlUtils** | ★ | MAML-based animated icon rendering. |
| 18 | **IconAnalyticalDataCollector** | ★ | Analytics tracking. |
| 19 | **ReflectUtils** | ★★ | Reflection helper used by MonochromeUtils. |
| 20 | **DrawableCache** | ★★ | HashMap-based Drawable cache. |

---

## 6. Call Graph

```
Application.onCreate()
  └─→ MonochromeUtils.init()
        ├── MiuiSettingsUtils.getBooleanFromSystem(KEY_MONOCHROME) → sMonoEnable
        └── MiuiSettingsUtils.getIntFromSystem(KEY_MONOCHROME_COLOR) → sCurrentColor

[ICON LOADING PATH]
BaseLauncher
  └─→ IconCache(mContext, IconProvider.newInstance(context))
        └─→ addIconToMemCache(LauncherActivityInfo)
              └─→ mIconProvider.getActivityIcon(launcherActivityInfo)
                    ├── [1] MamlUtils.getIconDrawable() → fancy MAML icons
                    ├── [2] IconCustomizer.getIcon() (reflect) → MIUI custom icons
                    ├── [3] launcherActivityInfo.getIcon(0) → Android native icon
                    └── [4] getLayerAdaptiveDrawable(la, adaptiveIcon, badge)
                          ├── MonochromeUtils.getMonochrome(adaptiveIcon)
                          │     └── ReflectUtils.invokeObject(
                          │           adaptiveIcon, "getMonochrome") → framework hidden API
                          ├── MonochromeUtils.getColor() → sCurrentColor
                          └── new LayerAdaptiveIconDrawable(bg, fg, badge, cn)
              └─→ createBadgedIconBitmap(drawable, user)
              └─→ mCache.put(ComponentKey, DrawableInfo)

[THEME CHANGE PATH]
Settings → ContentObserver → BaseLauncher.mMonochromeObserver.onChange()
  └─→ BaseLauncher.forceReload()
        └─→ mModel.forceReload(this, mLauncherMode)
              └─→ [reloads all icons from scratch]

[SETTINGS UI PATH]
IconCustomizeFragment
  ├── setUpMonoPreference()
  │     └── MonochromeUtils.isSupportMonochrome()
  │           → Android 13+ AND International build
  └── onPreferenceChange(mMonoPreference)
        ├── MonochromeUtils.setMonoEnable(z)
        ├── MonochromeUtils.setCurrentColor(i)
        └── broadcast "action_monochrome_color_changed"
```

---

## 7. Dependency Graph

### 7.1 `com.miui.home.icon` Dependencies

```
icon/
  ├──→ common/ContextProvider
  ├──→ common/device/DeviceConfigs
  ├──→ common/drawable/LayerAdaptiveIconDrawable
  ├──→ common/drawable/LayerAdaptiveIconDrawableUtils
  ├──→ common/utils/{AndroidVersionUtils, BigIconUtils, ...}
  ├──→ launcher/graphics/IconPalette
  ├──→ launcher/AppInfo, ShortcutInfo
  ├──→ model/api/IIconCache
  ├──→ library/compat/LauncherAppsCompat
  └──→ miui/launcher/utils/{MiuiSettingsUtils, ReflectUtils, MamlUtils}
```

### 7.2 `com.miui.home.launcher.graphics` Dependencies

```
launcher/graphics/
  ├── IconPalette.java
  │     ├──→ AppInfo (getIconDrawable, getIconBitmap, setIconColorType)
  │     ├──→ ShortcutInfo
  │     ├──→ androidx.palette.graphics.Palette
  │     ├──→ ContextProvider
  │     ├──→ DeviceConfigs
  │     ├──→ WallpaperUtil (wallpaper type detection)
  │     └──→ PreferenceUtils
```

### 7.3 `com.miui.home.model.core` Dependencies

```
model/core/
  ├── IconCache.java
  │     ├──→ IconProvider
  │     ├──→ LauncherAppsCompat
  │     ├──→ DrawableCache (HashMap<ComponentKey, DrawableInfo>)
  │     ├──→ BigDrawableCache
  │     └──→ BadgeDrawable
```

---

## 8. Hook Candidates

### 8.1 Ranked Candidates Table

| # | Hook Point | Class | Method | Returns | Risk | Recommendation |
|---|-----------|-------|--------|---------|------|----------------|
| **1** | **MonochromeUtils.getMonochrome()** | `com.miui.home.icon.MonochromeUtils` | `static Drawable getMonochrome(AdaptiveIconDrawable)` | `Drawable` (or null) | **LOW** | ✅ **PRIMARY** |
| 2 | MonochromeUtils.getColor() | `com.miui.home.icon.MonochromeUtils` | `static int getColor()` | `int` (ARGB) | **LOW** | ✅ Support hook |
| 3 | MonochromeUtils.isMonoEnable() | `com.miui.home.icon.MonochromeUtils` | `static boolean isMonoEnable()` | `boolean` | **LOW** | ✅ Support hook |
| 4 | MonochromeUtils.isSupportMonochrome() | `com.miui.home.icon.MonochromeUtils` | `static boolean isSupportMonochrome()` | `boolean` | **LOW** | ✅ Support hook |
| 5 | IconProvider.getActivityIcon() | `com.miui.home.icon.IconProvider` | `Drawable getActivityIcon(LauncherActivityInfo)` | `Drawable` | **MEDIUM** | Alternative |
| 6 | IconProvider.getLayerAdaptiveDrawable() | `com.miui.home.icon.IconProvider` | `private Drawable getLayerAdaptiveDrawable(...)` | `Drawable` | **HIGH** | Private method |
| 7 | IconCache.addIconToMemCache() | `com.miui.home.model.core.IconCache` | `private void addIconToMemCache(LAI)` | `void` | **HIGH** | Too late |
| 8 | LauncherActivityInfo.getIcon() | `android.content.pm.LauncherActivityInfo` | `Drawable getIcon(int)` | `Drawable` | **HIGH** | Framework API |
| 9 | LayerAdaptiveIconDrawable.draw() | `com.miui.home.common.drawable.LayerAdaptiveIconDrawable` | `void draw(Canvas)` | `void` | **HIGH** | Display only |
| 10 | ShortcutIcon.setIconDrawable() | `com.miui.home.launcher.ShortcutIcon` | `void setIconDrawable(Drawable, Bitmap)` | `void` | **MEDIUM** | Too late |

### 8.2 Detail Analysis for Each Candidate

#### Candidate #1: `MonochromeUtils.getMonochrome()` — PRIMARY

```java
// Location: com/miui/home/icon/MonochromeUtils.java:24
public static Drawable getMonochrome(AdaptiveIconDrawable adaptiveIconDrawable)
```

| Attribute | Value |
|-----------|-------|
| **Returns** | `Drawable` — the monochrome layer, or `null` if not available |
| **Parameters** | `AdaptiveIconDrawable` — the app's adaptive icon |
| **Invocation frequency** | Once per icon load (cached) |
| **Thread** | Background (RxJava IO thread via IconPalette) |
| **Drawable replaceable?** | ✅ YES — return value directly used |
| **Performance impact** | ✅ NONE — called once per icon, already in pipeline |
| **Compatibility risk** | ✅ MINIMAL — static method, well-defined contract |
| **Hook difficulty** | ✅ EASY — LSPosed hooks static method, replace return value |

**Hook strategy**:
```
IF original.getMonochrome() returns non-null:
  → return original  (app already has native monochrome)
ELSE:
  → extract foreground from AdaptiveIconDrawable
  → generate monochrome mask via our image pipeline
  → return our generated Drawable
```

#### Candidate #2-4: Support hooks for `MonochromeUtils`

| Hook | Purpose | Default | We Return |
|------|---------|---------|-----------|
| `isSupportMonochrome()` | Required for monochrome to work | `ATLEAST_T && IS_INTERNATIONAL_BUILD` | `true` (force enable) |
| `isMonoEnable()` | User toggle | From settings | `true` (always enabled) |
| `getColor()` | Background color | From settings (static presets) | Our dynamic Material You color |

#### Candidate #5: `IconProvider.getActivityIcon()` — Alternative

```java
// Location: com/miui/home/icon/IconProvider.java:52
public Drawable getActivityIcon(LauncherActivityInfo launcherActivityInfo)
```

| Attribute | Value |
|-----------|-------|
| **Returns** | `Drawable` |
| **Pros** | Earliest hook point, complete control |
| **Cons** | Need to handle MAML, custom icons, AdaptiveIconDrawable wrapping ourselves. More code, more risk. |

#### Candidate #10: `ShortcutIcon.setIconDrawable()` — Too Late

```java
// Location: com/miui/home/launcher/ShortcutIcon.java
public void setIconDrawable(Drawable drawable, Bitmap bitmap)
```

| Attribute | Value |
|-----------|-------|
| **Cons** | View-level hook, called for EVERY icon display (not cached). Performance risk. No access to package name. |

---

## 9. Recommended Hook Strategy

### Primary Hook: `MonochromeUtils` (4 static methods)

```
Hook 1: MonochromeUtils.getMonochrome(AdaptiveIconDrawable) → Drawable
  ├──→ Call original
  ├──→ If non-null: return original (preserve native monochrome)
  └──→ If null: generate monochrome mask → return it

Hook 2: MonochromeUtils.getColor() → int
  └──→ Return our dynamic Material You color

Hook 3: MonochromeUtils.isMonoEnable() → boolean
  └──→ Return true (force enable for all apps)

Hook 4: MonochromeUtils.isSupportMonochrome() → boolean
  └──→ Return true (force enable for all devices)
```

### Why This Is Optimal

1. **Minimal footprint**: One class, 4 static methods. Clean, simple, auditable.
2. **Existing integration**: `IconProvider.getLayerAdaptiveDrawable()` already calls `getMonochrome()`. When we return non-null, the launcher automatically wraps it correctly — colored background + black silhouette.
3. **Graceful failure**: If any hook fails → returns original value → launcher shows original icon. Zero crash risk.
4. **Respects native monochrome**: Apps that already provide monochrome (e.g., Google apps) keep their native monochrome.
5. **Dynamic color**: We replace static preset colors with Material You wallpaper colors via `getColor()`.
6. **No pipeline modification**: The launcher's rendering, caching, and display code is untouched.

### Hook Installation Code (Pseudocode)

```kotlin
// In IconThemeHook.onPackageLoaded()
val monochromeUtilsClass = XposedHelpers.findClass(
    "com.miui.home.icon.MonochromeUtils", classLoader)

// Hook 1: getMonochrome
XposedHelpers.findAndHookMethod(monochromeUtilsClass, "getMonochrome",
    AdaptiveIconDrawable::class.java,
    object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            // Call original first
        }
        override fun afterHookedMethod(param: MethodHookParam) {
            if (param.result == null) {
                // Generate monochrome from AdaptiveIconDrawable
                val aid = param.args[0] as AdaptiveIconDrawable
                param.result = generateMonochrome(aid)
            }
        }
    })

// Hook 2: getColor — return Material You color
XposedHelpers.findAndHookMethod(monochromeUtilsClass, "getColor",
    object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            param.result = getMaterialYouColor()
        }
    })

// Hook 3: isMonoEnable — always true
XposedHelpers.findAndHookMethod(monochromeUtilsClass, "isMonoEnable",
    object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            param.result = true
        }
    })

// Hook 4: isSupportMonochrome — always true
XposedHelpers.findAndHookMethod(monochromeUtilsClass, "isSupportMonochrome",
    object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            param.result = true
        }
    })
```

---

## 10. Dynamic Monochrome Integration

### 10.1 Design

```
Original AdaptiveIconDrawable
    │
    ▼
Extract Foreground (getForeground())
    │
    ▼
Render to Bitmap (Canvas)
    │
    ▼
ImageProcessor.process(bitmap)          ← Pure: Bitmap → monochrome Bitmap
    │  Pipeline:
    │  ├── ForegroundExtraction         ← Extract non-transparent pixels
    │  ├── EdgeDetection                ← Find edges for visual structure
    │  ├── LocalContrastAnalysis        ← Preserve contrast hierarchy
    │  ├── MorphologicalCleanup         ← Remove noise, fill gaps
    │  └── MonochromeGeneration         ← Generate final binary mask
    │
    ▼
ThemeEngine.apply(monochrome)           ← Apply Material You colors
    │
    ▼
return BitmapDrawable(monochromeBitmap)  ← Return to MonochromeUtils hook
    │
    ▼
[Launcher wraps it] LayerAdaptiveIconDrawable(ColorDrawable(color), monochrome, badge)
    │  Background = Material You primary color
    │  Foreground = Our monochrome mask (tinted black by launcher)
    │
    ▼
Displayed as Themed Icon
```

### 10.2 Cache Key Design

```kotlin
fun buildCacheKey(packageName: String, theme: MonoTheme): String {
    return "$packageName|${theme.primaryColor}|${theme.isDarkMode}"
}
```

- Changes when package updates (version part of key can be added)
- Changes when wallpaper/theme changes (primaryColor changes)
- Changes when dark mode toggles

### 10.3 Where Pipeline Inserts

Our monochrome generation pipeline inserts at `MonochromeUtils.getMonochrome()` — AFTER the launcher has obtained the `AdaptiveIconDrawable` but BEFORE the `LayerAdaptiveIconDrawable` is constructed. This is the ideal point:

- **Input**: `AdaptiveIconDrawable` (already has foreground/background separated)
- **Output**: `Drawable` (a monochrome mask)
- **Downstream**: Launcher handles everything — coloring, wrapping, caching, display

---

## 11. Existing Reusable Code

### 11.1 In Launcher — REUSABLE

| Component | Location | Reusable? |
|-----------|----------|-----------|
| `IconPalette.handleWithDrawable()` | `launcher/graphics/IconPalette.java:198-214` | ✅ Bitmap extraction from ShortcutInfo |
| `MonochromeUtils` static methods | `icon/MonochromeUtils.java` | ✅ Our hook target |
| `LayerAdaptiveIconDrawable` internal bitmap caching | `common/drawable/LayerAdaptiveIconDrawable.java` | ✅ Already handles display |
| `IconCache` LRU-based cache | `model/core/IconCache.java` | ⚠️ Do NOT reuse — we have our own cache |

### 11.2 NOT Available in Launcher

| Feature | Status |
|---------|--------|
| Material You / WallpaperColors | **NOT FOUND** — zero references |
| Dynamic color extraction | **NOT FOUND** |
| ColorScheme / Monet | **NOT FOUND** |
| Custom monochrome generation for non-native apps | **NOT SUPPORTED** — only framework native `getMonochrome()` |

### 11.3 No Reusable Image Processing Code

The launcher has:
- `IconPalette` — uses AndroidX Palette for color classification (HSL analysis), NOT for icon generation
- `LayerAdaptiveIconDrawable` — renders and caches bitmaps, but doesn't transform icon content

**Conclusion**: We must build our own monochrome generation pipeline. The launcher provides no reusable monochrome generation code.

---

## 12. Phase 2 Development Plan

### Task 2-1: Verify Hook Installation
- Hook `MonochromeUtils.getMonochrome()` with logging only
- Verify hook fires via logcat
- **Verification**: `adb logcat | grep MonoIcon.Hook` shows hook invocation

### Task 2-2: Enable Monochrome for All Apps
- Hook `isSupportMonochrome()` → return `true`
- Hook `isMonoEnable()` → return `true`
- Hook `getColor()` → return a test color (e.g., `0xFF6750A4`)
- **Verification**: All apps show colored background (even without native monochrome)

### Task 2-3: Generate Basic Monochrome
- Implement simple grayscale conversion (placeholder for proper pipeline)
- Return `BitmapDrawable` from `getMonochrome()` hook
- **Verification**: Icons show as black silhouettes on colored backgrounds

### Task 2-4: Implement Proper Image Pipeline
- Implement ForegroundExtraction, EdgeDetection, MonochromeGeneration stages
- Wire into `ImageProcessor`
- **Verification**: Monochrome masks look like proper designer-made icons

### Task 2-5: Dynamic Material You Colors
- Implement `ThemeProvider` with wallpaper color extraction
- Wire `getColor()` hook to return dynamic colors
- **Verification**: Icon colors change when wallpaper changes

### Task 2-6: LRU Cache Integration
- Wire `ImageService` with `LruBitmapCache`
- Measure cache hit rate
- **Verification**: `adb logcat | grep MonoIcon.Cache` shows HIT/MISS

### Task 2-7: Performance & Polish
- Measure per-icon processing time
- Optimize slow stages
- Handle edge cases (very small icons, transparency, icon packs)

---

## 13. Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| `MonochromeUtils` class changes in new HyperOS version | MEDIUM | Hook verification step detects class not found → log warning → disable module |
| `AdaptiveIconDrawable.getMonochrome()` behavior changes in new Android | LOW | We only override when it returns null; native monochrome still works |
| Performance: generating monochrome is CPU-intensive | MEDIUM | LRU cache, background thread, size-limited processing |
| HyperOS Launcher OTA changes icon pipeline | MEDIUM | Our hook is on a stable, purpose-built API point. Low chance of removal. |
| launcher crash from hook | LOW | XposedInterface.ExceptionMode.PROTECTIVE, try-catch in all hooks |

---

## 14. Remaining Unknowns

| Unknown | Impact | How to Resolve |
|---------|--------|---------------|
| `AdaptiveIconDrawable.getMonochrome()` exact return type on Android 16 | LOW | Test on device; likely unchanged from API 33 |
| HyperOS China ROM vs International ROM differences | MEDIUM | `isSupportMonochrome()` checks `IS_INTERNATIONAL_BUILD` — China ROMs may lack this entirely |
| Exact hook method signature for LSPosed static hook | LOW | Use `XC_MethodHook` or `XposedBridge.hookMethod()` — standard LSPosed API |
| Icon loading on non-AdaptiveIconDrawable apps | LOW | Old apps use `BitmapDrawable`; we skip them gracefully |
| Performance of `LayerAdaptiveIconDrawable` with our injected Drawable | LOW | It handles `BitmapDrawable` already — our return type is compatible |

---

## Appendix: Source File Index

### Key Files Examined

| File | Path | Lines |
|------|------|-------|
| MonochromeUtils.java | `.../com/miui/home/icon/MonochromeUtils.java` | 50 |
| IconProvider.java | `.../com/miui/home/icon/IconProvider.java` | 166 |
| IconCache.java | `.../com/miui/home/model/core/IconCache.java` | ~450 |
| LayerAdaptiveIconDrawable.java | `.../com/miui/home/common/drawable/LayerAdaptiveIconDrawable.java` | 908 |
| IconPalette.java | `.../com/miui/home/launcher/graphics/IconPalette.java` | 309 |
| BaseLauncher.java (excerpts) | `.../com/miui/home/launcher/BaseLauncher.java` | ~12000 |
| IconCustomizeFragment.java | `.../com/miui/home/settings/IconCustomizeFragment.java` | 274 |
| LayerAdaptiveIconDrawableUtils.java | `.../com/miui/home/common/drawable/LayerAdaptiveIconDrawableUtils.java` | 20 |

### All Source Files Examined

- com.miui.home.icon/* (31 files)
- com.miui.home.launcher/graphics/* (1 file: IconPalette.java)
- com.miui.home.model/core/IconCache.java
- com.miui.home.launcher/BaseLauncher.java (excerpts)
- com.miui.home.settings/IconCustomizeFragment.java
- com.miui.home.common/drawable/LayerAdaptiveIconDrawable.java
- com.miui.home.common/drawable/LayerAdaptiveIconDrawableUtils.java

### Note on Analysis Methodology

All conclusions in this report are based on **direct source code examination** of decompiled Java files. No AOSP Launcher3 assumptions were made. Every caller-callee relationship was verified by searching the entire source tree. Where source code was insufficient (obfuscated names, synthetic accessors), this is explicitly noted.
