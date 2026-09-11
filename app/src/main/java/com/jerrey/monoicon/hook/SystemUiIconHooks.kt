package com.jerrey.monoicon.hook

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import com.jerrey.monoicon.color.IconDrawableCache
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.logging.logd
import com.jerrey.monoicon.logging.loge
import com.jerrey.monoicon.logging.logw
import com.jerrey.monoicon.theme.color.dynamic.PixelMonetColorEngine
import com.jerrey.monoicon.theme.mask.LawniconsAssetSource
import com.jerrey.monoicon.theme.render.ColoredMonochromeDrawable
import com.jerrey.monoicon.theme.render.ThemedIconBuilder
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Notification app-icon hooks for the SystemUI process (Phase 11).
 *
 * HyperOS draws a notification's **app icon** (not the small icon) through MIUI's
 * `com.miui.systemui.graphics.AppIconsManager`, which has two consumers:
 *
 * - `getAppIconInner(pkg, userId, appInfo, pm)` → `ExpandedNotification.mAppIcon`
 *   → `Sbn.mAppIcon` → notification row / lock screen / mini window / fold & flip paths.
 * - `getAppIconBitmap(userId, pkg)` → `RemoteViews.setImageViewBitmap(R.id.app_icon, …)`
 *   — the *primary visible* path of the standard MIUI notification layout, which is
 *   why a drawable-only replacement is not enough.
 *
 * Both return values are replaced with the same drawable the desktop uses
 * ([ThemedIconBuilder] + [ConfigManager.iconShape]), so notification icons match the
 * desktop (including the circle shape). Everything is optional and fail-open: a
 * missing member, an unknown package or any `Throwable` returns MIUI's original value.
 *
 * `setSmallIcon` glyphs (StatusBarIconView), large icons / avatars
 * (`NotifImageUtil.getCustomAppIcon`) and system drawables (DND, …) never pass through
 * these two methods and are therefore untouched.
 */
internal object SystemUiIconHooks {

    private const val TAG = "MonoIcon.SystemUI"

    private const val CLASS_APP_ICONS_MANAGER = "com.miui.systemui.graphics.AppIconsManager"
    private const val CLASS_ICON_CUSTOMIZER = "miui.content.res.IconCustomizer"

    /** `public final Context mContext` on `AppIconsManager`. */
    private const val FIELD_CONTEXT = "mContext"

    private const val MAX_BITMAP_CACHE = 64

    /** Lazy one-shot init guard (needs an AppIconsManager instance for its Context). */
    @Volatile
    private var initialised = false

    /** Themed notification bitmaps, keyed `"pkg|WxH|shape"` (weak, rebuilt on demand). */
    private val bitmapCache = ConcurrentHashMap<String, WeakReference<Bitmap>>()

    /** Installs the two `AppIconsManager` hooks. */
    fun install(api: XposedInterface, cl: ClassLoader) {
        val managerClass = cl.loadClass(CLASS_APP_ICONS_MANAGER)
        var installed = 0

        val drawableMethod = try {
            managerClass.getDeclaredMethod(
                "getAppIconInner",
                String::class.java,
                Int::class.javaPrimitiveType,
                ApplicationInfo::class.java,
                PackageManager::class.java,
            )
        } catch (_: Throwable) {
            null
        }
        if (drawableMethod != null) {
            installDrawableHook(api, drawableMethod)
            installed++
        }

        val bitmapMethod = try {
            managerClass.getDeclaredMethod(
                "getAppIconBitmap",
                Int::class.javaPrimitiveType,
                String::class.java,
            )
        } catch (_: Throwable) {
            null
        }
        if (bitmapMethod != null) {
            installBitmapHook(api, bitmapMethod)
            installed++
        }

        if (installed == 0) {
            throw NoSuchMethodException("AppIconsManager exposes no app-icon entry point")
        }
        android.util.Log.i(TAG, "  ✓ SystemUiNotificationIcons methods=$installed")
    }

