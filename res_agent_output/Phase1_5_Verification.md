# MonoIcon Phase 1.5 — Hook Verification Report

**Date**: 2026-07-28
**Module Version**: 1.0.0
**Target**: HyperOS Launcher `RELEASE-7.00.00.2259-04301440` (com.miui.home)
**Platform**: HyperOS 3 / Android 16

---

## 1. Overview

This phase verifies that the reverse engineering conclusions from Phase 1 are correct on a real device. All hooks are **pass-through only** — they log invocation details and return original results unchanged.

Zero icon appearance modification. The only observable difference is logcat output.

---

## 2. Implemented Hooks

### 2.1 Self-Test (Module Load)

Printed once when the LSPosed module is loaded by the framework.

```
Expected output:
╔══════════════════════════════════════════════════════╗
║         MonoIcon v1.0.0 — Hook Verification          ║
╠══════════════════════════════════════════════════════╣
║  PID          = <pid>
║  Process      = com.miui.home
║  Android SDK  = 36
║  HyperOS      = <version>
║  Package      = com.miui.home
║  ClassLoader  = <classloader>
╚══════════════════════════════════════════════════════╝
```

### 2.2 Hook 1: `MonochromeUtils.getMonochrome(AdaptiveIconDrawable)`

| Property | Value |
|----------|-------|
| Target Class | `com.miui.home.icon.MonochromeUtils` |
| Target Method | `static Drawable getMonochrome(AdaptiveIconDrawable)` |
| Hook Type | Static method interceptor |
| Returns | Original result (never modified) |

**Expected logcat output:**
```
D/MonoIcon.Hook: [getMonochrome] thread=main drawable=AdaptiveIconDrawable(w=216,h=216)@12345678
I/MonoIcon.Hook: [getMonochrome] result=null | null=true | cost=3ms
```

**Expected invocation frequency**: Once per unique app icon (cached after first load). On first launcher start: ~100-200 invocations.

### 2.3 Hook 2: `MonochromeUtils.isSupportMonochrome()`

| Property | Value |
|----------|-------|
| Target Class | `com.miui.home.icon.MonochromeUtils` |
| Target Method | `static boolean isSupportMonochrome()` |
| Hook Type | Static method interceptor |
| Returns | Original result (never modified) |

**Expected logcat output:**
```
I/MonoIcon.Hook: [isSupportMonochrome] result=true | android=36 | thread=main | cost=0ms
```

**Expected invocation frequency**: Multiple times during launcher startup (registering content observers, backup settings, etc). ~3-10 times per launcher session.

### 2.4 Hook 3: `MonochromeUtils.isMonoEnable()`

| Property | Value |
|----------|-------|
| Target Class | `com.miui.home.icon.MonochromeUtils` |
| Target Method | `static boolean isMonoEnable()` |
| Hook Type | Static method interceptor |
| Returns | Original result (never modified) |

**Expected logcat output:**
```
I/MonoIcon.Hook: [isMonoEnable] result=false | thread=main | cost=0ms
```

**Expected invocation frequency**: Once per icon load (checked inside `getMonochrome()`). ~100-200 times.

### 2.5 Hook 4: `MonochromeUtils.getColor()`

| Property | Value |
|----------|-------|
| Target Class | `com.miui.home.icon.MonochromeUtils` |
| Target Method | `static int getColor()` |
| Hook Type | Static method interceptor |
| Returns | Original result (never modified) |

**Expected logcat output:**
```
I/MonoIcon.Hook: [getColor] result=0 | hex=0x00000000 | ARGB(0,0,0,0) | thread=main | cost=0ms
```

**Expected invocation frequency**: Once per icon load (checked inside `getMonochrome()`). ~100-200 times.

**Note**: When monochrome is disabled (default), the color is `0x00000000` (transparent black). When enabled, it will be one of the preset colors:
- `0xFF4D8CF0` (blue)
- `0xFF34C759` (green)
- `0xFFAF52DE` (purple)
- `0xFFA2845E` (brown)

### 2.6 Hook 5: `ShortcutIcon.setIconDrawable(Drawable, Bitmap)`

| Property | Value |
|----------|-------|
| Target Class | `com.miui.home.launcher.ShortcutIcon` |
| Target Method | `public void setIconDrawable(Drawable, Bitmap)` |
| Hook Type | Instance method interceptor |
| Modifies | Nothing (pass-through) |

**Expected logcat output:**
```
D/MonoIcon.Hook: [setIconDrawable] target=ShortcutIcon@87654321 | thread=main | drawable=LayerAdaptiveIconDrawable(AdaptiveIcon) w=216,h=216 | bitmap=null
I/MonoIcon.Hook: [setIconDrawable] cost=5ms
```

**Expected invocation frequency**: Once per visible icon on screen. ~20-50 times on initial load, then on scroll/redraw events.

---

## 3. Statistics Output

Every 100 invocations, HookStats prints a summary:

```
I/MonoIcon.Hook: [getMonochrome] count=100 | avg=2ms | min=0ms | max=15ms | total=200ms
I/MonoIcon.Hook: [isSupportMonochrome] count=5 | avg=0ms | min=0ms | max=0ms | total=0ms
I/MonoIcon.Hook: [isMonoEnable] count=100 | avg=0ms | min=0ms | max=0ms | total=0ms
I/MonoIcon.Hook: [getColor] count=100 | avg=0ms | min=0ms | max=1ms | total=5ms
I/MonoIcon.Hook: [setIconDrawable] count=30 | avg=5ms | min=3ms | max=12ms | total=150ms
```

