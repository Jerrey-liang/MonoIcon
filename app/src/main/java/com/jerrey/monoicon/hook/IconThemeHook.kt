package com.jerrey.monoicon.hook

import android.content.pm.LauncherActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.ColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import com.jerrey.monoicon.cache.MonochromeCache
import com.jerrey.monoicon.color.IconColorCache
import com.jerrey.monoicon.color.IconDrawableCache
import com.jerrey.monoicon.color.IconColorExtractor
import com.jerrey.monoicon.color.PixelStyleColorExtractor
import com.jerrey.monoicon.image.DrawableConverter
import com.jerrey.monoicon.image.MonochromeGenerator
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

private const val TAG = "MonoIcon.Hook"
private const val TAG_COLOR = "MonoIcon.Color"
private const val TAG_MASK = "MonoIcon.Mask"
private const val MODULE_VERSION = "1.0.1"

/**
 * MonoIcon — Modern LSPosed API 101 module entry point.
 *
 * Hooks 5 methods in HyperOS Launcher (com.miui.home) to intercept
 * the icon loading pipeline. All hooks use the official libxposed
 * interceptor-chain API.
 *
 * Single entry class. No legacy adapters. No IXposedHookLoadPackage.
 * Listed in [META-INF/xposed/java_init.list].
 */
class IconThemeHook : XposedModule() {

    private val stats = HookStats(TAG)

    // Phase 2.6: 生成的 monochrome drawable 缓存（基于 packageName|size）
    private val monochromeCache = MonochromeCache(maxSize = 512)

    // Phase 3.11: Pixel-style icon color extraction（独立管线，不影响 mask 生成）
    private val colorExtractor = PixelStyleColorExtractor()

    // ═══════════════════════════════════════════════════════════════
    // Bootstrap
    // ═══════════════════════════════════════════════════════════════

    init {
        try {
            val pid = Process.myPid()
            android.util.Log.i(TAG, "═══ MonoIcon v$MODULE_VERSION loaded — PID=$pid SDK=${Build.VERSION.SDK_INT} ═══")
        } catch (_: Throwable) { /* never crash */ }
    }