    /**
     * Guard against MIUI re-composing our own drawable:
     * `AppIconsManager.getIconStyleDrawable()` and
     * `IconLoader.convertToThemeStyleIcon()` both call
     * `IconCustomizer.generateIconStyleDrawable(...)`, which would otherwise wrap our
     * finished icon in a second MIUI plate/mask pass.
     */
    fun installStyledIconGuard(api: XposedInterface, cl: ClassLoader) {
        val customizer = cl.loadClass(CLASS_ICON_CUSTOMIZER)
        var installed = 0
        val signatures = listOf(
            arrayOf(Drawable::class.java),
            arrayOf(Drawable::class.java, java.lang.Boolean.TYPE),
        )
        for (signature in signatures) {
            val method = try {
                customizer.getDeclaredMethod("generateIconStyleDrawable", *signature)
            } catch (_: Throwable) {
                null
            } ?: continue
            api.deoptimize(method)
            api.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain: Chain ->
                    val argument = chain.getArg(0)
                    if (isOwnThemedDrawable(argument)) argument else chain.proceed()
                }
            installed++
        }
        if (installed == 0) {
            throw NoSuchMethodException("generateIconStyleDrawable not found")
        }
        android.util.Log.i(TAG, "  ✓ SystemUiStyledIconGuard methods=$installed")
    }

    /** True when [value] is a MonoIcon drawable that must not be styled again. */
    internal fun isOwnThemedDrawable(value: Any?): Boolean = value is ColoredMonochromeDrawable

    // ── Hooks ─────────────────────────────────────────────────────────

    private fun installDrawableHook(api: XposedInterface, method: java.lang.reflect.Method) {
        api.deoptimize(method)
        api.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                if (!active()) return@intercept chain.proceed()

                val original = try {
                    chain.proceed()
                } catch (t: Throwable) {
                    logw(TAG, "[NotifIcon] proceed threw: ${t.message}")
                    null
                }
                val icon = original as? Drawable ?: return@intercept original

                val pkg = chain.getArg(0) as? String
                val themed = try {
                    themedDrawable(chain.thisObject, pkg, icon)
                } catch (t: Throwable) {
                    loge(TAG, "[NotifIcon] generate failed: ${t.message}", t)
                    null
                }
                themed ?: icon
            }
    }

    private fun installBitmapHook(api: XposedInterface, method: java.lang.reflect.Method) {
        api.deoptimize(method)
        api.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                if (!active()) return@intercept chain.proceed()

                val original = try {
                    chain.proceed()
                } catch (t: Throwable) {
                    logw(TAG, "[NotifIconBitmap] proceed threw: ${t.message}")
                    null
                }
                val bitmap = original as? Bitmap ?: return@intercept original
                if (bitmap.isRecycled) return@intercept bitmap

                val pkg = chain.getArg(1) as? String
                val themed = try {
                    themedBitmap(chain.thisObject, pkg, bitmap)
                } catch (t: Throwable) {
                    loge(TAG, "[NotifIconBitmap] generate failed: ${t.message}", t)
                    null
                }
                themed ?: bitmap
            }
    }

    /** Master switch + the notification-icon escape hatch (Phase 11). */
    private fun active(): Boolean =
        ConfigManager.isEnabled() && ConfigManager.isNotificationIconsEnabled()

    // ── Generation ────────────────────────────────────────────────────

    private fun themedDrawable(manager: Any?, pkg: String?, fallback: Drawable?): Drawable? {
        if (pkg.isNullOrEmpty()) return null
        ensureInitialised(manager)
        val source = rawIconFor(manager, pkg) ?: fallback ?: return null
        val themed = ThemedIconBuilder.build(source, pkg) ?: return null
        logd(TAG, "[NotifIcon] themed pkg=$pkg src=${themed.source}")
        return themed.drawable
    }

    /**
     * Themed bitmap for the RemoteViews path, rendered at exactly the original size
     * (and density) so MIUI's notification layout is unaffected. Cached because
     * notifications re-bind frequently.
     */
    private fun themedBitmap(manager: Any?, pkg: String?, original: Bitmap): Bitmap? {
        if (pkg.isNullOrEmpty()) return null
        val width = original.width
        val height = original.height
        if (width <= 0 || height <= 0) return null

        val key = "$pkg|${width}x$height|${ConfigManager.iconShape()}"
        bitmapCache[key]?.get()?.takeIf { !it.isRecycled }?.let { return it }

        val themed = themedDrawable(manager, pkg, null) ?: return null
        val out = rasterize(themed, width, height, original.density)

        if (bitmapCache.size >= MAX_BITMAP_CACHE) bitmapCache.clear()
        bitmapCache[key] = WeakReference(out)
        logd(TAG, "[NotifIconBitmap] rendered pkg=$pkg ${width}x$height")
        return out
    }

    /**
     * Rasterizes [drawable] at exactly [width]×[height] (density preserved) for the
     * RemoteViews bitmap slot — MIUI's notification layout depends on the original
     * bitmap geometry, so the size must not change.
     */
    internal fun rasterize(drawable: Drawable, width: Int, height: Int, density: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.density = density
        val canvas = Canvas(out)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        canvas.setBitmap(null)
        return out
    }

    /**
     * Raw APK drawable for [pkg] (the AOSP mask pipeline's preferred input), cached in
     * [IconDrawableCache] under the package identity. Falls back to null so the caller
     * can use whatever MIUI handed us.
     */
    private fun rawIconFor(manager: Any?, pkg: String): Drawable? {
        IconDrawableCache.get(pkg)?.let { return it }
        val context = contextOf(manager) ?: return null
        return try {
            val icon = context.packageManager.getApplicationIcon(pkg)
            IconDrawableCache.put(pkg, icon)
            icon
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * One-shot lazy init: SystemUI has no launcher View to piggyback on, so the
     * `AppIconsManager` instance provides the Context.
     */
    private fun ensureInitialised(manager: Any?) {
        if (initialised) return
        val context = contextOf(manager) ?: return
        try {
            PixelMonetColorEngine.init(context)
            LawniconsAssetSource.init(context)
            initialised = true
            android.util.Log.i(TAG, "initialised in ${context.packageName}")
        } catch (t: Throwable) {
            logw(TAG, "init failed: ${t.message}")
        }
    }

    private fun contextOf(manager: Any?): Context? = try {
        manager?.javaClass?.getField(FIELD_CONTEXT)?.get(manager) as? Context
    } catch (_: Throwable) {
        null
    }
}
