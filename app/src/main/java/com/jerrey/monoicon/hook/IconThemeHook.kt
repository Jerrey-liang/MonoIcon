package com.jerrey.monoicon.hook

import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
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
    // ═══════════════════════════════════════════════════════════════

    private fun installSetIconDrawable(cl: ClassLoader) {
        val method = cl.loadClass("com.miui.home.launcher.ShortcutIcon")
            .getDeclaredMethod("setIconDrawable", Drawable::class.java, Bitmap::class.java)
        deoptimize(method)

        hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                val start = System.nanoTime()
                val d = chain.getArg(0) as? Drawable
                val b = chain.getArg(1) as? Bitmap
                val desc = describeDrawable(d)
                val bmp = if (b != null) "${b.width}x${b.height}" else "null"
                android.util.Log.d(TAG, "[setIconDrawable] $desc bitmap=$bmp")

                val result = chain.proceed()
                val elapsed = (System.nanoTime() - start) / 1_000_000L
                android.util.Log.i(TAG, "[setIconDrawable] cost=${elapsed}ms")
                stats.record("setIconDrawable", elapsed)
                result
            }
    }

    // ═══════════════════════════════════════════════════════════════
    // Utility
    // ═══════════════════════════════════════════════════════════════

    companion object {
        fun describeDrawable(d: Drawable?): String {
            if (d == null) return "drawable=null"
            val sb = StringBuilder("drawable=").append(d.javaClass.simpleName)
            val flags = mutableListOf<String>()
            if (d is AdaptiveIconDrawable) flags.add("AdaptiveIcon")
            if (d is android.graphics.drawable.LayerDrawable) flags.add("Layer(${d.numberOfLayers})")
            if (d is BitmapDrawable) {
                val bmp = d.bitmap
                flags.add(if (bmp != null) "${bmp.width}x${bmp.height}" else "bitmap=null")
            }
            if (flags.isNotEmpty()) sb.append("(${flags.joinToString()})")
            sb.append(" w=${d.intrinsicWidth},h=${d.intrinsicHeight}")
            return sb.toString()
        }
    }
}
