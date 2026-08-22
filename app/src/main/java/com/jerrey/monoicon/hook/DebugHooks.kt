package com.jerrey.monoicon.hook

import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain

/**
 * Diagnostic-only hooks for MonochromeUtils (Phase 3.18-E).
 *
 * Hooks 1–4 (formerly embedded in [IconThemeHook]) are instrumentation —
 * they log HyperOS's own monochrome utility invocations but never alter
 * behavior (all pass through). Production builds do not install them.
 *
 * ## Enable
 * Flip [ENABLED] to `true` for a diagnostic build. Install order in
 * [install] matches the Phase 3.17 installHooks order.
 *
 * Tag keeps `"MonoIcon.Hook"` so logcat filtering surface is unchanged.
 */
object DebugHooks {

    /** Production default: `false` — diagnostic hooks are not installed. */
    const val ENABLED = false

    private const val TAG = "MonoIcon.Hook"

    private val stats = HookStats(TAG)

    /**
     * Installs the four MonochromeUtils diagnostic hooks, gated by [ENABLED].
     *
     * @param api The hooked-process Xposed interface (from the module instance).
     * @param cl The launcher class loader.
     */
    fun install(api: XposedInterface, cl: ClassLoader) {
        if (!ENABLED) return
        var ok = 0
        val total = 4

        ok += safeInstall(api, "getMonochrome") { installGetMonochrome(api, cl) }
        ok += safeInstall(api, "isSupportMonochrome") { installIsSupportMonochrome(api, cl) }
        ok += safeInstall(api, "isMonoEnable") { installIsMonoEnable(api, cl) }
        ok += safeInstall(api, "getColor") { installGetColor(api, cl) }

        android.util.Log.i(TAG, "DebugHooks installed: $ok/$total")
    }

    private inline fun safeInstall(
        api: XposedInterface,
        name: String,
        block: () -> Unit
    ): Int = try {
        block()
        android.util.Log.i(TAG, "  ✓ $name (debug)")
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

    private fun installGetMonochrome(api: XposedInterface, cl: ClassLoader) {
        val method = cl.loadClass("com.miui.home.icon.MonochromeUtils")
            .getDeclaredMethod("getMonochrome", AdaptiveIconDrawable::class.java)
        api.deoptimize(method)

        api.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                val start = System.nanoTime()
                val adaptiveIcon = chain.getArg(0) as? AdaptiveIconDrawable
                val desc = if (adaptiveIcon != null) {
                    "${adaptiveIcon.javaClass.simpleName}(w=${adaptiveIcon.intrinsicWidth},h=${adaptiveIcon.intrinsicHeight})"
                } else "null"
                android.util.Log.d(TAG, "[getMonochrome] drawable=$desc")

                // Phase 6.6: log-only passthrough — mask generation now lives
                // in LabMonochromeExtractor (Pixel pipeline), so the diagnostic
                // hook no longer generates its own monochrome replacement.
                val original = try {
                    chain.proceed()
                } catch (t: Throwable) {
                    android.util.Log.e(TAG, "[getMonochrome] proceed threw: ${t.message}", t)
                    null
                }
                val elapsed = (System.nanoTime() - start) / 1_000_000L
                android.util.Log.i(TAG, "[getMonochrome] result null=${original == null} cost=${elapsed}ms")
                stats.record("getMonochrome", elapsed)
                original
            }
    }

    // ═══════════════════════════════════════════════════════════════
    // Hook 2: MonochromeUtils.isSupportMonochrome()
    // ═══════════════════════════════════════════════════════════════

    private fun installIsSupportMonochrome(api: XposedInterface, cl: ClassLoader) {
        val method = cl.loadClass("com.miui.home.icon.MonochromeUtils")
            .getDeclaredMethod("isSupportMonochrome")
        api.deoptimize(method)

        api.hook(method)
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

    private fun installIsMonoEnable(api: XposedInterface, cl: ClassLoader) {
        val method = cl.loadClass("com.miui.home.icon.MonochromeUtils")
            .getDeclaredMethod("isMonoEnable")
        api.deoptimize(method)

        api.hook(method)
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

    private fun installGetColor(api: XposedInterface, cl: ClassLoader) {
        val method = cl.loadClass("com.miui.home.icon.MonochromeUtils")
            .getDeclaredMethod("getColor")
        api.deoptimize(method)

        api.hook(method)
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
}
