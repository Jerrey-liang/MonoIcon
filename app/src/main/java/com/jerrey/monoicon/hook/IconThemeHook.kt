package com.jerrey.monoicon.hook

import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import com.jerrey.monoicon.cache.MonochromeCache
import com.jerrey.monoicon.image.DrawableConverter
import com.jerrey.monoicon.image.MonochromeGenerator
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

private const val TAG = "MonoIcon.Hook"
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
        val total = 6

        ok += safeInstall("getMonochrome") { installGetMonochrome(cl) }
        ok += safeInstall("isSupportMonochrome") { installIsSupportMonochrome(cl) }
        ok += safeInstall("isMonoEnable") { installIsMonoEnable(cl) }
        ok += safeInstall("getColor") { installGetColor(cl) }
        ok += safeInstall("setIconDrawable") { installSetIconDrawable(cl) }
        ok += safeInstall("folderSetImageDrawable") { installFolderSetImageDrawable(cl) }

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
    // Hook 6: FolderPreviewIconView.setImageDrawable(Drawable)
    //
    // 文件夹预览图标通过 ImageView.setImageDrawable() 设置，绕过
    // ShortcutIcon.setIconDrawable()。此 hook 生成白色 RGB 掩码
    // (ImageView 不 tint，需要自带颜色)。
    // ═══════════════════════════════════════════════════════════════

    private fun installFolderSetImageDrawable(cl: ClassLoader) {
        // refreshIconDrawable is the actual entry point — setImageDrawable delegates
        // to it, and the super call inside refreshIconDrawable bypasses our hook
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

                    val mask = DrawableConverter.toBitmap(d)
                    if (mask == null) return@intercept chain.proceed()

                    // 掩码格式: RGB=0, alpha=shape — 与 ImageView 渲染兼容
                    val replacement = MonochromeGenerator.create(mask)
                    return@intercept chain.proceed(
                        if (replacement != null) arrayOf<Any>(replacement) else chain.args.toTypedArray()
                    )
                } catch (t: Throwable) {
                    android.util.Log.e(TAG, "[folderSetImageDrawable] failed, falling back: ${t.message}")
                    return@intercept chain.proceed()
                } finally {
                    val elapsed = (System.nanoTime() - start) / 1_000_000L
                    stats.record("folderSetImageDrawable", elapsed)
                }
            }
    }


    /**
     * 生成 monochrome 替换 drawable，或返回 null（不可替换/失败）。
     *
     * 流程：解析 identity → 缓存查找（Bitmap mask） → 未命中则转换并存入缓存。
     * 抛出异常由调用方 catch，回落原始 drawable。
     */
    private fun processIconReplacement(chain: Chain): BitmapDrawable? {
        val d = chain.getArg(0) as? Drawable ?: return null
        val identity = resolveIdentity(chain.thisObject)

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
                // ② Foreground extraction (Phase 3.1)
                val fg = d.foreground
                if (fg != null) {
                    // 安全设置 bounds（不用 intrinsic，可能为 -1）
                    val srcBounds = d.bounds
                    val w = if (srcBounds.width() > 0) srcBounds.width()
                            else d.intrinsicWidth.coerceAtLeast(1)
                    val h = if (srcBounds.height() > 0) srcBounds.height()
                            else d.intrinsicHeight.coerceAtLeast(1)
                    fg.setBounds(0, 0, w, h)
                    maskBitmap = DrawableConverter.toBitmap(fg)
                    source = SOURCE_FOREGROUND
                    android.util.Log.d(TAG, "[toBitmap] source=FOREGROUND")
                } else {
                    // ③ Fallback to whole drawable
                    maskBitmap = DrawableConverter.toBitmap(d)
                    source = SOURCE_LUMINANCE
                    android.util.Log.d(TAG, "[toBitmap] source=LUMINANCE")
                }
            }
        } else {
            maskBitmap = DrawableConverter.toBitmap(d)
            source = SOURCE_LUMINANCE
            android.util.Log.d(TAG, "[toBitmap] source=LUMINANCE")
        }

        if (maskBitmap == null) return null

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
    }
}
