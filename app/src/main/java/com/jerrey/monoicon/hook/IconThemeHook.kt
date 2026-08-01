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
        val total = 5

        ok += safeInstall("getMonochrome") { installGetMonochrome(cl) }
        ok += safeInstall("isSupportMonochrome") { installIsSupportMonochrome(cl) }
        ok += safeInstall("isMonoEnable") { installIsMonoEnable(cl) }
        ok += safeInstall("getColor") { installGetColor(cl) }
        ok += safeInstall("setIconDrawable") { installSetIconDrawable(cl) }

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

    /**
     * 生成 monochrome 替换 drawable，或返回 null（不可替换/失败）。
     *
     * 流程：解析包名 → 缓存查找 → 未命中则转换并存入缓存。
     * 抛出异常由调用方 catch，回落原始 drawable。
     */
    private fun processIconReplacement(chain: Chain): BitmapDrawable? {
        val d = chain.getArg(0) as? Drawable ?: return null

        // 获取包名（反射 thisObject → getShortcutInfo → getPackageName）
        val packageName = resolvePackageName(chain.thisObject)

        // 缓存优先 — 同一应用图标多次显示时直接命中
        val iconW = d.intrinsicWidth
        val iconH = d.intrinsicHeight
        val cacheKey = monochromeCache.buildKey(packageName, iconW, iconH)
        if (cacheKey == null) return null

        monochromeCache.get(cacheKey)?.let { return it }

        // 未命中 → 生成并存入缓存
        val bitmap = DrawableConverter.toBitmap(d)
        val generated = MonochromeGenerator.create(bitmap)
        if (generated != null) {
            monochromeCache.put(cacheKey, generated)
        }
        return generated
    }

    /**
     * 通过反射从 [ShortcutIcon] 实例解析应用包名。
     *
     * 调用链：`thisObject.getShortcutInfo().getPackageName()`。
     * 任何一步失败都返回 null，让调用方回落原始行为。
     *
     * ## 性能优化（Phase 2.5）
     * 缓存的 [Method] 引用避免每次调用都执行 `javaClass.methods` 全量
     * 反射扫描。首次找到后固定复用，后续调用仅 `invoke`（快一个数量级）。
     * 缓存字段位于 [companion object]（见文件底部）。
     */
    private fun resolvePackageName(target: Any?): String? {
        if (target == null) return null
        return try {
            val getShortcutInfo = cachedGetShortcutInfo ?: run {
                target.javaClass.methods
                    .firstOrNull { it.name == "getShortcutInfo" && it.parameterCount == 0 }
                    ?.also { cachedGetShortcutInfo = it }
                    ?: return null
            }
            val shortcutInfo = getShortcutInfo.invoke(target)
                ?: return null

            val getPackageName = cachedGetPackageName ?: run {
                shortcutInfo.javaClass.methods
                    .firstOrNull { it.name == "getPackageName" && it.parameterCount == 0 }
                    ?.also { cachedGetPackageName = it }
                    ?: return null
            }
            getPackageName.invoke(shortcutInfo) as? String
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "[setIconDrawable] resolvePackageName failed: ${t.message}")
            null
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
        private var cachedGetPackageName: java.lang.reflect.Method? = null
    }
}