---

## 4. Verification Procedure

### 4.1 Prerequisites

1. Device with HyperOS 3 (Android 16) and LSPosed installed
2. MonoIcon APK installed and activated in LSPosed Manager
3. Scope set to `com.miui.home`
4. ADB debugging enabled

### 4.2 Step-by-Step Verification

#### Step 1: Install and Activate

```bash
adb install app-debug.apk
# Activate in LSPosed Manager UI
# Reboot device
```

#### Step 2: Check Module Loaded

```bash
adb logcat -c  # Clear buffer
adb logcat | grep "MonoIcon"
```

Expected: Self-test banner appears with version, PID, process info.

#### Step 3: Verify Hooks Installed

Look for:
```
I/MonoIcon.Hook: Hook installation: 5/5 succeeded
I/MonoIcon.Hook: ALL HOOKS INSTALLED SUCCESSFULLY
```

If fewer than 5 succeeded, check for error messages above this line.

#### Step 4: Trigger Icon Loading

- Swipe up to open app drawer
- Scroll through apps
- Go to home screen
- Add/remove apps

#### Step 5: Verify Each Hook

```bash
adb logcat | grep "MonoIcon.Hook.*getMonochrome"
adb logcat | grep "MonoIcon.Hook.*isSupportMonochrome"
adb logcat | grep "MonoIcon.Hook.*isMonoEnable"
adb logcat | grep "MonoIcon.Hook.*getColor"
adb logcat | grep "MonoIcon.Hook.*setIconDrawable"
```

#### Step 6: Verify Statistics

Wait for ~100 icon loads, then check for statistics summary.

#### Step 7: Verify Launcher Behavior

- Launcher should work identically to stock
- No crashes, no ANRs, no visual glitches
- Icons look exactly the same as before

### 4.3 Smoke Test Checklist

- [ ] Module self-test prints on launcher start
- [ ] All 5 hooks installed successfully
- [ ] `getMonochrome()` hook fires on icon load
- [ ] `isSupportMonochrome()` hook fires on launcher init
- [ ] `isMonoEnable()` hook fires on icon load
- [ ] `getColor()` hook fires on icon load
- [ ] `setIconDrawable()` hook fires on icon display
- [ ] Statistics print every 100 invocations
- [ ] No launcher crashes
- [ ] No ANRs
- [ ] Icons look identical to stock
- [ ] Launcher scrolls smoothly

---

## 5. Troubleshooting

### 5.1 Module Not Loaded

**Symptom**: No "MonoIcon" logcat output at all.

**Causes**:
1. LSPosed not activated for `com.miui.home`
2. Module not enabled in LSPosed Manager
3. Device not rebooted after activation

**Recovery**:
1. Open LSPosed Manager
2. Check MonoIcon is enabled
3. Check scope includes `com.miui.home`
4. Reboot device

### 5.2 Hook Installation Failed (0/5)

**Symptom**: Module loads but hooks fail.

**Possible causes**:
1. HyperOS Launcher version differs from reverse-engineered version
2. Class/method names changed in OTA update

**Recovery**:
- Check logcat for specific error messages
- If class name changed: update `CLASS_MONOCHROME_UTILS` constant
- If method signature changed: re-run reverse engineering on new APK

### 5.3 Hook Installation Partially Failed (X/5)

**Symptom**: Some hooks install, others don't.

**Recovery**:
- Each failure is individually logged with reason
- If `getMonochrome` fails: primary hook point unavailable — investigate alternative hooks
- If `setIconDrawable` fails: display hook unavailable — but icon pipeline hooks still work

### 5.4 Launcher Crash

**Symptom**: Launcher crashes on start.

**Recovery**:
1. Boot to safe mode (disables all LSPosed modules)
2. Disable MonoIcon in LSPosed Manager
3. Reboot normally
4. Check logcat for crash stack trace
5. Report issue with stack trace

---

## 6. Expected Logcat Filter Commands

```bash
# Module self-test
adb logcat -s MonoIcon.Hook:I | head -20

# All MonoIcon output
adb logcat -s MonoIcon.Hook:*

# Stats only
adb logcat -s MonoIcon.Hook:I | grep "\["

# Hook-specific filters
adb logcat -s MonoIcon.Hook:I | grep "getMonochrome"
adb logcat -s MonoIcon.Hook:I | grep "setIconDrawable"

# Errors only
adb logcat -s MonoIcon.Hook:E
```

---

## 7. Files Created/Modified

### New Files

| File | Purpose |
|------|---------|
| `hook/HookStats.kt` | Thread-safe invocation statistics with timing |
| `hook/HookTracer.kt` | Result description utility (object) |

### Modified Files

| File | Changes |
|------|---------|
| `hook/IconThemeHook.kt` | Complete rewrite: 5 verification hooks + self-test + lifecycle |
| `keepRules/rules.keep` | Added LSPosed entry point and utility class keep rules |

---

## 8. Next Steps (Phase 2)

After hook verification is confirmed:

1. **Hook 1 modification**: Make `getMonochrome()` return a generated monochrome Drawable when original returns `null`
2. **Hook 2 modification**: Make `isSupportMonochrome()` return `true` unconditionally
3. **Hook 3 modification**: Make `isMonoEnable()` return `true` unconditionally
4. **Hook 4 modification**: Make `getColor()` return Material You dynamic color
5. **Image pipeline**: Implement foreground extraction → monochrome mask generation
6. **Cache integration**: LRU cache for processed masks
7. **Theme engine**: Wallpaper-based dynamic color extraction
