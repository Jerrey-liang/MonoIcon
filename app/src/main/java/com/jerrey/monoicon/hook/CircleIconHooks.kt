package com.jerrey.monoicon.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
 * 4. [installComposeInset] — `composeIconForDefaultIcon()` output is redrawn at
 *    the AOSP-normalized size (`IconNormalizer` scale ≈ 0.913) so MIUI's own
 *    icons carry the same transparent padding as MonoIcon's.
 *
 * All four are **optional** hooks: a missing MIUI member is reported in the
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
    private val loggedComposeInset = AtomicBoolean(false)

    /**
     * Installs the optional hooks (all pass-through while the toggle is off).
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
        HookRegistry.install("CircleIconComposeInset", required = false) {
            installComposeInset(api, cl)
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

    // ── Hook 4: IconCustomizer.composeIconForDefaultIcon(IconConfig, Bitmap) ──

    /**
     * Applies the AOSP whole-icon normalization to MIUI's own composition.
     *
     * Our injected mask (hook 1) only *clips* MIUI's composed icon, so without
     * this step MIUI-side icons would stay full-bleed while MonoIcon-rendered
     * icons are inset. `BaseIconFactory.drawIconBitmap()` shrinks the whole
     * icon by `IconNormalizer`'s scale and leaves the ring transparent; the
     * same is done here by redrawing the composed bitmap into
     * [CircleIconShape.insetBounds].
     *
     * Only the mask-path composer is covered: the theme-bitmap composer
     * (`composeIcon`) would need its own bitmap stage, and a bitmap mask can
     * only crop content, never scale it.
     *
     * Known interaction: when MonoIcon has no cached raw drawable for an icon,
     * its mask tiers fall back to the display drawable — which is now this
     * inset bitmap — so that (rare) glyph comes out ~9% smaller. The raw
     * drawable path (`IconDrawableCache`, the normal case) is unaffected.
     */
    private fun installComposeInset(api: XposedInterface, cl: ClassLoader) {
        val clazz = cl.loadClass(CLASS_ICON_CUSTOMIZER)
        val method = clazz.declaredMethods.firstOrNull { candidate ->
            candidate.name == "composeIconForDefaultIcon" &&
                candidate.parameterTypes.size == 2 &&
                Bitmap::class.java.isAssignableFrom(candidate.parameterTypes[1])
        } ?: clazz.getDeclaredMethod(
            "composeIconForDefaultIcon",
            cl.loadClass("$CLASS_ICON_CUSTOMIZER\$IconConfig"),
            Bitmap::class.java,
        )
        api.deoptimize(method)

        api.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                val result = try {
                    chain.proceed()
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "[CircleIconComposeInset] proceed threw: ${t.message}")
                    null
                }
                val original = result as? Bitmap ?: return@intercept result
                if (!active()) return@intercept original

                val inset = try {
                    insetComposedIcon(original)
                } catch (_: Throwable) {
                    null
                } ?: return@intercept original

                if (loggedComposeInset.compareAndSet(false, true)) {
                    logd(
                        TAG,
                        "[CircleIconComposeInset] ${original.width}px → " +
                            "${inset.width}px icon, scale=${CircleIconShape.aospScale()}",
                    )
                }
                inset
            }
    }

    /**
     * Redraws a composed icon at the AOSP-normalized size, centred, with the
     * surrounding ring left transparent. Returns null when there is nothing to
     * do (scale 1 / empty bitmap).
     */
    private fun insetComposedIcon(source: Bitmap): Bitmap? {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return null
        val full = Rect(0, 0, width, height)
        val content = CircleIconShape.insetBounds(full)
        if (content.width() <= 0 || content.height() <= 0 || content == full) return null

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.density = source.density
        val canvas = Canvas(out)
        canvas.drawBitmap(
            source,
            null,
            RectF(
                content.left.toFloat(),
                content.top.toFloat(),
                content.right.toFloat(),
                content.bottom.toFloat(),
            ),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        canvas.setBitmap(null)
        return out
    }
}