    // ═══════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        android.util.Log.i(TAG, "onModuleLoaded: process=${param.processName} systemServer=${param.isSystemServer}")
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != "com.miui.home") return

        val cl = param.getDefaultClassLoader()
        android.util.Log.i(TAG, "onPackageLoaded: package=${param.packageName} firstPackage=${param.isFirstPackage}")
        android.util.Log.i(TAG, "ClassLoader: $cl")

        installHooks(cl)
    }

    // ═══════════════════════════════════════════════════════════════
    // Hook installation
    // ═══════════════════════════════════════════════════════════════

    private fun installHooks(cl: ClassLoader) {
        var ok = 0
        val total = 8

        ok += safeInstall("getMonochrome") { installGetMonochrome(cl) }
        ok += safeInstall("isSupportMonochrome") { installIsSupportMonochrome(cl) }
        ok += safeInstall("isMonoEnable") { installIsMonoEnable(cl) }
        ok += safeInstall("getColor") { installGetColor(cl) }
        ok += safeInstall("setIconDrawable") { installSetIconDrawable(cl) }
        ok += safeInstall("folderSetImageDrawable") { installFolderSetImageDrawable(cl) }
        ok += safeInstall("getActivityIcon") { installGetActivityIcon(cl) }
        ok += safeInstall("folderSmallIcon") { installFolderSmallIconDrawable(cl) }

        android.util.Log.i(TAG, "Hooks installed: $ok/$total")
    }

    private inline fun safeInstall(name: String, block: () -> Unit): Int = try {
        block()
        android.util.Log.i(TAG, "  ✓ $name")
        1
    } catch (e: ClassNotFoundException) {
        android.util.Log.w(TAG, "  ✗ $name: class not found — ${e.message}")
        0
    } catch (e: NoSuchMethodException) {
        android.util.Log.w(TAG, "  ✗ $name: method not found — ${e.message}")
        0
    } catch (e: Throwable) {
        android.util.Log.e(TAG, "  ✗ $name: ${e.javaClass.simpleName} — ${e.message}", e)
        0
    }

    // ═══════════════════════════════════════════════════════════════
    // Hook 1: MonochromeUtils.getMonochrome(AdaptiveIconDrawable)
    // ═══════════════════════════════════════════════════════════════

    private fun installGetMonochrome(cl: ClassLoader) {
        val method = cl.loadClass("com.miui.home.icon.MonochromeUtils")
            .getDeclaredMethod("getMonochrome", AdaptiveIconDrawable::class.java)
        deoptimize(method)

        hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                val start = System.nanoTime()
                val adaptiveIcon = chain.getArg(0) as? AdaptiveIconDrawable
                val desc = if (adaptiveIcon != null) {
                    "${adaptiveIcon.javaClass.simpleName}(w=${adaptiveIcon.intrinsicWidth},h=${adaptiveIcon.intrinsicHeight})"
                } else "null"
                android.util.Log.d(TAG, "[getMonochrome] drawable=$desc")

                // Step 1: 复用系统原生 monochrome layer（若应用自带）
                val original = try {
                    chain.proceed()
                } catch (t: Throwable) {
                    android.util.Log.e(TAG, "[getMonochrome] proceed threw: ${t.message}", t)
                    null
                }
                if (original != null) {
                    val elapsed = (System.nanoTime() - start) / 1_000_000L
                    android.util.Log.i(TAG, "[getMonochrome] native monochrome reused null=false cost=${elapsed}ms")
                    stats.record("getMonochrome", elapsed)
                    return@intercept original
                }

                // Step 2: 原生为空 → 自行生成 alpha-mask monochrome
                val generated = if (adaptiveIcon != null) {
                    val bitmap = DrawableConverter.toBitmap(adaptiveIcon)
                    MonochromeGenerator.create(bitmap)
                } else {
                    null
                }

                val elapsed = (System.nanoTime() - start) / 1_000_000L
                val isNull = generated == null
                android.util.Log.i(TAG, "[getMonochrome] generated null=$isNull type=${generated?.javaClass?.simpleName} cost=${elapsed}ms")
                stats.record("getMonochrome", elapsed)
                generated
            }
    }

    // ═══════════════════════════════════════════════════════════════
    // Hook 2: MonochromeUtils.isSupportMonochrome()
    // ═══════════════════════════════════════════════════════════════

    private fun installIsSupportMonochrome(cl: ClassLoader) {
        val method = cl.loadClass("com.miui.home.icon.MonochromeUtils")
            .getDeclaredMethod("isSupportMonochrome")
        deoptimize(method)

        hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                val start = System.nanoTime()
                val result = chain.proceed()
                val elapsed = (System.nanoTime() - start) / 1_000_000L
                android.util.Log.i(TAG, "[isSupportMonochrome] result=$result sdk=${Build.VERSION.SDK_INT} cost=${elapsed}ms")
                stats.record("isSupportMonochrome", elapsed)
                result
            }
    }

    // ═══════════════════════════════════════════════════════════════
    // Hook 3: MonochromeUtils.isMonoEnable()
    // ═══════════════════════════════════════════════════════════════

    private fun installIsMonoEnable(cl: ClassLoader) {
        val method = cl.loadClass("com.miui.home.icon.MonochromeUtils")
            .getDeclaredMethod("isMonoEnable")
        deoptimize(method)

        hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                val start = System.nanoTime()
                val result = chain.proceed()
                val elapsed = (System.nanoTime() - start) / 1_000_000L
                android.util.Log.i(TAG, "[isMonoEnable] result=$result cost=${elapsed}ms")
                stats.record("isMonoEnable", elapsed)
                result
            }
    }

    // ═══════════════════════════════════════════════════════════════
    // Hook 4: MonochromeUtils.getColor()
    // ═══════════════════════════════════════════════════════════════

    private fun installGetColor(cl: ClassLoader) {
        val method = cl.loadClass("com.miui.home.icon.MonochromeUtils")
            .getDeclaredMethod("getColor")
        deoptimize(method)

        hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                val start = System.nanoTime()
                val result = chain.proceed()
                val c = result as? Int ?: 0
                val elapsed = (System.nanoTime() - start) / 1_000_000L
                android.util.Log.i(TAG, "[getColor] value=$c hex=0x${c.toUInt().toString(16).uppercase().padStart(8, '0')} cost=${elapsed}ms")
                stats.record("getColor", elapsed)
                result
            }
    }

    // ═══════════════════════════════════════════════════════════════
    // Hook 5: ShortcutIcon.setIconDrawable(Drawable, Bitmap)
    //
    // Phase 2 架构调整后的唯一主入口：getMonochrome() 在 HyperOS 桌面
    // 图标加载中从不被调用（桌面图标是 FancyDrawable/BitmapDrawable，
    // 不走 AdaptiveIconDrawable 分支）。setIconDrawable 是每个图标
    // 显示时必定调用的方法，因此在拦截器中把 drawable 替换为生成的
    // monochrome 版本。
    //
    // packageName 通过反射 ShortcutIcon.getShortcutInfo().getPackageName()
    // 获取（链上已有：thisObject → getShortcutInfo → getPackageName）。
    // ═══════════════════════════════════════════════════════════════

    private fun installSetIconDrawable(cl: ClassLoader) {
        val method = cl.loadClass("com.miui.home.launcher.ShortcutIcon")
            .getDeclaredMethod("setIconDrawable", Drawable::class.java, Bitmap::class.java)
        deoptimize(method)

        hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                val start = System.nanoTime()

                // Phase 2.7: 生成逻辑整体 try/catch，任何异常回落原始 drawable
                val replacement = try {
                    processIconReplacement(chain)
                } catch (t: Throwable) {
                    // 绝不 crash launcher：记录后回落原始行为
                    android.util.Log.e(TAG, "[setIconDrawable] process failed, falling back: ${t.message}")
                    null
                }

                val result = if (replacement != null) {
                    val b = chain.getArg(1) as? Bitmap
                    chain.proceed(arrayOf<Any>(replacement, b ?: replacement.bitmap))
                } else {
                    chain.proceed()
                }
                val elapsed = (System.nanoTime() - start) / 1_000_000L
                // Phase 2.7: 降噪 — 仅记录替换与否，完整描述交给 HookStats 统计
                android.util.Log.i(TAG, "[setIconDrawable] replaced=${replacement != null} cost=${elapsed}ms")
                stats.record("setIconDrawable", elapsed)
                result
            }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══════════════════════════════════════════════════════════════
    // Hook 6: FolderPreviewIconView.refreshIconDrawable(Drawable)
    //
    // Phase 3.10: 直接替换参数 → chain.proceed(replacement)
    // ═══════════════════════════════════════════════════════════════

    private fun installFolderSetImageDrawable(cl: ClassLoader) {
        val method = cl.loadClass("com.miui.home.folder.FolderPreviewIconView")
            .getDeclaredMethod("refreshIconDrawable", Drawable::class.java)
        deoptimize(method)

        hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                val start = System.nanoTime()
                try {
                    val d = chain.getArg(0) as? Drawable
                    if (d == null) return@intercept chain.proceed()

                    // Phase 3.16-A: 尝试从缓存获取 raw APK drawable 用于 mask 生成
                    val identity = resolveFolderIconIdentity(chain.thisObject)
                    val rawCached = if (identity != null) IconDrawableCache.get(identity) else null

                    val mask = if (rawCached != null) {
                        // 使用 raw APK AdaptiveIconDrawable（完整色彩）生成 mask
                        val fullRender = DrawableConverter.toRawBitmap(rawCached)
                        if (fullRender != null) DrawableConverter.toLuminanceMask(fullRender) else null
                    } else {
                        // 回退：尝试 toBitmap → 不支持的类型用 Canvas 渲染
                        DrawableConverter.toBitmap(d)
                            ?: renderGenericToMask(d)
                    }

                    if (mask == null) return@intercept chain.proceed()

                    val replacement = MonochromeGenerator.create(mask)
                    if (replacement == null) return@intercept chain.proceed()

                    android.util.Log.d(TAG,
                        "[FolderPreviewReplace] original=${d.javaClass.simpleName} " +
                        "replacement=BitmapDrawable rawCached=${rawCached != null}")
                    return@intercept chain.proceed(arrayOf<Any>(replacement))
                } catch (t: Throwable) {
                    android.util.Log.e(TAG, "[folderSetImageDrawable] failed, falling back: ${t.message}")
                    return@intercept chain.proceed()
                } finally {
                    val elapsed = (System.nanoTime() - start) / 1_000_000L
                    stats.record("folderSetImageDrawable", elapsed)
                }
            }
    }

    // ═══════════════════════════════════════════════════════════════
    // Hook 8: FolderIconPreviewContainer1X1$PreviewIconView.refreshIconDrawable
    //
    // 小文件夹预览（1x1 容器内的 PreviewIconView）。
    // 与 FolderPreviewIconView 共用相同的处理管线。
    // ═══════════════════════════════════════════════════════════════

    private fun installFolderSmallIconDrawable(cl: ClassLoader) {
        val method = cl.loadClass(
            "com.miui.home.folder.FolderIconPreviewContainer1X1\$PreviewIconView"
        ).getDeclaredMethod("refreshIconDrawable", Drawable::class.java)
        deoptimize(method)

        hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                try {
                    val d = chain.getArg(0) as? Drawable
                    if (d == null) return@intercept chain.proceed()

                    val identity = resolveFolderIconIdentity(chain.thisObject)
                    val rawCached = if (identity != null) IconDrawableCache.get(identity) else null

                    val mask = if (rawCached != null) {
                        val fullRender = DrawableConverter.toRawBitmap(rawCached)
                        if (fullRender != null) DrawableConverter.toLuminanceMask(fullRender) else null
                    } else {
                        DrawableConverter.toBitmap(d) ?: renderGenericToMask(d)
                    }

                    if (mask == null) return@intercept chain.proceed()

                    val replacement = MonochromeGenerator.create(mask)
                    if (replacement == null) return@intercept chain.proceed()

                    android.util.Log.d(TAG,
                        "[FolderSmallReplace] original=${d.javaClass.simpleName} replacement=BitmapDrawable")
                    return@intercept chain.proceed(arrayOf<Any>(replacement))
                } catch (t: Throwable) {
                    android.util.Log.e(TAG, "[folderSmallIcon] failed: ${t.message}")
                    return@intercept chain.proceed()
                }
            }
    }

    /**
     * 从 FolderPreviewIconView 反射获取组件标识。
     * FolderPreviewIconView 保存了 IShortcutInfo → getPackageName/getComponentName。
     */
    private fun resolveFolderIconIdentity(view: Any?): String? {
        if (view == null) return null
        return try {
            val getBuddy = view.javaClass.getMethod("getMBuddyInfo")
            val buddy = getBuddy.invoke(view) ?: return null
            val pkg = buddy.javaClass.getMethod("getPackageName").invoke(buddy) as? String ?: return null
            val intent = buddy.javaClass.getMethod("getIntent").invoke(buddy)
            val component = intent?.javaClass?.getMethod("getComponent")?.invoke(intent)
            val cls = component?.javaClass?.getMethod("getClassName")?.invoke(component) as? String
            if (cls != null) "$pkg/$cls" else pkg
        } catch (_: Throwable) { null }
    }

    /**
     * 通用回退：对 [DrawableConverter.toBitmap] 不支持的类型，
     * 直接 Canvas 渲染 + toLuminanceMask 生成 monochrome mask。
     */
    private fun renderGenericToMask(drawable: Drawable): Bitmap? {
        return try {
            val w = drawable.intrinsicWidth.coerceAtLeast(1)
            val h = drawable.intrinsicHeight.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            DrawableConverter.toLuminanceMask(bmp)
        } catch (_: Throwable) { null }
    }


    /**
     * 生成 monochrome 替换 drawable，或返回 null（不可替换/失败）。
     *
     * 流程：解析 identity → 缓存查找（Bitmap mask） → 未命中则转换并存入缓存。
     * 抛出异常由调用方 catch，回落原始 drawable。
     */
        // ═══════════════════════════════════════════════════════════════
    // Hook 7: IconProvider.getActivityIcon(LauncherActivityInfo)
    //
    // Phase 3.15: 在图标加载阶段提取原始色彩。
    // 此时 AdaptiveIconDrawable 还保留着 APK 中的完整颜色，
    // HyperOS 的 theme 处理尚未将其替换为 monochrome mask。
    // ═══════════════════════════════════════════════════════════════

    private fun installGetActivityIcon(cl: ClassLoader) {
        val method = cl.loadClass("com.miui.home.icon.IconProvider")
            .getDeclaredMethod("getActivityIcon", LauncherActivityInfo::class.java)
        deoptimize(method)

        hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                val info = chain.getArg(0) as? LauncherActivityInfo
                // 在 HyperOS 处理之前，从 APK 直接获取原始彩色图标
                // launcherActivityInfo.getIcon(0) 返回未经 theme 修改的 AdaptiveIconDrawable
                if (info != null) {
                    try {
                        val rawIcon = info.getIcon(0)
                        if (rawIcon != null) {
                            // mutate() 创建隔离副本 — HyperOS 会在构造
                            // LayerAdaptiveIconDrawable 时原地修改 foreground。
                            // 没有 mutate(), our cached reference 会共享 HyperOS 的修改。
                            val isolated = rawIcon.constantState?.newDrawable()?.mutate()
                                ?: rawIcon.mutate()
                            val cn = info.componentName
                            if (cn != null) {
                                val ident = "${cn.packageName}/${cn.className}"
                                // Phase 3.16-A: cache isolated raw drawable for mask generation
                                IconDrawableCache.put(ident, isolated)
                                android.util.Log.d(TAG_MASK,
                                    "[RawDrawableCachePut] component=$ident drawable=${isolated.javaClass.simpleName}")
                            }
                            extractEarlyIconColor(info, isolated, "RawAPK")
                        }
                    } catch (_: Throwable) { }
                }
                chain.proceed()
            }
    }

    // ═══════════════════════════════════════════════════════════════
    // Phase 3.15: 早期颜色提取（IconProvider 加载阶段）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从刚加载完成的图标 drawable 中提取代表色并存入 [IconColorCache]。
     *
     * 此时 [drawable] 可能是：
     * - AdaptiveIconDrawable（保留完整色彩，尚未被 HyperOS theme 覆盖）
     * - BitmapDrawable
     * - 其他
     *
     * @param info LauncherActivityInfo（用于获取组件名）。
     * @param drawable 刚加载的图标（完整色彩）。
     */
    private fun extractEarlyIconColor(info: LauncherActivityInfo, drawable: Drawable, sourceLabel: String = "Adaptive") {
        try {
            val cn = info.componentName ?: return
            val component = "${cn.packageName}/${cn.className}"

            val (colorBitmap, srcLabel) = when {
                drawable is AdaptiveIconDrawable -> {
                    val w = drawable.intrinsicWidth.coerceAtLeast(1)
                    val h = drawable.intrinsicHeight.coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(canvas)
                    Pair(bmp, sourceLabel)
                }
                drawable is BitmapDrawable -> {
                    Pair(drawable.bitmap, "Bitmap")
                }
                else -> {
                    val w = drawable.intrinsicWidth.coerceAtLeast(1)
                    val h = drawable.intrinsicHeight.coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(canvas)
                    Pair(bmp, "Canvas")
                }
            }

            if (colorBitmap != null && !colorBitmap.isRecycled) {
                val extractedColor = colorExtractor.extractDominantColor(colorBitmap)
                IconColorCache.put(component, extractedColor)
                android.util.Log.d(TAG_COLOR,
                    "[EarlyColorExtract] component=$component " +
                    "drawable=${drawable.javaClass.simpleName} " +
                    "color=0x${extractedColor.toUInt().toString(16).uppercase().padStart(8, '0')} " +
                    "source=$srcLabel " +
                    "width=${colorBitmap.width} " +
                    "height=${colorBitmap.height}")
                if (drawable !is BitmapDrawable) {
                    colorBitmap.recycle()
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG_COLOR, "[EarlyColorExtractFail] component=${info.componentName} reason=${t.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Phase 3.16: Mask input diagnostics
    // ═══════════════════════════════════════════════════════════════

    /** 记录生成的 mask bitmap 质量信息。 */
    private fun diagMaskRender(mask: Bitmap, identity: String, source: Int) {
        val w = mask.width
        val h = mask.height
        var alpha255 = 0
        var nonZeroAlpha = 0
        var rgbNonZero = 0
        var lumSum = 0L
        val step = maxOf(1, minOf(w, h) / 16)
        var sampleCount = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = mask.getPixel(x, y)
                val a = (p shr 24) and 0xFF
                if (a == 255) alpha255++
                if (a > 0) {
                    nonZeroAlpha++
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    if ((r or g or b) != 0) rgbNonZero++
                    lumSum += ((0.299 * r + 0.587 * g + 0.114 * b).toInt())
                }
                sampleCount++
                x += step
            }
            y += step
        }
        val avgLum = if (nonZeroAlpha > 0) lumSum / nonZeroAlpha else 0L
        val center = mask.getPixel(w / 2, h / 2)
        val corner = mask.getPixel(0, 0)
        val srcLabel = when (source) {
            SOURCE_NATIVE -> "NATIVE"
            SOURCE_FOREGROUND -> "FOREGROUND"
            else -> "LUMA"
        }
        android.util.Log.d(TAG_MASK,
            "[MaskRender] package=$identity source=$srcLabel " +
            "w=$w h=$h alpha255=$alpha255 nonZeroAlpha=$nonZeroAlpha " +
            "rgbNonZero=$rgbNonZero avgLum=$avgLum " +
            "center=0x${Integer.toHexString(center)} " +
            "corner=0x${Integer.toHexString(corner)} " +
            "sampleCount=$sampleCount")
    }

    /** 记录进入 mask 生成管线的 drawable 结构信息。 */
    private fun diagMaskInput(drawable: Drawable, identity: String) {
        try {
            val cls = drawable.javaClass.name
            val bounds = drawable.bounds
            val sb = StringBuilder()
            sb.append("[MaskInput] package=$identity ")
            sb.append("drawable=$cls ")
            sb.append("intrinsicW=${drawable.intrinsicWidth} intrinsicH=${drawable.intrinsicHeight} ")
            sb.append("bounds=[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}] ")

            if (drawable is AdaptiveIconDrawable) {
                val bg = drawable.background
                val fg = drawable.foreground
                sb.append("bg=${bg?.javaClass?.simpleName ?: "null"} ")
                sb.append("fg=${fg?.javaClass?.simpleName ?: "null"} ")
                if (bg != null) {
                    sb.append("bgW=${bg.intrinsicWidth} bgH=${bg.intrinsicHeight} ")
                }
                if (fg != null) {
                    sb.append("fgW=${fg.intrinsicWidth} fgH=${fg.intrinsicHeight} ")
                }

                // LayerAdaptiveIconDrawable specific diagnostics
                val lcls = "com.miui.home.common.drawable.LayerAdaptiveIconDrawable"
                if (cls == lcls) {
                    try {
                        val getBgLayer = drawable.javaClass.getMethod("getBackgroundLayer")
                        val bgLayer = getBgLayer.invoke(drawable)
                        if (bgLayer != null) {
                            val getBgDrawable = bgLayer.javaClass.getMethod("getDrawable")
                            val bgLayerDrawable = getBgDrawable.invoke(bgLayer) as? Drawable
                            sb.append("layerBg=${bgLayerDrawable?.javaClass?.simpleName ?: "null"} ")
                        }
                        val getFgLayers = drawable.javaClass.getMethod("getForegroundLayers")
                        val fgLayers = getFgLayers.invoke(drawable) as? List<*>
                        sb.append("fgLayerCount=${fgLayers?.size ?: 0} ")
                    } catch (_: Throwable) { }
                }
            }

            android.util.Log.d(TAG_MASK, sb.toString())
        } catch (_: Throwable) { }
    }

    private fun processIconReplacement(chain: Chain): BitmapDrawable? {
        val d = chain.getArg(0) as? Drawable ?: return null
        val identity = resolveIdentity(chain.thisObject)

        // Phase 3.16: Mask quality diagnostics — log drawable structure before conversion
        diagMaskInput(d, identity)

        // Phase 3.12-A: 在 mask 生成之前提取原始图标颜色
        extractOriginalIconColor(d, identity)

        // Phase 3: 源优先级 — NATIVE > FOREGROUND > LUMINANCE
        val maskBitmap: Bitmap?
        val source: Int

        if (d is AdaptiveIconDrawable) {
            // ① Native monochrome layer (API 33+, 反射 hidden API)
            val mono = DrawableConverter.getMonochromeLayer(d)
            if (mono != null) {
                val raw = DrawableConverter.toRawBitmap(mono)
                maskBitmap = if (raw != null) DrawableConverter.normalizeNativeMonochrome(raw) else null
                source = SOURCE_NATIVE
                android.util.Log.d(TAG, "[toBitmap] source=NATIVE")
            } else {
                // Phase 3.16-A: 优先使用缓存的 Raw APK Drawable
                // 避免 HyperOS LayerAdaptiveIconDrawable 前景中已生成的 mask
                val rawCached = IconDrawableCache.get(identity)
                if (rawCached != null) {
                    // 渲染完整 AdaptiveIcon（background + foreground）
                    // toBitmap() 仅取 foreground → 纯白剪影无色彩对比度 → toLuminanceMask 全透明
                    // 完整渲染保留 background 色彩，提供 toLuminanceMask 所需的对比度
                    val fullRender = DrawableConverter.toRawBitmap(rawCached)
                    maskBitmap = if (fullRender != null) DrawableConverter.toLuminanceMask(fullRender) else null
                    source = SOURCE_FOREGROUND
                    android.util.Log.d(TAG_MASK, "[MaskSource] component=$identity source=RAW_APK_DRAWABLE drawable=${rawCached.javaClass.simpleName}")
                } else {
                    // ② Foreground extraction (Phase 3.1)
                    val fg = d.foreground
                    if (fg != null) {
                        val srcBounds = d.bounds
                        val w = if (srcBounds.width() > 0) srcBounds.width()
                                else d.intrinsicWidth.coerceAtLeast(1)
                        val h = if (srcBounds.height() > 0) srcBounds.height()
                                else d.intrinsicHeight.coerceAtLeast(1)
                        fg.setBounds(0, 0, w, h)
                        maskBitmap = DrawableConverter.toBitmap(fg)
                        source = SOURCE_FOREGROUND
                        android.util.Log.d(TAG_MASK, "[MaskSource] component=$identity source=FOREGROUND reason=cache_miss")
                    } else {
                        // ③ Fallback to whole drawable
                        maskBitmap = DrawableConverter.toBitmap(d)
                        source = SOURCE_LUMINANCE
                        android.util.Log.d(TAG_MASK, "[MaskSource] component=$identity source=LUMA reason=no_foreground")
                    }
                }
            }
        } else {
            maskBitmap = DrawableConverter.toBitmap(d)
            source = SOURCE_LUMINANCE
            android.util.Log.d(TAG, "[toBitmap] source=LUMINANCE")
        }

        if (maskBitmap == null) return null

        // Phase 3.16: log mask bitmap quality
        diagMaskRender(maskBitmap, identity, source)

        val cacheKey = monochromeCache.buildKey(identity, maskBitmap, source)
        if (cacheKey == null) return null

        // 缓存命中 → 直接包装为 Drawable
        val cached = monochromeCache.get(cacheKey)
        if (cached != null) {
            return MonochromeGenerator.create(cached)
        }

        // 未命中 → 存储 mask Bitmap，包装为 Drawable
        monochromeCache.put(cacheKey, maskBitmap)
        return MonochromeGenerator.create(maskBitmap)
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══════════════════════════════════════════════════════════════
    // Phase 3.14: 从 LayerAdaptiveIconDrawable 背景层提取原始色彩
    // ═══════════════════════════════════════════════════════════════

    /**
     * 当背景层为透明 ColorDrawable（monochrome 模式覆盖）时，
     * 从 LayerState 获取构造时保存的原始背景 drawable。
     * LayerState 在 LayerAdaptiveIconDrawable 构造时保存了原始 drawable 引用。
     */
    private fun extractFromLayerState(drawable: Drawable, identity: String): ExtractResult? {
        try {
            val cs = drawable.constantState ?: return null
            // LayerState 保存了 mBackground / mForeground / mBadge
            val bgField = cs.javaClass.getDeclaredField("mBackground")
            bgField.isAccessible = true
            val origBg = bgField.get(cs) as? Drawable ?: return null

            val w = origBg.intrinsicWidth.coerceAtLeast(1)
            val h = origBg.intrinsicHeight.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            origBg.setBounds(0, 0, w, h)
            origBg.draw(canvas)
            val color = colorExtractor.extractDominantColor(bmp)
            bmp.recycle()

            android.util.Log.d(TAG_COLOR,
                "[LayerAdaptiveColor] package=$identity " +
                "layerStateBg=${origBg.javaClass.simpleName} " +
                "color=0x${color.toUInt().toString(16).uppercase().padStart(8, '0')} " +
                "source=LayerState renderWidth=$w renderHeight=$h")

            return ExtractResult(color, "LayerState", w, h)
        } catch (t: Throwable) {
            android.util.Log.w(TAG_COLOR, "[LayerAdaptiveColor] LayerState extraction failed: ${t.message}")
            return null
        }
    }

    /**
     * 当背景层为透明 ColorDrawable（monochrome 模式覆盖）时，
     * 从前景层获取图标 artwork 并提取色彩。
     *
     * @return ExtractResult with color from foreground drawable, or null.
     */
    private fun extractFromForegroundLayers(drawable: Drawable, identity: String): ExtractResult? {
        try {
            val getFgMethod = drawable.javaClass.getMethod("getForegroundLayers")
            val fgLayers = getFgMethod.invoke(drawable) as? List<*> ?: return null
            if (fgLayers.isEmpty()) return null

            // 取第一个前景层
            val firstLayer = fgLayers[0] ?: return null
            val getDrawableMethod = firstLayer.javaClass.getMethod("getDrawable")
            val fgDrawable = getDrawableMethod.invoke(firstLayer) as? Drawable ?: return null

            val w = fgDrawable.intrinsicWidth.coerceAtLeast(1)
            val h = fgDrawable.intrinsicHeight.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            fgDrawable.setBounds(0, 0, w, h)
            fgDrawable.draw(canvas)
            val color = colorExtractor.extractDominantColor(bmp)
            bmp.recycle()

            android.util.Log.d(TAG_COLOR,
                "[LayerAdaptiveColor] package=$identity " +
                "layerFg=${fgDrawable.javaClass.simpleName} " +
                "color=0x${color.toUInt().toString(16).uppercase().padStart(8, '0')} " +
                "source=Foreground renderWidth=$w renderHeight=$h")

            return ExtractResult(color, "Foreground", w, h)
        } catch (t: Throwable) {
            android.util.Log.w(TAG_COLOR, "[LayerAdaptiveColor] foreground extraction failed: ${t.message}")
            return null
        }
    }

    /**
     * 从 [LayerAdaptiveIconDrawable] 的背景层提取图标代表色。
     *
     * HyperOS 将原始 AdaptiveIconDrawable 存入 LayerAdaptiveIconDrawable 的
     * mBackgroundLayer。渲染整个 LayerAdaptiveIconDrawable 会产生黑色（因为
     * monochrome foreground mask 覆盖在背景之上），所以必须直接访问背景层。
     *
     * @return ARGB color int，或 null 表示不是 LayerAdaptiveIconDrawable。
     */
    private fun extractLayerAdaptiveColor(drawable: Drawable, identity: String): Int? {
        // 通过类名字符串检测（避免 instanceof，因为类在 launcher 私有 classloader 中）
        val className = drawable.javaClass.name
        if (className != LAYER_ADAPTIVE_CLASS) return null

        try {
            // 反射获取 backgroundLayer（public API: getBackgroundLayer()）
            val getBgMethod = drawable.javaClass.getMethod("getBackgroundLayer")
            val bgLayer = getBgMethod.invoke(drawable) ?: return null

            // 反射获取原始 drawable（public API: Layer.getDrawable()）
            val getDrawableMethod = bgLayer.javaClass.getMethod("getDrawable")
            val bgDrawable = getDrawableMethod.invoke(bgLayer) as? Drawable ?: return null

            // 根据背景 drawable 类型提取颜色
            val result: ExtractResult = when {
                bgDrawable is AdaptiveIconDrawable -> {
                    // 原始彩色 AdaptiveIconDrawable — 完整渲染获取色彩
                    val w = bgDrawable.intrinsicWidth.coerceAtLeast(1)
                    val h = bgDrawable.intrinsicHeight.coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    bgDrawable.setBounds(0, 0, w, h)
                    bgDrawable.draw(canvas)
                    val color = colorExtractor.extractDominantColor(bmp)
                    bmp.recycle()
                    ExtractResult(color, "Adaptive", w, h)
                }
                bgDrawable is ColorDrawable && (bgDrawable.color ushr 24) == 0 -> {
                    // ColorDrawable 背景透明 → monochrome 模式覆盖了颜色
                    // 尝试从 LayerState 获取原始背景（保存于构造时）
                    extractFromLayerState(drawable, identity)
                        ?: ExtractResult(bgDrawable.color, "ColorDrawable", 0, 0)
                }
                bgDrawable is ColorDrawable -> {
                    // ColorDrawable 有不透明颜色 → 系统主题色或 adaptive 背景色
                    ExtractResult(bgDrawable.color, "ColorDrawable", 0, 0)
                }
                else -> {
                    // 其他类型 — 尝试 Canvas 渲染
                    val w = bgDrawable.intrinsicWidth.coerceAtLeast(1)
                    val h = bgDrawable.intrinsicHeight.coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    bgDrawable.setBounds(0, 0, w, h)
                    bgDrawable.draw(canvas)
                    val color = colorExtractor.extractDominantColor(bmp)
                    bmp.recycle()
                    ExtractResult(color, "Fallback", w, h)
                }
            }

            android.util.Log.d(TAG_COLOR,
                "[LayerAdaptiveColor] package=$identity " +
                "layerBg=${bgDrawable.javaClass.simpleName} " +
                "color=0x${result.color.toUInt().toString(16).uppercase().padStart(8, '0')} " +
                "source=${result.source}" +
                (if (result.renderWidth > 0) " renderWidth=${result.renderWidth} renderHeight=${result.renderHeight}" else ""))

            return result.color
        } catch (t: Throwable) {
            android.util.Log.w(TAG_COLOR, "[LayerAdaptiveColor] failed for $identity: ${t.message}")
            return null
        }
    }

    /** 颜色提取结果数据类。 */
    private data class ExtractResult(
        val color: Int,
        val source: String,
        val renderWidth: Int,
        val renderHeight: Int
    )

    /**
     * 从原始 [Drawable]（未经 [DrawableConverter.toBitmap] 或 luminance mask 处理）
     * 中提取代表色。优先尝试 LayerAdaptive 背景层，回退到通用渲染。
     *
     * @param drawable 原始图标 Drawable（色彩未被销毁）。
     * @param identity 组件标识（用于日志）。
     */
    private fun extractOriginalIconColor(drawable: Drawable, identity: String) {
        try {
            // Phase 3.15 优先: 检查早期提取缓存
            val cachedColor = IconColorCache.get(identity)
            if (cachedColor != null) {
                android.util.Log.d(TAG_COLOR,
                    "[ColorCacheHit] component=$identity " +
                    "color=0x${cachedColor.toUInt().toString(16).uppercase().padStart(8, '0')}")
                return
            }

            // Phase 3.14: 从 LayerAdaptiveIconDrawable 背景层提取
            val layerColor = extractLayerAdaptiveColor(drawable, identity)
            if (layerColor != null) return

            // 回退: 通用渲染 → PixelStyleColorExtractor
            val (colorBitmap, sourceLabel) = when {
                drawable is AdaptiveIconDrawable -> {
                    val w = drawable.intrinsicWidth.coerceAtLeast(1)
                    val h = drawable.intrinsicHeight.coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(canvas)
                    Pair(bmp, "Adaptive")
                }
                drawable is BitmapDrawable -> {
                    Pair(drawable.bitmap, "Bitmap")
                }
                else -> {
                    val w = drawable.intrinsicWidth.coerceAtLeast(1)
                    val h = drawable.intrinsicHeight.coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(canvas)
                    Pair(bmp, "Canvas")
                }
            }

            if (colorBitmap != null && !colorBitmap.isRecycled) {
                val extractedColor = colorExtractor.extractDominantColor(colorBitmap)
                android.util.Log.d(TAG_COLOR,
                    "[ColorExtract] package=$identity " +
                    "drawable=${drawable.javaClass.simpleName} " +
                    "color=0x${extractedColor.toUInt().toString(16).uppercase().padStart(8, '0')} " +
                    "source=$sourceLabel " +
                    "width=${colorBitmap.width} " +
                    "height=${colorBitmap.height}")
                if (drawable !is BitmapDrawable) {
                    colorBitmap.recycle()
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG_COLOR, "[ColorExtract] failed for $identity: ${t.message}")
        }
    }

    // ── Source constants (Phase 3) — 见 companion object ─────────────

    /**
     * 通过反射解析组件身份标识，优先级：
     * 1. getComponentName() → "pkg/cls"
     * 2. getClassName() → "pkg/cls"
     * 3. getPackageName() → "pkg"
     * 4. "unknown"
     *
     * 任何步骤失败静默回退到下一级。
     */
    private fun resolveIdentity(target: Any?): String {
        if (target == null) return "unknown"
        return try {
            val getShortcutInfo = cachedGetShortcutInfo ?: run {
                target.javaClass.methods
                    .firstOrNull { it.name == "getShortcutInfo" && it.parameterCount == 0 }
                    ?.also { cachedGetShortcutInfo = it }
                    ?: return "unknown"
            }
            val shortcutInfo = getShortcutInfo.invoke(target)
                ?: return "unknown"

            // 1. getComponentName()
            val componentName = try {
                val method = cachedGetComponentName ?: run {
                    shortcutInfo.javaClass.methods
                        .firstOrNull { it.name == "getComponentName" && it.parameterCount == 0 }
                        ?: null
                }
                if (method != null) { cachedGetComponentName = method; method.invoke(shortcutInfo) } else null
            } catch (_: Throwable) { null }
            if (componentName != null) {
                val cls = componentName.javaClass.getMethod("getClassName").invoke(componentName) as? String ?: ""
                val pkg = componentName.javaClass.getMethod("getPackageName").invoke(componentName) as? String ?: ""
                return "$pkg/$cls"
            }

            // 2. getClassName()
            val className = try {
                val method = cachedGetClassName ?: run {
                    shortcutInfo.javaClass.methods
                        .firstOrNull { it.name == "getClassName" && it.parameterCount == 0 }
                        ?: null
                }
                if (method != null) { cachedGetClassName = method; method.invoke(shortcutInfo) as? String } else null
            } catch (_: Throwable) { null }
            if (!className.isNullOrBlank()) return className

            // 3. getPackageName()
            val pkg = try {
                val method = cachedGetPackageName ?: run {
                    shortcutInfo.javaClass.methods
                        .firstOrNull { it.name == "getPackageName" && it.parameterCount == 0 }
                        ?: null
                }
                if (method != null) { cachedGetPackageName = method; method.invoke(shortcutInfo) as? String } else null
            } catch (_: Throwable) { null }
            pkg ?: "unknown"
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "[setIconDrawable] resolveIdentity failed: ${t.message}")
            "unknown"
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Utility
    // ═══════════════════════════════════════════════════════════════

    companion object {
        // Phase 2.5: 反射 Method 缓存，避免每次全量扫描 javaClass.methods
        // 首次 use 后只读；null 表示待解析
        @Volatile
        private var cachedGetShortcutInfo: java.lang.reflect.Method? = null
        @Volatile
        private var cachedGetComponentName: java.lang.reflect.Method? = null
        @Volatile
        private var cachedGetClassName: java.lang.reflect.Method? = null
        @Volatile
        private var cachedGetPackageName: java.lang.reflect.Method? = null

        // Phase 3 source constants
        const val SOURCE_NATIVE = 1
        const val SOURCE_FOREGROUND = 2
        const val SOURCE_LUMINANCE = 3

        // Phase 3.14: HyperOS LayerAdaptiveIconDrawable 全限定类名
        const val LAYER_ADAPTIVE_CLASS = "com.miui.home.common.drawable.LayerAdaptiveIconDrawable"
    }
}
