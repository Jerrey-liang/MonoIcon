package com.jerrey.monoicon.theme.color.dynamic

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.logging.LogcatLogger
import com.jerrey.monoicon.logging.logd

/** Runtime adapter for the independent Google Material Color Utilities 2025 engine. */
object PixelMonetColorEngine {
    private const val TAG = "MonoIcon.Monet"
    private const val WHICH_SYSTEM = WallpaperManager.FLAG_SYSTEM
    private const val MIUI_MANAGER = "com.miui.miwallpaper.MiuiWallpaperManager"
    private const val FALLBACK_SOURCE = 0xFF4285F4.toInt()

    private val LIGHT_FALLBACK = IconThemeColors(0xFF3C4043.toInt(), 0xFFF5F5F5.toInt(), fallbackPalette(FALLBACK_SOURCE))
    private val DARK_FALLBACK = IconThemeColors(0xFFDADCE0.toInt(), 0xFF202124.toInt(), fallbackPalette(FALLBACK_SOURCE))

    @Volatile private var launcherContext: Context? = null
    @Volatile private var lastLoggedColors: Long = Long.MIN_VALUE
    @Volatile private var lastLoggedWallpaper: String? = null

    fun init(context: Context) {
        launcherContext = context.applicationContext
        logd(
            TAG,
            "initialized package=${launcherContext?.packageName} spec=2025 " +
                "variant=${ConfigManager.getVariantId()}",
        )
        logWallpaperSnapshot(launcherContext!!, "init")
    }

    fun invalidate() {
        lastLoggedColors = Long.MIN_VALUE
        lastLoggedWallpaper = null
        logd(TAG, "invalidated")
    }

    fun getIconColors(): IconThemeColors {
        val context = launcherContext ?: return LIGHT_FALLBACK
        val dark = isSystemDark(context)
        val variantId = ConfigManager.getVariantId()
        readMiuiWallpaperColors()?.let { return calculate("miui_wallpaper_service", it, dark, variantId) }
        readFrameworkWallpaperColors(context)?.let { return calculate("android_wallpaper_manager", it, dark, variantId) }
        logd(TAG, "source=material2025_fixed_fallback dark=$dark sourceColor=${format(FALLBACK_SOURCE)}")
        return if (dark) DARK_FALLBACK else LIGHT_FALLBACK
    }

    private fun calculate(source: String, colors: WallpaperColors, dark: Boolean, variantId: String): IconThemeColors {
        logWallpaper(colors, source)
        val sourceColor = selectSourceColor(colors)
        if (sourceColor == null) return if (dark) DARK_FALLBACK else LIGHT_FALLBACK
        val result = Material2025ColorEngine.iconColorsForSource(
            sourceColor,
            dark,
            Material2025ColorEngine.variantFromId(variantId),
        )
        logColors(source, dark, variantId, sourceColor, result.foreground, result.background)
        return result
    }

    private fun selectSourceColor(colors: WallpaperColors): Int? {
        val candidates = listOf(colors.primaryColor, colors.secondaryColor, colors.tertiaryColor)
        return candidates.asSequence().filterNotNull().map { it.toArgb() }
            .firstOrNull { com.jerrey.monoicon.material2025.hct.Hct.fromInt(it).chroma >= 5.0 }
            ?: candidates.asSequence().filterNotNull().map { it.toArgb() }.firstOrNull()
    }

    private fun readMiuiWallpaperColors(): WallpaperColors? = try {
        val type = Class.forName(MIUI_MANAGER)
        val field = type.getDeclaredField("sInstance").apply { isAccessible = true }
        val instance = field.get(null) ?: return null
        type.getMethod("getMiuiWallpaperColors", Int::class.javaPrimitiveType)
            .invoke(instance, WHICH_SYSTEM) as? WallpaperColors
    } catch (t: Throwable) {
        logd(TAG, "miui_wallpaper_service unavailable=${t.javaClass.simpleName}")
        null
    }

    private fun readFrameworkWallpaperColors(context: Context): WallpaperColors? = try {
        WallpaperManager.getInstance(context).getWallpaperColors(WHICH_SYSTEM)
    } catch (t: Throwable) {
        logd(TAG, "android_wallpaper_manager unavailable=${t.javaClass.simpleName}")
        null
    }

    private fun logColors(source: String, dark: Boolean, variantId: String, sourceColor: Int, foreground: Int, background: Int) {
        if (!LogcatLogger.isDebugEnabled) return
        val packed = (foreground.toLong() shl 32) xor (background.toLong() and 0xFFFFFFFFL)
        if (packed == lastLoggedColors) return
        lastLoggedColors = packed
        logd(TAG, "source=$source spec=2025 variant=$variantId platform=Phone dark=$dark sourceColor=${format(sourceColor)} fg=${format(foreground)} bg=${format(background)}")
    }

    private fun logWallpaperSnapshot(context: Context, phase: String) {
        if (!LogcatLogger.isDebugEnabled) return
        readFrameworkWallpaperColors(context)?.let { logWallpaper(it, "android_wallpaper_manager phase=$phase") }
            ?: logd(TAG, "wallpaper_colors phase=$phase source=android_wallpaper_manager value=null")
    }

    private fun logWallpaper(colors: WallpaperColors, source: String) {
        if (!LogcatLogger.isDebugEnabled) return
        val snapshot = "$source primary=${formatNullable(colors.primaryColor?.toArgb())} secondary=${formatNullable(colors.secondaryColor?.toArgb())} tertiary=${formatNullable(colors.tertiaryColor?.toArgb())} hints=${colors.colorHints}"
        if (snapshot == lastLoggedWallpaper) return
        lastLoggedWallpaper = snapshot
        logd(TAG, "wallpaper_colors $snapshot")
    }

    private fun isSystemDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun format(color: Int): String = "0x${color.toUInt().toString(16).uppercase().padStart(8, '0')}"
    private fun formatNullable(color: Int?): String = color?.let(::format) ?: "null"

    private fun fallbackPalette(seed: Int): MonetPalette = MonetPalette(
        TonePalette.generate(seed), TonePalette.generate(seed), TonePalette.generate(seed),
        TonePalette.generate(seed), TonePalette.generate(seed),
    )
}
