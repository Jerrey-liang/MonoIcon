package com.jerrey.monoicon.hook

import android.graphics.Bitmap
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.logging.logd
import com.jerrey.monoicon.theme.mask.MiuiIconShapeCompat
import com.jerrey.monoicon.theme.render.CircleIconShape
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Circular icon shape hooks (Phase 9) — "circle without an MTZ theme".
 *
 * HyperOS takes the icon silhouette from `IconCustomizer`'s parsed
 * `IconConfig.mConfigIconMask` (theme `transform_config.xml`, with a built-in
 * default). These hooks intercept that value inside the launcher process, so
 * MIUI-composed icons become circular without installing any theme:
 *
 * 1. [installEnsureConfig] — `IconCustomizer.ensureIconConfigLoaded()` is the
 *    single accessor behind every composition path and every shape getter;
 *    the returned config object is rewritten in place. Read-time mutation is
 *    deliberate: it also covers a config that was cached before these hooks
 *    were installed.
 * 2. [installMaskValue] — `getConfigIconMaskValue()` feeds the launcher's
 *    animation clip path (`PathDataIconUtil`) and the icon stroke
 *    (`IconProvider.getIconStrokeMaskPath()`), so the circle stays consistent.
 * 3. [installRawIconMask] — `getRawIcon("icon_mask.png")` is the bitmap mask
 *    used by `composeIcon()` when a user theme supplies a non-full mask
 *    (`mIsFullMask == false`); a circular mask is substituted there too.
 *
 * All three are **optional** hooks: a missing MIUI member is reported in the
 * hook status line and degrades to "no shape change". Every interceptor is
 * pass-through when the `circle_icons` toggle is off, so the default build
 * behaves exactly as before.
 */
object CircleIconHooks {

    private const val TAG = "MonoIcon.Hook"

    private const val CLASS_ICON_CUSTOMIZER = "miui.content.res.IconCustomizer"

    /** Fallback mask edge when the replaced bitmap size is unknown. */
    private const val FALLBACK_MASK_SIZE = 192

    // One-shot debug evidence (debug builds only): proves per process that the
    // MIUI-side hijack actually fired rather than the icons merely being
    // clipped by ColoredMonochromeDrawable.
    private val loggedConfig = AtomicBoolean(false)
    private val loggedMaskValue = AtomicBoolean(false)
    private val loggedThemeMask = AtomicBoolean(false)

    /**
     * Installs the three optional hooks.
     *
     * @param api The hooked-process Xposed interface (the module instance).
     * @param cl The launcher class loader (MIUI framework classes resolve
     *  through it because they are on the boot class path).
     */
    fun install(api: XposedInterface, cl: ClassLoader) {
        // Once per process: validate the circle geometry before touching MIUI.
        MiuiIconShapeCompat.selfCheck()

        HookRegistry.install("CircleIconConfig", required = false) {
            installEnsureConfig(api, cl)
        }
        HookRegistry.install("CircleIconMaskValue", required = false) {
            installMaskValue(api, cl)
        }
        HookRegistry.install("CircleIconThemeMask", required = false) {
            installRawIconMask(api, cl)
        }
    }

    /** True when the shape hooks may alter MIUI's icon config right now. */
    private fun active(): Boolean =
        ConfigManager.isCircleIconsEnabled() && !MiuiIconShapeCompat.isBypassed()

    // ── Hook 1: IconCustomizer.ensureIconConfigLoaded() ────────────────

    private fun installEnsureConfig(api: XposedInterface, cl: ClassLoader) {
        val method = cl.loadClass(CLASS_ICON_CUSTOMIZER)
            .getDeclaredMethod("ensureIconConfigLoaded")
        api.deoptimize(method)

        api.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                val config = try {
                    chain.proceed()
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "[CircleIconConfig] proceed threw: ${t.message}")
                    null
                }
                if (config != null && active()) {
                    try {
                        if (MiuiIconShapeCompat.injectMask(config) &&
                            loggedConfig.compareAndSet(false, true)
                        ) {
                            logd(TAG, "[CircleIconConfig] IconConfig.mConfigIconMask → circle")
                        }
                    } catch (_: Throwable) {
                        // never disturb icon composition
                    }
                }
                config
            }
    }

    // ── Hook 2: IconCustomizer.getConfigIconMaskValue() ────────────────

    private fun installMaskValue(api: XposedInterface, cl: ClassLoader) {
        val method = cl.loadClass(CLASS_ICON_CUSTOMIZER)
            .getDeclaredMethod("getConfigIconMaskValue")
        api.deoptimize(method)

        api.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                if (active()) {
                    if (loggedMaskValue.compareAndSet(false, true)) {
                        logd(TAG, "[CircleIconMaskValue] returning the circle mask path")
                    }
                    CircleIconShape.MASK_PATH_DATA
                } else {
                    chain.proceed()
                }
            }
    }

    // ── Hook 3: IconCustomizer.getRawIcon(String) ──────────────────────

    private fun installRawIconMask(api: XposedInterface, cl: ClassLoader) {
        val method = cl.loadClass(CLASS_ICON_CUSTOMIZER)
            .getDeclaredMethod("getRawIcon", String::class.java)
        api.deoptimize(method)

        api.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                val original = try {
                    chain.proceed()
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "[CircleIconThemeMask] proceed threw: ${t.message}")
                    null
                }
                if (!active()) return@intercept original

                val name = chain.getArg(0) as? String
                if (!MiuiIconShapeCompat.isThemeMaskName(name)) return@intercept original

                val size = (original as? Bitmap)?.width?.takeIf { it > 0 } ?: FALLBACK_MASK_SIZE
                val circle = MiuiIconShapeCompat.circleMaskCopy(size) ?: return@intercept original
                if (loggedThemeMask.compareAndSet(false, true)) {
                    logd(TAG, "[CircleIconThemeMask] icon_mask.png → circle (${size}px)")
                }
                circle
            }
    }
}
