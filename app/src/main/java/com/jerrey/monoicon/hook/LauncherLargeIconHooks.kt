package com.jerrey.monoicon.hook

import android.graphics.drawable.Drawable
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.theme.render.ColoredMonochromeDrawable
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field

/** Keeps HyperOS 3 large icons as live, dimension-aware MonoIcon drawables. */
internal object LauncherLargeIconHooks {
    private const val SHORTCUT_ICON = "com.miui.home.launcher.ShortcutIcon"
    private const val BIG_SHORTCUT_ICON = "com.miui.home.launcher.bigicon.BigShortcutIcon"

    private class Geometry(
        val bigIconClass: Class<*>,
        val width: Field,
        val height: Field,
    )

    @Volatile private var geometry: Geometry? = null

    fun install(api: XposedInterface, cl: ClassLoader) {
        val shortcutClass = cl.loadClass(SHORTCUT_ICON)
        val bigIconClass = cl.loadClass(BIG_SHORTCUT_ICON)
        // BigShortcutIcon.updateIconDrawableSize() sets these public inherited
        // fields from DeviceConfigs.getMiuiWidgetSizeSpec(spanX, spanY, true).
        // Read the launcher layout instead of assuming that 2x1 is exactly 2:1.
        geometry = Geometry(
            bigIconClass,
            shortcutClass.getField("mLauncherIconWidth"),
            shortcutClass.getField("mLauncherIconHeight"),
        )

        HookRegistry.install("LargeIconRoundedDrawable", required = false) {
            val method = bigIconClass.getDeclaredMethod("roundedIcon", Drawable::class.java)
            api.deoptimize(method)
            api.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val drawable = chain.getArg(0)
                    if (ConfigManager.isEnabled() && drawable is ColoredMonochromeDrawable) {
                        // The original method rasterizes at the current bounds,
                        // losing the live palette and our animation type marker.
                        drawable
                    } else {
                        chain.proceed()
                    }
                }
        }

        HookRegistry.install("LargeIconUninstallDrawable", required = false) {
            val infoClass = cl.loadClass("com.miui.home.launcher.ShortcutInfo")
            val spanX = infoClass.getField("spanX")
            val spanY = infoClass.getField("spanY")
            val dimensions = requireNotNull(geometry)
            val method = shortcutClass.getDeclaredMethod(
                "roundedBigIcon",
                Drawable::class.java,
                infoClass,
                java.lang.Float.TYPE,
            )
            api.deoptimize(method)
            api.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val drawable = chain.getArg(0)
                    if (ConfigManager.isEnabled() && drawable is ColoredMonochromeDrawable) {
                        // The uninstall dialog uses a normal ShortcutIcon view.
                        // Read its ShortcutInfo spans so square 2x2 icons keep
                        // the large shape even if the input lost its size marker.
                        val adapted = runCatching {
                            val info = chain.getArg(1) ?: return@runCatching null
                            if (spanX.getInt(info) <= 1 && spanY.getInt(info) <= 1) {
                                return@runCatching null
                            }
                            val width = dimensions.width.getInt(chain.thisObject)
                            val height = dimensions.height.getInt(chain.thisObject)
                            if (width <= 0 || height <= 0) return@runCatching null
                            drawable.forLargeIcon(width, height)
                        }.getOrNull()
                        if (adapted != null) return@intercept adapted
                    }
                    chain.proceed()
                }
        }
    }

    fun isLargeIcon(view: Any?): Boolean =
        view != null && geometry?.bigIconClass?.isInstance(view) == true

    /** A fresh clone with the actual large-icon size, including square 2x2 tiles. */
    fun forView(view: Any?, drawable: Drawable): Drawable {
        if (drawable !is ColoredMonochromeDrawable || view == null) return drawable
        val accessors = geometry ?: return drawable
        if (!accessors.bigIconClass.isInstance(view)) return drawable
        return try {
            val width = accessors.width.getInt(view)
            val height = accessors.height.getInt(view)
            if (width > 0 && height > 0) drawable.forLargeIcon(width, height) else drawable
        } catch (_: Throwable) {
            drawable
        }
    }
}
